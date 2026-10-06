package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.shouldFolderBeVisible

/**
 * FastGallery (fast16): the ONE visibility rule for every path that adds rows to the media DB or reads them back.
 * It is stock Fossify's own rule (String.shouldFolderBeVisible, the same call getFoldersToScan() uses): with hidden
 * items off, a folder is invisible when its name or any parent starts with a dot, when it or any parent holds a
 * .nomedia file, or when it is in the excluded folders list (included folders win, as in stock). A file is invisible
 * when its own name starts with a dot or its folder is invisible.
 *
 * Rule for the fork (see CONTRIBUTING.md "Hidden folders"): any new code that inserts media rows, serves rows from the
 * DB or lists folders must go through this filter. Upstream rescanned every folder through the filtered folder list on
 * every launch, so a stray row never stayed visible; the fork's caches keep rows for a long time, so a row that slips
 * past the filter once would stay.
 */
object MediaVisibility {
    class Filter(
        private val showHidden: Boolean,
        private val excludedPaths: MutableSet<String>,
        private val includedPaths: MutableSet<String>
    ) {
        private val noMediaStatuses = HashMap<String, Boolean>()
        private val folderCache = HashMap<String, Boolean>()

        @Synchronized
        fun isFolderVisible(folder: String): Boolean {
            if (folder.isEmpty() || !folder.startsWith('/')) {
                return true   // FAVORITES, RECYCLE_BIN and other pseudo folders are not real paths
            }
            return folderCache.getOrPut(folder.trimEnd('/')) {
                folder.trimEnd('/').shouldFolderBeVisible(excludedPaths, includedPaths, showHidden, noMediaStatuses) { path, hasNoMedia ->
                    noMediaStatuses[path] = hasNoMedia
                }
            }
        }

        fun isFileVisible(path: String): Boolean {
            val name = path.substringAfterLast('/')
            if (!showHidden && name.startsWith('.')) {
                return false
            }
            return isFolderVisible(path.substringBeforeLast('/'))
        }
    }

    fun forContext(context: Context): Filter {
        val config = context.config
        val excluded = if (config.temporarilyShowExcluded) HashSet() else HashSet(config.excludedFolders)
        return Filter(config.shouldShowHidden, excluded, HashSet(config.includedFolders))
    }
}
