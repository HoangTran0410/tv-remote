#!/bin/bash
# Smoke test of TV Home (every Customize page) and TV Setup (main screens) on the smoke emulator, by uiautomator dump + D-pad keys only.
#   dev/test/home_smoke/home_smoke.sh [outdir] [--lang en|vi] [--only home|setup|doors]     (env: SERIAL=emulator-5610, BUILD=1 builds + installs the debug APKs first)
# Needs: adb, python3. Never touches another device: the serial must be the smoke emulator (smoke.py refuses others).
# Output: <outdir>/results-<lang>.json + numbered PNGs. Exit 1 when a step failed (crash, BACK went to the wrong screen, focus lost, untranslated text).
# Run it twice for both languages: `home_smoke.sh d --lang en; home_smoke.sh d --lang vi`. The vi run changes TV Home's own language setting and restores it.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
SERIAL="${SERIAL:-emulator-5610}"
OUT="/private/tmp/claude-501/-Users-hoangtran-Desktop-Github/818eb7ba-5b31-444e-81f2-e599faff06df/scratchpad/smoke/"
if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then OUT="$1"; shift; fi
adb -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "$SERIAL is not connected"; exit 2; }

if [ "${BUILD:-0}" = 1 ]; then
  (cd "$ROOT/app/gradle" && ./gradlew -q :tvhome:assembleDebug :tvsetup:assembleDebug)
  adb -s "$SERIAL" install -r "$ROOT/app/tvhome/build/outputs/apk/debug/tvhome-debug.apk" | tail -1
  adb -s "$SERIAL" install -r "$ROOT/app/tvsetup/build/outputs/apk/debug/tvsetup-debug.apk" | tail -1
  adb -s "$SERIAL" shell cmd package set-home-activity dev.tvremote.home/.HomeActivity >/dev/null
fi
# TV Home must be the HOME: Google TV's own launcher outranks it on the HOME intent, so it is switched off on this emulator
# (undo: adb -s emulator-5610 shell pm enable com.google.android.apps.tv.launcherx)
adb -s "$SERIAL" shell pm disable-user --user 0 com.google.android.apps.tv.launcherx >/dev/null 2>&1 || true
adb -s "$SERIAL" shell am start -c android.intent.category.HOME -a android.intent.action.MAIN >/dev/null
sleep 2
exec python3 -I "$HERE/smoke.py" "$OUT" --serial "$SERIAL" "$@"
