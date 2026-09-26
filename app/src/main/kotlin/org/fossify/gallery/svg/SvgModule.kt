package org.fossify.gallery.svg

import android.content.Context
import android.graphics.drawable.PictureDrawable

import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG

import java.io.InputStream

@GlideModule
class SvgModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder()).append(InputStream::class.java, SVG::class.java, SvgDecoder())
    }

    // fast fork (2026-09-20): Glide's default 250 MB thumbnail cache filled up on a 68k-file library, so every scroll
    // re-decoded 12 MP photos. 3 GB keeps roughly the whole library's thumbnails.
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, 3L * 1024 * 1024 * 1024))
        // FastGallery: Glide reads its disk cache on ONE thread by default, so a cold launch drew the album covers
        // one per frame; 4 readers load the whole first screen of covers at once.
        if (!org.fossify.gallery.helpers.PerfTrace.legacy) {
            builder.setDiskCacheExecutor(GlideExecutor.newDiskCacheBuilder().setThreadCount(4).build())
        }
    }

    override fun isManifestParsingEnabled() = false
}
