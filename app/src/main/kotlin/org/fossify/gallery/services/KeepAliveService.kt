package org.fossify.gallery.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import org.fossify.gallery.helpers.PerfTrace
import java.io.File

/**
 * FastGallery (fast11) keep-alive: an idle started service (no notification, no work, no wakelocks). While it runs,
 * Android ranks the process as a service instead of a cached app, so it is not dropped by the "too many cached/empty
 * processes" trimming and is among the last picked by the low-memory killer; launches stay warm. START_STICKY brings the
 * process back if the low-memory killer (or a Recents swipe) kills it anyway.
 *
 * Only runs when the app is on the battery-optimisation allowlist (Unrestricted battery), since Android stops
 * background services of other apps after about a minute anyway.
 * Off switch: an empty files/no_keep_alive (root), then force-stop the app.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!KeepAlive.enabled(this)) {
            PerfTrace.mark("keepalive_off")
            stopSelf()
            return START_NOT_STICKY
        }
        PerfTrace.mark("keepalive_on", "flags=$flags")
        return START_STICKY
    }
}

object KeepAlive {
    fun enabled(context: Context): Boolean {
        if (File(context.filesDir, "no_keep_alive").exists()) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** call off the main thread */
    fun start(context: Context) {
        try {
            if (enabled(context)) context.startService(Intent(context, KeepAliveService::class.java))
        } catch (e: Exception) {
            // not allowed from the background right now (not allowlisted, or a restricted state): no keep-alive
        }
    }
}
