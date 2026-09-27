#!/usr/bin/env python3
"""Analyze suite_record.py output. Times in ms since ActivityTaskManager START (swipe: since the finger lifted)."""
import json, subprocess, sys, numpy as np, statistics as st
lab = sys.argv[1]
d = json.load(open(f"{lab}.json")); fr = d["frames"]; scen = d["scenario"]
W, H = (168, 374) if scen in ("photo", "video") else (42, 94)
raw = subprocess.run(["ffmpeg", "-v", "error", "-f", "h264", "-i", f"{lab}.h264", "-vf", f"scale={W}:{H}", "-vsync", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
imgs = np.frombuffer(raw, np.uint8).reshape(-1, H, W, 3).astype(np.float32)
n = min(len(imgs), len(fr))
def window(t0, t1): return [i for i in range(n) if t0 < fr[i]["pts"] < t1]
def settle(idx, t0, final, thr):
    for i in idx:
        if np.abs(imgs[i] - final).mean() < thr: return fr[i]["pts"] - t0
res = []
for r, m in enumerate(d["marks"]):
    t0 = m.get("start_mono")
    if t0 is None: continue
    row = {}
    before = [i for i in range(n) if fr[i]["pts"] <= t0]
    ref = imgs[before[-1]]
    if scen == "photo":
        # the host->device clock mapping is not reliable enough here, so the swipe is located on the video itself:
        # the first big frame change more than 1.8 s after START (the photo is static by then) is the finger drag
        allw = window(t0, t0 + 12000)
        sw = next((i for k, i in enumerate(allw) if k and fr[i]["pts"] - t0 > 1800 and np.abs(imgs[i] - imgs[allw[k - 1]]).mean() > 5), None)
        if sw is None: continue
        ts = fr[sw]["pts"]
        idx = window(t0, ts - 1); final = imgs[idx[-1]]
        row["first_change"] = next((fr[i]["pts"] - t0 for i in idx if np.abs(imgs[i] - ref).mean() > 2), None)
        row["photo_settled"] = settle(idx, t0, final, 1.0)
        idx2 = [i for i in window(ts - 1, ts + 2900)]; final2 = imgs[idx2[-1]]
        row["next_settled"] = settle(idx2, ts, final2, 1.0)
    else:
        idx = window(t0, t0 + 2900); final = imgs[idx[-1]]
        row["first_change"] = next((fr[i]["pts"] - t0 for i in idx if np.abs(imgs[i] - ref).mean() > 2), None)
        if scen == "video":
            # first frame showing picture content in the middle of the screen (thumbnail or decoded video frame)
            # the viewer opens black, then the video's picture appears: first frame with picture content after the black one
            blk = next((k for k, i in enumerate(idx) if imgs[i].mean() < 5), None)
            row["content"] = None if blk is None else next((fr[i]["pts"] - t0 for i in idx[blk:] if imgs[i][H // 3:2 * H // 3].std() > 30), None)
        else:
            row["albums"] = settle(idx, t0, final, 30)   # the albums' layout is on screen (covers may still be missing/fading)
            row["settled"] = settle(idx, t0, final, 1.5)
        if "pss_kb" in m:
            row["pss_mb"] = m["pss_kb"] / 1024
            for k in ("java_heap_kb", "native_heap_kb", "graphics_kb", "code_kb"):
                if k in m: row[k[:-3] + "_mb"] = m[k] / 1024
            if "after15" in m: row["pss15_mb"] = m["after15"]["pss_kb"] / 1024; row["graphics15_mb"] = m["after15"].get("graphics_kb", 0) / 1024
    cam = [x for x in m.get("fgperf", []) if "scan_cache" in x and x.rstrip().endswith("DCIM/Camera")]
    if cam: row["camera_scan"] = "hit" if any("scan_cache_hit" in x for x in cam) else "miss"
    res.append(row)
    print(r, {k: (round(v) if isinstance(v, float) else v) for k, v in row.items()})
keys = [k for k in res[0] if k != "camera_scan"]
print("MEDIAN", {k: round(st.median([x[k] for x in res[1:] if x.get(k) is not None])) for k in keys if any(x.get(k) is not None for x in res[1:])}, "(run 0 dropped as warm-up)")
print("ROWS", json.dumps(res[1:]))
