#!/system/bin/sh
# FastGallery keep-alive (KernelSU boot script, /data/adb/service.d/fastgallery-keepalive.sh, chmod 755).
# After boot: makes sure org.fossify.gallery is Unrestricted (battery-optimisation allowlist + RUN_ANY_IN_BACKGROUND),
# then starts its idle KeepAliveService so the gallery process exists (and launches are warm) from the first open.
# The app itself restarts the service on every launch while it is allowlisted; this script only covers boot.
# UNDO (one command, from the laptop):
#   adb shell "su -c 'rm -f /data/adb/service.d/fastgallery-keepalive.sh; touch /data/data/org.fossify.gallery/files/no_keep_alive; am force-stop org.fossify.gallery'"
# (files/no_keep_alive makes the app skip the service; delete that file to turn it back on.)
PKG=org.fossify.gallery
LOG=/data/local/tmp/fastgallery-keepalive.log
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done
sleep 45
{
  echo "$(date '+%F %T') boot"
  dumpsys deviceidle whitelist +$PKG
  cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow
  am start-service --user 0 -n $PKG/.services.KeepAliveService
  sleep 5
  echo "pid=$(pidof $PKG)"
} >> $LOG 2>&1
