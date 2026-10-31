# Decisions: How the apps fit together

Why some code looks odd ([the list](../DECISIONS.md)).

## The apps leaning on each other

- **TV Home has no ADB**: its power button (screen off, standby, power off) asks TV Setup through the `HomeInfo` provider
  (signature-checked) with one of three fixed words, never a command line (`setup/home/HomePower.kt`). When TV Setup's service
  is not running, HomePower opens a link of its own on the same key and closes it after; the screen-off helper is
  `nohup setsid`, so it outlives that link.
- **A feature that needs another app stays visible and says how to get it** (Downloader code or "install from the phone"),
  at the point of use only: no banners. Hiding it reads as a bug. The map of who needs whom: `docs/` apps page.
- **"Open on TV" sends a media link (.m3u8, .mp4 … by the path's extension, `Channels.isStream` = `js/remote.js isStream`)
  to TV Channels' player**: most TVs have nothing that opens a bare stream. Without TV Channels it goes as any link, with a hint.
- **Heavy-app auto stop ("One heavy app mode") was dropped, measured 2026-10-25** on an FPT Play 650 (1.8 GB RAM, 520 MB available on Home):
  a YouTube left behind held only ~38-45 MB of MemAvailable and a force-stop gave back 45 MB, but the next launch became a cold start of
  1216 ms instead of a warm 159 ms (7-8x slower); Android's low-memory killer frees that memory by itself when it is needed. A stored
  `heavy` key in a TV's rules is simply ignored. `sharedUserId` to share one process between our apps was rejected: adding it to shipped apps breaks updates.
- **"Install as a separate app" (`core/ontv/AppCopy.kt`, `Axml.kt`) renames the package to `<pkg>.copy`, signed with ONE key per client** (`appcopy.key/.crt`
  beside the ADB keys, made once): a built-in system app cannot be replaced or removed, so the only way to run another build is another package.
  `.copy` is a fixed suffix so a later copy of the same app has the same name and, with the same key, updates over the first. The key is never a
  release key of this repo. The manifest is edited as binary (new strings appended to the pool, attributes repointed), not with apktool; authorities
  and the app's own permissions follow the name (else CONFLICTING_PROVIDER / DUPLICATE_PERMISSION), class names do not. resources.arsc stays.
  Limits: .apks / .xapk are refused; an app that checks its own signature closes at once (started and checked after 5 s, then Remove is offered).
  apksig (Apache-2.0, ~0.5 MB) is in :shared; its Android 8+ behaviour was not run on a phone.
- **Key shortcuts and "open an app at power-on" (TV Home settings, default off) are done by TV Setup, pushed by TV Home.** TV Home has no
  ADB, no key filter, and does not run in standby, so it sends the whole setting (`keyon/keyapps/autoon/autoapp`) through the
  signature-checked HomeInfo provider (method "keys") on every change and start; TV Setup keeps it (`HomeKeys`, prefs) and works after a
  reboot with TV Home not up. The DUMP-protected Rules broadcast was not used: apps cannot call it. Without TV Setup the keys work on
  TV Home's own screen only, and the setting says so.
- **Only free keys can be given an app:** colour keys, keys whose global component is dead (`KeyFix.plan`, same list in `HomeConfig.KEY_DEAD`,
  a test keeps them equal) and, on TV Home's screen only, 0-9. A working key is never taken. `input keyevent` over ADB is injected after
  the accessibility filter, so the service cannot be tested with it; on the TV emulator `adb emu event send EV_KEY:KEY_RED` also does not
  reach it (the emulator has no such key in its layout path), so the service path was checked by its logged plan only.
- **A key a client sends is decided by TV Setup, not injected:** measured on the TV emulator (5554, RED given an app, 178 dead): `input keyevent`
  183 / 178 while Settings was in front did nothing, so a phone / computer that injects a colour key or a dead YouTube / Netflix / Prime /
  All apps key reaches no one. With ADB these keys (`RulesClient.SETUP_KEYS`) go as a `RulesReceiver` broadcast `{"do":"key","code"}`; TV Setup
  answers `{"acted"}` after `KeyFix.decide` (TV Home's map laid over the dead keys' rules, the same plan the filter uses) and opens the app; not
  acted, an old TV Setup (answers "unknown action") or no ADB: the plain key as before, no error. The TV web page decides in-process. LIVE / INPUT
  stay on their old path (an extra broadcast ~0.3 s on every press for a plan that is mostly empty).
- **Power-on app fires once per power-on, by a 120 s guard** keyed on (boot count, elapsedRealtime): the boot and the screen coming on right
  after it are one power-on. It looks at the app on screen (UsageStats) and keeps it when the TV woke into another app (HDMI-CEC, cast).
  Home pressed later never re-fires: the trigger is a power event only. No `SetupNeed.MIN` entry: the setting asks TV Setup itself and
  says "install TV Setup" when it gets no answer; the first TV Setup with "keys" is the next one.

## The error guard (`app/shared/src/guard`, `js/errbox.js`)

- **Why:** a NullPointerException in `PhoneRemote.remoteMenu` from one menu tap killed the whole phone app, and nothing said what
  broke. Goal: no crash, and when something fails the error is on the screen, easy to copy.
- **The main looper is kept alive** (`Guard.install` posts `while (true) { try { Looper.loop(); break } catch ... }` to the main
  thread): a throw inside a click, a posted job or a callback ends only that message, the screen is still drawn and the person
  sees a dialog (title, first 6 stack lines, Copy / Share / Close; the TV's D-pad starts on Close). Other threads: saved and
  shown, the process lives.
- **It is NOT kept alive** when the stack has an Activity lifecycle frame (`ActivityThread.perform*/handle*Activity`,
  `Activity.performCreate`, `TransactionExecutor`; `GuardLogic.inLifecycle`, causes included): an Activity that threw in
  `onCreate` / `onResume` stays half built, a blank screen nothing ever draws into, and the looper would carry on with a window
  that cannot work. There the trace is saved, the app dies as before, and the next start shows "Lần trước app bị lỗi" once
  (`guard-died` flag + `guard-last.txt` in filesDir). Also left to die: 10 throws within 2 s on the main thread (the loop is not recovering).
- Same error again while the dialog is open: counted ("repeated n times"), never a second dialog. The web page does the same
  (`ErrBox`), and ignores resource load errors, the cross-origin "Script error." and the ResizeObserver notice.
- Checked on the emulators (phone, TV Home): a throw on the main and on a background thread left the pid unchanged and showed
  the dialog. A debug-only trigger stays in `Guard.install` (`am broadcast -a dev.tvremote.GUARD_TEST --es on main|bg`, only when
  the APK is debuggable). Native crashes (SIGSEGV) and an ANR are not caught: no JVM exception.


## One shared UI set for the TV apps (`app/shared/src/tvui`)
- Why (2026-10-23): the four TV apps copied "the same" row, dialog sheet, panel, pill and tv_style.xml by hand, and they drifted: the RAM live widget shipped with its own hand-built frame, dialogs and tiles were rebuilt per screen. A text rule (DESIGN-TV.md "copy the values") did not stop it, so the components now exist ONCE and an app calls them.
- `Tv.kt` (`TvUi` tokens + row / sheet / button / field / `TvState`, and the Games-style `panel` / `scrim` / `menuButton` / `pill` extensions) and `res/values/tv_style.xml` are compiled into TV Channels and TV Games (`srcDirs` / `res.srcDirs`). Components take words and drawable ids, never string ids: strings and generated icons stay per app. The tokens are Kotlin constants (the file cannot name an app's `R`); `tv_design.test.js` checks them against tv_style.xml and fails when channels / games define a component tvui has.
- Phase 1 moved Channels and Games. Phase 2 moved TV Setup (no own `tv_style.xml`, `BG/CARD/INK/ring()` are TvUi's) and TV Home: its tile focus (ring, grow, glass, `Motion`) became tvui's `TvTile` / `Glass` / `Motion` so a tile looks the same in every app, and its glass panel became `TvGlass`, which the long-press app menu now uses (it was a flat AlertDialog).
- The System widget read RAM from `ActivityManager.availMem` (cached apps counted as free: 64 % vs the RAM live widget's 83 %). It reads `/proc/meminfo` MemAvailable now, as TV Setup does; both colour by `HomeConfig.level` (ok < 70, warn 70-84, bad 85+), one function, with a test that `LiveWidgets.accent` agrees. The web preview shows no levels, so it needed nothing.
- TV Channels' fields are `TvField` now: the keyboard no longer rises by itself on focus (`raise()` does it where a field opens in front of the person: search, a name); OK raises it elsewhere; the D-pad can leave the field.


## TV Setup declares no accessibility service (TV Setup Keys add-on)
- Why (2026-10-23, measured by a user on a Sony Google TV in Vietnam): installing TV Setup through Downloader was hard-blocked by Google Play Protect's "enhanced fraud protection" ("app blocked to protect your device… sensitive data… financial fraud", only an "I understand" button). Cause: the manifest declared two `BIND_ACCESSIBILITY_SERVICE` services (`KeyCatch`, `KeyFixService`). Play Protect blocks an app that declares accessibility (also a notification listener / SMS) when it is installed from an internet-sideloading source (browser, Downloader, file manager); an install by `adb` / the shell is not that source. A README line telling people to press "Install anyway" cannot work: there is no such button.
- Fix at the root: both services moved into the add-on `app/tvkeys` ("TV Setup Keys", `dev.tvremote.setup.keys`). `tv-debug.apk` has no `BIND_ACCESSIBILITY_SERVICE` (checked with `aapt dump xmltree`, and `KeyFixTest.noAccessibilityServiceInThisApp` keeps it so). The add-on's APK is packed inside tv-setup.apk (`assets/tvkeys.apk`, gradle `packKeys*`, no network, one version) and TV Setup installs it with `pm install` over its own ADB link (`KeysAddon.kt`, the path `KeepAdb.grant` / `KeyFix.prepare` already use) when a feature needs it: `KeyFix.sync` (a dead key to bring back, or the hold rule) and `Screen.java` (the key that wakes the screen asks `RulesReceiver` `do: keys_addon`). A different version (TV Setup updated, or rolled back: the newer add-on is uninstalled first, `pm` refuses a downgrade) is put right on the next `sync`. The add-on is protected from the Apps tab like every `dev.tvremote.*` app.
- **Thin on purpose:** the add-on holds the services and nothing else. `KeyFixService` there asks TV Setup's `KeysBridge` (a bound service guarded by the signature permission `dev.tvremote.setup.permission.KEYS`, so only the same key binds) about each key, with a synchronous binder call, and does what it answers (taken / pass). KeyFix.plan/decide, HoldKeys.Gate, Rules, TV Home's key map stay in TV Setup, so the add-on changes almost never. Keys outside `KeysProtocol.INTEREST` (a fixed superset of the dead-key list, the colour keys and the hold keys; a test keeps it a superset) pass with no call; if TV Setup is not bound / slow / gone the key passes (the remote must never be worse for it). `KeyCatch` needs nothing from TV Setup and stays whole in the add-on, as does the "screen off or KeyCatch waiting" check.
- **One TV's odd buttons from the site (2026-10-26):** `docs/setup/keys.json` (signed, `dev/sign/sign_keys.py`; `KeyList.kt` reads site, GitHub, kept copy, packed copy, a day old) adds `KeyFix.DEVICE_KEYS` entries per `Build.MODEL` / `DEVICE` with no new APK. A code outside `INTEREST` would never reach TV Setup, so the add-on asks `KeysProtocol.TX_CODES` for this TV's extra codes when it binds, and again when the rev that each `TX_KEY` answer now carries changes (an older TV Setup sends none: read as 0, no extras). Navigation, volume, power and menu are refused in both the app and the sign script (`KeyList.NEVER`, a test keeps them equal). Tested on the TV emulator with a fake HID keyboard over `/dev/uhid` (F1 = 131): with a test entry it opened Settings, without it F1 passed.
- Same signing key as TV Setup (`app/tvsetup/signing.jks`, debug and release alike): the permission is defined by TV Setup only (a second definer would fail to install), the add-on `uses-permission` it. `ACCESS_RESTRICTED_SETTINGS` / `APP_AUTO_START` are allowed for the add-on's package too (`KeysAddon.prepare`): the restriction is on the app that provides the service.
- Migration: a TV updated from a version with the services inside has `dev.tvremote.setup/...KeyFixService` (or `...KeyCatch`) in `enabled_accessibility_services`. `KeyFix.list` drops those stale entries and writes the add-on's; `sync` runs after every update (BootReceiver / live ADB), so the keys come back within seconds of the update. A client with an older `tvr-helper.jar` (phone / computer not yet updated) looks for `dev.tvremote.setup/...KeyCatch` and finds nothing on an updated TV: the first key after the screen off then also reaches the app, until that client updates. `Screen.java` still uses an older TV Setup's own KeyCatch.
- Removing TV Setup does not remove the add-on (there is no remove path in the clients; TV Setup's own "Remove TV Setup" button asks Android to remove TV Setup only). The add-on without TV Setup is inert: it cannot bind and its services are off.
- Workaround for versions before this one: install from the Phone / Computer app (ADB, no block) or switch Play Protect scanning off for a minute (README "Android calls the app harmful").

## One "open target" for keys, power-on and the home grid (`Target`, `js/target.js`)
- **Format:** one string. `com.example.app` (an app, the only form before), `pin:com.example.app/<shortcut id>` (a launcher shortcut, `LauncherApps.getShortcuts`: static, dynamic or pinned), `tile:<provider>/<id>` (a live widget from `LiveWidgets`). Why a prefix and not JSON: configs already hold plain strings in `dock` / `hidden` / `order` / `keyapps` / `autoapp`; no migration, an older TV Home drops an unknown `pin:` key harmlessly. `Target.kt` and `target.js` share `tests/target_cases.json`.
- **Where:** `keyapps` values, `autoapp`, and `tiles` (the grid's list; `TILES_MAX` 8 -> 24 now that shortcuts share it). An app is on the grid unless it is in `hidden`.
- **Only the default home may read or start another app's shortcuts** (`hasShortcutHostPermission`). So TV Setup never starts a `pin:`/`tile:` itself: `HomeKeys.open` starts TV Home's exported `OpenActivity` with the target, which opens it only if that target is in TV Home's own config (nothing else can ask it to run an arbitrary shortcut). The key filter's rule (`KeyFix`) still starts apps only, so `HomeKeys.keys` hands it app targets; shortcut/tile keys work while TV Home is on screen (`Keys.onKey`). Power-on (`AutoOpen`) uses `HomeKeys.open` and handles all three.
- **"Keys this remote has":** `config.keysHave` = union of `InputDevice.hasKeys` over the non-virtual keyboard devices; `null` = unknown, every slot stays live. A missing key with a target on it stays editable (to clear it); an empty one is dead.
- **Shortcuts to pick from:** Channels `favourites`, `search` (+ dynamic `last`), Games `library`, `padtest`; each opens a deep link of its own app.

## Usage events and a clock that ran ahead (`app/tvsetup` `Usage.kt`, 2026-10-26)

- An FPT Play box booted at 4/9/2027 and was set right by the network 38 s later. Android keeps its current stretch of usage events (`dumpsys usagestats`: "In-memory daily stats timeRange=8:16 4/9/2027") beginning in that future until the next reboot, so `queryEvents(begin, now)` came back empty: the app lock saw no app (unlocked once = never asked again, kids mode let YouTube open), the day's use stayed 0. Android's own `lastTimeUsed` totals still moved on.
- Fix: every read goes through `Usage.events`, which asks 20 years past now and drops events after now. `tests/usage_events.test.js` fails on a direct `queryEvents` anywhere else. Cursors kept by time (`Guard.eventsTo`, `pinAt`, `lastStop`, `volSaidAt`) are reset when they lie ahead of the clock.
- Self-healing is the update itself: TV Setup's `AutoUpdate` is on by default, so a release reaches such boxes with nobody connecting; a reboot also clears it on old versions.
- Not reproduced on the emulator by moving its clock (it re-reads fine); only a boot with the wrong year does it.
