package org.fossify.gallery.helpers

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.text.format.DateFormat
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.gallery.R
import org.fossify.gallery.extensions.*
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import org.fossify.gallery.models.ThumbnailSection
import java.io.File
import java.util.Calendar
import java.util.Locale

class MediaFetcher(val context: Context) {
    var shouldStop = false

    // on Android 11 we fetch all files at once from MediaStore and have it split by folder, use it if available
    fun getFilesFrom(
        curPath: String, isPickImage: Boolean, isPickVideo: Boolean, getProperDateTaken: Boolean, getProperLastModified: Boolean,
        getProperFileSize: Boolean, favoritePaths: ArrayList<String>, getVideoDurations: Boolean,
        lastModifieds: HashMap<String, Long>, dateTakens: HashMap<String, Long>, android11Files: HashMap<String, ArrayList<Medium>>?,
        lazyMaps: LazyScanMaps? = null
    ): ArrayList<Medium> {
        val filterMedia = context.config.filterMedia
        if (filterMedia == 0) {
            return ArrayList()
        }

        val curMedia = ArrayList<Medium>()
        if (context.isPathOnOTG(curPath)) {
            if (context.hasOTGConnected()) {
                val newMedia = getMediaOnOTG(curPath, isPickImage, isPickVideo, filterMedia, favoritePaths, getVideoDurations)
                curMedia.addAll(newMedia)
            }
        } else {
            if (curPath != FAVORITES && curPath != RECYCLE_BIN && isRPlus() && !isExternalStorageManager()) {
                if (android11Files?.containsKey(curPath.lowercase(Locale.getDefault())) == true) {
                    curMedia.addAll(android11Files[curPath.lowercase(Locale.getDefault())]!!)
                } else if (android11Files == null) {
                    val files = getAndroid11FolderMedia(isPickImage, isPickVideo, favoritePaths, false, getProperDateTaken, dateTakens)
                    if (files.containsKey(curPath.lowercase(Locale.getDefault()))) {
                        curMedia.addAll(files[curPath.lowercase(Locale.getDefault())]!!)
                    }
                }
            }

            // fast fork: an unchanged folder (same dir mtime + same cached row count + same scan options) is served from the DB.
            // The signature deliberately ignores isPickImage/isPickVideo (2026-09-20 fast4): a file-picker launch used to get its
            // own signature, so every picker open missed the cache and rescanned all 68k files (~30 s). Picker launches now hit
            // the same cache and just filter the cached rows by type; they never WRITE the cache, because their row count is
            // type-filtered and would poison the signature for normal launches.
            val isPicker = isPickImage || isPickVideo
            val scanExtra = "$filterMedia|$getProperDateTaken|$getProperLastModified|$getProperFileSize|$getVideoDurations|${context.config.shouldShowHidden}"
            val cacheable = curMedia.isEmpty() && curPath != FAVORITES && curPath != RECYCLE_BIN && !ScanCache.forceNextScan
            val dirMtime = if (cacheable) ScanCache.dirMtime(curPath) else 0L
            if (cacheable && dirMtime > 0L) {
                val stored = ScanCache.get(context, curPath)
                if (stored != null) {
                    // FastGallery: hidden rows (e.g. Google Photos' ".trashed-*" files) are skipped by the scan but were never
                    // purged from the DB, so the Camera/Screenshots row counts never matched and the cache always missed.
                    // Compare and serve only the rows a scan would produce.
                    val cachedAll = context.mediaDB.getMediaFromPath(curPath)
                    val cached = if (PerfTrace.legacy || context.config.shouldShowHidden) {
                        cachedAll
                    } else {
                        cachedAll.filter { !it.name.startsWith('.') }
                    }
                    if (ScanCache.matches(stored, dirMtime, cached.size, scanExtra)) {
                        PerfTrace.mark("scan_cache_hit", "n=${cached.size} $curPath")
                        ScanCache.servedFromCache.add(curPath)
                        curMedia.addAll(when {
                            isPickImage && !isPickVideo -> cached.filter { !it.isVideo() }
                            isPickVideo && !isPickImage -> cached.filter { it.isVideo() }
                            else -> cached
                        })
                        sortMedia(curMedia, context.config.getFolderSorting(curPath))
                        return curMedia
                    }

                    // FastGallery: the folder changed (new photo/screenshot, delete, rename) but the DB still holds exactly what
                    // the last scan found, so only the difference is examined instead of re-stat'ing every file (Camera
                    // 17.8k files took ~10 s per launch, Screenshots 20k ~6-13 s, after every new photo/screenshot).
                    if (!isPicker && !PerfTrace.legacy && ScanCache.matchesExceptMtime(stored, cached.size, scanExtra)) {
                        val updated = rescanChangedFolder(
                            curPath, cached, filterMedia, getProperDateTaken, getProperLastModified, getProperFileSize,
                            favoritePaths, getVideoDurations
                        )
                        if (updated != null) {
                            ScanCache.servedFromCache.remove(curPath)
                            if (!shouldStop) {
                                ScanCache.put(context, curPath, dirMtime, updated.size, scanExtra)
                            }
                            curMedia.addAll(updated)
                            sortMedia(curMedia, context.config.getFolderSorting(curPath))
                            return curMedia
                        }
                    }
                }
            }

            if (curMedia.isEmpty() && cacheable && curPath != FAVORITES && curPath != RECYCLE_BIN) {
                PerfTrace.mark("scan_cache_miss", "mtime=$dirMtime stored=${ScanCache.get(context, curPath)} $curPath")
            }
            if (curMedia.isEmpty()) {
                // FastGallery: Favorites (a handful of files) gets its dates from a query for just those paths, instead of
                // forcing the 50k-row global maps to be built (3.5 s, and it is the first folder the rescan visits)
                var lazy = lazyMaps
                if (lazy != null && curPath == FAVORITES && favoritePaths.size <= 2000 && (getProperLastModified || getProperDateTaken)) {
                    val lm = HashMap<String, Long>()
                    val dt = HashMap<String, Long>()
                    queryDatesForPaths(favoritePaths, lm, dt)
                    try {
                        val favSet = favoritePaths.toHashSet()
                        context.dateTakensDB.getAllDateTakens().forEach { if (it.fullPath in favSet) dt[it.fullPath] = it.taken }
                    } catch (ignored: Exception) {
                    }
                    lastModifieds.putAll(lm)
                    dateTakens.putAll(dt)
                    lazy = null
                }
                if (curPath == RECYCLE_BIN) {
                    lazy = null   // recycle-bin rows come from the DB as they are; the date maps are never read for them
                }
                val lazyMaps = lazy
                // FastGallery: getMediaInFolder only reads these maps now, so no per-folder copy of the 50k-entry maps
                val newMedia = getMediaInFolder(
                    curPath, isPickImage, isPickVideo, filterMedia, getProperDateTaken, getProperLastModified, getProperFileSize,
                    favoritePaths, getVideoDurations,
                    when {
                        PerfTrace.legacy -> lastModifieds.clone() as HashMap<String, Long>
                        lazyMaps != null && getProperLastModified -> lazyMaps.lastModifieds
                        else -> lastModifieds
                    },
                    when {
                        PerfTrace.legacy -> dateTakens.clone() as HashMap<String, Long>
                        lazyMaps != null && getProperDateTaken -> lazyMaps.dateTakens
                        else -> dateTakens
                    }
                )

                if (curPath == FAVORITES && isRPlus() && !isExternalStorageManager()) {
                    val files =
                        getAndroid11FolderMedia(isPickImage, isPickVideo, favoritePaths, true, getProperDateTaken, dateTakens.clone() as HashMap<String, Long>)
                    newMedia.forEach { newMedium ->
                        for ((folder, media) in files) {
                            media.forEach { medium ->
                                if (medium.path == newMedium.path) {
                                    newMedium.size = medium.size
                                }
                            }
                        }
                    }
                }
                curMedia.addAll(newMedia)
                ScanCache.servedFromCache.remove(curPath)
                if (cacheable && dirMtime > 0L && !shouldStop && !isPicker) {
                    if (!PerfTrace.legacy) {
                        purgeStaleRows(curPath, newMedia)
                        // MainActivity only writes a folder's rows when its album tile changed, so a folder whose rows were
                        // missing from the DB (Pictures/Twitter: 0 of 689) was fully rescanned on every launch; store them here
                        try {
                            context.mediaDB.insertAll(newMedia)
                        } catch (ignored: Exception) {
                        }
                    }
                    ScanCache.put(context, curPath, dirMtime, newMedia.size, scanExtra)
                }
            }
        }

        sortMedia(curMedia, context.config.getFolderSorting(curPath))
        return curMedia
    }

