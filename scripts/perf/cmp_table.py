#!/usr/bin/env python3
# Usage: cmp_table.py <outdir>   (after cmp_analyze.py) -> markdown table of medians, rows = builds
import json, sys
d = sys.argv[1]
M = json.load(open(f"{d}/cmp_medians.json"))
BUILDS = ["orig"] + [f"fast{i}" for i in range(4, 13)]
NAME = {"orig": "original (Fossify 1.13.1)"}
COLS = [  # (header, scenario, key, lower_is_better)
    ("Cold launch TotalTime", "gridcold", "total", True),
    ("Cold: albums visible", "gridcold", "content", True),
    ("Cold: all covers drawn", "gridcold", "settled", True),
    ("Launch splash ms", "gridcold", "splash_ms", True),
    ("Warm launch (in memory)", "gridwarm", "total", True),
    ("Album open warm: thumbs drawn", "albumwarm", "settled", True),
    ("Cold into Camera: thumbs drawn", "albumcold", "settled", True),
    ("Photo: first visible", "photo", "first", True),
    ("Photo: full-res", "photo", "fullres", True),
    ("Photo: black ms", "photo", "black_ms", True),
    ("Swipe next: settled", "photo", "next_visible", True),
    ("Video: first picture", "player", "picture", True),
    ("Video: first frame", "player", "video", True),
    ("Video: black ms", "player", "black_ms", True),
    ("Picker splash ms", "picker", "splash_ms", True),
    ("Picker black ms", "picker", "blank_ms", True),
    ("Picker: albums visible", "picker", "content", True),
    ("Scroll jank % grid (p99 ms)", "scrollgrid", ("janky", "p99"), True),
    ("Scroll jank % album (p99 ms)", "scrollalbum", ("janky", "p99"), True),
    ("PSS MB", "gridcold", "pss_mb", True),
]


def val(b, s, k):
    v = M.get(f"{b}|{s}", {})
    if isinstance(k, tuple):
        a, c = v.get(k[0]), v.get(k[1])
        return None if a is None else (a, c)
    return v.get(k)


def fmt(x, splash=False):
    if x is None: return "n/a"
    if isinstance(x, tuple): return f"{x[0]:g}% ({x[1]:g})"
    if splash and x: return f"{x:g} (~{round(x / 16.7)} fr)"
    return f"{x:g}"


print("| Build | " + " | ".join(c[0] for c in COLS) + " |")
print("|" + "---|" * (len(COLS) + 1))
for b in BUILDS:
    if not any(f"{b}|" in k for k in M): continue
    n = M.get(f"{b}|gridcold", {}).get("n")
    print(f"| {NAME.get(b, b)} (n={n}) | " + " | ".join(fmt(val(b, s, k), "splash" in h) for h, s, k, _ in COLS) + " |")
last = [b for b in BUILDS if any(f"{b}|" in k for k in M)][-1]
row = []
for h, s, k, _ in COLS:
    o, n = val("orig", s, k), val(last, s, k)
    if isinstance(o, tuple): o, n = (o[0] if o else None), (n[0] if n else None)
    if o is None or n is None: row.append("n/a")
    elif o == 0: row.append("same (0)" if n == 0 else f"+{n:g}")
    else: row.append(f"{(n - o) / o * 100:+.0f}%")
print(f"| **{last} vs original** | " + " | ".join(row) + " |")
