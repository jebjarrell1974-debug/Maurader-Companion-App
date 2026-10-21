package com.kybercypher.marauder.protocol

/**
 * One unit of output from the Marauder's single serial stream.
 *
 * The stream carries two interleaved things: ASCII console text, and binary pcap
 * frames wrapped in [BUF/BEGIN] ... [BUF/CLOSE]. [FrameDemuxer] separates them.
 */
sealed interface DeviceMessage {

    /** A complete console line, newline stripped. May be empty. */
    @JvmInline
    value class Text(val line: String) : DeviceMessage

    /**
     * The payload of one complete [BUF/BEGIN]..[BUF/CLOSE] frame.
     *
     * The first frame of a capture begins with the 24-byte pcap global header;
     * later frames continue at a record boundary. Concatenating every frame
     * payload in order yields a well-formed .pcap file.
     */
    class Frame(val payload: ByteArray) : DeviceMessage

    /**
     * The demuxer lost the stream and recovered. Surfaced rather than swallowed:
     * silent resync is how a capture corruption bug hides for months.
     */
    data class Desync(val reason: String, val bytesDropped: Int) : DeviceMessage
}
