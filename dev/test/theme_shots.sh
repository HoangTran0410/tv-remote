#!/bin/bash
# dev/test/theme_shots.sh <serial> [out dir] [id ...]: applies each ready-made TV Home theme (docs/home/themes.json, the TV's own copy)
# through HomeReceiver, waits for the wall, and takes a 1920x1080 screenshot per theme: <out>/<id>.png. No id = every theme in the list.
# The page's previews (docs/img/themes/<id>.webp 480x270, <id>-big.webp 1280x720) come from these: WEBP=1 writes them too.
# TV Home is started by name: on the Google TV emulator the HOME key reaches Google's launcher even with TV Home as the home role.
# Leaves the last theme on (Customize > Themes > Before brings the earlier look back).
set -e
S="${1:?serial}"; OUT="${2:-${TMPDIR:-/tmp}/theme_shots}"; shift 2 2>/dev/null || shift $#
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IDS=("$@"); [ ${#IDS[@]} -eq 0 ] && IDS=($(python3 -c "import json;print(' '.join(t['id'] for t in json.load(open('$ROOT/docs/home/themes.json'))['themes']))"))
mkdir -p "$OUT"
set_home() { adb -s "$S" shell am broadcast -n dev.tvremote.home/.HomeReceiver --es set "$(printf '%s' "$1" | base64 | tr -d '\n')" >/dev/null; }
# the one-time "Hold OK" tip would be in every shot: marked seen (debug builds only: run-as)
adb -s "$S" shell "run-as dev.tvremote.home sh -c 'grep -q tip_menu_done shared_prefs/home.xml || sed -i \"s#</map>#<boolean name=\\\"tip_menu_done\\\" value=\\\"true\\\" />\\n</map>#\" shared_prefs/home.xml'" 2>/dev/null && adb -s "$S" shell am force-stop dev.tvremote.home
for id in "${IDS[@]}"; do
  # the repo's look (docs/home/themes.json), not the TV's copy of the list, so an edit is seen before it is pushed
  set_home "$(python3 -c "import json,sys;print(json.dumps([t for t in json.load(open('$ROOT/docs/home/themes.json'))['themes'] if t['id']==sys.argv[1]][0]['conf']))" "$id")"
  adb -s "$S" shell am start -W -n dev.tvremote.home/.HomeActivity >/dev/null; sleep "${WAIT:-8}"          # web walls fetch their picture; a video wall its first frames
  adb -s "$S" exec-out screencap -p > "$OUT/$id.png"
  if [ -n "$WEBP" ]; then
    cwebp -quiet -q 72 -resize 480 270 "$OUT/$id.png" -o "$ROOT/docs/img/themes/$id.webp"
    cwebp -quiet -q 75 -resize 1280 720 "$OUT/$id.png" -o "$ROOT/docs/img/themes/$id-big.webp"
  fi
  echo "$id -> $OUT/$id.png"
done
