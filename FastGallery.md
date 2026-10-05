# FastGallery — all versions compared (v2)

Measured on a Pixel 6 Pro (real screen, same photo library, same speed-profile compile after install), 5 runs per
metric per build, medians in milliseconds. v1 (2026-09-27) + the placeholder and picker-breakdown columns and the
fast11 fix (2026-09-28). 663 runs, 553 screen recordings.

## Launch and albums

```
build      cold albums covers splash   warm
orig        178    385    402    217     28
fast4       181    394    411    218     29
fast5       195    326    512    230     28
fast6       189    307    490    219     28
fast7       187    305    491    220     26
fast8       175    306    501      0     26
fast9       168    227    243      0     31
fast10      172    242    258      0     31
fast11      147    246    276      0     28
fast12      171    239    256      0     28
vs orig     -4%   -38%   -36%  -100%    +0%
```

- cold = cold launch TotalTime; albums = first albums on screen (cold); covers = all album covers drawn (cold); splash = launch splash length; warm = launch with the process alive

## Album open and photo viewer

```
build     album camera  photo  swipe
orig        186    323    166    270
fast4       203    338    176    257
fast5       196    302    165    284
fast6       208    306    171    270
fast7       205    307    169    270
fast8       211    307    166    267
fast9       193    333    172    286
fast10      223    306    173    274
fast11      212    308    165    269
fast12      176    312    168    284
vs orig     -6%    -4%    +1%    +5%
```

- album = warm album open, thumbnails settled; camera = cold start straight into Camera, thumbnails settled; photo = viewer opened directly (am start), first picture; swipe = swipe to the next photo, settled (ms after the swipe)

## Photo tap, photo not opened before

```
build    picture fullres   black   frame
orig         225     225      84      66
fast4        230     230      83      70
fast5        224     224      84      68
fast6        221     221      84      65
fast7        228     228      82      68
fast8        218     218      84      64
fast9        227     227      83      71
fast10       224     224      83      68
fast11       222     222      83      69
fast12       217     217      83      61
vs orig      -4%     -4%     -2%     -8%
```

- picture = grid tap to the first picture on screen (the instant placeholder counts); fullres = grid tap to the last refinement (full resolution); black = black between the grid and the picture; frame = grid tap to the viewer's first frame (Displayed)
- A real tap on the top-left tile of the Camera grid. The photo is a fresh copy of the test photo pushed under a new name before every run (and deleted after it), so the gallery has never opened it: its grid thumbnail is in memory, its screen-size image is not cached. The viewer's title bar is OCR'd to confirm the tap opened that copy (100 of 100 runs).
- **The instant placeholder (fast11+) does not reach the screen.** Every build, orig included, shows 5 pure-black viewer frames (~83 ms) and the picture at ~220 ms after the tap; the FGPerf `photo_placeholder` mark fires at START+58 ms, before the first frame, but the frame that hits the panel is black until the screen-size decode lands (`photo_screen_ready src=LOCAL` at ~+200 ms). Switching the placeholder off with the app's `perf_no_viewer_placeholder` flag file on fast12 gives the same numbers. The earlier "140 → 64 ms" came from log marks, not from the screen. Needs a look at the code path (the placeholder is set on `gesturesView` right before `loadImage()` hands the view to Glide), not at the benchmark.

## Photo tap, photo opened recently

```
build    picture fullres   black   frame
orig         163     178      15      73
fast4        171     178    18.6      74
fast5        157     171       0      66
fast6        160     172       0      69
fast7        160     175    15.7      69
fast8        160     175    17.8      71
fast9        162     178       0      73
fast10       153     168       0      62
fast11       168     182       0      78
fast12       161     176       0      72
vs orig      -1%     -1%   -100%     -1%
```

- Same tap on the newest photo, which the warm-up and the photo scenario opened before: its screen-size image is in Glide's disk cache and arrives before the viewer's first frame, so the picture is in the first frame on every build (black = 0 or a single frame). Not a placeholder effect.

## Video

```
build     video vblack
orig        191     65
fast4       186     65
fast5       199     52
fast6       200     50
fast7       196     66
fast8       174     50
fast9       176     50
fast10      184     50
fast11      168     35
fast12      135      0
vs orig    -29%  -100%
```

