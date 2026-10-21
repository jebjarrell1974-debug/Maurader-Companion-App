package com.kybercypher.marauder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kybercypher.marauder.capture.CaptureWriter
import com.kybercypher.marauder.device.MarauderCommand
import com.kybercypher.marauder.device.MarauderSession
import com.kybercypher.marauder.protocol.PcapAssembler
import com.kybercypher.marauder.transport.ConnectionState
import com.kybercypher.marauder.transport.UsbTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val transport = UsbTransport(app)
    val session = MarauderSession(transport, viewModelScope)

    private val _streamEnabled = MutableStateFlow(false)
    val streamEnabled = _streamEnabled.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    private var writer: CaptureWriter? = null

    init {
        transport.register()
        session.start()
        viewModelScope.launch {
            transport.state.collect { st ->
                if (st is ConnectionState.Connected) {
                    // Handshake uses the firmware's own machine protocol, which
                    // is already upstream — no forked firmware required.
                    session.handshake()
                    session.refreshSettings()
                } else if (st is ConnectionState.Disconnected || st is ConnectionState.Failed) {
                    session.onDisconnected()
                    closeWriter()
                }
            }
        }
        viewModelScope.launch {
            session.anomalies.collect { _notice.value = it }
        }
    }

    fun connect() { transport.requestConnect() }

    fun setStream(enabled: Boolean) { _streamEnabled.value = enabled }

    fun run(cmd: MarauderCommand, values: Map<String, String> = emptyMap()) {
        viewModelScope.launch {
            // -serial only means anything on a command that opens a capture.
            val stream = _streamEnabled.value && cmd.startsScan
            val assembler = if (stream) {
                closeWriter()
                CaptureWriter(getApplication(), cmd.id).also {
                    it.open(); writer = it
                }.let { PcapAssembler(it.stream()) }
            } else null

            session.startScan(cmd, values, stream, assembler)
            if (stream && session.settings.value["SavePCAP"]?.asBool != true) {
                // Buffer.cpp returns early when SavePCAP is false, which makes
                // -serial a silent no-op. Never let that fail quietly.
                _notice.value = "SavePCAP could not be enabled — streaming will produce nothing."
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            session.stopScan()
            val w = writer
            if (w != null) {
                w.close()
                val n = w.exportAll()
                _notice.value = if (n > 0) "Exported $n capture file(s) to Download/Marauder" else null
                writer = null
            }
        }
    }

    fun extendTransmit() { session.extendTransmitWindow() }

    fun refreshAccessPoints() { viewModelScope.launch { session.refreshAccessPoints() } }

    fun dismissNotice() { _notice.value = null }

    private fun closeWriter() { writer?.close(); writer = null }

    override fun onCleared() {
        transport.unregister()
        transport.disconnect()
        closeWriter()
        super.onCleared()
    }
}
