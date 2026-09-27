#!/usr/bin/env python3
"""Analyze suite_record.py output. Times in ms since ActivityTaskManager START (swipe: since the finger lifted)."""
import json, subprocess, sys, numpy as np, statistics as st
lab = sys.argv[1]
d = json.load(open(f"{lab}.json")); fr = d["frames"]; scen = d["scenario"]
W, H = (168, 374) if scen in ("photo", "video") else (42, 94)
raw = subprocess.run(["ffmpeg", "-v", "error", "-f", "h264", "-i", f"{lab}.h264", "-vf", f"scale={W}:{H}", "-vsync", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
imgs = np.frombuffer(raw, np.uint8).reshape(-1, H, W, 3).astype(np.float32)
n = min(len(imgs), len(fr))
CROP_THR = 2.5
crops = None
if scen == "photo":   # native-resolution 384x384 centre crop (grey) to tell a blurry placeholder from the full picture
    C = 384
    rawc = subprocess.run(["ffmpeg", "-v", "error", "-f", "h264", "-i", f"{lab}.h264", "-vf", f"crop={C}:{C}:(iw-{C})/2:(ih-{C})/2", "-vsync", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
    crops = np.frombuffer(rawc, np.uint8).reshape(-1, C, C).astype(np.float32)
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
        row["photo_settled"] = settle(idx, t0, final, 1.0)   # includes the toolbar auto-hide (hide_system_ui, 500 ms)
        # photo only (toolbar/bottom bar excluded): visible = the picture band is 70% of the way from the black viewer
        # frame to its final look; full = a native-resolution centre crop matches the final one (detail, not just colour)
        b0, b1 = int(H * .2), int(H * .8)
        def band(i): return imgs[i][b0:b1]
        fb = band(idx[-1])
        blk = next((i for i in idx if imgs[i].mean() < 8), None)
        if blk is not None:
            dblk = max(np.abs(band(blk) - fb).mean(), 1e-3)
            row["photo_visible"] = next((fr[i]["pts"] - t0 for i in idx if fr[i]["pts"] >= fr[blk]["pts"] and np.abs(band(i) - fb).mean() < .3 * dblk), None)
        if crops is not None:
            fc = crops[idx[-1]]
            row["photo_full"] = next((fr[i]["pts"] - t0 for i in idx if i < len(crops) and np.abs(crops[i] - fc).mean() < CROP_THR and all(np.abs(crops[j] - fc).mean() < CROP_THR for j in idx if fr[j]["pts"] > fr[i]["pts"] and j < len(crops))), None)
        idx2 = [i for i in window(ts - 1, ts + 2900)]; final2 = imgs[idx2[-1]]
        row["next_settled"] = settle(idx2, ts, final2, 1.0)
        fb2 = final2[b0:b1]
        row["next_visible"] = next((fr[i]["pts"] - ts for i in idx2 if np.abs(imgs[i][b0:b1] - fb2).mean() < 3), None)
        if crops is not None:
            fc2 = crops[idx2[-1]]
            row["next_full"] = next((fr[i]["pts"] - ts for i in idx2 if all(np.abs(crops[j] - fc2).mean() < CROP_THR for j in idx2 if fr[j]["pts"] >= fr[i]["pts"])), None)
        # app markers (device monotonic ms, same clock as START): the shown photo's full-resolution layer ready
        pm = [(float(l.split()[0]) * 1000, l) for l in m.get("perf_mono", [])]
        fo = next((t for t, l in pm if "photo_fullres_ready" in l and "vis=true" in l), None)
        if fo is not None: row["m_open_full"] = fo - t0
        sel = next((t for t, l in pm if "vp_page_selected" in l and t > t0 + 1000), None)
        if sel is not None:
            f2 = next((t for t, l in pm if "photo_fullres_ready" in l and "vis=true" in l and t > sel), None)
            if f2 is not None: row["m_next_full_after_select"] = f2 - sel
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
