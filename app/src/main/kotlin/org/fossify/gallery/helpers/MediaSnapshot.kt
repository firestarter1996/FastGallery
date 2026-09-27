package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.config
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import org.fossify.gallery.models.ThumbnailSection
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FastGallery (fast10): the first screens of an album's media list, saved per album.
 *
 * Opening an album used to show an empty grid until the media DB query (17-20k rows for Camera/Screenshots) had been
 * loaded, filtered, sorted and grouped, ~130 ms even when warm. The first [itemsToKeep] items of the finished list
 * (sections included, exactly as the adapter got them) are written after every real load, and MediaActivity binds
 * them on its first frame; the DB list and then the scan result replace them as before.
 *
 * A snapshot is used only when it was written with the same view settings (sorting, grouping, type filter, hidden
 * files, horizontal scroll, date format and, for daily groups, the same day, so "Today"/"Yesterday" stay right) and
 * the folder's mtime is unchanged since the list it came from was scanned, i.e. no file was added, removed or renamed.
 * Bounded: [MAX_ALBUMS] most recently written albums, older files are deleted.
 */
object MediaSnapshot {
    private const val DIR_NAME = "media_snapshot"
    private const val VERSION = 1
    private const val MAX_ALBUMS = 24
    private const val MIN_ITEMS = 120

    private fun dir(context: Context) = File(context.filesDir, DIR_NAME)

    private fun file(context: Context, path: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        return File(dir(context), digest.joinToString("") { "%02x".format(it) } + ".bin")
    }

    fun isDisabled(context: Context) = PerfTrace.legacy || File(context.filesDir, "perf_no_media_snapshot").exists()

    /** Everything that changes which items the list holds, their order or their section titles. */
    fun settingsKey(context: Context, path: String): String {
        val cfg = context.config
        val grouping = cfg.getFolderGrouping(path)
        val daily = grouping and (GROUP_BY_LAST_MODIFIED_DAILY or GROUP_BY_DATE_TAKEN_DAILY) != 0
        val day = if (daily) SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) else ""
        return "$path|${cfg.getFolderSorting(path)}|$grouping|${cfg.filterMedia}|${cfg.shouldShowHidden}|" +
            "${cfg.scrollHorizontally}|${cfg.dateFormat}|$day"
    }

    private fun itemsToKeep(context: Context): Int = maxOf(MIN_ITEMS, context.config.mediaColumnCnt * 30)

    /** Returns the saved first screens of [path], or null when there is none or it may be out of date. Main thread OK (one stat + a ~20 KB read). */
    fun read(context: Context, path: String): ArrayList<ThumbnailItem>? {
        return try {
            val f = file(context, path)
            if (!f.exists()) return null
            val dirMtime = File(path).lastModified()
            if (dirMtime <= 0L) return null
            DataInputStream(f.inputStream().buffered(32 * 1024)).use { input ->
                if (input.readInt() != VERSION) return null
                if (input.readUTF() != settingsKey(context, path)) return null
                if (input.readLong() != dirMtime) return null
                val n = input.readInt()
                val items = ArrayList<ThumbnailItem>(n)
                repeat(n) {
                    if (input.readByte().toInt() == 1) {
                        items.add(ThumbnailSection(input.readUTF()))
                    } else {
                        items.add(
                            Medium(
                                id = null, name = input.readUTF(), path = input.readUTF(), parentPath = input.readUTF(),
                                modified = input.readLong(), taken = input.readLong(), size = input.readLong(), type = input.readInt(),
                                videoDuration = input.readInt(), isFavorite = input.readBoolean(), deletedTS = input.readLong(),
                                mediaStoreId = input.readLong(), gridPosition = input.readInt()
                            )
                        )
                    }
                }
                items
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves the first screens of a finished list for [path]. [dirMtimeAtLoadStart] is the folder's mtime read before the
     * load began; if it changed meanwhile nothing is written. Background thread only.
     */
    fun write(context: Context, path: String, media: List<ThumbnailItem>, dirMtimeAtLoadStart: Long) {
        try {
            if (dirMtimeAtLoadStart <= 0L || File(path).lastModified() != dirMtimeAtLoadStart) return
            val f = file(context, path)
            if (media.isEmpty()) {
                f.delete()
                return
            }
            val keep = media.take(itemsToKeep(context))
            val bytes = ByteArrayOutputStream(keep.size * 160)
            DataOutputStream(bytes).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(settingsKey(context, path))
                out.writeLong(dirMtimeAtLoadStart)
                out.writeInt(keep.size)
                keep.forEach {
                    if (it is ThumbnailSection) {
                        out.writeByte(1)
                        out.writeUTF(it.title)
                    } else {
                        val m = it as Medium
                        out.writeByte(0)
                        out.writeUTF(m.name); out.writeUTF(m.path); out.writeUTF(m.parentPath)
                        out.writeLong(m.modified); out.writeLong(m.taken); out.writeLong(m.size); out.writeInt(m.type)
                        out.writeInt(m.videoDuration); out.writeBoolean(m.isFavorite); out.writeLong(m.deletedTS)
                        out.writeLong(m.mediaStoreId); out.writeInt(m.gridPosition)
                    }
                }
            }
            val data = bytes.toByteArray()
            if (f.exists() && f.length() == data.size.toLong() && f.readBytes().contentEquals(data)) {
                f.setLastModified(System.currentTimeMillis())   // LRU: still recently used
                return
            }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(f)) tmp.delete()
            prune(context)
        } catch (ignored: Exception) {
        }
    }

    fun delete(context: Context, path: String) {
        try {
            file(context, path).delete()
        } catch (ignored: Exception) {
        }
    }

    private fun prune(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".bin") } ?: return
        if (files.size <= MAX_ALBUMS) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_ALBUMS).forEach { it.delete() }
    }
}
