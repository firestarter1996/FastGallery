package org.fossify.gallery.helpers

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.gallery.extensions.buildThumbnailRequest
import org.fossify.gallery.extensions.config
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import java.io.File

/**
 * FastGallery (fast13): start loading at the tap, not when the next screen binds its views.
 *
 * Both helpers issue the exact Glide request the next screen is about to make (same model, signature, size,
 * transformation and options, so the same engine key) as a preload on the application's request manager. The result
 * lands in Glide's memory cache; the screen's own request then either finds it there (delivered synchronously, in its
 * first frame) or joins the decode that is already running.
 */
object ViewerImage {
    private const val PREFS = "viewer_size"
    @Volatile
    private var cachedKey: String? = null
    @Volatile
    private var cachedSize: Pair<Int, Int>? = null

    /** A/B switch: files/perf_no_viewer_preload = the viewer loads as before fast13 (size from layout, no tap preload). */
    fun isDisabled(context: Context) = File(context.filesDir, "perf_no_viewer_preload").exists()

    /** the options of the viewer's screen-sized image; PhotoFragment adds its placeholder and listener */
    fun options(medium: Medium, priority: Priority): RequestOptions = RequestOptions()
        .signature(medium.getKey())
        .format(DecodeFormat.PREFER_ARGB_8888)
        .priority(priority)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .fitCenter()

    private fun screenKey(context: Context): String {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return "${metrics.widthPixels}x${metrics.heightPixels}"
    }

    /** the size the viewer's image view had the last time it was laid out on a screen of the current size, or null */
    fun size(context: Context): Pair<Int, Int>? {
        val key = screenKey(context)
        if (cachedKey == key) return cachedSize
        val parts = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)?.split(",")
        val size = try {
            if (parts?.size == 2) Pair(parts[0].toInt(), parts[1].toInt()) else null
        } catch (e: Exception) {
            null
        }
        cachedSize = size
        cachedKey = key
        return size
    }

    fun record(context: Context, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val key = screenKey(context)
        val size = Pair(width, height)
        if (cachedKey == key && cachedSize == size) return
        cachedSize = size
        cachedKey = key
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, "$width,$height").apply()
    }

    /** true for the files PhotoFragment loads through loadWithGlide with the plain path */
    fun isPlainImage(context: Context, medium: Medium) = (medium.isImage() || medium.isRaw()) && !medium.isGIF() && !medium.isSVG() &&
        !medium.isApng() && !medium.isAvif() && !medium.isPortrait() && !medium.path.endsWith(".webp", true) &&
        medium.path.startsWith("/") && !context.isPathOnOTG(medium.path)

    /** called when a photo is tapped in a grid, before the viewer activity is started */
    fun preload(context: Context, medium: Medium) {
        try {
            val app = context.applicationContext
            if (isDisabled(app) || !isPlainImage(app, medium)) return
            val (width, height) = size(app) ?: return
            Glide.with(app).load(medium.path).apply(options(medium, Priority.IMMEDIATE)).preload(width, height)
            PerfTrace.mark("viewer_preload", "${width}x$height ${medium.name}")
        } catch (ignored: Exception) {
        }
    }
}

object AlbumThumbs {
    private const val MAX_TILES = 60
    private var lastPath = ""
    private var lastAt = 0L

    /** A/B switch: files/perf_no_thumb_prewarm */
    fun isDisabled(context: Context) = File(context.filesDir, "perf_no_thumb_prewarm").exists()

    /**
     * Loads the thumbnails of an album's first screen into Glide's memory cache, from the album's saved first screens
     * ([MediaSnapshot]). Called when an album is tapped and again at the top of MediaActivity.onCreate (other entry
     * points); the second call within a second is skipped. Main thread, ~1 ms plus one request per tile.
     */
    fun prewarm(context: Context, path: String, snapshot: List<ThumbnailItem>? = null) {
        try {
            val app = context.applicationContext
            if (path.isEmpty() || isDisabled(app) || MediaSnapshot.isDisabled(app)) return
            val now = android.os.SystemClock.uptimeMillis()
            if (path == lastPath && now - lastAt < 1000) return
            val config = app.config
            if (config.showAll || config.isFolderProtected(path) || config.scrollHorizontally) return
            if (config.getFolderViewType(path) == VIEW_TYPE_LIST) return
            val spec = ThumbSizes.get(app, "media:$path") ?: return
            if (spec.width <= 0 || spec.height <= 0) return
            val items = snapshot ?: MediaSnapshot.read(app, path) ?: return
            lastPath = path
            lastAt = now
            val screenHeight = app.resources.displayMetrics.heightPixels
            val columns = (app.resources.displayMetrics.widthPixels / spec.width).coerceAtLeast(1)
            val tiles = (columns * (screenHeight / spec.height + 1)).coerceAtMost(MAX_TILES)
            var count = 0
            for (item in items) {
                val medium = item as? Medium ?: continue
                if (count >= tiles) break
                count++
                if (medium.isSVG()) continue
                app.buildThumbnailRequest(medium.path, spec.crop, spec.round, medium.getKey(), animate = spec.animate)
                    .preload(spec.width, spec.height)
            }
            PerfTrace.mark("thumb_prewarm", "n=$count ${spec.width}x${spec.height} $path")
        } catch (ignored: Exception) {
        }
    }
}
