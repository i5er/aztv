#!/usr/bin/env bash
# Installs android/out/*.apk (debug-signed, from build-local.sh) over the TV's release-signed apps. The
# uninstall + install runs as one script on the TV (nohup), so a dropped Wi-Fi ADB can't leave it without a home.
# The apps' settings go with the uninstall: re-apply them with optimize/saver.sh and optimize/dock.sh after.
set -euo pipefail
cd "$(dirname "$0")/.."
PY=optimize/venv/bin/python
"$PY" -I optimize/tv.py --push-b64 android/out/urtv-screensaver.apk /data/local/tmp/urtv-s.apk
"$PY" -I optimize/tv.py --push-b64 android/out/urtv-home.apk /data/local/tmp/urtv-h.apk
cat > android/out/swap.sh <<'SH'
cd /data/local/tmp
rm -f urtv_swap.log
{
  pm install -r urtv-s.apk || { pm uninstall sa.urtv.screensaver; pm install urtv-s.apk; }
  settings put secure screensaver_enabled 1
  settings put secure screensaver_components sa.urtv.screensaver/.ScreenDream
  settings put secure screensaver_default_component sa.urtv.screensaver/.ScreenDream
  if ! pm install -r urtv-h.apk; then
    pm uninstall sa.urtv.home
    pm install urtv-h.apk || pm enable com.google.android.tvlauncher   # never leave the TV without a home
  fi
  pm grant sa.urtv.home android.permission.ACCESS_FINE_LOCATION
  appops set sa.urtv.home SYSTEM_ALERT_WINDOW allow
  cmd package set-home-activity --user 0 sa.urtv.home/.MainActivity
  am start -a android.intent.action.MAIN -c android.intent.category.HOME
  echo DONE
} > urtv_swap.log 2>&1
SH
"$PY" -I optimize/tv.py --push-b64 android/out/swap.sh /data/local/tmp/urtv-swap.sh
"$PY" -I optimize/tv.py "nohup sh /data/local/tmp/urtv-swap.sh > /dev/null 2>&1 &"
for i in $(seq 1 40); do
  if "$PY" -I optimize/tv.py "cat /data/local/tmp/urtv_swap.log" 2>/dev/null | tee /tmp/urtv_swap.out | grep -q DONE; then
    cat /tmp/urtv_swap.out; exit 0
  fi
  sleep 3
done
echo "!! the swap did not finish; log so far:"; cat /tmp/urtv_swap.out; exit 1
