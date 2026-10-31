# app/shared: the shared code: the web UI (`web/`), the Kotlin core (`src/`) and the page tests

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## Files

```
app/shared/web/          THE web UI (index.html, css/*.css, js/*.js): the Computer app, the TV web page, the Phone app's Manage screen
app/shared/i18n/         every app's words, every language (langs.json, <lang>.json for the page and T(), apps/<lang>/ for the native
                         apps): the page still asks for i18n/<lang>.json (the computer app maps it, the APKs get it in their assets)
app/shared/src/.../core/ Kotlin shared by the apps, one package per kind of work (Java helpers Meta / Caps / Playing / Screen stay
                         in `core`: TV Setup and app_process call them by name):
  remote/                one TV's own remote link, by brand, behind `Remote`: Atv (Android TV, keys without ADB), Lg, Samsung,
                         Roku, Ssdp (finding them), WebSocket, Inputs (HDMI and other sources)
  adb/                   Adb, AdbLink, AdbPrompt ("Allow USB debugging?"), Spake2 + AdbTls + Mdns (pairing and connecting through
                         Android 11+ Wireless debugging: SPAKE2 with BoringSSL's own M/N, TLS 1.3 through `app/shared/conscrypt`
                         ConscryptBackend, found by mDNS; Phone and Computer apps only, `Manage.canPair`), TvShell, Pkg
  picture/               Tv (scrcpy session, video/audio/control), Feeds, AudioOut (Opus playback), Downmix, SoundQueue
  manage/                Manage (the /api core for the web UI: routes the TV itself, the rest to its parts, each a ManagePart with
                         its own `route()`: AppsApi, FilesApi, InstallApi, HealthApi, SetupApi, HomeApi, LauncherApi, TvAppsApi,
                         TvCopy; Jobs (the jobs the page polls), pure helpers TvPaths / Procs / Apk; a new route goes in the part
                         it belongs to)
  web/                   HttpServer, WebPage, Invite, Qr, Transfer (Stop, no room), Hook, Telegram + TelegramHttp (the Telegram bot: long polling, runs in TV Setup; commands, alerts via `RemoteService.alert(kind)`, history, `/start <code>` pairing; the phone/computer card reaches it through Manage `/api/telegram` -> RulesReceiver `do:"telegram"`; TV Home asks HomeInfo `call("telegram", ""|"code")` -> `{state: token_missing|none|ready, bot, code, code_left, link}`, see dev/decisions/tv-setup.md "Telegram bot")
                         (Home Assistant / Shortcuts: `/api/hook`, its own token; TV Setup on 8765, the computer app on port+1 only while on)
  ontv/                  things done on the TV over ADB: Setup (Optimize TV; TV Home as the home screen; Bloat: brands' apps,
                         plus `docs/optimize/bloat.json` from the site, signed by `dev/sign/sign_bloat.py`, only adding to keep), SetupNeed / SetupUpdate, SetupFiles (reading TV Setup's
                         files: the shell, or TV Setup hands them over where the shell may not, a TCL on Android 11), RulesClient (TV Setup's
                         rules), Draw (strokes to TV Setup's layer), Notify, NowPlaying (through Playing), RemoteLabels (app
                         names, pictures and Caps, through Meta and Caps: Java in src/main/java, dexed into
                         assets/tvr-helper.jar by :shared:tvHelper, kept in git; app/shared/assets/README.md), ScreenOff, Sensors, Meminfo, Tailscale
  scenes/                Scenes (scenes + key names), Voice (spoken commands), RemoteSpec (remote.json)
  (guard)                `app/shared/src/guard`: Guard.kt (every Android app: keeps the main looper alive, shows the error in a dialog; dev/decisions/apps.md "The error guard") + GuardLogic.kt (also the computer app); the page's is `js/errbox.js`. An error shown there is also sent to the census worker (`POST /v1/crash`, `Census.crash`, `Scrub.kt` + `js/scrub.js`, one case table `tests/scrub_cases.json`) when the stats switch is on; the apps without :shared (TV Home, Channels, Games, Apps) have no switch and send nothing (`Guard.crash` stays null)
  (core itself)          Errors, Diagnose (the self-check when a TV cannot be reached: one conclusion + fix, `/api/diagnose`, `js/diagnose.js`, phone `Diagnosing.kt`), WireLog (what the TVs said and the errors shown, keys blanked: About → Send the log,
                         `/api/wirelog`), Fleet (several saved TVs at once), SameTv (one TV saved under two addresses), B64, SelfSigned
app/shared/tests/        node tests of the page's scripts, and the case tables the Kotlin tests read too (its README.md)
```

## Other brands (LG, Samsung, Roku)

LG (webOS, `Lg.kt`, SSAP on 3000/3001) and Samsung (Tizen, `Samsung.kt`, 8001/8002) TVs are adapters behind the same
`Remote` interface as `Atv` (`Remote.kt`: `Remote.Kind` with its `caps`, `RemoteSteps` for scenes), found by `Ssdp.kt`.
Only the Phone app and the Computer app have them (the TV web page needs TV Setup, Android only). The page reads `kind` /
`caps` from `/api/state` (`applyKind()` in `js/core.js`): `data-needs="cap"` hides a part or greys a control, and a
`remote.json` entry's `needs` does the same for keys and menu entries on both sides. A new feature that needs ADB: give it
`needs: ["adb"]` / `data-needs="adb"`.

## Downloader codes

- Downloader (AFTVnews) short codes, always pointing at the latest release: **2397346** → `tv-setup.apk`, **2973135** → `tv-remote.apk`, **4356026** → `tv-home.apk`, **8135635** → `tv-channels.apk`, **2294968** → `tv-games.apk`, **1082201** → `tv-apps.apk`. They are listed once in `docs/data/downloader.json`, signed by `dev/sign/sign_downloader.py` (the launcher list's key; run it after every edit and commit the `.sig` with it); the apps read it through `core/Downloader.kt` (site, else GitHub, else the copy kept, else `PACKED`, which `DownloaderTest` keeps equal to the file; an unsigned list is ignored: a code makes the TV install what it points at), and the page asks `/api/downloader`. The words take `{code}`, never the digits. The static pages of `docs/` and the READMEs keep the codes as text. Downloader registers the `downloader:` scheme: opening `downloader://<code>` (an intent, or the remote protocol's launch command) makes it fetch the APK and bring up the installer with nothing typed. Only send it once Downloader is known to be installed; a link the TV has no app for can make it drop the remote connection.

## ADB and keys

The Phone app and the TV app speak ADB themselves (`core/adb/Adb.kt`) and push `scrcpy-server` (`app/shared/assets`) for video, touch and keys. So does the Computer app, on the same core. The Phone app and the Computer app also send keys without ADB through the Android TV Remote protocol (`Atv.kt`, ports 6466/6467).
