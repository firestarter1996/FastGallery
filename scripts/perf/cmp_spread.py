#!/usr/bin/env python3
# Usage: cmp_spread.py <outdir> [<outdir2> ...]   (after cmp_analyze.py in each; rows of all dirs are pooled per build)
"""Per build and scenario: median, min - max and run count of the keys that matter, from cmp_rows.json.
One short block per scenario, <= 44 characters wide (phone width)."""
import json, statistics as st, sys
KEYS = {
    "tapnew": [("nonblack", "tap_nonblack"), ("picture", "tap_first"), ("fullimg", "tap_fullres"), ("black", "black_ms"),
               ("fill", "fill"), ("drawn", "tap_displayed")],
    "phototap": [("nonblack", "tap_nonblack"), ("picture", "tap_first"), ("fullimg", "tap_fullres"), ("black", "black_ms"),
                 ("fill", "fill"), ("drawn", "tap_displayed")],
    "albumnew": [("thumbs", "content"), ("listed", "listed"), ("newpic", "newthumb")],
    "albumnewwarm": [("thumbs", "content"), ("listed", "listed"), ("newpic", "newthumb")],
    "gridcold": [("total", "total"), ("albums", "content"), ("covers", "settled"), ("black", "blank_ms")],
    "picker": [("total", "total"), ("albums", "content"), ("settled", "settled"), ("black", "blank_ms")],
    "pickerwarm": [("total", "total"), ("albums", "content"), ("black", "blank_ms")],
    "albumcold": [("total", "total"), ("thumbs", "content"), ("settled", "settled")],
    "albumwarm": [("total", "total"), ("thumbs", "content"), ("settled", "settled")],
    "scrollgrid": [("p99", "p99"), ("jank%", "janky")],
    "scrollalbum": [("p99", "p99"), ("jank%", "janky")],
}
rows = {}
for d in sys.argv[1:]:
    for k, L in json.load(open(f"{d}/cmp_rows.json")).items():
        rows.setdefault(k, []).extend(L)
builds = []
for k in rows:
    b = k.split("|")[0]
    if b not in builds: builds.append(b)
for scen, keys in KEYS.items():
    if not any(f"{b}|{scen}" in rows for b in builds): continue
    print(f"## {scen}\n\n```")
    for b in builds:
        L = rows.get(f"{b}|{scen}")
        if not L: continue
        print(f"{b}  (n={len(L)})")
        for label, key in keys:
            v = [x[key] for x in L if isinstance(x.get(key), (int, float)) and not isinstance(x.get(key), bool)]
            if not v: print(f"  {label:8s} n/a"); continue
            print(f"  {label:8s}{st.median(v):6.0f}  ({min(v):.0f} - {max(v):.0f})" + ("" if len(v) == len(L) else f" n={len(v)}"))
    print("```\n")