- video = player opened directly, first picture; vblack = black before the video picture

## File picker (cold)

```
build     black splash albums
orig        148    174    326
fast4       131    189    330
fast5       116    199    333
fast6       116    199    301
fast7       250    187    340
fast8       132      0    315
fast9       132      0    341
fast10      132      0    350
fast11        0      0    238
fast12        0      0    238
vs orig   -100%  -100%   -27%
```

- Another app's "choose a photo": `GET_CONTENT image/*` sent to the gallery's MainActivity (the chooser-tap path; the implicit intent goes to Google's photo picker on this phone), gallery process not running. black = black / empty grid before the picker's albums; splash = launch splash length in the picker; albums = albums visible in the picker.

## File picker, gallery already in memory

```
build     black splash albums
orig        131     84    225
fast4       166     72    250
fast5       132     95    219
fast6       242     85    342
fast7       109     87    182
fast8        82      0    183
fast9       248      0    344
fast10       84      0    190
fast11        0      0    149
fast12        0      0    112
vs orig   -100%  -100%   -50%
```

- Same picker launch with the gallery process alive in the background (launched, then HOME).

## Scroll and memory

```
build       p99  jank%    pss
orig         11      0    195
fast4        11      0    187
fast5        12    0.3    198
fast6        11      0    198
fast7        12    0.3    198
fast8        11    0.3    198
fast9        11      0    203
fast10       12      0    196
fast11       12      0    201
fast12       11      0    197
vs orig     +0%     0%    +1%
```

- p99 = grid scroll p99 frame time; jank% = janky frames while flinging the grid; pss = PSS after a cold launch (MB)

## fast15 (10-04-2026): photos open without the animation, back without it too

Owner's decision 10-04-2026, asked whether to keep the photo opening animation: "always: whatever the fastest result
is". fast15 opens the viewer without the system's open animation (fast14's `perf_viewer_noanim` path is now the normal
path, including the explicit remembered size for the viewer's own request) and also goes back to the album without the
close animation (the swipe down gesture keeps its own slide). Pixel 6 Pro, real screen, fast15 and fast14 interleaved
(blocks of 3 runs, ABBA, plus a second session of blocks of 5 for the photo tap, cold launch and video), medians in ms,
run count in brackets. Raw runs: ~/fg-fast14 (f1, g1, c1).

```
Photo tap (since the tap)
                  fast15  fast14
never opened
 picture          75(6)  156(5)
 sharp image     163(6)  170(5)
opened before
 picture         86(16)  176(15)
 sharp image     86(16)  189(15)
Back to album
 album settled    53(6)  144(6)
```

```
Unchanged         fast15  fast14
cold launch      162(16) 161(16)
 albums          214(16) 204(16)
video picture    142(16) 142(16)
album, tap       118(6)  120(6)
album, intent    126(6)  127(6)
picker, albums   208(6)  201(6)
new photo, list  288(6)  284(6)
 in background   155(6)  156(6)
photo by intent  187(6)  183(6)
 swipe to next   286(6)  287(6)
```

- Going back: with the close animation brought back (`perf_viewer_close_anim`) the album was settled 169 ms after the
  key, without it 55 ms (8 runs each, same session), so the close animation goes too.
- Cold launch "albums" (first frame with album covers, screen recording) reads about 10 ms later for fast15, but the
  launch path is the same code in both builds (MainActivity, untouched), the app's own markers are level (Displayed
  162 against 161, grid drawn 140 against 140) and the frame read varies by one frame between sessions, so it is
  counted as noise. Same for the picker.
- No black frame on any photo open (black 0 ms in every run); opened photos show the sharp image in the first frame.
- Switches (flag files in files/): perf_viewer_anim brings the open animation back, perf_viewer_close_anim the close
  animation; perf_viewer_noanim is gone (it is the default). New suite scenario `back` (cmp_suite.py, cmp_analyze.py).
- Hands on check of fast15: album, photo, swipe, zoom, rotate, video, back, swipe down to close, slideshow (runs and
  stops). No crash.

## fast14 (10-04-2026): fast13 and fast13f merged, measured against both

