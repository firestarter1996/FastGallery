package org.fossify.gallery.jobs

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import org.fossify.commons.helpers.FAVORITES
import org.fossify.gallery.App
import org.fossify.gallery.extensions.buildThumbnailRequest
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.helpers.DirSnapshot
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.PerfTrace
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.ThumbSizes
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Medium
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * FastGallery nightly thumbnail pre-load (Settings > "Pre-load all thumbnails overnight while charging").
 *
 * Runs from midnight whenever the phone is charging, walks every visible album in display order (album covers
 * first, then each album's files newest-first) and renders each grid thumbnail into Glide's disk cache with the
 * exact request the grids use (buildThumbnailRequest + the sizes recorded by ThumbSizes), so opening any album
 * shows cached thumbnails instead of decoding full photos one by one. Files already cached cost a small read.
 * If Android stops the job (charger unplugged, time limit) it resumes where it left off; once a night is
 * complete the next run is scheduled for the following midnight.
 */
class ThumbnailPreloader : JobService() {
    companion object {
        const val JOB_ID = 7301
        private const val PREFS = "thumbnail_preloader"
        private const val IN_FLIGHT = 4

        fun schedule(context: Context, runNow: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!context.config.nightlyThumbnailPreload) {
                scheduler.cancel(JOB_ID)
                return
            }

            val info = JobInfo.Builder(JOB_ID, ComponentName(context, ThumbnailPreloader::class.java))
                .setRequiresCharging(true)
                .setMinimumLatency(if (runNow) 0L else msUntilNextMidnight())
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .setPersisted(true)
                .build()
            try {
                scheduler.schedule(info)
            } catch (ignored: Exception) {
            }
        }

        /** keeps exactly one pending job while the option is on, without disturbing a pending/running one */
        fun ensureScheduled(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!context.config.nightlyThumbnailPreload) {
                scheduler.cancel(JOB_ID)
            } else if (scheduler.getPendingJob(JOB_ID) == null) {
                schedule(context)
            }
        }

        private fun msUntilNextMidnight(): Long {
            val next = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 30)
                set(Calendar.MILLISECOND, 0)
            }
            return (next.timeInMillis - System.currentTimeMillis()).coerceAtLeast(60_000L)
        }

        /** the night a run belongs to = the date of the midnight it started after */
        private fun nightKey(): String {
            val c = Calendar.getInstance()
            return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
        }
    }

    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread {
            val finished = try {
                preload()
            } catch (e: Exception) {
                PerfTrace.mark("preload_error", e.toString())
                false
            }

            if (finished) {
                jobFinished(params, false)
                schedule(applicationContext)
            } else if (!stopped) {
                jobFinished(params, true)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true   // resume later tonight (backoff) while still charging
    }

    /** @return true when every visible thumbnail has been processed for this night */
    private fun preload(): Boolean {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val night = nightKey()
        if (prefs.getString("done_night", null) == night) return true
        if (prefs.getString("night", null) != night) {
            prefs.edit().putString("night", night).putInt("dir", 0).putInt("item", 0).putInt("count", 0).apply()
        }

        val started = SystemClock.elapsedRealtime()
        val config = ctx.config
        val dirs = (DirSnapshot.load(ctx) ?: ctx.directoryDB.getAll()).filter { it.path != RECYCLE_BIN && it.tmb.isNotEmpty() }
        val glide = Glide.with(ctx)
        val inFlight = ArrayList<FutureTarget<*>>()
        var processed = prefs.getInt("count", 0)

        fun drain(max: Int) {
            while (inFlight.size > max) {
                val f = inFlight.removeAt(0)
                try {
                    f.get(60, TimeUnit.SECONDS)
                } catch (ignored: Exception) {
                }
                glide.clear(f)
                processed++
            }
        }

        fun submit(path: String, key: com.bumptech.glide.signature.ObjectKey, spec: ThumbSizes.Spec) {
            if (path.endsWith(".svg", true)) return
            // the gallery is open: leave the decoders and disk to the screen the user is looking at
            if (App.startedActivities > 0) {
                drain(0)
                while (App.startedActivities > 0 && !stopped) {
                    SystemClock.sleep(2000)
                }
                if (stopped) return
            }
            inFlight.add(
                ctx.buildThumbnailRequest(path, spec.crop, spec.round, key, skipMemoryCache = true, animate = spec.animate)
                    .submit(spec.width, spec.height)
            )
            drain(IN_FLIGHT)
        }

        // 1) album covers
        ThumbSizes.get(ctx, "folder")?.let { spec ->
            dirs.forEach { if (!stopped) submit(it.tmb, it.getKey(), spec) }
            drain(0)
        }

        // 2) every album's files, in the order the album grid shows them
        val fetcher = MediaFetcher(ctx)
        val showHidden = config.shouldShowHidden
        var dirIndex = prefs.getInt("dir", 0)
        var itemIndex = prefs.getInt("item", 0)
        while (dirIndex < dirs.size && !stopped) {
            val dir: Directory = dirs[dirIndex]
            val spec = ThumbSizes.get(ctx, "media:${dir.path}") ?: ThumbSizes.get(ctx, "media")
            if (spec != null) {
                val rows = ArrayList<Medium>(
                    (if (dir.path == FAVORITES) ctx.mediaDB.getFavorites() else ctx.mediaDB.getMediaFromPath(dir.path))
                        .filter { showHidden || !it.name.startsWith('.') }
                )
                fetcher.sortMedia(rows, config.getFolderSorting(dir.path))
                while (itemIndex < rows.size && !stopped) {
                    val medium = rows[itemIndex]
                    submit(medium.path, medium.getKey(), spec)
                    itemIndex++
                    if (itemIndex % 100 == 0) {
                        prefs.edit().putInt("dir", dirIndex).putInt("item", (itemIndex - IN_FLIGHT).coerceAtLeast(0))
                            .putInt("count", processed).apply()
                    }
                }
            }
            if (stopped) break
            drain(0)
            dirIndex++
            itemIndex = 0
            prefs.edit().putInt("dir", dirIndex).putInt("item", 0).putInt("count", processed).apply()
        }

        drain(0)
        val took = (SystemClock.elapsedRealtime() - started) / 1000
        if (stopped) {
            PerfTrace.mark("preload_paused", "dir=$dirIndex/${dirs.size} processed=$processed took=${took}s")
            return false
        }

        prefs.edit().putString("done_night", night).putInt("count", processed).apply()
        PerfTrace.mark("preload_done", "dirs=${dirs.size} processed=$processed took=${took}s")
        return true
    }
}
