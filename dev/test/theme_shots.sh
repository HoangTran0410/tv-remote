#!/bin/bash
# dev/test/theme_shots.sh <serial> [out dir] [id ...]: applies each ready-made TV Home theme of the repo's docs/home/themes.json (signed)
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
# and the repo's signed list is put in as TV Home's kept copy, so an edit is seen before it is pushed, applied as a person applies it
# (setting a theme's fields by hand merged its widget options into the last theme's: Easy's clock came out light)
for f in themes.json themes.json.sig; do adb -s "$S" push "$ROOT/docs/home/$f" /data/local/tmp/$f >/dev/null; adb -s "$S" shell "run-as dev.tvremote.home sh -c 'cp /data/local/tmp/$f files/$f; rm -f files/themes.json.ok'"; done
adb -s "$S" shell "run-as dev.tvremote.home sh -c 'grep -q tip_menu_done shared_prefs/home.xml || sed -i \"s#</map>#<boolean name=\\\"tip_menu_done\\\" value=\\\"true\\\" />\\n</map>#\" shared_prefs/home.xml'" 2>/dev/null && adb -s "$S" shell am force-stop dev.tvremote.home
for id in "${IDS[@]}"; do
  set_home "{\"theme\":{\"apply\":\"$id\"}}"
  adb -s "$S" shell am start -W -n dev.tvremote.home/.HomeActivity >/dev/null; sleep "${WAIT:-8}"          # web walls fetch their picture; a video wall its first frames
  adb -s "$S" exec-out screencap -p > "$OUT/$id.png"
  if [ -n "$WEBP" ]; then
    cwebp -quiet -q 72 -resize 480 270 "$OUT/$id.png" -o "$ROOT/docs/img/themes/$id.webp"
    cwebp -quiet -q 75 -resize 1280 720 "$OUT/$id.png" -o "$ROOT/docs/img/themes/$id-big.webp"
  fi
  echo "$id -> $OUT/$id.png"
done
if [ -n "$WEBP" ]; then                                             # browsers keep a picture a day by its URL: new pictures, a new ?v= everywhere they are named
  V=$(date +%m%d%H%M)
  sed -i '' -E "s#(img/themes/[a-z0-9]+(-big)?\.webp)(\?v=[0-9]+)?\"#\1?v=$V\"#g" "$ROOT/docs/index.html"
  sed -i '' -E "s#(\\.webp)(\?v=[0-9]+)?\"; \}#\1?v=$V\"; }#g" "$ROOT/docs/js/themes.js"
  sed -i '' -E "s#js/themes\.js(\?v=[0-9]+)?\"#js/themes.js?v=$V\"#" "$ROOT/docs/themes.html" "$ROOT/docs/index.html"
  echo "previews named ?v=$V in docs/index.html, docs/js/themes.js, docs/themes.html"
fi
