package org.fossify.gallery.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.helpers.DbRepair

/**
 * FastGallery (fast16): right after an in place update, repair the media DB in the background (hidden folder rows out,
 * wrong dates fixed, album rows refreshed), so the first launch of the new build already shows correct albums and no
 * screen has to be opened for it. Same work MainActivity does at the start of every full load.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext
        ensureBackgroundThread {
            try {
                DbRepair.run(app, refreshFolders = true)
            } finally {
                pending.finish()
            }
        }
    }
}
