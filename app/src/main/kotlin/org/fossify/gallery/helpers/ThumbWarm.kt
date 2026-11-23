package org.fossify.gallery.helpers

import android.content.Context
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestBuilder
import org.fossify.gallery.extensions.buildThumbnailRequest
import org.fossify.gallery.models.Medium
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * fast24 (owner 11-23-2026: "when I open an album, all the thumbs are redrawn" and "thumbs disappear while fast
 * scroll"). Album thumbnails are fetched into Glide's memory cache before the grid asks for them:
 *  - [warmAlbum]: on the folder tap, the album's saved first screens (MediaSnapshot) are requested with the grid's exact
 *    request (ThumbSizes spec), so they decode while the album screen starts and the grid binds memory hits.
 *  - [request]: the same request for one medium, used by the album grid's scroll preloader.
 * Switch files (benchmarks): files/perf_no_thumb_warm, files/perf_no_scroll_preload, files/perf_big_mem_cache (SvgModule).
 */
object ThumbWarm {
    @Volatile
    private var flags: BooleanArray? = null

    private fun flags(context: Context): BooleanArray {
        flags?.let { return it }
        val dir = context.filesDir
        val f = booleanArrayOf(
            PerfTrace.legacy || File(dir, "perf_no_thumb_warm").exists(),
            PerfTrace.legacy || File(dir, "perf_no_scroll_preload").exists()
        )
        flags = f
        return f
    }

    fun warmDisabled(context: Context) = flags(context)[0]
    fun preloadDisabled(context: Context) = flags(context)[1]

    /** The grid's memory cache key for [medium] at [spec]: model, signature, size, transforms and decode options. */
    fun request(context: Context, medium: Medium, spec: ThumbSizes.Spec): RequestBuilder<*> =
        context.buildThumbnailRequest(medium.path, spec.crop, spec.round, medium.getKey(), animate = spec.animate)
            .override(spec.width, spec.height)

    /** Called on the folder tap, before the album screen starts. Never throws, never blocks the main thread. */
    fun warmAlbum(context: Context, path: String) {
        val app = context.applicationContext
        try {
            if (warmDisabled(app)) return
        } catch (_: Throwable) {
            return
        }
        Thread {
            try {
                val spec = ThumbSizes.get(app, "media:$path") ?: return@Thread
                val items = MediaSnapshot.read(app, path) ?: return@Thread
                val dm = app.resources.displayMetrics
                val cols = max(1, dm.widthPixels / max(1, spec.width))
                val rows = dm.heightPixels / max(1, spec.height) + 2
                val want = min(48, cols * rows)
                var n = 0
                for (item in items) {
                    if (n >= want) break
                    val m = item as? Medium ?: continue
                    if (m.isSVG()) continue
                    request(app, m, spec).priority(Priority.IMMEDIATE).preload()
                    n++
                }
                PerfTrace.mark("thumb_warm", "n=$n $path")
            } catch (_: Throwable) {
            }
        }.start()
    }
}
