#!/bin/bash
# Publishes a GitHub release. Its version numbers, each bumped only when its own part changed:
#   VERSION               the release itself (the tag) and the computer app: bump it for every release (the computer app
#                         is only built again when app/desktop or app/shared changed; versions.json "web" says which it is)
#   app/android/VERSION   the phone app            app/tvsetup/VERSION   the TV app       app/tvhome/VERSION   TV Home (the launcher)
#   app/tvchannels/VERSION   TV Channels (the iptv-org player)     app/tvgames/VERSION   TV Games (retro + web games, phone pads)
#   app/tvapps/VERSION     TV Apps (free open-source TV apps)
# Both APKs are attached to every release together with versions.json (the computer apps follow, from GitHub Actions); each app reads its own number from that file,
# so an app that did not change is not offered a pointless update.
#
#   1. write the notes under "## Unreleased" in CHANGELOG.md, commit        2. dev/release/release.sh
# It bumps the numbers itself: VERSION when it is still the last tag's, an app's when that app (or app/shared) changed since
# the last tag and its number did not (SKIP_CHECK=1: the apps' numbers are left alone). Which part goes up:
#   patch (default)   x.y.z+1    fixes and small changes
#   BUMP=minor        x.y+1.0    a release with new features
#   BUMP=major        x+1.0.0    only when the user says so: 0.x is the alpha, 1.0.0 the first release for everyone
# The numbers restarted at 0.1.0 in October 2026 (they had run up to 2.24.2 before anyone used the apps).
# "## Unreleased" becomes "## <VERSION> - <date>" with the line of the numbers; then it commits that and pushes.
# Run again after a failure: nothing is bumped twice (VERSION is no longer the last tag's, and its section is there).
# Needs the gh CLI logged in, and every app's signing.jks the earlier releases were signed with (or updates will not install).
# Last it lists every file's SHA-256 in SHA256SUMS and signs that with the release key (dev/release/Sign.java; RELEASE_KEY, by default
# ~/.tv-remote-release/ed25519.pem): the apps install nothing a release does not list there, signed by a key in Release.KEYS.
set -e
cd "$(dirname "$0")/../.."
KEY=${RELEASE_KEY:-$HOME/.tv-remote-release/ed25519.pem}
[ -f "$KEY" ] || { echo "release key missing ($KEY): without SHA256SUMS.sig no app takes the update"; exit 1; }
grep -q "$(java dev/release/Sign.java pub "$KEY")" app/shared/src/sign/kotlin/dev/tvremote/update/Release.kt || { echo "$KEY is not one of Release.KEYS"; exit 1; }
[ -f app/android/signing.jks ] && [ -f app/tvsetup/signing.jks ] && [ -f app/tvhome/signing.jks ] && [ -f app/tvchannels/signing.jks ] && [ -f app/tvgames/signing.jks ] && [ -f app/tvapps/signing.jks ] || { echo "signing.jks missing: an APK signed with a new key cannot update the installed apps"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit your changes first"; exit 1; }
# the user guide: a topic whose app files changed since the release it was checked against may now be wrong
node --test app/shared/tests/ app/tvgames/tests/ >/dev/null 2>&1 || { echo "node tests fail: node --test app/shared/tests/ app/tvgames/tests/"; exit 1; }  # what ships must parse and pass
node dev/test/slowload.js >/dev/null 2>&1 || { echo "the page throws while a slow TV loads it: node dev/test/slowload.js"; exit 1; }   # census 91edf904 and kin
(cd app/gradle && ./gradlew -q :shared:testDebugUnitTest >/dev/null 2>&1) || { echo "Kotlin tests fail (speed budgets included): cd app/gradle && ./gradlew :shared:testDebugUnitTest"; exit 1; }   # not in pre-push: Gradle is ~1 min; a release is where a slowdown must not ship
LINTS=":shared:lintDebug :android:lintDebug :tvsetup:lintDebug :tvkeys:lintDebug :tvhome:lintDebug :tvchannels:lintDebug :tvgames:lintDebug :tvapps:lintDebug :tvvoice:lintDebug"
(cd app/gradle && ./gradlew -q $LINTS >/dev/null 2>&1) || { echo "a call newer than Android 5 unguarded (lint NewApi): cd app/gradle && ./gradlew $LINTS"; exit 1; }   # Android 5-6 boxes crash on it (census 8f22ae15)
if [ -n "$(dev/release/guide_stale.py -q)" ]; then                # no way round it: GUIDE_OK=1 let 51 topics pile up unread by v0.35.0
  dev/release/guide_stale.py
  echo "read those topics in docs/guide.html against the commits shown, fix what is wrong, and set their data-v=\"next\" (that is the mark of a topic read)"; exit 1
fi
git fetch -q --tags origin || { echo "git fetch --tags failed: the last tag decides every number"; exit 1; }   # a tag made on another clone / by a past run: without it LAST is one release behind and that release is made again
LAST=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)   # release tags only: cores-1 (TV Games' arcade cores) once passed for the last release
next() { python3 -c 'import sys; a = [int(x) for x in sys.argv[1].split(".")]; i = {"major": 0, "minor": 1}.get(sys.argv[2], 2); a[i] += 1; a[i + 1:] = [0] * (2 - i); print(".".join(map(str, a)))' "$1" "${BUMP:-patch}"; }
# "changed": dev/release/code_changed.py, code beyond comments (a path renamed in comments is no update), in what the app's own
# build.gradle.kts compiles (dev/release/app_paths.py) plus the extra paths given
changed() { local a=$1; shift; local p=(); while IFS= read -r l; do p+=("$l"); done < <(dev/release/app_paths.py "$a"); dev/release/code_changed.py "$LAST" "${p[@]}" "$@"; }
was() { dev/release/code_changed.py --show "$LAST" "app/$1/VERSION" 2>/dev/null || true; }   # the app's number at the last tag (through renamed folders)
if [ -n "$LAST" ]; then
  [ "$(cat VERSION)" = "${LAST#v}" ] && next "$(cat VERSION)" > VERSION
  for a in android tvsetup; do                                        # changed code must come with a new number, or nobody gets it
    [ -z "$SKIP_CHECK" ] || continue
    old=$(was $a)
    more=(); [ "$a" = tvsetup ] && more=(app/tvkeys app/tvvoice)     # TV Setup Keys (inside tv-setup.apk) and TV Setup Voice (tv-voice.apk) carry TV Setup's number
    if [ -n "$old" ] && [ "$old" = "$(cat app/$a/VERSION)" ] && changed "$a" "${more[@]}"; then
      next "$old" > "app/$a/VERSION"
    fi
  done
  old=$(was tvhome)                                                   # TV Home: its own code and the settings' shape, not the rest of app/shared
  if [ -z "$SKIP_CHECK" ] && [ -n "$old" ] && [ "$old" = "$(cat app/tvhome/VERSION)" ] && changed tvhome; then
    next "$old" > app/tvhome/VERSION
  fi
  old=$(was tvchannels)                                               # TV Channels: its code, the packed providers list and app/shared/src/sign
  if [ -z "$SKIP_CHECK" ] && [ -n "$old" ] && [ "$old" = "$(cat app/tvchannels/VERSION)" ] && changed tvchannels docs/channels; then
    next "$old" > app/tvchannels/VERSION
  fi
  old=$(was tvgames)                                                  # TV Games: its code, the packed games list and app/shared/src/sign
  if [ -z "$SKIP_CHECK" ] && [ -n "$old" ] && [ "$old" = "$(cat app/tvgames/VERSION)" ] && changed tvgames docs/games/games.json docs/games/games.json.sig; then
    next "$old" > app/tvgames/VERSION
  fi
  old=$(was tvapps)                                                   # TV Apps: its code (the list is read from the site, not packed)
  if [ -z "$SKIP_CHECK" ] && [ -n "$old" ] && [ "$old" = "$(cat app/tvapps/VERSION)" ] && changed tvapps; then
    next "$old" > app/tvapps/VERSION
  fi
