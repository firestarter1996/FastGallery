package org.fossify.gallery

import android.app.Activity
import android.os.Bundle
import com.bumptech.glide.Glide
import com.github.ajalt.reprint.core.Reprint
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response
import org.fossify.commons.FossifyApp
import org.fossify.gallery.helpers.PerfTrace
import org.fossify.gallery.jobs.ThumbnailPreloader
import org.fossify.gallery.svg.SvgModule

class App : FossifyApp() {
    companion object {
        /** FastGallery: activities currently started; the nightly thumbnail pre-load pauses while the UI is in use */
        @Volatile
        var startedActivities = 0
    }

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        PerfTrace.mark("app_onCreate")
        super.onCreate()
        PerfTrace.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // FastGallery: build Glide (registry, caches, executors) off the main thread while the first activity starts,
        // instead of on the main thread during the first album-cover bind
        if (!PerfTrace.legacy) {
            Thread { Glide.get(this) }.start()
        }
        Thread {
            ThumbnailPreloader.ensureScheduled(this)
            SvgModule.deleteOldCacheOnce(this)
        }.start()
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }
}
