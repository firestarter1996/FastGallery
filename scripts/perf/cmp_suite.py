#!/usr/bin/env python3
# Usage: cmp_suite.py <outdir> <plan> [scenarios]
#   plan e.g. "orig:3,fast4:3,...,fast12:3,fast12:2,...,orig:2"   (APKs: $CMP_APKS/<name>.apk, default ~/fg-cmp/apks)
"""All-versions comparison on the REAL screen (display 0) of the Pixel 6 Pro. No virtual display is ever created.

Every build is measured the same way, whether or not it has FGPerf markers (the original and fast4 have none):
- times are ms since ActivityTaskManager START (logcat wall clock);
- screen timing comes from `screenrecord --bugreport`, whose overlay prints each frame's device wall-clock time; a few
  frames are OCR'd (tesseract, seconds.millis only: the status-bar clock underneath garbles the minutes) to map the
  video's pts to the logcat clock; frame 0 (the bugreport info page) is dropped;
- the launch splash is timed from WindowManager's "Splash Screen org.fossify.gallery" window (added -> removed);
- scroll smoothness = `dumpsys gfxinfo` after scripted flings on display 0;
- each build: install (-r -d; the original has another signer -> uninstall + data transplant), warm-up, profile save
  (SIGUSR1) and `compile -m speed-profile -f`, so none runs uncompiled.
Safety as real_suite.py: starts only unlocked with the launcher focused; any physical touch/key aborts; FG_BLACKOUT.
"""
import json, os, re, subprocess, sys, threading, time
S = os.environ.get("FG_SERIAL", "192.168.1.69:5555"); PKG = "org.fossify.gallery"
LAUNCH = f"{PKG}/.activities.SplashActivity.Green"; LAUNCHER = "com.teslacoilsw.launcher"
CAM = "/storage/emulated/0/DCIM/Camera"
INPUTS = os.environ.get("FG_INPUTS", "/dev/input/event3,/dev/input/event1").split(",")
SWIPE = os.environ.get("FG_SWIPE", "1250 1560 150 1560"); REC = os.environ.get("FG_REC", "720x1560")
BLACKOUT = [tuple(r.split("-")) for r in os.environ.get("FG_BLACKOUT", "").split(",") if "-" in r]
APKS = os.environ.get("CMP_APKS", os.path.expanduser("~/fg-cmp/apks"))
ORIG_SIGNER = "orig"          # builds whose APK has a different signer than FastGallery
out, plan = sys.argv[1], [(p.split(":")[0], int(p.split(":")[1])) for p in sys.argv[2].split(",")]
SCEN = (sys.argv[3] if len(sys.argv) > 3 else "gridcold,gridwarm,albumcold,albumwarm,photo,player,picker,scrollgrid,scrollalbum").split(",")
os.makedirs(out, exist_ok=True)
abort = threading.Event(); why = []; watchers = []


def log(*a): print(time.strftime("%H:%M:%S"), *a, flush=True)


def sh(c, t=60):
    try: return subprocess.run(["adb", "-s", S, "shell", c], capture_output=True, text=True, timeout=t).stdout
    except subprocess.TimeoutExpired: return ""


def su(c, t=120): return sh("su -c '" + c.replace("'", "'\\''") + "'", t)


class Abort(Exception): pass


def state():
    txt = sh("dumpsys display | awk '/Display Id=0$/{f=1;next} f&&/Display State=/{print $2;exit}'; "
             "dumpsys window | grep -E 'mCurrentFocus=|isKeyguardShowing=' | head -2", 20)
    m = re.search(r"mCurrentFocus=Window\{\S+ u0 ([^/}\s]+)", txt)
    return "State=ON" in txt, "isKeyguardShowing=true" in txt, (m.group(1) if m else None)


def check():
    if abort.is_set(): raise Abort(why[-1] if why else "abort")
    now = time.strftime("%H:%M")
    if any(a <= now < b for a, b in BLACKOUT):
        why.append("blackout"); abort.set(); raise Abort("blackout")
    sh("input keyevent 0")
    on, kg, foc = state()
    if not on or kg or not (foc is None or foc.startswith(LAUNCHER) or foc.startswith(PKG) or foc.startswith("android")):
        why.append(f"state {on} {kg} {foc}"); abort.set(); log("ABORT state", on, kg, foc); raise Abort("state")