fi
# Release.legacy() takes 1.3.0 … 2.x for the numbers before the restart: past 1.2 the new ones would look old
for v in VERSION app/android/VERSION app/tvsetup/VERSION app/tvhome/VERSION app/tvchannels/VERSION app/tvgames/VERSION app/tvapps/VERSION; do
  if python3 -c 'import sys; a = [int(x) for x in open(sys.argv[1]).read().split(".")]; sys.exit(0 if a[0] >= 2 or (a[0] == 1 and a[1] >= 3) else 1)' "$v" &&
     grep -q "fun legacy" app/shared/src/sign/kotlin/dev/tvremote/update/Release.kt; then
    echo "$v reaches $(cat $v): first remove Release.legacy() (and its tests), the numbers from before the restart at 0.1.0"; git checkout -- VERSION app/android/VERSION app/tvsetup/VERSION app/tvhome/VERSION app/tvchannels/VERSION app/tvgames/VERSION app/tvapps/VERSION; exit 1
  fi
done
VER=$(cat VERSION)
if ! grep -q "^## $VER " CHANGELOG.md; then
  grep -q "^## Unreleased$" CHANGELOG.md || { echo "CHANGELOG.md: write this release's notes under a \"## Unreleased\" heading"; git checkout -- VERSION app/android/VERSION app/tvsetup/VERSION app/tvhome/VERSION app/tvchannels/VERSION app/tvgames/VERSION app/tvapps/VERSION; exit 1; }
  python3 - "$VER" "$(date +%F)" "$(cat app/android/VERSION)" "$(cat app/tvsetup/VERSION)" "$(cat app/tvhome/VERSION)" "$(cat app/tvchannels/VERSION)" "$(cat app/tvgames/VERSION)" "$(cat app/tvapps/VERSION)" <<'PY'
