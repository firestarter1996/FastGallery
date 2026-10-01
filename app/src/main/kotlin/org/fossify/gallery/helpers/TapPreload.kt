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
import org.fossify.gallery.models.Medium
import java.io.File

/**
 * FastGallery (fast13): start loading at the tap, not when the next screen binds its views.
 *
 * [preload] issues the exact Glide request the viewer is about to make (same model, signature, size, transformation
 * and options, so the same engine key) as a preload on the application's request manager. The result lands in Glide's
 * memory cache; the viewer's own request then either finds it there (delivered synchronously, in its first frame) or
 * joins the decode that is already running.
 *
 * Measured and dropped (Pixel 6 Pro, 10 interleaved runs each way): the same idea for an album's first-screen
 * thumbnails (on screen 127 vs 127 ms), and an explicit size on the viewer's own request so it starts before the
 * first layout pass (no gain for a never-opened photo, and the viewer's first frame came ~7 ms later for a cached one).
 */
object ViewerImage {
    private const val PREFS = "viewer_size"
    @Volatile
    private var cachedKey: String? = null
    @Volatile
    private var cachedSize: Pair<Int, Int>? = null

    /** A/B switch: files/perf_no_viewer_preload = no preload at the tap (the viewer loads as before fast13). */
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
