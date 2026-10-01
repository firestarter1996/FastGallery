#!/usr/bin/env python3
"""strip.py <outdir> <rec.mp4 basename> <from_ms> <to_ms> <out.png> : frames (time since START, ms) side by side"""
import json, os, re, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw
d, rec, a, b, outp = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
R = json.load(open(f"{d}/cmp_results.json")); m = next(x for x in R if x.get("rec") == rec)
off = json.load(open(f"{d}/_ocr_cache.json")).get(rec)
ACT = {"albumcold": "MediaActivity", "albumwarm": "MediaActivity", "photo": "ViewPagerActivity", "player": "VideoPlayerActivity", "phototap": "ViewPagerActivity", "tapnew": "ViewPagerActivity"}
act = ACT.get(m["scen"])
L = [l for l in m["logs"] if "ActivityTaskManager: START u0" in l and "org.fossify.gallery" in l and (act is None or act in l)]
mm = re.match(r"\d\d-\d\d \d\d:\d\d:(\d\d)\.(\d{3})", L[0]); start = int(mm.group(1)) + int(mm.group(2)) / 1000
mp4 = f"{d}/{rec}"
pts = [float(x) for x in subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", mp4], capture_output=True, text=True).stdout.split()]
W, H = 180, 390
raw = subprocess.run(["ffmpeg", "-v", "error", "-i", mp4, "-vf", f"scale={W}:{H}", "-vsync", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
im = np.frombuffer(raw, np.uint8).reshape(-1, H, W, 3)
if off is None: print("no OCR offset for", rec); sys.exit(1)
t = [(((p + off) % 60 - start + 30) % 60 - 30) * 1000 for p in pts[:len(im)]]
sel = [i for i in range(1, len(t)) if a <= t[i] <= b]
canvas = Image.new("RGB", (W * len(sel), H + 14), "white"); dr = ImageDraw.Draw(canvas)
for k, i in enumerate(sel):
    canvas.paste(Image.fromarray(im[i]), (k * W, 14)); dr.text((k * W + 4, 1), f"{t[i]:.0f}", fill="black")
canvas.save(outp); print(len(sel), "frames", [round(t[i]) for i in sel])
