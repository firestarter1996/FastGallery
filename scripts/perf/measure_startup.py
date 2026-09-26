#!/usr/bin/env python3
"""Cold-start benchmark for FastGallery on a real device (reads the app's FGPerf logcat markers).

Metrics (ms since the app process was forked, i.e. what the user waits for after tapping the icon):
  grid   = first frame that shows album tiles (MainActivity reportFullyDrawn)
  thumbs = per visible album, first frame that shows its cover (most recent file) thumbnail
           -> median over the visible albums, and "all" = last visible cover drawn
  fresh  = with --new-file: a new image is copied into /sdcard/Download (a visible album) before the launch;
           first frame that shows THAT file as the Download album's cover (i.e. the album shows its real newest
           file, not the one remembered from the last session). The copy is deleted after the run.

Each run: force-stop, optionally drop the kernel page cache (root), clear logcat, launch the launcher
activity, wait, read FGPerf lines. Usage:
  measure_startup.py --runs 7 [--drop-caches] [--display 2] [--serial 10.13.13.4:5555] [--label before]

--display launches on a secondary display (e.g. an always-unlocked scrcpy virtual display) so the benchmark
works while the phone itself is locked.
"""
import argparse
import json
import re
import statistics
import subprocess
import time

PKG = "org.fossify.gallery"
LAUNCH = f"{PKG}/.activities.SplashActivity.Green"
LINE = re.compile(r"FGPerf\s*:\s*(\w+) t=(\d+)\s*(.*)$")


def sh(serial, cmd, timeout=60):
    return subprocess.run(["adb", "-s", serial, "shell", cmd], capture_output=True, text=True,
                          timeout=timeout).stdout


def one_run(a, idx):
    sh(a.serial, f"am force-stop {PKG}")
    new_name = None
    if a.new_file:
        new_name = f"fgperf_{int(time.time())}_{idx}.jpg"
        sh(a.serial, f"cp '{a.new_file}' '/sdcard/Download/{new_name}' && touch '/sdcard/Download/{new_name}'")
    if a.drop_caches:
        sh(a.serial, "su -c 'sync; echo 3 > /proc/sys/vm/drop_caches'")
    time.sleep(a.settle)
    sh(a.serial, "logcat -c")
    disp = f"--display {a.display} " if a.display is not None else ""
    am = sh(a.serial, f"am start -W {disp}-a android.intent.action.MAIN "
                      f"-c android.intent.category.LAUNCHER -n {LAUNCH}")
    time.sleep(a.wait)
    log = sh(a.serial, "logcat -d -s FGPerf:I")
    if new_name:
        sh(a.serial, f"rm -f '/sdcard/Download/{new_name}'")
    ev = {}
    thumbs = []
    for ln in log.splitlines():
        m = LINE.search(ln)
        if not m:
            continue
        name, t, rest = m.group(1), int(m.group(2)), m.group(3)
        if name == "thumb_drawn":
            kv = dict(re.findall(r"(\w+)=(\S+)", rest.split(" album=")[0]))
            album = rest.split(" album=", 1)[1] if " album=" in rest else "?"
            cover = album.split(" cover=", 1)[1] if " cover=" in album else ""
            thumbs.append({"t": t, "pos": int(kv.get("pos", -1)), "src": kv.get("src"), "album": album, "cover": cover})
        else:
            ev.setdefault(name, (t, rest))
    total = re.search(r"TotalTime: (\d+)", am)
    res = {"am_total": int(total.group(1)) if total else None,
           "events": {k: v[0] for k, v in ev.items()}}
    g = ev.get("grid_drawn")
    if g:
        res["grid"] = g[0]
        vis = int(re.search(r"visible=(\d+)", g[1]).group(1))
        res["visible"] = vis
        first = {}
        for th in thumbs:
            if 0 <= th["pos"] < vis and th["pos"] not in first:
                first[th["pos"]] = th
        res["thumbs"] = [first[p]["t"] for p in sorted(first)]
        res["thumb_src"] = sorted({first[p]["src"] for p in first})
        res["missing_thumbs"] = vis - len(first)
    if new_name:
        hit = [th["t"] for th in thumbs if th["cover"] == new_name]
        res["fresh"] = min(hit) if hit else None
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="10.13.13.4:5555")
    ap.add_argument("--runs", type=int, default=7)
    ap.add_argument("--drop-caches", action="store_true")
    ap.add_argument("--display", type=int)
    ap.add_argument("--wait", type=float, default=8.0)
    ap.add_argument("--settle", type=float, default=2.0)
    ap.add_argument("--label", default="run")
    ap.add_argument("--new-file", help="device path of a JPEG to copy into /sdcard/Download before each launch")
    ap.add_argument("--json")
    a = ap.parse_args()

    runs = []
    for i in range(a.runs):
        r = one_run(a, i)
        runs.append(r)
        th = r.get("thumbs") or []
        print(f"[{a.label} {i + 1}/{a.runs}] am={r['am_total']} grid={r.get('grid')} visible={r.get('visible')} "
              f"thumb_median={statistics.median(th) if th else None} thumb_all={max(th) if th else None} "
              f"missing={r.get('missing_thumbs')} fresh={r.get('fresh')} src={r.get('thumb_src')} events={r['events']}", flush=True)

    def med(vals):
        vals = [v for v in vals if v is not None]
        return statistics.median(vals) if vals else None

    summary = {
        "label": a.label, "runs": a.runs, "drop_caches": a.drop_caches,
        "am_total_median": med(r["am_total"] for r in runs),
        "grid_median": med(r.get("grid") for r in runs),
        "thumb_per_album_median": med(statistics.median(r["thumbs"]) for r in runs if r.get("thumbs")),
        "thumb_all_visible_median": med(max(r["thumbs"]) for r in runs if r.get("thumbs")),
        "fresh_cover_median": med(r.get("fresh") for r in runs) if a.new_file else None,
        "fresh_cover_missing": sum(1 for r in runs if a.new_file and r.get("fresh") is None),
    }
    print("SUMMARY " + json.dumps(summary))
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"summary": summary, "runs": runs}, f, indent=1)


if __name__ == "__main__":
    main()
