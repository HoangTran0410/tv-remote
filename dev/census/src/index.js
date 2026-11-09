// tvr-census: an anonymous count of which TVs the apps ran on. Stores no IP, no headers, no user agent;
// the install id is kept only as the first 8 bytes of its sha256. See ../README.md.
import { support, prune } from "./support.js";
import { MAX_BODY, validate, hashId, today, aggregate, series, downloads, MAX_CRASH, CRASH_PER_DAY, CRASH_ROWS, CRASH_IDS, validateCrash, crashSig, sameSecret } from "./logic.js";

const CORS = { "access-control-allow-origin": "*" };
const json = (o, status = 200, extra = {}) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", ...CORS, ...extra } });

// A ping is counted once per (install, model) per day: the upsert only fires when `last` is older than today.
const UPSERT = `INSERT INTO tvs (h, brand, model, device, board, hw, soc, android, sdk, leanback, app, v, adb, video, optimize, first, last)
VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?16)
ON CONFLICT (h, brand, model) DO UPDATE SET
  device=excluded.device, board=excluded.board, hw=excluded.hw, soc=excluded.soc, android=excluded.android, sdk=excluded.sdk,
  leanback=excluded.leanback, app=excluded.app, v=excluded.v,
  adb=COALESCE(excluded.adb, adb), video=COALESCE(excluded.video, video), optimize=COALESCE(excluded.optimize, optimize),
  last=excluded.last
WHERE tvs.last < excluded.last
RETURNING first`;

// A second ping the same day is not counted again, but its results are kept: the apps ping again when one becomes true (the first
// ping comes with ADB, the picture and Optimize TV later), and the upsert above dropped it, so 297 of 298 rows had no optimize.
const RESULTS = `UPDATE tvs SET adb=COALESCE(?4, adb), video=COALESCE(?5, video), optimize=COALESCE(?6, optimize)
WHERE h = ?1 AND brand = ?2 AND model = ?3`;

// An install that comes back with another version: counted on the day of its first ping with it (the apps ping once a day, so a
// same-day update shows the next day), per (from, to), as how many installs update each day. Run before UPSERT, which writes the new v;
// a ping that UPSERT does not count (same day) writes it below, or the next ping would count the same update again
const UPDATED = `INSERT INTO updates (day, app, src, dst, n) SELECT ?4, ?5, v, ?6, 1 FROM tvs
WHERE h = ?1 AND brand = ?2 AND model = ?3 AND app = ?5 AND v IS NOT NULL AND v <> '' AND ?6 <> '' AND v <> ?6
ON CONFLICT (day, app, src, dst) DO UPDATE SET n = n + 1`;

// counts the day's first counted ping: active always, new when the install was first seen today
const DAY = `INSERT INTO days (day, app, active, new) VALUES (?1, ?2, 1, ?3)
ON CONFLICT (day, app) DO UPDATE SET active = COALESCE(active, 0) + 1, new = new + excluded.new`;

const AGG = `SELECT brand, model, device, android, app, leanback, COUNT(*) AS n FROM tvs GROUP BY brand, model, device, android, app, leanback LIMIT 5000`;

// The totals are worked out once an hour (wrangler.toml crons) and kept as one row: the GROUP BY reads every install, so doing it
// on each cache miss (10 min x every Cloudflare place a visitor comes from) would grow with the installs toward the free 5M rows
// read a day; this way it is 24 x installs a day, and a page view reads one row
async function refresh(env) {
  const { results } = await env.DB.prepare(AGG).all();
  const body = JSON.stringify(aggregate(results));
  await env.DB.prepare("INSERT INTO summary (k, body) VALUES ('tvs', ?1) ON CONFLICT (k) DO UPDATE SET body = excluded.body").bind(body).run();
  return body;
}

// Crash reports. The day's count per hashed id first (over the limit: nothing stored), then the (sig, v) row, then the distinct installs.
const CRASH_DAY = `INSERT INTO crash_day (h, day, n) VALUES (?1, ?2, 1) ON CONFLICT (h, day) DO UPDATE SET n = n + 1 WHERE n < ${CRASH_PER_DAY} RETURNING n`;
const CRASH_UP = `INSERT INTO crashes (sig, v, app, loc, kind, msg, stack, brand, model, android, sdk, os, arch, java, n, installs, first, last)
VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,1,0,?15,?15)
ON CONFLICT (sig, v) DO UPDATE SET n = n + 1, last = excluded.last RETURNING n`;
const CRASH_ID = `INSERT INTO crash_ids (sig, v, h) SELECT ?1, ?2, ?3 WHERE (SELECT COUNT(*) FROM crash_ids WHERE sig = ?1 AND v = ?2) < ${CRASH_IDS} ON CONFLICT DO NOTHING`;
const CRASH_CUT = `DELETE FROM crashes WHERE (sig, v) IN (SELECT sig, v FROM crashes ORDER BY last ASC, first ASC LIMIT MAX(0, (SELECT COUNT(*) FROM crashes) - ${CRASH_ROWS}))`;

