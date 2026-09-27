#!/usr/bin/env python3
# Usage: real_analyze.py <outdir>   (reads real_results.json + the photo/video screenrecords written by real_suite.py)
"""Medians per build of the real-screen suite. Marker times are ms since ActivityTaskManager START.

Visual (screenrecord, alignment-free because screenrecord has no device-clock timestamps):
  photo: black_gap   = ms the viewer showed an empty (black) picture area before the photo appeared (0 = the photo was
                       in the viewer's first frame); flicker = frames after the photo appeared whose picture area jumps
                       away from its final look before the swipe (should be 0); next_visible = ms from the start of the
                       swipe until the next photo's area is settled.
  video: black_gap   = ms of black picture area before the video's picture appears.
"""
import json, statistics as st, subprocess, sys, collections
import numpy as np
d = sys.argv[1]
R = json.load(open(f"{d}/real_results.json"))
W, H = 168, 374


def frames(mp4):
    pts = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", mp4], capture_output=True, text=True).stdout.split()
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", mp4, "-vf", f"scale={W}:{H}", "-vsync", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
    im = np.frombuffer(raw, np.uint8).reshape(-1, H, W).astype(np.float32)
    n = min(len(im), len(pts))
    return [float(p) * 1000 for p in pts[:n]], im[:n]


def visual(m):
    try: t, im = frames(f"{d}/{m['rec']}")
    except Exception: return {}
    b0, b1 = int(H * .2), int(H * .8)
    band = im[:, b0:b1]
    mean = band.mean(axis=(1, 2))
    out = {}
    blk = [i for i in range(len(t)) if mean[i] < 8]
    if m["scen"] == "photo":
        # swipe = first big change of the picture area after it had been static for 1 s
        sw = None
        for i in range(1, len(t)):
            if t[i] - t[0] > 1500 and np.abs(band[i] - band[i - 1]).mean() > 6 and np.abs(band[i - 1] - band[max(0, i - 8)]).mean() < 1.5:
                sw = i; break
        end = sw if sw else len(t)
        fin = band[end - 1]
        first_black = next((i for i in blk if i < end), None)
        vis = next((i for i in range(first_black or 0, end) if np.abs(band[i] - fin).mean() < 6), None)
        out["black_gap"] = 0.0 if first_black is None else (t[vis] - t[first_black] if vis is not None else None)
        if vis is not None:
            out["flicker"] = int(sum(1 for i in range(vis, end) if np.abs(band[i] - fin).mean() > 12))
        if sw:
            fin2 = band[-1]
            out["next_visible"] = next((t[i] - t[sw] for i in range(sw, len(t)) if all(np.abs(band[j] - fin2).mean() < 4 for j in range(i, min(len(t), i + 6)))), None)
            out["swipe_black_frames"] = int(sum(1 for i in range(sw, len(t)) if mean[i] < 8))
    elif m["scen"].startswith("album"):
        # appear = first frame that differs from the starting screen (launcher / album grid); settled = first frame
        # after which the picture stays within a small distance of the final frame (screenrecord emits frames on change)
        # status bar (clock/icons) excluded; the final look = last frame within 2 s of appearing (a scrollbar fade
        # ~2.4 s later is not part of opening the album)
        full = im[:, int(H * .04):]
        app = next((i for i in range(1, len(t)) if np.abs(full[i] - full[0]).mean() > 10), None)
        if app is not None:
            end = max(j for j in range(app, len(t)) if t[j] - t[app] <= 2000) + 1
            fin = full[end - 1]
            st_i = next((i for i in range(app, end) if all(np.abs(full[j] - fin).mean() < 3 for j in range(i, end))), None)
            out["settle_after_first_frame"] = (t[st_i] - t[app]) if st_i is not None else None
    else:
        first_black = blk[0] if blk else None
        if first_black is not None:
            out["black_gap"] = next((t[i] - t[first_black] for i in range(first_black, len(t)) if band[i].std() > 25), None)
    return out


def ev(m, key, cond=lambda s: True, after=-1e9):
    return next((t for t, s in m.get("ev", []) if key in s and cond(s) and t > after), None)


rows = collections.defaultdict(list)
for m in R:
    s = m["scen"]; r = {"total": m.get("total")}
    if s.startswith("grid"):
        r["grid_drawn"] = ev(m, "grid_drawn")
        th = [t for t, x in m.get("ev", []) if "thumb_drawn" in x]
        if th: r["last_cover"] = max(th)
        if "pss_kb" in m: r["pss_mb"] = m["pss_kb"] / 1024
    elif s.startswith("album"):
        r["thumbs"] = ev(m, "media_thumbs")
        if m.get("rec"):
            r.update(visual(m))
            if r.get("settle_after_first_frame") is not None and r.get("total"):
                r["thumbs_settled_est"] = r["total"] + r["settle_after_first_frame"]
    elif s == "photo":
        r["m_placeholder"] = ev(m, "photo_placeholder")
        r["m_screen_ready"] = ev(m, "photo_screen_ready", lambda x: "vis=true" in x)
        r["m_first_image"] = min(x for x in (r["m_placeholder"], r["m_screen_ready"]) if x is not None) if (r["m_placeholder"] or r["m_screen_ready"]) else None
        r["m_fullres"] = ev(m, "photo_fullres_ready", lambda x: "vis=true" in x)
        sel = ev(m, "vp_page_selected", after=500)
        if sel is not None:
            r["m_next_fullres_after_select"] = (ev(m, "photo_fullres_ready", lambda x: "vis=true" in x, after=sel) or float("nan")) - sel
        r["pager_sets"] = sum(1 for t, x in m.get("ev", []) if "vp_pager_set" in x)
        r.update(visual(m))
    elif s in ("video", "player", "playertap"):
        r.update(visual(m))
        for k in ("player_poster", "player_surface", "player_ready", "player_first_frame"):
            r[k] = ev(m, k)
    r["launch"] = m.get("launch")
    rows[(s, m["build"])].append(r)

for (s, b), L in sorted(rows.items()):
    keys = [k for k in L[0] if k != "launch"]
    med = {}
    for k in keys:
        v = [x[k] for x in L if isinstance(x.get(k), (int, float)) and x.get(k) == x.get(k)]
        if v: med[k] = round(st.median(v), 1)
    print(f"{s:10s} {b} n={len(L)} {med} launch={collections.Counter(x['launch'] for x in L)}")
print("RAW", json.dumps({f"{s}_{b}": L for (s, b), L in rows.items()}))
