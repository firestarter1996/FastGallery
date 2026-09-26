package org.fossify.gallery.helpers

/**
 * FastGallery: the MediaStore-wide last-modified / date-taken maps (50k+ rows each, ~6 s together on a 68k library)
 * are only needed when a folder really has to be walked file by file, which the scan cache and the incremental
 * rescan make rare, so they are built on first use instead of before every scan.
 */
class LazyScanMaps(private val fetcher: MediaFetcher) {
    val lastModifieds: HashMap<String, Long> by lazy { fetcher.getLastModifieds() }
    val dateTakens: HashMap<String, Long> by lazy { fetcher.getDateTakens() }
}