    fun getFoldersToScan(): ArrayList<String> {
        return try {
            val OTGPath = context.config.OTGPath
            val folders = getLatestFileFolders()
            folders.addAll(arrayListOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString(),
                "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
            ).filter { context.getDoesFilePathExist(it, OTGPath) })

            val filterMedia = context.config.filterMedia
            val uri = Files.getContentUri("external")
            val projection = arrayOf(Images.Media.DATA)
            val selection = getSelectionQuery(filterMedia)
            val selectionArgs = getSelectionArgsQuery(filterMedia).toTypedArray()
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            folders.addAll(parseCursor(cursor!!))

            val config = context.config
            val shouldShowHidden = config.shouldShowHidden
            val excludedPaths = if (config.temporarilyShowExcluded) {
                HashSet()
            } else {
                config.excludedFolders
            }

            val includedPaths = config.includedFolders

            val folderNoMediaStatuses = HashMap<String, Boolean>()
            val distinctPathsMap = HashMap<String, String>()
            val distinctPaths = folders.distinctBy {
                when {
                    distinctPathsMap.containsKey(it) -> distinctPathsMap[it]
                    else -> {
                        val distinct = it.getDistinctPath()
                        distinctPathsMap[it.getParentPath()] = distinct.getParentPath()
                        distinct
                    }
                }
            }

            val noMediaFolders = context.getNoMediaFoldersSync()
            noMediaFolders.forEach { folder ->
                folderNoMediaStatuses["$folder/$NOMEDIA"] = true
            }

            distinctPaths.filter {
                it.shouldFolderBeVisible(excludedPaths, includedPaths, shouldShowHidden, folderNoMediaStatuses) { path, hasNoMedia ->
                    folderNoMediaStatuses[path] = hasNoMedia
                }
            }.toMutableList() as ArrayList<String>
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun getLatestFileFolders(): LinkedHashSet<String> {
        val uri = Files.getContentUri("external")
        val projection = arrayOf(Images.ImageColumns.DATA)
        val parents = LinkedHashSet<String>()
        var cursor: Cursor? = null
        try {
            if (isRPlus()) {
                val bundle = Bundle().apply {
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 10)
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(BaseColumns._ID))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                }

                cursor = context.contentResolver.query(uri, projection, bundle, null)
                if (cursor?.moveToFirst() == true) {
                    do {
                        val path = cursor.getStringValue(Images.ImageColumns.DATA) ?: continue
                        parents.add(path.getParentPath())
                    } while (cursor.moveToNext())
                }
            } else {
                val sorting = "${BaseColumns._ID} DESC LIMIT 10"
                cursor = context.contentResolver.query(uri, projection, null, null, sorting)
                if (cursor?.moveToFirst() == true) {
                    do {
                        val path = cursor.getStringValue(Images.ImageColumns.DATA) ?: continue
                        parents.add(path.getParentPath())
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return parents
    }

    private fun getSelectionQuery(filterMedia: Int): String {
        val query = StringBuilder()
        if (filterMedia and TYPE_IMAGES != 0) {
            photoExtensions.forEach {
                query.append("${Images.Media.DATA} LIKE ? OR ")
            }
        }

        if (filterMedia and TYPE_PORTRAITS != 0) {
            query.append("${Images.Media.DATA} LIKE ? OR ")
            query.append("${Images.Media.DATA} LIKE ? OR ")
        }

        if (filterMedia and TYPE_VIDEOS != 0) {
            videoExtensions.forEach {
                query.append("${Images.Media.DATA} LIKE ? OR ")
            }
        }

        if (filterMedia and TYPE_GIFS != 0) {
            query.append("${Images.Media.DATA} LIKE ? OR ")
        }

        if (filterMedia and TYPE_RAWS != 0) {
            rawExtensions.forEach {
                query.append("${Images.Media.DATA} LIKE ? OR ")
            }
        }

        if (filterMedia and TYPE_SVGS != 0) {
            query.append("${Images.Media.DATA} LIKE ? OR ")
        }

        return query.toString().trim().removeSuffix("OR")
    }

    private fun getSelectionArgsQuery(filterMedia: Int): ArrayList<String> {
        val args = ArrayList<String>()
        if (filterMedia and TYPE_IMAGES != 0) {
            photoExtensions.forEach {
                args.add("%$it")
            }
        }

        if (filterMedia and TYPE_PORTRAITS != 0) {
            args.add("%.jpg")
            args.add("%.jpeg")
        }

        if (filterMedia and TYPE_VIDEOS != 0) {
            videoExtensions.forEach {
                args.add("%$it")
            }
        }

        if (filterMedia and TYPE_GIFS != 0) {
            args.add("%.gif")
        }

        if (filterMedia and TYPE_RAWS != 0) {
            rawExtensions.forEach {
                args.add("%$it")
            }
        }

        if (filterMedia and TYPE_SVGS != 0) {
            args.add("%.svg")
        }

        return args
    }

    private fun parseCursor(cursor: Cursor): LinkedHashSet<String> {
        val foldersToIgnore = arrayListOf("/storage/emulated/legacy")
        val config = context.config
        val includedFolders = config.includedFolders
        val OTGPath = config.OTGPath
        val foldersToScan = config.everShownFolders.filter { it == FAVORITES || it == RECYCLE_BIN || context.getDoesFilePathExist(it, OTGPath) }.toHashSet()

        cursor.use {
            if (cursor.moveToFirst()) {
                do {
                    val path = cursor.getStringValue(Images.Media.DATA)
                    val parentPath = File(path).parent ?: continue
                    if (!includedFolders.contains(parentPath) && !foldersToIgnore.contains(parentPath)) {
                        foldersToScan.add(parentPath)
                    }
                } while (cursor.moveToNext())
            }
        }

        includedFolders.forEach {
            addFolder(foldersToScan, it)
        }

        return foldersToScan.toMutableSet() as LinkedHashSet<String>
    }

    private fun addFolder(curFolders: HashSet<String>, folder: String) {
        curFolders.add(folder)
        val files = File(folder).listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                addFolder(curFolders, file.absolutePath)
            }
        }
    }

    private fun getMediaInFolder(
        folder: String, isPickImage: Boolean, isPickVideo: Boolean, filterMedia: Int, getProperDateTaken: Boolean,
        getProperLastModified: Boolean, getProperFileSize: Boolean, favoritePaths: ArrayList<String>,
        getVideoDurations: Boolean, lastModifieds: HashMap<String, Long>, dateTakens: HashMap<String, Long>,
        onlyFiles: List<File>? = null
    ): ArrayList<Medium> {
        val media = ArrayList<Medium>()
        val isRecycleBin = folder == RECYCLE_BIN
        val deletedMedia = if (isRecycleBin) {
            context.getUpdatedDeletedMedia()
        } else {
            ArrayList()
        }

        val config = context.config
        val checkProperFileSize = getProperFileSize || config.fileLoadingPriority == PRIORITY_COMPROMISE
        val checkFileExistence = config.fileLoadingPriority == PRIORITY_VALIDITY
        val showHidden = config.shouldShowHidden
        val showPortraits = filterMedia and TYPE_PORTRAITS != 0
        // FastGallery: an incremental rescan (onlyFiles) looks at a few new files; don't query sizes for the whole folder
        val fileSizes = if (onlyFiles == null && (checkProperFileSize || checkFileExistence)) getFolderSizes(folder) else HashMap()

        val files = when {
            onlyFiles != null -> ArrayList(onlyFiles)
            else -> when (folder) {
            FAVORITES -> favoritePaths.filter { showHidden || !it.contains("/.") }.map { File(it) }.toMutableList() as ArrayList<File>
            RECYCLE_BIN -> deletedMedia.map { File(it.path) }.toMutableList() as ArrayList<File>
            else -> File(folder).listFiles()?.toMutableList() ?: return media
            }
        }

        for (curFile in files) {
            var file = curFile
            if (shouldStop) {
                break
            }

            var path = file.absolutePath
            var isPortrait = false
            val isImage = path.isImageFast()
            val isVideo = if (isImage) false else path.isVideoFast()
            val isGif = if (isImage || isVideo) false else path.isGif()
            val isRaw = if (isImage || isVideo || isGif) false else path.isRawFast()
            val isSvg = if (isImage || isVideo || isGif || isRaw) false else path.isSvg()

            if (!isImage && !isVideo && !isGif && !isRaw && !isSvg) {
                if (showPortraits && file.name.startsWith("img_", true) && file.isDirectory) {
                    val portraitFiles = file.listFiles() ?: continue
                    val cover = portraitFiles.firstOrNull { it.name.contains("cover", true) } ?: portraitFiles.firstOrNull()
                    if (cover != null && !files.contains(cover)) {
                        file = cover
                        path = cover.absolutePath
                        isPortrait = true
                    } else {
                        continue
                    }
                } else {
                    continue
                }
            }

            if (isVideo && (isPickImage || filterMedia and TYPE_VIDEOS == 0))
                continue

            if (isImage && (isPickVideo || filterMedia and TYPE_IMAGES == 0))
                continue

            if (isGif && filterMedia and TYPE_GIFS == 0)
                continue

            if (isRaw && filterMedia and TYPE_RAWS == 0)
                continue

            if (isSvg && filterMedia and TYPE_SVGS == 0)
                continue

            val filename = file.name
            if (!showHidden && filename.startsWith('.'))
                continue

            var size = 0L
            if (checkProperFileSize || checkFileExistence) {
                var newSize = fileSizes.remove(path)
                if (newSize == null) {
                    newSize = file.length()
                }
                size = newSize
            }

            if ((checkProperFileSize || checkFileExistence) && size <= 0L) {
                continue
            }

            if (checkFileExistence && (!file.exists() || !file.isFile)) {
                continue
            }

            if (isRecycleBin) {
                deletedMedia.firstOrNull { it.path == path }?.apply {
                    media.add(this)
                }
            } else {
                var lastModified: Long
                var newLastModified = lastModifieds[path]
                if (newLastModified == null) {
                    newLastModified = if (getProperLastModified) {
                        file.lastModified()
                    } else {
                        0L
                    }
                }
                lastModified = newLastModified

                var dateTaken = lastModified
                val videoDuration = if (getVideoDurations && isVideo) context.getDuration(path) ?: 0 else 0

                if (getProperDateTaken) {
                    var newDateTaken = dateTakens[path]
                    if (newDateTaken == null) {
                        newDateTaken = if (getProperLastModified) {
                            lastModified
                        } else {
                            file.lastModified()
                        }
                    }
                    dateTaken = newDateTaken
                }

                val type = when {
                    isVideo -> TYPE_VIDEOS
                    isGif -> TYPE_GIFS
                    isRaw -> TYPE_RAWS
                    isSvg -> TYPE_SVGS
                    isPortrait -> TYPE_PORTRAITS
                    else -> TYPE_IMAGES
                }

                val isFavorite = favoritePaths.contains(path)
                val medium = Medium(null, filename, path, file.parent, lastModified, dateTaken, size, type, videoDuration, isFavorite, 0L, 0L)
                media.add(medium)
            }
        }

        return media
    }

    fun getAndroid11FolderMedia(
        isPickImage: Boolean,
        isPickVideo: Boolean,
        favoritePaths: ArrayList<String>,
        getFavoritePathsOnly: Boolean,
        getProperDateTaken: Boolean,
        dateTakens: HashMap<String, Long>
    ): HashMap<String, ArrayList<Medium>> {
        val media = HashMap<String, ArrayList<Medium>>()
        if (!isRPlus() || Environment.isExternalStorageManager()) {
            return media
        }

        val filterMedia = context.config.filterMedia
        val showHidden = context.config.shouldShowHidden

        val projection = arrayOf(
            Images.Media._ID,
            Images.Media.DISPLAY_NAME,
            Images.Media.DATA,
            Images.Media.DATE_MODIFIED,
            Images.Media.DATE_TAKEN,
            Images.Media.SIZE,
            MediaStore.MediaColumns.DURATION
        )

        val uri = Files.getContentUri("external")

        context.queryCursor(uri, projection) { cursor ->
            if (shouldStop) {
                return@queryCursor
            }

            try {
                val mediaStoreId = cursor.getLongValue(Images.Media._ID)
                val filename = cursor.getStringValue(Images.Media.DISPLAY_NAME)
                val path = cursor.getStringValue(Images.Media.DATA)
                if (getFavoritePathsOnly && !favoritePaths.contains(path)) {
                    return@queryCursor
                }

                val isPortrait = false
                val isImage = path.isImageFast()
                val isVideo = if (isImage) false else path.isVideoFast()
                val isGif = if (isImage || isVideo) false else path.isGif()
                val isRaw = if (isImage || isVideo || isGif) false else path.isRawFast()
                val isSvg = if (isImage || isVideo || isGif || isRaw) false else path.isSvg()

                if (!isImage && !isVideo && !isGif && !isRaw && !isSvg) {
                    return@queryCursor
                }

                if (isVideo && (isPickImage || filterMedia and TYPE_VIDEOS == 0))
                    return@queryCursor

                if (isImage && (isPickVideo || filterMedia and TYPE_IMAGES == 0))
                    return@queryCursor

                if (isGif && filterMedia and TYPE_GIFS == 0)
                    return@queryCursor

                if (isRaw && filterMedia and TYPE_RAWS == 0)
                    return@queryCursor

                if (isSvg && filterMedia and TYPE_SVGS == 0)
                    return@queryCursor

                if (!showHidden && filename.startsWith('.'))
                    return@queryCursor

                val size = cursor.getLongValue(Images.Media.SIZE)
                if (size <= 0L) {
                    return@queryCursor
                }

                val type = when {
                    isVideo -> TYPE_VIDEOS
                    isGif -> TYPE_GIFS
                    isRaw -> TYPE_RAWS
                    isSvg -> TYPE_SVGS
                    isPortrait -> TYPE_PORTRAITS
                    else -> TYPE_IMAGES
                }

                val lastModified = cursor.getLongValue(Images.Media.DATE_MODIFIED) * 1000
                var dateTaken = cursor.getLongValue(Images.Media.DATE_TAKEN)

                if (getProperDateTaken) {
                    dateTaken = dateTakens.remove(path) ?: lastModified
                }

                if (dateTaken == 0L) {
                    dateTaken = lastModified
                }

                val videoDuration = Math.round(cursor.getIntValue(MediaStore.MediaColumns.DURATION) / 1000.toDouble()).toInt()
                val isFavorite = favoritePaths.contains(path)
                val medium =
                    Medium(null, filename, path, path.getParentPath(), lastModified, dateTaken, size, type, videoDuration, isFavorite, 0L, mediaStoreId)
                val parent = medium.parentPath.lowercase(Locale.getDefault())
                val currentFolderMedia = media[parent]
                if (currentFolderMedia == null) {
                    media[parent] = ArrayList<Medium>()
                }

                media[parent]?.add(medium)
            } catch (e: Exception) {
            }
        }

        return media
    }

    private fun getMediaOnOTG(
        folder: String, isPickImage: Boolean, isPickVideo: Boolean, filterMedia: Int, favoritePaths: ArrayList<String>,
        getVideoDurations: Boolean
    ): ArrayList<Medium> {
        val media = ArrayList<Medium>()
        val files = context.getDocumentFile(folder)?.listFiles() ?: return media
        val checkFileExistence = context.config.fileLoadingPriority == PRIORITY_VALIDITY
        val showHidden = context.config.shouldShowHidden
        val OTGPath = context.config.OTGPath

        for (file in files) {
            if (shouldStop) {
                break
            }

            val filename = file.name ?: continue
            val isImage = filename.isImageFast()
            val isVideo = if (isImage) false else filename.isVideoFast()
            val isGif = if (isImage || isVideo) false else filename.isGif()
            val isRaw = if (isImage || isVideo || isGif) false else filename.isRawFast()
            val isSvg = if (isImage || isVideo || isGif || isRaw) false else filename.isSvg()

            if (!isImage && !isVideo && !isGif && !isRaw && !isSvg)
                continue

            if (isVideo && (isPickImage || filterMedia and TYPE_VIDEOS == 0))
                continue

            if (isImage && (isPickVideo || filterMedia and TYPE_IMAGES == 0))
                continue

            if (isGif && filterMedia and TYPE_GIFS == 0)
                continue

            if (isRaw && filterMedia and TYPE_RAWS == 0)
                continue

            if (isSvg && filterMedia and TYPE_SVGS == 0)
                continue

            if (!showHidden && filename.startsWith('.'))
                continue

            val size = file.length()
            if (size <= 0L || (checkFileExistence && !context.getDoesFilePathExist(file.uri.toString(), OTGPath)))
                continue

            val dateTaken = file.lastModified()
            val dateModified = file.lastModified()

            val type = when {
                isVideo -> TYPE_VIDEOS
                isGif -> TYPE_GIFS
                isRaw -> TYPE_RAWS
                isSvg -> TYPE_SVGS
                else -> TYPE_IMAGES
            }

            val path = Uri.decode(
                file.uri.toString().replaceFirst("${context.config.OTGTreeUri}/document/${context.config.OTGPartition}%3A", "${context.config.OTGPath}/")
            )
            val videoDuration = if (getVideoDurations) context.getDuration(path) ?: 0 else 0
            val isFavorite = favoritePaths.contains(path)
            val medium = Medium(null, filename, path, folder, dateModified, dateTaken, size, type, videoDuration, isFavorite, 0L, 0L)
            media.add(medium)
        }

        return media
    }

    /**
     * FastGallery incremental rescan of a folder whose mtime changed: keep the cached rows whose files are still listed,
     * drop the rest, and build rows only for the new names. Returns null (caller does a full scan) when the folder cannot
     * be listed or too many files are new.
     */
    private fun rescanChangedFolder(
        folder: String, cached: List<Medium>, filterMedia: Int, getProperDateTaken: Boolean, getProperLastModified: Boolean,
        getProperFileSize: Boolean, favoritePaths: ArrayList<String>, getVideoDurations: Boolean
    ): ArrayList<Medium>? {
        if (context.config.fileLoadingPriority == PRIORITY_VALIDITY) return null
        val names = File(folder).list() ?: return null
        val present = HashSet<String>(names.size * 2)
        names.forEach { present.add(it) }
        val cachedNames = HashSet<String>(cached.size * 2)
        val result = ArrayList<Medium>(names.size)
        for (medium in cached) {
            cachedNames.add(medium.name)
            if (medium.name in present) {
                result.add(medium)
            }
        }

        val keptCount = result.size
        val showHidden = context.config.shouldShowHidden
        val newFiles = names.filter { it !in cachedNames && (showHidden || !it.startsWith('.')) }.map { File(folder, it) }
        // (MediumDao.deleteMedia deletes by id, which these rows don't carry, so it is a no-op; delete by path)
        cached.filter { it.name !in present }.forEach {
            try {
                context.mediaDB.deleteMediumPath(it.path)
            } catch (ignored: Exception) {
            }
        }
        if (newFiles.size > 2000) return null
        if (newFiles.isNotEmpty()) {
            val lastModifieds = HashMap<String, Long>()
            val dateTakens = HashMap<String, Long>()
            if (getProperLastModified || getProperDateTaken) {
                queryDatesForPaths(newFiles.map { it.absolutePath }, lastModifieds, dateTakens)
                try {
                    context.dateTakensDB.getDateTakensFromPath(folder).forEach { dateTakens[it.fullPath] = it.taken }
                } catch (ignored: Exception) {
                }
            }

            val added = getMediaInFolder(
                folder, false, false, filterMedia, getProperDateTaken, getProperLastModified, getProperFileSize,
                favoritePaths, getVideoDurations, lastModifieds, dateTakens, onlyFiles = newFiles
            )
            result.addAll(added)
            try {
                context.mediaDB.insertAll(added)
            } catch (ignored: Exception) {
            }
        }
        PerfTrace.mark("incremental_rescan", "kept=$keptCount of ${cached.size} new=${newFiles.size} added=${result.size - keptCount} $folder")
        return result
    }

    /** MediaStore DATE_MODIFIED / DATE_TAKEN for just these paths (plus the app's own fixed date-takens) */
    private fun queryDatesForPaths(paths: List<String>, lastModifieds: HashMap<String, Long>, dateTakens: HashMap<String, Long>) {
        val uri = Files.getContentUri("external")
        val projection = arrayOf(Images.Media.DATA, Images.Media.DATE_MODIFIED, Images.Media.DATE_TAKEN)
        paths.chunked(500).forEach { chunk ->
            val selection = "${Images.Media.DATA} IN (${chunk.joinToString(",") { "?" }})"
            try {
                context.queryCursor(uri, projection, selection, chunk.toTypedArray()) { cursor ->
                    val path = cursor.getStringValue(Images.Media.DATA)
                    val modified = cursor.getLongValue(Images.Media.DATE_MODIFIED) * 1000
                    if (modified != 0L) lastModifieds[path] = modified
                    val taken = cursor.getLongValue(Images.Media.DATE_TAKEN)
                    if (taken != 0L) dateTakens[path] = taken
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * FastGallery: after a full walk, drop the folder's DB rows the walk did not find. Upstream's cleanup deletes by id
     * (rows read back from the DB have none), so stale rows piled up and the scan cache's row count never matched again.
     */
    private fun purgeStaleRows(folder: String, scanned: List<Medium>) {
        try {
            val showHidden = context.config.shouldShowHidden
            val found = HashSet<String>(scanned.size * 2)
            scanned.forEach { found.add(it.path) }
            context.mediaDB.getMediaFromPath(folder)
                .filter { (showHidden || !it.name.startsWith('.')) && it.path !in found }
                .forEach { context.mediaDB.deleteMediumPath(it.path) }
        } catch (ignored: Exception) {
        }
    }

    fun getFolderDateTakens(folder: String): HashMap<String, Long> {
        val dateTakens = HashMap<String, Long>()
        if (folder != FAVORITES) {
            val projection = arrayOf(
                Images.Media.DISPLAY_NAME,
                Images.Media.DATE_TAKEN
            )

            val uri = Files.getContentUri("external")
            val selection = "${Images.Media.DATA} LIKE ? AND ${Images.Media.DATA} NOT LIKE ?"
            val selectionArgs = arrayOf("$folder/%", "$folder/%/%")

            context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
                try {
                    val dateTaken = cursor.getLongValue(Images.Media.DATE_TAKEN)
                    if (dateTaken != 0L) {
                        val name = cursor.getStringValue(Images.Media.DISPLAY_NAME)
                        dateTakens["$folder/$name"] = dateTaken
                    }
                } catch (e: Exception) {
                }
            }
        }

        val dateTakenValues = try {
            if (folder == FAVORITES) {
                context.dateTakensDB.getAllDateTakens()
            } else {
                context.dateTakensDB.getDateTakensFromPath(folder)
            }
        } catch (e: Exception) {
            return dateTakens
        }

        dateTakenValues.forEach {
            dateTakens[it.fullPath] = it.taken
        }

        return dateTakens
    }

    fun getDateTakens(): HashMap<String, Long> {
        val dateTakens = HashMap<String, Long>()
        val projection = arrayOf(
            Images.Media.DATA,
            Images.Media.DATE_TAKEN
        )

        val uri = Files.getContentUri("external")

        try {
            context.queryCursor(uri, projection) { cursor ->
                try {
                    val dateTaken = cursor.getLongValue(Images.Media.DATE_TAKEN)
                    if (dateTaken != 0L) {
                        val path = cursor.getStringValue(Images.Media.DATA)
                        dateTakens[path] = dateTaken
                    }
                } catch (e: Exception) {
                }
            }

            val dateTakenValues = context.dateTakensDB.getAllDateTakens()

            dateTakenValues.forEach {
                dateTakens[it.fullPath] = it.taken
            }
        } catch (e: Exception) {
        }

        return dateTakens
    }

    fun getFolderLastModifieds(folder: String): HashMap<String, Long> {
        val lastModifieds = HashMap<String, Long>()
        if (folder != FAVORITES) {
            val projection = arrayOf(
                Images.Media.DISPLAY_NAME,
                Images.Media.DATE_MODIFIED
            )

            val uri = Files.getContentUri("external")
            val selection = "${Images.Media.DATA} LIKE ? AND ${Images.Media.DATA} NOT LIKE ?"
            val selectionArgs = arrayOf("$folder/%", "$folder/%/%")

            context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
                try {
                    val lastModified = cursor.getLongValue(Images.Media.DATE_MODIFIED) * 1000
                    if (lastModified != 0L) {
                        val name = cursor.getStringValue(Images.Media.DISPLAY_NAME)
                        lastModifieds["$folder/$name"] = lastModified
                    }
                } catch (e: Exception) {
                }
            }
        }

        return lastModifieds
    }

    fun getLastModifieds(): HashMap<String, Long> {
        val lastModifieds = HashMap<String, Long>()
        val projection = arrayOf(
            Images.Media.DATA,
            Images.Media.DATE_MODIFIED
        )

        val uri = Files.getContentUri("external")

        try {
            context.queryCursor(uri, projection) { cursor ->
                try {
                    val lastModified = cursor.getLongValue(Images.Media.DATE_MODIFIED) * 1000
                    if (lastModified != 0L) {
                        val path = cursor.getStringValue(Images.Media.DATA)
                        lastModifieds[path] = lastModified
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }

        return lastModifieds
    }

    private fun getFolderSizes(folder: String): HashMap<String, Long> {
        val sizes = HashMap<String, Long>()
        if (folder != FAVORITES) {
            val projection = arrayOf(
                Images.Media.DISPLAY_NAME,
                Images.Media.SIZE
            )

            val uri = Files.getContentUri("external")
            val selection = "${Images.Media.DATA} LIKE ? AND ${Images.Media.DATA} NOT LIKE ?"
            val selectionArgs = arrayOf("$folder/%", "$folder/%/%")

            context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
                try {
                    val size = cursor.getLongValue(Images.Media.SIZE)
                    if (size != 0L) {
                        val name = cursor.getStringValue(Images.Media.DISPLAY_NAME)
                        sizes["$folder/$name"] = size
                    }
                } catch (e: Exception) {
                }
            }
        }

        return sizes
    }

    fun sortMedia(media: ArrayList<Medium>, sorting: Int) {
        if (sorting and SORT_BY_RANDOM != 0) {
            media.shuffle()
            return
        }

        media.sortWith { o1, o2 ->
            o1 as Medium
            o2 as Medium
            var result = when {
                sorting and SORT_BY_NAME != 0 -> {
                    if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                        AlphanumericComparator().compare(o1.name.normalizeString().lowercase(Locale.getDefault()), o2.name.normalizeString().lowercase(Locale.getDefault()))
                    } else {
                        o1.name.normalizeString().lowercase(Locale.getDefault()).compareTo(o2.name.normalizeString().lowercase(Locale.getDefault()))
                    }
                }

                sorting and SORT_BY_PATH != 0 -> {
                    if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                        AlphanumericComparator().compare(o1.path.lowercase(Locale.getDefault()), o2.path.lowercase(Locale.getDefault()))
                    } else {
                        o1.path.lowercase(Locale.getDefault()).compareTo(o2.path.lowercase(Locale.getDefault()))
                    }
                }

                sorting and SORT_BY_SIZE != 0 -> o1.size.compareTo(o2.size)
                sorting and SORT_BY_DATE_MODIFIED != 0 -> o1.modified.compareTo(o2.modified)
                else -> o1.taken.compareTo(o2.taken)
            }

            if (sorting and SORT_DESCENDING != 0) {
                result *= -1
            }
            result
        }
    }

    fun groupMedia(media: ArrayList<Medium>, path: String): ArrayList<ThumbnailItem> {
        val pathToCheck = if (path.isEmpty()) SHOW_ALL else path
        val currentGrouping = context.config.getFolderGrouping(pathToCheck)
        if (currentGrouping and GROUP_BY_NONE != 0) {
            return media as ArrayList<ThumbnailItem>
        }

        val thumbnailItems = ArrayList<ThumbnailItem>()
        if (context.config.scrollHorizontally) {
            media.mapTo(thumbnailItems) { it }
            return thumbnailItems
        }

        val mediumGroups = LinkedHashMap<String, ArrayList<Medium>>()
        media.forEach {
            val key = it.getGroupingKey(currentGrouping)
            if (!mediumGroups.containsKey(key)) {
                mediumGroups[key] = ArrayList()
            }
            mediumGroups[key]!!.add(it)
        }

        val sortDescending = currentGrouping and GROUP_DESCENDING != 0
        val sorted = if (currentGrouping and GROUP_BY_LAST_MODIFIED_DAILY != 0 || currentGrouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0 ||
            currentGrouping and GROUP_BY_DATE_TAKEN_DAILY != 0 || currentGrouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0
        ) {
            mediumGroups.toSortedMap(if (sortDescending) compareByDescending {
                it.toLongOrNull() ?: 0L
            } else {
                compareBy { it.toLongOrNull() ?: 0L }
            })
        } else {
            mediumGroups.toSortedMap(if (sortDescending) compareByDescending { it } else compareBy { it })
        }

        mediumGroups.clear()
        for ((key, value) in sorted) {
            mediumGroups[key] = value
        }

        val today = formatDate(System.currentTimeMillis().toString(), true)
        val yesterday = formatDate((System.currentTimeMillis() - DAY_SECONDS * 1000).toString(), true)
        for ((key, value) in mediumGroups) {
            var currentGridPosition = 0
            val sectionKey = getFormattedKey(key, currentGrouping, today, yesterday, value.size)
            thumbnailItems.add(ThumbnailSection(sectionKey))

            value.forEach {
                it.gridPosition = currentGridPosition++
            }

            thumbnailItems.addAll(value)
        }

        return thumbnailItems
    }

    private fun getFormattedKey(key: String, grouping: Int, today: String, yesterday: String, count: Int): String {
        var result = when {
            grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0 || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0 -> getFinalDate(
                formatDate(key, true),
                today,
                yesterday
            )

            grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0 || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0 -> formatDate(key, false)
            grouping and GROUP_BY_FILE_TYPE != 0 -> getFileTypeString(key)
            grouping and GROUP_BY_EXTENSION != 0 -> key.uppercase(Locale.getDefault())
            grouping and GROUP_BY_FOLDER != 0 -> context.humanizePath(key)
            else -> key
        }

        if (result.isEmpty()) {
            result = context.getString(org.fossify.commons.R.string.unknown)
        }

        return if (grouping and GROUP_SHOW_FILE_COUNT != 0) {
            "$result ($count)"
        } else {
            result
        }
    }

    private fun getFinalDate(date: String, today: String, yesterday: String): String {
        return when (date) {
            today -> context.getString(org.fossify.commons.R.string.today)
            yesterday -> context.getString(org.fossify.commons.R.string.yesterday)
            else -> date
        }
    }

    private fun formatDate(timestamp: String, showDay: Boolean): String {
        return if (timestamp.areDigitsOnly()) {
            val cal = Calendar.getInstance(Locale.ENGLISH)
            cal.timeInMillis = timestamp.toLong()
            val format = if (showDay) context.config.dateFormat else "MMMM yyyy"
            DateFormat.format(format, cal).toString()
        } else {
            ""
        }
    }

    private fun getFileTypeString(key: String): String {
        val stringId = when (key.toInt()) {
            TYPE_IMAGES -> R.string.images
            TYPE_VIDEOS -> R.string.videos
            TYPE_GIFS -> R.string.gifs
            TYPE_RAWS -> R.string.raw_images
            TYPE_SVGS -> R.string.svgs
            else -> R.string.portraits
        }
        return context.getString(stringId)
    }
}
