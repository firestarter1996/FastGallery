#!/usr/bin/env python3
# Usage: cmp_analyze.py <outdir> [--md]
"""Medians per build of cmp_suite.py runs. All times = ms since the first ActivityTaskManager START log line of the launch
(logcat wall clock; = the launch request, the splash window is added ~15 ms later and `Displayed +N` lands at ~N), from
screenrecord --bugreport frames whose device time is read by OCR of the overlay (seconds.millis).

Visual definitions (status bar + overlay rows excluded):
  content   first frame at/after START (and after the splash window is gone) whose area below the toolbar differs
            from the screen before START and is not blank (texture present) = albums / thumbnails / picker albums
            visible (an empty grid with only the search bar drawn does not count)
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
    """seconds.millis from one overlay crop; the thin status-bar clock underneath is stripped by a 3x3 opening
    (overlay strokes are ~4 px at 720 px wide, status-bar strokes ~2 px)"""
    from PIL import Image, ImageFilter
    b = Image.fromarray((img > 160).astype(np.uint8) * 255).filter(ImageFilter.MinFilter(3)).filter(ImageFilter.MaxFilter(3))
    b = b.resize((img.shape[1] * 4, img.shape[0] * 4), Image.NEAREST)
    p = f"{d}/_ocr.png"; Image.fromarray(255 - np.array(b)).save(p)
    r = subprocess.run(["tesseract", p, "-", "--psm", "7", "-c", "tessedit_char_whitelist=0123456789:."], capture_output=True, text=True).stdout
    m = re.search(r"\d{2}:\d{2}:(\d{2})\.?(\d{3})(?!\d)", r)
    if m: return int(m.group(1)) + int(m.group(2)) / 1000
    # the dot is often read as a digit or dropped ("10:1.9:315946."): take seconds + millis from the digits after the
    # last colon (5 digits = ssmmm, 6 = ss?mmm); a misread still has to agree with 3 other frames (see frames())
    dg = re.sub(r"\D", "", r.strip().rsplit(":", 1)[-1])
    if len(dg) in (5, 6) and int(dg[:2]) < 60: return int(dg[:2]) + int(dg[-3:]) / 1000
    return None


def frames(mp4):
    pts = [float(x) for x in subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", mp4], capture_output=True, text=True).stdout.split()]
    im = decode(mp4, f"scale={W}:{H}", W, H).astype(np.float32)
    n = min(len(pts), len(im))
    pts, im = pts[1:n], im[1:n]            # frame 0 = bugreport info page
    key = os.path.basename(mp4)
    if key not in ocr_cache:
        crop = decode(mp4, "crop=200:52:0:4", 200, 52)[1:n]
        # every 2nd frame from the end (app screens read best); stop once 3 readings agree within 6 ms
        offs = []; off = None
        for i in list(range(len(pts) - 1, -1, -2)) + list(range(len(pts) - 2, -1, -2)):
            s = ocr_ss(crop[i])
            if s is None: continue
            offs.append((s - pts[i]) % 60)
            agree = lambda o: sum(1 for x in offs if min(abs(x - o), 60 - abs(x - o)) < 0.006)
            best = max(offs, key=agree)
            if agree(best) >= 4: off = best; break
        ocr_cache[key] = off
    return pts, im, ocr_cache[key]


def wall_ss(line):
    m = re.match(r"\d\d-\d\d \d\d:\d\d:(\d\d)\.(\d{3})", line)
    return int(m.group(1)) + int(m.group(2)) / 1000 if m else None


def rel(ss, start):   # seconds-in-minute difference -> ms, wrapped to [-30 s, 30 s)
    return ((ss - start + 30) % 60 - 30) * 1000


def start_ss(m, act=None):
    L = [l for l in m.get("logs", []) if "ActivityTaskManager: START u0" in l and "org.fossify.gallery" in l and (act is None or act in l)]
    return wall_ss(L[0]) if L else None     # first line: builds with a SplashActivity trampoline log a second START


def splash(m, start):
    L = [l for l in m.get("logs", []) if "Splash Screen org.fossify.gallery" in l and "adbd" not in l]
    if not L or start is None: return 0.0, 0.0
    add = wall_ss(L[0]); rem = next((wall_ss(l) for l in L if "EXITING" in l), None)
    if rem is None: return None, None
    return round(rel(rem, add), 1), round(rel(rem, start), 1)   # duration, removal time since START


def fgperf(m, key, start, last=False):
    v = [rel(wall_ss(l), start) for l in m.get("logs", []) if " FGPerf  :" in l and key in l]
    return (max(v) if last else min(v)) if v else None


def displayed(m, start):
    L = [l for l in m.get("logs", []) if "ActivityTaskManager: Displayed" in l]
    return round(rel(wall_ss(L[-1]), start), 1) if (L and start is not None) else None


def body(f): return f[TOP:]


def title_ocr(m):
    """text of the viewer's title bar in the last frame (720x1560 recording: the toolbar text sits at y 105-155,
    x 90-470, under the status bar + bugreport overlay; verified on a frame 2026-09-28)"""
    from PIL import Image
    try:
        fr = decode(f"{d}/{m['rec']}", "crop=400:56:90:102", 400, 56)[-1]
    except Exception: return ""
    b = Image.fromarray(fr).resize((400 * 3, 56 * 3), Image.BICUBIC)
    p = f"{d}/_title.png"; b.save(p)
    return subprocess.run(["tesseract", p, "-", "--psm", "7"], capture_output=True, text=True).stdout.strip()


GRID_TOP = int(H * .24)       # below the toolbar/search bar (bar ends ~20% down on the 6 Pro): "content" must be in the album/thumbnail area


GRID_BOT = int(H * .88)       # above the "Shell was granted Superuser rights" toast of root am start


def grid(f): return f[GRID_TOP:GRID_BOT]


LAT = []          # log-to-pixel latency (ms) of the first visible change, measured on OCR-timed recordings


def times(m, start, collect=False):
    """frame times in ms since START. Primary: OCR of the overlay clock (accepted only if the first visible change lands
    within -20..250 ms of its log line). Fallback (no usable OCR): anchor the first visible change to the splash
    window's add time, or to `Displayed` for splash-less builds, plus the median latency measured on OCR'd runs."""
    pts, im, off = frames(f"{d}/{m['rec']}")
    if start is None: return None, None, None
    ch = next((i for i in range(1, len(im)) if np.abs(im[i] - im[0]).mean() > 6), None)
    L = [l for l in m.get("logs", []) if "Splash Screen org.fossify.gallery" in l and "adbd" not in l]
    anchor = rel(wall_ss(L[0]), start) if L else displayed(m, start)
    if off is not None:
        t = [rel((p + off) % 60, start) for p in pts]
        lat = (t[ch] - anchor) if (ch is not None and anchor is not None) else None
        # sanity: the recording starts ~1 s before START and runs on after it; the first visible change must follow
        # its log line by a plausible screen latency (a consistent misread of one digit fails this)
        # (t[-1] > 100, was > 500: with the status bar in demo mode its clock no longer forces a frame every second,
        # so a recording ends with the last real screen change, which can be < 500 ms after START)
        if -4000 < t[0] < 0 < t[-1] and t[-1] > 100 and (lat is None or -20 <= lat <= 250):
            if collect and lat is not None: LAT.append(lat)
            return t, im, "ocr"
        ocr_cache[os.path.basename(m['rec'])] = None
    if ch is None or anchor is None: return None, None, None
    lat = st.median(LAT) if LAT else 50.0
    return [(p - pts[ch]) * 1000 + anchor + lat for p in pts], im, "anchor"


ACT = {"albumcold": "MediaActivity", "albumwarm": "MediaActivity", "albumtap": "MediaActivity", "photo": "ViewPagerActivity", "player": "VideoPlayerActivity",
       "phototap": "ViewPagerActivity", "tapnew": "ViewPagerActivity", "albumnew": "MediaActivity", "albumnewwarm": "MediaActivity"}


def tap_rel(m, start):
    """ms from the grid tap to START (device `date +%H:%M:%S.%N`, same clock as logcat), negative = tap before START.
    tap_done = the time right after `input tap` returned (within a few ms of the touch release); older runs only have
    tap_wall = before `input` started (~90 ms too early: the tool's own start-up)"""
    mm = re.match(r"\d\d:\d\d:(\d\d)\.(\d{3})", m.get("tap_done") or m.get("tap_wall") or "")
    return rel(int(mm.group(1)) + int(mm.group(2)) / 1000, start) if (mm and start is not None) else None


def calibrate():
    for m in R:
        if not m.get("rec"): continue
        try: times(m, start_ss(m, ACT.get(m["scen"])), collect=True)
        except Exception: pass
    json.dump(ocr_cache, open(CACHE, "w"))
    print("latency calibration: n", len(LAT), "median", round(st.median(LAT), 1) if LAT else None,
          "p10/p90", (round(np.percentile(LAT, 10), 1), round(np.percentile(LAT, 90), 1)) if LAT else None, flush=True)


def visual_grid(m, start, splash_ms):
    try: t, im, how = times(m, start)
    except Exception: return {}
    if t is None: return {"err": "no_ocr"}
    pre = [i for i in range(len(t)) if t[i] < 0]
    ref = grid(im[pre[-1]]) if pre else grid(im[0])
    # blank = the splash / an empty grid with only the toolbar drawn (the original shows that for ~150 ms)
    blank = lambda f: grid(f).mean() < 15 or grid(f).std() < 12
    splash_end = splash_ms if splash_ms else 0
    cont = next((i for i in range(len(t)) if t[i] >= splash_end - 1 and np.abs(grid(im[i]) - ref).mean() > 10 and not blank(im[i])), None)
    out = {"anchor": how}
    if cont is None: return {"err": "no_content"}
    out["content"] = round(t[cont], 1)
    end = max(j for j in range(cont, len(t)) if t[j] - t[cont] <= 2000) + 1
    fin = body(im[end - 1])
    s_i = next((i for i in range(cont, end) if all(np.abs(body(im[j]) - fin).mean() < 3 for j in range(i, end))), None)
    out["settled"] = round(t[s_i], 1) if s_i is not None else None
    fb = next((i for i in range(len(t)) if t[i] >= 0 and blank(im[i]) and np.abs(grid(im[i]) - ref).mean() > 10), None)
    out["blank_ms"] = round(t[cont] - t[fb], 1) if (fb is not None and fb < cont) else 0.0
    return out


def visual_photo(m, start):
    try: t, im, how = times(m, start)
    except Exception: return {}
    if t is None: return {"err": "no_ocr"}
    band = im[:, int(H * .25):int(H * .75)]
    sq = im[:, int(H * .42):int(H * .58), int(W * .3):int(W * .7)]
    sw = None
    # the swipe: with the recorded swipe time (photo scenario) it is the first frame at/after it. screenrecord only
    # emits a frame when the screen changes, so with the status bar in demo mode (no clock ticks) there are no
    # frames between the settled photo and the swipe and the old "8 stable frames before it" test never matched.
    swm = re.match(r"\d\d:\d\d:(\d\d)\.(\d{3})", m.get("swipe_wall") or "")
    if swm:
        sw_t = rel(int(swm.group(1)) + int(swm.group(2)) / 1000, start)
        sw = next((i for i in range(1, len(t)) if t[i] >= sw_t), None)
    else:
        for i in range(1, len(t)):
            if t[i] > 1500 and np.abs(band[i] - band[i - 1]).mean() > 6 and np.abs(band[i - 1] - band[max(0, i - 8)]).mean() < 1.5:
                sw = i; break
    endi = sw if sw else len(t)
    fin_sq = sq[endi - 1]
    out = {"how": how}
    sqm = sq.mean(axis=(1, 2))
    fb = next((i for i in range(endi) if t[i] >= 0 and sqm[i] < 8), None)
    if fb is not None:
        nb = next((i for i in range(fb, endi) if sqm[i] >= 8), None)
        out["black_ms"] = round(t[nb] - t[fb], 1) if nb is not None else None
    else:
        out["black_ms"] = 0.0
    fv = next((i for i in range(endi) if t[i] >= 0 and np.abs(sq[i] - fin_sq).mean() < 20), None)
    out["first"] = round(t[fv], 1) if fv is not None else None
    # viewer = first frame that no longer shows the screen from before the launch (the viewer window is up, black or
    # not); nonblack = first viewer frame with a picture in its centre (any picture: the system's open animation shifts
    # the first frame sideways by ~2 %, which `first`'s match against the final picture rejects)
    pre = [i for i in range(len(t)) if t[i] < 0]
    if pre:
        ref = band[pre[-1]]
        vw = next((i for i in range(endi) if t[i] >= 0 and np.abs(band[i] - ref).mean() > 6), None)
        out["viewer"] = round(t[vw], 1) if vw is not None else None
        nbk = next((i for i in range(vw, endi) if sqm[i] >= 8), None) if vw is not None else None
        out["nonblack"] = round(t[nbk], 1) if nbk is not None else None
    # refinement happens within ~0.5 s of the first picture; later changes (swipe start, UI) are not full-res
    ch = [i for i in range(max(1, fv or 1), endi) if np.abs(band[i] - band[i - 1]).mean() > 1.0 and fv is not None and t[i] - t[fv] <= 1500]
    out["fullres"] = round(t[ch[-1]], 1) if ch else out["first"]
    if sw:
        fin2 = band[-1]
        out["next_visible"] = next((round(t[i] - t[sw], 1) for i in range(sw, len(t)) if all(np.abs(band[j] - fin2).mean() < 4 for j in range(i, min(len(t), i + 6)))), None)
    return out


def visual_albumnew(m, start):
    """the album opened right after a photo was added (the grid is 3 columns; boxes = the middle of tile 1 and tile 2):
    newthumb = first frame whose top-left tile shows the new photo (as in the last frame, and it stays);
    listed   = first frame whose 2nd tile shows what it shows in the last frame (the old newest photo moved over:
               the list with the new photo is on screen, its own thumbnail may still be decoding)"""
    try: t, im, how = times(m, start)
    except Exception: return {}
    if t is None: return {"err": "no_ocr"}
    y0, y1 = int(H * .14), int(H * .25)
    t1 = im[:, y0:y1, int(W * .05):int(W * .29)]; t2 = im[:, y0:y1, int(W * .38):int(W * .62)]
    f1, f2 = t1[-1], t2[-1]
    out = {}
    stays = lambda a, f, i: all(np.abs(a[j] - f).mean() < 10 for j in range(i, min(len(t), i + 4)))
    nt = next((i for i in range(len(t)) if t[i] >= 0 and stays(t1, f1, i)), None)
    ls = next((i for i in range(len(t)) if t[i] >= 0 and stays(t2, f2, i)), None)
    out["newthumb"] = round(t[nt], 1) if nt is not None else None
    out["listed"] = round(t[ls], 1) if ls is not None else None
    # sanity: the new photo must look unlike the old newest one (which is what tile 2 ends up showing)
    out["new_ok"] = bool(np.abs(f1 - f2).mean() > 15)
    if not out["new_ok"]: out["newthumb"] = out["listed"] = None
    return out


def visual_player(m, start):
    try: t, im, how = times(m, start)
    except Exception: return {}
    if t is None: return {"err": "no_ocr"}
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



def visual_back(m):
    """BACK from the viewer: ms since the key was handled (key_done) until the screen shows the album as it ends up
    (settled: stays within a small distance of the last frame) and until the first visible change"""
    mm = re.match(r"\d\d:\d\d:(\d\d)\.(\d{3})", m.get("key_done") or "")
    if not mm: return {"err": "no_key"}
    start = int(mm.group(1)) + int(mm.group(2)) / 1000
    try: t, im, how = times(m, start)
    except Exception: return {}
    if t is None or how != "ocr": return {"err": "no_ocr"}
    fin = body(im[-1]); pre = [i for i in range(len(t)) if t[i] < 0]
    ref = body(im[pre[-1]]) if pre else body(im[0])
    out = {}
    ch = next((i for i in range(len(t)) if t[i] >= -100 and np.abs(body(im[i]) - ref).mean() > 4), None)
    out["back_change"] = round(t[ch], 1) if ch is not None else None
    s_i = next((i for i in range(len(t)) if t[i] >= -100 and all(np.abs(body(im[j]) - fin).mean() < 3 for j in range(i, len(t)))), None)
    out["back_settled"] = round(t[s_i], 1) if s_i is not None else None
    return out

calibrate()
rows = collections.defaultdict(list)
for m in R:
    s = m["scen"]; b = m["build"]; r = {"total": m.get("total"), "launch": m.get("launch")}
    if s == "gridcold":
        st0 = start_ss(m); sp, sp_end = splash(m, st0); r["splash_ms"] = sp
        r.update(visual_grid(m, st0, sp_end))
        r["m_grid_drawn"] = fgperf(m, "grid_drawn", st0) if st0 else None
        r["m_last_cover"] = fgperf(m, "thumb_drawn", st0, last=True) if st0 else None
        r["pss_mb"] = round(m["pss_kb"] / 1024, 1) if m.get("pss_kb") else None
        r["displayed"] = displayed(m, st0)
    elif s in ("albumcold", "albumwarm"):
        st0 = start_ss(m, "MediaActivity"); sp, sp_end = splash(m, st0); r["splash_ms"] = sp
        r.update(visual_grid(m, st0, sp_end))
    elif s == "albumtap":
        st0 = start_ss(m, "MediaActivity"); sp, sp_end = splash(m, st0); r["splash_ms"] = sp
        r.update(visual_grid(m, st0, sp_end))
        r["displayed"] = displayed(m, st0); tp = tap_rel(m, st0); r["tap_to_start"] = None if tp is None else round(-tp, 1)
        for k in ("content", "settled", "displayed"):   # times since the TAP
            r["tap_" + k] = round(r[k] - tp, 1) if (tp is not None and isinstance(r.get(k), (int, float))) else None
        # the tap must have opened the Camera album (FGPerf lines name the folder), else the tile moved
        r["album_ok"] = int(any("FGPerf" in l and "DCIM/Camera" in l for l in m.get("logs", [])))
        if not r["album_ok"]:
            print("WARN albumtap opened another album:", b, m.get("run"), m.get("rec"))
            for k in ("content", "settled", "blank_ms", "tap_content", "tap_settled", "tap_displayed"): r[k] = None
    elif s in ("albumnew", "albumnewwarm"):
        st0 = start_ss(m, "MediaActivity"); sp, sp_end = splash(m, st0); r["splash_ms"] = sp
        r.update(visual_grid(m, st0, sp_end)); r.update(visual_albumnew(m, st0))
        r["m_rescan"] = fgperf(m, "incremental_rescan", st0) if st0 else None
    elif s == "photo":
        st0 = start_ss(m, "ViewPagerActivity"); r["splash_ms"] = splash(m, st0)[0]; r.update(visual_photo(m, st0))
        r["m_fullres"] = fgperf(m, "photo_fullres_ready", st0) if st0 else None
    elif s == "player":
        st0 = start_ss(m, "VideoPlayerActivity"); r["splash_ms"] = splash(m, st0)[0]; r.update(visual_player(m, st0))
        r["m_first_frame"] = fgperf(m, "player_first_frame", st0) if st0 else None
    elif s in ("picker", "pickerwarm"):
        st0 = start_ss(m); sp, sp_end = splash(m, st0); r["splash_ms"] = sp; r.update(visual_grid(m, st0, sp_end))
        r["displayed"] = displayed(m, st0)
    elif s in ("phototap", "tapnew"):
        st0 = start_ss(m, "ViewPagerActivity"); r["splash_ms"] = splash(m, st0)[0]; r.update(visual_photo(m, st0))
        r["displayed"] = displayed(m, st0); tp = tap_rel(m, st0); r["tap_to_start"] = None if tp is None else round(-tp, 1)
        # a run whose overlay clock could not be read is placed on the time axis by the fallback (Displayed + the
        # median screen latency of the OCR'd runs). That latency differs by ~60 ms between builds with and without
        # the open animation, so for the tap scenarios such a run only keeps its durations (black_ms, fill)
        r["fill"] = round(r["fullres"] - r["first"], 1) if all(isinstance(r.get(k), (int, float)) for k in ("first", "fullres")) else None
        if r.get("how") != "ocr":
            for k in ("first", "fullres", "viewer", "nonblack"): r[k] = None
        for k in ("first", "fullres", "displayed", "viewer", "nonblack"):   # times since the TAP
            r["tap_" + k] = round(r[k] - tp, 1) if (tp is not None and isinstance(r.get(k), (int, float))) else None
        if s == "tapnew":   # the viewer's title bar must name the pushed copy, else the tap opened another photo
            r["title"] = title_ocr(m); r["title_ok"] = "cmp" in r["title"].lower().replace(" ", "")
            if not r["title_ok"]:
                print("WARN tapnew opened another photo:", b, m.get("run"), repr(r["title"]), m.get("rec"))
                for k in ("first", "fullres", "black_ms", "fill", "tap_first", "tap_fullres", "tap_displayed", "tap_viewer", "tap_nonblack"): r[k] = None
    elif s == "back":
        r.update(visual_back(m))
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
