# ⚡🖼️ FastGallery

A fork of [Fossify Gallery](https://github.com/FossifyOrg/Gallery) tuned for very large libraries (built on a Pixel 8 Pro with ~68,000 photos and videos across ~250 folders).

<img src="fastlane/metadata/android/en-US/images/icon.png" width="96" alt="FastGallery icon">

## What is different

| Problem in stock Fossify Gallery | FastGallery |
|---|---|
| Every launch and every folder open re-walked the folder on disk (17k files in Camera, 20k in Screenshots) before showing anything current | A per-folder signature (directory mtime + cached row count + scan options) lets unchanged folders load straight from the app's own database. Pull-to-refresh still forces a full rescan. |
| Thumbnail disk cache capped at 250 MB, so a big library re-decoded full photos on every scroll | Thumbnail cache raised to 3 GB |
| Rows for unchanged folders were rewritten to the database on every launch | Skipped when a folder was served from the cache |
| The album grid only appeared after a database query plus a MediaStore-wide `.nomedia` scan (~0.9 s, 3.5 s+ with a cold page cache) | The last album grid is saved to a small file and drawn in the very first frame; the normal load refreshes it right after |
| Album covers were read from the thumbnail cache one at a time (Glide's single disk-cache thread) | 4 disk-cache readers, and Glide is initialised off the main thread |
| Hidden `.trashed-*` rows and folders whose rows were missing made Camera/Screenshots miss the scan cache on every launch, so a new photo's cover showed up only after a 16-25 s full rescan | Stale rows are purged by path, missing rows are stored, and a changed folder is rescanned incrementally (only new/removed names) |
| Every rescan first loaded 50k-row date maps from MediaStore (~6 s) and walked all of MediaStore for new folders (~7 s) | Date maps are built only if a folder really needs a full walk; new-folder discovery is skipped when MediaStore's generation is unchanged |

### Measured (Pixel 8 Pro, 68k files, cold start, median ms since process start)

| | 1.13.1-fast4 | fast5 |
|---|---|---|
| Albums displayed (first frame with the album grid) | 864 | 224 |
| Each visible album's cover drawn (median over the 10 visible albums) | 973 | 350 |
| All visible covers drawn | 1047 | 362 |
| Same three after dropping the page cache | 3564 / 3710 / 3768 | 362 / 496 / 537 |
| A new photo shows up as its album's cover | 16159 | 1673 |

Measured with `scripts/perf/measure_startup.py` (reads the app's `FGPerf` logcat markers; `reportFullyDrawn` marks the album grid). Creating `files/perf_legacy` in the app's data dir switches the optimisations off for A/B runs.

Everything else (features, settings, package name `org.fossify.gallery`) is stock, so it drops in over the original with your settings, pinned folders and favorites intact. Because it is signed with its own key you must uninstall the F-Droid/Obtainium build first (back up its data if you want to keep it, see below), and updates then come from this repo's Releases (Obtainium: add `https://github.com/firestarter1996/FastGallery`).

## Install keeping your data (root)

```
am force-stop org.fossify.gallery
tar -C /data/data -cf /data/local/tmp/gallery-data.tar --exclude=org.fossify.gallery/cache org.fossify.gallery
pm uninstall org.fossify.gallery
pm install -g -r FastGallery-*.apk
appops set org.fossify.gallery MANAGE_EXTERNAL_STORAGE allow
am force-stop org.fossify.gallery
rm -rf /data/data/org.fossify.gallery/{shared_prefs,databases,files,no_backup}
tar -C /data/data -xf /data/local/tmp/gallery-data.tar
chown -R $(stat -c %U /data/data/org.fossify.gallery) /data/data/org.fossify.gallery
restorecon -R /data/data/org.fossify.gallery
```

## Building

Standard Fossify Gradle project (`./gradlew assembleFossRelease`). Signing reads `keystore.properties` or the `SIGNING_*` environment variables; the release workflow signs with the FastGallery key from repository secrets and publishes each build as a GitHub Release.

## License

GPL-3.0, same as upstream. Not affiliated with Fossify.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=firestarter1996/FastGallery&type=Date)](https://star-history.com/#firestarter1996/FastGallery&Date)
