package org.fossify.gallery.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import org.fossify.commons.extensions.getRealPathFromURI
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.buildThumbnailRequest
import org.fossify.gallery.models.Medium
import java.io.File

/**
 * FastGallery (fast12): the video player shows the grid thumbnail the user tapped (Glide memory-cache hit, same request
 * as the grid) in its first frames, drawn over the video's centre square at the video's real displayed size, until
 * ExoPlayer renders the first video frame. Before, the player was black for ~100 ms.
 * Off switch: an empty files/perf_no_video_poster (root).
 */
object VideoPoster {
    private const val EXTRA_PATH = "fg_poster_path"
    private const val EXTRA_SIG = "fg_poster_sig"
    private const val EXTRA_PARENT = "fg_poster_parent"

    fun putExtras(intent: Intent, medium: Medium?) {
        medium ?: return
        intent.putExtra(EXTRA_PATH, medium.path)
        intent.putExtra(EXTRA_SIG, medium.getSignature())
        intent.putExtra(EXTRA_PARENT, medium.parentPath)
    }

    /** calls [onReady] on the main thread with the poster, or never (no cached thumbnail, unknown size, switched off) */
    fun load(activity: Activity, intent: Intent, uri: Uri, onReady: (Drawable) -> Unit) {
        val started = PerfTrace.sinceStart()
        ensureBackgroundThread {
            if (File(activity.filesDir, "perf_no_video_poster").exists()) return@ensureBackgroundThread
            var path = intent.getStringExtra(EXTRA_PATH)
            var sig = intent.getStringExtra(EXTRA_SIG)
            var parent = intent.getStringExtra(EXTRA_PARENT)
            if (path == null || sig == null || parent == null) {
                // opened through a VIEW intent (system player setting / "Open with" / other apps): find the grid's row
                val p = (if (uri.scheme == "file") uri.path else try {
                    activity.getRealPathFromURI(uri)
                } catch (e: Exception) {
                    null
                }) ?: return@ensureBackgroundThread
                val medium = try {
                    activity.mediaDB.getMediumByPath(p)
                } catch (e: Exception) {
                    null
                } ?: return@ensureBackgroundThread
                path = medium.path; sig = medium.getSignature(); parent = medium.parentPath
            }
            val vPath: String = path ?: return@ensureBackgroundThread
            val vSig: String = sig ?: return@ensureBackgroundThread
            val vParent: String = parent ?: return@ensureBackgroundThread
            val spec = ThumbSizes.get(activity, "media:$vParent") ?: ThumbSizes.get(activity, "media") ?: return@ensureBackgroundThread
            if (spec.round != ROUNDED_CORNERS_NONE) return@ensureBackgroundThread
            val (w, h) = displayedSize(activity, vPath) ?: return@ensureBackgroundThread
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                activity.buildThumbnailRequest(
                    path = vPath,
                    cropThumbnails = spec.crop,
                    roundCorners = spec.round,
                    signature = ObjectKey(vSig),
                    animate = spec.animate
                ).onlyRetrieveFromCache(true)
                    .into(object : CustomTarget<Drawable>(spec.width, spec.height) {
                        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                            val bmp = (resource as? BitmapDrawable)?.bitmap ?: return
                            PerfTrace.mark("player_poster", "took=${PerfTrace.sinceStart() - started} ${w}x$h crop=${spec.crop}")
                            onReady(ViewerPlaceholderDrawable(bmp, w, h, spec.crop))
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {}
                    })
            }
        }
    }

    /** the video's displayed size (rotation applied), from MediaStore (one indexed row, a few ms) */
    private fun displayedSize(context: Context, path: String): Pair<Int, Int>? {
        return try {
            val projection = arrayOf(MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.ORIENTATION)
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Video.Media.DATA} = ?", arrayOf(path), null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val w = c.getInt(0)
                val h = c.getInt(1)
                val rot = c.getInt(2)
                if (w <= 0 || h <= 0) return null
                if (rot == 90 || rot == 270) Pair(h, w) else Pair(w, h)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * FastGallery (fast12): ExoPlayer's first player in a process queries the device's codec list (MediaCodecUtil, tens of
 * ms, cached in a static map afterwards). An album grid that contains videos warms that cache once per process on a
 * background thread, so the first video opened doesn't pay for it. Off switch: files/perf_no_player_prewarm.
 */
object PlayerPrewarm {
    @Volatile
    private var done = false

    @OptIn(UnstableApi::class)
    fun warm(context: Context) {
        if (done) return
        done = true
        val app = context.applicationContext
        ensureBackgroundThread {
            if (File(app.filesDir, "perf_no_player_prewarm").exists()) return@ensureBackgroundThread
            val started = PerfTrace.sinceStart()
            try {
                Thread.sleep(1500)   // after the grid's first screens; never competes with them
                for (mime in arrayOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC)) {
                    MediaCodecUtil.getDecoderInfos(mime, false, false)
                }
                PerfTrace.mark("player_prewarm", "took=${PerfTrace.sinceStart() - started - 1500}")
            } catch (e: Throwable) {
            }
        }
    }
}
