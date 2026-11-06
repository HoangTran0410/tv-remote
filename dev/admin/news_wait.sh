#!/usr/bin/env bash
# Wakes an idle Claude session when crashwatch finds something: news.sh only runs on a prompt or after a tool call, so news
# waited until the user typed. The session runs this in the background (asked by the SessionStart hook, `news_wait.sh arm`);
# it exits when ~/.tv-remote-release/news.txt has text, and its exit wakes the session with that text. Then it is started again.
f="$HOME/.tv-remote-release/news.txt"
if [ "$1" = arm ]; then                                              # the SessionStart hook: the request to start it
  msg="Start \"$CLAUDE_PROJECT_DIR/dev/admin/news_wait.sh\" with Bash run_in_background now (once per session, no need to tell the user). It ends when crashwatch has news: tell the user, triage it (memory auto-triage-and-reply), then start it again."
  python3 -c 'import json,sys; print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": sys.argv[1]}}))' "$msg"
  exit 0
fi
until [ -s "$f" ]; do sleep 5; done
cat "$f"; : > "$f"
echo
echo "NEWS for the user (crashwatch). Tell them, investigate / fix / answer on GitHub at once, then start dev/admin/news_wait.sh in the background again."
