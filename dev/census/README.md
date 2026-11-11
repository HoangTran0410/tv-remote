# tvr-census

An anonymous count of which TVs the apps ran on (like vgs-cleantv's `dem/`). A Cloudflare Worker + D1, free tier only.
The apps send one ping a day; the site's home page and `docs/tvs.html` read the public totals.

- `POST /v1/ping` (JSON, at most 4 KB, answers 204): `{id, app: setup|phone|pc, v, tv: {brand, model, device, board, hw, soc, android, sdk, leanback}, ok: {adb, video, optimize}}`.
  `ok` flags are `true` / `false` / missing (not tried). Unknown fields are dropped, strings trimmed to 64 characters.
- `GET /v1/tvs` -> `{updated, installs, models: [{brand, model, count, android[], devices[], kind: tv|box|projector}]}` (the ok flags and last day stay in D1, not sent); worked out hourly by a cron into the `summary` table (a view reads one row, not every install), cached 10 minutes; no ids.
- `GET /v1/days` -> `{updated, installs, days: [{day, active: {setup, phone, pc}, new: {…}, total}]}` for the growth chart; cached 10 minutes. `active` = installs that pinged that UTC day, `new` = first seen that day, `total` = running sum of `new`. Before 2026-10-25 only `new` exists (rebuilt from `tvs.first`).
- Stored: sha256(id) cut to 16 hex, the model strings, flags, first / last day. **Not stored:** IP, headers, user agent. One row per (install, model);
  a second ping the same UTC day changes nothing. The kind is worked out from the names in `src/logic.js` `kindOf` (not stored).
- Updates: an install whose ping brings another version than the stored one adds 1 to `updates (day, app, src, dst)` (schema.sql), not served; read it with `npx wrangler d1 execute tvr-census --remote --command "SELECT * FROM updates ORDER BY day DESC"`. Counted from 2026-10-29 on.
- `GET /v1/downloads` -> `{downloads}`: GitHub's download count of the installers only (`.apk`, `.zip`, `.tar.gz`; not `versions.json` or `SHA256SUMS`, which every update check fetches), cached 6 h, the last good count kept in `summary` when GitHub refuses (it does, 403, to Workers at times). The README badge and the site's hero read it.
- `POST /v1/crash` (JSON, at most 24 KB, answers 204): `{id, app: setup|phone|pc, v, where: kotlin|page, dev: {brand, model, android, sdk, device, board, hw | os, arch, java}, kind, msg (300), stack (8 KB)}`, sent by the apps' error box / dialog with the same stats switch, scrubbed on the device first (`Scrub.kt` / `js/scrub.js`: IPs, home folders, keys, emails). Emulators are refused like a ping. One row per (sig, v): `sig` = sha256(app + kind + top 5 meaningful frames without line numbers); count, first / last day, distinct installs (capped at 200), one sample message / stack / device. A hashed id may send 20 a day (429 after); the table is cut to 5000 rows (oldest `last` go); `crash_day` rows are deleted by the cron.
- `GET /v1/crashes?v=&app=&last=` (top 200 by count; `last` = seen on or after that day, by the index: crashwatch asks every 2 min and D1 free is 5M rows read a day), `?sig=` (the samples with stack) and `DELETE /v1/crashes?sig=[&v=]`: behind `Authorization: Bearer <CRASH_TOKEN>` (a wrangler secret; the owner's copy in `~/.tv-remote-release/crash-token`, never in the repo). No secret set = 403. `python3 dev/admin/admin.py` shows them in a local web page (tab Lỗi); `python3 crashes.py` prints them (`crashes.py SIG` shows a stack, `--delete SIG`).
- Support (`src/support.js`, TV Setup's `Support.kt`): `POST /v1/support` `{id, app, v, model, report}` (64 KB, 20 a day per hashed id, 429 after) -> `{code (8 digits), key, left}` (sends left today, shown with the code); with code + key the TV posts `/v1/support/live` `{minutes}` (≤60, 0 = stop), `/poll` every 2 s (-> `{live, asks:[{id, path}]}`) and `/answer` `{id, status, body}`. The owner, behind CRASH_TOKEN: `GET /v1/support[?code=]`, `POST /v1/support/ask` `{code, path}` (an `/api/…` GET only; 409 when not open), `GET /v1/support/answer?code&id`, `DELETE /v1/support?code=`. Reports kept 14 days, questions a day (cron). `python3 support.py` lists, `support.py CODE` reads one, `support.py CODE /api/state` or `--all` asks the TV.
- Shared with KidGuard (2026-11-11, app `kidguard`, a child's phone): `GET /v1/support` and `/v1/support/said` take `?app=X` (only X) or `?app=!X` (all but X); `said` rows carry `app`. `support.py` leaves KidGuard out and refuses its codes unless `--app kidguard`; crashwatch tells KidGuard only as a desktop notification, never into news.txt.
- Abuse limits: body size, id must be a uuid v4, one counted ping per install per day. No rate-limit product (they cost money).

Live: https://tvr-census.99-hoangtran.workers.dev (account of 99.hoangtran@gmail.com; D1 `tvr-census`).

```
cd dev/census
node --test test/                                   # validation + aggregation
npx wrangler d1 execute tvr-census --local --file=schema.sql && npx wrangler dev --local
npx wrangler deploy                                 # after `npx wrangler login`; a first deploy may need a retry after ~20 s
npx wrangler d1 execute tvr-census --remote --command "DELETE FROM tvs"   # empty the data (test rows)
```
