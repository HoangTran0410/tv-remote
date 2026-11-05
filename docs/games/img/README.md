# Game pictures

One 640x360 JPEG per catalog game (`img` of its entry in `../games.json`, path relative to `docs/games/`), shown on its tile in TV Games
(Suggestions and, once downloaded, the library). The app reads them from the site, else from GitHub raw, like `games.json`, and keeps them on the TV.

Each is a logo card drawn by `dev/gen/game_logos.py` (our own art, the repo's licence): one background gradient, the game's name on top and a
flat sticker-style emblem in the middle, all from shapes. The bottom-left 22% x 22% stays empty because TV Home draws the TV Games badge
there when a game is pinned. The `.v2` in the names is the drawing's version: the app keeps a picture under its path (`Thumbs.kt`), so
a redraw gets a new name (`SUFFIX` in the script) and `img` in `games.json` follows, then `dev/sign/sign_games.py`.

The 15 NES / homebrew games have their own pixel look (`.v3`, `RETRO` + `draw_retro` in the script): the game is 8-bit in play, so its card is too. 160x90 pixels shown at x4 with nearest-neighbour (no antialias), a 5x7 pixel-font name, a 16x16 sprite in a NES palette, dark background with stars, scanlines and a small NES badge bottom-right. A new NES game: add its sprite to `SPRITES` and a line to `RETRO` as well as `GAMES`.

Emblems only suggest what a game is; they copy no game's art. The games themselves keep their own licences:

| Game | Licence | Source |
|---|---|---|
| Robo Run (Haseeb) | GPL-3.0 | https://github.com/heaven-hm/roborun-nes |
| Tobu Tobu Girl Deluxe (Tangram Games) | MIT (code), CC BY 4.0 (art, music) | https://github.com/SimonLarsen/tobutobugirl-dx |
| Nova the Squirrel (NovaSquirrel) | GPL-3.0 | https://github.com/NovaSquirrel/NovaTheSquirrel |
| Witch n' Wiz (Matt Hughson) | MIT | https://github.com/mhughson/mbh-A53-witchnwiz |
| Stallar, File Fixers (Wendel Scardua) | MIT | https://github.com/wendelscardua/stallar (file-fixers) |
| 8-Bit Table Tennis (Mike Michel) | MIT | https://github.com/mike42/8bit-table-tennis |
| openNES Snake / Breakout / Pong (Sebastian Dine) | Zlib | https://github.com/sebastiandine/openNES-Snake (-Breakout, -Pong) |
| Concentration Room, RHDE: Furniture Fight, Thwaite (Damian Yerrick) | GPL-3.0+ / GNU All-Permissive / GPL-3.0+ | https://github.com/pinobatch/croom-nes (rhde-nes, thwaite-nes) |
| Coco Battle Royale II (Rodrigo Dornelles) | GPL-3.0 | https://github.com/psywave-games/coco-battle-royale-2 |
| Runner (zorchenhimer) | MIT | https://github.com/zorchenhimer/nes-runner |
| Ghosts and Graves (Anthony Bongers) | Unlicense | https://github.com/AnthonyBongers/GhostsAndGraves |
| the 17 web games | same as the repo | `docs/games/web/` |

A new catalog game: add a spec (name, two colours, emblem function) to `GAMES` in the script, run it, add `"img": "img/<id>.v2.jpg"` (`.v3` for a NES game) to its entry and re-sign.
