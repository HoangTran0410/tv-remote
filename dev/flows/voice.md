# Flow: the remote's mic button and who listens (the TV's recognizer or TV Setup Voice)

Read [README.md](README.md) first: change the row a fault belongs to, and this file in the same commit.

**Code:** `app/tvsetup/src/main/kotlin/dev/tvremote/setup/MicActivity.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/MicVoice.kt`,
`app/tvsetup/src/main/kotlin/dev/tvremote/setup/VoiceActivity.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/home/HomeControl.kt`,
`app/tvhome/src/main/kotlin/dev/tvremote/home/ui/ControlCenter.kt`, `app/shared/src/main/kotlin/dev/tvremote/core/ontv/VoiceAddon.kt`,
`app/shared/web/js/tvset.js`, `app/tvvoice/src/main/kotlin/dev/tvremote/setup/voice/VoiceService.kt`, `app/tvvoice/src/protocol/kotlin/dev/tvremote/setup/voice/VoiceProtocol.kt`.

**Tests:** `app/tvsetup/src/test/kotlin/dev/tvremote/setup/MicVoiceTest.kt`, `app/tvsetup/src/test/kotlin/dev/tvremote/setup/home/HomeControlTest.kt`, `app/tvvoice/src/test/kotlin/dev/tvremote/setup/voice/VoiceProtocolTest.kt`; the numbers: `dev/test/voice_wer.py`.

Turning Google's own listener off or back on (`MicVoice`) acts on the user in front (`TvUser`, Google TV profiles; dev/decisions/tv-setup.md "Profiles").

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

The language the TV's own recognizer listens in (rule `mic_tv_lang`, "" = TV Setup's language; a `<select>` under the mic switches while `mic` is on, `mic_offline` off and TV Setup reports it): `MicActivity.start` passes it as `EXTRA_LANGUAGE` when the add-on is not the listener. It is not `mic_lang`, which is the add-on's.

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

## Free speech: a search is not a command (TV Setup Voice, 2026-11-05)

The add-on listens with a grammar (the commands, app and channel names as one list): exact and fast, but a sentence outside it is
forced onto grammar words (a real user: "Ngày xưa anh nói" came out "nghệ xoá hẹn noi phút"; "[unk]" in the list never wins). So the
same sound also goes, from the first word on and in a thread of its own, through the model's own language model (Gr.fst, now packed:
+14 MB per APK, vi 39 MB, en 47 MB; `VoiceService.Free`). Measured offline (`dev/test/voice_wer.py`, 30 sentences, `say -v Linh` voice, so
flattering): word error rate commands / searches, grammar 33 / 92 %, free 64-71 / 23-25 %, the big model (`vosk-model-vn-0.4`, 168 MB on
disk, a server model) 60 / 27 %: no better than the small one for searches, so the small model stays.

