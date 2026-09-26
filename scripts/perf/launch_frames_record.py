#!/usr/bin/env python3
# Usage: launch_frames_record.py <outdir> <label> <runs>; then (in <outdir>) launch_frames_analyze.py <label>
"""scrcpy virtual display + recorder: N cold launches of FastGallery on the virtual display with Settings behind;
saves raw h264 + per-frame device pts + host marks."""
import socket, struct, subprocess, sys, time, json, threading
S = "10.13.13.4:5555"; SCID = "5e6f7a8b"; PORT = 27201
out = sys.argv[1]; label = sys.argv[2]; runs = int(sys.argv[3])
PKG = "org.fossify.gallery"; LAUNCH = f"{PKG}/.activities.SplashActivity.Green"
def sh(c, t=60): return subprocess.run(["adb", "-s", S, "shell", c], capture_output=True, text=True, timeout=t).stdout
subprocess.run(["adb", "-s", S, "push", "/mnt/c/Users/jonal/Downloads/Downloads/Project Files/Software/Android/Android Programs/scrcpy-win64-v3.2/scrcpy-server", "/data/local/tmp/scrcpy-server.jar"], capture_output=True)
srv = subprocess.Popen(["adb", "-s", S, "shell", f"CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 3.2 scid={SCID} new_display=1344x2992/480 tunnel_forward=true control=false audio=false log_level=info video_bit_rate=16000000"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
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
rx(1); rx(64); rx(12)
disp = None
for _ in range(50):
    line = srv.stdout.readline()
    if "New display" in line:
        disp = int(line.split("id=")[1].split(")")[0]); break
print("display", disp, flush=True)
raw = open(f"{out}/{label}.h264", "wb"); meta = []
stop = False
def reader():
    while not stop:
        try: hdr = rx(12)
        except Exception: return
        ptsf, size = struct.unpack(">QI", hdr)
        data = rx(size); cfg = bool(ptsf >> 63)
        raw.write(data)
        if not cfg: meta.append({"pts": (ptsf & ((1 << 62) - 1)) / 1000.0, "host": time.monotonic() * 1000})
threading.Thread(target=reader, daemon=True).start()
marks = []
for i in range(runs):
    sh(f"am force-stop {PKG}")
    sh(f"am start --display {disp} -a android.settings.SETTINGS")
    time.sleep(3)
    sh("logcat -c")
    t = time.monotonic() * 1000
    am = sh(f"am start -W --display {disp} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {LAUNCH}")
    time.sleep(4)
    log = sh("logcat -d -s FGPerf:I ActivityTaskManager:I | grep -E 'grid_drawn|Displayed'")
    tot = [l.strip() for l in am.splitlines() if "Time" in l]
    st = [l for l in sh("logcat -d -v monotonic -s ActivityTaskManager:I").splitlines() if "START u0" in l and PKG in l]
    t0 = float(st[0].split()[0]) * 1000 if st else None
    marks.append({"host": t, "am": tot, "log": log.strip().splitlines(), "start_mono": t0})
    print(i, tot, [l.split(": ", 1)[-1] for l in log.strip().splitlines()], flush=True)
    sh(f"am force-stop {PKG}")
time.sleep(1); stop = True; raw.flush()
json.dump({"frames": meta, "marks": marks}, open(f"{out}/{label}.json", "w"), indent=0)
sock.close(); srv.terminate(); sh("for p in $(pgrep -f genymobile); do kill $p; done")
subprocess.run(["adb", "-s", S, "forward", "--remove", f"tcp:{PORT}"])
print("frames", len(meta))
