#!/usr/bin/env python3
# Usage: cmp_table.py <outdir>[=filter] [<outdir2>[=filter] ...]   (after cmp_analyze.py in each)
#   -> phone-width code-block tables. Medians of several runs are merged (keys are build|scenario; a later dir
#   overrides an earlier one, so a re-measured build goes last). An optional filter takes only part of a dir:
#   "dir=fast11|scrollgrid,fast11|gridcold.pss_mb" = that whole scenario, and only that key of the other one.
# Every line stays <= 44 characters (Telegram code blocks wrap at ~46 on the owner's phone); numbers right-aligned;
# the "vs orig" row is percentages only; a build whose install failed shows "n/a (install fails)".
import json, sys
M = {}
for arg in sys.argv[1:]:
    d, _, filt = arg.partition("=")
    N = json.load(open(f"{d}/cmp_medians.json"))
    if not filt: M.update(N); continue
    for item in filt.split(","):
        bs, _, key = item.partition(".")
        if key: M.setdefault(bs, {})[key] = N[bs][key]
        else: M[bs] = N[bs]
BUILDS = ["orig"] + [f"fast{i}" for i in range(4, 14)]
BLOCKS = [  # (title, [(header, scenario, key, note)])
    ("Launch and albums", [
        ("cold", "gridcold", "total", "cold launch TotalTime"),
        ("albums", "gridcold", "content", "first albums on screen (cold)"),
        ("covers", "gridcold", "settled", "all album covers drawn (cold)"),
        ("splash", "gridcold", "splash_ms", "launch splash length"),
        ("warm", "gridwarm", "total", "launch with the process alive")]),
    ("Album open and photo viewer", [
        ("album", "albumwarm", "settled", "warm album open, thumbnails settled"),
        ("camera", "albumcold", "settled", "cold start straight into Camera, thumbnails settled"),
        ("photo", "photo", "first", "viewer opened directly, first picture"),
        ("swipe", "photo", "next_visible", "swipe to the next photo, settled (ms after the swipe)")]),
    ("Photo tap, photo not opened before", [
        ("picture", "tapnew", "tap_first", "grid tap to the first picture on screen (the instant placeholder counts)"),
        ("fullres", "tapnew", "tap_fullres", "grid tap to the last refinement (full resolution)"),
        ("black", "tapnew", "black_ms", "black between the grid and the picture"),
        ("frame", "tapnew", "tap_displayed", "grid tap to the viewer's first frame (Displayed)")]),
    ("Photo tap, photo opened recently", [
        ("picture", "phototap", "tap_first", "grid tap to the first picture (screen-size image already in Glide's disk cache)"),
        ("fullres", "phototap", "tap_fullres", "grid tap to the last refinement (full resolution)"),
        ("black", "phototap", "black_ms", "black between the grid and the picture"),
        ("frame", "phototap", "tap_displayed", "grid tap to the viewer's first frame (Displayed)")]),
    ("Video", [
        ("video", "player", "picture", "player opened directly, first picture"),
        ("vblack", "player", "black_ms", "black before the video picture")]),
    ("File picker (cold)", [
        ("black", "picker", "blank_ms", "black / empty grid before the picker's albums"),
        ("splash", "picker", "splash_ms", "launch splash length in the picker"),
        ("albums", "picker", "content", "albums visible in the picker")]),
    ("File picker, gallery already in memory", [
        ("black", "pickerwarm", "blank_ms", "black / empty grid before the picker's albums"),
        ("splash", "pickerwarm", "splash_ms", "launch splash length in the picker"),
        ("albums", "pickerwarm", "content", "albums visible in the picker")]),
    ("Scroll and memory", [
        ("p99", "scrollgrid", "p99", "grid scroll p99 frame time"),
        ("jank%", "scrollgrid", "janky", "janky frames while flinging the grid"),
        ("pss", "gridcold", "pss_mb", "PSS after a cold launch (MB)")]),
]


def val(b, s, k):
    return M.get(f"{b}|{s}", {}).get(k)


def num(v):
    return "n/a" if v is None else (f"{v:.1f}" if isinstance(v, float) and abs(v) < 20 and v != int(v) else f"{v:.0f}")


out = []
have = [b for b in BUILDS if any(k.startswith(b + "|") for k in M)]
for title, cols in BLOCKS:
    if not any(val(b, s, k) is not None for b in have for _, s, k, _ in cols): continue
    width = max(6, *(len(h) for h, _, _, _ in cols)) + 1
    lines = ["build   " + "".join(h.rjust(width) for h, _, _, _ in cols)]
    for b in have:
        vals = [val(b, s, k) for _, s, k, _ in cols]
        if all(v is None for v in vals) and f"{b}|install_failed" in M:
            lines.append(b.ljust(8) + "n/a (install fails)")
        else:
            lines.append(b.ljust(8) + "".join(num(v).rjust(width) for v in vals))
    pct = []
    for _, s, k, _ in cols:
        o, n = val("orig", s, k), val(have[-1], s, k)
        if o is None or n is None: pct.append("n/a")
        elif o == 0: pct.append("0%" if n == 0 else "+inf")
        else: pct.append(f"{(n - o) / o * 100:+.0f}%")
    lines.append("vs orig ".ljust(8) + "".join(p.rjust(width) for p in pct))
    assert all(len(l) <= 44 for l in lines), (title, max(len(l) for l in lines))
    out.append(f"## {title}\n\n```\n" + "\n".join(lines) + "\n```\n")
    out.append("- " + "; ".join(f"{h} = {note}" for h, _, _, note in cols) + "\n")
print("\n".join(out))
