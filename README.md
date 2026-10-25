# 🗂️⚡🖼️ FastGallery

A fork of [Fossify Gallery](https://github.com/FossifyOrg/Gallery) tuned for very large libraries (built on a Pixel 8 Pro with ~68,000 photos and videos across ~250 folders).

<img src="fastlane/metadata/android/en-US/images/icon.png" width="96" alt="FastGallery icon">

Latest release: [v18](https://github.com/firestarter1996/FastGallery/releases/latest), `FastGallery-1.13.1-fast18.apk` (10-24-2026).

## What is different

| Problem in stock Fossify Gallery | FastGallery |
|---|---|
| Every launch and every folder open re walked the folder on disk (17k files in Camera, 20k in Screenshots) before showing anything current | A per folder signature (directory mtime + cached row count + scan options) lets unchanged folders load straight from the app's own database. The album list, the album screen and the file pickers share that cache. Pull to refresh still forces a full rescan. |
| Thumbnail disk cache capped at 250 MB in `cache/`, so a big library re decoded full photos on every scroll, and Android emptied the cache under storage pressure | Thumbnail cache raised to 4 GB and moved to `no_backup/`. A nightly job (on by default, from midnight while charging) renders every visible album's thumbnails into it. |
| Rows for unchanged folders were rewritten to the database on every launch | Skipped when a folder was served from the cache |
| The album grid only appeared after a database query plus a MediaStore wide `.nomedia` scan (~0.9 s, 3.5 s+ with a cold page cache), and its covers faded in afterwards | The last album grid and its first screen of covers are saved to small files and drawn in the very first frame; the normal load refreshes them right after. The tile fade in animation is gone. |
| Album covers were read from the thumbnail cache one at a time (Glide's single disk cache thread) | 4 disk cache readers, and Glide is initialised off the main thread |
| Android 12+ showed a launch splash before the albums, also when another app opened a photo or a picker | No splash: the albums (or the photo) are the first thing drawn after the tap |
| An album showed its thumbnails only after its folder listing | Each album's first screen is saved and drawn right after its first frame; photos added since the last visit come from MediaStore before the folder listing finishes |
| Hidden `.trashed-*` rows and folders whose rows were missing made Camera/Screenshots miss the scan cache on every launch, so a new photo's cover showed up only after a 16-25 s full rescan | Stale rows are purged by path, missing rows are stored, and a changed folder is rescanned incrementally (only new/removed names) |
| Every rescan first loaded 50k row date maps from MediaStore (~6 s) and walked all of MediaStore for new folders (~7 s) | Date maps are built only if a folder really needs a full walk; new folder discovery is skipped when MediaStore's generation is unchanged |
| Rows added from MediaStore change notifications were dated with the current time until the next full scan; with a scan cache that date would stick, so old photos another app touched (for example Google Photos restoring them from its trash) sorted above today's photos | Rows always carry their real dates (MediaStore, then the `PXL_` file name time, never the scan time). A repair pass fixes wrong dates already saved, at a full load and right after an in place update. |
| Those same notification rows also let hidden folders (dot folders, `.nomedia` folders, excluded folders) into the database | Hidden folders never enter the database, and rows already saved for them are removed. No file on the phone is touched. |
| A photo started decoding only after the viewer was laid out, and it opened and closed with the system animation | The decode starts at the grid tap, the cached grid thumbnail is shown until the sharp image is ready, and the viewer opens and closes without the system animation (the swipe down gesture keeps its slide) |
| A video showed a black or stretched first frame | The tapped thumbnail stays on screen until the first video frame, at the right size |
| A playing video stopped when the screen turned off or the app went to the background | It keeps playing with audio, with a media notification, headset buttons and audio focus. Setting "Keep playing videos with the screen off", on by default. |
| Double tap seek used upstream's 1/7 edge zones | Double tap the left or right third to jump 10 s back or forward; more taps within 750 ms stack (20 s, 30 s). A small dark pill with three chevrons shows the total in the tapped half. Setting "Double tap to seek", on by default; off brings back the old edge behaviour. |

Videos open in the app's own player unless you pick another one; upstream switched to the system player after the first run.

### Measured on fast5 (Pixel 8 Pro, 68k files, cold start, median ms since process start, 09-26-2026)

These numbers compare 1.13.1-fast4 with fast5 and were not measured again on later builds.

| | 1.13.1-fast4 | fast5 |
|---|---|---|
| Albums displayed (first frame with the album grid) | 864 | 224 |
| Each visible album's cover drawn (median over the 10 visible albums) | 973 | 350 |
| All visible covers drawn | 1047 | 362 |
| Same three after dropping the page cache | 3564 / 3710 / 3768 | 362 / 496 / 537 |
| A new photo shows up as its album's cover | 16159 | 1673 |

Measured with `scripts/perf/measure_startup.py` (reads the app's `FGPerf` logcat markers; `reportFullyDrawn` marks the album grid). Creating `files/perf_legacy` in the app's data dir switches the optimisations off for A/B runs.

### Measured on fast15 against fast14 (Pixel 6 Pro, real screen, median ms, 10-04-2026)

| | fast14 | fast15 |
|---|---|---|
| Photo never opened before: tap to picture | 156 | 75 |
| Photo opened before: tap to picture | 176 | 86 |
| Back to the album: album settled | 144 | 53 |

The flag files `files/perf_viewer_anim` and `files/perf_viewer_close_anim` bring the open and close animations back for benchmarks. The full measurement history, from stock through fast15, is in [FastGallery.md](FastGallery.md).

Apart from the changes above and their settings, everything (features, settings, package name `org.fossify.gallery`) is stock, so it drops in over the original with your settings, pinned folders and favorites intact. Because it is signed with its own key you must uninstall the F-Droid/Obtainium build first (back up its data if you want to keep it, see below), and updates then come from this repo's Releases (Obtainium: add `https://github.com/firestarter1996/FastGallery`).

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

## Support

FastGallery is free with no ads. If it helps you, you can support it on GitHub Sponsors (https://github.com/sponsors/firestarter1996) or Ko-fi (https://ko-fi.com/firestarter1996). Thank you!

## License

GPL-3.0, same as upstream. Not affiliated with Fossify.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=firestarter1996/FastGallery&type=Date)](https://star-history.com/#firestarter1996/FastGallery&Date)
