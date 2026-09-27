#!/usr/bin/env python3
# Usage: cmp_analyze.py <outdir> [--md]
"""Medians per build of cmp_suite.py runs. All times = ms since ActivityTaskManager START (logcat wall clock), from
screenrecord --bugreport frames whose device time is read by OCR of the overlay (seconds.millis).

Visual definitions (status bar + overlay rows excluded):
  content   first frame at/after START (and after the splash window is gone) that differs from the screen before
            START and is not blank (texture present) = albums / album grid / picker albums visible
  settled   first frame after which the screen stays within a small distance of its look 2 s after `content`
  blank     time from the first blank-dark frame after START to `content` (black splash / empty black grid)
  photo     first = first frame whose centre square matches the photo shown before the swipe (placeholder counts);
            fullres = last change of the picture area before the swipe (screen-size -> full-resolution refinement)
  player    picture = first frame with the video's centre non-black (poster counts); video = first frame whose left
            strip is non-black (a real video frame of the landscape test video)
  splash    WindowManager's "Splash Screen org.fossify.gallery" window: added -> removed (logcat)
"""
import collections, json, os, re, statistics as st, subprocess, sys
import numpy as np
d = sys.argv[1]; MD = "--md" in sys.argv
R = json.load(open(f"{d}/cmp_results.json"))
W, H = 180, 390
TOP = int(H * .045)           # status bar + bugreport overlay
CACHE = f"{d}/_ocr_cache.json"
ocr_cache = json.load(open(CACHE)) if os.path.exists(CACHE) else {}


def decode(mp4, vf, w, h):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", mp4, "-vf", vf, "-vsync", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "-"], capture_output=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, h, w)


def ocr_ss(img):
    from PIL import Image
    b = (img > 200).astype(np.uint8) * 255
    p = f"{d}/_ocr.png"; Image.fromarray(255 - b).resize((img.shape[1] * 3, img.shape[0] * 3), Image.NEAREST).save(p)
    r = subprocess.run(["tesseract", p, "-", "--psm", "7", "-c", "tessedit_char_whitelist=0123456789:.f=()"], capture_output=True, text=True).stdout
    m = re.search(r"(\d{2})\.(\d{3})\s*f", r) or re.search(r":(\d{2})\.(\d{3})", r)
    return int(m.group(1)) + int(m.group(2)) / 1000 if m else None


