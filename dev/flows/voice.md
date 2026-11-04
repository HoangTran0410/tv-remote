# Flow: the remote's mic button and who listens (the TV's recognizer or TV Setup Voice)

Read [README.md](README.md) first: change the row a fault belongs to, and this file in the same commit.

**Code:** `app/tvsetup/src/main/kotlin/dev/tvremote/setup/MicActivity.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/MicVoice.kt`,
`app/tvsetup/src/main/kotlin/dev/tvremote/setup/VoiceActivity.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/home/HomeControl.kt`,
`app/tvhome/src/main/kotlin/dev/tvremote/home/ui/ControlCenter.kt`, `app/shared/src/main/kotlin/dev/tvremote/core/ontv/VoiceAddon.kt`,
`app/shared/web/js/tvset.js`.

**Tests:** `app/tvsetup/src/test/kotlin/dev/tvremote/setup/MicVoiceTest.kt`, `app/tvsetup/src/test/kotlin/dev/tvremote/setup/home/HomeControlTest.kt`.

## The goal, for the person

Press the remote's mic button, say a command, the TV does it: with Google's recognizer where the TV has one (it understands any
sentence, in every language), and with **TV Setup Voice** (`app/tvvoice`, Vietnamese or English commands, no Internet) where it has none
or Google's cannot answer. By default the best one that works is used; a person who prefers TV Setup Voice (faster, private) picks it
once and it listens first (`mic_engine` "tv", the user 2026-11-03).

## The two switches (Setup tab → Remote buttons card, `tvset.js`; both rules in TV Setup, off by default)

| Switch | Rule | On | Off |
|---|---|---|---|
| Mic button opens TV Remote voice | `mic` | the mic key opens `MicActivity` (KeysBridge) | the key goes to the TV as before |
| Understand speech on the TV itself, no Google needed | `mic_offline` | `MicVoice.syncOffline`: TV Setup downloads the APK of the language picked (`mic_lang`, below) from the release (SHA256SUMS), installs it over its own ADB, grants the mic; the other languages on the TV stay | every language uninstalled: no service, model or file stays |

| Who listens first: Google / TV Setup | `mic_engine` | "tv": TV Setup Voice first, Google's only while the add-on is missing | "auto": the table below |

The third is shown only once the add-on is ready (chips under the mic switches; `setupFor('mic_engine')`, TV Setup 0.39.1).

