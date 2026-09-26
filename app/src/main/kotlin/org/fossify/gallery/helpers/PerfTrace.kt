package org.fossify.gallery.helpers

import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

/**
 * FastGallery startup instrumentation (logcat tag "FGPerf").
 *
 * Times are ms since the process was forked (Process.getStartUptimeMillis), so a cold launch reads as
 * "time until the user sees X". Two metrics matter:
 *  - grid_drawn: the first frame in which the album grid has items on screen (also reportFullyDrawn()).
 *  - thumb_drawn: the first frame in which an album's cover (its most recent file) is drawn, per album.
 * Logging is a handful of lines per launch; the per-album lines stop after THUMB_LOG_LIMIT albums.
 */
object PerfTrace {
    const val TAG = "FGPerf"
    private const val THUMB_LOG_LIMIT = 40

    /**
     * A/B switch for benchmarking: when the file files/perf_legacy exists (create it with root), the FastGallery
     * startup optimisations are skipped and the app behaves like 1.13.1-fast4. Read once per process.
     */
    @Volatile
    var legacy = false

    fun init(context: android.content.Context) {
        legacy = try {
            java.io.File(context.filesDir, "perf_legacy").exists()
        } catch (e: Exception) {
            false
        }
        if (legacy) mark("legacy_mode")
    }

    @Volatile
    var gridDrawn = false
        private set
    private val thumbsLogged = HashSet<String>()

    fun sinceStart(): Long = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

    fun mark(event: String, detail: String = "") {
        Log.i(TAG, "$event t=${sinceStart()} $detail".trim())
    }

    /** Logs [event] only when it took at least [thresholdMs] since [startedAt] (a sinceStart() value). */
    fun slow(event: String, startedAt: Long, detail: String, thresholdMs: Long = 150) {
        val took = sinceStart() - startedAt
        if (took >= thresholdMs) mark(event, "took=$took $detail")
    }

    private val mediaThumbCounts = HashMap<String, IntArray>()

    /** per folder, logs a running tally of where the first 60 media-grid thumbnails came from (cache vs decoded) */
    fun mediaThumb(folder: String, source: String) {
        val tally = synchronized(mediaThumbCounts) {
            val c = mediaThumbCounts.getOrPut(folder) { IntArray(2) }
            if (c[0] + c[1] >= 60) return
            if (source == "RESOURCE_DISK_CACHE" || source == "MEMORY_CACHE") c[0]++ else c[1]++
            c.copyOf()
        }
        if ((tally[0] + tally[1]) % 20 == 0) mark("media_thumbs", "cached=${tally[0]} decoded=${tally[1]} folder=$folder")
    }

    fun markGridDrawn(detail: String): Boolean {
        if (gridDrawn) return false
        gridDrawn = true
        mark("grid_drawn", detail)
        return true
    }

    /** Logs thumb_drawn for [key] the first time [view] is drawn after its cover resource was set. */
    fun onThumbReady(album: String, cover: String, position: Int, source: String, view: View) {
        synchronized(thumbsLogged) {
            if (thumbsLogged.size >= THUMB_LOG_LIMIT || !thumbsLogged.add("$album|$cover")) return
        }
        val key = "$album cover=${cover.substringAfterLast('/')}"
        val readyAt = sinceStart()
        val observer = view.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
                mark("thumb_drawn", "pos=$position ready=$readyAt src=$source album=$key")
                return true
            }
        })
        view.invalidate()
    }
}
