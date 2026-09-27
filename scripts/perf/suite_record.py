#!/usr/bin/env python3
# Usage: suite_record.py <outdir> <label> <runs> <scenario>; then suite_analyze.py <label> in <outdir>
"""Regression suite on a hidden scrcpy virtual display. Never touches display 0.

Phone safety (the owner uses this phone): nothing starts while the real screen is awake, and a watcher thread polls
the screen every ~1.5 s during the recording; the moment it wakes, the scrcpy server is killed (which removes the
virtual display, so it can never hold the phone's global focus), the completed runs are saved and the script exits 3.
The caller waits for the screen to go off and records the remaining runs under a new label.
Exit codes: 0 done, 3 paused (partial json saved; "runs_done" in it), 4 virtual display could not be verified gone.

scenarios:
  grid       cold launcher start -> album grid (+ PSS / heaps 4 s and 15 s after launch)
  albumwarm  album grid running, Camera opened on top of it (MediaActivity)
  albumcold  cold start straight into Camera (MediaActivity)
  photo      Camera open, newest photo opened fullscreen, then one swipe to the next photo (scrcpy touch injection)
  video      Camera open, newest video opened in the in-app viewer
"""
import json, socket, struct, subprocess, sys, threading, time
S = "10.13.13.4:5555"; SCID = "5e6f7a8c"; PORT = 27202; W, H = 1344, 2992
out, label, runs, scen = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
PKG = "org.fossify.gallery"; LAUNCH = f"{PKG}/.activities.SplashActivity.Green"
CAM = "/storage/emulated/0/DCIM/Camera"
SERVER = "/mnt/c/Users/jonal/Downloads/Downloads/Project Files/Software/Android/Android Programs/scrcpy-win64-v3.2/scrcpy-server"


def sh(c, t=60):
    try:
        return subprocess.run(["adb", "-s", S, "shell", c], capture_output=True, text=True, timeout=t).stdout
    except subprocess.TimeoutExpired:
        return ""


# The REAL screen's state (display 0). Not the global mWakefulness: the virtual display is its own display group and
# Android reports the device "Awake" (WAKE_REASON_DISPLAY_GROUP_ADDED) for as long as it exists, with display 0 still off.
D0 = "dumpsys display | awk '/Display Id=0$/{f=1;next} f&&/Display State=/{print $2;exit}'"
def awake():
    st = sh(D0, 15).strip()
    return st != "State=OFF" and st != "State=DOZE" and st != "State=DOZE_SUSPEND"   # unknown/unreachable counts as awake
def virtual_count(): return int((sh("dumpsys display | grep -c 'type VIRTUAL'", 20).strip() or "-1"))


if awake():
    print("PAUSED screen awake before start", flush=True); sys.exit(3)

