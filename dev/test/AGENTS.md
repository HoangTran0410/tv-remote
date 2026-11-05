# dev/test: emulators and test tools

Read with the root [AGENTS.md](../../AGENTS.md).

## Testing

Test on emulators, not on real TVs. Two AVDs are used: `tvremote_gtv` (Google TV, Android 14) and `tvremote_test` (phone, Android 14).
`dev/test/emu.sh` does the setup below; use it rather than doing it by hand:

```
dev/test/emu.sh create    # both AVDs, once (system images downloaded; arm64 or x86_64 to match the computer)
dev/test/emu.sh           # start (the TV first) + build both APKs + install each on the right emulator; again after every change
dev/test/emu.sh install   # build + install only, the emulators already running
dev/test/emu.sh reset     # the phone app's saved TVs reduced to 10.0.2.2
dev/test/emu.sh stop
dev/test/emu.sh status    # every emulator on the computer, hidden ones too: CPU, uptime, what is in front
```

Then in the phone app the TV is `10.0.2.2`; accept "Allow USB debugging?" on the TV emulator the first time (its window).
Env: `SECURE=0` (no prompt), `HEADLESS=1` (no windows; such an emulator stops itself after `EMU_TTL` minutes, 120), `TV_AVD`/`PHONE_AVD`/`TV_PORT`/`PHONE_PORT` (a second pair beside the first).
What it takes care of, for doing it by hand:

