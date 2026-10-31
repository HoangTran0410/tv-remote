# Decisions: TV Home

Why some code looks odd ([the list](../DECISIONS.md)).

## TV Home QR widgets

- Encoder: Qr.kt / qr.js now do versions 1-10 (271 bytes, level L, mask 0; blocks interleaved, alignment patterns, version info). Every length 10-271 decoded with zbarimg; a Wi-Fi line with a long password or a menu link did not fit in v5 (106 bytes).
- Model: config `qrs` = {qr1..qr4: {tpl, f: fields, cap, show}} and the keys are ordinary "widgets" (order, size, off for free). The TV builds the payload from the fields (HomeQr.payload), JS mirrors it (home_qr.js); both are held to tests/home_qr_cases.json. A template is only a form; "text" is the raw payload.
- The Wi-Fi password lives only in TV Home's config on the TV (and comes back to a paired client so the widget can be edited). WireLog.clean blanks `WIFI:...;;` and "pass" values. Shown as text only with `show` (default off).
- Drawing: white plate, dark modules at whole pixels, 2-module quiet zone plus padding, whatever the theme. Measured on emulator-5554 (1920x1080): two widgets, each ~260 px code; zbarimg read both from the screenshot.

## TV Home "Manage from phone" (its own settings page)
- One QR in Customize opens TV Home's settings in the phone's browser; the UI is TV Setup's `home.js`, with one source: Gradle `packHomeWeb` copies the needed js/css, an i18n subset (`home.` `homeweb.` `common.` `core.` `apps.`) and home.html (icons sprite from index.html) into `assets/homeweb`. TV Home still has no :shared and no dependency on TV Setup.
- `home.js` asks `homeLink` (js/home_link.js): host = `/api/home*` over ADB, direct = TV Home's `/h/*` (config, photo, photo/drop, app/meta, app/icon). Same HomeConfig rules run on the TV either way (`HomeApi`, shared with `HomeReceiver`). Direct has no wallpaper gallery and no update button (null routes), and `dream` still needs the ADB grant (SecurityException: applied without it).
- `ManageServer` (ServerSocket, port 8790 or any free) runs only while the QR screen is open. `Access`: key in the QR works once, then a cookie session (60 min, HttpOnly, Strict); no session = 401; 30 wrong keys void the key; posts need `X-Tvh`; the server stops after 10 min with no authorised request (one Handler timer, the page does not poll) or on close. Clocks are `nanoTime`.
- Replaced `PhotoServer`/photo.html: the photo and web-page QR screens now use the same server and page.

## Opening an app from TV Home (`Zoom.kt`) and updating the apps under it

- Measured on the Google TV emulator (screenrecord, brightness per frame): opening TV Games cold showed ~0.6 s of black.
  It was our own plate: it darkened to near black and the tile's picture faded after 150 ms, then the app's starting window
  (`Theme.Black` there, black in most TV apps) took over. Now the picture keeps its size, glides to the middle and pulses
  until the app draws. YouTube still shows ~0.3 s of its own black starting window over it: a launcher cannot cover
  another app's window. `SPLASH_SCREEN_STYLE_ICON` (Android 13+) is asked for but changed nothing on the emulator.
- "Zoomed, then the zoom went, Home showed, then TV Games": `left` (Home paused since the launch) was reset when the
  growth ended (380 ms), after Home had already paused (~250 ms). On an app that takes >3.4 s to show (a cold start after
  an update, a slow TV), the 3 s guard took it for a failed launch and shrank the plate. Reset before the launch now.
- TV Games closed under the person a moment after opening: TV Setup's AutoUpdate checked that it was not on the screen,
  then pushed and installed for several seconds; opened in between, the install closed it. TV Channels and TV Games are
  now updated by it only while the TV is in standby (their own SelfUpdate still asks in the app).
- **On a weak TV (FPT Play box: 4 cores, 32-bit, 1.8 GB, ~170 MB free; logcat 2026-10-23):** TV Games cold start 2.66 s,
  TV Setup 2.13 s, TV Channels 0.89 s, Stremio 5.56 s; Home back from an app skipped 68 frames. So: (1) the pulse repainted the
  whole plate (bitmap + clipPath) 60 times a second while the app it waited for was starting; now the picture is an ImageView in
  a layer, clipped by its outline, and the pulse only changes its alpha / scale (no drawing), after 350 ms. (2) The app is
  started at 25 % of the growth, not 60 % (~140 ms sooner). (3) The shrink on the way back ran on Home's thread just while Home
  redrew itself, and lost all its frames ("no zoom out"): gone. The plate disappears before Home's first frame and the system's
  own crossfade brings Home in. A launcher's `windowAnimationStyle` is ignored for this on Android 14 (a test 1.5x scale over
  1.5 s never showed on the Google TV emulator), and shrinking the app's own picture is not possible: only the system's launcher
  may take another app's window. `SPLASH_SCREEN_STYLE_ICON` was dropped: it changed nothing.
