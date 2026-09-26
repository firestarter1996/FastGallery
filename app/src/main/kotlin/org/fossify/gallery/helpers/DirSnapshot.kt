package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.extensions.config
import org.fossify.gallery.models.Directory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * FastGallery: the album grid exactly as it was last displayed, saved to a small file so a cold launch can draw
 * the albums (and start loading their covers) in the very first frame, instead of waiting ~0.7 s (3+ s with a
 * cold page cache) for the Room query + .nomedia MediaStore scan in getCachedDirectories(). The normal
 * DB load and rescan still run right after and update the grid if anything changed.
 *
 * The snapshot is only used when the settings that decide which folders are visible still match ([key]).
 * Plain org.json (no reflection) so R8 renaming cannot corrupt it across builds.
 */
object DirSnapshot {
    private const val FILE_NAME = "dir_snapshot.json"
    private const val VERSION = 1

    private fun key(context: Context): String = with(context.config) {
        listOf(
            VERSION,
            BuildConfig.VERSION_CODE,
            showHiddenMedia || temporarilyShowHidden,
            temporarilyShowExcluded,
            excludedFolders.sorted().hashCode(),
            includedFolders.sorted().hashCode(),
            filterMedia,
            showRecycleBinAtFolders,
            showRecycleBinLast,
            groupDirectSubfolders,
        ).joinToString("|")
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): ArrayList<Directory>? {
        return try {
            val f = file(context)
            if (!f.exists()) return null
            val root = JSONObject(f.readText())
            if (root.optString("key") != key(context)) return null
            val arr = root.getJSONArray("dirs")
            val dirs = ArrayList<Directory>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                dirs.add(
                    Directory(
                        id = if (o.has("id")) o.getLong("id") else null,
                        path = o.getString("path"),
                        tmb = o.optString("tmb"),
                        name = o.optString("name"),
                        mediaCnt = o.optInt("mediaCnt"),
                        modified = o.optLong("modified"),
                        taken = o.optLong("taken"),
                        size = o.optLong("size"),
                        location = o.optInt("location"),
                        types = o.optInt("types"),
                        sortValue = o.optString("sortValue"),
                        subfoldersCount = o.optInt("subfoldersCount"),
                        subfoldersMediaCount = o.optInt("subfoldersMediaCount"),
                        containsMediaFilesDirectly = o.optBoolean("containsMediaFilesDirectly", true),
                    )
                )
            }
            if (dirs.isEmpty()) null else dirs
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, dirs: List<Directory>) {
        try {
            val arr = JSONArray()
            dirs.forEach { d ->
                arr.put(JSONObject().apply {
                    d.id?.let { put("id", it) }
                    put("path", d.path)
                    put("tmb", d.tmb)
                    put("name", d.name)
                    put("mediaCnt", d.mediaCnt)
                    put("modified", d.modified)
                    put("taken", d.taken)
                    put("size", d.size)
                    put("location", d.location)
                    put("types", d.types)
                    put("sortValue", d.sortValue)
                    put("subfoldersCount", d.subfoldersCount)
                    put("subfoldersMediaCount", d.subfoldersMediaCount)
                    put("containsMediaFilesDirectly", d.containsMediaFilesDirectly)
                })
            }
            val json = JSONObject().put("key", key(context)).put("dirs", arr).toString()
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json)
            tmp.renameTo(f)
        } catch (ignored: Exception) {
        }
    }
}