- Boot the TV emulator **first** so it is `emulator-5554`; its ADB port 5555 is then reachable from the phone emulator as `10.0.2.2:5555`. Check which serial is the TV (`android.software.leanback` in `pm list features`) before installing anything: the serials swap when the boot order differs.
- Boot the TV emulator with `-prop ro.adb.secure=1` to get the real "Allow USB debugging?" prompt. The TV shows one prompt at a time and queues prompts of dead connections first.
- Emulator snapshots can bring back an old saved-TV list holding real TV addresses: before tapping anything, check `shared_prefs/tvs.xml` (`ips`) of `dev.tvremote.phone` (needs `adb root`) and reduce it to `10.0.2.2` (`dev/test/emu.sh reset`).
- The Google TV image is arm64-only (runs 64-bit apps only); an x86_64 image (Intel computers) behaves differently there.
- Tap by text (find the node in `uiautomator dump`), never by fixed coordinates.
- TV Home's grid and tile menus against the table (`Tiles.kt`): `dev/test/home_tiles.sh <serial>` (exit 1 with what is wrong; dev/flows/home-tiles.md);
  the same flows pressed through the real UI, with that check after each step and screenshots: `dev/test/home_walk.py <serial> [dir]`
  (picks the tiles the TV has, in TV Home's language; puts the grid settings back).
- On a real TV install with `dev/test/tvinstall.sh <serial> <apk>...`: `adb install -r` of TV Home kills the launcher and the
  TV falls into another app; the script sets TV Home again and presses HOME when it was the home.
- If `adb exec-out screencap` comes back blank on the phone emulator, use `adb emu screenrecord screenshot <file>`.
- Web UI: headless Chrome through the DevTools protocol against the test server above.
- **Keep the computer usable** (a run of parallel agents froze it once, dev/decisions/testing.md "Tests that froze the computer"): at most
  two emulators (`emu.sh` refuses a third: reuse the running one); heavy steps (ffmpeg, Gradle builds, `audit.js`) one at a time
  through `dev/test/light.sh <cmd>` (waits for room, `nice`, one lock), never several with `&`; test videos / GIFs from
  `dev/test/media.sh` (made once in `.cache/media`), and any ffmpeg on `lavfi` sets the length in the source (`:duration=`), never only `-t`.
  A video background left looping on TV Home makes the TV emulator take ~9.5 cores (software codec + CPU-drawn GPU; 0.4% with TV
  Home in the background, measured 2026-10-24): play it only while measuring, then a picture background or another app in front.
- **A slow link** (`node dev/test/slowlink.js --to 127.0.0.1:5615 [--listen 5700] [--delay 25] [--rate KB/s]`): a TCP proxy with a one-way delay (default 25 ms: ~50 ms round trip, 5G over Tailscale) and an optional bandwidth cap per direction. Point it at an emulator's ADB port (console port + 1, e.g. 5615 for `-port 5614`) and connect the client to the proxy's port. The Android 14 emulator's adbd over-acks plain writes, so measure reads (`exec:cat file`) or the picture there; a push needs a real TV. dev/decisions/connect.md "ADB delayed_ack".
- Unit tests (no device needed): `node --test app/shared/tests/ app/tvgames/tests/` (also run by `dev/hooks/pre-push` before every push, since a push deploys `docs/`, and by `release.sh`; set up once per clone: `git config core.hooksPath dev/hooks`) (the web games' rules and bots: `app/tvgames/tests`, beside the app that packs them, not in `docs/`, which the site publishes),
  `cd app/gradle && ./gradlew :shared:testDebugUnitTest :tvsetup:testDebugUnitTest`. The voice tests exist on both sides
  (`voice.test.js`, `VoiceTest.kt`): keep `js/voice.js` and `Voice.kt` understanding the same sentences.
- **Speed budgets** (`PerfBudgetTest.kt` in `:shared`, ~17 hot paths: Ed25519 / `SignedList.kept`, `Launchers.parse/facts`, `WebPage` gzip, `Words` loading, `Qr`, `Diagnose`, parsers; `tests/perf_budget.test.js` for the page's: i18n, QR, voice; ~2 s each; the node one runs in the pre-push hook, the Kotlin one with `:shared:testDebugUnitTest` and in `release.sh`, not pre-push: Gradle is ~1 min). Baselines are per machine, in the gitignored `dev/perf/.local/` (keyed by OS / CPU count / JVM or node version): a first run on a machine or a new case records and passes, a clearly faster run lowers it. Red = over 2x the baseline AND over baseline + the case's floor: **find the regression (`git log` on the function) and fix it**. Raise the baseline only when slower is intended (`TVR_PERF_UPDATE=1`, say why in the commit); never commit baselines. A new hot path = one more `case(...)`, not a test per function (dev/decisions/testing.md "Speed budgets").
- **APK jump alarm** (`node dev/test/apk_sizes.js`, rule in `app/shared/tests/apk_sizes.test.js`): each built release APK against the committed `dev/test/apk-sizes.json`; red when one grew over 10% and 256 KB, or over 1 MB. Not a cap (size alone costs no CPU / RAM); look at what came in, and if it is wanted `--update` (or `TVR_APK_UPDATE=1`) and say why in the commit. An APK not built here is skipped; `TVR_APK_ROOT=<repo>` reads another checkout's builds. Run it after `app/<x>/build.sh` and in a release.
- Real network and real browsers: `dev/test/smoke.py tv` asks every `/api` route of TV Setup on the TV emulator through the emulator's
  own port redirect (adb forward hid a reply lost to a reset); `TVR_PASS=… dev/test/smoke.py URL` does the same for the computer app.
  `TVR_PASS=… node dev/test/layout.js` checks the layout in Chrome and WebKit (the Mac app's window and iPhones are WebKit). Run both
  after a change to HttpServer / the routes, or to the page's CSS.
- `node dev/test/apifail.js` runs every core and tab while /api fails (all down, all 500, half the paths failing): a field read of a
  failed answer (`r.always` on null) throws only there; exit 1 on any script error. release.sh runs it (~2 min).
- `node dev/test/slowload.js` loads the page as a slow TV sends it (each script late, /api at once) in every core and start tab;
  exit 1 on any script error ("X is not defined": an answer handled before a later file was read). release.sh runs it.
- Before a release with UI changes: `node dev/test/audit.js` presses every button of the page (no TV, the demo's made-up one) and checks each
  screen it opens; read `/tmp/tvr-audit/index.html` and fix what it boxes ("same" is advice). `--only=auto` for one tab.
- Contrast: `node --test app/shared/tests/` includes `contrast.test.js` (WCAG ratios of the `css/base.css` tokens in both themes); `dev/test/contrast.py [URL]` (Chrome, default the test server on 8772) scans the rendered page, both themes x 1100/400 px x every tab + Settings, exit 1 under 4.5:1 (3:1 icons, large text). Run it after colour changes.
- The TV's sound: the Google TV emulator cannot run YouTube; press D-pad keys on the TV during a capture to get click sounds.
- The intro site's screenshots: `dev/shots/shots.py` (`docs/README.md`).

## Speed on a weak box (TV Home and the other TV apps)

Lessons from the FPT Play box (1.8 GB, Mali, Android 14), 2026-10-27:

- **Measure the release build, never a debug one.** A debuggable APK runs interpreted (`ExecuteSwitchImplCpp` in the
  profile, about 2x the CPU) and its numbers mislead. Home's release build is signed with the same key as debug, so
  `adb install -r` keeps the person's settings. It is `<profileable android:shell="true">`, so simpleperf works on it.
- `dev/test/tvperf/`: `panels.sh <serial> <label>` walks the panels from Home (the tile menu, Customize and two of its pages,
  the Control Center) and prints frames, jank, worst frame, CPU ms and PSS before / open / after. `keys.sh` measures one key
  set after an opening set. `idle.sh` measures frames and CPU per thread while Home is left alone. `scroll.sh` walks Customize → Widgets (a TvScroll) at a key gap: frames, p90, CPU per frame. `prof.sh` runs simpleperf
  while keys are pressed and then `agg.py` (inclusive ms per method, names from the R8 map). The key paths assume Home's
  default layout (the first tile; the gear at the top right).
- simpleperf on the box: `-e cpu-clock` (the FPT box has no `cpu-cycles`). The host's `simpleperf report` cannot read an R8
  map, so use `report_sample.py --proguard-mapping-file app/<x>/build/outputs/mapping/release/mapping.txt`.
- **Read the frame counts, not only jank %.** A focus move should draw a handful of frames. 22 a move meant an animation
  nobody needed (the menu rows' 1.06 pop). Fewer frames can raise the jank % while the panel got smoother.
- **Usual costs found:**
  - Building views nobody looks at: all seven Customize pages were built on open (~1300 views, +16 MB). Each page is now
    built when its row holds the focus.
  - Laying out a big page in the first frame of its slide: lay it out hidden (`INVISIBLE`) when it is built.
  - Per-tile views that are rarely shown: make badges on first use.
  - Recomputing settings on every `Store.get`.
  - Rebuilding the whole screen for a change that touches one thing (the veil).
  - Re-decoding a picture already held.
  - Decoding a picture whole, then scaling it down (`inSampleSize`).
  - Software round rects redrawn on every opening: keep the previews in a small LRU.
- RenderThread time is mostly the GPU path (`prepareTree`, Skia flush) and drops with the number of frames. Look at
  `Glass.onDraw` and `VectorDrawable` draw only if they lead.
