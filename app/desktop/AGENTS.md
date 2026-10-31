# app/desktop: the Computer app

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## What it is

| Name | Code | `data-core` | What it is |
|---|---|---|---|
| **Computer app** (App máy tính) | `app/desktop` (Kotlin, JVM) | `pc` | "TV Remote" for Mac, Windows, Linux: `Server.kt` on `127.0.0.1:8765`, shown in its own window (the system's web view, `Window.kt`) |

## Files

```
app/desktop/             the computer app on the shared Kotlin core (JVM, no adb/Python/ffmpeg), the "pc" core: Server.kt
                         (the routes only the computer has) + Manage.kt and its parts; Pass.kt (a key per start, in the link the window and browser open: tests pass `TVR_PASS` and `?k=`); Updater.kt (updates itself from releases, swapped in by a script once it quits); Window.kt shows the page in the system's web view
                         (native/: webview, kept in git, native/README.md). `./gradlew :desktop:installDist`, then
                         `app/desktop/build/install/desktop/bin/desktop --port 8769 [--window]` (inside the repo: its tvs.json, keys in
                         .cache/keys). `./gradlew :desktop:packageApp` -> the app for this system + zip / tar.gz (own Java via jlink;
                         data in the user's app data folder); .github/workflows/desktop.yml builds and opens it on Mac, Windows, Linux
```

## Build and run

- Computer app: `cd app/gradle && ./gradlew :desktop:installDist`, then `app/desktop/build/install/desktop/bin/desktop [--window]` (inside the repo: its tvs.json, keys in .cache/keys; the page is read from `app/shared/web` on each load, so a web change needs only a reload). For tests, another port and its own TV list so real TVs are untouched: `TVS_FILE=/tmp/x.json TVR_KEYS=/tmp/keys TVR_PASS=testtest app/desktop/build/install/desktop/bin/desktop --port 8769`, opened at `http://127.0.0.1:8769/?k=testtest` (it prints the link; curl: header `x-tvr-pass`). With `TVS_FILE` set it sends no census (no install counted, no crash report from a test).
- Computer app for people: `./gradlew :desktop:packageApp` → `app/desktop/build/package/` (the app for this system + `TV-Remote-<os>-<arch>.zip|tar.gz`); a release gets them from `.github/workflows/desktop.yml`: Windows, Linux x64 / arm64, and one Mac app for both chips (`TV-Remote-mac.zip`, the arm64 and x64 builds joined by `app/desktop/universal.sh`).