Language (the user, 2026-11-03: pick it, add one without losing the other): one model per APK and one package per language (`VoiceProtocol.addon`: `dev.tvremote.setup.voice` vi, `.voice.en` en), so they sit side by side. The rule `mic_lang` is the one that listens (`MicVoice.lang`; unset = `VoiceAddon.lang` of TV Setup's language), picked on `VoiceActivity` (`paintLangs`) or the page's chips (`r.mic_langs`: absent = a TV Setup older than languages, no chips).

| Case | What happens |
|---|---|
| a language picked that is on the TV | `mic_lang` set; nothing downloaded |
| a language picked that is not | `mic_lang` set, `mic_offline` on; `syncOffline` installs it beside the others |
| the download of the one picked fails (not in the release: `voice:none`, no ADB) | `MicVoice.offlineState` says `failed:` with `MicVoice.why`, even while another language is on the TV and keeps listening (`offline` falls back to it); before, "ready" from that one hid the failure (FPT, 2026-11-03) |
| TV Setup's own language changed | nothing: `mic_lang` keeps what was picked (before: removed and swapped) |
| `mic_lang_remove` of one of two | `MicVoice.removeLang` uninstalls it; it was in use = the other picked |
| `mic_lang_remove` of the last | `mic_offline` off, `mic_engine` auto: all gone, the mic back to Google |
| the picked one missing, another there | `MicVoice.offline` gives the other one until it is installed |

The words it is given are `Phrases.spoken(<the listening add-on's language>)` (`VoiceProtocol.langOf`; `spoken.en` in `docs/voice/phrases.json`). ru / fr start with English: no model for them. `AutoUpdate` updates each language on the TV, by its own switch (`AutoUpdate.on(c, pkg)`).
While it installs, TV Setup's main screen shows it in its log, with the download's percent as a bar (`MainActivity.voiceWatch`, `MicVoice.offlinePct`), whoever started it.

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

| 8 | any, `mic_engine` "tv" and the add-on ready | on | TV Setup Voice listens first (`MicActivity.tvFirst`); Google's is not asked |
| 9 | any, `mic_engine` "tv" but the add-on removed / failed | any | as rows 1-7 (`MicVoice.offline` null) |

## How the mic key reaches TV Setup (before any row above), by what the TV allows

| # | The TV | What happens |
|---|---|---|
| a | ADB (WRITE_SECURE_SETTINGS granted) | `MicVoice.takeAssist` makes `.MicAssist` the assistant; the system starts it on the mic key |
| b | no ADB (Coocaa Android 14, 2026-11-04) | the key sends VOICE_ASSIST and the system starts WEB_SEARCH; `.MicSearch` (a second alias of `MicActivity`, disabled in the manifest, switched on by `MicVoice.syncSearch` from `takeAssist` while the rule `mic` is on, off when it goes off) is offered: the first press asks which app, the person picks TV Setup and "Always". With Kiki or Google also there the chooser lists them too. The page says it in `mic.first_press` when the state has `mic_secure` false |
| c | the rule off | alias disabled: no chooser ever lists TV Setup |
| d | a BLE voice remote, the Google app (`com.google.android.katniss`) on, and `com.google.android.tv.remote.service` there (Skyworth Android 14, 2026-11-05) | that service streams the audio to Google itself; TV Setup gets nothing, even with `.MicAssist` set. Log: `AssistantServiceLauncher: Package dev.tvremote.setup is not found` (package visibility inside Google's service, not ours to fix), `Non-legacy assistant mode is suppressed`, then `Assistant has been requested` -> WEB_SEARCH (no handler before 0.43.0: `result code=-91`). The same packages sit on an FPT Play 650 where the mic works, so the packages only make a conditional warning ("if the mic button still opens Google"): once the key has started `.MicAssist` / `.MicSearch` (`MicVoice.noteKey` in `MicActivity.onCreate`, a timestamp in prefs, forgotten when the rule goes on) there is no warning at all. Not the page's test button, `--es say` or `KeysBridge.press`. `MicVoice.block` answers `google_holds_mic` (state `mic_block`): the page's mic card, TV Setup's log and its Optimize menu say so; the button turns the Google app off over ADB (kept in `tvr-disabled.txt`: Optimize TV's Undo turns it on; Google Assistant search stops meanwhile), without ADB it opens the app's settings page (`MicVoice.free`, Rules `mic_free`) |
| e | WEB_SEARCH has a default app that is not ours and the assistant setting is not ours | `other_app_holds_mic`, naming the app (`mic_block_app`); the button opens that app's settings to clear its default |
| f | the rule `mic` off | `MicVoice.block` reads nothing; the state has no `mic_block` |
| g | the rule on, a press seen, our screen never started | not detected: no cheap, reliable signal (an app cannot read the system log of a press that went elsewhere) |

## Switching on the TV (TV Home's Control Center mic tile, `HomeControl` words)

| Press | TV Setup does |
|---|---|
| tile, now TV Setup Voice → "mic:google" | `mic_engine` auto, `mic` off: the TV's own Google Assistant again |
| tile, now Google → "mic:tv", add-on ready | `MicVoice.useTv`: `mic_offline` on, `mic_engine` tv, `mic` on |
| tile, now Google → "mic:tv", add-on missing | opens `VoiceActivity` (what it does better, Install and use / Later); Install = `useTv`, progress shown, closing it leaves the install running |
| hold the tile → "voice" | opens `VoiceActivity`: switch back, Remove (asks first) |
| an older TV Setup (no `mic_engine` in its answer) | the tile stays the old on/off of `mic` |

TV Setup's More TV apps row also shows TV Setup Voice (not in `Bundle.apps`, which installs by itself): OK opens `VoiceActivity`.

## Checking it

`adb logcat -s MicVoice` prints `engine own` / `engine offline`, `network: offline`, `stall: again`. Without a microphone:
`am start -n dev.tvremote.setup/.MicActivity --es say "mở youtube"` (the shell only). Rows 3 and 4 on an emulator: the API 34 TV
image has Google's recognizer; cut the network (`svc wifi disable`) for row 3.