async function crash(req, env) {
  if (Number(req.headers.get("content-length") || 0) > MAX_CRASH) return json({ error: "too big" }, 413);
  const body = await req.text();
  if (body.length > MAX_CRASH) return json({ error: "too big" }, 413);
  const r = validateCrash(body);
  if (!r) return json({ error: "bad body" }, 400);
  const h = await hashId(r.id), day = today();
  if (!(await env.DB.prepare(CRASH_DAY).bind(h, day).first())) return json({ error: "enough for today" }, 429);
  const sig = await crashSig(r.app, r.kind, r.stack);
  const row = await env.DB.prepare(CRASH_UP).bind(sig, r.v, r.app, r.loc, r.kind, r.msg, r.stack, r.brand, r.model, r.android, r.sdk, r.os, r.arch, r.java, day).first();
  const seen = await env.DB.prepare(CRASH_ID).bind(sig, r.v, h).run();
  if (seen.meta && seen.meta.changes > 0) await env.DB.prepare("UPDATE crashes SET installs = installs + 1 WHERE sig = ?1 AND v = ?2").bind(sig, r.v).run();
  if (row && row.n === 1) {                                           // a new row: keep the table at its cap
    const cut = await env.DB.prepare(CRASH_CUT).run();
    if (cut.meta && cut.meta.changes > 0)
      await env.DB.prepare("DELETE FROM crash_ids WHERE NOT EXISTS (SELECT 1 FROM crashes c WHERE c.sig = crash_ids.sig AND c.v = crash_ids.v)").run();
  }
  return new Response(null, { status: 204 });
}

// The owner's reading of the crashes: behind CRASH_TOKEN (a wrangler secret). No secret set = no admin.
async function crashes(req, env, url) {
  const auth = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if (!(await sameSecret(auth, env.CRASH_TOKEN))) return json({ error: "forbidden" }, 403);
  const sig = url.searchParams.get("sig") || "", v = url.searchParams.get("v") || "", app = url.searchParams.get("app") || "";
  const last = url.searchParams.get("last") || "";                  // only those seen on or after that day: crashwatch's 2-minute look reads a few rows by the index, not the table
  if (req.method === "DELETE") {
    if (!sig) return json({ error: "sig needed" }, 400);
    const where = v ? "sig = ?1 AND v = ?2" : "sig = ?1";
    const bind = v ? [sig, v] : [sig];
    await env.DB.prepare(`DELETE FROM crashes WHERE ${where}`).bind(...bind).run();
    await env.DB.prepare(`DELETE FROM crash_ids WHERE ${where}`).bind(...bind).run();
    return json({ ok: true });
  }
  if (sig) {                                                          // one crash with its whole sample
    const { results } = await env.DB.prepare("SELECT * FROM crashes WHERE sig = ?1 ORDER BY last DESC LIMIT 20").bind(sig).all();
    return json({ crashes: results });
  }
  const q = ["SELECT sig, v, app, loc, kind, SUBSTR(msg, 1, 120) AS msg, brand, model, android, os, n, installs, first, last FROM crashes WHERE 1=1"], b = [];
  if (v) { b.push(v); q.push(`AND v = ?${b.length}`); }
  if (app) { b.push(app); q.push(`AND app = ?${b.length}`); }
  if (last) { b.push(last); q.push(`AND last >= ?${b.length}`); }
  q.push("ORDER BY n DESC, last DESC LIMIT 200");
  const { results } = await env.DB.prepare(q.join(" ")).bind(...b).all();
  return json({ crashes: results });
}

export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(refresh(env));
    ctx.waitUntil(env.DB.prepare("DELETE FROM crash_day WHERE day < ?1").bind(today()).run());
    ctx.waitUntil(prune(env));
  },

  // an error thrown below (a D1 hiccup) would be Cloudflare's own page, without CORS: the site read it as a CORS block, not a 503
  async fetch(req, env) {
    try { return await handle(req, env); } catch (e) { return json({ error: "busy" }, 503, { "cache-control": "no-store" }); }
  },
};

