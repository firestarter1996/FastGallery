package org.fossify.gallery.helpers

import android.content.Context
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import org.fossify.commons.helpers.isRPlus
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.dateTakensDB
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.updateDirectoryPath
import org.fossify.gallery.models.Medium

/**
 * FastGallery (fast16): keeps the media DB the way a stock full scan would leave it. Runs on the scan thread of
 * MainActivity before the folders are rechecked (so the album tiles pick up the result in the same pass).
 *
 * 1. Hidden purge: with hidden items off, rows (and album rows) of folders that MediaVisibility says are invisible are
 *    deleted from the app's DB: Android's .thumbnails cache, .recycle, folders with .nomedia, excluded folders. Only
 *    DB rows: no file on the phone is ever touched. Runs again whenever the hidden/excluded/included settings change.
 * 2. Date repair: rows whose last modified date disagrees with MediaStore by more than two seconds get the dates a full
 *    scan would give them (MediaDates). The first run checks every row; later runs only the files MediaStore changed
 *    since (GENERATION_MODIFIED), which costs one small query and is skipped while MediaStore's generation is unchanged.
 */
object DbRepair {
    private const val PREFS = "fast_repairs"
    private const val PURGE_KEY = "hidden_purge"
    private const val DATES_KEY = "dates_generation"
    private const val VERSION = 2   // 2: dates checked to the second (v1 let rows stamped up to a minute late pass)

    /** returns the folders whose rows changed */
    /**
     * [refreshFolders]: also rebuild the album rows of the changed folders and drop the launch snapshot (used when no
     * MainActivity scan follows, e.g. right after an update); MainActivity's own scan does that itself.
     */
    @Synchronized
    fun run(context: Context, refreshFolders: Boolean = false): Set<String> {
        if (PerfTrace.legacy) return emptySet()
        val changed = HashSet<String>()
        val redated = HashSet<String>()
        try {
            purgeHidden(context, changed)
        } catch (ignored: Exception) {
        }
        try {
            repairDates(context, redated)
        } catch (ignored: Exception) {
        }
        changed.addAll(redated)
        changed.forEach { MediaSnapshot.delete(context, it) }
        if (refreshFolders && changed.isNotEmpty()) {
            // only folders that stay visible get their album row rebuilt (a purged folder must not be scanned again)
            val filter = MediaVisibility.forContext(context)
            redated.filter { java.io.File(it).isDirectory && filter.isFolderVisible(it) }.forEach {
                try {
                    context.updateDirectoryPath(it)
                } catch (ignored: Exception) {
                }
            }
            DirSnapshot.delete(context)
        }
        return changed
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun purgeHidden(context: Context, changed: MutableSet<String>) {
        val config = context.config
        if (config.shouldShowHidden) return   // hidden items are wanted (also while shown temporarily): nothing to purge
        val key = listOf(
            VERSION, config.temporarilyShowExcluded, config.excludedFolders.sorted().hashCode(), config.includedFolders.sorted().hashCode()
        ).joinToString("|")
        val prefs = prefs(context)
        if (prefs.getString(PURGE_KEY, null) == key) return

        val started = PerfTrace.sinceStart()
        val filter = MediaVisibility.forContext(context)
        val folders = LinkedHashSet<String>()
        folders.addAll(context.mediaDB.getVisibleParentPaths())
        context.directoryDB.getAll().forEach { folders.add(it.path) }
        var rows = 0
        var purged = 0
        folders.filter { it.startsWith('/') && !filter.isFolderVisible(it) }.forEach { folder ->
            rows += context.mediaDB.deleteVisibleMediaInFolder(folder)
            context.directoryDB.deleteDirPath(folder)
            ScanCache.remove(context, folder)
            changed.add(folder)
            purged++
        }
        prefs.edit().putString(PURGE_KEY, key).apply()
        PerfTrace.mark("db_purge_hidden", "folders=$purged rows=$rows took=${PerfTrace.sinceStart() - started}")
    }

    private fun repairDates(context: Context, changed: MutableSet<String>) {
        val prefs = prefs(context)
        val stored = prefs.getString(DATES_KEY, null)
        val generation = if (isRPlus()) {
            try {
                MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } catch (e: Exception) {
                -1L
            }
        } else {
            -1L
        }
        val current = "$VERSION|$generation"
        if (stored == current && generation >= 0L) return
        val sinceGeneration = stored?.split("|")?.takeIf { it.size == 2 && it[0] == VERSION.toString() }?.get(1)?.toLongOrNull()
        val incremental = generation >= 0L && sinceGeneration != null && sinceGeneration >= 0L && sinceGeneration <= generation
        if (stored != null && generation < 0L) return   // no generation (pre Android 11): the one full pass was enough

        val started = PerfTrace.sinceStart()
        val fixed = HashMap<String, Long>()
        try {
            context.dateTakensDB.getAllDateTakens().forEach { fixed[it.fullPath] = it.taken }
        } catch (ignored: Exception) {
        }

        // MediaStore's view of the files: all media on the first run, else only those changed since the last run
        val mediaStore = ArrayList<Triple<String, Long, Long>>()
        val projection = arrayOf(Images.Media.DATA, Images.Media.DATE_MODIFIED, Images.Media.DATE_TAKEN)
        val typeFilter = "${Files.FileColumns.MEDIA_TYPE} IN (${Files.FileColumns.MEDIA_TYPE_IMAGE}, ${Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val selection = if (incremental) "$typeFilter AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?" else typeFilter
        val args = if (incremental) arrayOf(sinceGeneration.toString()) else null
        context.contentResolver.query(Files.getContentUri("external"), projection, selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0) ?: continue
                mediaStore.add(Triple(path, cursor.getLong(1) * 1000, cursor.getLong(2)))
            }
        }

        val rowsByPath: Map<String, Medium>? = if (incremental) null else context.mediaDB.getAllVisibleMedia().associateBy { it.path }
        val updates = ArrayList<Medium>()
        mediaStore.forEach { (path, modified, taken) ->
            val row = if (rowsByPath != null) rowsByPath[path] else context.mediaDB.getMediumByPath(path)?.takeIf { it.deletedTS == 0L }
            if (row != null) {
                MediaDates.correction(row.modified, modified, taken, fixed[path], row.name)?.let { (newModified, newTaken) ->
                    row.modified = newModified
                    row.taken = newTaken
                    updates.add(row)
                    changed.add(row.parentPath)
                }
            }
        }
        if (updates.isNotEmpty()) {
            updates.forEach { context.mediaDB.updateDates(it.path, it.modified, it.taken) }
        }
        prefs.edit().putString(DATES_KEY, current).apply()
        PerfTrace.mark(
            "db_repair_dates",
            "mode=${if (incremental) "since" else "full"} checked=${mediaStore.size} fixed=${updates.size} took=${PerfTrace.sinceStart() - started}"
        )
    }
}