fast13 (branch opus-speed) and fast13f (branch fable-speed) did the same speed work independently. fast14 keeps one
implementation per feature: fast13f's quick additions from MediaStore and its tap preload (ViewerPreload), fast13's
codec prewarm thread, the shared placeholder fix. Pixel 6 Pro, real screen, the three builds installed in turn in one
session (blocks of 3 runs), medians in ms, run count in brackets. fast13f was run with the open animation kept
(`perf_viewer_anim`), because fast14 keeps it. Raw runs: ~/fg-fast14 (p1, p2, p3, d2, d3; first session m1, m2, t1).

```
Photo tap (since the tap)
                 fast14 fast13f  fast13
never opened
 picture        161(12) 171(8) 160(11)
 sharp image    177(12) 185(8) 178(11)
opened before
 picture        164(11) 172(9) 165(11)
 sharp image    177(11) 190(9) 180(11)
```

```
Album right after a new photo
                 fast14 fast13f  fast13
gallery not running
 list, new photo 296(6) 295(6) 371(6)
 new thumbnail   465(6) 470(6) 571(6)
gallery in the background
 list, new photo 156(6) 155(6) 352(6)
 new thumbnail   335(6) 349(6) 544(6)
```

```
Unchanged        fast14 fast13f  fast13
cold launch     162(6) 168(6) 164(6)
 albums         204(6) 211(6) 207(6)
picker, albums  201(6) 208(6) 201(6)
album, intent  127(24) 127(6) 126(25)
album, tap     121(15) 126(6) 122(15)
 settled       155(15) 159(6) 155(15)
```

