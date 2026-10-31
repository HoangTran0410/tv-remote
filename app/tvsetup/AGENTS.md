# app/tvsetup: TV Setup

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## What it is

| Name | Code | `data-core` | What it is |
|---|---|---|---|
| **TV app** (App TV) | `app/tvsetup` | | the app on the TV, shown as "TV Setup" (keep that label: it is on installed TVs, Downloader codes and file names) |
| **TV web page** (Web của TV) | `app/tvsetup` `RemoteService` | `tv` | the web UI the TV app serves on port 8765, for any browser (iPhone included), after a 6-digit pairing code |

## Files

```
app/tvsetup/                  TV Setup: MainActivity (the TV screen), RemoteService (web server), ManageActivity (the web UI on the
                         TV itself: a WebView in its own process, `?ontv=1`, D-pad focus in `js/ontv.js`), PairActivity, Stats, TrustReceiver, Scope (what a shared browser may do: full / remote / view),
                         Rules + Guard + RulesReceiver + WarnActivity (sleep timer, bedtime, app limits, the full memory notice), DnsWatch (turns off a
                         DNS filter that cut the TV off), DrawLayer + DrawReceiver (the drawing over every app), NotifyLayer + NotifyReceiver (the message card in a corner; `Notify.kt` in the shared code is the one place its corner / seconds are clamped), Nightly (2-5 am in standby: caches, `cmd package compile` of the apps used, `stats/nightly.json` for Health), KeepAdb (ADB debugging
                         back on when switched off, with WRITE_SECURE_SETTINGS granted through ADB), Support (Connection → Send to support, or from a phone / computer / its web page through RulesReceiver "support" and `/api/support/tv`: the facts
                         for an 8-digit code; Let support look: 30 min of GETs on a read-only list of its own routes, plus SupportDump: all prefs scrubbed, its own logcat, read-only shell commands; through the census worker), Eco (the More TV apps row: TV Home / Channels / Games / Apps from `Bundle.apps`,
                         their banners copied in as `res/drawable/eco_*.png`; installed over ADB, else through Android's installer)
```

### By folder (`src/main/kotlin/dev/tvremote/setup/`)

Everything the manifest, another app or an old client names stays at the root, in package `dev.tvremote.setup` (`am start/broadcast -n
dev.tvremote.setup/.X`, `HomeInfo`'s authority, appops): the Application, activities, receivers (`DimReceiver` in `DimLayer.kt`,
`DrawReceiver`, `NotifyReceiver`, `SceneRunActivity` in `ScenePin.kt`, `WelcomeReceiver` in `Welcome.kt`), `RemoteService`, `HomeInfo`,
`KeysBridge`, plus `MicVoice`, `WebViewFix`. Never move one of those into a folder. The rest, by what it does (tests mirror the folders):

| Folder | Files |
|---|---|
| `rules/` | `Rules`, `Guard`, `Clock`, `Kids` (+ `Lockout`), `Totp`, `Usage`: sleep timer, bedtime, limits, the PIN lock |
| `home/` | `HomeApp`, `HomeCompile`, `HomeControl`, `HomeKey`, `HomeKeys` (+ `AutoOpen`), `HomePower`, `LiveWidgets`, `LauncherGuard`, `Reoptimize`: what TV Home asks of TV Setup, the home screen swap |
| `keys/` | `KeyFix`, `KeyList`, `KeyLog`, `KeysAddon`, `HoldKeys`: the key filter and its add-on |
| `link/` | `Paired`, `PairBackoff`, `Scope`, `Tls`, `LinkFailure`, `OnPhone`, `DnsWatch`, `KeepAdb`, `KeepAwake`, `TailscaleWatch`: who may reach the TV and keeping the link up |
| `health/` | `AdbReport`, `AutoUpdate`, `Nightly`, `Stats`, `Support`, `SupportDump`: updates, nightly work, stats, support |

## Key tester

`KeyTestActivity` (+ pure `KeyLog.kt`: names, history, BackExit) shows every key live. Keys the system eats (HOME, app keys) come
from the key filter: while it is open `KeyTest.live()` makes `KeyFix.sync` turn the filter on and `KeysBridge.handle` feeds the
screen and eats them. Reached from Optimize TV, TV Home's Quick keys ("keytest" word in `HomeControl`) and the web page
(`/api/keytest/open`, `SetupNeed` "keytest"). `input keyevent` bypasses the filter: HOME ends the test on an emulator.

## Automation (sleep timer, bedtime, app limits, scenes)

- The timer, bedtime and limits live **on the TV**, in TV Setup (`Rules.kt`, prefs), enforced by `Guard` (standby with keyevent 223,
  closing apps, the notice `WarnActivity`), so they work with every other device off. All three clients reach them the same way:
  `am broadcast -n dev.tvremote.setup/.RulesReceiver --es set <base64 json>` over ADB (`RulesClient.kt`);
  the receiver needs the shell's DUMP permission, so apps cannot call it. No receiver = TV Setup missing or older than the timer.
- Scenes are kept per client (computer: `tvs.json` "scenes"; TV Setup: prefs; phone: `SceneStore`), cleaned by `Scenes.clean`; `GET /api/scenes?download=1` is the backup file the page imports back.
