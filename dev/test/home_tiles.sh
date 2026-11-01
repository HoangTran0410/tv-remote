#!/bin/bash
# Checks TV Home's real grid and every tile's menu against the one table (Tiles.kt), on a TV or the emulator:
#   dev/test/home_tiles.sh <serial> [--json]      e.g. dev/test/home_tiles.sh 192.168.100.154:5555
# TV Home answers HomeReceiver "tiles" (HomeActivity.tilesCheck): the grid as drawn, the table's grid, each tile's kind, menu
# actions and rows, and what is wrong (a lost tile, a tile twice, a menu row that should or should not be there). Exit 1 when
# anything is wrong. Run it after a change to tiles, folders, pins or the tile menu (dev/flows/home-tiles.md); TilesTest covers
# random edits, this covers what only a real TV has (installed apps, Android's pins, live widgets that answer).
set -u
[ $# -ge 1 ] || { echo "usage: dev/test/home_tiles.sh <serial> [--json]"; exit 2; }
TV="$1"; ADB="${ADB:-adb}"
"$ADB" -s "$TV" shell input keyevent HOME                            # the check reads the Home on screen
sleep 1
out=$("$ADB" -s "$TV" shell am broadcast --include-stopped-packages -n dev.tvremote.home/.HomeReceiver --ez tiles true 2>&1)
b64=$(printf '%s' "$out" | sed -n 's/.*data="\([A-Za-z0-9+/=]*\)".*/\1/p' | head -1)
[ -n "$b64" ] || { echo "no answer (TV Home too old, or not the home screen?):"; echo "$out" | tail -2; exit 2; }
printf '%s' "$b64" | base64 -d | python3 -c '
import json, sys
d = json.load(sys.stdin)
if "--json" in sys.argv: print(json.dumps(d, ensure_ascii=False, indent=1)); sys.exit(0)
if "error" in d: print("error:", d["error"]); sys.exit(2)
kinds = {}
for t in d["tiles"]: kinds[t["kind"]] = kinds.get(t["kind"], 0) + 1
print("grid %d tiles (%s), menus checked %d" % (len(d["grid"]), ", ".join("%s %d" % kv for kv in sorted(kinds.items())), len(d["tiles"])))
for t in d["tiles"]: print("  %-5s %-48s %s" % (t["kind"], t["key"][:48], " ".join(a.lower() for a in t["acts"])))
bad = d["bad"]
print("OK: nothing wrong" if not bad else "WRONG (%d):" % len(bad))
for b in bad: print("  - " + b)
sys.exit(1 if bad else 0)
' "$@"