- The open animation is a choice. fast14 opens a photo with the system animation, as upstream and fast13 do. The file
  `files/perf_viewer_noanim` opens the viewer without it (fast13f's default): the picture is then on screen 78 ms
  after the tap for a never opened photo and 89 ms for an opened one (9 runs each; fast13f itself 76 and 92), about
  80 ms sooner, at the price of the grid cutting straight to the photo. Going back keeps its animation either way.
- One thing was fixed on the way. The first fast14 build started the viewer's own request with the remembered view
  size (fast13f's way). With the animation kept that drew the viewer's first frame about one frame later for a photo
  already in the memory cache: picture at 170 against fast13's 162 (14 and 13 runs), log time of the first viewer
  frame 75 against 68. fast13's notes had the same finding. Now the explicit size is used only together with
  `perf_viewer_noanim`; the decode still starts at the grid tap in both modes. After the fix: 164 against 165.
- Album open by intent looked 8 ms behind fast13 in the first sessions (129 against 122, 26 and 27 runs) while album
  open by tap was level (123 against 122). The code on that path is the same in both builds, and a round of 10 more
  runs each gave 130 against 131, so it is counted as noise.
- Off switches (flag files in files/): perf_viewer_noanim (see above), perf_no_viewer_placeholder,
  perf_no_viewer_preload, perf_pager_late, perf_no_quick_additions, perf_no_player_prewarm.
- Hands on check of fast14: album, photo, swipe, zoom, rotate, video, back, slideshow start, split screen. No crash.

## fast13f (10-03-2026): the placeholder paints, plus what else measured as a gain

Pixel 6 Pro, fast12 and fast13f interleaved in one session (blocks of 3 runs, 6 runs per build and scenario),
medians in ms with the range in brackets. Raw runs: ~/fg-fable/final2 (+ final_attr, final_scroll, e1 - e4).

```
Tap, photo never opened    fast12   fast13f
first non black frame         226        90
  (clock read runs)           n=4       n=2
black frames (n=6)             82         0
image decoded, log (n=6)      190       127
viewer frame drawn (n=6)       67        64
```

```
Tap, photo opened before   fast12   fast13f
first picture                 164        91
image decoded, log (n=6)      101        45
```

```
Album right after a new photo
(gallery not running)      fast12   fast13f
first thumbnails             1807       304
list with the new photo      1965       300
new photo's thumbnail        2150       472
(gallery in the background)
list with the new photo      1844       152
new photo's thumbnail        2033       328
```

```
Unchanged                  fast12   fast13f
cold launch TotalTime         164       160
cold launch, albums           210       208
file picker cold, albums      210       204
warm album, thumbnails        124       120
grid scroll p99                11        12
```

- Root cause of the placeholder that never painted: `showInstantPlaceholder()` runs inside `onCreateView`, where
  `Fragment.getView()` is still null, so its `view != null` guard dropped the placeholder on every tap. Nothing to do
  with Glide clearing the view or the window background.
- Kept: the placeholder fix; the screen sized decode started at the grid tap with a remembered view size
  (ViewerPreload); the viewer opened without the system open animation and its pager filled before the first frame;
  PlayerPrewarm on its own thread (in fast12 its 1.5 s sleep ran on the thread that loads the album, which held back
  both the first thumbnails and the rescan whenever the album had changed); new photos taken from MediaStore before
  the folder listing (quickAdditions).
- Dropped after measuring: Glide decode threads at default priority, for all decodes (no gain on tap, album, cold
  launch or picker) and for the viewer only (image 16 ms sooner but the first frame 11 ms later).
- Off switches (flag files in files/): perf_no_viewer_placeholder, perf_no_viewer_preload, perf_viewer_anim,
  perf_pager_late, perf_no_quick_additions. `cmp_suite.py` takes them as `fast13f+perf_viewer_anim:3`.
- Only part of the tap recordings had a readable overlay clock; a run without one keeps its durations (black) and its
  log times but not its screen times (the fallback anchor is wrong by ~60 ms between builds with and without the open
  animation).

## fast11: what changed since v1

- v1's fast11 rows were not fast11. Every `pm install` of fast11.apk hung on Google Play Protect's "Send app for a security check?" dialog (`PlayProtectDialogsActivity`) until the 120 s timeout, the suite logged an empty install result and measured whatever was still installed: fast10 in the first block and fast12 in the second. v1's fast11 row was a fast10/fast12 mix.
- Fix in cmp_suite.py: `verifier_verify_adb_installs=0` for the run (restored after), BACK on the dialog if it shows anyway, and the installed versionName is verified after every install (3 attempts, then the build is marked "n/a (install fails)" instead of being measured as another build).
- fast11 was re-measured 2026-09-28 (all 9 v1 scenarios + warm picker, 5 runs, versionName 1.13.1-fast11 verified). Its scroll p99 / jank% / PSS come from a same-session control run (fast12 then fast11, 3 runs each, 2026-09-28 20:00): the first fast11 session read PSS 262 MB and p99 24 ms, the control run 201 MB / 12 ms with fast12 at 201 MB / 13 ms alongside, so the first reading was phone state, not the build.
- fast11 rows were measured in a different session than the other builds (cold 147 in its session vs 158–162 in the control run an hour later), so treat +-10 % on its launch columns as session drift.

## Method notes

- All times = ms since the launch's ActivityTaskManager START (logcat), screen timing from `screenrecord --bugreport` frames whose overlay clock is OCR'd. The tap scenarios time from the moment `input tap` returns (within a few ms of the touch release, START lands +-10 ms from it).
- Scripts: `scripts/perf/cmp_suite.py` (records), `cmp_analyze.py` (measures), `cmp_table.py` (renders these blocks, <= 44 chars wide). Runs: ~/fg-cmp/all (v1, fast11 discarded), all2 (warm picker + phototap, fast11 discarded), v2fast11, v2tap (tapnew + phototap, all builds), v2ctrl (fast11 scroll/PSS), v2ab_on / v2ab_off (fast12 placeholder flag A/B, 3 runs each).
- Unchanged across builds: warm launch 26–31 ms, album open 176–223, cold into Camera 302–338, photo first picture 165–177, scroll p99 11–12 ms, memory 187–203 MB.
- Result: fast12 still wins (video 191/65 → 135/0, picker black/splash → 0, picker albums -27 % cold / -50 % warm); the instant placeholder is the one shipped feature with no measurable screen effect.

# fast13 (branch opus-speed, 2026-09-30, not released)

Measured on the same Pixel 6 Pro, real screen, fast12 and fast13 installed in turn in one session
(fast12 x3, fast13 x3, fast12 x2, fast13 x2), 5 runs per build, medians in ms, lowest and highest run in brackets.

```
metric              fast12       fast13
tap, new photo
 picture     225 [218..232] 146 [146..161]
 sharp       225 [218..232] 164 [163..176]
 black        84   [66..86]   0     [0..0]
album, new photo
 thumbnails 1666 [1653..1677] 129 [116..151]
 settled    1767 [1760..1794] 273 [263..302]
```

- tap, new photo = grid tap on a photo the gallery never opened (scenario `tapnew`), times since the tap. picture = first frame with the picture (the placeholder counts), sharp = last refinement, black = black between grid and picture.
- album, new photo = a photo lands in Camera, the gallery is started, Camera is tapped (new scenario `albumnew`), times since the tap.

What changed:

1. The instant placeholder is really set. `showInstantPlaceholder()` runs inside `onCreateView`, where `Fragment.getView()` is still null, so its `view != null` guard dropped the thumbnail on every tap since fast11.
2. The viewer's screen sized image starts decoding at the tap (`ViewerImage.preload` in `MediaActivity.openInViewPager`, same Glide key as the viewer's own request, A/B flag `files/perf_no_viewer_preload`). Sharp image 227 to 173 ms with the flag off and on, 5 runs each on one APK.
3. The codec prewarm of fast12 slept 1.5 s on the album's loader thread (`ensureBackgroundThread` runs inline off the main thread), once per process, for any album with videos. With a valid first screen snapshot the grid looked fine and only the full list came late; without one (a new photo in the album) the grid stayed empty for 1.5 s. It has its own thread now.

Unchanged within run to run noise (same session, 5 runs each): cold launch 173 / 178, albums on screen 207 / 214, warm launch 29 / 29, album open by tap 145 / 121 (143 / 142 in the run before), tap on an already opened photo 170 / 161, viewer by intent 167 / 178 (163 / 171 and 175 / 165 in other runs), video 136 / 127, picker cold 206 / 207, picker warm 99 / 103, scroll p99 12 / 12 and 15 / 15, PSS 215 / 212 MB. Final screens of every scenario match fast12 (`cmp_regress.py`), no crash or ANR in 140 runs.

Tried and dropped (no gain beyond noise):

- Pager pages set before the first layout pass: tap picture 157 vs 158 ms (4 and 4 runs).
- Album first screen thumbnails preloaded at the album tap: thumbnails on screen 127 vs 127, settled 161 vs 160 (10 and 10 runs).
- Explicit size on the viewer's own request (starts before layout): sharp image 174 vs 174, and the viewer's first frame came later for cached photos (171 vs 164, TotalTime 77.5 vs 68; 10 runs each).

Suite changes (scripts/perf): installs are streamed into pm and `sys_storage_threshold_max_bytes` is lowered for the run (the phone had about 80 MB free), status bar demo mode and no heads up notifications during a run (both restored at the end; the notification icons broke the overlay OCR and a heads up swallowed a tap), plan names may carry flag files (`fast13+perf_no_viewer_preload:3`), new scenarios `albumtap` and `albumnew`, `cmp_spread.py` (every run's value), `cmp_regress.py` (final screens A vs B), `cmp_strip.py` (frames side by side).

Rebuild and measure again:

```
cd ~/fossify-gallery-opus-speed
flock ~/.gradle-claude.lock ~/bin/memcap 8G \
  ./gradlew assembleFossRelease --no-daemon -q
# APK: app/build/outputs/apk/foss/release/
#      gallery-2813-foss-release.apk
# copy it to <apks>/fast13.apk, fast12.apk
# next to it, then (phone unlocked, launcher):
CMP_APKS=<apks> FG_SERIAL=192.168.1.69:5555 \
 CMP_VIDEO=/storage/emulated/0/DCIM/Camera/\
PXL_20260428_101322367.TS.mp4 \
 python3 scripts/perf/cmp_suite.py <out> \
 fast12:3,fast13:3,fast12:2,fast13:2 \
 gridcold,gridwarm,albumcold,albumwarm,\
albumtap,photo,player,picker,pickerwarm,\
phototap,albumnew,tapnew,scrollgrid,scrollalbum
python3 scripts/perf/cmp_analyze.py <out>
python3 scripts/perf/cmp_spread.py <out>
python3 scripts/perf/cmp_regress.py <out> \
 fast12 fast13
```
