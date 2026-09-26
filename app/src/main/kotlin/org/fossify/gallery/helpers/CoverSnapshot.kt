package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import com.bumptech.glide.Glide
import org.fossify.commons.extensions.isSvg
import org.fossify.gallery.extensions.buildThumbnailRequest
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.getDirsToShow
import org.fossify.gallery.extensions.getDistinctPath
import org.fossify.gallery.extensions.getSortedDirectories
import org.fossify.gallery.models.Directory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FastGallery (fast9): the first screen of album covers, saved as small image files next to the album-grid snapshot.
 *
 * On a cold launch Glide has to open its thumbnail disk cache before it can return a single cover, and opening it
 * means reading the whole DiskLruCache journal (one line per cached thumbnail, tens of thousands after the nightly
 * pre-load): the covers came ~150 ms after the albums, all at once, however fast the decode itself was.
 * These files are decoded on a background thread while MainActivity is being created and handed to the grid as the
 * Glide placeholder, so the covers are in the first frame; Glide's own (identical) bitmap replaces them without
 * a fade when it arrives. Files are keyed by cover path, its modified time and the exact request size, so a changed
 * cover or grid size simply misses and loads normally.
 */
object CoverSnapshot {
    private const val DIR_NAME = "cover_snapshot"
    private const val RELEASE_AFTER_MS = 10_000L
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val ready = CountDownLatch(1)
    @Volatile
    private var started = false
    @Volatile
    private var waited = false

    private fun dir(context: Context) = File(context.filesDir, DIR_NAME)

