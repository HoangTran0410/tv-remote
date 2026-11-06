#!/usr/bin/env bash
# Hands the Claude session what crashwatch found since last time (new census crashes, GitHub issues waiting for us), once:
# ~/.tv-remote-release/news.txt is written by crashwatch.py (launchd, every 2 min) and emptied here. Run by the project's hooks
# (.claude/settings.json) on each prompt and after each tool call, so news reaches a long turn too; an idle session is woken by news_wait.sh. Nothing new: silent.
# $1: the hook's event name (UserPromptSubmit, PostToolUse).
f="$HOME/.tv-remote-release/news.txt"
[ -s "$f" ] || exit 0
text="$(cat "$f")"; : > "$f"
msg="NEWS for the user (crashwatch, every 2 min). Tell the user about it in your next words, before going on:
$text
Census: python3 dev/census/crashes.py --new. Issues: gh issue view N. The user wants these investigated, fixed and answered on GitHub at once, without asking (memory auto-triage-and-reply)."
python3 -c 'import json,sys; print(json.dumps({"hookSpecificOutput": {"hookEventName": sys.argv[1], "additionalContext": sys.argv[2]}}))' "${1:-UserPromptSubmit}" "$msg"