def frames(mp4):
    pts = [float(x) for x in subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", mp4], capture_output=True, text=True).stdout.split()]
    im = decode(mp4, f"scale={W}:{H}", W, H).astype(np.float32)
    n = min(len(pts), len(im))
    pts, im = pts[1:n], im[1:n]            # frame 0 = bugreport info page
    key = os.path.basename(mp4)
    if key not in ocr_cache:
        crop = decode(mp4, "crop=320:42:0:0", 320, 42)[1:n]
        idx = sorted(set([0, len(pts) // 3, 2 * len(pts) // 3, len(pts) - 1]))
        offs = []
        for i in idx:
            s = ocr_ss(crop[i])
            if s is not None: offs.append((s - pts[i]) % 60)
        off = None
        if offs:
            offs.sort()
            best = max(offs, key=lambda o: sum(1 for x in offs if min(abs(x - o), 60 - abs(x - o)) < 0.006))
            if sum(1 for x in offs if min(abs(x - best), 60 - abs(x - best)) < 0.006) >= 2: off = best
        ocr_cache[key] = off
    return pts, im, ocr_cache[key]


def wall_ss(line):
    m = re.match(r"\d\d-\d\d \d\d:\d\d:(\d\d)\.(\d{3})", line)
    return int(m.group(1)) + int(m.group(2)) / 1000 if m else None


def rel(ss, start):   # seconds-in-minute difference -> ms, wrapped to [-30 s, 30 s)
    return ((ss - start + 30) % 60 - 30) * 1000


def start_ss(m, act=None):
    L = [l for l in m.get("logs", []) if "START u0" in l and "org.fossify.gallery" in l and (act is None or act in l)]
    return wall_ss(L[-1]) if L else None


def splash(m, start):
    L = [l for l in m.get("logs", []) if "Splash Screen org.fossify.gallery" in l]
    if not L or start is None: return 0.0
    add = wall_ss(L[0]); rem = next((wall_ss(l) for l in L if "EXITING" in l), None)
    return round(rel(rem, add), 1) if rem is not None else None


def fgperf(m, key, start, last=False):
    v = [rel(wall_ss(l), start) for l in m.get("logs", []) if "FGPerf" in l and key in l]
    return (max(v) if last else min(v)) if v else None


def body(f): return f[TOP:]


def visual_grid(m, start, splash_ms):
    t0 = []
    try: pts, im, off = frames(f"{d}/{m['rec']}")
    except Exception: return {}
    if off is None or start is None: return {"err": "no_ocr"}
    t = [rel((p + off) % 60, start) for p in pts]
    pre = [i for i in range(len(t)) if t[i] < 0]
    ref = body(im[pre[-1]]) if pre else body(im[0])
    blank = lambda f: body(f).mean() < 15 or body(f).std() < 12
    splash_end = splash_ms if splash_ms else 0
    cont = next((i for i in range(len(t)) if t[i] >= splash_end - 1 and np.abs(body(im[i]) - ref).mean() > 10 and not blank(im[i])), None)
    out = {}
    if cont is None: return {"err": "no_content"}
    out["content"] = round(t[cont], 1)
    end = max(j for j in range(cont, len(t)) if t[j] - t[cont] <= 2000) + 1
    fin = body(im[end - 1])
    s_i = next((i for i in range(cont, end) if all(np.abs(body(im[j]) - fin).mean() < 3 for j in range(i, end))), None)
    out["settled"] = round(t[s_i], 1) if s_i is not None else None
    fb = next((i for i in range(len(t)) if t[i] >= 0 and blank(im[i]) and np.abs(body(im[i]) - ref).mean() > 10), None)
    out["blank_ms"] = round(t[cont] - t[fb], 1) if (fb is not None and fb < cont) else 0.0
    return out


def visual_photo(m, start):
    try: pts, im, off = frames(f"{d}/{m['rec']}")
    except Exception: return {}
    if off is None or start is None: return {"err": "no_ocr"}
    t = [rel((p + off) % 60, start) for p in pts]
    band = im[:, int(H * .25):int(H * .75)]
    sq = im[:, int(H * .42):int(H * .58), int(W * .3):int(W * .7)]
    sw = None
    for i in range(1, len(t)):
        if t[i] > 1500 and np.abs(band[i] - band[i - 1]).mean() > 6 and np.abs(band[i - 1] - band[max(0, i - 8)]).mean() < 1.5:
            sw = i; break
    endi = sw if sw else len(t)
    fin_sq = sq[endi - 1]
    out = {}
    fv = next((i for i in range(endi) if t[i] >= 0 and np.abs(sq[i] - fin_sq).mean() < 20), None)
    out["first"] = round(t[fv], 1) if fv is not None else None
    ch = [i for i in range(max(1, fv or 1), endi) if np.abs(band[i] - band[i - 1]).mean() > 1.0]
    out["fullres"] = round(t[ch[-1]], 1) if ch else out["first"]
    if sw:
        fin2 = band[-1]
        out["next_visible"] = next((round(t[i] - t[sw], 1) for i in range(sw, len(t)) if all(np.abs(band[j] - fin2).mean() < 4 for j in range(i, min(len(t), i + 6)))), None)
    return out


def visual_player(m, start):
    try: pts, im, off = frames(f"{d}/{m['rec']}")
    except Exception: return {}
    if off is None or start is None: return {"err": "no_ocr"}
    t = [rel((p + off) % 60, start) for p in pts]
    mid = im[:, int(H * .47):int(H * .53)]
    centre = mid[:, :, int(W * .42):int(W * .58)].mean(axis=(1, 2))
    left = mid[:, :, int(W * .02):int(W * .1)].mean(axis=(1, 2))
    pre = [i for i in range(len(t)) if t[i] < 0]
    base_c = centre[pre[-1]] if pre else None
    # the player screen is black around the picture; wait for the black viewer, then the first non-black centre
    blk = next((i for i in range(len(t)) if t[i] >= 0 and im[i][TOP:].mean() < 40), None)
    out = {}
    if blk is None:   # poster in the first frame: no black frame at all
        blk = next((i for i in range(len(t)) if t[i] >= 0 and base_c is not None and abs(centre[i] - base_c) > 5), None)
    if blk is None: return {"err": "no_player"}
    pic = next((i for i in range(blk, len(t)) if centre[i] > 12), None)
    vid = next((i for i in range(blk, len(t)) if left[i] > 12), None)
    out["picture"] = round(t[pic], 1) if pic is not None else None
    out["video"] = round(t[vid], 1) if vid is not None else None
    out["black_ms"] = round(t[pic] - t[blk], 1) if (pic is not None and im[blk][TOP:].mean() < 40) else 0.0
    return out


rows = collections.defaultdict(list)
for m in R:
    s = m["scen"]; b = m["build"]; r = {"total": m.get("total"), "launch": m.get("launch")}
    if s == "gridcold":
        st0 = start_ss(m); sp = splash(m, st0); r["splash_ms"] = sp
        r.update(visual_grid(m, st0, sp))
        r["m_grid_drawn"] = fgperf(m, "grid_drawn", st0) if st0 else None
        r["m_last_cover"] = fgperf(m, "thumb_drawn", st0, last=True) if st0 else None
        r["pss_mb"] = round(m["pss_kb"] / 1024, 1) if m.get("pss_kb") else None
    elif s in ("albumcold", "albumwarm"):
        st0 = start_ss(m, "MediaActivity"); r.update(visual_grid(m, st0, 0))
    elif s == "photo":
        st0 = start_ss(m, "ViewPagerActivity"); r.update(visual_photo(m, st0))
        r["m_fullres"] = fgperf(m, "photo_fullres_ready", st0) if st0 else None
    elif s == "player":
        st0 = start_ss(m, "VideoPlayerActivity"); r.update(visual_player(m, st0))
        r["m_first_frame"] = fgperf(m, "player_first_frame", st0) if st0 else None
    elif s == "picker":
        st0 = start_ss(m); sp = splash(m, st0); r["splash_ms"] = sp; r.update(visual_grid(m, st0, sp))
    elif s.startswith("scroll"):
        g = m.get("gfx", {}); r = {k: float(v) for k, v in g.items() if v is not None}
    rows[(b, s)].append(r)
json.dump(ocr_cache, open(CACHE, "w"))

med = {}
for (b, s), L in rows.items():
    keys = sorted({k for x in L for k in x if k != "launch"})
    med[(b, s)] = {k: round(st.median([x[k] for x in L if isinstance(x.get(k), (int, float))]), 1)
                   for k in keys if any(isinstance(x.get(k), (int, float)) for x in L)}
    med[(b, s)]["n"] = len(L)
    med[(b, s)]["launch"] = dict(collections.Counter(x.get("launch") for x in L))
for (b, s) in sorted(med): print(f"{b:7s} {s:11s} {med[(b, s)]}")
json.dump({f"{b}|{s}": v for (b, s), v in med.items()}, open(f"{d}/cmp_medians.json", "w"), indent=1)
json.dump({f"{b}|{s}": L for (b, s), L in rows.items()}, open(f"{d}/cmp_rows.json", "w"), indent=0)
