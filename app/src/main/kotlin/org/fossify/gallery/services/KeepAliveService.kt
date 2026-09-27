package org.fossify.gallery.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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
            KeepAlive.cancelRefresh(this)
            stopSelf()
            return START_NOT_STICKY
        }
        PerfTrace.mark("keepalive_on", "flags=$flags")
        KeepAlive.scheduleRefresh(this)
        return START_STICKY
    }
}

object KeepAlive {
    fun enabled(context: Context): Boolean {
        if (File(context.filesDir, "no_keep_alive").exists()) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * fast12: Android demotes a started service to a cached process once it has not been (re)started for 30 min of
     * uptime (MAX_SERVICE_INACTIVITY; seen on the 6 Pro: adj 800 -> 975-999 after 30 min, then frozen/killable).
     * Re-starting it every 15 min (inexact) resets that clock. Non-wakeup inexact alarm: it never wakes a sleeping phone (the
     * clock is uptime-based, so it does not run while asleep either); one tiny start command, no work.
     */
    private const val REFRESH_MS = 15 * 60 * 1000L   // inexact: may fire up to ~75% later, still < 30 min

    private fun refreshIntent(context: Context) = PendingIntent.getService(
        context, 7302, Intent(context, KeepAliveService::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun scheduleRefresh(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + REFRESH_MS, REFRESH_MS, refreshIntent(context))
        } catch (e: Exception) {
        }
    }

    fun cancelRefresh(context: Context) {
        try {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(refreshIntent(context))
        } catch (e: Exception) {
        }
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