import sys
v, day, phone, tv, home, channels, games, tvapps = sys.argv[1:]
s = open("CHANGELOG.md").read().replace("## Unreleased\n", f"## {v} - {day}\n\nWeb {v} · Phone app {phone} · TV app {tv} · TV Home {home} · TV Channels {channels} · TV Games {games} · TV Apps {tvapps}\n", 1)
open("CHANGELOG.md", "w").write(s)
PY
fi
# the guide's topics checked against main ("next") now describe this release
python3 - "$VER" <<'PY'
import sys
s = open("docs/guide.html", encoding="utf-8").read()
open("docs/guide.html", "w", encoding="utf-8").write(s.replace('data-v="next"', f'data-v="{sys.argv[1]}"'))
PY
python3 dev/release/stamp_since.py "$VER" "$(date +%F)"          # docs/data/since.json: "next" -> $VER, with its date
NOTES=$(awk -v v="## $VER " 'index($0, v) == 1 {on = 1; next} on && /^## / {exit} on' CHANGELOG.md)
[ -n "$NOTES" ] || { echo "CHANGELOG.md has no section for $VER"; exit 1; }
if [ -n "$(git status --porcelain)" ]; then
  git commit -qam "Release v$VER (phone app $(cat app/android/VERSION), TV app $(cat app/tvsetup/VERSION))"
  echo "numbers: web $VER, phone $(cat app/android/VERSION), TV $(cat app/tvsetup/VERSION)"
fi
git push -q origin HEAD                                               # the release is made from what GitHub has
# the computer app is built again (on GitHub, each system) only when its code changed; otherwise the last release's archives
# are attached again and "web" (what the computer app reads) keeps their version, so nobody is offered the same app again
# NO_DESKTOP=1: only the APKs, no GitHub Actions run (its minutes are limited). Changes are counted from the release that
# last built the computer app, so what a NO_DESKTOP release skipped is built by the next one
DESK=$VER
if [ -n "$LAST" ]; then
  BUILT=$(gh release download "$LAST" -p versions.json -O - | python3 -c 'import json, sys; d = json.load(sys.stdin); print(d["web"])')
  if [ -n "$NO_DESKTOP" ] || ! LAST="v$BUILT" changed desktop >/dev/null; then DESK=$BUILT; fi