async function handle(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: { ...CORS, "access-control-allow-methods": "GET", "access-control-max-age": "86400" } });

    if (url.pathname === "/v1/ping" && req.method === "POST") {
      if (Number(req.headers.get("content-length") || 0) > MAX_BODY) return json({ error: "too big" }, 413);
      const text = await req.text();
      if (text.length > MAX_BODY) return json({ error: "too big" }, 413);
      const r = validate(text);
      if (!r) return json({ error: "bad body" }, 400);
      const h = await hashId(r.id);
      const day = today();
      const up = await env.DB.prepare(UPDATED).bind(h, r.brand, r.model, day, r.app, r.v).run();
      const row = await env.DB.prepare(UPSERT)
        .bind(h, r.brand, r.model, r.device, r.board, r.hw, r.soc, r.android, r.sdk, r.leanback, r.app, r.v, r.adb, r.video, r.optimize, day)
        .first();
      if (row) await env.DB.prepare(DAY).bind(day, r.app, row.first === day ? 1 : 0).run();
      else if (r.adb != null || r.video != null || r.optimize != null)
        await env.DB.prepare(RESULTS).bind(h, r.brand, r.model, r.adb, r.video, r.optimize).run();
      if (!row && up.meta && up.meta.changes > 0)
        await env.DB.prepare("UPDATE tvs SET v = ?4 WHERE h = ?1 AND brand = ?2 AND model = ?3").bind(h, r.brand, r.model, r.v).run();
      return new Response(null, { status: 204 });
    }

    if (url.pathname === "/v1/crash" && req.method === "POST") return crash(req, env);
    if (url.pathname === "/v1/crashes" && (req.method === "GET" || req.method === "DELETE")) return crashes(req, env, url);

    if (url.pathname === "/v1/support" || url.pathname.startsWith("/v1/support/")) return support(req, env, url, json);

    if (url.pathname === "/v1/tvs" && req.method === "GET") {
      const cache = caches.default, key = new Request(url.origin + "/v1/tvs");
      const hit = await cache.match(key);
      if (hit) return hit;
      const row = await env.DB.prepare("SELECT body FROM summary WHERE k = 'tvs'").first();
      const body = row ? row.body : await refresh(env);                       // first call after the table was made
      const res = new Response(body, { headers: { "content-type": "application/json; charset=utf-8", ...CORS, "cache-control": "public, max-age=600" } });
      await cache.put(key, res.clone());
      return res;
    }

    if (url.pathname === "/v1/days" && req.method === "GET") {
      const cache = caches.default, key = new Request(url.origin + "/v1/days");
      const hit = await cache.match(key);
      if (hit) return hit;
      const { results } = await env.DB.prepare("SELECT day, app, active, new FROM days ORDER BY day LIMIT 20000").all();
      const res = json(series(results), 200, { "cache-control": "public, max-age=600" });
      await cache.put(key, res.clone());
      return res;
    }

    if (url.pathname === "/v1/downloads" && req.method === "GET") {   // README badge and the site's hero: {downloads}
      const cache = caches.default, key = new Request(url.origin + "/v1/downloads");
      const hit = await cache.match(key);
      if (hit) return hit;
      let n = 0;
      try {                                                   // GitHub allows 60 calls an hour without a token: once per 6 h here
        for (let page = 1; page <= 5; page++) {
          const r = await fetch(`https://api.github.com/repos/HoangTran0410/tv-remote/releases?per_page=100&page=${page}`,
            { headers: { "user-agent": "tvr-census", accept: "application/vnd.github+json" } });
          if (!r.ok) throw new Error("github " + r.status + " " + r.headers.get("x-ratelimit-remaining") + " " + (await r.text()).slice(0, 60));
          const rs = await r.json();
          n += downloads(rs);
          if (rs.length < 100) break;
        }
        await env.DB.prepare("INSERT INTO summary (k, body) VALUES ('downloads', ?1) ON CONFLICT (k) DO UPDATE SET body = excluded.body").bind(String(n)).run();
      } catch (e) {                                           // GitHub busy: the last good count
        const row = await env.DB.prepare("SELECT body FROM summary WHERE k = 'downloads'").first();
        if (!row) return json({ error: "busy", why: String(e.message || e).slice(0, 80) }, 503, { "cache-control": "no-store" });
        n = Number(row.body);
      }
      const res = json({ downloads: n }, 200, { "cache-control": "public, max-age=21600" });
      await cache.put(key, res.clone());
      return res;
    }

    return json({ error: "not found" }, 404);
}
