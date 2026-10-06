package org.fossify.gallery.helpers

import kotlin.math.abs

/**
 * FastGallery (fast16): the dates a media row must carry, the way a stock Fossify full scan computes them:
 * last modified = MediaStore DATE_MODIFIED (else the file's own mtime), date taken = the date the user fixed in the app,
 * else MediaStore DATE_TAKEN, else the last modified date. Never the moment the row was written.
 *
 * Why (10-06-2026): upstream's addPathToDB stamped rows from MediaStore change notifications with
 * System.currentTimeMillis() and relied on the next full scan to overwrite them. The fork's scan cache keeps rows, so
 * 2025 photos that Google Photos touched (restored from its trash) were dated "today" and sat on top of Camera.
 */
object MediaDates {
    /** a stored date this far from MediaStore's is wrong, not rounding */
    const val TOLERANCE_MS = 60_000L

    fun pick(
        mediaStoreModifiedMs: Long?, mediaStoreTakenMs: Long?, fileMtimeMs: Long, fixedTakenMs: Long?, fileName: String? = null
    ): Pair<Long, Long> {
        val modified = mediaStoreModifiedMs?.takeIf { it > 0L } ?: fileMtimeMs
        val taken = fixedTakenMs?.takeIf { it > 0L } ?: mediaStoreTakenMs?.takeIf { it > 0L }
            ?: fileName?.let { pixelNameTime(it) } ?: modified
        return modified to taken
    }

    private val PIXEL_NAME = Regex("""^PXL_(\d{8})_(\d{9})""")

    /** Pixel camera names carry the capture time in UTC: PXL_20250216_162532939.jpg = 2025-02-16 16:25:32.939 UTC */
    fun pixelNameTime(fileName: String): Long? {
        val m = PIXEL_NAME.find(fileName) ?: return null
        return try {
            val f = java.text.SimpleDateFormat("yyyyMMddHHmmssSSS", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            f.isLenient = false
            f.parse(m.groupValues[1] + m.groupValues[2])?.time
        } catch (e: Exception) {
            null
        }
    }

    /** null when the stored row agrees with MediaStore; otherwise the corrected (modified, taken) */
    fun correction(
        dbModifiedMs: Long, mediaStoreModifiedMs: Long, mediaStoreTakenMs: Long?, fixedTakenMs: Long?, fileName: String? = null
    ): Pair<Long, Long>? {
        if (mediaStoreModifiedMs <= 0L || dbModifiedMs == 0L) return null
        if (abs(dbModifiedMs - mediaStoreModifiedMs) <= TOLERANCE_MS) return null
        return pick(mediaStoreModifiedMs, mediaStoreTakenMs, mediaStoreModifiedMs, fixedTakenMs, fileName)
    }
}
