# Add a /api route every core can answer

Cores: Phone app (`phone`), Computer app (`pc`), TV web page (`tv`). Read first: the "Every change reaches" rule in the root
[AGENTS.md](../../AGENTS.md) and the web entry in [app/shared/AGENTS.md](../../app/shared/AGENTS.md).

1. **Where it goes.** Work that talks to the TV through a shell goes in the `route()` of the part of `Manage` it belongs to, in
   `app/shared/src/main/kotlin/dev/tvremote/core/web/` (`AppsApi`, `FilesApi`, `InstallApi`, `HealthApi`, `SetupApi`, `HomeApi`;
   the TV itself in `Manage.handle`): one `when (what)` branch (`"home/theme"` in `HomeApi.kt` is a model), a POST-only one in the
   block after `if (!post) return null`; one name per route across the parts. The logic in a helper under `app/shared/src/main/kotlin/dev/tvremote/core/ontv/`. All three cores then answer it.
2. **Only some cores.** Computer app: `route()` in `app/desktop/src/main/kotlin/dev/tvremote/desktop/Server.kt`; mind its sets
   `OURS` / `NATIVE_TOO` (what an LG or Samsung TV may call). TV web page: `route()` in
   `app/tvsetup/src/main/kotlin/dev/tvremote/setup/RemoteService.kt` (and `PASSIVE` if it must not count as use). Phone app: the Manage
   screen is the same web UI served by the in-app core (`app/android/src/main/kotlin/dev/tvremote/phone/Managing.kt`); a
   native-only call goes in the phone's own Kotlin.
3. **Caller**: the page calls `api()` in `app/shared/web/js/core.js` (a page script asks the owner of the state, see "The page's
   state" in [app/shared/web/AGENTS.md](../../app/shared/web/AGENTS.md)). Errors: words a person can act on, in `Errors.kt` and
   `friendly()` in `core.js`; a long call shows progress and can be stopped.
4. **Needs a newer TV Setup?** One line in `SetupNeed.MIN` (`app/shared/src/main/kotlin/dev/tvremote/core/ontv/SetupNeed.kt`) with
   the first TV Setup version that has it (the release after the current one), its `name()` there, and its id in `SETUP_NEEDS` in
   `app/shared/web/js/core.js` (+ `core.need_<f>` words in every language). Check before starting (`setupFor()` on the page,
   `withSetup()` on the phone) or throw `SetupNeed.Missing`.
5. **Needs ADB or another capability?** Mark the UI `data-needs="adb"` (or `needs` in `remote.json`) so it hides without it.
6. **Tests**: a Kotlin unit test beside `app/shared/src/test/kotlin/dev/tvremote/core/ManagePathsTest.kt` for the logic; a page
   test in `app/shared/tests/` when the JS changed; then `node --test app/shared/tests/` and
   `cd app/gradle && ./gradlew :shared:testDebugUnitTest :tvsetup:testDebugUnitTest :desktop:test :android:testDebugUnitTest`.
7. Docs: README / README.vi "Three apps and a web page" table kept true; `docs/guide.html` topic `data-v="next"`; CHANGELOG in
   both languages.

**Done =** the route really works against the TV emulator (`dev/test/emu.sh`) from the Computer app (own port, root "Build and
run"), the TV web page and the Phone app (`10.0.2.2`); plus an error case, Stop, and an old TV Setup if step 4 applies. UI part:
a screenshot at 1100 and 400 px. Say which core could not be run.
