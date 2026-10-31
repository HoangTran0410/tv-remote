# Add a key or a menu entry on the remote

Read first: "The remote screen is drawn from one file" in [app/shared/web/AGENTS.md](../../app/shared/web/AGENTS.md).

1. **Entry**, once, in `app/shared/web/remote.json`: under `rows` (the More keys) or the menu you want, add
   `{ "id", "icon", "t": "rkey.<id>", "key": "<NAME>" }` for a key, or `"do": "<action>"` (+ `"arg"`) for an action. Optional:
   `"sub"` (a line under it), `"needs": ["adb"]` (a capability; the entry is greyed or left out on a TV without it), `"cores"`
   (`["phone"]` or `["web"]`, with a `why`), per-side `web` / `phone` overrides.
2. **A key name** must be one `Scenes.KEYS` knows (`app/shared/src/main/kotlin/dev/tvremote/core/scenes/Scenes.kt`) and the TV
   brands can send; a new key is added there first (`remote.test.js`: "every key is a name the servers know").
3. **A new `do` action** needs a handler on both sides: the web in `REMOTE_DO` (`app/shared/web/js/remote_spec.js`, drawn by
   `app/shared/web/js/remote.js`) and the phone in `phoneActions`
   (`app/android/src/main/kotlin/dev/tvremote/phone/remote/PhoneRemote.kt`). If a side truly cannot, limit the entry with `cores` and a
   `why`. Reuse an existing handler when one fits.
4. **Words**: `rkey.<id>` (and the `sub` id) in `app/shared/i18n/vi.json` first, then `en.json` and every other language of
   `app/shared/i18n/langs.json` (`app/shared/tests/i18n.test.js` fails on a missing id).
5. **Icon**: it must be in the shared set; if new, add it to `dev/gen/gen_icons.py` and regenerate (never hand-edit `ic_*.xml`).
6. **Hand-laid parts are outside the file** (top row, D-pad, number keys, volume / channel pills, the listen menu): change those in
   `app/shared/web/js/` and `app/android/src/main/kotlin/dev/tvremote/phone/remote/RemoteScreen.kt` by hand, or say why one side cannot.
7. **Tests**: `node --test app/shared/tests/` (`app/shared/tests/remote.test.js`: schema, icons, key names, handlers on both
   sides) and `cd app/gradle && ./gradlew :shared:testDebugUnitTest :android:testDebugUnitTest` (`RemoteSpecTest`).
8. README.md / README.vi.md if the feature is visible; `docs/guide.html` topic + `data-v="next"` if it describes the remote;
   CHANGELOG under `## Unreleased` in both languages.

**Done =** Phone app on the phone emulator (native remote screen; the key reaches the TV emulator at `10.0.2.2`) + the Computer
app's page in Chrome at 1100 and 400 px + the TV web page if the entry shows there (`cores`) + an LG / Samsung check when `needs`
matters (the test in step 7 covers their menus). Screenshot each; nothing cut, wrapped or clipped.
