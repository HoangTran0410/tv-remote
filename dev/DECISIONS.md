# Decisions worth remembering

Hard-won facts behind code that looks odd. Each: what was seen, what was decided. Add to it when a bug took a day:
under the file of its part (one `## ` heading each), and a line here. Code points at one as `dev/decisions/<file>.md "<heading start>"`;
`app/shared/tests/decisions.test.js` keeps this list and those pointers true.

## [Connecting to TVs, the network, the web page](decisions/connect.md)

- Picture
- ADB delayed_ack
- Waits
- Network
- Browsers
- The TV's "Allow USB debugging?"
- ISP boxes that lock Developer options (FPT Play, Viettel)
- Start-up speed (computer app, TV emulator, `TVR_TRACE`)
- Reading the TV's state over ADB
- The Phone app's dialogs are the page's (`fromRemote`), but not Wireless pairing
- The web page is served gzipped (`WebPage.kt`)
- Session cookies from a QR are SameSite=Lax (`ManageServer.kt`, desktop `Pass.kt`)

## [How the apps fit together](decisions/apps.md)

- The apps leaning on each other
- The error guard (`app/shared/src/guard`, `js/errbox.js`)
- One shared UI set for the TV apps (`app/shared/src/tvui`)
- TV Setup declares no accessibility service (TV Setup Keys add-on)
- One "open target" for keys, power-on and the home grid (`Target`, `js/target.js`)
- Usage events and a clock that ran ahead (`app/tvsetup` `Usage.kt`, 2026-10-26)

## [TV Setup features](decisions/tv-setup.md)

- Hold to use Input / Settings (HoldKeys)
- Check-out (Trả phòng) for homestays and hotels
- Live widgets in TV Home's app grid (TV Setup provides them)
- Telegram bot in TV Setup (issue #4: `core/web/Telegram.kt`, `TelegramHttp.kt`, `web/js/telegram.js`, `/api/telegram`)
- Kids mode (`Kids.kt`, `Totp.kt`, `Clock.kt`, `PinActivity.kt`, `Guard.kt`, `web/js/kids.js`, 2026-10-24)
- Profiles: which user a command acts on (`core/ontv/TvUser.kt`, 2026-11-07)

## [TV Home](decisions/tv-home.md)

- TV Home QR widgets
- TV Home "Manage from phone" (its own settings page)
- Opening an app from TV Home (`Zoom.kt`) and updating the apps under it
- The stock launcher wins over the HOME choice (BRAVIA, Android 9)
- Customize opens lazily; no second hand (`Customize.kt`, `widgets/Widgets.kt`)
- A dialog taller than the screen squashes its last row (`TvUi.sheet`, `PhotoScreen.kt`)
- Settings are cleaned once (`Store.kt`)
- Focus without growth on the Customize pages (`Customize.kt`, `PickTile`)
- TV Home scroll: tiles at rest are layers (`TvTile.card`, TV Setup `DimLayer`)
- TV Home's settings page: groups, one save for the order, a session that outlives its QR (`js/home.js`, `js/home_order.js`, `Access.kt`, `WebCheck.kt`)
- TV Home compiled ahead (`Home.kt`, `LauncherInstall.kt`, `Setup.kt`, TV Setup `HomeCompile.kt`, 2026-10-24)
- Launcher gallery opening (`MainActivity.chooseLauncher`, `Launchers.fetch/thumb`, `SignedList.kept`, 2026-10-24)
- TV Home: video and GIF backgrounds (`app/tvhome` wall/Videos.kt, Transcode.kt, VideoWall.kt)
- TV Home themes (2026-10-24)

## [TV Channels and TV Games](decisions/channels-games.md)

- Phone pads: why links dropped and lagged (`GameServer.kt`, `pad.js`)
- Ready-made channel categories are data, per country (`Categories.kt`, `Shelves.kt`, `chancat.js`)
- TV Games: lobby and columns cost (`GameActivity.still()`, `LobbyView.kt`, `LibraryActivity.kt`)
- TV Channels first open: show iptv-org's list first, the rest behind (`BrowseActivity.load`, `Catalog.iptv`, `Providers.warm`, `TvState.loading`)
- TV Channels: other countries' channels in a country's categories (`"also"` in `docs/channels/categories/<cc>.json`, `Categories.alsoFetch`, `GlobalIndex.byIds`)
- TV Games / TV Setup D-pad + UI pass (2026-10-24, emulator tvremote_gtv3, 1080p, density 320)
- TV Channels: VTV from VTVgo's free player (`Providers.Vtvgo`, `"vtvgo"` in `docs/channels/providers.json`)

## [Tests and speed](decisions/testing.md)

- Speed budgets (`PerfBudgetTest.kt`, `tests/perf_budget.test.js`, 2026-10-24)
- Tests that froze the computer (`dev/test/light.sh`, `media.sh`, `emu.sh` EMU_MAX, 2026-10-24)
