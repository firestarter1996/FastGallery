package org.fossify.gallery.helpers

import android.content.Context
import android.view.View
import java.util.concurrent.ConcurrentHashMap

/**
 * FastGallery: remembers the exact size and style the grids request thumbnails at (Glide's disk-cache key includes
 * the target size), so the nightly ThumbnailPreloader can produce cache entries the grids will actually hit.
 * Kinds: "folder" (album covers), "media" (last used media grid) and "media:<folder path>" (per folder, since the
 * column count can differ).
 */
object ThumbSizes {
    private const val PREFS = "thumb_sizes"
    private val memory = ConcurrentHashMap<String, String>()
    @Volatile
    private var loaded = false

    data class Spec(val width: Int, val height: Int, val crop: Boolean, val round: Int, val animate: Boolean)

    fun record(context: Context, kind: String, view: View, crop: Boolean, round: Int, animate: Boolean) {
        if (view.width > 0 && view.height > 0) {
            store(context, kind, view, crop, round, animate)
        } else {
            view.post { if (view.width > 0 && view.height > 0) store(context, kind, view, crop, round, animate) }
        }
    }

    private fun store(context: Context, kind: String, view: View, crop: Boolean, round: Int, animate: Boolean) {
        val w = view.width - view.paddingLeft - view.paddingRight
        val h = view.height - view.paddingTop - view.paddingBottom
        if (w <= 0 || h <= 0) return
        val value = "$w,$h,$crop,$round,$animate"
        ensureLoaded(context)
        val keys = if (kind.startsWith("media:")) listOf(kind, "media") else listOf(kind)
        val changed = keys.filter { memory[it] != value }
        if (changed.isEmpty()) return
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        changed.forEach {
            memory[it] = value
            editor.putString(it, value)
        }
        editor.apply()
    }

    fun get(context: Context, kind: String): Spec? {
        ensureLoaded(context)
        val parts = (memory[kind] ?: return null).split(",")
        if (parts.size != 5) return null
        return try {
            Spec(parts[0].toInt(), parts[1].toInt(), parts[2].toBoolean(), parts[3].toInt(), parts[4].toBoolean())
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (v is String) memory.putIfAbsent(k, v)
            }
            loaded = true
        }
    }
}
