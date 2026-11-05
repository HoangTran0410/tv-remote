# app/tvgames: TV Games

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## What it is

| Name | Code | `data-core` | What it is |
|---|---|---|---|
| **TV Games** | `app/tvgames` | | retro games (libretro cores, GPL-3: `app/tvgames/LICENSE`) and web games on the TV, played with phones as pads (`dev.tvremote.games`): its own server on 8780 + a QR, no ADB and no TV Setup needed to play; installed, opened and updated from every client over ADB like TV Channels (`core/ontv/Games.kt`, `OwnApp.kt`, `js/games.js`) |

## Files

```
app/tvgames/               TV Games: LibraryActivity (the games, Suggestions from the catalog), GameActivity (LibretroDroid, save / load),
                         WebGameActivity (a web game in a WebView: keys, the Gamepad API and `window.tvpad`, WEBGAMES.md), GameServer (8780:
                         the pad page `assets/pad`, its words `/words.js` from `i18n/apps/<lang>/pad.json` (by Accept-Language), uploads, WebSocket slots 1-4), Systems (extension -> system -> core, pad layout), Catalog +
                         Cores (`docs/games/games.json`, signed by `dev/sign/sign_games.py` with the launcher list's key: cores downloaded by sha256 from
                         the `cores-1` release made by `dev/release/cores.sh`, suggested web games, free homebrew; fceumm packed as the offline fallback)
                         KeyPads (a real keyboard = two pads, one slot per half; `PadMap` fits button names to a core, Mega Drive swaps A/B and X/Y;
                         a keyboard is told from a remote by its letters and no D-pad/gamepad source). Cores must be GPL-compatible, never non-commercial
                         (Genesis Plus GX and PicoDrive are left out on purpose).
```
