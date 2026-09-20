package org.fossify.gallery.helpers

import android.content.Context
import java.io.File

/**
 * Per-folder "nothing changed" cache (fast fork, 2026-09-20). A folder's directory mtime changes whenever a file is
 * added, removed or renamed inside it, so when the mtime AND the number of cached rows in the media DB match what the
 * last real scan recorded, the cached rows are served instead of walking the folder again (17k files in Camera,
 * 20k in Screenshots on the owner's phone). Pull-to-refresh forces one full rescan.
 */
object ScanCache {
    private const val PREFS = "scan_cache"

    @Volatile
    var forceNextScan = false

    /** folders whose last getFilesFrom() answer came from the DB (callers skip re-writing those rows) */
    val servedFromCache: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun dirMtime(path: String): Long = try { File(path).lastModified() } catch (e: Exception) { 0L }

    fun get(context: Context, path: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(path, null)

    fun put(context: Context, path: String, mtime: Long, count: Int, extra: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(path, "$mtime|$count|$extra").apply()
    }

    /** true when the stored signature matches the folder's current mtime, the cached row count and the scan options */
    fun matches(stored: String?, mtime: Long, count: Int, extra: String): Boolean {
        if (stored == null || mtime <= 0L) return false
        val parts = stored.split("|", limit = 3)
        return parts.size == 3 && parts[0] == mtime.toString() && parts[1] == count.toString() && parts[2] == extra
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
