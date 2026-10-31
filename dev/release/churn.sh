#!/usr/bin/env bash
# How settled the /api is, week by week: of the commits that changed code, how many touched the routes the page calls
# (Manage.kt, Server.kt, RemoteService.kt) and how many were the web page alone. Run by release.sh after a release.
# Once the routes have stayed quiet for a few busy weeks it says so: the time to think about 1.0, and about the apps
# fetching new web pages by themselves (signed), which only pays while the /api holds still.
set -euo pipefail
cd "$(dirname "$0")/../.."

WEEKS=${WEEKS:-6}        # weeks shown
QUIET=${QUIET:-3}        # busy weeks in a row with few route changes before the reminder
BUSY=5                   # fewer code commits than this: not a week that says anything
MAXPCT=10                # route changes allowed in a quiet week, in % of its code commits

API='app/shared/src/main/kotlin/dev/tvremote/core/manage/Manage.kt app/desktop/src/main/kotlin/dev/tvremote/desktop/Server.kt app/tvsetup/src/main/kotlin/dev/tvremote/setup/RemoteService.kt'
NOISE='^(CHANGELOG|README|CLAUDE|VERSION|docs/|dev/)|/VERSION$'   # notes, the site and version bumps are not code

count() {                                                            # code / route / web-only commits in [since, until)
  local code=0 api=0 web=0 files
  for c in $(git log --since="$1" --until="$2" --no-merges --format=%h); do
    files=$(git show --name-only --format= "$c" | grep -Ev "$NOISE" || true)
    [ -z "$files" ] && continue
    code=$((code + 1))
    for f in $API; do grep -qxF "$f" <<<"$files" && { api=$((api + 1)); break; }; done
    grep -qv '^app/shared/web/' <<<"$files" || web=$((web + 1))
  done
  echo "$code $api $web"
}

echo "/api churn (code commits · touching the routes · web page only):"
quiet=0 streak=1
for w in $(seq 0 $((WEEKS - 1))); do
  a=$(date -v-$(((w + 1) * 7))d +%F 2>/dev/null || date -d "-$(((w + 1) * 7)) days" +%F)
  b=$(date -v-$((w * 7))d +%F 2>/dev/null || date -d "-$((w * 7)) days" +%F)
  read -r code api web <<<"$(count "$a" "$b")"
  [ "$code" -eq 0 ] && { echo "  $a … $b   no code commits"; streak=0; continue; }
  pct=$((api * 100 / code))
  echo "  $a … $b   $code · $api ($pct%) · $web"
  if [ "$streak" = 1 ] && [ "$code" -ge "$BUSY" ] && [ "$pct" -le "$MAXPCT" ]; then quiet=$((quiet + 1)); else streak=0; fi
done

if [ "$quiet" -ge "$QUIET" ]; then
  echo
  echo "→ The /api has held still for $quiet busy weeks: time to think about 1.0 and self-updating web pages (signed)."
  echo "→ API đã đứng yên $quiet tuần bận rộn: đến lúc tính chuyện 1.0 và cho web tự cập nhật (có chữ ký)."
fi
