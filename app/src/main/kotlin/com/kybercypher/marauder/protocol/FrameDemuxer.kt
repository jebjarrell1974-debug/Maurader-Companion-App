package com.kybercypher.marauder.protocol

/**
 * Splits the Marauder's serial stream into console text and binary pcap frames.
 *
 * THE REASON THIS CLASS EXISTS, AND WHY IT BUFFERS ACROSS READS:
 *
 * Measured on real hardware at wire saturation with 64-byte USB reads: of 206
 * frame markers, 15 were split across a read boundary — a ~7% split rate, worst
 * case '[' arriving in one read and 'BUF/CLOSE]' in the next. A demuxer that
 * scans each read independently misses ~7% of frame terminations and desyncs
 * permanently. Three earlier probe runs reported "no splits observed" and were
 * simply underpowered (expected counts 3.4, 1.7, 0.2).
 *
 * Measured frame payloads: min 685 B, max 7678 B, mean 3130 B. The firmware's
 * theoretical maximum is BUF_SIZE*2 = 16384 B of payload. Do NOT size buffers at
 * SNAP_LEN (4096) — that bounds one record, and a frame carries many.
 *
 * Also measured: 0 of 143 frames contained injected console text, with 10,645
 * bytes of concurrent text interleaved around them. Arduino's HardwareSerial
 * holds its lock across a write, so payloads are clean. Framing is fragile at
 * the transport layer; payloads are sound at the application layer.
 *
 * Not thread-safe. Feed it from exactly one thread.
 */
class FrameDemuxer(
    /** Hard ceiling before we declare desync. Firmware max payload is 16384. */
    private val maxFrameBytes: Int = 32 * 1024,
    /** Flush an unterminated text run this long as a line, so text can't grow unbounded. */
    private val maxTextRunBytes: Int = 8 * 1024,
) {
    private companion object {
        val BEGIN = "[BUF/BEGIN]".toByteArray(Charsets.US_ASCII)
        val CLOSE = "[BUF/CLOSE]".toByteArray(Charsets.US_ASCII)
        const val INITIAL_CAPACITY = 64 * 1024
    }

    private enum class Mode { TEXT, BINARY }

    private var mode = Mode.TEXT
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var len = 0

    /** Diagnostics, surfaced in the UI rather than hidden. */
    var framesEmitted = 0L; private set
    var desyncs = 0L; private set
    var bytesDropped = 0L; private set

    fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size - offset): List<DeviceMessage> {
        append(data, offset, count)
        val out = ArrayList<DeviceMessage>(8)
        drain(out)
        return out
    }

    /** Call on disconnect to release anything complete that is still buffered. */
    fun flush(): List<DeviceMessage> {
        val out = ArrayList<DeviceMessage>(2)
        if (mode == Mode.TEXT && len > 0) {
            emitLines(len, out)   // consumes what it emits
            if (len > 0) {
                out.add(DeviceMessage.Text(String(buf, 0, len, Charsets.UTF_8)))
                len = 0
            }
        } else if (mode == Mode.BINARY && len > 0) {
            out.add(DeviceMessage.Desync("stream ended mid-frame", len))
            desyncs++; bytesDropped += len
            len = 0
            mode = Mode.TEXT
        }
        return out
    }

    fun reset() {
        len = 0
        mode = Mode.TEXT
    }

    // ---------------------------------------------------------------- internals

    private fun append(data: ByteArray, offset: Int, count: Int) {
        ensure(len + count)
        System.arraycopy(data, offset, buf, len, count)
        len += count
    }

    private fun ensure(needed: Int) {
        if (needed <= buf.size) return
        var cap = buf.size
        while (cap < needed) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    private fun consume(upTo: Int) {
        if (upTo <= 0) return
        val remaining = len - upTo
        if (remaining > 0) System.arraycopy(buf, upTo, buf, 0, remaining)
        len = remaining
    }

    private fun drain(out: MutableList<DeviceMessage>) {
        while (true) {
            if (mode == Mode.TEXT) {
                val cidx = indexOf(CLOSE, 0)
                val bidx = indexOf(BEGIN, 0)

                // Residue from a desynced frame. A literal "[BUF/CLOSE]" cannot
                // occur in legitimate console output, so swallow it silently
                // rather than letting it pollute the console view.
                if (cidx >= 0 && (bidx < 0 || cidx < bidx)) {
                    val consumed = emitLines(cidx, out)
                    consume((cidx - consumed) + CLOSE.size)
                    continue
                }

                if (bidx >= 0) {
                    // emitLines consumes what it emits, so the marker index
                    // shifts left by exactly that many bytes.
                    val consumed = emitLines(bidx, out)
                    val stray = bidx - consumed
                    if (stray > 0) {
                        // A partial line immediately before the marker. Emit it
                        // rather than gluing it onto post-frame text.
                        out.add(DeviceMessage.Text(String(buf, 0, stray, Charsets.UTF_8)))
                    }
                    consume(stray + BEGIN.size)
                    mode = Mode.BINARY
                    continue
                }

                // KEY INVARIANT: neither marker contains a newline byte, so a
                // newline can never fall inside a marker. Every complete line is
                // therefore safe to emit immediately; only the trailing run
                // after the last newline must be withheld, since that is where a
                // partial marker would sit. (An earlier version held back a
                // fixed 10-byte margin and swallowed whole lines at the tail.)
                emitLines(len, out)
                val tailGuard = BEGIN.size - 1
                if (len - tailGuard >= maxTextRunBytes) {
                    // Pathological: a very long run with no newline and no
                    // marker. Flush it, but keep the guard bytes intact.
                    val cut = len - tailGuard
                    out.add(DeviceMessage.Text(String(buf, 0, cut, Charsets.UTF_8)))
                    consume(cut)
                }
                return
            } else {
                val idx = indexOf(CLOSE, 0)
                if (idx >= 0) {
                    out.add(DeviceMessage.Frame(buf.copyOfRange(0, idx)))
                    framesEmitted++
                    consume(idx + CLOSE.size)
                    mode = Mode.TEXT
                    continue
                }
                if (len > maxFrameBytes) {
                    val keep = CLOSE.size - 1
                    val dropped = len - keep
                    out.add(DeviceMessage.Desync("frame exceeded $maxFrameBytes B with no close marker", dropped))
                    desyncs++; bytesDropped += dropped
                    consume(dropped)
                    mode = Mode.TEXT
                    continue
                }
                return
            }
        }
    }

    /**
     * Emits every complete line that ends before [limit], consumes exactly those
     * bytes, and returns how many bytes it consumed. Callers holding an index
     * into the buffer MUST subtract the return value from it — the buffer is
     * compacted underneath them.
     */
    private fun emitLines(limit: Int, out: MutableList<DeviceMessage>): Int {
        var start = 0
        var i = 0
        while (i < limit) {
            if (buf[i] == '\n'.code.toByte()) {
                var end = i
                if (end > start && buf[end - 1] == '\r'.code.toByte()) end--
                out.add(DeviceMessage.Text(String(buf, start, end - start, Charsets.UTF_8)))
                start = i + 1
            }
            i++
        }
        if (start > 0) consume(start)
        return start
    }

    private fun indexOf(pattern: ByteArray, from: Int): Int {
        val first = pattern[0]
        val last = len - pattern.size
        var i = from
        outer@ while (i <= last) {
            if (buf[i] == first) {
                var j = 1
                while (j < pattern.size) {
                    if (buf[i + j] != pattern[j]) { i++; continue@outer }
                    j++
                }
                return i
            }
            i++
        }
        return -1
    }
}
