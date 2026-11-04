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
| Random edits | `TilesTest` 400 seeds x 60 steps: hide (from the grid, or Customize's list for an app in a folder), Choose apps' Done, choose one back, remove, add live / pin, Android pins, folders (new, add, out, delete), dock, uninstall; after each, `Tiles.kt:problems` | a tile lost, shown twice, a folder on no grid, a grid tile a folder cannot take, a wrong menu; a grid that does not start with `Tiles.kt:dock` (`HomeActivity.show` splits it there, from the same conf: the dock read from Store with the grid from an older conf added one tile twice, crash 3e2e54ba) | `randomEditsLoseNoTile` |
| The real TV | `dev/test/home_tiles.sh <serial>`: HomeReceiver `tiles` answers `HomeActivity.tilesCheck` (the drawn grid against `Tiles.kt:grid`, `Tiles.kt:problems` on the real apps and pins, each tile's real menu rows against the table) | what only a TV has: installed apps, Android's pins, live widgets that answer, the drawing | |
| The real UI | `dev/test/home_walk.py <serial>`: the remote's keys through hide / + Add / Choose apps, a live widget's Remove / + Add, an Android pin into a folder and out; the TV check after each step, screenshots; the grid settings put back | what the person meets: menu words, counts, a tile that does not come back | |

Run the TV check after any change to tiles, folders, pins or the tile menu. A new kind or action: a row in the table below and in
`Tiles.kt:acts`, then the UI reads it.

## The kinds (`Tiles.kt:kind`)

| Kind | Key | On the grid because | Menu (`Tiles.kt:acts`) | Off the grid |
|---|---|---|---|---|
| app | `com.x` | installed, not in `hidden` / a folder | Move, Put in a folder, Rename, Own picture, **Hide**, Start, Lock, Maintenance, Play Next | `Tiles.kt:hide` (Customize's toggle) / `Tiles.kt:choose` (Choose apps' Done): into `hidden` and out of the dock and its folder (`Folders.kt:hide`; the web page the same, `HomeFolders.hide`), back through Choose apps (`Tiles.kt:hiddenShown`); a folder member in `hidden` is dropped by `HomeConfig.clean` (it shows in its folder) |
| live widget | `tile:pkg/id` | its key in `tiles` | Move, Put in a folder, **Remove**, Start | `Tiles.kt:remove`: out of `tiles` / dock / order; + Add brings it back |
| shortcut | `pin:pkg/id` | pinned through Android (no key in `tiles` needed), or its key in `tiles` | Move, Put in a folder, Rename, Own picture, **Remove**, Start, Link | `Tiles.kt:remove` + `Pins.off` (the system pin too, or it came back) |
| folder | `folder:id` | its key in `tiles`, holding a member | its own menu (`openFolder`); Choose what's in it (`Folders.kt:setApps`: the ticked ones in, the unticked out, each through `put` / `out`) | emptied, or Delete: its tiles come back where it was |

In a folder, any kind has Out of the folder and Move inside the open panel (the arrows carry it, OK saves through `Folders.kt:reorder`, Back cancels; Earlier / Later only when no panel is open) instead of the grid's Move, and no Hide / Remove (out of the folder first).
A shortcut pinned through Android that goes into a folder gets its key into `tiles` (`Folders.kt:keep`): that is what keeps it.
