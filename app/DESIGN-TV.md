# The TV apps' design system

Rules for any UI change in **TV Setup** (`app/tvsetup`), **TV Home** (`app/tvhome`), **TV Channels** (`app/tvchannels`) and **TV Games**
(`app/tvgames`), plus the **Phone app's native screens** (last section). They describe what the code does today. Where the apps
disagree, see [Known drift](#7-known-drift): don't make it worse, and don't start a new variant.
The shared values are copied into each app's `res/values/tv_style.xml` (this file replaced `dev/TV_STYLE.md`).

## 0. Shared code, and which app is the reference

| App | Shared code | Words from | Tokens from |
|---|---|---|---|
| TV Setup | `:shared` (`tv/build.gradle.kts:50`) | `T("id")`: `app/shared/i18n/<lang>.json` (`core/Words.kt`); **no strings.xml** | `values/tv_style.xml` + constants in `MainActivity.kt:62-70` |
| TV Home | none (only `shared/src/home` HomeConfig) | `i18n/apps/<lang>/<module>.json` (built to strings.xml) | constants in `HomeActivity.kt` (**no tv_style.xml**) |
| TV Channels | **`tvui`** (`shared/src/tvui`: `Tv.kt` + `res/`), `shared/src/sign` | `i18n/apps/<lang>/<module>.json` (built to strings.xml), own language picker (`Lang.kt`) | `tvui` `TvUi` constants (= `tv_style.xml`, built in from `tvui/res`) |
| TV Games | **`tvui`**, `Qr.kt` copied, `shared/src/sign` | `i18n/apps/<lang>/<module>.json` (built to strings.xml) | `tvui` (`TvUi`, `tv_style.xml` built in; no colors.xml) |

- **`app/shared/src/tvui` is the reference** for what the apps have in common, and the one copy: `Tv.kt` (`TvUi`: tokens, `row`, `sheet`, `button`,
  `field` (TvField), `states`, `ring`, `initials`, `TvState`; Games-style `panel`, `scrim`, `menuButton`, `withIcon`, `pill`, `heading`) and
  `res/values/tv_style.xml`; the tile focus (`TvTile`, `Glass`, `Motion`) and the glass panel / menu (`TvGlass`). TV Setup, Channels and Games build
  it in (`srcDirs` / `res.srcDirs`); TV Home compiles the Kotlin (no `tv_style.xml`: it takes the constants). Use a component from there, never a look-alike; a look it lacks is added there first.
  Components take words and drawable ids, not string ids: strings and generated icons stay per app. `tv_design.test.js` fails when
  any app defines a component tvui has, or Tv.kt's colours differ from tv_style.xml.
- `tv_style.xml` exists once (`tvui/res`): change a value there, in `TvUi`, and here together; the test compares them.
  - **TV Home** for focus on tiles: ring, glass, grow, motion (now `TvTile`, `Glass`, `Motion` in tvui; Home's `Tile` is a thin subclass);
  - **`tvui`** for rows, lists, fields, dialogs, buttons, pills and full-screen popups (`panel()` / `scrim()`): it was Channels' and Games' `Ui.kt`.

## 1. Principles (10-foot UI)

| Rule | How, today |
|---|---|
| **D-pad first** | Everything you can act on has `isFocusable = true; isClickable = true` (`channels/Ui.kt:58`, `games/Ui.kt:76`). No touch-only gestures: swipes, drags and taps on things that can't be focused are out. A mouse wheel is a bonus (`games/LibraryActivity.kt:291`). |
| **Focus always visible** | Every focusable control has a focused state: a ring, the glass or a light fill (§2.5). Never rely on the system's default highlight. |
| **A dialog with one button opens with focus on it** | One press of OK answers a notice / detail dialog (`Customize.kt` `showOnOk`, `HomeActivity` update failure). A delete confirmation keeps the focus on the safe choice. |
| **The first focus lands somewhere useful** | A screen or popup that opens puts the focus on something (e.g. Retry: `BrowseActivity.kt:379`). A rebuilt list keeps the focused row (`BrowseActivity.kt:243`). |
| **Back closes, then leaves** | Inner layer first: a download is cancelled (`LibraryActivity.kt:175`), then an overlay closes (`:176`, `closeOverlay()` `:185`), then a filter is cleared, then you leave (`:165`). Move mode: Back cancels (`HomeActivity.kt:498`). TV Home (the launcher) never leaves: Back scrolls to the top, ignoring a second press within 500 ms (`:340`). |
| **The focused row scrolls to the middle** | Every scroll view is tvui's `TvScroll` / `TvHScroll`: the D-pad's focus stops mid-view, so the rows beyond it show (Android's own stops it at the edge: nobody saw more below, the user 2026-11-03); at either end it stops at the end. It glides there (a critically damped spring, one step a frame while it moves, the speed kept from key to key): Android's own focus scroll is a jump. Only a view that scrolls itself keeps a bare one (TV Home's grid, below; TV Games' library). `tests/tv_scroll.test.js`. The TV web page: `scrollIntoView({ block: 'center', behavior: 'smooth' })` (`ontv.js`) |
| **Hold OK = the item's menu** (same as the Menu key) | Channels: `e.startTracking()` + `onKeyLongPress` → `actions()` (`BrowseActivity.kt:659-669`); Home: tile `isLongClickable` → `menu()` (`:744`); Games: `setOnLongClickListener { menu(i) }` (`LibraryActivity.kt:796`). A new list of things gets the same: hold OK and Menu both open its menu. |
| **Readable at 3 m** | Body text ≥ 15 sp, secondary ≥ 12 sp (§2.4). One line + ellipsis for names (`maxLines = 1; ellipsize = END`). Contrast: ink on bg/card ≥ 4.5:1. |
| **Overscan / safe area** | Main screens are padded `tv_margin_x` 48 dp × `tv_margin_y` 27 dp (5 % of 960×540 dp): `BrowseActivity.kt:75`, `MainActivity.kt:144`, `LibraryActivity.kt:272`. Nothing to read or focus goes outside. Corner overlays sit ≥ 32 dp in (`NotifyLayer.kt:53`). |
| **Weak boxes (1 GB, MT9221 / MT5895)** | Plain Views, no AppCompat / Compose (`channels/Ui.kt:14`, `Lang.kt:9`). No real blur (`ui/Frost.kt`: a 1/16 copy, stretched), no per-frame redraws (`Glass.kt:15-18`: drawn once, then moved), `clipRect` not `clipPath` (`Glass.kt:53`), `withLayer()` on scale animations (`HomeActivity.kt:1103`), `hasOverlappingRendering() = false` on dimmed cards (`BrowseActivity.kt:719`). |
| **Motion can be off** | Respect `ANIMATOR_DURATION_SCALE == 0` (`Motion.read` `HomeActivity.kt:1092`, `animOn` `LibraryActivity.kt:714`) and go *calm* on ≤ 1.5 GB RAM (`Motion.lowRam` `:1086`, `calm` `LibraryActivity.kt:715`): shorter (×11/20), one animator, no overshoot. A card opening over the screen (TV Home's folder) comes up with `Motion.appear` (0.94 → 1 and a fade, 240 ms; calm: the fade alone), once its content is there. |
| **TV vs phone** | TV Setup also runs on phones: decide by `FEATURE_LEANBACK`, never screen size alone (`MainActivity.kt` `phone`). |

## 2. Tokens

### 2.1 Colours (dark only: there is no light theme on the TV)

| Token | Value | Where it lives | Use |
|---|---|---|---|
| `tv_bg` | `#0E1116` | `tv_style.xml`; Setup `BG` (`MainActivity.kt:62`) | screen background (Channels `windowBackground`) |
| `tv_card` | `#1B2028` | `tv_style.xml`; Channels `Ui.CARD`; Setup `CARD` | panels, cards, fields |
| tile (Setup) | `#22272F` | Setup `TILE` (`MainActivity.kt:64`) | a resting action tile / button on a card |
| `tv_ink` | `#E8EAED` | `tv_style.xml`; `Ui.TEXT`; Setup `INK` | main text, icon tint |
| `tv_dim` | `#9AA0A6` | `tv_style.xml`; `Ui.DIM` | secondary text, counts, hints |
| `tv_ring_accent` | `#2F7DF6` | `tv_style.xml`; Home `RING_ACCENT` (`HomeActivity.kt:1013`) | **the focus ring only** |
| app accent | Channels `#5EEAD4` (`Ui.ACCENT`, theme `colorAccent`); Setup `#3B73F6`; Home `#8AB4F8` | per app | spinners, a chosen item, the app's own brand; never the focus |
| danger | Channels `Ui.BAD` `#F28B82`; Setup `BAD` `#FF7070` | per app | errors, "off" states |
| ok / warn | Setup `OK` `#46D283`, `WARN` `#F0B34A` (`MainActivity.kt:68-69`) | Setup | status dot + title (`setStatus` `:598`) |
| scrim | Games `0xD90E1116` (`games/Ui.kt:69`) | Games | the dimmed screen under a popup |
| logo tile | `#F2F4F7` light / `#3A4350` dark (`LogoFit.kt:51-52`) | Channels | what a channel logo sits on (picked by contrast) |
| player colours | `#3B82F6 #EF4444 #22C55E #EAB308` (`games/Ui.kt:22`) | Games | player 1-4 dots |

- Read `tv_*` from resources (`getColor(R.color.tv_dim)`) or the app's named constant. **No new raw hex in a view**: a colour
  needed once more becomes a named constant beside the others (Channels `Ui`, Setup's `BG…BAD`, Home's top-level `const`s).
- White-alpha fills are the second layer of greys: `0x14FFFFFF` field rest, `0x22FFFFFF` divider (`BrowseActivity.kt:236`),
  `0x33FFFFFF` focused row fill (`Ui.states` default), `0x40FFFFFF` panel hairline.

### 2.2 Radii (dp)

| Shape | Radius | Source |
|---|---|---|
| Panel / card / popup | **16** `tv_radius_card` | `games/Ui.kt:54` |
| Dialog sheet (Channels), notice card | 20 | `BrowseActivity.kt:462`, `WarnActivity.kt:44`, `NotifyLayer.kt:81` (drift, §7) |
| Field / pill-button | 22 (Channels search, Retry) · 18 (field inside a sheet) | `BrowseActivity.kt:94,126,551` |
| List row / channel card | 12 / 16 | `Ui.row` `channels/Ui.kt:57`; card `BrowseActivity.kt:723` |
| App / game tile, poster | **10** `tv_radius_tile` | `HomeActivity.kt:877`, `LibraryActivity.kt:735` |
| App icon in the header | 10 `tv_head_radius` | `tv_style.xml` |
| Small button / menu button | 10 (Setup `smallButton`, Games `menuButton`) · 14 (Setup tile, Warn button) | `MainActivity.kt:1083`, `games/Ui.kt:77` |
| Chip / pill (choices) | 30 (Games `pill`) | `LibraryActivity.kt:353` |

### 2.3 Spacing (dp)

Use the steps already in use: **4 · 6 · 8 · 10 · 12 · 14 · 16 · 20 · 24 · 28 · 32 · 48**.

| Where | Value |
|---|---|
| Screen margin | 48 × 27 (`tv_margin_x/y`); TV Home 48 / 34 / 48 / 48 (`HomeActivity.kt:130`) |
| Header | icon 44 (`tv_head_icon`), gap 14 (`tv_head_gap`), name 24 sp bold `tv_ink`, 18 below (`BrowseActivity.kt:211`) |
| Pill row (Games `pill`) | 40 high, 12 apart, 15 sp; header, pills and section titles start 8 in; 18 between the header, the pills and the first title |
| Grid gap between tiles | 32 (`GRID_GAP` `HomeActivity.kt:1011`): room under a grown tile for its name |
| List row padding | 12 × 10, icon 20 + 12 gap (`channels/Ui.kt:55-62`) |
| Dialog sheet padding | 14 (`BrowseActivity.kt:461`); its title 12 left, 10 below |
| Panel padding | 36 / 26 / 36 / 28 (`games/Ui.kt:59`) |
| Notice card | 40 / 32 / 40 / 28 (`WarnActivity.kt:45`); buttons 52 high, 12 apart (`:80`) |

### 2.4 Text (sp, system font; TV Home alone uses Inter from `Font.kt`)

| Role | Size | Source |
|---|---|---|
| Clock (Home) | 60-64, light | `HomeActivity.kt:116,144` |
| Panel heading (Games) | 28 bold, `tv_ink` | `games/Ui.kt:63` |
| App name in the header | 24 bold (`tv_head_name`) | `tv_style.xml` |
| Dialog / sheet title | 18 bold | `BrowseActivity.kt:469` |
| Menu button | 20-22 (Games) · 16-17 (Home Drop, Warn) | `LibraryActivity.kt:590`, `Drop.kt:58`, `WarnActivity.kt:134` |
| Empty / state message | 17, `tv_dim`, centred, line spacing 1.2 | `BrowseActivity.kt:122` |
| Row label | 15 | `Ui.row` |
| Field | 15-16 | `BrowseActivity.kt:92,550` |
| Count, meta, tile name | 13-14 | `Ui.row` count, `HomeActivity.kt:918` |
| Floor | 12 (section caps, small hints) | Setup `section()` `MainActivity.kt:1059` |

- Use the app's text helper: Channels `Ui.text(c, s, sp, color, bold)` (`includeFontPadding = false`), Games `label()` /
  `heading()`, Setup `text()`, Home `Font.on(TextView)`. Over a wallpaper (Home), words get the shadow `shadow()` (`:840`).

### 2.5 Focus

| Thing | Treatment | Helper |
|---|---|---|
| **Tile** (app, game, poster) | TV Home's: ring 3 dp `tv_ring_accent` outside the face + 1 dp white inside (`RING_OUT` / `tv_ring`); card grows to **1.12** (`GROW`) on `Motion.spring` 520 ms in / glide 300 ms out; picture ×1.05 inside it; shine; name fades in under it. Optional **glass** instead of the ring (`Glass.kt`, Home setting `focus`): then the same pane goes to every TV Home stop (widget cards, header, top-bar buttons), which drop their own focus look | Home `Tile.focus` (`HomeActivity.kt:941`); Games copies it: `shell()` + `focusTile()` (`LibraryActivity.kt:734,845`) |
| **Card / action tile** (smaller) | Same ring via `ring()` / `Ui.ring()`, scale **1.06** | Channels `Ui.states(c, fill, r)` (`channels/Ui.kt:38`); Setup `ring()` (`MainActivity.kt:1044`) |
| **Row of a plain list** | light fill `0x33FFFFFF`, no ring; *activated* (the one in use) `0x2214B8A6` | `Ui.states(..., edge = false)` / `Ui.row` |
| **Button / menu entry** | Games: dark `#2A3042` → white fill + 4 dp white edge, words go dark (`menuButton`); Home: white fill, dark words/icons (`ui/Drop.kt:53`, `ui/TopBar.kt:20`), ×1.06 (`Motion.pop` `:1098`) | per app (§7) |
| **Chip / choice** | rest `0x24FFFFFF`, focused `0x73FFFFFF` + ×1.05, *selected* `0xF2FFFFFF` with dark words | Games `pill()` (`LibraryActivity.kt:349`) |

### 2.5b TV Home looks: tile style and accent (theme keys `tstyle`, `accent`)

Two choices that let a ready-made theme look different from another one, not only its wallpaper. Both are in `THEME_KEYS`; absent or unknown = the default = TV Home as it was, pixel for pixel.

| `tstyle` | An app tile is | Name | Focus |
|---|---|---|---|
| `banner` (default) | the TV banner on a 16:9 card, or the icon on a dark card | under the card, while focused (inside the card for an icon) | ring or glass round the card |
| `icon` | the app's icon large on the wall, **no card** (a phone's home screen) | under the icon, always | the same ring / glass round the icon's cell |
| `glass` | a frosted card (`0x2EFFFFFF` fill, 1 dp `0x55FFFFFF` edge, a lighter top half) with the icon and the name | inside the card, always | the same ring / glass |

Kinds that are a picture of their own stay a card in every style: a live widget (`tile:`), a folder's picture, an app with the person's own picture (`pics`), a pin with its picture, a Play Next poster. The "+ Add" and "Get TV Setup" tiles take the style (a glyph in the icon / glass form). A weak box pays nothing: no blur (the frost is a fill), no layer other than the card's.

`wplace` (where the widgets sit): `top` (default: one row under the top bar) or `left` / `right` (a column beside the grid, 20 dp under the top bar like the grid; blocks packed into the screen's height, `HomeConfig.packColumn`). Beside a column every row of apps shows, so the Rows setting is hidden there.

`accent`: `""` (default: today's blue ring, red date and today) or one colour of the palette. One colour for what says "this is chosen / now": it is chosen to be seen on a dark and on a light wall (contrast against `#0E1116` / `#F4F4F6`, both at least 3.3 except blue 4.0 on light and violet 3.9 on dark).

| Name | Value | Dark / light |
|---|---|---|
| blue | `#2F72F0` | 4.3 / 4.0 |
| teal | `#0E9F92` | 5.8 / 3.0 |
| green | `#2E9E55` | 5.5 / 3.1 |
| amber | `#C57400` | 5.3 / 3.3 |
| orange | `#E8590C` | 5.3 / 3.3 |
| red | `#E5383B` | 4.5 / 3.9 |
| pink | `#D6336C` | 4.1 / 4.2 |
| violet | `#7B55EE` | 3.9 / 4.4 |

It tints: the focus ring's outer band (`TvTile.ringDrawable`) and the rim and glow of the gliding glass (`Glass.accent`), the clock widget's date line (modern / digital faces), the calendar's "today" disc, the top bar's rule between the clock and the day. It tints nothing else: tonal widgets keep their own colours, the app's blue (`#8AB4F8`) stays the "chosen item" colour in menus, and the ring of a tile being moved stays the carry ring. The page keeps the same table in `js/home_walls.js` `HOME_ACCENTS` (a test keeps it equal to `HomeConfig.ACCENTS`).

### 2.6 Motion (ms)

| Move | Duration | Interpolator |
|---|---|---|
| Tile focus in / out | 520 / 300 | `Motion.spring` (damped spring, 0.62) / `Motion.glide` (`HomeActivity.kt:1075-1080`) |
| Ring fade in / out | 200 / 160 | glide |
| Glass glide | 460 (to another shape too: two panes cross, one fading in, one out); first show fade 180; hide 140 | spring (`Glass.kt` `to`, `slide`) |
| Edge nudge (nothing that way) | 80 out + 460 back | glide, then `Motion.bounce` |
| Small card / tile scale | 120 (Channels, Setup), 200 (Games pill), 420 / 220 (`Motion.pop`) | default / spring |
| Overlay / hint fade | 150 | glide (`HomeActivity.kt:478-485`) |
| Page in | 240 (420 sliding) | glide (`:314`) |
| Page scroll (TV Home) | 360; only as far as the focused row needs (40 dp from the edge), never a jump to the upper third | `HomeActivity.EASE` (eased in and out: glide's fast start read as a jerk) |
| App name under a tile | 220 in / 120 out, fades and slides down from `-lift/3`, in *calm* too (one small text, no layer) | glide (`TvTile.focus`) |

Durations go through `Motion.ms(t)` (Home) / `ms(t)` (Games) so *calm* shortens them; with motion off, set the end state at once.

## 3. Components: reuse, don't rebuild

| Component | Reuse | App | Notes |
|---|---|---|---|
| **Header** (icon + app name) | `buildRail()` head (`BrowseActivity.kt:210-220`), Setup header (`MainActivity.kt:158-165`), Games (`LibraryActivity.kt:306`) | Channels, Setup, Games | Home has none (clock + date). Name: 24 sp bold `tv_ink`, never the accent |
| **List row** | `TvUi.row(c, icon, label, count) { go }` (`tvui/Tv.kt`) | Channels | icon tinted `TEXT`, count in `DIM`; `isActivated` = the one in use |
| **Dialog sheet** (choices) | `TvUi.sheet(c, R.style.Dialog, w)` + `TvUi.sheetTitle()` + `TvUi.row`s (`tvui/Tv.kt`) | Channels | 380-420 dp wide; Back closes it |
| **Popup on a scrim** | `scrim(panel(title).apply { addView(menuButton(...).withIcon(R.drawable.ic_x)) })` (`tvui/Tv.kt`, used `LibraryActivity.kt` `popup()`) | Games | always add a Close button; Back closes; first entry focused |
| **System list menu** | `TvGlass.menu` (glass panel, icon + label rows, as Customize; focus = the brighter fill + white edge, no growth: a 1.06 pop redrew the panel ~22 frames a move) | Home's every long-press menu (apps, widgets, top-bar buttons, Play Next: `elementMenu`; a switch is `Item.on`, a live value `Item.live`); Setup uses `TvUi.menu` (see Dialogs below) | for plain lists of actions only |
| **Play Next tile** | `Watch.art` (TV Home) | the Play Next row and its tiles in the grid: a wide still fills the tile with a bottom shade and one title line; a tall poster sits left on its own blurred, dimmed backdrop with up to 3 title lines beside; a logo stays small; progress bar along the bottom, app icon bottom-right | posters drawn as logos (small, lost in the tile) |
| **Quick settings** (tiles + round actions) | `ui/ControlCenter.kt`, laid out by use: `TvGlass.panelBg` 460 dp; ONE row of two **big tiles** (`TvGlass` row tile, padding 16 dp, icon 22 on a 40 dp disc, name 16 sp, state 12 sp `TvGlass.HINT`: the most pressed, Customize and Sleep timer), then ONE row of 3-4 **small tiles** (`CcTile(small = true)`: icon 20 on a 36 dp disc over a centred name 12.5 sp and a one-word state 11.5 sp `HINT`; switches and states read more than pressed: Network, Inputs or Bluetooth, Mic button, Kids mode). As Android's quick settings, a tile's disc is `ROW_FOCUS` with a white icon, and **on** (`CcTile.on`: the timer running, kids mode, a network, the mic ours, Bluetooth) `#8AB4F8` with a `#202124` icon. The **brightness bar** is Android's slider: a 52 dp pill (`ROW_FOCUS`), filled with lit glass (white 28 %) to the value (at least a disc round the sun), the sun 22 white inside the fill, the value 13 sp bold at the end (white on the fill, `HINT` on the glass), a 2 dp ring white when focused, `RING_ACCENT` while OK has it changing; beside it the tint's 52 dp round button, `Motion.pop` (calm motion: grown at once, no animation); below them ONE row of 3 **round actions** (centred): a 48 dp disc (`ROW_FILL` / `ROW_EDGE`, focused `ROW_FOCUS` + white 1 dp edge), icon 22, a short name under it 12 sp `HINT`, then a 12 sp `HINT` footer line; "Turn off the TV" opens a small `TvGlass.panelBg` dialog (380 dp, title 20 sp, four row tiles: Screensaver, Screen off, Restart, Power off; focus on Screensaver) | Home | no look of its own: Customize's tile + the glass tokens |
| **Drop-down from a head button** | `Drop.show(a, anchor, rows)` | Home | a Dialog: Home and Back close it |
| **App / game tile** | Home `Tile` (`HomeActivity.kt:857`); Games `shell()` | Home, Games | 16:9; rings outside the face; name under it, in the row gap |
| **Action tile** (icon, title, one line) | Setup `Tile` (`MainActivity.kt:73`): `say()`, `work(on)` spinner, `enable()` | Setup | |
| **Channel card** | `Cards` adapter (`BrowseActivity.kt:694`) | Channels | logo on `LogoFit` light/dark tile; initials when no logo (`Ui.initials`) |
| **Button** | Setup `smallButton()` (`:1075`); Games `menuButton()`; `TvUi.button` (bold 16 sp on `states(CARD, 22)`, tvui) | per app | a verb, short; `isAllCaps = false` |
| **Chip / choice** | `pill()` (`tvui/Tv.kt`) | Games | `isSelected` = chosen |
| **Text field** | `TvUi.field(...)`: a `TvField` on `states(c, CARD or FIELD_REST, 22 / 18)`, `isSingleLine`, `IME_FLAG_NO_EXTRACT_UI` + `IME_ACTION_SEARCH` / `DONE`; `raise()` for a field that opens in front of the person; `first` for the Back pattern | Channels | see the Back pattern below |
| **Toast** | `Toast.makeText(...)`: one-line results only (`pin_refused`, `guide_none`, Home `toast()` `:836`) | all | never the only way to report an error the person must act on |
| **Notice over other apps** | `NotifyLayer` (corner card, `#F2202124` r20, 22 sp) ; `WarnActivity` (scrim + card + countdown + buttons) | Setup | corner / seconds clamped in shared `Notify.kt` |
| **Update question** | `SelfUpdate` (`app/shared/src/selfupdate`): `check { askUpdate(it) }` on open, then the app's own popup with Update now / Later / Skip this version (`upd_*` words), the download with Cancel, Android's installer last | Home, Channels, Games | at most every 12 h; Later = a day; Back = later; never over a game or a dialog already open; **not at all** while TV Setup (auto-update on, ADB ready: HomeInfo `updates`) installs it quietly, until the release is 3 days old to the app (`UpdatePlan.GRACE`): TV Setup's `AutoUpdate` does it every 3 h, awake only when the app is not on top and Channels / Games were not in front for 30 min |
| **Loading / empty / error** | `TvState` (`tvui/Tv.kt`, Channels `chrome()`): one full-screen state (spinner tinted accent, + a bar under the words when the size is known: `loading(words, percent)`, then the words + buttons, the first focused); in-grid note `say()` (`:370`); Setup `Tile.work()`; Games download bar (Back cancels) (`LibraryActivity.kt:696`) | | words say what to do next (`offline`, `none_here`, `no_match`); a long wait shows progress and can be cancelled |
| **Voice card** (the remote's mic) | `MicActivity` + `MicLevel` (the one drawn view: a live sound graph, 15 bars of the last second sliding left, newest at the right, app accent fading to the left; frames only while listening, one redraw per level with animations off) | Setup | `TvUi.CARD` 620 dp at the bottom; one step at a time on its second line (listening, heard: `mic.thinking`, doing: accent `→ …`, error: `BAD`), each change a 90 / 140 ms fade |

**Text field + keyboard + Back** (`BrowseActivity.kt:562-620`, `countries()`): the keyboard eats Back, so

1. subclass `EditText` and override `onKeyPreIme`: on `KEYCODE_BACK` + `ACTION_DOWN`, if there are results, hide the IME and move focus to the results (`toList()`), return true;
2. set a `swallowUp` flag and eat the matching key-up in `dlg.setOnKeyListener`, or the Dialog closes too;
3. D-pad Down from the field → the list; Up from the list's first row → the field; the IME action key also goes to the list;
4. empty field or no results: Back behaves normally (closes).

### Tonal widgets (TV Home's home screen widgets, style "tonal")

The other look of EVERY home screen widget but the clock (weather, system, lunar, air, calendar, usage, timer, market, news and the QR widgets; option `look` in `wopt`, `HomeConfig.options` / `HomeConfig.TONAL`; "glass" stays the default; the clock has its own face and theme). One path draws all of them: `widgets/Tonal.kt` (`tonalFill` says what each widget shows, through `tonalBody` / `systemTonal` on `tonalCard`, `Scallop`, `TonalBar`); a widget never hand-paints its own tonal style, and a new widget gets the look by joining `HomeConfig.TONAL` and one line in `tonalFill`. The settings dialog on every client draws the option from the schema, nothing else to add. Per widget: calendar = weekday / day number / date + lunar date; usage = the week's time / top app; timer = time left / when it ends; market = first price / the next two (the ticker layout is the glass widget only); news = one headline (a sentence, not a figure: `tonalBody(headline = true)`) / source and time; QR = the code on its white square in the tonal widget, words in `Tonal.INK`. In a ready theme all widgets are one family: glass with glass, tonal with tonal (and a light clock beside tonal).

Two more looks for every widget, the clock too (`HomeConfig.LOOKS`; the clock's are `glass` / `solid` / `none`): **solid**, the glass widget's shape filled nearly opaque `#181B22` (`Frost.look`, a busy photo stays out); **none**, no widget drawn at all, the words on the wallpaper with a 6 dp shadow (`Widgets.bare`), for a person whose own photo is the point. Both are the glass widget's layout (one drawing path: `Frost.draw`), only the fill changes.

**StandBy faces** (iPhone StandBy's spirit, drawn once a minute like every face): the clock's `face` adds **modern** (60 ticks, the
hour ones bright, 12 numbers, the weekday and day in the dial in the calendar's red `ACCENT`) and **digital** (a screen in the
theme's disc colour: the date line in red over the time, as large as the widget allows; it fills the widget at both sizes, the pill keeps
the plain digits). The calendar's `layout` adds **date** (the weekday in red over the month's name, the day as the widget's figure, the
lunar date under it) beside the month grid. No second hand on Home: a redraw each second costs a weak box the D-pad's smoothness.
The screensaver's StandBy styles are these same views, larger (`HomeDream`).

| Part | Value |
|---|---|
| Widget | opaque fill `Tonal.FILL` `#F6E9E0` (one warm tone, **not** taken from the wallpaper: TV Home has no colour pick), radius **28** dp, no border, no shadow, height = the widgets' `BIG_H` |
| Words | ink `Tonal.INK` `#3B2A22`; 3 lines: label 14 sp (placed first), figure 64 sp regular (shrinks to 30 in a narrow widget), caption 14 sp in `Tonal.SUB` |
| Badge | `Scallop`: a circle with 9 bumps (Material 3 "cookie"), 100 dp (smaller in a narrow or small widget), top right. Pale `Tonal.PALE` + dark icon, or dark olive `Tonal.OLIVE` + light icon (air) |
| Bar (system rows) | 10 dp rounded, fill `Tonal.BAR` on track `Tonal.TRACK`, a small dot at the end of the track |
| Small (half height) | same parts, figure 30 sp, badge 52 dp |

### Theme tags (TV Home's Themes page)

A theme tile carries tags top left (the check is top right): `▶ Video`, `🖼 ×N` (a rotation of N backgrounds), `🖼 Slideshow`. A pill like the name scrim (`NAME_SCRIM`, radius 10 dp), 11 sp white, from `ThemePreview.badges`; the web page's `.badge` on a tile is its twin.

### Live editors (TV Home's Customize)

A value seen best on the real home screen (a size, a step, the own wall's colours) is edited in a **small glass card top left**, the
Customize panel hidden, the change applied behind it as it is made (`Customize.stepper`, `ownColours`): one row per value, ◀ ▶ change
it, ▲ ▼ move, OK closes; a colour row shows its chip (`SWATCH_EDGE`), walked through a fixed `PALETTE` (12 hues × deep / mid / soft +
greys) instead of a free picker the D-pad cannot drive. The page's twin is `input[type=color]` (`app/shared/web/DESIGN.md`).

### App badge on a shortcut's picture (TV Home)
A shortcut (a scene, a channel, a game) is drawn with its OWN picture and its app's logo in the bottom-right corner, so a picture alone never leaves "which app opens this?" open: `Watch.corner` (logo 24 % of the picture's height on a wide card; on a shortcut's square picture 40 % of its side, with a thin dark ring so it reads on any picture; 11 % in from the edges (4 % at 40 %), a see-through dark plate, rounded like an app tile). Same on a wide pin's card, a square pin on the grid (`Pins.squareBadged`) and a row of + Add (`Pins.rowIcon`, 192 px, kept by key + the shortcut's `lastChangedTimestamp`). No badge when the picture already shows the app (Play Next items, a card that has it).

### Dialogs

One look for every dialog in TV Setup and TV Home (`tvui/Tv.kt`, theme `R.style.TvSheet` from `tvui/res`; `:tv` and `:home` both include that res dir). Don't build another.

- **A list of choices: `TvUi.menu(c, R.style.TvSheet, title, items, R.drawable.ic_check, accent)`**, rows are `TvUi.Item(icon, label, sub, on, go)`. Never `AlertDialog` + `setItems`.
- **A question: `TvUi.confirm(c, theme, title, message, okLabel, cancelLabel, danger, accent) { ok }`.** `danger` (removes or revokes something): Cancel first and focused, OK in red.
- **Icons, not emoji**: each row has the app's own `R.drawable.ic_*` vector, tinted with the app's accent. No emoji or symbols in labels.
- **A switch is a ring with the check inside while on** (`on = { state }`), not "✓" / ": off" in the words. Pressing it flips it in place and the menu **stays open**; an **action closes** the menu, then runs.
- **Detail goes on the second line** (`sub`, dim, up to 3 lines), not joined to the label with " · ".
- **Dialogs take the app's accent** (`accent =`, like `TvUi.row`): TV Setup passes its blue, the others the default.
- A progress or one-button notice is a `TvUi.sheet` + `sheetTitle` + `sheetNote` + `TvUi.button`. Full-screen pickers with checkboxes or pictures (Optimize TV's list, the launcher gallery) are screens of their own, not dialogs.

## 4. Words

- **Home, Channels, Games:** every visible string in `app/shared/i18n/apps/<lang>/<module>.json` (every language of
  `langs.json`, same ids; Gradle makes `res/values-<lang>/strings.xml` of them: there is no hand-written strings.xml). No string literals in Kotlin.
- **TV Setup:** no strings.xml; `T("area.id", "n" to 3)` from `app/shared/i18n/vi.json` + `en.json` (the AGENTS.md rule:
  `vi.json` first, `en.json` complete). No new `t("vi", "en")` pairs.
- Short labels: a verb or a noun, 1-3 words on a button, one line in a row; details go in the sub-line (`tv_dim`), not the label.
  Check that the Vietnamese fits too: it is usually longer.
- Emoji: allowed in content and big states (Warn icons `😴 🌙 🧠 ⏳` `WarnActivity.kt:46`, Setup's rule lines, Games `📱`).
  **Plain marks (✓ ✗ ✕ ⏻ ▶ ♪ ★) never**: a TV's font draws them at its own size or not at all; use an `ic_*` drawable.
  **Controls use line icons** (§5), not emoji.
- Numbers and names come through placeholders (`%1$d`, `{n}`), never glued strings.

## 5. Icons

- Line icons only: 24×24, stroke 2, round ends (Lucide shapes), white, tinted in code (`imageTintList` / `setTintList`).
- Add one in `dev/gen/gen_icons.py` (`ICONS`, or `GAME_ICONS`), run `python dev/gen/gen_icons.py`: it writes `ic_*.xml` into
  android, tv, home, channels and games (games only the names in `GAMES`, `gen_icons.py:154`) and the web sprite.
  **Never hand-edit `res/drawable/ic_*.xml`** (each says "generated by dev/gen/gen_icons.py").
- Sizes: 20 dp in a row (`Ui.row`), 22 dp in a menu button (`withIcon`), 26 dp badge in a Setup tile; icon colour = the words' colour
  (`setTintList(textColors)` so it turns dark with the words under a white focus).
- App icons and banners: `banner.png` / `icon.png` per app; rounded corners from `dev/gen/round_icons.py`.

## 6. Checklist before "done"

1. `dev/test/emu.sh` (or `install`), screenshots from **`tvremote_gtv`**: `adb -s emulator-5554 exec-out screencap -p > shot.png`.
   Look at each new or changed screen: no cut or overflowing label, no wrapped row, focus visible, inside the 48 × 27 dp margins.
2. **D-pad walk** with `adb shell input keyevent` (19-22 arrows, 23 OK, 4 Back, 82 Menu; hold OK = `--longpress 23`): every control
   reachable, none skipped, no focus trap, Back closes each layer in order, hold OK / Menu open the item's menu.
3. **Both languages**: Vietnamese and English (Channels: its Language row; the others: the TV's language). Long Vietnamese words still fit.
4. **Motion off** (`settings put global animator_duration_scale 0`): the end states are right with no animation.
5. Text fields: open the keyboard, press Back, check the focus lands on the results and the dialog stays.
6. `node --test app/shared/tests/` (`tv_design.test.js`: no new raw colour outside a named constant, one `tv_style.xml`, words in both languages, generated icons); unit tests of the app (`./gradlew :<app>:testDebugUnitTest`); TV Setup words: `node --test app/shared/tests/` (`i18n.test.js`).
7. **Visual check against a neighbour**, on the screenshots, before calling it done: inner padding = the tokens (§2.3), not 0;
   same size, corner and focus as the siblings in the same row or list; built from a §3 component (a new `onDraw` view fails
   `DRAWN` in `tv_design.test.js`). "It works" is not "it looks right": the Free RAM tile shipped glued to its edges (2026-10-23).
8. Report what was checked where, and what was not (a real 1 GB box, a real remote's long press, overscan on a real panel).

## 7. Known drift (documented, not fixed: don't copy it, don't add more)

Raw colours are counted per file in `app/shared/tests/tv_design.test.js` (`RAW`): a count may only go down.

| # | What | Where |
|---|---|---|
| 1 | (fixed in phase 2: ring, tile radius and glass come from tvui) | |
| 2 | Two focus lifts: TV Home and Games tiles grow **1.12×** on a 520 ms spring, Channels cards and Setup tiles **1.06×** in 120 ms (§2.5 keeps both: big tiles vs small cards; don't add a third) | `HomeActivity.kt:967`, `LibraryActivity.kt:917`, `BrowseActivity.kt:726`, `MainActivity.kt:112` |
| 3 | Muted grey: `tv_dim` `#9AA0A6` vs Setup `MUTED` `#8C96A3`, Warn `#AAB2BD`, Games `#AAB0C0` `#8892A6` `#6B7385` `#9AA3B8` `#D5DAE6`, Home white-alpha `0xA6FFFFFF` / `0x8CFFFFFF` | `MainActivity.kt:66`, `WarnActivity.kt:61`, `LibraryActivity.kt:265,276`, `LobbyView.kt:36-46`, `Widgets.kt:75`, `Drop.kt:27` |
| 4 | Panel fill / radius: `tv_card` r16 (Games) vs Channels sheets `0xF21B2028` r20 + hairline, Warn `#191D23` r20, Notify `#F2202124` r20, Home Drop gradient r20, Customize r28, Setup system AlertDialogs | `games/Ui.kt:54`, `BrowseActivity.kt:395,462,567`, `WarnActivity.kt:44`, `NotifyLayer.kt:81`, `Drop.kt:37`, `Customize.kt:270` |
| 5 | Scrim: Games `0xD90E1116` vs Warn black at 200/255 | `games/Ui.kt:69`, `WarnActivity.kt:39` |
| 6 | Button focus: ring (tiles) vs Games white fill + 4 dp white edge vs Setup / Warn solid accent fill `#3B73F6` + 2 dp white vs Home white fill | `games/Ui.kt:27,77`, `MainActivity.kt:1083`, `WarnActivity.kt:138-139`, `Drop.kt:53` |
| 7 | App accent differs: Channels teal `#5EEAD4` (and activated `0x2214B8A6`), Setup `#3B73F6`, Home `#8AB4F8`, Games lobby `#4F8CFF` | `channels/Ui.kt:18,42`, `MainActivity.kt:67`, `home/styles.xml`, `LobbyTheme.kt:11` |
| 8 | Tile radius: `tv_radius_tile` 10 vs Channels logo tile 12, player tiles 10 / 6 | `BrowseActivity.kt:696-733`, `PlayActivity.kt:105,394` |
| 9 | Danger red: Channels `#F28B82`, Setup `#FF7070`, Home lock `#FF8A80`, Games `#F87171` | `channels/Ui.kt:21`, `MainActivity.kt:70`, `HomeActivity.kt:1024`, `GameCards.kt:142` |
| 10 | Text below the 12 sp floor / small: Setup tile sub-line 11 sp, title 13 sp | `MainActivity.kt:100-104` |
| 11 | Fields: Channels `Ui.states` r22 / r18; Games search hint `0x99FFFFFF` on its own look, rename field `focusBg(10, #2A3042, #3B4460)` | `BrowseActivity.kt:94,551`, `LibraryActivity.kt:308,609` |
| 12 | Language: Channels has an in-app picker (`Lang.kt`); Home and Games follow the TV; Setup follows the TV through `Words` | |
| 13 | Font: TV Home bundles Inter (`Font.kt`); the others use the system font | |
| 14 | Games `LobbyView` predates the family look (its own bg `#0B0D12`, greys, sizes up to 150 sp) | `LobbyView.kt:36-67` |

Fixing drift is a separate change: pick the reference value (§0), change every copy and this table.

## 8. Phone app's native screens (`app/android/.../phone/`)

They mirror the web UI (`app/shared/web`, `css/kit.css`), not the TV apps.

- Colours only from `Palette.kt` (`dark` and `light`, both checked by `PaletteContrastTest.kt`), copied into `Ui.kt` by
  `applyPalette()`. No raw hex in a screen.
- **Two blues**, as on the page: the full `ACCENT` fill = a button that *does* something (OK, Send, Install); *on / chosen* =
  `setOn(on, radius = 14)` (`Ui.kt:120`): `onBox` = `SEL` tint + 1.5 dp `ACCENT` edge, words `ACCENT_TEXT` (`Ui.kt:116`). Never the fill for "on".
- Build with the helpers in `Ui.kt`: `box()`, `button()`, `label()`, `head()`, `note()`, `row()`, `input()`, `inDialog()`,
  `setBusy()`, `say()`; icons via `icon()` / `IconButton` from the same `ic_*` drawables.
- Words: `T("id")` from `i18n/*.json` (`core/Words.kt`); `t(vi, en)` (`Ui.kt:76`) is legacy, no new pairs.
- A change to the remote's keys or menus goes in `app/shared/web/remote.json` (read by `PhoneRemote.kt`), not by hand; any other web
  UI change is mirrored by hand here, or the change says why it can't be (AGENTS.md).
- Check on the phone emulator `tvremote_test`, both themes and both languages, with screenshots.
