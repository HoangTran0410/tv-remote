-- One row per (install, model). h = first 16 hex of sha256(id): the id itself is never stored.
CREATE TABLE IF NOT EXISTS tvs (
  h        TEXT NOT NULL,
  brand    TEXT NOT NULL,
  model    TEXT NOT NULL,
  device   TEXT, board TEXT, hw TEXT, soc TEXT,
  android  TEXT, sdk INTEGER,
  leanback INTEGER,            -- 1 / 0
  app      TEXT, v TEXT,
  adb      INTEGER, video INTEGER, optimize INTEGER,   -- 1 worked, 0 failed, NULL not tried
  first    TEXT NOT NULL,      -- YYYY-MM-DD (UTC)
  last     TEXT NOT NULL,
  PRIMARY KEY (h, brand, model)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS tvs_model ON tvs (brand, model);

-- One row per (UTC day, app): installs that pinged that day (`active`) and of those, first seen that day (`new`).
-- Fed by the ping's upsert (it returns a row only for the first counted ping of the day), for the growth chart.
CREATE TABLE IF NOT EXISTS days (
  day     TEXT NOT NULL,       -- YYYY-MM-DD (UTC)
  app     TEXT NOT NULL,       -- setup | phone | pc | home | channels | games | tvapps
  active  INTEGER,             -- NULL on days before the table existed (only `new` could be rebuilt from tvs.first)
  new     INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, app)
) WITHOUT ROWID;

-- Installs that came back with another version (src/index.js UPDATED), per UTC day and (from, to): how many update each day.
-- The Phone and Computer apps ask before updating, so theirs is about how many people pressed Update; TV Setup / TV Home update quietly.
CREATE TABLE IF NOT EXISTS updates (
  day     TEXT NOT NULL,       -- YYYY-MM-DD (UTC), the first ping with the new version
  app     TEXT NOT NULL,
  src     TEXT NOT NULL,       -- the version before
  dst     TEXT NOT NULL,       -- the version now (lower than src = went back)
  n       INTEGER NOT NULL,
  PRIMARY KEY (day, app, src, dst)
) WITHOUT ROWID;

-- What GET /v1/tvs answers, worked out once an hour by the cron (src/index.js refresh): a page view reads this one row, not every install.
CREATE TABLE IF NOT EXISTS summary (
  k     TEXT PRIMARY KEY,      -- 'tvs'
  body  TEXT NOT NULL          -- the JSON reply
) WITHOUT ROWID;

-- Crash reports (POST /v1/crash): one row per (signature, app version). sig = sha256(app + kind + top 5 meaningful frames, no line numbers).
-- One sample message / stack / device per row; `installs` counts distinct hashed ids (crash_ids, capped). The table is cut to 5000 rows (oldest `last` go).
CREATE TABLE IF NOT EXISTS crashes (
  sig     TEXT NOT NULL,
  v       TEXT NOT NULL,
  app     TEXT NOT NULL,       -- setup | phone | pc | home | channels | games | tvapps
  loc     TEXT NOT NULL,       -- kotlin | page
  kind    TEXT NOT NULL,       -- exception class / error name
  msg     TEXT, stack TEXT,
  brand   TEXT, model TEXT, android TEXT, sdk INTEGER, os TEXT, arch TEXT, java TEXT,   -- the first device that sent it
  n       INTEGER NOT NULL,
  installs INTEGER NOT NULL DEFAULT 1,
  first   TEXT NOT NULL,       -- YYYY-MM-DD (UTC)
  last    TEXT NOT NULL,
  PRIMARY KEY (sig, v)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS crashes_last ON crashes (last);
CREATE TABLE IF NOT EXISTS crash_ids (sig TEXT NOT NULL, v TEXT NOT NULL, h TEXT NOT NULL, PRIMARY KEY (sig, v, h)) WITHOUT ROWID;
-- a hashed id may send at most 20 crashes a day; old days are deleted by the cron
CREATE TABLE IF NOT EXISTS crash_day (h TEXT NOT NULL, day TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (h, day)) WITHOUT ROWID;

-- Support (src/support.js): what a TV sent when its owner pressed "Send to support", under an 8-digit code read out to the
-- project's owner; key = sha256 of the TV's key for that code (hashId). live = until when (ms) the TV lets the owner GET its /api.
CREATE TABLE IF NOT EXISTS support (
  code TEXT PRIMARY KEY, key TEXT NOT NULL, h TEXT NOT NULL, app TEXT, v TEXT, model TEXT, report TEXT,
  made INTEGER NOT NULL, live INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS support_h ON support (h, made);
CREATE INDEX IF NOT EXISTS support_made ON support (made);   -- the owner's list (newest 50) every 2 min read the whole table without it
-- Replies (src/support.js): one thread per install (h), "us" = the project's owner, "tv" = the person (the page or the TV).
CREATE TABLE IF NOT EXISTS support_msg (
  h TEXT NOT NULL, id INTEGER NOT NULL, who TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL,
  PRIMARY KEY (h, id)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS support_msg_at ON support_msg (who, at);   -- crashwatch's "what people wrote since"
-- The owner's questions to a live TV and its answers; deleted after a day by the cron.
CREATE TABLE IF NOT EXISTS support_q (
  code TEXT NOT NULL, id INTEGER NOT NULL, path TEXT NOT NULL, asked INTEGER NOT NULL, taken INTEGER NOT NULL DEFAULT 0,
  status INTEGER, body TEXT, answered INTEGER,
  PRIMARY KEY (code, id)
) WITHOUT ROWID;
