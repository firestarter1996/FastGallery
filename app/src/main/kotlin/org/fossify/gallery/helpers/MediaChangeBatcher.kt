package org.fossify.gallery.helpers

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getRealPathFromURI
import org.fossify.gallery.extensions.addPathToDB
import org.fossify.gallery.extensions.updateDirectoryPath

/**
 * FastGallery (fast11): MediaStore change notifications from the grid activities' content observer, coalesced.
 * Upstream rescanned the whole folder (Camera: 17.9k MediaStore rows + a directory walk) for EVERY notification, from
 * every open grid activity, even in the background. One photo sends several notifications, so a burst of photos kept
 * the process busy until Android killed it for "excessive CPU" (seen 2026-09-26 23:37). Now: nothing happens while no
 * gallery screen is started (NewPhotoFetcher, a batched JobScheduler job, keeps the DB fresh then), and in the
 * foreground each folder is refreshed at most once per quiet 1.5 s.
 */
object MediaChangeBatcher {
    private const val DELAY_MS = 1500L
    private val thread by lazy { HandlerThread("fg-media-changes").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val pending = LinkedHashSet<Uri>()

    fun post(context: Context, uri: Uri) {
        val app = context.applicationContext
        synchronized(pending) { pending.add(uri) }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ flush(app) }, DELAY_MS)
    }

    private fun flush(context: Context) {
        val uris = synchronized(pending) { ArrayList(pending).also { pending.clear() } }
        val paths = LinkedHashSet<String>()
        uris.forEach { uri -> context.getRealPathFromURI(uri)?.let { paths.add(it) } }
        val folders = LinkedHashSet<String>()
        paths.forEach { path ->
            folders.add(path.getParentPath())
            context.addPathToDB(path)
        }
        folders.forEach {
            try {
                context.updateDirectoryPath(it)
            } catch (ignored: Exception) {
            }
        }
        PerfTrace.mark("media_changes", "uris=${uris.size} paths=${paths.size} folders=${folders.size}")
    }
}