newest = lambda ext: CAM + "/" + sh(f"ls -t {CAM} | grep -m1 -i '\\.{ext}$'").strip()
PHOTO, VIDEO = newest("jpg"), newest("mp4")
subprocess.run(["adb", "-s", S, "push", SERVER, "/data/local/tmp/scrcpy-server.jar"], capture_output=True)
srv = subprocess.Popen(["adb", "-s", S, "shell", f"CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 3.2 scid={SCID} new_display={W}x{H}/480 tunnel_forward=true control=true audio=false log_level=info video_bit_rate=16000000"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
paused = threading.Event(); stop = threading.Event(); torn = threading.Lock(); torn_down = [False]
sock = ctl = None


def teardown():
    with torn:
        if torn_down[0]: return
        torn_down[0] = True
    for s in (sock, ctl):
        try: s and s.close()
        except Exception: pass
    try: srv.terminate()
    except Exception: pass
    for _ in range(5):
        sh("for p in $(pgrep -f genymobile); do kill $p; done", 20)
        time.sleep(1)
        if virtual_count() == 0: break
    subprocess.run(["adb", "-s", S, "forward", "--remove", f"tcp:{PORT}"], capture_output=True)


def watcher():
    while not stop.is_set():
        if awake():
            print("screen woke: removing the virtual display now", flush=True)
            paused.set(); teardown(); return
        stop.wait(1.5)


threading.Thread(target=watcher, daemon=True).start()
marks = []; meta = []; disp = None
try:
    time.sleep(2.5)
    subprocess.run(["adb", "-s", S, "forward", f"tcp:{PORT}", f"localabstract:scrcpy_{SCID}"], check=True)
    sock = socket.create_connection(("127.0.0.1", PORT))

    def rx(n):
        b = b""
        while len(b) < n:
            d = sock.recv(n - len(b))
            if not d: raise EOFError
            b += d
        return b
    rx(1)
    ctl = socket.create_connection(("127.0.0.1", PORT))
    rx(64); rx(12)

    def drain():
        while True:
            try:
                if not ctl.recv(4096): return
            except Exception: return
    threading.Thread(target=drain, daemon=True).start()

    def touch(action, x, y):  # 0 down, 1 up, 2 move
        ctl.sendall(struct.pack(">BBqiiHHHII", 2, action, 0, int(x), int(y), W, H, 0xffff if action != 1 else 0, 0, 0))

    def swipe_left():
        if paused.is_set(): raise InterruptedError
        y = H // 2; x0, x1 = int(W * 0.85), int(W * 0.15)
        touch(0, x0, y)
        for k in range(1, 9):
            time.sleep(0.012); touch(2, x0 + (x1 - x0) * k / 8, y)
        touch(1, x1, y)

    for _ in range(50):
        line = srv.stdout.readline()
        if "New display" in line:
            disp = int(line.split("id=")[1].split(")")[0]); break
    print("display", disp, PHOTO, VIDEO, flush=True)
    raw = open(f"{out}/{label}.h264", "wb")

    def reader():
        while not paused.is_set() and not stop.is_set():
            try: hdr = rx(12)
            except Exception: return
            ptsf, size = struct.unpack(">QI", hdr)
            try: data = rx(size)
            except Exception: return
            raw.write(data)
            if not (ptsf >> 63): meta.append({"pts": (ptsf & ((1 << 62) - 1)) / 1000.0})
    threading.Thread(target=reader, daemon=True).start()

    def nap(s):
        if paused.wait(s): raise InterruptedError

    def start_mono(act):
        st = [l for l in sh("logcat -d -v monotonic -s ActivityTaskManager:I").splitlines() if "START u0" in l and act in l]
        return float(st[-1].split()[0]) * 1000 if st else None

    def fgperf():
        return [l.split("FGPerf", 1)[1].lstrip(" :") for l in sh("logcat -d -s FGPerf:I").splitlines() if "FGPerf" in l and
                any(k in l for k in ("scan_cache", "media_snapshot", "media_thumbs", "grid_drawn"))]

    def mem():
        txt = sh(f"dumpsys meminfo {PKG}")
        r = {"pss_kb": int(next((l.split()[2] for l in txt.splitlines() if "TOTAL PSS:" in l), "0"))}
        for l in txt.splitlines():
            for name in ("Java Heap:", "Native Heap:", "Graphics:", "Code:"):
                if l.strip().startswith(name): r[name[:-1].replace(" ", "_").lower() + "_kb"] = int(l.split(":")[1].split()[0])
        return r

    def dsh(c):   # every command aimed at the virtual display: never sent once the screen woke (the display is gone)
        if paused.is_set(): raise InterruptedError
        return sh(c)

    def launcher(): dsh(f"am start -W --display {disp} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {LAUNCH}")
    def camera(): dsh(f"su -c 'am start -W --display {disp} -n {PKG}/.activities.MediaActivity --es directory \"{CAM}\"'")

    for i in range(runs):
        if paused.is_set(): raise InterruptedError
        sh(f"am force-stop {PKG}")
        dsh(f"am start --display {disp} -a android.settings.SETTINGS")
        nap(3)
        m = {}
        if scen == "grid":
            sh("logcat -c"); launcher(); nap(4)
            m["start_mono"] = start_mono(PKG); m.update(mem()); nap(11); m["after15"] = mem()
        elif scen == "albumwarm":
            launcher(); nap(4)
            sh("logcat -c"); camera(); nap(4)
            m["start_mono"] = start_mono("MediaActivity")
        elif scen == "albumcold":
            sh("logcat -c"); camera(); nap(4)
            m["start_mono"] = start_mono("MediaActivity")
        else:
            camera(); nap(3)
            sh("logcat -c")
            target = PHOTO if scen == "photo" else VIDEO
            dsh(f"su -c 'am start -W --display {disp} -n {PKG}/.activities.ViewPagerActivity --es path \"{target}\" --ez is_from_gallery true --ez show_all false'")
            nap(3)
            m["start_mono"] = start_mono("ViewPagerActivity")
            if scen == "photo":
                swipe_left(); nap(3)
        m["fgperf"] = fgperf()
        if scen == "photo":   # viewer markers with device-monotonic time (same clock as start_mono)
            m["perf_mono"] = [l for l in sh("logcat -d -v monotonic -s FGPerf:I").splitlines() if ("photo_" in l or "vp_" in l)]
        if paused.is_set(): raise InterruptedError   # a run cut short by the watcher is not kept
        marks.append(m)
        print(i, {k: v for k, v in m.items() if k != "fgperf"}, [x for x in m["fgperf"] if "scan_cache" in x and "DCIM/Camera" in x][:2], flush=True)
        sh(f"am force-stop {PKG}")
except (InterruptedError, EOFError, OSError) as e:
    if not paused.is_set(): print("error", repr(e), flush=True)
finally:
    stop.set()
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    teardown()
    json.dump({"frames": meta, "marks": marks, "scenario": scen, "runs_done": len(marks)}, open(f"{out}/{label}.json", "w"), indent=0)
    vc = virtual_count()
    print("frames", len(meta), "runs_done", len(marks), "virtual_displays", vc, flush=True)
    if vc != 0: sys.exit(4)
    if paused.is_set() or len(marks) < runs: sys.exit(3)
