### Reporting
Before you report something, read the reporting rules [here](https://github.com/FossifyOrg/General-Discussion#how-do-i-suggest-an-improvement-ask-a-question-or-report-an-issue) please.

### Contributing as a developer
Some instructions about code style and everything that has to be done to increase the chance of your code getting accepted can be found at the [General Discussion](https://github.com/FossifyOrg/General-Discussion#contribution-rules-for-developers) section. 

### Contributing as a non developer
In case you just want to for example improve a translation, you can find the way of doing it [here](https://github.com/FossifyOrg/General-Discussion#how-can-i-suggest-an-edit-to-a-file).

### FastGallery fork rules: what may enter the media DB (10-06-2026)
The fork keeps media rows for a long time (scan cache, album snapshots), so a wrong row is not fixed by the next launch
the way it is in stock Fossify. Two rules hold for every code path that writes media rows or serves them from the DB:

1. **Hidden folders never get rows.** Use `helpers/MediaVisibility` (stock Fossify's own `shouldFolderBeVisible` rule):
   with hidden items off, nothing from a dot folder, a folder with a `.nomedia` file in it or in any parent, or an
   excluded folder. Android's `Pictures/.thumbnails` cache must never show as photos.
2. **Rows carry the file's real dates, never the time the row was written.** Use `helpers/MediaDates`
   (MediaStore DATE_MODIFIED / DATE_TAKEN, else the file's mtime). Never `System.currentTimeMillis()`.

`helpers/DbRepair` purges rows that break rule 1 and fixes dates that break rule 2 on every full load.
`app/src/test/.../MediaVisibilityTest.kt` must pass before a release: `./gradlew testFossDebugUnitTest`.