    private fun fileKey(dir: Directory, spec: ThumbSizes.Spec): String {
        val raw = "${dir.tmb}|${dir.getKey()}|${spec.width}x${spec.height}|${spec.crop}|${spec.round}|${spec.animate}"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun mapKey(tmb: String, signature: Any) = "$tmb|$signature"

    /** The directories whose covers are on the first screen, in display order (same order the grid uses). */
    private fun firstScreen(context: Context, dirs: ArrayList<Directory>, spec: ThumbSizes.Spec): List<Directory> {
        val cfg = context.config
        val copy = dirs.map { it.copy() } as ArrayList<Directory>
        val distinct = copy.distinctBy { it.path.getDistinctPath() }.toMutableList() as ArrayList<Directory>
        val shown = context.getDirsToShow(context.getSortedDirectories(distinct), copy, "")
        val dm = context.resources.displayMetrics
        val cols = (dm.widthPixels / spec.width).coerceIn(1, 20)
        val rows = (dm.heightPixels / spec.height).coerceIn(1, 40) + 1
        return shown.take(cols * rows).filter { it.tmb.isNotEmpty() && !it.tmb.isSvg() && !cfg.isFolderProtected(it.path) }
    }

    /**
     * Called at the start of a launcher MainActivity.onCreate: on a background thread, decodes the saved covers into memory (in parallel).
     * Glide's own copies are left to the grid's binds; starting them here too only competed with the first frame.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        Thread { load(context) }.start()
    }

    private fun load(context: Context) {
        try {
            // A/B switch for measuring: an empty files/perf_no_cover_snapshot turns this off
            if (File(context.filesDir, "perf_no_cover_snapshot").exists()) return
            val cfg = context.config
            if (cfg.showAll || cfg.defaultFolder.isNotEmpty()) return
            val spec = ThumbSizes.get(context, "folder") ?: return
            val dirs = DirSnapshot.load(context) ?: return
            val covers = firstScreen(context, dirs, spec)
            val folder = dir(context)
            val pool = Executors.newFixedThreadPool(4)
            val tasks = covers.map { d ->
                pool.submit {
                    val f = File(folder, fileKey(d, spec))
                    if (f.exists()) {
                        // HARDWARE: decoded straight into GPU memory here, so the first frame has no texture uploads
                        val opts = BitmapFactory.Options()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) opts.inPreferredConfig = Bitmap.Config.HARDWARE
                        BitmapFactory.decodeFile(f.absolutePath, opts)?.let {
                            if (it.width == spec.width && it.height == spec.height) bitmaps[mapKey(d.tmb, d.getKey())] = it
                        }
                    }
                }
            }
            tasks.forEach { it.get() }
            pool.shutdown()
            PerfTrace.mark("cover_snapshot_loaded", "n=${bitmaps.size}/${covers.size}")
        } catch (e: Throwable) {
            PerfTrace.mark("cover_snapshot_error", e.toString())
        } finally {
            ready.countDown()
            // only needed for the first screen of the launch; Glide's memory cache takes over after that
            Thread {
                try {
                    Thread.sleep(RELEASE_AFTER_MS)
                } catch (ignored: InterruptedException) {
                }
                bitmaps.clear()
            }.start()
        }
    }

    /**
     * The saved cover for this album as a placeholder drawable, or null. Waits up to 40 ms for the background decode
     * if it is still running (it normally finishes long before the first bind).
     */
    fun placeholder(context: Context, tmb: String, signature: Any): BitmapDrawable? {
        if (!started) return null
        if (ready.count > 0L && !waited) {
            waited = true
            try {
                ready.await(40, TimeUnit.MILLISECONDS)
            } catch (ignored: InterruptedException) {
            }
        }
        val bmp = bitmaps[mapKey(tmb, signature)] ?: return null
        return BitmapDrawable(context.resources, bmp)
    }

    /** Glide delivered its own bitmap for this cover; the saved one is no longer needed. */
    fun release(tmb: String, signature: Any) {
        if (bitmaps.isNotEmpty()) bitmaps.remove(mapKey(tmb, signature))
    }

    /**
     * Saves the covers of the first screen for the next launch (background thread). Covers come from Glide with the
     * grid's exact request (normally memory cache hits by now); unchanged covers are not rewritten, stale files are
     * deleted.
     */
    fun save(context: Context, dirs: List<Directory>) {
        Thread {
            try {
                val spec = ThumbSizes.get(context, "folder") ?: return@Thread
                val covers = firstScreen(context, ArrayList(dirs), spec)
                val folder = dir(context)
                folder.mkdirs()
                val wanted = HashSet<String>()
                covers.forEach { d ->
                    val name = fileKey(d, spec)
                    wanted.add(name)
                    val f = File(folder, name)
                    if (f.exists()) return@forEach
                    val future = context.buildThumbnailRequest(d.tmb, spec.crop, spec.round, d.getKey(), animate = spec.animate)
                        .submit(spec.width, spec.height)
                    try {
                        val bmp = (future.get(10, TimeUnit.SECONDS) as? BitmapDrawable)?.bitmap
                        if (bmp != null && bmp.width == spec.width && bmp.height == spec.height) {
                            val format = when {
                                !bmp.hasAlpha() || isOpaque(bmp) -> Bitmap.CompressFormat.JPEG
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSLESS
                                else -> Bitmap.CompressFormat.PNG
                            }
                            val tmp = File(folder, "$name.tmp")
                            tmp.outputStream().use { bmp.compress(format, 90, it) }
                            tmp.renameTo(f)
                        }
                    } catch (ignored: Exception) {
                    } finally {
                        Glide.with(context.applicationContext).clear(future)
                    }
                }
                folder.listFiles()?.forEach { if (it.name !in wanted) it.delete() }
            } catch (ignored: Throwable) {
            }
        }.start()
    }

    /** PNG screenshots decode with an alpha channel even when every pixel is opaque; those can be small JPEGs. */
    private fun isOpaque(bmp: Bitmap): Boolean {
        if (bmp.config == Bitmap.Config.HARDWARE) return false
        val row = IntArray(bmp.width)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (p in row) if (p ushr 24 != 0xFF) return false
        }
        return true
    }
}
