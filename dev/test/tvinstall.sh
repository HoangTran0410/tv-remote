#!/bin/bash
# Installs APKs on a TV the way a person expects to find it afterwards.
#   dev/test/tvinstall.sh <serial> <apk>...     e.g. dev/test/tvinstall.sh 192.168.100.154:5555 app/tvhome/build/tv-home.apk
# Why: `adb install -r` of TV Home kills the running launcher and the TV falls into whatever app is under it (the FPT box
# landed in TV Channels, 2026-10-24). When TV Home was the home screen before, it is set again and HOME pressed in the same go.
set -u
[ $# -ge 2 ] || { echo "usage: dev/test/tvinstall.sh <serial> <apk>..."; exit 2; }
TV="$1"; shift
ADB="${ADB:-adb}"
HOME_ACT="dev.tvremote.home/.HomeActivity"
was_home=$("$ADB" -s "$TV" shell cmd package resolve-activity --brief --query-flags 0x10000 -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | tail -1 | tr -d '\r')
for apk in "$@"; do
  "$ADB" -s "$TV" install -r "$apk" | tail -1 | sed "s|^|$(basename "$apk") → $TV: |"
done
if [ "$was_home" = "$HOME_ACT" ] || [ "$was_home" = "dev.tvremote.home/dev.tvremote.home.HomeActivity" ]; then
  "$ADB" -s "$TV" shell cmd package set-home-activity "$HOME_ACT" >/dev/null
  "$ADB" -s "$TV" shell input keyevent HOME
  sleep 1
  "$ADB" -s "$TV" shell dumpsys window | grep -m1 mCurrentFocus | sed 's/^ */in front: /'
fi
