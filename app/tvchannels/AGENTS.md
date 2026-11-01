# app/tvchannels: TV Channels

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## What it is

| Name | Code | `data-core` | What it is |
|---|---|---|---|
| **TV Channels** | `app/tvchannels` | | iptv-org's free channels on the TV (`dev.tvremote.channels`); knows nothing of the other apps: they reach it only by its links (`tvchannels://play/<id>`, `group/<id>`, `find/<name>`); installed and opened from every client over ADB (`core/ontv/Channels.kt`, `js/channels.js`) |

## Files

```
app/tvchannels/            TV Channels: BrowseActivity (countries, categories, favourites, groups, search), PlayActivity (ExoPlayer, the
                         lineup, a dead stream falls to the next), ChannelsReceiver + Edit (favourites and groups edited from the clients over ADB: DUMP-protected, whole state by channel id, `Edit` cleans it; a country's channels go to a file the shell reads: `core/ontv/Channels.kt` `manage`, `/api/channels/manage`, `js/chan_manage.js`), Catalog (iptv-org's m3u, kept a day), Playlists (the person's own m3u links, max 5, set with `lists` in the same broadcast: `Edit.cleanLists`, fetched with a 5 MB cap, kept a day, a row each under "Your lists"; Xtream Codes not done), Store (prefs), HomeRows (favourites as a preview channel and the last 3 watched in Play Next, in TvProvider for any launcher: the TV's own, TV Home's Watch row), Find (a name -> a
                         channel), Pins (links and pinned shortcuts), ChannelLiveWidget + HomeInfo (the "Keep watching" live widget for TV Home, which it alone may ask), Providers (more sources than iptv-org, e.g. VieON; kind `streams` = the stations' own public addresses written in the list itself, a backup stream when the name matches a channel: each kind is
                         code here, their addresses / headers / on-off in `docs/channels/providers.json`, signed by `dev/sign/sign_providers.py`
                         with the launcher list's key, read from the site, else GitHub, else the APK's packed copy; Ed25519 from
                         `app/shared/src/sign`), Guide + GuideList (the programme guide from the providers with a "guide": TV360 by
                         name, VieON by its id; now / next on the player and the cards, the day's list). AndroidX (Media3, RecyclerView) here only; no :shared
```