- The pickers over Home ("Add to home screen", "Quick keys") are a solid page, not a panel over a dimmed, see-through Home: the window
  over Home and the Customize panel was blended again on every frame of a focus move. Emulator, 12 D-pad moves, 3 runs: 14-26% late
  frames (median 31-36 ms) as before; dropping only the gliding glass changed nothing (48% → 48% in one run); solid window, no dim,
  plain panel: 6-11% (25 ms). Home's own grid: 0.4% (16 ms). `solidPage()` in keys/Picker.kt; `TvTile.glassy` false keeps the ring.

## The stock launcher wins over the HOME choice (BRAVIA, Android 9)

- **Measured on a BRAVIA 4K GB ATV3 (Android 9), 2026-10-23:** with `com.google.android.tvlauncher` on, `cmd package set-home-activity dev.tvremote.home/.HomeActivity` prints `Success`, yet the HOME resolver still names tvlauncher and the Home key opens it (twice in a row). With tvlauncher off, Home lands on TV Home. Google TV's launcherx does the same (a Coocaa, the GTV emulator).
- **The list (`Setup.LAUNCHER`) also has leanbacklauncher and Xiaomi's `com.mitv.tvhome`**, from cleanyourtv's `KNOWN_STOCK_LAUNCHERS` (their contributors' TVs); Undo's unrecorded "legacy" set stays the two Google ones.
- **So every way of choosing another launcher runs the whole `LauncherSwap`** whenever any of `Setup.LAUNCHER` is on (`Manage.homeSet`, `stockOn()`), not only with launcherx; the role alone is used only when none is on. `Reoptimize.homeBack` falls back to `take()` when set-home-activity does not stick after a firmware update.
- Android 9's Apps key / long-press Home goes to the system-only HOME resolver (`PhoneWindowManager.launchAllAppsAction`, MATCH_SYSTEM_ONLY): tvlauncher's apps view while it is on, whatever the home. Turning it off covers that too.
- **The Apps key (284) with nobody answering `ACTION_ALL_APPS` is a dead key** (`KeyFix.keyMap` / `withAllApps`): it is not in `global_keys.xml`, so the table could not tell; it gets the HOME rule (TV Home) and joins TV Home's free keys. Checked by asking the package manager, not by brand: with tvlauncher on, its apps view keeps the key. Long-press Home is left alone (on Google TV it is the dashboard). `input keyevent` is injected after the key filter, so on the emulator it was checked through TV Home's Quick keys list ("All apps key" offered), not by a press.
- **TV Home cannot change the home screen itself**: it has no ADB, `HOME_SETTINGS` / `MANAGE_DEFAULT_APPS_SETTINGS` are stubs on the BRAVIA and the emulator, the role dialog is Android 10+, and a stock launcher left on wins anyway. Its "Home screen" entry starts TV Setup with `open=launchers`, which opens the picker once ADB is up.
- **setupwraith goes off whole where its Home screen cannot (cleanyourtv's way, 2026-10-23).** Real Google TVs refuse `pm disable` of one
  component from the shell, so every swap there used to roll back ("home"). Now `LauncherSwap.take` turns the whole package off, first
  in the record (rollback turns it on last: back before launcherx, its RecoveryActivity takes Home; a reboot there factory-reset a
  Xiaomi MiTV-MOSR4), but only when `WIFI_OWNERS` names no network of its own (`cname=<pkg or its uid>`): an FPT Play box lost its
  network and ADB that way. Measured: a BRAVIA (Android 9) prints `ID: n SSID` lines + `cname=android.uid.system:1000`, the FPT box
  (Android 14) the same `cname=` form; both emulators only the `WifiConfigManager` header (no saved network, no setupwraith). No dump,
  or networks without owners → "wifi_unknown", never a guess. Cast hosts (`ro.product.device` sabrina / boreal / kirkwood) → "cast"
  (phones' casts fail "Untrusted device" there). Not run on a device with setupwraith yet: LauncherTest's fake TV covers the paths.

## Customize opens lazily; no second hand (`Customize.kt`, `widgets/Widgets.kt`)
- Measured on the FPT box (217 MB free RAM, swap in use), key to first frame: all seven pages built in `show()` took 0.9–1.6 s; building only the top level and the rest one page a message after the slide-in takes 0.33–0.57 s (same logging, 5 runs each, 2026-10-23).
- `Store.get` costs ~7–14 ms a call there; `refresh()` read it once per row, so it reads it once. A memo of the cleaned conf barely moved the total and was dropped.
- The analog clock's smooth second hand redrew Home ~30 fps (125 frames in 5 s idle) on the same UI thread as any panel over it. Removed; the face redraws on the minute.
- Panel 460 dp, not 600: the rows' text uses under half of 600. Tile titles take 2 lines so nothing is cut at the narrower width.
- Still ~470 ms from the menu row's click to the slide (FPT, timestamps per step): building the top level ~300 ms (seven group rows ~100-190 ms, `Store.get` ~15 ms x2, the photo list ~17 ms), then window + first frame ~140 ms. Now `Customize.prepare` builds the panel on an idle handler 3.5 s after Home starts and 1.5 s after each close; the click only shows it: 170-240 ms. Pages still fill on first use and `refresh()` runs at show, so a change made meanwhile (a phone, a sent photo) is in it. The slide's start offset is set at show, not at build: motion may have been turned off since.
- Reopened on a page (`show(at = "lang")` after a new language rebuilt Home): no slide, the page is simply there with its choice focused. Language is a list (each in its own words, the one in use ticked), not a tile that stepped through them and rebuilt Home on every press.

## A dialog taller than the screen squashes its last row (`TvUi.sheet`, `PhotoScreen.kt`)
- Android caps a WRAP_CONTENT dialog window at the screen height; the content is then laid out short and the last child (the Close/Done button) is squashed until its text vanishes. The FPT box is 540 dp tall; the web-page QR screen measured taller.
- `TvUi.sheet` wraps its rows in a ScrollView (every TV Channels/Games sheet). `PhotoScreen` keeps Close outside the scroll, pinned under it, and lays out in two columns: the QR alone on the left (at most 45% of the width, shrunk to what the height leaves, min 160 dp), words and choices on the right. The QR is drawn unfiltered so the scaled squares stay sharp. On the FPT the QR went from 100 dp (web) / ~195 dp (manage) to ~250 dp.
- Already scrolling: Customize, QuickKeys, Picker, TvGlass.menu, TV Setup's MainActivity. `ui/Drop.kt` has at most 6 rows: no scroll.
- Top-bar menus (`Drop`) hang from the capsule, not from the whole bar: the bar's height includes the focused button's word under it, which left a 38 dp gap. Dim 0.45 (0.15 left Home as bright as the menu). Customize opened from that menu dims the same (`Drop.MENU_DIM`), else the light jumped back between them; its preview pages (bg, look, widgets) set the dim to 0 (not layout: its rows / columns open a stepper of their own with the panel hidden) so a veil or a background is judged as it will be.

## Settings are cleaned once (`Store.kt`)
- `Store.get` parsed and cleaned the saved JSON (~7 ms on the FPT box) and added the live reports (`effective`, ~6 ms) on every call; `Store.list` paid both for one array. The picker called it per tile (~370 ms of UI thread), Quick keys per key (~400 ms).
- Now the cleaned copy is kept until the text in prefs changes (string compare: any writer, even another process's prefs, is seen). `list` reads it without `effective` (dock, hidden, order, tiles, widgets are all saved keys): ~0.1 ms. `get` still returns a fresh copy with the reports, so callers may change it.
- The picker also reuses Home's loaded apps and banners (copies via `constantState`, so the Home grid's drawables keep their bounds).
- The picker's tiles cost ~10 ms each to make on the FPT box. The first three rows are made on the picker's thread (views not yet on a window, as AsyncLayoutInflater does) and drawn alone; the rest is added a frame later, focus kept. Making all of them off the UI thread (~180 ms) took longer than the wait it saved. First rows on screen: ~0.25–0.3 s, from 0.75–1 s.

## Focus without growth on the Customize pages (`Customize.kt`, `PickTile`)
- Measured on the FPT box, 24 D-pad moves: the picker's growing tiles 8.3–8.5 s of CPU, ~615 frames (50th 32 ms); the ring alone 1.1–1.3 s, ~27 frames. Customize's rows (1.06 pop) 5.3–5.5 s, ~533 frames; the lighter background alone 0.7–1.1 s, ~29 frames. Idle: 0 frames, 60 ms in 10 s.
- So these pages show focus by colour and ring only. Home's grid keeps its growth (the look of the launcher, one tile at a time over a still page).

## TV Home scroll: tiles at rest are layers (`TvTile.card`, TV Setup `DimLayer`)

Measured on the FPT box (Mali-G31, Android 14, 50 Hz, the person's 25% Dim on), DOWN/UP between the dock row and the next, focus on a tile:
the app delivered a frame every vsync, but SurfaceFlinger held a frame (2 vsyncs, then one skipped) in 3-10 presses of 8, always in the first
6 frames, where the scroll is fastest. Sideways moves (same focus animation, no scroll): 0-1 in 8. The box composes every layer with the GPU
(`CLIENT` in `dumpsys SurfaceFlinger`, even a lone app), so the Dim window's full-screen blend shares the GPU with the app; with the Dim
hidden for a minute the same scroll held 0-1 in 8. The Dim stays (the person chose it): the app had to cost less.
App GPU per scroll frame (framestats `GpuCompleted`), p50 / p90 ms: as it was 17 / 38; Frost not invalidated, no text shadows, no widget
layer, the web wall: no change; one layer for the whole grid 48 / 70 (re-rendered every frame by the focus animation inside); the
wallpaper a solid colour 15 / 27 (at half or quarter size: no change, the cost is the full-screen pass, not the bitmap); no rounded
clip on the faces 15.5 / 28; **a hardware layer per card 12 / 16** (presses with a held frame: 1 in 8). The focused card has none: a
layer scaled by GROW is soft, and its animation runs `withLayer()` as before. Cost: about 11 MB of render targets for TV Home's 27.
`DimLayer` on a TV is MATCH_PARENT now: twice the long side each way (for phones that turn) was a 3840 x 3840 buffer for one colour; it
did not change the composition (`CLIENT` either way) nor did moving its alpha into the colour.

## TV Home's settings page: groups, one save for the order, a session that outlives its QR (`js/home.js`, `js/home_order.js`, `Access.kt`, `WebCheck.kt`)

- **Groups as on the TV** (`GROUPS` in home.js = `Customize.kt`'s seven): everything on one page was a long scroll, and the person setting it up already knows the TV's groups. Each row shows a summary (`sums()`) so most questions are answered without opening it.
- **The app order is saved once.** A write per move made TV Home rebuild its grid at every step and the page wait for each answer. The grid is now a preview (`homeGridOrder` = HomeActivity.show's order: old dock first, then `order`, the rest A-Z); Save sends `{dock: [], order: all}`.
- **Drag listens on the window, not with pointer capture.** Moving the dragged node in the DOM (`before`/`after`) drops its pointer capture: in Chrome the drag stopped after one step and the pointerup went elsewhere (tile stuck "lifted"). Measured on the FPT's page in Chrome 2026-10-24. The app pictures are `<img>`: `dragstart` is cancelled and the img gets `pointer-events:none`, or the browser's own image drag takes the pointer.
- **Closing the QR keeps phones that came in** (`ManageServer.release`, `Access.closeKey`): the person closes the QR to look at the TV while still setting it up. The key stops working (a new QR = a new key, `issue(keep = true)` keeps the sessions); idle 10 min still ends it.
- **The web background is tried where it will be shown** (`WebCheck`): TV Home's own page asks the TV (`/h/web/check`), so a server on the home network that the phone cannot see is judged by the TV; host pages ask from the host (`/api/home/web/check`). FPT measured: dns 173 ms, refused 36 ms, 404 / 200 + title 0.4-0.8 s. A failed check still allows "Use anyway" (a page needing a login on the TV).


## TV Home compiled ahead (`Home.kt`, `LauncherInstall.kt`, `Setup.kt`, TV Setup `HomeCompile.kt`, 2026-10-24)

- Every install by a client runs `cmd package compile -m speed -f dev.tvremote.home` over ADB. TV Home's own "Update now"
  (Android's installer, no shell) left it at `status=verify` until Nightly: TV Setup now listens for PACKAGE_REPLACED of
  TV Home and compiles it 15 s later (no `-f`: skipped when a client already did).
- FPT box (Android 14, release APK): HOME cold start 1332 / 457 / 595 ms at `verify` -> 413 / 420 / 392 ms compiled; the
  compile took 1 s. Android 14 reports `speed-profile` after `-m speed`. A debuggable APK stays at `verify` whatever is
  asked (the emulator check only proves the command ran: `reason=cmdline`).
- A dev `adb install -r` of TV Home is not compiled (no client ran it): measure speed only after a compile.

## Launcher gallery opening (`MainActivity.chooseLauncher`, `Launchers.fetch/thumb`, `SignedList.kept`, 2026-10-24)
Measured on emulator-5554 (TV Setup, open → list drawn): 2.5–3.6 s before, ~0.4 s (0.33–0.73) after.
- The 13 thumbnails (960x540, ~0.9 MB) came one by one (~4 s on a Mac link) and the list waited for all: now the list is drawn first, pictures come 4 at a time into their cards, decoded with inSampleSize to the card's size, and are kept in memory + `cache/launchers/<sha>.jpg` (hash checked again on read).
- The list itself: launchers.json and .sig were two sequential round trips (`SignedList.fetch` now gets the .sig alongside), and the network was waited on at every opening (~1 s): a kept signed copy is answered at once and the site read 2 s later at low priority for the next opening. Installs still check the APK hash pinned by the list that was shown.
- Ed25519 in BigInteger costs ~0.5–1 s per check on Android: `SignedList.kept` checks a kept copy once, then writes `<file>.ok` = SHA-256(list, 0, sig) and compares only that. The signature guards against a taken-over site or repository; whoever can write the app's private files can change the app anyway.
- `Launchers.facts` asks its six questions in one shell round trip; facts, `pm list packages` and the home lookup run side by side (Adb multiplexes streams on one connection).
- Not measured on a real box: the FPT box runs a release-signed build, a debug install would need an uninstall (its rules lost).

## TV Home: video and GIF backgrounds (`app/tvhome` wall/Videos.kt, Transcode.kt, VideoWall.kt)

- **Library, not a file:** `<ext files>/videos/<hex8>.mp4` + `<hex8>.jpg` (poster, first frame) + `index.json`; the wall id is `vid:<hex8>`. `Walls.file()` gives the poster, so the screensaver, blurred cards and thumbnails need no change. `Videos.delete` refuses a video the setting uses or any `Videos.refs` hook (themes) keeps. Max 200 MB sent, 12 kept, free space >= 2x the file + 100 MB.
- **Player:** platform MediaPlayer + SurfaceView behind the grid (no library; APK 2.0 MB before and after; TextureView was the first version, see the A/B below). The poster is the window background plus an ImageView over the SurfaceView's hole, so no black flash; released in onStop (an app over it: player count 1 -> 0 on the emulator), restarted in onStart. Looping gap not measurable on the emulator.
- **Made light on the TV** (MediaCodec + EGL + MediaMuxer, no Media3: its APK cost was not worth it): a GIF always; a video when this TV has no hardware decoder for it, it is bigger than the chosen size, over 8 Mbps, or faster than the chosen frame rate (or than the decoder does at that size, with "Original"). Output H.264, ~0.065 bit/pixel/frame (1080p30 = 4.0 Mbps, 720p30 = 1.8), 2 s GOP, no B-frames. Frame rate (Original / 60 / 30 / 24 / 15) and size (screen / 1080 / 720 / 480) are settings (`vfps`, `vres`), applied to the NEXT upload. **Originals are not kept**: a 200 MB original would eat a weak box's storage twice; to change it, upload again.
- **Measured on emulator-5554** (Android 14, 1080p; decoder `c2.goldfish.h264.decoder`; 10 s windows, D-pad scrolling the grid; emulator, not a weak box: only relative):
  | wall | file | TV Home CPU | codec2 CPU | PSS | first frame | janky (scroll) |
  |---|---|---|---|---|---|---|
  | picture | - | 0.2 % | 0 | 72 MB | - | 6.5 % |
  | (a) 1080p60 12 Mbps B-frames, as sent | 31 MB / 20 s | 27.6 % | 61 % | 72.5 MB | 93 ms | 8.0 % (616 frames) |
  | (a) made light by the app, 1080p30 | 15 MB | 21.2 % | 42 % | 72.8 MB | 104 ms | 3.0 % |
  | (b) 1080p30 4 Mbps | 10 MB | 5.6 % | 23 % | 72.4 MB | 74 ms | 25.5 % |
  | (c) 720p30 2 Mbps | 5 MB | 6.6 % | 23 % | 72.6 MB | 106 ms | 32.5 % |
  | (d) 1080p30 4 Mbps GOP 300 | 10 MB | 7.6 % | 30 % | 72.8 MB | 92 ms | 33.5 % |
  | GIF 480x270 15 fps | 1.3 MB -> 0.70 MB | 7.0 % | 23 % | 65 MB | 88 ms | 28.3 % |
  | GIF 800x450 | 5.6 MB -> 1.9 MB | 5.2 % | 20 % | 58 MB | 91 ms | 26.7 % |
  The "janky" figure counts every video frame the TextureView hands the UI, so it is not smoothness of scrolling by itself (it varied 25-33 % across the 30 fps files and ~3-8 % on the others with no pattern); do not read a winner from it. What is solid: 60 fps / 12 Mbps costs 3-5x the CPU of 30 fps / 4 Mbps; transcoding a 60 fps source to 30 fps cut CPU 21 vs 28 % and codec 42 vs 61 %; a GIF as mp4 is 2-3x smaller and decoded in hardware; memory is flat (+0-12 MB PSS vs a picture). Qemu itself (host CPU) with a 1080p30 video: ~890 % vs 5.6 % with a picture and ~1 % with an app in front: the emulator decodes in software on the host, so run a video background only in 10-60 s windows (it heated the Mac to 91 C).
- **SurfaceView, not TextureView (A/B 2026-10-24, emulator-5554, 28 s windows, same clips, TV Home CPU = % of one core):**
  | clip | view | TV Home CPU | frames Home drew | PSS | first frame | host qemu CPU | self-measured |
  |---|---|---|---|---|---|---|---|
  | 1080p30 4M | TextureView | 5 % | 852 | 40.7 MB | 77 ms | 470 % | 30.8 fps of 30 |
  | 1080p30 4M | **SurfaceView** | 0-1 % | 10-14 | 39.4-39.8 MB | 70-84 ms | 279-309 % | 30.8 fps of 30 |
  | 720p30 2M | TextureView | 6 % | 852 | 40.5 MB | 101 ms | 461 % | 30.8 fps of 30 |
  | 720p30 2M | **SurfaceView** | 1 % | 12 | 39.6 MB | 69 ms | 286 % | 30.9 fps of 30 |
  | 4K60 30M (stress) | SurfaceView | 2 % | - | - | 129 ms | 776 % | 60.7 fps of 60 |
  The TextureView makes Home draw a frame for every video frame (852 in 28 s, 98 % "janky" in gfxinfo: the count is of video frames, not of lost smoothness) and a GPU copy; the SurfaceView hands the frames to the compositor and Home draws only what changes (the 10-14 frames are its own UI). Surfaceflinger stayed at 0-2 % in both (the emulator composes on the host, so qemu is where the difference shows; qemu itself decodes in software: it is the emulator's cost, not a TV's). MediaCodec direct was not tried: MediaPlayer already gives 1 % CPU, a codec loop would only add code. Checked on SurfaceView: the veil and the whole grid draw over it, translucent cards show the video through them (screenshots in .cache/shots/tvhome_video_surface*.png), and leaving Home for Settings and coming back shows a frame at 0.4 s, no black (a poster ImageView, sharing the window background's drawable so no second bitmap, covers the SurfaceView's hole from `play()` to the first frame and again whenever the player is let go).
  **Why not what Backdrop's comment says** (a BRAVIA with a SurfaceView behind the window blended two full-screen layers on its GPU: 45 ms a frame): that was a picture wall on a SurfaceView plus the window's own layer, on a video wall there is one decoder layer and the window, which is what hardware composers do best. It could still be a box with a single overlay plane; the self-measurement below is the net for that (a slow box falls back to the poster), and `VideoWall` is the one place to change if a real box shows otherwise. Not checked: any real box.
- **TV Home measures itself** (`VideoWall` + `Videos.Verdict` + `Videos.measured`): 20 s (`Verdict.WINDOW_MS`) after the first frame of each start, `MediaPlayer.metrics` (`android.media.mediaplayer.frames` / `.dropped`, Long values: `getInt` returns 0) give frames shown against the clip's own rate, plus this process's CPU (`Process.getElapsedCpuTime`, % of one core: the decoder's own work is in the media process, so this is Home's side only). One `postDelayed`, removed in `release()` (Home stopped, screensaver, another app): nothing runs or measures when Home is not on screen. Verdict (pure, `VideosTest`): shown/wanted >= 0.9 "ok", >= 0.6 "choppy", under it "slow"; two slow in a row for the same video (`slow` count, new start or after the app in front) sets `off` and Home shows the poster alone (`attachVideo` skips an `off` video). Choosing the video again (`Store.change` with `wall`, also the rotation coming back to it) forgets the record and gives it another try. The last record is in prefs (`vperf`) and rides in the snapshot as `video.perf {id, fps, want, cpu, at, level, slow, off}`; the web card (`homePerf` in `js/home.js`) writes one line from it. Seen on the emulator: ok (31 fps / 30), and with the thresholds temporarily raised: slow, then `off` after the second start, player count 0, qemu 3-9 %, the poster on screen. The emulator could not be made slow (4K60 and 1080p60 12 Mbps both ran at full rate, 6 busy guest loops changed nothing: the decoder runs on the host), so a real slow box is not seen.
- **Real TVs (2026-10-24, the self-measure's numbers):**

  | TV | clip | upload / make light | shown | TV Home CPU |
  |---|---|---|---|---|
  | FPT Play 650 (Realtek, 4 cores, 1.8 GB, Android 14) | 1080p30 4M | kept as sent | 30 of 30 | 14 % |
  | FPT Play 650 | 1080p60 12M | 4 s / 92 s -> 1080p60 6 MB | 56 of 60 | 29 % |
  | BRAVIA 4K GB ATV3 (MTK, 2 GB, Android 9) | 1080p30 4M | kept as sent | 30 of 30 | 11 % |
  | BRAVIA | 1080p60 12M, before the fix | 3 s / 95 s -> **27 Mbps of broken frames** (green smear on screen, MediaPlayer error) | - | - |
  | BRAVIA | 1080p60 12M, after | 3 s / 71 s -> 720p30 1.8 MB, 0 decode errors | 30 of 30 | 7 % |

  The BRAVIA's MTK encoder asked for 1080p60 at 6 Mbps ignored the rate and wrote frames ffmpeg cannot decode; nothing checked.
  Now (`Videos.outFps`, `Transcode.sane`): a made video drops to 30 fps when this TV's encoder or decoder says it cannot do the
  rate at that size; what comes out must be near the bitrate asked (2x + 1 Mbps) and decode its first 60 frames, else it is made
  once more at 30 fps / 720p, else the upload fails. On the BRAVIA 1080p30 was refused too, 720p30 was kept. Backdrop's
  45 ms/frame did not show on this BRAVIA with a SurfaceView video: 30 of 30.
- **The loop (2026-10-27): no sound, a second player.** The FPT box stood still for seconds at each turn of `isLooping`. Measured on
  emulator-5554 (screenrecord, gaps between frame timestamps; Pexels fireplace 1080p30 with an AAC track): `isLooping` muted 0.45 s a
  turn (one frame, then 184 + 273 ms of nothing); the same file without its audio track 0.05 s (one frame). The platform player paces
  the picture by the audio clock even at volume 0 and waits for the audio to start again at each turn. So: a wall has no sound at all
  (the user: "wallpaper chỉ cần hình", `vsound` / `vvol` removed) and plays a copy without the audio track (`wall/Silent.kt`:
  MediaExtractor -> MediaMuxer, seconds, no codec, cache keyed by path + size; the first turn plays the file as it is). Then a second
  MediaPlayer is prepared 4 s before the end (`VideoWall.LEAD_MS`) and takes the surface on completion: completion -> first frame 57-73 ms.
  After: a site video (`NetVideo`) 0.05 s a turn, a library video (720p, B-frames) 0.13-0.14 s. A web wall's video is kept as a file
  after its first play (`wall/NetVideo.kt`, 80 MB max, 3 kept): streamed again on each return to Home, a weak box waited on the
  network at each start.
- **The loop again (2026-10-31, FPT box, release build, SurfaceFlinger `--latency` of the video layer, 6 s clip, 4 loops):** the second
  player held the last frame **270-300 ms** a turn (log "loop gap" 264-317 ms; 300 ms between presented frames against 40 ms normal).
  Inside the turn: `quit` of the old player 64-73 ms on the main thread, `setSurface` + `start` 2-4 ms, the rest (~200 ms) is the new
  decoder's set-up: a player prepared with no surface cannot configure its codec, so "ready before the end" only parsed the file.
  Neither part can be hidden. `isLooping` on the **audio-less copy** ([Silent]) keeps codec and surface and only flushes + seeks:
  gaps **60-100 ms** (site video, kept file), 60-120 ms (library video); the emulator's 0.05 s agrees. So the second player (`armNext`,
  `turn`, `LEAD_MS`) is gone and a file loops by itself. A stream's first turn (a net video before [NetVideo] has its file; it has
  sound) is not looped: at its end `play()` starts again and takes the kept file's silent copy (one 300 ms hold, once; measured with
  the cache cleared). Two SurfaceViews ping-ponging could go below 60 ms; not built (two layers on a box that may have one overlay
  plane, to win two frames).
- **The loop, third time (2026-10-31, FPT box, release build): no MediaPlayer for a file.** The ~100 ms of `isLooping` (flush +
  seek, the Realtek decoder refilling) was still seen by the user. [Loop] feeds MediaExtractor's samples to one MediaCodec round and
  round: at the end the extractor is sought back and the times moved on by one clip, so the decoder never sees an end, a flush or a
  seek. SurfaceFlinger frames of the video layer (3 s windows over several turns): city 1080p30 (kept site file) and a 12 s 720p30
  library clip with an AAC track: longest gap **50 ms** in every window (one frame of 30 fps on the 50 Hz screen), was 100-117 ms.
  Only the video track is read, so [Silent] (the audio-less copy) is gone. CPU moved, not grew: mediaserver 18.8 % + Home 5.2 %
  before, Home 19.5 % (its `MediaCodec_loop` / HwBinder threads, the feeder 2.4 %) + mediaserver 0 now; the self-measure counts
  [Loop]'s own shown / dropped frames (601 of 601 in 20 s). Emulator: plays, gaps 50-119 ms everywhere (the software decoder under
  load), not at the turn only. Streams (a link's first turns) still use MediaPlayer, not looped.
- **A web wall's link is checked like an upload (2026-10-31, FPT box, release build).** Its decoder (`c2.realtek.video.avc.decoder`)
  says 4096x2176 at most, 4K30, 20 Mbps; the screen's mode is 1080p50. SurfaceFlinger frames of the video layer, 6 x 3 s:

  | link (12 s fireplace) | as it was | now |
  |---|---|---|
  | 720p30 2.5 Mbps | 30 fps, mediaserver 26 %, Home 4 % | the same (judged light) |
  | 4K30 H.264 20 Mbps / HEVC 12 Mbps | 30 fps, mediaserver 24 / 18 % | not streamed: made 1080p first |
  | 4K60 H.264 35 Mbps | **green and brown stripes over the whole screen**, 6 frames then none, no player error | 51 s on the box -> 1080p30, 30 fps, longest gap 50 ms |

  Nothing said so: the player gave no error, so the WebView fallback never ran. Now [NetVideo] probes the link's header
  ([Videos.probeUrl], ~0.7 s once, before the first frame) with the same [Videos.heavy] as an upload: light = streamed as before;
  heavy = never streamed, the file is fetched and made light ([Videos.makeLight], the upload's code) before it is kept, the
  poster (or the gradient) shows meanwhile. Too big to keep (over 80 MB) and heavy: never played, the poster stays (better than
  stripes; a link of a whole film is not a wallpaper). No warning to the person: it is made light by itself, as an upload is.
  Testing with a local server: Python's `http.server` has no Range, and MediaPlayer then fails on a file with its moov at the end
  or with audio ("Failed to init from data source", -1004): a fault of the test server, not of 4K.

## TV Home themes (2026-10-24)

- A theme is a cut of the settings that already exist (`HomeConfig.THEME_KEYS`), kept on the TV in the config as `themes` and changed by ONE field every client sends, `theme: {apply|save|del}`, resolved in `HomeConfig.merge` on the TV. So the page, the phone, the computer and the QR page needed no new route, and the logic is written once (Kotlin).
- Left out on purpose: the apps (dock, hidden, order, tiles), QR widgets (a Wi-Fi password), keys, language, screensaver, power button: they are not a look. Applying keeps the QR widgets in `widgets` / `wsize`.
- A saved theme keeps its photo or video: `Videos.refs` (set in `HomeApp`) makes the library's delete and the photos' delete refuse (409 `inuse`). The cost is that a photo must be freed by deleting the theme first; the other way round (copying the file into the theme) would double the TV's storage.
- "Which theme is on" (`themeNow`) compares the look as sorted-key text, not JSON text: org.json keeps key order on Android and not on the JVM tests.
