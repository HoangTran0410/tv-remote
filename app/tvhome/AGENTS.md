# app/tvhome: TV Home

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## What it is

| Name | Code | `data-core` | What it is |
|---|---|---|---|
| **TV Home** | `app/tvhome` | | the project's own launcher (`dev.tvremote.home`), first in the launcher gallery; set up from every client over ADB |

## Files

```
app/tvhome/                TV Home, the launcher: plain Views, no :shared (it would pack the web UI; its "Manage from phone" page is packed from app/shared/web by packHomeWeb and served by ManageServer); HomeActivity (clock, dock,
                         grid, long-press menu), Widgets (a **widget** is a box in the row above the app grid, `widgets/*Widget.kt`; a **live widget** is a tile in the grid showing live numbers, `apps/LiveWidgets.kt`; an **app tile** is an app's tile in the grid: system / usage / lunar / timer / note widgets; usage, timer and CPU from TV
                         Setup's HomeInfo provider, which answers only TV Home by its signing key; also its live widgets, `apps/LiveWidgets.kt` here / `LiveWidgets.kt` there (TV Channels: `ChannelLiveWidget.kt`), stored under the older name "tile" (key `tile:<pkg>/<id>`, provider calls `tiles` / `tile` / `tile_do`), a `layout` each: gauge / next / toggle; a second provider, TV Channels' own HomeInfo, both pinned by signing key), Lunar (the lunar calendar, offline), Apps, Store (prefs), HomeReceiver (`--es set <b64 json>`, DUMP-protected, like
                         RulesReceiver). Its Kotlin (`dev.tvremote.home`) is grouped by who uses whom: the root keeps the entry points (HomeApp, HomeActivity, HomeReceiver, Store, Customize, Lang, Font, Power, the screensaver HomeDream / DreamSettings / Dream) and the components other apps start by name (PinActivity, OpenActivity: a moved class would break `screensaver_components` and TV Setup's `HomeKeys`); subpackages `widgets/` (the widgets + Weather, Lunar, MonthGrid, QrCard), `wall/` (backgrounds), `apps/` (Apps, Pins, LiveWidgets, Badges, Nav, InputOpen), `keys/` (Keys, QuickKeys, Targets, Picker), `manage/` (ManageServer, HomeApi, Access, PhotoScreen), `ui/` (TopBar, Drop, Zoom, Frost). The ready-made themes are the site's signed list `docs/home/themes.json` (`dev/sign/sign_themes.py`; `Themes.kt` reads site, GitHub, kept copy, packed copy; `HomeConfig.parseReady` cleans each look; `/api/home` `themesBuiltin` carries `names`). Its settings' shape is ONE file, `app/shared/src/home/kotlin/.../HomeConfig.kt`, compiled
                         into :shared, :desktop and :home; the clients' side is `core/ontv/Home.kt` + `/api/home` + `js/home.js`.
                         In the gallery it is kind "self": its APK is the latest release's `tv-home.apk`, pinned by SHA256SUMS
```

**The project's own apps first:** `Store.OWN` (TV Setup, TV Games, TV Channels) lead the first-run dock, and one installed later is
moved in once (`Store.promote`, remembered per package: moved elsewhere by the user, it stays there). Without TV Setup the grid shows
a **Get TV Setup** tile among them (`suggestSetup()`, the art `suggest_setup.png` from `dev/gen/brand_art.py`); it installs the
release's `tv-setup.apk` through `SelfUpdate`, or is turned off for good (`showSuggest`; the old `Store.suggestOff` flag is moved into it once).

**First run grid:** `Store.first` puts the dock plus `FEATURED` apps on the grid and the other installed apps into `hidden` (= off the grid; nothing else reads it), listed in + Add as "Not on the grid"; on that first Home a one-time `Picker.Mode.CHOOSE` screen (pre-ticked; Done applies, Back keeps the tucking already saved; flag `choose_due`, so an update never shows it) lets the person tick apps, and it reopens from + Add ("Choose apps…") and Customize; only a fresh install (no saved settings), only if at least `TUCK_MIN` known apps exist, and apps installed later are not in the list so they show as always.

**Two widgets with an action** (`widgets/RecentWidget.kt`, `AdblockWidget.kt`): **recent** = the last 4-6 apps opened (icons, labels in the big size), OK opens one, Left / Right walk the icons inside the widget (`Widgets.recentAt`, a ring drawn in place) before leaving it; data is TV Setup's HomeInfo `recent` (usage events in event order, never by time: the TV clock jumps; not Setup / Home / system screens), without it the widget says to install or update TV Setup. **adblock** = Private DNS on an ad-blocking host (`HomeConfig.ADBLOCK_DNS`, kept equal to `DNS_SETS.ads` in `js/tvset.js` by `tests/home_adblock.test.js`): OK sets `private_dns_mode=hostname` (the last ad host used, else AdGuard), checks names still resolve for 7 s and puts the old setting back if not; off restores what was there (`prev_mode` / `prev_host`). Needs WRITE_SECURE_SETTINGS (granted by `HomeConfig.GRANT_WIFI`, the clients' ADB step), else its OK says so in one line; hidden below Android 9 (`ADBLOCK_MIN_SDK`, `HomeConfig.schema(sdk)`). DNS-level only: it does not stop the ads inside YouTube's videos (said in its menu and settings).

**Nothing is forced on the first screen:** every element it draws has a `show*` switch in `HomeConfig.SHOWS` (clock, date, Wi-Fi button, settings button, "+ Add", Get TV Setup, Play Next row; all on by default), in the TV's Customize and in `js/home.js`. A new element needs its switch in the same change. Hiding the gear leaves the Menu key and the clients as the way back.

**Every element has its own small menu** (long OK, or Menu, on what already takes the focus: app tiles, Play Next items, every widget, the top-bar buttons, + Add, Get TV Setup): `HomeActivity.elementMenu` (`TvGlass.menu`, the SAME glass sheet as the app tiles' menu, on the side of the screen away from the element; switches are `TvGlass.Item.on`, a value that changes in place is `live`), with the SAME `HomeConfig` keys Customize writes (`Store.change`), applied live behind it; a switch stays open, a hide closes it and `settle()` puts the focus on a neighbour. Every widget is a focus stop (the user, 2026-10-27): OK on weather / news / market opens its screen, on any other widget its menu. The header (clock, day, greeting) is a stop too, its OK its menu. With the "glass" focus (`Motion.glass`) the ONE glass pane (`tvui/Glass.kt`) goes to every stop: app tiles, widgets, the header, the top-bar buttons (`HomeActivity.glassTo` / `glassOff` / `glassy`); each of those drops its own focus look then (the widgets' ring, the header's ring, TopBar's disc). Glass draws each shape once (a halo cache of 6, shared) and, to another shape, crosses two panes (the old one flies there and fades, a twin in the new shape flies in): still no redraw while moving. Other elements (clock, day, greeting as separate items) become stops only in edit mode: "Edit home screen" (`edit()`, an item in each menu and a row in Customize > Keys) makes every element focusable with an outline and a hint line, OK opens its menu, Back leaves it. A new element: its menu in `HomeActivity` and its stop in edit mode.

**Play Next and channels** (`apps/Watch.kt`): the rows other apps publish to TvProvider (Watch Next, preview channels), read with
READ_TV_LISTINGS (runtime: `HomeConfig.GRANT_WIFI` grants it over ADB with the others; without it, or before Android 8, no row) and
drawn as one row (`HomeActivity.watchRead` / `show`) placed by `watchAt`: `top`, `under1`, `bottom` (default) or `tiles` (no row of its own: the items follow + Add in the grid, capped at `cols`). In the own-row placements it is a `Strip`, a sideways-scrolling row of up to `WATCH_ROW` (20) items, same tile size, first tile on the grid's first column; `Strip.reveal` keeps the focused tile in view, Up / Down leave it to the nearest tile by x (`step`). Play Next tiles have `app == null`, so `moveKey` leaves them in their slots in every placement. Menu on one: row position (cycles `watchAt`), Pin to the grid (`Pins.pinIntent`: TV Home's own shortcut `pn<hash of the intent>`, the same safe intent as `Watch.intent()`, then an ordinary pin), don't show that app's items (`Store.rowsOff`; the app's own menu, On the home screen, brings it
back, as + does; the user looked there first); `showWatch` turns the whole row off. TV Channels' preview-channel programs are left out (they repeat its pinned shortcuts), its Play Next stays. A launcher may read only rows marked searchable (the default) and may not filter with a `selection`: hence the filtering in
Kotlin. TV Channels writes its own (`HomeRows.kt` there) and is the test subject on the emulator.

**Other apps' channels** (`apps/Links.kt`): Android lets no launcher list the deep links an app takes, and VTVgo / TV360 publish no
shortcuts, so the site's signed `docs/home/links.json` names them: per app its package, its own link with `{id}` (read from the app,
tried on a TV) and the items (id, name, logo in `docs/home/logos/`). `dev/gen/app_links.py` remakes VTVgo / TV360 / FPT Play (its two packages, FPT boxes' launcher first then the TV app, one entry each; `Links.installed` offers one, and none turned off) with every channel
the apps show (VTVgo 47, TV360 159, FPT Play 93; their own logos at 256 colours); a hand edit is signed with `dev/sign/sign_links.py`. "+ Add" shows "Pin a <app>
channel" for each installed app (not in Customize's full picker: ~200 tiles more); the picker (`Picker.show(only = pkg)`, search for one) pins an item as TV Home's own shortcut (`Pins.pinIntent`, VIEW held to that package, the
logo on a card kept sharp by `PinArt.own`). A new app = an entry in the list, no APK. Phones and the web page cannot pin (only the
home screen may ask for a pin).

**YouTube pins and "whoever handles the link"** (`apps/YouTube.kt`, `apps/LinkApps.kt`, `LinkActivity`): a pin holds the canonical https link only,
never a package: its shortcut starts our own non-exported `LinkActivity`, which at press time asks `PackageManager.queryIntentActivities`
(VIEW + BROWSABLE, **`MATCH_ALL`**: with `MATCH_DEFAULT_ONLY` Android 12+ drops every app without a verified domain, YouTube among them) and opens the
first handler: the person's choice (tile menu "Open YouTube links with", `LinkApps.choose`, one for all YouTube pins) > stock `com.google.android.youtube.tv` >
the rest. Browsers and stubs are dropped (an app that also takes `https://example.com/`: BrowserStub, Downloader). So SmartTube / TizenTube / ReVanced work, with stock YouTube off,
and swapping apps needs no re-pin; none installed: a friendly "install a YouTube app". Package visibility is the `<queries>` VIEW https entries in the manifest. A `links.json` entry with `pkg "*"` (https only) means the
same for any site; only YouTube uses it (`dev/gen/app_links.py youtube`). The contract other clients call: `HomeReceiver --es pin <b64 {"url","name"?,"img"?}>` and `POST /h/pin`
(same JSON; answers the snapshot or `{"error": pin_bad_link | pin_no_app | pin_failed}`). `YouTube.parse` is pure (watch, youtu.be, shorts, live, embed, playlist, @handle, channel/UC, c/, user/;
`music.youtube.com` becomes www: no YouTube TV filter takes it); name and picture from oEmbed (videos, playlists) or the channel page's og: tags (they sit ~830 KB in: a bounded read).
**Any link** (`apps/WebLink.kt`, `LinkUrl.kt`, TV side `Home.link` / `Home.openLink`): `LinkUrl.clean` is the one rule (http(s) or `scheme://`, never intent/file/content/javascript/data...; its twin is `linkParse` in `home_pin.js`). `POST /h/link` and `HomeReceiver --es link` answer what the TV opens a link with (`LinkApps.preview`: apps, else browsers, else none, with 64 px logos); `/h/pin` takes `pkg` (the picked default, `LinkApps.choose`) and pins nothing when no app or browser takes the link. A pin of a deep link / page is a shortcut into `LinkActivity` as YouTube's is (browsers are tried only for non-YouTube groups). + Add has a top-level "Add a link" row (the paste card alone). + Add > "Pin from YouTube" is the picker plus a top card: a QR of TV Home's manage page + `#pin` (`ManageServer.PASTE_HASH`); the page's paste box posts to `/h/pin`. A pin made from a shell needs TV Home in front (HOME key), or the system refuses the request.

## Backgrounds

Which path draws what (read before adding a theme or a wall kind):

| `wall` | Drawn by | Rotates (`walls` + `wevery`) |
|---|---|---|
| gradients, drawn scenes, `bing`, `photo*`, `vid:<id>` | native (`wall/Wall.kt`, `WallArt`, `VideoWall`) | yes (`HomeConfig.next`) |
| `web` + `loop.html?i=<jpg>` (the site's photo themes, the gallery) | native picture, cached (`wall/NetPicture.kt` `direct()`) | no: `web` is never in `walls` |
| `web` + `loop.html?v=<video>` (the site's video themes) | native player (`wall/WebVideo.kt` `direct()`) | no |
| `web` + `loop.html?s=<list>` (a slideshow, `docs/home/<list>.json`) | native in new TV Homes; older ones run loop.html's own slideshow | inside the slideshow |
| `web` + any other page (Immich Kiosk, a dashboard) | a WebView (`wall/WebWall.kt`) | no |

A WebView wall was measured slow on the FPT Play box (cold start + uncached download), so every site theme goes native; loop.html
stays only as the fallback for older TV Homes and the page's preview. Its allowed hosts are listed twice: loop.html `HOSTS` and the
`HOSTS` of NetPicture / WebVideo. Themes live in `docs/home/themes.json` (signed, `dev/sign/sign_themes.py`, bump `rev`), photos come
from `dev/gen/wallpapers.py` (Unsplash or Wikimedia Commons, licence + author kept; calm, few details: the tiles sit on them), and a TV Home asks for the list again on Home (a day old) and
on opening Themes (an hour), `Themes.refresh`.

**Suggested** (Customize > Wallpaper, the page's "Suggested"): the same signed `wallpapers.json`, its `"links"` (the Việt Nam slideshow,
Pexels videos, a live page; `dev/gen/wallpapers.py LINKS_ONLY=1`, small pictures pinned by SHA-256) and its pictures: each only sets
`wall: web` + `wurl`, so the table above draws it (`Suggest.kt` here, `Wallpapers.links` + `/api/home/walls/thumb` for the page).
**Custom** (`wall: own`): `wcol` 1-4 colours (one = plain), `wdir` one of `HomeConfig.WDIRS` (`radial` from the middle), drawn by
`wallGradient` (Backdrop, the screensaver, the tiles); a change of either is part of `wall()`'s key.

**Sky and burn-in care:** `wall: sky` (in `HomeConfig.PLAIN`) is a three-colour top-to-bottom gradient from `Sun.sky` (`app/shared/src/home/.../Sun.kt`, NOAA maths, unit-tested) at the Weather widget's saved place (`wall/Sky.kt`, prefs `weather`/`place`), else the time zone's longitude; `HomeActivity.skyTimer` repaints every `Sun.REPAINT_MS`, only while it is the wall. `shift` (default off) moves the whole content view 1 dp a minute round a 6 dp square (`ui/PixelShift.kt`, `shiftTimer`); "Pure black (OLED)" is the existing `black` wall.

## Off = nothing runs

Size does not slow a weak box; what runs at start and in the background does. So: **a new option / switch defaults to off (or to
today's behaviour), and with it off no receiver, service, thread, timer, alarm, observer or file write runs for it.** Work that
only a widget or wall needs starts when that thing is on the screen, and stops with it (the `netTick` of the network graph
is the model: it re-arms only while that widget exists). Everything runs only while Home is on screen: the manifest has no service
but the dream and no boot / alarm receiver. Guards: `dev/test/apk_sizes.js` (APK jump alarm, baseline `dev/test/apk-sizes.json`),
`dev/test/tvperf/idle.sh` (CPU per thread when left alone), `cold.sh` (start and PSS).
