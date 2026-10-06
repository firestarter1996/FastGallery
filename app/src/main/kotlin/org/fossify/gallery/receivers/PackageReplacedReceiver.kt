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
        // not held with goAsync(): on the 8 Pro (70k rows) the work outlived the broadcast timeout and Android killed the
        // process as a background ANR. The repair is idempotent and MainActivity runs it again if this one is cut short.
        val app = context.applicationContext
        ensureBackgroundThread {
            DbRepair.run(app, refreshFolders = true)
        }
    }
}
