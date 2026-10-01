package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import org.fossify.gallery.extensions.buildThumbnailRequest
import org.fossify.gallery.models.Medium
import kotlin.math.min

/**
 * FastGallery (fast11): the fullscreen viewer shows the photo in its first frames by drawing the grid thumbnail the user
 * just tapped (same Glide request/key/size as the grid, so it is a memory-cache hit, delivered synchronously) until the
 * screen-sized decode is ready. Grid thumbnails are usually centre-cropped squares, so the drawable reports the photo's
 * real (oriented) size and draws the square exactly over the photo's central square: the picture never moves or
 * rescales when the full image replaces it, the edges just fill in.
 */
class ViewerPlaceholderDrawable(
    val bitmap: Bitmap,
    private val fullWidth: Int,
    private val fullHeight: Int,
    private val cropped: Boolean
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    override fun getIntrinsicWidth() = fullWidth
    override fun getIntrinsicHeight() = fullHeight

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (cropped) {
            val side = min(b.width(), b.height()).toFloat()
            dst.set(b.exactCenterX() - side / 2, b.exactCenterY() - side / 2, b.exactCenterX() + side / 2, b.exactCenterY() + side / 2)
        } else {
            dst.set(b)
        }
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

object ViewerPlaceholder {
    /**
     * Calls [onReady] with a placeholder built from the cached grid thumbnail of [medium] (synchronously when it is in
     * Glide's memory cache, a few ms later from the disk cache), or never when there is no cached thumbnail. Never
     * decodes the original file.
     */
    fun load(context: Context, medium: Medium, onReady: (Drawable) -> Unit) {
        val spec = ThumbSizes.get(context, "media:${medium.parentPath}") ?: ThumbSizes.get(context, "media") ?: return
        if (spec.round != ROUNDED_CORNERS_NONE || !(medium.isImage() || medium.isRaw())) return
        val started = PerfTrace.sinceStart()
        val (w, h) = orientedSize(medium.path) ?: return
        context.buildThumbnailRequest(
            path = medium.path,
            cropThumbnails = spec.crop,
            roundCorners = spec.round,
            signature = medium.getKey(),
            animate = spec.animate
        ).onlyRetrieveFromCache(true)
            .into(object : CustomTarget<Drawable>(spec.width, spec.height) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    val bmp = (resource as? BitmapDrawable)?.bitmap ?: return
                    PerfTrace.mark("photo_placeholder", "took=${PerfTrace.sinceStart() - started} ${w}x$h crop=${spec.crop} ${medium.name}")
                    onReady(ViewerPlaceholderDrawable(bmp, w, h, spec.crop))
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    /** the photo's size as displayed (EXIF rotation applied); reads only the file header */
    private fun orientedSize(path: String): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val rotated = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true

                else -> false
            }
            if (rotated) Pair(opts.outHeight, opts.outWidth) else Pair(opts.outWidth, opts.outHeight)
        } catch (e: Exception) {
            null
        }
    }
}