fi
./app/android/build.sh && ./app/tvsetup/build.sh && ./app/tvhome/build.sh && ./app/tvchannels/build.sh && ./app/tvgames/build.sh && ./app/tvapps/build.sh && ./app/tvvoice/build.sh
unzip -l app/tvsetup/build/tv-setup.apk | grep -q 'assets/tvkeys.apk' || { echo "tv-setup.apk has no assets/tvkeys.apk (TV Setup Keys, the add-on with the accessibility services): not releasing"; exit 1; }
printf '{"android": "%s", "tv": "%s", "home": "%s", "channels": "%s", "games": "%s", "tvapps": "%s", "web": "%s"}\n' "$(cat app/android/VERSION)" "$(cat app/tvsetup/VERSION)" "$(cat app/tvhome/VERSION)" "$(cat app/tvchannels/VERSION)" "$(cat app/tvgames/VERSION)" "$(cat app/tvapps/VERSION)" "$DESK" > app/android/build/versions.json
D=https://github.com/HoangTran0410/tv-remote/releases/latest/download
NOTES="$NOTES

**Tải về / Download**

| | |
|---|---|
| TV (TV Setup) | [tv-setup.apk]($D/tv-setup.apk) · Downloader \`2397346\` |
| Android | [tv-remote.apk]($D/tv-remote.apk) · Downloader \`2973135\` |
| TV Home (launcher) | [tv-home.apk]($D/tv-home.apk) · Downloader \`4356026\` |
| TV Channels | [tv-channels.apk]($D/tv-channels.apk) · Downloader \`8135635\` |
| TV Games | [tv-games.apk]($D/tv-games.apk) · Downloader \`2294968\` |
| TV Apps | [tv-apps.apk]($D/tv-apps.apk) · Downloader \`1082201\` |
| Mac (Apple Silicon + Intel) | [TV-Remote-mac.zip]($D/TV-Remote-mac.zip) |
| Windows 10/11 | [TV-Remote-windows-x64.zip]($D/TV-Remote-windows-x64.zip) |
| Linux x64 / arm64 | [TV-Remote-linux-x64.tar.gz]($D/TV-Remote-linux-x64.tar.gz) · [TV-Remote-linux-arm64.tar.gz]($D/TV-Remote-linux-arm64.tar.gz) |"
# the APKs live only in releases, never in git
gh release create "v$VER" app/android/build/tv-remote.apk app/tvsetup/build/tv-setup.apk app/tvhome/build/tv-home.apk app/tvchannels/build/tv-channels.apk app/tvgames/build/tv-games.apk app/tvapps/build/tv-apps.apk app/tvvoice/build/tv-voice.apk app/android/build/versions.json --title "v$VER" --notes "$NOTES" --latest  # by date: GitHub would still call 2.24.2 the latest
if [ "$DESK" != "$VER" ]; then
  echo "computer app not built: the $DESK archives attached again"
  T=$(mktemp -d); gh release download "$LAST" -p 'TV-Remote-*' -D "$T" && gh release upload "v$VER" "$T"/TV-Remote-*; rm -rf "$T"
else
  # built on each system by .github/workflows/desktop.yml, which attaches them: waited for, so the release is only called
  # done once every download link works
  echo "building the computer apps (.github/workflows/desktop.yml)..."
  START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  gh workflow run desktop.yml -f tag="v$VER"
  for i in $(seq 30); do RUN=$(gh run list --workflow desktop.yml --event workflow_dispatch -L 5 --json databaseId,createdAt -q ".[] | select(.createdAt >= \"$START\") | .databaseId" | head -1); [ -n "$RUN" ] && break; sleep 5; done
  [ -n "$RUN" ] || { echo "the desktop workflow did not start: gh workflow run desktop.yml -f tag=v$VER"; exit 1; }
  # one rerun of the failed jobs first: a runner image brownout or a flaky window check (Windows, v0.39.x) passes the second time
  gh run watch "$RUN" --exit-status >/dev/null || { echo "a computer app failed: its jobs once more"; gh run rerun "$RUN" --failed && sleep 10 && gh run watch "$RUN" --exit-status >/dev/null; } \
    || { echo "a computer app failed to build twice: gh run view $RUN --log-failed (gh api .../check-runs/<job>/annotations says why a job was cancelled)"; exit 1; }
fi
dev/release/sums.sh "v$VER"                                                   # the signed list of every file, last: until it is there no app offers this release
gh release view "v$VER" --json assets -q '.assets[].name'
echo; node dev/admin/issues.js || true                                  # issues labelled next-release: this release is what they wait for
echo; dev/release/churn.sh || true                                           # how settled the /api is: says when 1.0 is worth thinking about
