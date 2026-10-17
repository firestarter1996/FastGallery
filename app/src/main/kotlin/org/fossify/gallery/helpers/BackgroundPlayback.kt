package org.fossify.gallery.helpers

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.gallery.extensions.config
import org.fossify.gallery.services.VideoPlaybackService

/**
 * fast17: hands the player a video is playing on to [VideoPlaybackService], which wraps it in a media session and a
 * media notification so the video keeps playing with the screen off or the app in the background.
 *
 * Nothing here runs until a video actually plays: the service is started from the first play() of a session and is
 * stopped when the player that owns the session is released. Everything is main thread only, same process.
 */
object BackgroundPlayback {
    @Volatile
    var player: ExoPlayer? = null
        private set

    var sessionActivityIntent: PendingIntent? = null
        private set

    /** Set by the service while it lives, so a later video can swap the player instead of restarting the service. */
    var service: VideoPlaybackService? = null

    fun isEnabled(context: Context) = context.config.backgroundVideoPlayback

    /** The activity hosting [exoPlayer] started or resumed playback. Starts the service once, or swaps the player in. */
    fun onPlaybackStarted(activity: Activity, exoPlayer: ExoPlayer) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (!isEnabled(activity)) return
        player = exoPlayer
        sessionActivityIntent = buildSessionActivity(activity)
        askNotificationPermissionOnce(activity)

        val running = service
        if (running != null) {
            running.setPlayer(exoPlayer, sessionActivityIntent)
            return
        }

        try {
            PerfTrace.mark("bg_playback_start")
            activity.startService(Intent(activity, VideoPlaybackService::class.java))
        } catch (ignored: Exception) {
            // Android refusing a background start: the video simply plays like upstream (stops on pause)
            player = null
        }
    }

    /** Call BEFORE releasing a player. Ends the session and the notification if this player owns them. */
    fun onPlayerReleasing(exoPlayer: ExoPlayer) {
        if (player !== exoPlayer) return
        player = null
        service?.shutdown()
    }

    private fun buildSessionActivity(activity: Activity): PendingIntent? {
        return try {
            val intent = Intent(activity.intent ?: Intent(activity, activity.javaClass)).apply {
                setClass(activity, activity.javaClass)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            PendingIntent.getActivity(
                activity,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (ignored: Exception) {
            null
        }
    }

    /** Android 13+ hides the media notification without POST_NOTIFICATIONS; ask once, playback runs either way. */
    private fun askNotificationPermissionOnce(activity: Activity) {
        try {
            val config = activity.config
            if (config.playbackNotificationAsked || activity.hasPermission(PERMISSION_POST_NOTIFICATIONS)) return
            config.playbackNotificationAsked = true
            (activity as? BaseSimpleActivity)?.handleNotificationPermission { }
        } catch (ignored: Exception) {
        }
    }
}
