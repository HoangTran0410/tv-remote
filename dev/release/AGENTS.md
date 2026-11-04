# dev/release: versions and releases

Read with the root [AGENTS.md](../../AGENTS.md).

## Versions and releases

Seven numbers, each bumped only when its part changes: `VERSION` (every release; the tag and the computer app), `app/android/VERSION`, `app/tvsetup/VERSION`,
`app/tvhome/VERSION` (TV Home: its code changed, `app_paths.py`; its key `app/tvhome/signing.jks`, backed up by `dev/release/keys.sh`),
`app/tvchannels/VERSION` (TV Channels: `app/tvchannels` changed; `app/tvchannels/signing.jks`, also in `dev/release/keys.sh`),
`app/tvgames/VERSION` (TV Games: `app/tvgames` or its packed `docs/games/games.json` changed; `app/tvgames/signing.jks`, also in `dev/release/keys.sh`),
`app/tvapps/VERSION` (TV Apps: `app/tvapps` changed; its list `docs/apps/apps.json` is read from the site, not packed; `app/tvapps/signing.jks`, also in `dev/release/keys.sh`).

The apps are an alpha: the numbers stay 0.x until the user calls a release 1.0.0. Patch (the default) for fixes and small changes,
`BUMP=minor` for a release with new features, `BUMP=major` only when the user says so. The numbers restarted at 0.1.0 in October 2026
(they had reached 2.24.2): `Release.legacy()` counts 1.3.0 … 2.x as older than every new number, `versionCode` is 100000 + the number
so Android installs over the old APKs, and `release.sh` stops before 1.3.0 until `legacy()` is removed.

- What counts as an app's code is what its own `build.gradle.kts` compiles (`dev/release/app_paths.py`): `app/shared` for the phone and TV apps less the `app/shared/src/<dir>` they never build (`tvui` is TV only), the parts of `app/shared/src` each TV app names, everything the computer app names; of `app/shared/i18n/apps/<lang>/`, only the modules the app builds its words from (`extra["i18nApps"]`): TV Channels' words are no phone or TV Setup update.
- **TV Setup Keys rides with TV Setup's number** (`app/tvkeys` changed = `app/tvsetup/VERSION` goes up; release.sh sees it and refuses a `tv-setup.apk` without `assets/tvkeys.apk`; `KeyFixTest` checks the asset is packed, signed, and that the manifest of `app/tvsetup` declares no accessibility service). Never give the add-on a number or a release file of its own.
- **TV Setup Voice also rides with TV Setup's number** (`app/tvvoice` changed = `app/tvsetup/VERSION` goes up, release.sh checks it), but it is 25 MB, so it is not packed in tv-setup.apk: it is its own release files `tv-voice.apk` (vi) and `tv-voice-en.apk` (en; in SHA256SUMS), which TV Setup downloads only while "mic_offline" is on (`VoiceAddon.kt`).
- **The user guide** (`docs/guide.html`, one `<article class="topic">` per topic) says which release each topic was checked
  against (`data-v`) and which app files can make it wrong (`data-src`, short paths: `web/`, `core/`, `phone/`, `setup/`, `pc/`).
  A change to what a topic describes: update the topic and set its `data-v="next"` (release.sh stamps the version).
  `dev/release/guide_stale.py` lists the topics whose files changed since; `release.sh` stops on them, with no way round (a bypass let 51 topics pile up unread by v0.35.0): read each against its commits, fix it, set `data-v="next"`.
  A new topic needs both attributes and an entry in `docs/data/since.json` (`"since": "next"`, plus `setup` / `phone` / `pc` minimum versions where the feature needs a newer app; `release.sh` stamps "next" with the version and its date; `guide_stale.py` warns when missing). Point to another topic with a link (`<a href="#id" data-t="id">`), never “see … below”.
- Keep the next release's notes under `## Unreleased` at the top of `CHANGELOG.md` (English only, plain `- ` bullets: `Release.brief` takes every bullet when a release has no **Tiếng Việt** list, so the apps show them in every language), added to as features land.
- `dev/release/release.sh` does the rest: bumps `VERSION` and the number of each app whose code (or `app/shared`) changed since the last tag (beyond comments: `dev/release/code_changed.py`) (patch; `BUMP=minor`), turns `## Unreleased` into `## <VERSION> - <date>` with the line `Web x · Phone app y · TV app z`, commits, pushes, builds and creates the GitHub release with both APKs and `versions.json` (APKs are never committed). It needs `gh` logged in and both `signing.jks` files. Run again after a failure: nothing is bumped twice.
- The computer apps are not built by `release.sh`: it first creates the release as a draft (tag pushed first), attaches the
  last built computer archives again (names without a version, so `releases/latest/download/TV-Remote-<os>-<arch>.zip` always
  works), signs and publishes it, so `/releases/latest` is never a release without `SHA256SUMS` (v0.45.0 was, 30 min, while
  GitHub's Mac runners queued: TVs could install no add-on). Then, when `app/desktop` or `app/shared` changed since the
  release that last built them (`NO_DESKTOP=1`: skip once), it starts `.github/workflows/desktop.yml`, which replaces the
  archives, and only then raises `versions.json` "web" (the number the computer app's Updater.kt reads) and signs again.
- Both apps update themselves from GitHub releases (`update/Release.kt`). Every app installs only a file listed in the
  release's `SHA256SUMS` with its SHA-256, signed (`SHA256SUMS.sig`, Ed25519) by a key in `Release.KEYS`; a release without
  them is not offered. `release.sh` runs `dev/release/sums.sh <tag>` before publishing and again after the computer apps are attached; run it again after
  replacing any file of a release by hand. The keys (`~/.tv-remote-release/`, never on GitHub) and their
  backup: `dev/README.md`.
