# Flow: the remote's mic button and who listens (the TV's recognizer or TV Setup Voice)

Read [README.md](README.md) first: change the row a fault belongs to, and this file in the same commit.

**Code:** `app/tvsetup/src/main/kotlin/dev/tvremote/setup/MicActivity.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/MicVoice.kt`,
`app/shared/src/main/kotlin/dev/tvremote/core/ontv/VoiceAddon.kt`, `app/shared/web/js/tvset.js`.

**Tests:** `app/tvsetup/src/test/kotlin/dev/tvremote/setup/MicVoiceTest.kt`.

## The goal, for the person

Press the remote's mic button, say a command, the TV does it: with Google's recognizer where the TV has one (it understands any
sentence, in every language), and with **TV Setup Voice** (`app/tvvoice`, Vietnamese commands only, no Internet) where it has none
or Google's cannot answer. The person never picks an engine: the best one that works is used.

## The two switches (Setup tab → Remote buttons card, `tvset.js`; both rules in TV Setup, off by default)

| Switch | Rule | On | Off |
|---|---|---|---|
| Mic button opens TV Remote voice | `mic` | the mic key opens `MicActivity` (KeysBridge) | the key goes to the TV as before |
| Understand speech on the TV itself, no Google needed | `mic_offline` | `MicVoice.syncOffline`: TV Setup downloads `tv-voice.apk` from the release (SHA256SUMS), installs it over its own ADB, grants the mic | uninstalled: no service, model or file stays |

`tv-voice.apk` has no Downloader code on purpose: alone it does nothing (TV Setup grants its mic and gives it the words to listen for).
An older TV Setup: the page asks to update first (`setupFor('mic_offline')`). No ADB: the page says it needs ADB (`failed:adb`).

## Who listens, case by case (`MicActivity.listen`: `MicVoice.own` first, `MicVoice.offline` where there is no own)

| # | The TV | TV Setup Voice | What happens |
|---|---|---|---|
| 1 | has a recognizer (`MicVoice.own`: Google TV, most Android TVs) | off | Google's listens; no network → "No network for speech" and the key goes to the TV for 10 min (`MicVoice.broke`) |
| 2 | has a recognizer | on | Google's listens first (better: any sentence) |
| 3 | has a recognizer, Google answers a network / server error | on | TV Setup Voice listens at once, "say it again" on the card (`MicActivity.onError`) |
| 4 | has a recognizer, speech heard but no word for 3.5 s (Google's server stalled) | on | one fresh try with TV Setup Voice (`MicActivity.stall`); nothing again → "Didn't catch that" |
| 5 | has a recognizer, stalls | off | one fresh try with Google's; nothing again → "Didn't catch that" |
| 6 | none (a box without Google, Google app switched off) | on | TV Setup Voice listens, with the commands, this TV's apps and scenes as its words (`MicVoice.words`) |
| 7 | none | off | "No speech recognizer on this TV" and the key goes to the TV for 10 min; the page's switch is the way out |

Not offered on purpose: forcing TV Setup Voice on a TV whose Google works (row 2). It knows only its word list in Vietnamese and is
no faster; it already takes over in rows 3, 4 and 6, the cases where Google's cannot answer.

## Checking it

`adb logcat -s MicVoice` prints `engine own` / `engine offline`, `network: offline`, `stall: again`. Without a microphone:
`am start -n dev.tvremote.setup/.MicActivity --es say "mở youtube"` (the shell only). Rows 3 and 4 on an emulator: the API 34 TV
image has Google's recognizer; cut the network (`svc wifi disable`) for row 3.
