# FastGallery — all versions compared

Measured 2026-09-27 on a Pixel 6 Pro (real screen, same photo library, same speed-profile compile after install),
5 runs per metric per build, medians in milliseconds from launch. 450 runs, 300 screen recordings.

## Launch and albums

```
build    cold  albums  covers  splash
orig      178     385     402     217
fast4     181     394     411     218
fast5     195     326     512     230
fast6     189     307     490     219
fast7     187     305     491     220
fast8     175     306     501       0
fast9     168     227     243       0
fast10    172     242     258       0
fast11    181     239     256       0
fast12    171     239     256       0
vs orig   -4%    -38%    -36%   -100%
```

## Video and file picker

```
build     video  vblack  picker
orig        191      65     326
fast4       186      65     330
fast5       199      52     333
fast6       200      50     301
fast7       196      66     340
fast8       174      50     315
fast9       176      50     341
fast10      184      50     350
fast11      199      53     236
fast12      135       0     238
vs orig    -29%   -100%    -27%
```

- cold = TotalTime of a cold launch; albums = first albums on screen; covers = all covers drawn; splash = launch splash length
- video = first video picture; vblack = black before it; picker = albums visible when opened from another app's file picker (picker black 148 → 0, picker splash 174 → 0)
- Unchanged across builds: warm launch 26–31 ms, album open 176–223, cold into Camera 302–338, photo first picture 165–177, scroll p99 11–15 ms, memory 187–203 MB
- Not in this table yet: the instant photo placeholder (grid tap → first image content, 140 → 64 ms in earlier runs) and a full picker-launch breakdown; both are being measured for every build and will be added.
- Result: fast12 wins (no regression vs fast11 on any row; video 199/53 → 135/0). Shipped as release v12.
