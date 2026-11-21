package org.fossify.gallery.helpers

import android.os.Handler
import android.os.Looper

/**
 * FastGallery (fast20): the viewer keeps five pages alive (the open one and two neighbours on each side), and every
 * video page used to start its own screen sized frame decode of the original file at once. In a run of new 4K videos
 * that is five hardware frame extractions competing, so the video the user actually opened was the slowest to appear.
 * The open page takes the gate, the neighbours wait until it is done (or has given up) and then go one at a time.
 * Main thread only. Nothing here polls: every step is started by a load finishing.
 */
object VideoPreviewGate {
    private const val STUCK_MS = 4000L
    private val handler = Handler(Looper.getMainLooper())
    private val waiting = ArrayDeque<Pair<Any, () -> Unit>>()
    private var holder: Any? = null
    private val unstick = Runnable { holder = null; next() }

    /** the open page: runs now, the neighbours wait for [done] */
    fun takeNow(owner: Any) {
        waiting.removeAll { it.first === owner }
        hold(owner)
    }

    /** a neighbour: [start] runs when no other page is decoding; [start] must end in [done] */
    fun whenFree(owner: Any, start: () -> Unit) {
        waiting.removeAll { it.first === owner }
        waiting.addLast(owner to start)
        next()
    }

    fun done(owner: Any) {
        waiting.removeAll { it.first === owner }
        if (holder === owner) {
            holder = null
            handler.removeCallbacks(unstick)
            next()
        }
    }

    private fun hold(owner: Any) {
        holder = owner
        handler.removeCallbacks(unstick)
        handler.postDelayed(unstick, STUCK_MS)   // a lost callback can never block the queue for good
    }

    private fun next() {
        if (holder != null) return
        val (owner, start) = waiting.removeFirstOrNull() ?: return
        hold(owner)
        try {
            start()
        } catch (e: Exception) {
            done(owner)
        }
    }
}
