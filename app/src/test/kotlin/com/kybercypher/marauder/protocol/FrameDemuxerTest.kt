package com.kybercypher.marauder.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * These vectors exist because of a measurement, not a hunch.
 *
 * On real hardware at wire saturation with 64-byte USB reads, 15 of 206 frame
 * markers were split across a read boundary — a ~7% rate, worst case '[' in one
 * read and 'BUF/CLOSE]' in the next. Three earlier probe runs reported "no
 * splits" and were simply underpowered (expected counts 3.4, 1.7, 0.2).
 *
 * splitAtEveryOffset() is the test that matters. If the demuxer is ever
 * "optimised" to scan each read independently, it fails immediately.
 */
class FrameDemuxerTest {

    private val BEGIN = "[BUF/BEGIN]".toByteArray()
    private val CLOSE = "[BUF/CLOSE]".toByteArray()

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun globalHeader(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le32(0xA1B2C3D4.toInt()))
        o.write(byteArrayOf(2, 0, 4, 0))      // version 2.4
        o.write(le32(0)); o.write(le32(0))    // thiszone, sigfigs
        o.write(le32(4096))                   // snaplen
        o.write(le32(105))                    // LINKTYPE_IEEE802_11
        return o.toByteArray()
    }

    private fun record(ts: Int, len: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le32(ts)); o.write(le32(0)); o.write(le32(len)); o.write(le32(len))
        o.write(ByteArray(len) { (it % 256).toByte() })
        return o.toByteArray()
    }

    private fun payload(records: Int = 5): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(globalHeader())
        repeat(records) { o.write(record(it, 200 + it * 13)) }
        return o.toByteArray()
    }

    private fun stream(payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("line before\n".toByteArray())
        o.write(BEGIN); o.write(payload); o.write(CLOSE)
        o.write("line after\n".toByteArray())
        return o.toByteArray()
    }

    private fun drain(d: FrameDemuxer, data: ByteArray, chunk: Int): List<DeviceMessage> {
        val out = ArrayList<DeviceMessage>()
        var i = 0
        while (i < data.size) {
            val n = minOf(chunk, data.size - i)
            out += d.feed(data.copyOfRange(i, i + n))
            i += n
        }
        return out
    }

    private fun frames(msgs: List<DeviceMessage>) = msgs.filterIsInstance<DeviceMessage.Frame>()
    private fun texts(msgs: List<DeviceMessage>) = msgs.filterIsInstance<DeviceMessage.Text>().map { it.line }

    @Test fun plainTextLinesMixedLineEndings() {
        val out = drain(FrameDemuxer(), "hello\nworld\r\n#list -a\n".toByteArray(), 64)
        assertEquals(listOf("hello", "world", "#list -a"), texts(out))
    }

    @Test fun singleFrameBetweenText() {
        val p = payload()
        val out = drain(FrameDemuxer(), stream(p), 64)
        assertEquals(1, frames(out).size)
        assertArrayEquals(p, frames(out)[0].payload)
        assertEquals(listOf("line before", "line after"), texts(out))
    }

    /** THE CRITICAL TEST. Every possible split point must recover the frame exactly. */
    @Test fun splitAtEveryOffset() {
        val p = payload()
        val s = stream(p)
        for (split in 1 until s.size) {
            val d = FrameDemuxer()
            val out = d.feed(s.copyOfRange(0, split)) + d.feed(s.copyOfRange(split, s.size))
            val f = frames(out)
            assertEquals("split=$split frame count", 1, f.size)
            assertArrayEquals("split=$split payload", p, f[0].payload)
            assertTrue("split=$split text", texts(out).containsAll(listOf("line before", "line after")))
        }
    }

    @Test fun chunkedAtRealUsbPacketSizes() {
        val p = payload()
        for (chunk in intArrayOf(1, 7, 64, 512)) {
            val out = drain(FrameDemuxer(), stream(p), chunk)
            assertEquals("chunk=$chunk", 1, frames(out).size)
            assertArrayEquals("chunk=$chunk", p, frames(out)[0].payload)
            assertTrue("chunk=$chunk", texts(out).contains("line after"))
        }
    }

    @Test fun markerLookalikesInsidePayloadSurvive() {
        val o = ByteArrayOutputStream()
        o.write(globalHeader())
        val body = "[BUF/BEGI".toByteArray() + byteArrayOf(0x58) +
                   "[BUF/CLOS".toByteArray() + byteArrayOf(0x59) + ByteArray(80)
        o.write(le32(1)); o.write(le32(0)); o.write(le32(body.size)); o.write(le32(body.size)); o.write(body)
        val p = o.toByteArray()
        val s = BEGIN + p + CLOSE + "ok\n".toByteArray()
        val out = drain(FrameDemuxer(), s, 7)
        assertEquals(1, frames(out).size)
        assertArrayEquals(p, frames(out)[0].payload)
    }

    @Test fun manyFramesReassembleToAValidPcap() {
        val whole = ByteArrayOutputStream()
        val wire = ByteArrayOutputStream()
        repeat(20) { f ->
            val body = ByteArrayOutputStream()
            if (f == 0) body.write(globalHeader())
            repeat(6) { i -> body.write(record(f * 10 + i, 100 + i * 7)) }
            whole.write(body.toByteArray())
            wire.write("-6${f % 10} Ch: 6 AA:BB:CC:DD:EE:F${f % 10} ESSID: net$f\n".toByteArray())
            wire.write(BEGIN); wire.write(body.toByteArray()); wire.write(CLOSE)
        }
        val out = drain(FrameDemuxer(), wire.toByteArray(), 64)
        val f = frames(out)
        assertEquals(20, f.size)
        assertEquals(20, texts(out).size)
        val rebuilt = ByteArrayOutputStream().apply { f.forEach { write(it.payload) } }.toByteArray()
        assertArrayEquals(whole.toByteArray(), rebuilt)
        assertEquals(0xA1B2C3D4.toInt(),
            (rebuilt[0].toInt() and 0xFF) or ((rebuilt[1].toInt() and 0xFF) shl 8) or
            ((rebuilt[2].toInt() and 0xFF) shl 16) or ((rebuilt[3].toInt() and 0xFF) shl 24))
    }

    @Test fun fuzzRandomChunkings() {
        val p = payload(8)
        val s = stream(p)
        val rnd = Random(1234)
        repeat(300) {
            val d = FrameDemuxer()
            val out = ArrayList<DeviceMessage>()
            var i = 0
            while (i < s.size) {
                val n = minOf(rnd.nextInt(1, 200), s.size - i)
                out += d.feed(s.copyOfRange(i, i + n)); i += n
            }
            assertEquals(1, frames(out).size)
            assertArrayEquals(p, frames(out)[0].payload)
        }
    }

    @Test fun desyncIsSurfacedThenRecovers() {
        val d = FrameDemuxer(maxFrameBytes = 2048)
        val out1 = d.feed(BEGIN + ByteArray(5000) { 1 })
        assertEquals(1, out1.filterIsInstance<DeviceMessage.Desync>().size)

        val p = payload()
        val out2 = d.feed(CLOSE + "recovered\n".toByteArray() + BEGIN + p + CLOSE + "after\n".toByteArray())
        assertEquals(1, frames(out2).size)
        assertArrayEquals(p, frames(out2)[0].payload)
        // The stray CLOSE must be swallowed, not shown as console noise.
        assertTrue(texts(out2).none { it.contains("[BUF/") })
        assertTrue(texts(out2).contains("after"))
    }

    @Test fun partialMarkerAtTailIsNeverEmittedAsText() {
        val d = FrameDemuxer()
        val out = d.feed("abc\n[BUF/".toByteArray())
        assertEquals(listOf("abc"), texts(out))
        val p = payload()
        val out2 = d.feed("BEGIN]".toByteArray() + p + CLOSE)
        assertEquals(1, frames(out2).size)
        assertArrayEquals(p, frames(out2)[0].payload)
    }

    @Test fun pcapAssemblerValidatesRecords() {
        val a = PcapAssembler(null)
        assertEquals(null, a.accept(payload(4)))
        assertEquals(4, a.recordsSeen)
        assertEquals(4096, a.snaplen)
        assertEquals(105, a.linkType)

        // incl_len != orig_len must be caught: the firmware always writes them equal.
        val bad = ByteArrayOutputStream()
        bad.write(le32(0)); bad.write(le32(0)); bad.write(le32(100)); bad.write(le32(99))
        bad.write(ByteArray(100))
        val b = PcapAssembler(null)
        b.accept(globalHeader())
        assertTrue(b.accept(bad.toByteArray())!!.contains("incl_len"))
    }
}
