#!/usr/bin/env python3
# Usage: real_suite.py <outdir> <plan>   plan e.g. "A:3,B:3,A:2,B:2"; A/B = /data/local/tmp/fgA.apk / fgB.apk
"""Regression suite on the REAL screen (display 0). No virtual display is ever created.

Needs the owner's phone unlocked, on the home screen, hands off. Safety:
- starts only when display 0 is ON, the keyguard is not showing and the launcher (or the gallery) has focus;
- two `getevent` streams (touchscreen fts + gpio keys) run for the whole session: ANY physical touch or key press aborts
  at once (our own `input` injections do not go through /dev/input, so they never trigger it);
- before every run the screen/keyguard/focus are checked again; a failed check aborts.
On abort the finished runs are saved (json) and the script exits 3. At the end it presses HOME.

Per run it records ActivityTaskManager START + `am start -W` TotalTime/LaunchState, FGPerf markers with device-monotonic
timestamps (all converted to ms since START), PSS for grid launches, and for photo/video a screenrecord (half size) used
for alignment-free visual checks (black gap before the picture, flicker after it).
"""
import json, os, re, subprocess, sys, threading, time
S = os.environ.get("FG_SERIAL", "10.13.13.4:5555"); PKG = "org.fossify.gallery"; LAUNCH = f"{PKG}/.activities.SplashActivity.Green"
LAUNCHER = "com.teslacoilsw.launcher"; CAM = "/storage/emulated/0/DCIM/Camera"
out, plan = sys.argv[1], [(p.split(":")[0], int(p.split(":")[1])) for p in sys.argv[2].split(",")]
SCEN = (sys.argv[3] if len(sys.argv) > 3 else "gridcold,gridwarm,albumcold,albumwarm,photo,video").split(",")
os.makedirs(out, exist_ok=True)
# device profile (defaults = owner's Pixel 8 Pro); Pixel 6 Pro: FG_SERIAL=192.168.1.69:5555 FG_INPUTS=/dev/input/event3,/dev/input/event1
# FG_SWIPE="1250 1560 150 1560" FG_REC=720x1560
INPUTS = os.environ.get("FG_INPUTS", "/dev/input/event2,/dev/input/event0").split(",")
SWIPE = os.environ.get("FG_SWIPE", "1150 1500 150 1500")
REC = os.environ.get("FG_REC", "672x1496")
# never run while another job owns the phone: FG_BLACKOUT="08:50-09:25" (local time, comma-separated ranges)
BLACKOUT = [tuple(r.split("-")) for r in os.environ.get("FG_BLACKOUT", "").split(",") if "-" in r]


def in_blackout():
    now = time.strftime("%H:%M")
    return any(a <= now < b for a, b in BLACKOUT)
abort = threading.Event(); why = []


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def sh(c, t=60):
    try:
        return subprocess.run(["adb", "-s", S, "shell", c], capture_output=True, text=True, timeout=t).stdout
    except subprocess.TimeoutExpired:
        return ""


def state():
    txt = sh("dumpsys display | awk '/Display Id=0$/{f=1;next} f&&/Display State=/{print $2;exit}'; "
             "dumpsys window | grep -E 'mCurrentFocus=|isKeyguardShowing=' | head -2", 20)
    disp = "State=ON" in txt
    kg = "isKeyguardShowing=true" in txt
    m = re.search(r"mCurrentFocus=Window\{\S+ u0 ([^/}\s]+)", txt)
    return disp, kg, (m.group(1) if m else None)


def battery_ok():
    txt = sh("dumpsys battery", 20)
    lvl = re.search(r"level: (\d+)", txt); stt = re.search(r"status: (\d+)", txt)
    return not (lvl and int(lvl.group(1)) < 40 and not (stt and stt.group(1) in ("2", "5")))   # 2 charging, 5 full


def ok_now():
    disp, kg, foc = state()
    good = disp and not kg and (foc is None or foc.startswith(LAUNCHER) or foc.startswith(PKG))
    return good, (disp, kg, foc)


