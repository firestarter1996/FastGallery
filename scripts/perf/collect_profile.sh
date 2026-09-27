#!/bin/bash
# Collect a startup/usage ART profile for baseline-prof.txt from a FRESH install of a release build (uncompiled, so
# every executed method is recorded). Real screen (display 0), phone unlocked on the home screen.
# Usage: collect_profile.sh <serial> <apk> <out.prof.txt>   then: deobf_profile.py <that build's mapping.txt> <out> baseline-prof.txt
set -u
S=$1; APK=$2; OUT=$3; PKG=org.fossify.gallery; CAM=/storage/emulated/0/DCIM/Camera
a() { adb -s "$S" shell "$@"; }
adb -s "$S" push "$APK" /data/local/tmp/fg_profile.apk >/dev/null
a "su -c 'pm install -r -d /data/local/tmp/fg_profile.apk'"      # an update wipes the app's ART profile
a "cmd package compile -m verify -f $PKG"                          # interpreter/JIT: all executed code gets recorded
PHOTO=$CAM/$(a "ls -t $CAM | grep -m1 -i '\.jpg$'" | tr -d '\r')
VIDEO=$CAM/$(a "ls -t $CAM | grep -m1 -i '\.mp4$'" | tr -d '\r')
L="-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.activities.SplashActivity.Green"
for i in 1 2 3; do
  a "am force-stop $PKG; input keyevent 3"; sleep 1
  a "am start -W --display 0 $L" >/dev/null; sleep 3                # cold launch, album grid
  a "input -d 0 swipe 700 2400 700 900 150"; sleep 1.5               # scroll the albums
  a "input keyevent 3"; sleep 1; a "am start -W --display 0 $L" >/dev/null; sleep 2   # hot launch
  a "su -c 'am start -W --display 0 -n $PKG/.activities.MediaActivity --es directory $CAM'" >/dev/null; sleep 3
  a "input -d 0 swipe 700 2400 700 900 150"; sleep 1.5               # scroll Camera
  a "su -c 'am start -W --display 0 -n $PKG/.activities.ViewPagerActivity --es path $PHOTO --ez is_from_gallery true --ez show_all false'" >/dev/null; sleep 2.5
  a "input -d 0 swipe 1250 1560 150 1560 120"; sleep 2; a "input -d 0 swipe 1250 1560 150 1560 120"; sleep 2
  a "input keyevent 4"; sleep 1
  a "su -c 'am start -W --display 0 -n $PKG/.activities.VideoPlayerActivity -d file://$VIDEO -t video/mp4'" >/dev/null; sleep 4
  a "input keyevent 4"; sleep 1; a "input keyevent 4"; sleep 1
  a "am start -W --display 0 -a android.intent.action.GET_CONTENT -t 'image/*' -c android.intent.category.OPENABLE -n $PKG/.activities.MainActivity" >/dev/null; sleep 2
  a "input keyevent 4"; sleep 1; a "input keyevent 3"; sleep 1
done
sleep 20                                                           # let the profile saver run, then force a save
a "su -c 'kill -USR1 \$(pidof $PKG)'"; sleep 3
a "cmd package dump-profiles --dump-classes-and-methods $PKG" >/dev/null
a "su -c 'cat /data/misc/profman/$PKG-primary.prof.txt'" > "$OUT"
wc -l "$OUT"; grep -c "org/fossify/gallery/activities/MainActivity" "$OUT"
a "input keyevent 3"; a "rm -f /data/local/tmp/fg_profile.apk"
