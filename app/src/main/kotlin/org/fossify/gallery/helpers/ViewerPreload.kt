package org.fossify.gallery.helpers

import android.app.Activity
import android.content.Context
import android.util.DisplayMetrics
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import org.fossify.gallery.models.Medium
import java.io.File

/**
 * FastGallery (fast13): the viewer's screen-sized decode starts at the grid tap instead of after the viewer's first
 * layout. Glide keys a load on the target size, which a view only knows once it is laid out, so the size of the viewer's
 * image view is remembered per screen size ([record]) and both the tap-time preload ([start]) and the viewer's own
 * request (PhotoFragment, via [options] + override) use it: same key, so the viewer joins the decode that is already
 * running (or finds it in the memory cache) instead of starting its own ~60 ms later.
 */
object ViewerPreload {
    private fun kind(activity: Activity): String {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        return "viewer:${metrics.widthPixels}x${metrics.heightPixels}"
    }

    /** benchmark switch: files/perf_no_viewer_preload brings back the fast12 behaviour (size from the laid-out view) */
    private fun disabled(context: Context) = File(context.filesDir, "perf_no_viewer_preload").exists()

    /** the remembered size of the viewer's image view on this screen, or null (first use, or switched off) */
    fun size(activity: Activity): ThumbSizes.Spec? = if (disabled(activity)) null else ThumbSizes.get(activity, kind(activity))

    /**
     * fast14: the size the viewer's OWN request is started with, or null = it takes the size from its laid-out view
     * (same key as the tap preload either way). An explicit size only pays when the viewer opens without the system
     * animation (files/perf_viewer_noanim): with the animation kept it brought no gain for a never opened photo and
     * drew the viewer's first frame about one frame (8 ms) later for a photo that is already in the memory cache
     * (Pixel 6 Pro, 10-04-2026, 14 and 13 runs; opus-speed measured the same on 09-30-2026).
     */
    fun requestSize(activity: Activity): ThumbSizes.Spec? =
        if (File(activity.filesDir, "perf_viewer_noanim").exists()) size(activity) else null

    fun record(activity: Activity, view: View) = ThumbSizes.record(activity, kind(activity), view, false, 0, false)

    /** the part of the viewer's request that makes up Glide's cache key; shared so the preload and the viewer match */
    fun options(key: ObjectKey): RequestOptions = RequestOptions()
        .signature(key)
        .format(DecodeFormat.PREFER_ARGB_8888)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .fitCenter()

    fun start(activity: Activity, medium: Medium) {
        if (!medium.isImage() || medium.isPortrait() || medium.isWebP() || medium.isApng() || medium.isAvif() || !medium.path.startsWith("/")) return
        val size = size(activity) ?: return
        Glide.with(activity.applicationContext)
            .load(medium.path)
            .apply(options(medium.getKey()).priority(Priority.IMMEDIATE))
            .preload(size.width, size.height)
        PerfTrace.mark("viewer_preload", "${size.width}x${size.height} ${medium.name}")
    }
}