def input_watch(dev):
    p = subprocess.Popen(["adb", "-s", S, "shell", f"getevent -q {dev}"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    watchers.append(p)
    for line in p.stdout:
        if line.strip() and not abort.is_set():
            why.append(f"physical input on {dev}"); abort.set(); log("ABORT: owner touched the phone", dev)
            return


class Abort(Exception):
    pass


def check():
    if abort.is_set(): raise Abort(why[-1] if why else "abort")
    if in_blackout():
        why.append("blackout window"); abort.set(); log("ABORT: blackout window (another job uses the phone)"); raise Abort("blackout")
    sh("input keyevent 0")   # KEYCODE_UNKNOWN: ignored by apps, but counts as user activity so the screen stays on
    good, st = ok_now()
    if good and not battery_ok():
        good, st = False, "battery below 40% and not charging"
    if not good:
        why.append(f"state {st}"); abort.set(); log("ABORT: phone state", st); raise Abort(str(st))


def nap(s):
    if abort.wait(s): raise Abort(why[-1] if why else "abort")


def perf_lines():
    return sh("logcat -d -v monotonic -s FGPerf:I ActivityTaskManager:I", 30).splitlines()


def mono(line):
    try: return float(line.split()[0]) * 1000
    except Exception: return None


def parse(lines, act):
    st = [mono(l) for l in lines if "START u0" in l and act in l]
    t0 = st[-1] if st else None
    ev = []
    for l in lines:
        if "FGPerf" in l and t0 is not None:
            t = mono(l)
            if t is not None: ev.append([round(t - t0, 1), l.split("FGPerf", 1)[1].lstrip(" :")])
    return t0, ev


def amstart(args):
    r = sh(f"am start -W --display 0 {args}", 30)
    tt = re.search(r"TotalTime: (\d+)", r); ls = re.search(r"LaunchState: (\w+)", r)
    return (int(tt.group(1)) if tt else None), (ls.group(1) if ls else None)


def su_amstart(args):
    r = sh(f"su -c 'am start -W --display 0 {args}'", 30)
    tt = re.search(r"TotalTime: (\d+)", r); ls = re.search(r"LaunchState: (\w+)", r)
    return (int(tt.group(1)) if tt else None), (ls.group(1) if ls else None)


def pss():
    txt = sh(f"dumpsys meminfo {PKG}", 30)
    r = {"pss_kb": int(next((l.split()[2] for l in txt.splitlines() if "TOTAL PSS:" in l), "0"))}
    for l in txt.splitlines():
        for name in ("Java Heap:", "Native Heap:", "Graphics:"):
            if l.strip().startswith(name): r[name[:-1].replace(" ", "_").lower() + "_kb"] = int(l.split(":")[1].split()[0])
    return r


def home():
    # a second HOME on Nova's home screen opens its app search with the keyboard: BACK twice closes both (no-op otherwise)
    sh("input keyevent 3"); nap(0.6); sh("input keyevent 4"); nap(0.3); sh("input keyevent 4"); nap(0.8)


def launcher_start(): return amstart(f"-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {LAUNCH}")
def camera(): return su_amstart(f"-n {PKG}/.activities.MediaActivity --es directory \"{CAM}\"")


def viewer(path): return su_amstart(f"-n {PKG}/.activities.ViewPagerActivity --es path \"{path}\" --ez is_from_gallery true --ez show_all false")


def record_start(name, secs):
    sh(f"rm -f /data/local/tmp/{name}.mp4")
    return subprocess.Popen(["adb", "-s", S, "shell", f"screenrecord --size {REC} --bit-rate 8000000 --time-limit {secs} /data/local/tmp/{name}.mp4"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def record_pull(p, name):
    try: p.wait(timeout=20)
    except subprocess.TimeoutExpired: sh("pkill -INT -x screenrecord"); time.sleep(1)
    subprocess.run(["adb", "-s", S, "pull", f"/data/local/tmp/{name}.mp4", f"{out}/{name}.mp4"], capture_output=True)
    sh(f"rm -f /data/local/tmp/{name}.mp4")


def video_tile():
    """centre of the first video tile (its duration label) on the current Camera grid screen, via uiautomator"""
    x = sh("uiautomator dump /data/local/tmp/fg_ui.xml >/dev/null 2>&1; cat /data/local/tmp/fg_ui.xml; rm -f /data/local/tmp/fg_ui.xml", 30)
    for mm in re.finditer(r'resource-id="org\.fossify\.gallery:id/video_duration"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
        a, b, c, d = map(int, mm.groups())
        if b > 400: return ((a + c) // 2, (b + d) // 2)
    return None


def run_one(scen, tag):
    check()
    m = {"scen": scen}
    if scen == "gridcold":
        sh(f"am force-stop {PKG}"); home(); check(); sh("logcat -c")
        m["total"], m["launch"] = launcher_start(); nap(3.5)
        m["t0"], m["ev"] = parse(perf_lines(), PKG); m.update(pss())
    elif scen == "gridwarm":
        home(); check(); sh("logcat -c")                       # grid activity still alive from the previous run
        m["total"], m["launch"] = launcher_start(); nap(2)
        m["t0"], m["ev"] = parse(perf_lines(), PKG)
    elif scen in ("albumcold", "albumwarm"):
        # screenrecord: the album's thumbnails-settled time is measured visually (the media_thumbs marker only fires
        # after 20 thumbnails, which a 6 Pro first screen with date headers never reaches)
        sh(f"am force-stop {PKG}"); home()
        if scen == "albumwarm": launcher_start(); nap(3)
        check(); sh("logcat -c")
        rec = f"{scen}_{tag}"
        rp = record_start(rec, 4); time.sleep(0.8)
        m["total"], m["launch"] = camera(); nap(3)
        m["t0"], m["ev"] = parse(perf_lines(), "MediaActivity")
        record_pull(rp, rec); m["rec"] = rec + ".mp4"
    elif scen == "player":   # the owner's path for videos (open_videos_on_separate_screen): grid tap -> VideoPlayerActivity
        sh(f"am force-stop {PKG}"); home(); camera(); nap(2.5); check(); sh("logcat -c")
        rec = f"{scen}_{tag}"
        rp = record_start(rec, 5); time.sleep(0.6)
        m["total"], m["launch"] = su_amstart(f"-n {PKG}/.activities.VideoPlayerActivity -d \"file://{VIDEO}\" -t video/mp4")
        nap(3.5)
        m["t0"], m["ev"] = parse(perf_lines(), "VideoPlayerActivity")
        record_pull(rp, rec); m["rec"] = rec + ".mp4"
    elif scen == "playertap":   # the owner's real path: tap a video tile in the Camera grid (grid -> launchGesturePlayer)
        sh(f"am force-stop {PKG}"); home(); camera(); nap(2.5); check()
        xy = video_tile()
        if not xy:
            m["error"] = "no video tile on the first Camera screen"; sh("input keyevent 3"); return m
        sh("logcat -c")
        rec = f"{scen}_{tag}"
        rp = record_start(rec, 5); time.sleep(0.6)
        sh(f"input -d 0 tap {xy[0]} {xy[1]}"); nap(3.5)
        lines = perf_lines()
        m["t0"], m["ev"] = parse(lines, "VideoPlayerActivity")
        d = next((l for l in lines if "Displayed" in l and "VideoPlayerActivity" in l), "")
        dm = re.search(r"\+(?:(\d+)s)?(\d+)ms", d)
        m["total"] = (int(dm.group(1) or 0) * 1000 + int(dm.group(2))) if dm else None
        m["launch"] = "TAP"
        record_pull(rp, rec); m["rec"] = rec + ".mp4"
    elif scen in ("photo", "video"):
        sh(f"am force-stop {PKG}"); home(); camera(); nap(2.5); check(); sh("logcat -c")
        rec = f"{scen}_{tag}"
        rp = record_start(rec, 7 if scen == "photo" else 5); time.sleep(0.6)
        m["total"], m["launch"] = viewer(PHOTO if scen == "photo" else VIDEO)
        if scen == "photo":
            nap(2.2); check()
            sh(f"input -d 0 swipe {SWIPE} 120"); nap(2.2)
        else:
            nap(3.5)
        m["t0"], m["ev"] = parse(perf_lines(), "ViewPagerActivity")
        record_pull(rp, rec); m["rec"] = rec + ".mp4"
    sh(f"input keyevent 3")
    return m


watchers = []
disp, kg, foc = state()
log("start state", disp, kg, foc)
good, st = ok_now()
if not good:
    log("NOT READY", st); sys.exit(3)
newest = lambda ext: CAM + "/" + sh(f"ls -t {CAM} | grep -m1 -i '\\.{ext}$'").strip()
PHOTO, VIDEO = newest("jpg"), newest("mp4")
log("photo", PHOTO, "video", VIDEO)
for dev in INPUTS:
    threading.Thread(target=input_watch, args=(dev,), daemon=True).start()
time.sleep(1)
results = json.load(open(f"{out}/real_results.json")) if os.path.exists(f"{out}/real_results.json") else []
done = {(m["block"], m["scen"], m["run"]) for m in results}   # resume after an abort: finished runs are kept
rc = 0
try:
    for bi, (build, k) in enumerate(plan):
        if all((bi, sc, r) in done for sc in SCEN for r in range(k)):
            continue
        check()
        # "B+speed" = install fgB.apk, then AOT-compile it with that filter;
        # "B@speed-profile" = NO reinstall (keeps the app's accumulated ART profile), save the running process's
        # profile (SIGUSR1), then recompile with that filter (verify = back to uncompiled/JIT)
        if "@" in build:
            apk, _, comp = build.partition("@")
            pid = sh(f"pidof {PKG}").strip()
            if pid: sh(f"su -c 'kill -USR1 {pid}'"); time.sleep(2)
            sh(f"am force-stop {PKG}")
        else:
            apk, _, comp = build.partition("+")
            log("install", build, sh(f"su -c 'pm install -r -d /data/local/tmp/fg{apk}.apk'").strip())
        if comp:
            log("compile", comp, sh(f"cmd package compile -m {comp} -f {PKG}", 300).strip(),
                sh(f"pm art dump {PKG} | grep -m1 -o 'status=[a-z-]*'").strip())
        ver = sh(f"dumpsys package {PKG} | grep -m1 versionName").strip()
        # warm-up after install (not measured): dex/profile state, scan cache, thumbnails in cache
        home(); launcher_start(); nap(2.5); camera(); nap(2); viewer(PHOTO); nap(1.5); home()
        for scen in SCEN:
            for r in range(k):
                if (bi, scen, r) in done: continue
                m = run_one(scen, f"{build}{bi}_{r}")
                m.update({"build": build, "block": bi, "run": r, "ver": ver})
                results.append(m)
                ev = m.get("ev", [])
                log(build, bi, scen, r, "total", m.get("total"), m.get("launch"), [e for e in ev if any(x in e[1] for x in ("grid_drawn", "media_thumbs", "photo_placeholder", "photo_screen_ready", "photo_fullres"))][:4])
                json.dump(results, open(f"{out}/real_results.json", "w"), indent=0)
except Abort as e:
    log("aborted:", e); rc = 3
finally:
    for p in watchers:
        try: p.terminate()
        except Exception: pass
    json.dump(results, open(f"{out}/real_results.json", "w"), indent=0)
    if not abort.is_set():
        sh("input keyevent 3")
    log("runs saved", len(results), "virtual displays:", sh("dumpsys display | grep -c 'type VIRTUAL'").strip())
sys.exit(rc)
