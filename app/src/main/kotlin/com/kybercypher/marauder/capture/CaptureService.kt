package com.kybercypher.marauder.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the USB session alive while the screen is off.
 *
 * TYPE CHOICE IS LOAD-BEARING: connectedDevice, not dataSync. Android 15 caps
 * dataSync and mediaProcessing at 6 hours per 24h; connectedDevice carries no
 * documented timeout. The intuitive pick for "streaming data to a file" would
 * silently kill an overnight capture at the six hour mark.
 *
 * That exemption is established by omission in Google's docs rather than
 * affirmatively, so onTimeout() is implemented defensively anyway: if the policy
 * ever changes we close the file cleanly instead of corrupting it.
 *
 * UsbManager.requestPermission() MUST have been called before startForeground()
 * with this type — since Android 14 the system validates the type prerequisite
 * at service creation and throws otherwise.
 */
class CaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "marauder_capture"
        const val NOTIFICATION_ID = 0xA2
        const val ACTION_START = "com.kybercypher.marauder.CAPTURE_START"
        const val ACTION_STOP = "com.kybercypher.marauder.CAPTURE_STOP"
        const val EXTRA_LABEL = "label"

        fun start(ctx: Context, label: String) {
            val i = Intent(ctx, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, label)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Marauder session",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
        }
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "connected"

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(label),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )

        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Marauder:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        // Not START_STICKY: a restarted service without the USB grant and an open
        // port would be a zombie. The user reattaches, which is honest.
        return START_NOT_STICKY
    }

    /** Android 15+ may call this for timed-out types. Close cleanly, never corrupt. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        shutdown()
    }

    override fun onDestroy() {
        releaseLock()
        super.onDestroy()
    }

    private fun shutdown() {
        releaseLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun buildNotification(label: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Marauder connected")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
