package org.fossify.gallery

import com.bumptech.glide.Glide
import com.github.ajalt.reprint.core.Reprint
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response
import org.fossify.commons.FossifyApp
import org.fossify.gallery.helpers.PerfTrace

class App : FossifyApp() {

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        PerfTrace.mark("app_onCreate")
        super.onCreate()
        PerfTrace.init(this)
        // FastGallery: build Glide (registry, caches, executors) off the main thread while the first activity starts,
        // instead of on the main thread during the first album-cover bind
        if (!PerfTrace.legacy) {
            Thread { Glide.get(this) }.start()
        }
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }
}
