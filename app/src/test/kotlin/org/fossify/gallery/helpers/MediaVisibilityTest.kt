package org.fossify.gallery.helpers

import org.fossify.commons.helpers.FAVORITES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * fast16 regression tests (10-06-2026): hidden folders must never be shown, and rows must never be dated "now".
 * Run: ./gradlew testFossDebugUnitTest
 */
class MediaVisibilityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(vararg parts: String): File = File(tmp.root, parts.joinToString("/")).apply { mkdirs() }

    private fun file(dir: File, name: String): String = File(dir, name).apply { writeText("x") }.absolutePath

    @Test
    fun hiddenFoldersAreNeverVisible() {
        val camera = dir("DCIM", "Camera")
        val thumbs = dir("Pictures", ".thumbnails")
        File(thumbs, ".nomedia").writeText("")
        val dotDir = dir(".recycle", "123", "Magisk", "res")
        val noMedia = dir("SwiftBackup", "icon_cache")
        File(noMedia, ".nomedia").writeText("")
        val underNoMedia = dir("Apps", "cache", "images")
        File(dir("Apps"), ".nomedia").writeText("")
        val excluded = dir("Download", "Music", "Album")

        val filter = MediaVisibility.Filter(false, mutableSetOf(dir("Download", "Music").absolutePath), mutableSetOf())

        assertTrue(filter.isFileVisible(file(camera, "PXL_20261006_013418935.jpg")))
        assertFalse("dot folder with .nomedia", filter.isFileVisible(file(thumbs, "1700000000000.jpg")))
        assertFalse("folder under a dot parent", filter.isFileVisible(file(dotDir, "icon.png")))
        assertFalse("folder holding .nomedia", filter.isFileVisible(file(noMedia, "icon.png")))
        assertFalse("parent holding .nomedia", filter.isFileVisible(file(underNoMedia, "a.jpg")))
        assertFalse("excluded parent", filter.isFileVisible(file(excluded, "cover.jpg")))
        assertFalse("hidden file in a visible folder", filter.isFileVisible(file(camera, ".trashed-1781405178-PXL_1.jpg")))
    }

    @Test
    fun showHiddenAndIncludedFoldersStillWork() {
        val thumbs = dir("Pictures", ".thumbnails")
        File(thumbs, ".nomedia").writeText("")
        val shown = MediaVisibility.Filter(true, mutableSetOf(), mutableSetOf())
        assertTrue(shown.isFileVisible(file(thumbs, "1.jpg")))

        val noMedia = dir("Kept")
        File(noMedia, ".nomedia").writeText("")
        val included = MediaVisibility.Filter(false, mutableSetOf(), mutableSetOf(noMedia.absolutePath))
        assertTrue(included.isFileVisible(file(noMedia, "1.jpg")))
    }

    @Test
    fun pseudoFoldersAreLeftAlone() {
        val filter = MediaVisibility.Filter(false, mutableSetOf(), mutableSetOf())
        assertTrue(filter.isFolderVisible(FAVORITES))
        assertTrue(filter.isFolderVisible(RECYCLE_BIN))
    }

    @Test
    fun rowDatesComeFromTheFileNeverFromNow() {
        val feb2025 = 1_739_723_134_000L
        val takenFeb2025 = 1_739_723_132_939L
        // MediaStore knows the file: its dates win
        assertEquals(feb2025 to takenFeb2025, MediaDates.pick(feb2025, takenFeb2025, 0L, null))
        // MediaStore not indexed yet: the file's own mtime, also as date taken
        assertEquals(feb2025 to feb2025, MediaDates.pick(null, null, feb2025, null))
        // MediaStore not indexed yet, Pixel camera name: the capture time in the name is the date taken
        assertEquals(feb2025 to 1_739_723_132_939L, MediaDates.pick(null, null, feb2025, null, "PXL_20250216_162532939.jpg"))
        assertEquals(1_739_723_132_939L, MediaDates.pixelNameTime("PXL_20250216_162532939.RESTORED.jpg"))
        assertNull(MediaDates.pixelNameTime("Screenshot_20261006-072726.png"))
        // a date the user fixed in the app wins for date taken
        assertEquals(feb2025 to 42L, MediaDates.pick(feb2025, takenFeb2025, 0L, 42L))
    }

    @Test
    fun wrongRowsAreCorrectedRightRowsLeftAlone() {
        val feb2025 = 1_739_723_134_000L
        val stampedToday = 1_791_291_561_000L   // 10-06-2026 04:59:21 UTC, what the 8 Pro DB held
        assertEquals(feb2025 to 1_739_723_132_939L, MediaDates.correction(stampedToday, feb2025, 1_739_723_132_939L, null))
        assertNull(MediaDates.correction(feb2025 + 2_000L, feb2025, null, null))
        // the 8 Pro case: MediaStore has no date taken yet, the name still gives February 2025, never today
        assertEquals(feb2025 to 1_739_723_132_939L, MediaDates.correction(stampedToday, feb2025, 0L, null, "PXL_20250216_162532939.jpg"))
        assertNull("rows scanned without dates are not touched", MediaDates.correction(0L, feb2025, null, null))
        assertNull("no MediaStore date, nothing to compare", MediaDates.correction(stampedToday, 0L, null, null))
    }
}
