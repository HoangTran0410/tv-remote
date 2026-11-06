# Crashes: guide for agents

Reports come from every app (anonymous, scrubbed) to the census worker. Tool: `dev/census/crashes.py` (token `~/.tv-remote-release/crash-token`, read by the script; never print it).

```
python3 dev/census/crashes.py --new --json            # not looked at yet, every version: never marked, or happened again since its mark
python3 dev/census/crashes.py --new --adb --json      # also "TV Setup ADB ..." (a TV with ADB off or unanswered: states, hidden from --new by default)
python3 dev/census/crashes.py --mark SIG --note "fixed in 1a2b3c4"   # looked at (also --mark all); writes checked.json: commit it
python3 dev/census/crashes.py --json --since 2026-10-01 [--app pc] [--v 0.22.1]
python3 dev/census/crashes.py SIG --frames            # stack + repo path:line of each frame that maps to a file here
python3 dev/census/crashes.py --delete SIG            # after the fix is released
```
`--json` fields: sig, app, v, kind, msg, count, installs, first, last, device, stack seen (the last mark `{at, n, note}` or null), unseen (times since the mark) (+ `frames` with `--frames`). Default output is the table.

## Triage loop
1. List new (`--new --json`). Nothing: say so, stop.
2. Group by `kind` + top frame: many sigs, one cause = one fix.
3. `SIG --frames`: open the first `path:line`. JS frames map to `app/shared/web/js`, Kotlin by package + class.
4. Reproduce if you can (unit test, node test, or the page in Chrome; emulators only when the owner allows).
5. Fix with a test that fails before. Put the sig (8 letters) in the commit message.
6. Tell the owner: sig, count / installs, cause, fix, test. Not releasing unless asked.
6b. `--mark SIG --note "<cause / fix commit / why left>"` for each one looked at, also the ones left alone; commit `dev/census/checked.json`. A marked crash comes back in `--new` only when it happens again.
7. After the fix is released (a tag), `--delete SIG`. The same sig on a newer `v` means the fix failed.

## Never
- Delete a crash that is not fixed and released (it is the only record).
- Print or copy the token.
- Paste stacks, messages or device lines into public issues / PRs: scrubbed data can still hold names a person typed.
- Release or push because of a crash; the usual rules in AGENTS.md apply.
