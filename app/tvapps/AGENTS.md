# app/tvapps: TV Apps

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## What it is

| Name | Code | `data-core` | What it is |
|---|---|---|---|
| **TV Apps** | `app/tvapps` | | free open-source TV apps (SmartTube, Kodi, VLC …) on the TV (`dev.tvremote.apps`); the same list the clients' TV Apps page shows (`js/tvapps.js`, `/api/tvapps`) |

## Files

```
app/tvapps/              TV Apps: AppsActivity (the list, a pill per category, details, hold OK = Open / Install again / Details;
                         TvAppsApp: Guard + OwnReport). The list and its rules are shared with the clients:
                         app/shared/src/tvapps/kotlin (AppList: fetch from the site, else GitHub raw, else the last cached copy,
                         never packed in the APK; ApkCert: an APK is installed only when every signer is one "certs" pins).
                         docs/apps/apps.json + .sig: edit it, then dev/sign/sign_apps.py (checks every source, pins, icons, signs).
```

## Rules

- **Installing: TV Setup first, Android's installer second.** `HomeInfo` `call("tvapps", {op: install|job|cancel, id})` makes TV Setup
  download, check and install over its ADB link with no question; it answers only apps in `CENSUS_APPS` (this app's certificate,
  `app/tvapps/signing.jks`, never committed: `dev/release/keys.sh`). An empty answer (no ADB, an old TV Setup) falls to
  `SelfUpdate.commit` (Android asks); "Install unknown apps" is explained in a sheet before Android's screen opens, and the install
  goes on by itself when the person comes back with it allowed.
- The row's state follows the TV: re-drawn on resume and on PACKAGE_ADDED / REMOVED (registered only while the screen is open:
  Android's installer closes before the app lands).
- Plugins (Stremio add-ons, Kodi add-ons) are never installed for the person; the list names them in "notes".
- An entry with `"setup"` (TV Setup Voice: `"voice"`) is installed by TV Setup on that screen of its own, never by the list: it has
  no `"apk"` (older clients drop it). Install / Open go there (TV Apps: HomeInfo `tvapps` `{op: open, screen}` hands back the
  screen's PendingIntent; the page: `goTo` that card); no TV Setup on the TV = says so, with Install TV Setup.
- AndroidX none; `:shared` none (only its `tvui`, `sign`, `selfupdate`, `tvapps`, `guard`, `report` sources).
