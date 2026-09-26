package org.fossify.gallery.svg

import android.content.Context
import android.graphics.drawable.PictureDrawable

import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG

import java.io.File
import java.io.InputStream

@GlideModule
class SvgModule : AppGlideModule() {
    companion object {
        const val THUMB_CACHE_DIR = "thumbnail_cache"

        /** removes Glide's old cache/image_manager_disk_cache once (the cache moved to no_backup/) */
        fun deleteOldCacheOnce(context: Context) {
            val prefs = context.getSharedPreferences("thumbnail_preloader", Context.MODE_PRIVATE)
            if (prefs.getBoolean("old_cache_deleted", false)) return
            try {
                File(context.cacheDir, "image_manager_disk_cache").deleteRecursively()
            } catch (ignored: Exception) {
            }
            prefs.edit().putBoolean("old_cache_deleted", true).apply()
        }
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder()).append(InputStream::class.java, SVG::class.java, SvgDecoder())
    }

    // fast fork (2026-09-20): Glide's default 250 MB thumbnail cache filled up on a 68k-file library, so every scroll
    // re-decoded 12 MP photos. 3 GB keeps roughly the whole library's thumbnails.
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // FastGallery 2026-09-26: 4 GB (the nightly pre-load fills ~2.6 GB for the 44k visible files), and it lives in no_backup/ instead of cache/, because Android empties app cache
        // dirs under storage pressure (the phone is ~89% full: the thumbnail cache shrank from 48 MB to 7.5 MB within
        // an hour), which threw away the thumbnails and the nightly pre-load's work.
        builder.setDiskCache(DiskLruCacheFactory(File(context.noBackupFilesDir, THUMB_CACHE_DIR).absolutePath, 4L * 1024 * 1024 * 1024))
        // FastGallery: Glide reads its disk cache on ONE thread by default, so a cold launch drew the album covers
        // one per frame; 4 readers load the whole first screen of covers at once.
        if (!org.fossify.gallery.helpers.PerfTrace.legacy) {
            builder.setDiskCacheExecutor(GlideExecutor.newDiskCacheBuilder().setThreadCount(4).build())
        }
    }

    override fun isManifestParsingEnabled() = false
}
