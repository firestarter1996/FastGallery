package org.fossify.gallery.extensions

import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.TransitionFactory

/**
 * Cross fade transition option that disabled fading when loading from cache.
 */
fun getOptionalCrossFadeTransition(duration: Int): DrawableTransitionOptions {
    return DrawableTransitionOptions.with(
        TransitionFactory { dataSource, isFirstResource ->
            // FastGallery (fast9): nothing from a cache fades in, only freshly decoded files get the (shorter) fade
            if (dataSource == DataSource.MEMORY_CACHE || dataSource == DataSource.RESOURCE_DISK_CACHE || dataSource == DataSource.DATA_DISK_CACHE) {
                return@TransitionFactory null
            }
            DrawableCrossFadeFactory.Builder(duration).build().build(dataSource, isFirstResource)
        }
    )
}

