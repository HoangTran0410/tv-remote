# app/android: the Phone app

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## What it is

| Name | Code | `data-core` | What it is |
|---|---|---|---|
| **Phone app** (App điện thoại) | `app/android` | `phone` | Android app "TV Remote"; its remote screen is native Kotlin, its Manage screen is the web UI (in-app HTTP core) |

## Files

```
app/android/.../phone/   phone app: extension functions on MainActivity, one file per part, in a folder per feature (as app/tvhome's)
  (root)                 what Android or the OS names or starts: PhoneApp, MainActivity (state, lifecycle), SceneActivity + SceneService
                         (widget, tiles, shortcuts), ListenService (🎧), WorkService, QuietUpdate (silent on Android 12+; `$Done` receiver),
                         Tiles (Quick Settings), RemoteWidget. Manifest names, pinned widgets and shortcuts point here: never move them.
  remote/                the remote screen: RemoteScreen, PhoneRemote (from remote.json), Typing, KbdWatch, KeysOnly, InputsMenu,
                         Rescuing (Unfreeze TV), GamesPointer, PadMap (a game controller -> keys; the page's is `js/gamepad.js`)
  picture/               the TV's picture: Pip (floating player), VideoFrame, Drawing (✏️), AirMouse (the gyroscope as a pointer: a virtual
                         HID mouse through scrcpy, `Tv.mouseOpen`; LG's pointer by `Remote.pointer`)
  media/                 sound and what plays: Listen (🎧), Talk (quick talk), NowPlayingBar (+ the TV's clipboard), NowApp, Media (links, shares, files to the TV)
  picker/                the list of TVs and getting in: PickerScreen, ManyTvs (install / remove / restart on several), AdbHelp, Diagnosing
                         (the self-check's screen), Wol, TvStatus, SetupBanner (a new TV Setup), Net
  scenes/                Automation (timer, scenes, voice), SceneStore, Headless (acting with no remote screen)
  manage/                Managing (the web UI in a WebView), InfoScreens (Settings, About), Updater, PhoneSupport
  ir/                    the infrared remote on the phone's IR LED: IrRemote (screen), Ir (encoders NEC / Samsung32 / SIRC / RC5 / RC6 /
                         RCA / raw), IrCodes (`assets/ir/codes.json` made by `dev/gen/ir_codes.py` from Flipper-IRDB, CC0), IrAuto, IrLearn
  ui/                    Ui (colours, words, view helpers), Popup, IconButton, Palette
```

## Remote screen

Its keys and menus come from `app/shared/web/remote.json` (`PhoneRemote.kt`, `phoneActions`): the rule is in
[app/shared/web/AGENTS.md](../shared/web/AGENTS.md) "The remote screen is drawn from one file".
