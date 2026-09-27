#!/system/bin/sh
# FastGallery AOT compile after every install/update (KernelSU boot script, /data/adb/service.d/fastgallery-compile.sh).
# Why: an APK installed by Obtainium/adb runs uncompiled ("verify": interpreter + JIT) until Android's idle maintenance
# job compiles it, which only happens at night while charging. This checks every 10 min (one small `pm art dump`) and,
# when the gallery is not compiled, compiles it right away with FILTER (the app's own profile + its baseline profile).
# UNDO: adb shell "su -c 'rm -f /data/adb/service.d/fastgallery-compile.sh'" then reboot (or kill the loop:
#       su -c 'kill $(cat /data/local/tmp/fastgallery-compile.pid)'). Android's own nightly dexopt then applies again.
PKG=org.fossify.gallery
FILTER=speed-profile
LOG=/data/local/tmp/fastgallery-compile.log
echo $$ > /data/local/tmp/fastgallery-compile.pid
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done
sleep 120
while true; do
  st=$(pm art dump $PKG 2>/dev/null | grep -m1 -o 'status=[a-z-]*')
  case "$st" in
    status=verify|status=run-from-apk|status=extract|status=quicken)
      echo "$(date '+%F %T') $st -> compile $FILTER" >> $LOG
      pm compile -m $FILTER -f $PKG >> $LOG 2>&1
      echo "$(date '+%F %T') now $(pm art dump $PKG 2>/dev/null | grep -m1 -o 'status=[a-z-]*')" >> $LOG ;;
  esac
  sleep 600
done
