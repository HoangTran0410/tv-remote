# app/tvvoice: TV Setup Voice

Read with the root [AGENTS.md](../../AGENTS.md) (rules, build, tests, releases apply here too).

## Files

```
app/tvvoice/              TV Setup Voice (`dev.tvremote.setup.voice`): speech heard on the TV itself, for TVs with no recognizer of their
                          own (and when Google's hears nothing for 3.5 s). An Android RecognitionService, so TV Setup's MicActivity asks it
                          exactly as it asks Google's. On the TV only while TV Setup's rule "mic_offline" is on (off by default): TV Setup
                          downloads `tv-voice.apk` from the release (checked against SHA256SUMS), installs it over its own ADB, grants the mic
                          and sends Warm; switched off = uninstalled (`MicVoice.syncOffline`, `core/ontv/VoiceAddon.kt`).
                          No number of its own: TV Setup's VERSION, same key (app/tvsetup/signing.jks); dev/release/AGENTS.md
  src/protocol/           VoiceProtocol: what TV Setup and the add-on agree on (package, WORDS extra, Warm, fold, number words); ONE copy,
                          compiled into both (`tvsetup` MicVoiceTest keeps VoiceAddon.PKG equal to it)
  src/main/.../voice/     VoiceService (mic starts at once, sound kept while the model loads, partials ~0.25 s, Vosk's own end of sentence),
                          VoiceModel (unpack to filesDir/model once per VERSION, load, kept 5 min after the last use then freed),
                          Warm (unpack now, not on the first press; shell only)
  build.gradle.kts        packModel: the Vosk small Vietnamese model 0.4 (Apache-2.0) into assets, Gr.fst too (commands run on a grammar: TV Setup
                          passes the commands, apps and scenes as said, `Phrases.spoken`; Gr.fst is for the free-speech pass, `VoiceService.Free`);
                          words.txt + fold.txt made at build time
  build.sh                -> build/tv-voice.apk (vi) + build/tv-voice-en.apk (release.sh builds and attaches them)
```

## Rules

- One APK per language, never two models in one (vi 38.6 MB `tv-voice.apk`, en 47.1 MB `tv-voice-en.apk`, Gr.fst included; `-Pvoicelang=vi|en`, `build.sh`
  builds both). Each language is its own package (`VoiceProtocol.addon(lang)`: vi `dev.tvremote.setup.voice`, en `….voice.en`), so several sit side by side; `VoiceProtocol.langOf` reads an APK's language (meta-data `dev.tvremote.voice.LANG`; absent = an old vi one). TV Setup's rule `mic_lang` picks the one that listens, `mic_lang_remove` takes one out (dev/flows/voice.md). TV Setup uses Google's recognizer first
  wherever the TV has one: it is faster and understands any sentence; this add-on only knows the grammar it is given.
- Off = nothing: the rule off removes the APK, so no service, model or file stays on the TV.
- Speed is measured on a release build (debug is ~4x slower): FPT box, model cold load 2.6 s (hidden behind the buffered mic),
  unpack ~1 s at install, TV Setup's word list 0.2 s off the main thread.
- Commands stay on the grammar (exact); a sentence it cannot hold (a search) is also decoded free in a second thread and both texts go
  back (`VoiceProtocol.FREE`, `pick`): dev/flows/voice.md "Free speech". Do not swap in `vosk-model-vn-0.4` (168 MB on disk, no better
  for searches: `dev/test/voice_wer.py`).
