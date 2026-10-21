package com.kybercypher.marauder.device

import com.kybercypher.marauder.protocol.*   // ScanClass comes from this package's Commands.kt
import com.kybercypher.marauder.transport.ConnectionState
import com.kybercypher.marauder.transport.UsbTransport
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the board is doing right now, mirrored host-side. */
data class ScanState(
    val running: Boolean = false,
    val label: String = "",
    val scanClass: ScanClass = ScanClass.NONE,
    val streaming: Boolean = false,
    /** True while the radio is EMITTING, not merely listening. */
    val transmitting: Boolean = false,
    /** Seconds left on the auto-stop, or null when it does not apply. */
    val secondsRemaining: Int? = null,
)

data class CaptureStats(
    val frames: Long = 0,
    val records: Long = 0,
    val bytes: Long = 0,
    val desyncs: Long = 0,
    val malformedFrames: Long = 0,
    val startedAtMs: Long = 0,
) {
    val bytesPerSec: Double
        get() {
            if (startedAtMs == 0L) return 0.0
            val secs = (System.currentTimeMillis() - startedAtMs) / 1000.0
            return if (secs <= 0.5) 0.0 else bytes / secs
        }
    /**
     * 115200 8N1 carries 11,520 B/s. Measured sustained best on real hardware was
     * 7,691 B/s = 67% of the wire. Past roughly 40 packets/sec the firmware's
     * 2x8KB ring fills faster than the wire can drain it and it drops frames
     * SILENTLY — Buffer.cpp:92, with the diagnostic commented out. We cannot
     * detect that loss, so we say so rather than implying fidelity.
     */
    val wireUtilisation: Double get() = (bytesPerSec / 11520.0).coerceIn(0.0, 1.0)
    val likelyDropping: Boolean get() = wireUtilisation > 0.6
}

/**
 * Owns the connection, the demuxer, the parser, and the mirrored device state.
 *
 * THE TWO RULES THAT KEEP THIS STABLE, both measured on real hardware:
 *
 * 1. While a scan is running, every scan/attack command is SILENTLY DISCARDED
 *    by the firmware (CommandLine.cpp:755). So this class tracks scan state and
 *    stops the scan first instead of firing and hoping.
 *
 * 2. Polling the board while it captures starves the capture path. Measured A/B:
 *    injecting a console command every 0.5s dropped the capture from 24.28 to
 *    0.30 records/sec — an 81x collapse — and after the first 19% of the run the
 *    binary output stopped entirely while console commands kept being answered.
 *    So: NO automatic polling while a scan is active. Refresh is user-initiated,
 *    and the UI says what it costs.
 */
