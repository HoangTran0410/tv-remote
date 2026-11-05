#!/bin/bash
# dev/release/cores.sh: stages the libretro cores TV Games downloads on first use (docs/games/games.json "cores").
# The buildbot's nightlies change every day, so a sha256 of them would break: each core is downloaded once here, kept as an
# asset of the GitHub release tag cores-1 (never changes), and its sha256 goes into games.json. Nothing here uploads or edits
# anything: it prints the "cores" entries and the `gh release upload` command to run by hand.
#   dev/release/cores.sh              all cores below, both ABIs      dev/release/cores.sh snes     only one system
# After uploading: paste the entries into docs/games/games.json (set "on": true), run dev/sign/sign_games.py, commit both.
set -e
cd "$(dirname "$0")/../.."
OUT=dev/.cache/cores-1; mkdir -p "$OUT"
BB=https://buildbot.libretro.com/nightly/android/latest
REL=https://github.com/HoangTran0410/tv-remote/releases/download/cores-1
# system | buildbot core | name | licence | extensions | pad layout
CORES='snes|bsnes_mercury_performance|SNES (bsnes-mercury performance)|GPL-3.0|sfc,smc|snes
gba|mgba|Game Boy Advance (mGBA)|MPL-2.0|gba|gba
psx|pcsx_rearmed|PlayStation (PCSX ReARMed)|GPL-2.0|cue,chd,pbp|ps
arcade|fbneo|Arcade (FinalBurn Neo)|LicenseRef-FBNeo-noncommercial|zip|arcade
gb|gambatte|Game Boy (Gambatte)|GPL-2.0|gb,dmg|nes
gbc|gambatte|Game Boy Color (Gambatte)|GPL-2.0|gbc|nes
md|clownmdemu|Mega Drive (ClownMDEmu)|AGPL-3.0|md,gen,smd|md
sms|smsplus|Master System (SMS Plus GX)|GPL-2.0|sms|sms
gg|smsplus|Game Gear (SMS Plus GX)|GPL-2.0|gg|sms
pce|mednafen_pce_fast|PC Engine (Beetle PCE Fast)|GPL-2.0|pce|pce'
# Not used on purpose: Genesis Plus GX and PicoDrive (non-commercial licences, not compatible with a GPL-3 app).
ENTRIES=""
while IFS='|' read -r id core name lic exts lay; do
  [ -n "$1" ] && [ "$1" != "$id" ] && continue
  abis=""
  for abi in arm64-v8a armeabi-v7a; do
    f="${core}_libretro_android-$abi.so.zip"
    if [ ! -s "$OUT/$f" ]; then                                    # a staged zip is kept (its sha256 may be uploaded already); gambatte / smsplus serve two systems
      curl -fsSL "$BB/$abi/${core}_libretro_android.so.zip" -o "$OUT/$f" || { echo "x $core $abi: not on the buildbot" >&2; continue; }
    fi
    unzip -p "$OUT/$f" >/dev/null                                   # a zip that does not open is not staged
    sum=$(shasum -a 256 "$OUT/$f" | cut -d' ' -f1); size=$(stat -f%z "$OUT/$f" 2>/dev/null || stat -c%s "$OUT/$f")
    abis="$abis\"$abi\":{\"url\":\"$REL/$f\",\"sha256\":\"$sum\",\"size\":$size},"
  done
  ENTRIES="$ENTRIES\"$id\":{\"name\":\"$name\",\"on\":false,\"abi\":{${abis%,}},\"license\":\"$lic\",\"exts\":[\"${exts//,/\",\"}\"],\"layout\":\"$lay\",\"file\":\"${core}_libretro_android.so\"},"
done <<< "$CORES"
echo "--- \"cores\" entries for docs/games/games.json:"
echo "{${ENTRIES%,}}" | python3 -m json.tool
echo "--- upload (once; creates the release tag cores-1, never the latest: releases/latest/download/*.apk must stay the apps):"
echo "gh release create cores-1 --latest=false --title 'TV Games cores' --notes 'libretro cores TV Games downloads on first use (dev/release/cores.sh)' $OUT/*.zip"
echo "    (tag exists already: gh release upload cores-1 $OUT/*.zip --clobber)"
