# Flow: TV Home's grid tiles (add, hide, remove, folders, the tile menu)

Read [README.md](README.md) first: change the row a fault belongs to, and this file in the same commit.

**Code:** `app/shared/src/home/kotlin/dev/tvremote/home/Tiles.kt`, `app/shared/src/home/kotlin/dev/tvremote/home/Folders.kt`.

**Tests:** `app/shared/src/test/kotlin/dev/tvremote/home/TilesTest.kt`: each row names its test.

## The goal, for the person

Every app, live widget, shortcut and folder they put on the home screen stays reachable: on the grid, in a folder on the grid, or
(apps only) in + Add's Choose apps. A tile's menu offers what that kind of tile can do, and nothing that would lose it.

## How it is checked, without pressing through it by hand

| Check | Where | What it catches | Test |
|---|---|---|---|
| The table | `Tiles.kt:acts` per kind (below) | a menu row a kind should not have (Hide on a live widget) | `menuPerKind` |
| Random edits | `TilesTest` 400 seeds x 60 steps: hide, choose, remove, add live / pin, Android pins, folders (new, add, out, delete), dock, uninstall; after each, `Tiles.kt:problems` | a tile lost, shown twice, a folder on no grid, a grid tile a folder cannot take, a wrong menu | `randomEditsLoseNoTile` |
| The real TV | `dev/test/home_tiles.sh <serial>`: HomeReceiver `tiles` answers `HomeActivity.tilesCheck` (the drawn grid against `Tiles.kt:grid`, `Tiles.kt:problems` on the real apps and pins, each tile's real menu rows against the table) | what only a TV has: installed apps, Android's pins, live widgets that answer, the drawing | |

Run the TV check after any change to tiles, folders, pins or the tile menu. A new kind or action: a row in the table below and in
`Tiles.kt:acts`, then the UI reads it.

## The kinds (`Tiles.kt:kind`)

| Kind | Key | On the grid because | Menu (`Tiles.kt:acts`) | Off the grid |
|---|---|---|---|---|
| app | `com.x` | installed, not in `hidden` / a folder | Move, Put in a folder, Rename, **Hide**, Start, Lock, Maintenance, Play Next | `Tiles.kt:hide`: into `hidden`, back through Choose apps (`Tiles.kt:hiddenShown`) |
| live widget | `tile:pkg/id` | its key in `tiles` | Move, Put in a folder, **Remove**, Start | `Tiles.kt:remove`: out of `tiles` / dock / order; + Add brings it back |
| shortcut | `pin:pkg/id` | pinned through Android (no key in `tiles` needed), or its key in `tiles` | Move, Put in a folder, Rename, **Remove**, Start, Link | `Tiles.kt:remove` + `Pins.off` (the system pin too, or it came back) |
| folder | `folder:id` | its key in `tiles`, holding a member | its own menu (`openFolder`) | emptied, or Delete: its tiles come back where it was |

In a folder, any kind has Out of the folder / Earlier / Later instead of Move, and no Hide / Remove (out of the folder first).
A shortcut pinned through Android that goes into a folder gets its key into `tiles` (`Folders.kt:keep`): that is what keeps it.
