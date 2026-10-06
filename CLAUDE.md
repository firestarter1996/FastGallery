# FastGallery (fork of Fossify Gallery)

Read `CONTRIBUTING.md`, section "FastGallery fork rules", before touching any code that writes or reads media rows.

- Every path that adds media (MediaStore change notifications, NewPhotoFetcher, quick additions, incremental rescans,
  scan cache, snapshots, widgets, picker) applies `helpers/MediaVisibility` and `helpers/MediaDates`. A row from a
  hidden or `.nomedia` folder, or a row dated "now", is a release blocker.
- Before every release: `./gradlew testFossDebugUnitTest` (MediaVisibilityTest) and, on the test phone, read the app
  DB: no rows under any folder with a dot segment, and Camera's newest rows by last_modified must be the newest photos.
- Incident 10-06-2026 (fast15): upstream `addPathToDB` stamped rows with the current time; the scan cache kept them, so
  95 photos from 2025 showed on top of the owner's Camera album. Fixed in fast16.
