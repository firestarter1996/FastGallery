package org.fossify.gallery.helpers

import android.content.Context
import android.provider.MediaStore
import org.fossify.commons.helpers.isRPlus
import org.fossify.gallery.extensions.config

/**
 * FastGallery: remembers the MediaStore generation (and the folder-visibility settings) at the last complete
 * new-folder discovery, so an unchanged library skips the ~7 s MediaStore-wide walk on the next launch.
 */
object DiscoveryGate {
    private const val PREFS = "scan_cache"
    private const val KEY = "__discovery_gate__"

    fun currentKey(context: Context): String? {
        if (!isRPlus()) return null
        return try {
            val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
            val gen = MediaStore.getGeneration(context, volume)
            val ver = MediaStore.getVersion(context, volume)
            val config = context.config
            listOf(
                ver, gen, config.showHiddenMedia || config.temporarilyShowHidden, config.temporarilyShowExcluded,
                config.excludedFolders.sorted().hashCode(), config.includedFolders.sorted().hashCode(), config.filterMedia,
                config.OTGPath
            ).joinToString("|")
        } catch (e: Exception) {
            null
        }
    }

    fun isUnchanged(context: Context, key: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) == key

    fun save(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key).apply()
    }
}