| # | What was said | What the add-on answers | What TV Setup does (`MicActivity.onResults`) |
|---|---|---|---|
| F1 | a command heard clearly (every word of the grammar text at confidence 0.9 or more, mean 0.95: `VoiceProtocol.sure`) | the grammar text alone; the free pass is dropped | acts on it as before (a clear command is also run early from the partials, no wait) |
| F2 | a search ("tìm ngày xưa anh nói", "sơn tùng trên youtube") | `[grammar text, free text]` + `VoiceProtocol.FREE` | `VoiceProtocol.pick` by what each text parses to (`Voice.kind`: a search, a command, nothing): free is a search (`Voice.looksLikeSearch` or a parsed Search) = free; grammar is a search and free is no command = a search with the free text (`Voice.asSearch`, the list's search verb in front); grammar is a command = it, unless free is a different command; neither parses = free (it goes to the TV's search like the grammar text would); "hẹn giờ tắt 30 phu" parses as Timer 30 and stays |
| F3 | a command heard unclearly | both, as F2 | the grammar text still wins when it parses to a command and free parses to none or the same one ("tắt tiếng", "hẹn giờ tắt 30 phu"); a different command in free speech wins |
| F4 | the free pass is slower than the sound (measured on release builds, 2026-11-05: a NEW recognizer costs 3-5x the sound on FPT, 5x on BRAVIA, because its composed graph is built while decoding; the add-on keeps the last one, reset, for the model's 5 minutes (`VoiceModel.takeSpare`), and builds it before the first word, so a second search costs ~0.6x on FPT; trailing silence is not fed) | after 6 s (`FREE_MS`) of waiting it is cut and the grammar text alone is answered, as before this change | `MicActivity.LATE_FREE_MS` (8 s, was 1.2 s) is how long the card waits for the final text when TV Setup Voice listens |
| F0 | the choice "Commands only (fast)" (default) or "Commands + free speech (slower, finds searches right)" (rule `mic_freespeech`; the web page's Voice card, TV Setup's Voice screen; `MicVoice.freeSpeech`) | off: `MicActivity` does not send `VoiceProtocol.FREE_SPEECH`, so the add-on starts no second thread, feeds no replay buffer, builds no second Recognizer (the answer is today's, ~0.1 s after the sound ends); Gr.fst stays in the APK (it is only read when the second Recognizer is built) | on: F1-F5 below; `LATE_FREE_MS` applies only then |
| F5 | the partials on the card while speaking | still the grammar's (a search shows forced words until the final text replaces them) | unchanged |

Google's alternatives are never reordered: only a Bundle with `FREE` is picked from. The extra cost is one more core while the person
speaks (only when free speech is chosen) (not at all when a command was heard clearly and the key was early-run), nothing when the add-on is off. Noise breaks every
column (white noise at 15 dB: grammar 92 %, free 96 %, big 91 % of words wrong): the small model is for a quiet room. If its free text
is still poor in real use, the next step is Google's recognizer for searches only (not built).

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
| 8 | has a recognizer; TV Setup Voice listens (row 3, 4 or 10) but its model or word list does not load (corrupt or missing files) | on | `VoiceService` answers `VoiceProtocol.ERROR_NO_MODEL` (not ERROR_SERVER); TV Setup asks the TV's own at once, "say it again" on the card (`MicActivity.noModel`, `MicVoice.useAddon`), once per press; it fails too = as row 1 (before: "Speech model could not load" and 10 min of nothing though Google's worked) |
| 9 | none, and the model does not load | on | the card says "Speech model could not load", the key goes to the TV for 10 min like the other failures (`MicVoice.broke`); no network wording |
| 10 | any, `mic_engine` "tv" and the add-on ready | on | TV Setup Voice listens first (`MicActivity.tvFirst`); Google's is not asked (unless row 8) |
| 11 | any, `mic_engine` "tv" but the add-on removed / failed | any | as rows 1-7 (`MicVoice.offline` null) |

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
| hold the tile → "voice" | opens `VoiceActivity`: rows Language (+ Remove, asks first) and Listen for (commands only / + free speech); buttons: switch back, Close |
| an older TV Setup (no `mic_engine` in its answer) | the tile stays the old on/off of `mic` |

TV Setup's More TV apps row also shows TV Setup Voice (not in `Bundle.apps`, which installs by itself): OK opens `VoiceActivity`.

## Which app "search" and "YouTube" mean, and a scene's own phrases (`Voice.Prefs`, `Scenes.sayings`)

| Case | What happens |
|---|---|
| nothing picked (rules `voice_search`, `voice_youtube` empty) | as before: "tìm X" is null (the TV's own search), "YouTube" is the YouTube app |
| `voice_search` = an installed app | a bare "tìm / search / find X" (`search.verbs` in the signed list) is a search in that app: SmartTube takes the youtube.com/results?search_query= link; TizenTube has no search link, so it is only opened (the page says so); a channel name still wins ("tìm vtv3") |
| `voice_search` / `voice_youtube` names an app no longer installed | ignored: today's behaviour |
| `voice_youtube` = SmartTube / TizenTube | "mở / tắt youtube" and "X trên youtube" use that app (alias `tube: main`); "mở smarttube" still opens SmartTube |
| a scene has `say` phrases | matched first, accents and case folded, before the built-in commands; exact phrase only; `Scenes.clean` keeps at most 5 (`MAX_SAY`) |
| TV Setup's offline mic | `MicVoice.words` adds the phrases to the Vosk grammar (the free text of a bare search is heard only when its words are in it); only scenes saved in TV Setup's own list are known there |
| an older TV Setup | no `voice_tubes` in the state: the page shows no picker |

Cases: `app/shared/tests/voice_picks_cases.json` (page and phone). Clients read the picks from `/api/rules` (`loadVoicePrefs` in `auto.js`, `warmAppNames` on the phone).

## Checking it

`adb logcat -s MicVoice` prints `engine own` / `engine offline`, `network: offline`, `stall: again`. Without a microphone:
`am start -n dev.tvremote.setup/.MicActivity --es say "mở youtube"` (the shell only). Rows 3 and 4 on an emulator: the API 34 TV
image has Google's recognizer; cut the network (`svc wifi disable`) for row 3.