def nap(s):
    if abort.wait(s): raise Abort(why[-1] if why else "abort")


def input_watch(dev):
    p = subprocess.Popen(["adb", "-s", S, "shell", f"getevent -q {dev}"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    watchers.append(p)
    for line in p.stdout:
        if line.strip() and not abort.is_set():
            why.append(f"physical input on {dev}"); abort.set(); log("ABORT: physical input", dev); return


def home():
    # a second HOME on Nova's home screen opens its app search with the keyboard: BACK twice closes both (no-op otherwise)
    sh("input keyevent 3"); nap(0.6); sh("input keyevent 4"); nap(0.3); sh("input keyevent 4"); nap(0.8)


def amstart(args, root=False):
    c = f"am start -W --display 0 {args}"
    r = su(c, 40) if root else sh(c, 40)
    tt = re.search(r"TotalTime: (\d+)", r); ls = re.search(r"LaunchState: (\w+)", r)
    return (int(tt.group(1)) if tt else None), (ls.group(1) if ls else None)


def launcher_start(): return amstart(f"-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {LAUNCH}")
def camera(): return amstart(f"-n {PKG}/.activities.MediaActivity --es directory \"{CAM}\"", root=True)
def viewer(p): return amstart(f"-n {PKG}/.activities.ViewPagerActivity --es path \"{p}\" --ez is_from_gallery true --ez show_all false", root=True)
def player(p): return amstart(f"-n {PKG}/.activities.VideoPlayerActivity -d \"file://{p}\" -t video/mp4", root=True)
def picker(): return amstart(f"-a android.intent.action.GET_CONTENT -t 'image/*' -c android.intent.category.OPENABLE -n {PKG}/.activities.MainActivity")


def scan_file(path):
    """adb push / rm do not touch MediaStore (the gallery lists from it): scan the file in / out (verified 2026-09-28)"""
    sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{path} >/dev/null 2>&1"); nap(1.5)


def rec_start(name, secs):
    sh(f"rm -f /data/local/tmp/{name}.mp4")
    p = subprocess.Popen(["adb", "-s", S, "shell", f"screenrecord --bugreport --size {REC} --bit-rate 8000000 --time-limit {secs} /data/local/tmp/{name}.mp4"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.0)      # the info page is frame 0; real frames start ~0.3 s later
    return p


def rec_pull(p, name):
    try: p.wait(timeout=25)
    except subprocess.TimeoutExpired: sh("pkill -INT -x screenrecord"); time.sleep(1)
    subprocess.run(["adb", "-s", S, "pull", f"/data/local/tmp/{name}.mp4", f"{out}/{name}.mp4"], capture_output=True)
    sh(f"rm -f /data/local/tmp/{name}.mp4")
    return name + ".mp4"


def logs():
    """wall-clock (threadtime) lines: START, Displayed, splash window, FGPerf"""
    return sh("logcat -d -b all -v threadtime | grep -E 'START u0|Displayed|Splash Screen org.fossify.gallery|FGPerf'", 30).splitlines()


def gfx_reset(): sh(f"dumpsys gfxinfo {PKG} reset >/dev/null")


def gfx():
    t = sh(f"dumpsys gfxinfo {PKG}", 30)
    g = lambda pat: (lambda m: m.group(1) if m else None)(re.search(pat, t))
    return {"frames": g(r"Total frames rendered: (\d+)"), "janky": g(r"Janky frames: \d+ \(([\d.]+)%\)"),
            "p50": g(r"50th percentile: (\d+)ms"), "p90": g(r"90th percentile: (\d+)ms"),
            "p95": g(r"95th percentile: (\d+)ms"), "p99": g(r"99th percentile: (\d+)ms")}


def flings(n=8):
    for i in range(n):
        if i % 2 == 0: sh("input -d 0 swipe 720 2500 720 900 110")
        else: sh("input -d 0 swipe 720 900 720 2500 110")
        nap(0.9)


def pss():
    t = sh(f"dumpsys meminfo {PKG}", 30)
    m = re.search(r"TOTAL PSS:\s+(\d+)", t)
    return int(m.group(1)) if m else None


def run_one(scen, tag):
    check(); m = {"scen": scen}
    rec = f"{scen}_{tag}"
    if scen == "gridcold":
        sh(f"am force-stop {PKG}"); home(); check(); sh("logcat -b all -c")
        rp = rec_start(rec, 6); m["total"], m["launch"] = launcher_start(); nap(4.0)
        m["pss_kb"] = pss(); m["rec"] = rec_pull(rp, rec)
    elif scen == "gridwarm":
        home(); check(); sh("logcat -b all -c")
        m["total"], m["launch"] = launcher_start(); nap(1.5)
    elif scen in ("albumcold", "albumwarm"):
        sh(f"am force-stop {PKG}"); home()
        if scen == "albumwarm": launcher_start(); nap(3)
        check(); sh("logcat -b all -c")
        rp = rec_start(rec, 5); m["total"], m["launch"] = camera(); nap(3.2); m["rec"] = rec_pull(rp, rec)
    elif scen == "photo":
        sh(f"am force-stop {PKG}"); home(); camera(); nap(2.5); check(); sh("logcat -b all -c")
        rp = rec_start(rec, 8); m["total"], m["launch"] = viewer(PHOTO); nap(2.4); check()
        m["swipe_wall"] = sh("date +%H:%M:%S.%N").strip()
        sh(f"input -d 0 swipe {SWIPE} 120"); nap(2.4); m["rec"] = rec_pull(rp, rec)
    elif scen == "player":
        sh(f"am force-stop {PKG}"); home(); camera(); nap(2.5); check(); sh("logcat -b all -c")
        rp = rec_start(rec, 5); m["total"], m["launch"] = player(VIDEO); nap(3.2); m["rec"] = rec_pull(rp, rec)
        sh("input keyevent 4")
    elif scen in ("picker", "pickerwarm"):
        # another app's "choose a photo": the chooser starts MainActivity with GET_CONTENT (on this phone the implicit
        # intent goes to Google's photo picker, so the explicit start is the chooser-tap path); warm = process alive
        sh(f"am force-stop {PKG}"); home()
        if scen == "pickerwarm": launcher_start(); nap(3); home()
        check(); sh("logcat -b all -c")
        rp = rec_start(rec, 5); m["total"], m["launch"] = picker(); nap(3.2); m["rec"] = rec_pull(rp, rec)
        sh("input keyevent 4")
    elif scen in ("phototap", "tapnew"):
        # the instant-placeholder path: a real tap on the top-left tile of the Camera grid (CMP_PHOTO_TILE "x y"; tile
        # centres come from a screencap since uiautomator is unusable on the 6 Pro).
        #   phototap = the newest photo, which the warm-up and the photo scenario opened before (its screen-size image
        #              is in Glide's disk cache: the placeholder only saves the black first frame)
        #   tapnew   = a photo the gallery has never opened: a fresh copy of the test photo (EXIF stripped so it sorts
        #              newest) pushed under a NEW name before the run and deleted after it. Glide keys on the path, so
        #              the grid decodes its thumbnail into memory and the viewer's screen-size image is a cache miss.
        sh(f"am force-stop {PKG}"); home()
        if scen == "tapnew":
            m["tap_file"] = f"cmp_tap_{tag}.jpg"
            subprocess.run(["adb", "-s", S, "push", TAP_SRC, f"{CAM}/{m['tap_file']}"], capture_output=True)
            sh(f"touch {CAM}/{m['tap_file']}"); scan_file(f"{CAM}/{m['tap_file']}"); nap(2.0)
        camera(); nap(3.5 if scen == "tapnew" else 2.5); check(); sh("logcat -b all -c")
        rp = rec_start(rec, 5)
        # one shell call: `input` is a Java tool (~90 ms to start), so the time AFTER it returns (it waits for the UP
        # to be handled) is within a few ms of the touch release; the time before it is only kept for reference
        w = sh(f"date +%H:%M:%S.%N; input -d 0 tap {PHOTO_TILE}; date +%H:%M:%S.%N").split()
        m["tap_wall"], m["tap_done"] = (w[0], w[-1]) if len(w) >= 2 else (None, None)
        nap(2.6); m["rec"] = rec_pull(rp, rec)
        sh("input keyevent 4")
        if scen == "tapnew": sh(f"rm -f {CAM}/{m['tap_file']}"); scan_file(f"{CAM}/{m['tap_file']}"); nap(1.0)
    elif scen == "scrollgrid":
        sh(f"am force-stop {PKG}"); home(); launcher_start(); nap(3); check(); gfx_reset(); flings(); m["gfx"] = gfx()
    elif scen == "scrollalbum":
        sh(f"am force-stop {PKG}"); home(); camera(); nap(3); check(); gfx_reset(); flings(); m["gfx"] = gfx()
    if scen not in ("scrollgrid", "scrollalbum"):
        m["logs"] = logs()
    sh("input keyevent 3")
    return m


def perms():
    sh(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
    for p in ("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "POST_NOTIFICATIONS"): sh(f"pm grant {PKG} android.permission.{p}")
    sh(f"dumpsys deviceidle whitelist +{PKG} >/dev/null; cmd appops set {PKG} RUN_ANY_IN_BACKGROUND allow")


def save_data(tar, with_cache):
    ex = "" if with_cache else f"--exclude={PKG}/cache --exclude={PKG}/code_cache"
    su(f"am force-stop {PKG}; tar -C /data/data {ex} -cf {tar} {PKG}", 300)


def restore_data(tar):
    d = f"/data/data/{PKG}"
    su(f"am force-stop {PKG}; [ -d {d}/shared_prefs ] || exit 1; U=$(stat -c %u {d}); "
       f"rm -rf {d}/cache {d}/code_cache {d}/databases {d}/files {d}/no_backup {d}/shared_prefs; "
       f"tar -C /data/data -xf {tar}; chown -R $U:$U {d}; restorecon -R {d}", 300)


current_signer = [None]


class InstallFailed(Exception): pass


def installed_ver(): return sh(f"dumpsys package {PKG} | grep -m1 versionName").strip().split("=", 1)[-1]


def ver_ok(build, v): return ("-fast" not in v) if build == ORIG_SIGNER else v.endswith("-" + build)


def dismiss_play_protect():
    """Play Protect's "Send app for a security check?" dialog (com.android.vending PlayProtectDialogsActivity) blocks
    `pm install` until it is answered: on 2026-09-27/28 every fast11 install timed out that way, the previous build
    stayed installed and was measured as fast11. verifier_verify_adb_installs=0 (set below) stops the dialog; this is
    the fallback when it shows anyway."""
    if "PlayProtectDialogsActivity" in sh("dumpsys window | grep mCurrentFocus="):
        log("Play Protect dialog on screen: BACK"); sh("input keyevent 4"); time.sleep(1.5); return True
    return False


def install(build):
    """install + VERIFY the installed versionName (orig = no -fast suffix, fastN = -fastN); 3 attempts, then InstallFailed"""
    apk = f"/data/local/tmp/cmp_{build}.apk"
    subprocess.run(["adb", "-s", S, "push", f"{APKS}/{build}.apk", apk], capture_output=True)
    signer = "orig" if build == ORIG_SIGNER else "fast"
    if current_signer[0] is None:
        current_signer[0] = "fast" if "-fast" in installed_ver() else "orig"
    v = installed_ver()
    for attempt in range(1, 4):
        if signer != current_signer[0]:
            old = current_signer[0]
            save_data(f"/data/local/tmp/cmp_{old}data.tar", with_cache=(old == "orig"))
            log("signer switch", old, "->", signer, sh(f"pm uninstall {PKG}").strip())
            r = sh(f"pm install -g {apk}", 120).strip(); perms()
            sh(f"am start -W -n {LAUNCH} >/dev/null"); time.sleep(3); sh(f"am force-stop {PKG}")   # create data dir
            tar = f"/data/local/tmp/cmp_{signer}data.tar"
            if "No such" in sh(f"ls {tar}"): tar = f"/data/local/tmp/cmp_{old}data.tar"   # first time: same prefs/DB
            restore_data(tar); perms(); current_signer[0] = signer
        else:
            r = su(f"pm install -r -d {apk}").strip()
        v = installed_ver()
        if ver_ok(build, v): break
        log(f"install {build} attempt {attempt}: {r!r}, installed versionName={v}"); dismiss_play_protect(); time.sleep(2)
    else:
        sh(f"rm -f {apk}"); raise InstallFailed(f"{build}: still versionName={v} after 3 attempts")
    sh(f"rm -f {apk}")
    return f"{r} versionName={v}"


def prepare(build):
    """warm-up usage, save the ART profile, compile speed-profile (same for every build), warm-up again"""
    home(); launcher_start(); nap(2.5); camera(); nap(2); viewer(PHOTO); nap(1.5); player(VIDEO); nap(2); home()
    pid = sh(f"pidof {PKG}").strip()
    if pid: su(f"kill -USR1 {pid}"); time.sleep(2)
    c = sh(f"cmd package compile -m speed-profile -f {PKG}", 300).strip()
    st = sh(f"pm art dump {PKG} | grep -m1 -o 'status=[a-z-]*'").strip()
    sh(f"am force-stop {PKG}")
    home(); launcher_start(); nap(2.5); camera(); nap(2); home()
    return f"{c} {st}"


on, kg, foc = state()
log("start state", on, kg, foc)
if not on or kg or not (foc and foc.startswith(LAUNCHER)):
    sh("input keyevent 3"); time.sleep(1.5); on, kg, foc = state()
    if not on or kg: log("NOT READY"); sys.exit(3)
PHOTO = CAM + "/" + sh(f"ls -t {CAM} | grep -m1 -i '\\.jpg$'").strip()
PHOTO_TILE = os.environ.get("CMP_PHOTO_TILE", "240 608")
TAP_SRC = os.environ.get("CMP_TAP_SRC", os.path.expanduser("~/fg-cmp/cmp_tap.jpg"))   # tapnew: EXIF-less copy of the test photo
VIDEO = os.environ.get("CMP_VIDEO") or (CAM + "/" + sh(f"ls -t {CAM} | grep -m1 -i '\\.mp4$'").strip())
log("photo", PHOTO, "video", VIDEO)
for dev in INPUTS: threading.Thread(target=input_watch, args=(dev,), daemon=True).start()
time.sleep(1)
# Play Protect asks "Send app for a security check?" for sideloaded builds and pm install blocks on the dialog
VERIFY_PREV = sh("settings get global verifier_verify_adb_installs").strip()
sh("settings put global verifier_verify_adb_installs 0")
RES = f"{out}/cmp_results.json"
results = json.load(open(RES)) if os.path.exists(RES) else []
done = {(m["block"], m["scen"], m["run"]) for m in results}
rc = 0
try:
    for bi, (build, k) in enumerate(plan):
        if all((bi, sc, r) in done for sc in SCEN for r in range(k)): continue
        check()
        try: log("install", build, install(build))
        except InstallFailed as e:      # keep a marker row so the table shows "n/a (install fails)" instead of dropping the build
            log("INSTALL FAILED", e); results.append({"build": build, "block": bi, "run": 0, "scen": "install_failed", "err": str(e)})
            json.dump(results, open(RES, "w"), indent=0); continue
        ver = sh(f"dumpsys package {PKG} | grep -m1 versionName").strip()
        log("prepare", build, ver, prepare(build))
        for r in range(k):
            for scen in SCEN:            # interleave scenarios within the block
                if (bi, scen, r) in done: continue
                m = run_one(scen, f"{build}_{bi}_{r}")
                m.update({"build": build, "block": bi, "run": r, "ver": ver})
                results.append(m)
                log(build, bi, scen, r, m.get("total"), m.get("launch"), m.get("gfx", ""))
                json.dump(results, open(RES, "w"), indent=0)
except Abort as e:
    log("aborted:", e); rc = 3
finally:
    for p in watchers:
        try: p.terminate()
        except Exception: pass
    json.dump(results, open(RES, "w"), indent=0)
    if VERIFY_PREV not in ("0", ""): sh(f"settings put global verifier_verify_adb_installs {VERIFY_PREV}")
    if not abort.is_set(): sh("input keyevent 3")
    log("runs saved", len(results), "virtual displays:", sh("dumpsys display | grep -c 'type VIRTUAL'").strip())
sys.exit(rc)