class MarauderSession(
    private val transport: UsbTransport,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Bounded console ring. Terminal views must never hold unbounded history. */
        const val CONSOLE_CAPACITY = 2000
        /** 15 Hz. See start(). */
        const val UI_TICK_MS = 66L
        /**
         * Hard ceiling on any transmitting mode. The firmware runs until
         * stopscan, so without this a forgotten tab keeps emitting from a phone
         * in a pocket indefinitely. That is the failure mode worth engineering
         * against; everything else about these modes is recoverable.
         */
        const val TRANSMIT_AUTO_STOP_SECONDS = 60
        private const val RESPONSE_TIMEOUT_MS = 4000L
    }

    private val demuxer = FrameDemuxer()
    private var autoStopJob: Job? = null
    private var assembler: PcapAssembler? = null

    val connection: StateFlow<ConnectionState> = transport.state

    private val _scan = MutableStateFlow(ScanState())
    val scan = _scan.asStateFlow()

    /**
     * Console scrollback. mutableStateOf with neverEqualPolicy, fed by a fixed
     * 15 Hz tick, NOT a StateFlow updated per line. Appending to a StateFlow<List>
     * per line is O(n) per line, which at 200 lines/sec is quadratic and will
     * melt the UI thread. neverEqualPolicy also skips the O(n) equals entirely.
     */
    private val consoleBuffer = ConsoleBuffer(CONSOLE_CAPACITY)
    private val _console = mutableStateOf<List<LogLine>>(emptyList(), neverEqualPolicy())
    val console: State<List<LogLine>> get() = _console

    private val _accessPoints = MutableStateFlow<List<AccessPoint>>(emptyList())
    val accessPoints = _accessPoints.asStateFlow()

    private val _ssids = MutableStateFlow<List<SsidEntry>>(emptyList())
    val ssids = _ssids.asStateFlow()

    private val _ble = MutableStateFlow<List<BleDevice>>(emptyList())
    val ble = _ble.asStateFlow()

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations = _stations.asStateFlow()

    private val _settings = MutableStateFlow<Map<String, SettingEntry>>(emptyMap())
    val settings = _settings.asStateFlow()

    private val _sightings = MutableStateFlow<List<ApSighting>>(emptyList())
    val sightings = _sightings.asStateFlow()

    private val _info = MutableStateFlow<ProtocolInfo?>(null)
    val info = _info.asStateFlow()

    private val _capture = MutableStateFlow(CaptureStats())
    val capture = _capture.asStateFlow()

    /**
     * What the session is doing right now, as a short human-readable step, or
     * null when idle. Starting a scan is several serial round trips; naming the
     * step is more useful than a bare spinner because it tells you WHICH part
     * is slow when something stalls.
     */
    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()

    /** Emitted when a frame fails validation, so anomalies surface instead of hiding. */
    private val _anomalies = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val anomalies = _anomalies.asSharedFlow()

    /** Echoes, used to terminate responses that the firmware does not terminate. */
    private val echoes = Channel<String>(Channel.BUFFERED)

    // Accumulators for a list response in flight.
    private val apAccum = mutableListOf<AccessPoint>()
    private val ssidAccum = mutableListOf<SsidEntry>()
    private val bleAccum = mutableListOf<BleDevice>()
    private val stationAccum = mutableListOf<Station>()
    private val settingAccum = mutableListOf<String>()
    private var collecting: String? = null

    fun start() {
        // Stage 1+2: ingest and parse, off the main thread.
        scope.launch(Dispatchers.Default) {
            transport.bytes.collect { chunk ->
                for (msg in demuxer.feed(chunk)) handle(msg)
            }
        }
        // Stage 3: the backpressure valve. A pull-based tick means the UI decides
        // when it wants data, so a queue of pending UI updates never forms.
        // 15 Hz is above the ~10 Hz threshold where text updates look like
        // discrete steps, and 4x under the 60 Hz frame budget so a tick never
        // competes with a scroll fling.
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                consoleBuffer.snapshotIfDirty()?.let { _console.value = it }
                delay(UI_TICK_MS)
            }
        }
    }

    private fun kindOf(p: ParsedLine): LineKind = when (p) {
        is ParsedLine.CommandEcho -> LineKind.ECHO
        is ParsedLine.Sighting, is ParsedLine.Probe -> LineKind.SIGHTING
        is ParsedLine.Ap, is ParsedLine.Ble, is ParsedLine.Simple,
        is ParsedLine.StationRow, is ParsedLine.Flipper, is ParsedLine.Counts -> LineKind.LIST
        is ParsedLine.SettingField -> LineKind.SETTING
        is ParsedLine.ScanStarted, is ParsedLine.ScanStopped -> LineKind.STATE
        is ParsedLine.Machine -> LineKind.MACHINE
        else -> LineKind.PLAIN
    }

    // ------------------------------------------------------------------ input

    private suspend fun handle(msg: DeviceMessage) {
        when (msg) {
            is DeviceMessage.Text -> handleLine(msg.line)
            is DeviceMessage.Frame -> {
                val a = assembler ?: return
                val complaint = a.accept(msg.payload)
                if (complaint != null) _anomalies.tryEmit(complaint)
                _capture.value = _capture.value.copy(
                    frames = a.framesSeen,
                    records = a.recordsSeen,
                    bytes = a.bytesWritten,
                    malformedFrames = a.malformedFrames,
                    desyncs = demuxer.desyncs,
                )
            }
            is DeviceMessage.Desync -> {
                _anomalies.tryEmit("resync: ${msg.reason} (${msg.bytesDropped} B dropped)")
                _capture.value = _capture.value.copy(desyncs = demuxer.desyncs)
            }
        }
    }

    private suspend fun handleLine(line: String) {
        val parsed = LineParser.parse(line)
        appendConsole(line, kindOf(parsed))
        when (val p = parsed) {
            is ParsedLine.CommandEcho -> echoes.trySend(p.command)

            is ParsedLine.Machine -> MachineProto.parse(p.json)?.let { _info.value = it }

            is ParsedLine.ScanStopped -> _scan.value = ScanState()

            is ParsedLine.ScanStarted ->
                _scan.value = _scan.value.copy(running = true, label = p.what)

            is ParsedLine.Ap -> if (collecting == "list_a") apAccum += p.value
            is ParsedLine.Ble -> if (collecting == "list_b") bleAccum += p.value
            is ParsedLine.StationRow -> if (collecting == "list_c") stationAccum += p.value
            is ParsedLine.Simple -> if (collecting == "list_s") {
                ssidAccum += SsidEntry(p.value.index, p.value.value, p.raw.endsWith(" (selected)"))
            }
            is ParsedLine.SettingField -> if (collecting == "settings") settingAccum += "${p.key}\u0000${p.value}"

            is ParsedLine.Sighting -> {
                // Live telemetry. Arrives whether or not -serial is on — this is
                // what lets the default mode feel live without streaming pcap.
                val cur = _sightings.value
                _sightings.value = (listOf(p.value) + cur).take(400)
            }

            else -> Unit
        }
    }

    private fun appendConsole(line: String, kind: LineKind) = consoleBuffer.add(line, kind)

    fun clearConsole() = consoleBuffer.clear()

    // ----------------------------------------------------------------- output

    /** Sends a command and waits for its own echo, so responses are bounded. */
    private suspend fun sendAwaitingEcho(command: String): Boolean {
        while (echoes.tryReceive().isSuccess) { /* drain stale */ }
        transport.write(command)
        return withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            var matched = false
            while (!matched) {
                val echo = echoes.receive()
                if (echo.trim() == command.trim()) matched = true
            }
            matched
        } ?: false
    }

    /**
     * Runs a command whose response has no terminator by following it with a
     * cheap sentinel. Only `list -a`, `-s` and `-c` print a trailing count; every
     * other list variant ends with nothing at all, so without this the reader
     * would have to guess with a timeout.
     */
    private suspend fun runCollecting(tag: String, command: String) {
        collecting = tag
        val prior = _busy.value
        _busy.value = command
        try {
            sendAwaitingEcho(command)
            // The sentinel's echo bounds a response the firmware never
            // terminates. Only list -a/-s/-c print a trailing count.
            sendAwaitingEcho(Commands.SENTINEL)
        } finally {
            collecting = null
            _busy.value = prior
        }
    }

    suspend fun handshake(): ProtocolInfo? {
        _busy.value = "handshake"
        val tx = MachineProto.newTransactionId()
        sendAwaitingEcho(MachineProto.handshakeCommand(tx))
        _busy.value = null
        return _info.value
    }

    private var settingsLoaded = false

    suspend fun refreshSettings() {
        settingAccum.clear()
        runCollecting("settings", Commands.SETTINGS.render())
        // Fields arrive as Name/Type/Value triples in order.
        val out = linkedMapOf<String, SettingEntry>()
        var name: String? = null; var type: String? = null
        for (entry in settingAccum) {
            val (k, v) = entry.split('\u0000', limit = 2)
            when (k) {
                "Name" -> { name = v; type = null }
                "Type" -> type = v
                "Value" -> name?.let { out[it] = SettingEntry(it, type ?: "", v) }
            }
        }
        _settings.value = out
        settingsLoaded = true
    }

    suspend fun refreshAccessPoints() {
        apAccum.clear(); runCollecting("list_a", Commands.LIST_AP.render())
        _accessPoints.value = apAccum.toList()
    }

    suspend fun refreshSsids() {
        ssidAccum.clear(); runCollecting("list_s", Commands.LIST_SSIDS.render())
        _ssids.value = ssidAccum.toList()
    }

    suspend fun refreshBle() {
        bleAccum.clear(); runCollecting("list_b", Commands.LIST_BLE.render())
        _ble.value = bleAccum.toList()
    }

    suspend fun refreshStations() {
        stationAccum.clear(); runCollecting("list_c", Commands.LIST_STATIONS.render())
        _stations.value = stationAccum.toList()
    }

    suspend fun stopScan() {
        _busy.value = "stopscan"
        autoStopJob?.cancel()
        autoStopJob = null
        sendAwaitingEcho(Commands.STOPSCAN.render())
        _scan.value = ScanState()
        _busy.value = null
    }

    /**
     * THE SAVEPCAP TRAP: Buffer.cpp:62 returns early if the SavePCAP setting is
     * false, which makes -serial a silent no-op — no error, no output, the
     * capture simply never arrives. Verify and enable before any streaming run.
     */
    suspend fun ensureSavePcapEnabled(): Boolean {
        // Settings are dumped once at connect and cached. Re-dumping on every
        // capture start cost two extra round trips for a value that only this
        // app changes. `settings` is also the single most expensive command on
        // the board (621 B plus an NVS read), so it is worth not repeating.
        if (!settingsLoaded) {
            _busy.value = "reading settings"
            refreshSettings()
        }
        if (_settings.value["SavePCAP"]?.asBool == true) return true
        _busy.value = "enabling SavePCAP"
        sendAwaitingEcho("settings -s SavePCAP enable")
        refreshSettings()
        return _settings.value["SavePCAP"]?.asBool == true
    }

    /**
     * Starts a scan. Honours the firmware's gating rather than fighting it.
     * [stream] appends -serial; the default is OFF, which keeps the SD capture
     * complete and still delivers live telemetry.
     */
    suspend fun startScan(
        cmd: MarauderCommand,
        values: Map<String, String> = emptyMap(),
        stream: Boolean = false,
        sink: PcapAssembler? = null,
    ) {
        try {
            if (_scan.value.running && cmd.gating == Gating.REQUIRES_IDLE) {
                _busy.value = "stopping current scan"
                stopScan()
            }
            if (stream) {
                assembler = sink
                _capture.value = CaptureStats(startedAtMs = System.currentTimeMillis())
                ensureSavePcapEnabled()
            } else {
                assembler = null
            }
            val rendered = cmd.render(values)
            _busy.value = rendered
            // -serial only means anything on a command that opens a capture.
            sendAwaitingEcho(if (cmd.startsScan) Commands.withSerial(rendered, stream) else rendered)
            _scan.value = ScanState(
                running = cmd.startsScan,
                label = cmd.label,
                scanClass = cmd.scanClass,
                streaming = stream,
                transmitting = cmd.transmits,
                secondsRemaining = if (cmd.transmits) TRANSMIT_AUTO_STOP_SECONDS else null,
            )
            if (cmd.transmits) armAutoStop()
        } finally {
            _busy.value = null
        }
    }

    /**
     * Counts the transmit window down and stops the radio when it expires.
     * Cancelled by any manual stop. Extend() restarts the window rather than
     * adding to it, so the displayed number is always the real remaining time.
     */
    private fun armAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            var left = TRANSMIT_AUTO_STOP_SECONDS
            while (left > 0 && isActive) {
                delay(1000)
                left--
                _scan.value = _scan.value.copy(secondsRemaining = left)
            }
            if (isActive) {
                // Clear the handle FIRST: stopScan() cancels autoStopJob, and
                // that is the job we are currently running inside.
                autoStopJob = null
                stopScan()
            }
        }
    }

    /** Restart the 60s window without dropping the carrier. */
    fun extendTransmitWindow() {
        if (!_scan.value.transmitting) return
        _scan.value = _scan.value.copy(secondsRemaining = TRANSMIT_AUTO_STOP_SECONDS)
        armAutoStop()
    }

    suspend fun raw(command: String) {
        _busy.value = command
        try { sendAwaitingEcho(command) } finally { _busy.value = null }
    }

    fun onDisconnected() {
        autoStopJob?.cancel()
        autoStopJob = null
        demuxer.flush()
        demuxer.reset()
        _scan.value = ScanState()
    }
}
