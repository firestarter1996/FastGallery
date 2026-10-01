#!/usr/bin/env python3
"""regress.py <outdir> <buildA> <buildB>: are the final screens the same? Last frame of every recording (180x390 gray,
status bar/overlay rows cut), mean abs pixel difference: A-vs-A pairs (run to run noise) against A-vs-B pairs.
Also counts crash/ANR lines in the run logs and blank (near black) final frames."""
import json, subprocess, sys, itertools, statistics as st
import numpy as np
d, A, B = sys.argv[1:4]
R = json.load(open(f"{d}/cmp_results.json")); W, H = 180, 390; TOP = int(H * .12)   # also cuts the title bar (file names differ)
def last(mp4):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", f"{d}/{mp4}", "-vf", f"scale={W}:{H}", "-vsync", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, H, W)[-1][TOP:].astype(np.float32)
F = {}
crash = {A: 0, B: 0}
for m in R:
    if m["build"] not in (A, B): continue
    crash[m["build"]] += sum(1 for l in m.get("logs", []) if ("AndroidRuntime: " in l and "fossify" in l) or "FATAL EXCEPTION" in l or "ANR in org.fossify" in l)
    if m.get("rec"):
        try: F.setdefault((m["scen"], m["build"]), []).append(last(m["rec"]))
        except Exception as e: print("unreadable", m["rec"], e)
print("crash/ANR lines:", crash)
print(f"{'scenario':12s} {'A-A diff':>9s} {'A-B diff':>9s} {'darkest final frame mean (A / B)':>34s}")
for scen in sorted({k[0] for k in F}):
    a, b = F.get((scen, A), []), F.get((scen, B), [])
    if not a or not b: continue
    aa = [np.abs(x - y).mean() for x, y in itertools.combinations(a, 2)] or [0]
    ab = [np.abs(x - y).mean() for x in a for y in b]
    print(f"{scen:12s} {st.median(aa):9.2f} {st.median(ab):9.2f} {min(x.mean() for x in a):16.1f} / {min(x.mean() for x in b):.1f}   n={len(a)}/{len(b)}")
