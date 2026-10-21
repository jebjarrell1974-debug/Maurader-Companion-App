package com.kybercypher.marauder.capture

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes capture bytes to disk, then exports on completion.
 *
 * Live target is getExternalFilesDir as a REAL java.io.File: no permission
 * needed, and it keeps FileOutputStream, getFD().sync() and cheap length()
 * queries. None of that is clean through a ContentResolver URI, and it keeps
 * FUSE out of the hot path.
 *
 * On completion we copy into MediaStore.Downloads, which needs no permission for
 * files the app owns, lands user-visible in Files, and survives uninstall —
 * unlike app-specific storage, which is deleted with the app and is not
 * browsable at all on Android 11+.
 *
 * flush() is cheap and frequent; sync() is expensive and rare. flush() only
 * moves bytes from the Java buffer to the OS — it is NOT durability. sync()
 * blocks and causes flash write amplification, so it runs on a timer and on
 * every stop path, never per packet.
 */
class CaptureWriter(private val context: Context, private val label: String) {

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val SYNC_INTERVAL_MS = 30_000L
        /** Segment so a kill costs seconds, not hours. */
        private const val SEGMENT_BYTES = 32L * 1024 * 1024
    }

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val dir = File(context.getExternalFilesDir(null), "captures").apply { mkdirs() }

    private var segment = 0
    private var file: File? = null
    private var raw: FileOutputStream? = null
    private var out: BufferedOutputStream? = null
    private var segmentBytes = 0L
    private var lastSync = System.currentTimeMillis()

    val segments = mutableListOf<File>()

    fun open() {
        rotate()
    }

    private fun rotate() {
        closeCurrent()
        segment++
        val f = File(dir, "%s-%s-%03d.pcap".format(label, stamp, segment))
        val r = FileOutputStream(f)
        file = f
        raw = r
        out = BufferedOutputStream(r, BUFFER_BYTES)
        segmentBytes = 0
        segments += f
    }

    /** OutputStream handed to PcapAssembler. */
    fun stream(): java.io.OutputStream = object : java.io.OutputStream() {
        override fun write(b: Int) { out?.write(b); account(1) }
        override fun write(b: ByteArray, off: Int, len: Int) { out?.write(b, off, len); account(len.toLong()) }
        override fun flush() { out?.flush() }
    }

    private fun account(n: Long) {
        segmentBytes += n
        val now = System.currentTimeMillis()
        if (now - lastSync >= SYNC_INTERVAL_MS) {
            lastSync = now
            runCatching { out?.flush(); raw?.fd?.sync() }
        }
        if (segmentBytes >= SEGMENT_BYTES) rotate()
    }

    private fun closeCurrent() {
        runCatching { out?.flush(); raw?.fd?.sync() }
        runCatching { out?.close() }
        out = null; raw = null
    }

    fun close() = closeCurrent()

    /**
     * Copies every segment into Download/Marauder/ so it is visible in Files and
     * survives uninstall. IS_PENDING keeps a half-copied file hidden from the
     * media scanner and the Files app.
     */
    fun exportAll(): Int {
        var exported = 0
        for (f in segments) {
            if (!f.exists() || f.length() == 0L) continue
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.tcpdump.pcap")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Marauder")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
            runCatching {
                resolver.openOutputStream(uri)!!.use { os -> f.inputStream().use { it.copyTo(os, BUFFER_BYTES) } }
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                exported++
            }
        }
        return exported
    }
}
