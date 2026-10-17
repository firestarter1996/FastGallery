package org.fossify.gallery.services

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.fossify.gallery.helpers.BackgroundPlayback
import org.fossify.gallery.helpers.PerfTrace

/**
 * fast17: foreground media playback service. It does not own a player: it wraps the ExoPlayer of the video that is
 * playing (handed over by [BackgroundPlayback]) in a media3 MediaSession, which gives the media notification with
 * play and pause, headset buttons and the "keep running with the screen off" foreground state. media3 promotes the
 * service to the foreground while the player plays and demotes it (notification stays) when it pauses.
 *
 * Started only when a video starts playing; stopped when that player is released or has nothing left to play.
 */
@UnstableApi
class VideoPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = BackgroundPlayback.player
        if (player == null) {
            stopSelf()
            return
        }

        BackgroundPlayback.service = this
        mediaSession = buildSession(player, BackgroundPlayback.sessionActivityIntent)
        PerfTrace.mark("bg_playback_session")
        if (mediaSession == null) {
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** A later video started playing: swap its player into the live session instead of restarting the service. */
    fun setPlayer(player: ExoPlayer, sessionActivity: PendingIntent?) {
        val session = mediaSession
        if (session == null) {
            mediaSession = buildSession(player, sessionActivity)
            return
        }

        if (session.player !== player) {
            try {
                session.player = player
            } catch (e: Exception) {
                // the old player may already be released; start a fresh session
                releaseSession()
                mediaSession = buildSession(player, sessionActivity)
                return
            }
        }

        if (sessionActivity != null) {
            try {
                session.setSessionActivity(sessionActivity)
            } catch (ignored: Exception) {
            }
        }
    }

    /** The owning player is about to be released: drop the session (and its notification) right now, then stop. */
    fun shutdown() {
        releaseSession()
        BackgroundPlayback.service = null
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            shutdown()
        }
    }

    override fun onDestroy() {
        releaseSession()
        if (BackgroundPlayback.service === this) {
            BackgroundPlayback.service = null
        }
        super.onDestroy()
    }

    /**
     * Builds the session AND registers it with the service ([addSession]): a MediaSessionService only posts the media
     * notification and goes foreground for sessions it was given, and a plain startService never calls onGetSession.
     */
    private fun buildSession(player: ExoPlayer, sessionActivity: PendingIntent?): MediaSession? {
        return try {
            val session = MediaSession.Builder(this, player)
                .setId("fastgallery_video_${System.identityHashCode(player)}")
                .apply { if (sessionActivity != null) setSessionActivity(sessionActivity) }
                .build()
            addSession(session)
            session
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseSession() {
        val session = mediaSession ?: return
        mediaSession = null
        try {
            removeSession(session)
        } catch (ignored: Exception) {
        }
        try {
            session.release()
        } catch (ignored: Exception) {
        }
    }
}
