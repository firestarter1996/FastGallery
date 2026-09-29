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
