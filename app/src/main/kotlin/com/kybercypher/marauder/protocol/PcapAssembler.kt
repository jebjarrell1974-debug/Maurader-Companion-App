package com.kybercypher.marauder.protocol

import java.io.OutputStream

/**
 * Validates and accounts for the pcap records inside demuxed frames, and writes
 * the bytes straight through to a file.
 *
 * Record layout, from Buffer.cpp:114 — ts_sec, ts_usec, incl_len, orig_len, all
 * little-endian, followed by the payload. The firmware writes `len` into BOTH
 * length fields, which gives a strong validation invariant: incl_len must equal
 * orig_len, always. Timestamps are uptime from micros(), not epoch.
 *
 * Validation is defence in depth, not architecture. Measured on hardware, 0 of
 * 143 frames contained injected console text — payloads are clean because
 * HardwareSerial holds its lock across a write. We check anyway, cheaply, and
 * report rather than hide any anomaly.
 */
class PcapAssembler(private val sink: OutputStream?) {

    companion object {
        const val GLOBAL_HEADER_SIZE = 24
        const val RECORD_HEADER_SIZE = 16
        const val MAGIC_LE = 0xA1B2C3D4.toInt()
        const val MAGIC_NANO = 0xA1B23C4D.toInt()
        /** configs.h: SNAP_LEN 4096 on PSRAM boards (v8). */
        const val SNAP_LEN = 4096
    }

    var bytesWritten = 0L; private set
    var recordsSeen = 0L; private set
    var framesSeen = 0L; private set
    var malformedFrames = 0L; private set
    var snaplen: Int? = null; private set
    var linkType: Int? = null; private set
    private var sawGlobalHeader = false

    /** Returns null if the frame validated, or a human-readable complaint if not. */
    fun accept(frame: ByteArray): String? {
        framesSeen++
        var off = 0
        var complaint: String? = null

        if (!sawGlobalHeader && frame.size >= GLOBAL_HEADER_SIZE) {
            val magic = le32(frame, 0)
            if (magic == MAGIC_LE || magic == MAGIC_NANO) {
                snaplen = le32(frame, 16)
                linkType = le32(frame, 20)
                sawGlobalHeader = true
                off = GLOBAL_HEADER_SIZE
            }
        }

        while (off + RECORD_HEADER_SIZE <= frame.size) {
            val tsUsec = le32(frame, off + 4)
            val incl = le32(frame, off + 8)
            val orig = le32(frame, off + 12)
            if (incl != orig) { complaint = "incl_len $incl != orig_len $orig at offset $off"; break }
            if (incl <= 0 || incl > SNAP_LEN) { complaint = "implausible incl_len $incl at offset $off"; break }
            if (tsUsec < 0 || tsUsec >= 1_000_000) { complaint = "ts_usec $tsUsec out of range at offset $off"; break }
            if (off + RECORD_HEADER_SIZE + incl > frame.size) { complaint = "record truncated at offset $off"; break }
            off += RECORD_HEADER_SIZE + incl
            recordsSeen++
        }

        if (complaint != null) malformedFrames++

        // Write the whole frame regardless. A malformed tail is still better in
        // the file than silently discarded, and Wireshark will say where it ends.
        sink?.write(frame)
        bytesWritten += frame.size
        return complaint
    }

    fun flush() = sink?.flush()

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or
        ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or
        ((b[i + 3].toInt() and 0xFF) shl 24)
}
