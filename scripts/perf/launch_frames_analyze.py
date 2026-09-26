#!/usr/bin/env python3
"""Classify frames recorded by launch_frames_record.py: S=Settings behind, B=black/splash, A=app. Times in ms since ActivityTaskManager START."""
import json, subprocess, sys, numpy as np, statistics as st
lab = sys.argv[1]; W, H = 42, 94
d = json.load(open(f"{lab}.json")); fr = d["frames"]
raw = subprocess.run(["ffmpeg", "-v", "error", "-f", "h264", "-i", f"{lab}.h264", "-vf", f"scale={W}:{H}", "-vsync", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
imgs = np.frombuffer(raw, np.uint8).reshape(-1, H, W, 3).astype(float)
n = min(len(imgs), len(fr))
res = []
for r, m in enumerate(d["marks"]):
    t0 = m["start_mono"]
    nxt = d["marks"][r + 1]["start_mono"] if r + 1 < len(d["marks"]) else 1e18
    before = [i for i in range(n) if fr[i]["pts"] <= t0]
    ref = imgs[before[-1]] if before else None
    idx = [i for i in range(n) if t0 < fr[i]["pts"] < min(nxt, t0 + 3000)]
    final = imgs[idx[-1]]
    first_change = splash_first = splash_last = app_first = settled = None; nblack = 0; kinds = []
    for i in idx:
        im = imgs[i]; t = fr[i]["pts"] - t0
        if ref is not None and np.abs(im - ref).mean() < 2: k = "S"
        elif im.std() < 4 and im.mean() < 6: k = "B"
        else: k = "A"
        kinds.append(k)
        if k != "S" and first_change is None: first_change = t
        if k == "B":
            nblack += 1; splash_first = splash_first if splash_first is not None else t; splash_last = t
        if k == "A" and app_first is None: app_first = t
        if settled is None and np.abs(im - final).mean() < 1.5: settled = t
    ev = {l.split("FGPerf")[1].split()[1] if "FGPerf" in l else "": l for l in m["log"]}
    row = dict(first_change=first_change, black_frames=nblack, black_ms=(app_first - splash_first) if splash_first is not None and app_first else 0,
               albums_visible=app_first, covers_settled=settled, am_total=int(m["am"][0].split()[-1]))
    res.append(row)
    print(r, row, "".join(kinds[:40]))
print("MEDIAN", {k: st.median([x[k] for x in res if x[k] is not None]) for k in res[0]})
