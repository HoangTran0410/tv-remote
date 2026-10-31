// Validation and flags for a submitted theme. Pure functions, tested in ../test.
export const MAX_BODY = 5_200_000;   // ten photos of 350 KB in base64 + the preview + conf
export const MAX_PREVIEW = 150_000, MAX_WALL = 1_000_000, MAX_CONF = 8192;   // MAX_WALL: the old single "wall"
export const MAX_PHOTO = 360_000, MAX_PHOTOS = 10;   // each of "walls" (HomeConfig.PHOTO_MAX), re-encoded by the app to 1920 wide / 350 KB; one D1 row each (a row or blob tops out near 2 MB)
export const PHOTO_KEY = /^photo(?:[2-9]|10)?$/;      // HomeConfig.PHOTOS
export const PER_DAY = 3;
export const MAX_QUEUE_BYTES = 350_000_000, MAX_AGE_DAYS = 60;   // D1 free = 500 MB a database: refuse new ones past 350 MB of waiting pictures
export const ALLOWED_HOST = "tvremote.pages.dev";
// HomeConfig.THEME_KEYS (app/shared/src/home/kotlin/.../HomeConfig.kt) + "wstyle", which it still reads once; ../test fails when they differ
export const THEME_KEYS = ["wall", "walls", "wurl", "wcol", "wdir", "wmode", "wsnap", "wkeep", "wevery", "shade", "wfit", "wfade",
  "widgets", "wsize", "wopt", "cols", "rows", "focus", "motion", "anim", "clock24", "greet", "wstyle"];

// one line, no control characters, trimmed, cut to [max] (the whole value is refused when empty)
export function clean(s, max = 24) {
  if (typeof s !== "string") return "";
  return s.replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, " ").replace(/\s+/g, " ").trim().slice(0, max).trim();
}

export function b64(s) {
  if (typeof s !== "string" || !/^[A-Za-z0-9+/]+={0,2}$/.test(s) || s.length % 4) return null;
  try { const bin = atob(s); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u; } catch { return null; }
}
export const isJpeg = (u) => u.length > 4 && u[0] === 0xff && u[1] === 0xd8 && u[2] === 0xff;
export const isWebp = (u) => u.length > 12 && String.fromCharCode(...u.slice(0, 4)) === "RIFF" && String.fromCharCode(...u.slice(8, 12)) === "WEBP";
export const kind = (u) => (isJpeg(u) ? "image/jpeg" : isWebp(u) ? "image/webp" : null);

// plain JSON only (parse gives that), not too deep, strings not huge
function plain(v, depth = 0) {
  if (depth > 5) return false;
  if (v === null || typeof v === "boolean") return true;
  if (typeof v === "number") return Number.isFinite(v);
  if (typeof v === "string") return v.length <= 600;
  if (Array.isArray(v)) return v.length <= 60 && v.every((x) => plain(x, depth + 1));
  if (typeof v === "object") { const k = Object.keys(v); return k.length <= 60 && k.every((x) => x.length <= 60 && plain(v[x], depth + 1)); }
  return false;
}

/** text -> {ok:{name,by,lang,conf,preview,wall,photos:[[key,bytes]]}} | {error, status}. */
export function validate(text) {
  const bad = (error, status = 400) => ({ error, status });
  if (typeof text !== "string" || text.length > MAX_BODY) return bad("too big", 413);
  let o; try { o = JSON.parse(text); } catch { return bad("bad body"); }
  if (!o || typeof o !== "object" || Array.isArray(o)) return bad("bad body");
  if (o.tvtheme !== 1) return bad("tvtheme must be 1");
  const name = clean(o.name), by = clean(o.by);
  if (!name) return bad("name: 1-24 characters");
  if (!by) return bad("by: 1-24 characters");
  if (o.lang !== "vi" && o.lang !== "en") return bad("lang must be vi or en");
  const conf = o.conf;
  if (!conf || typeof conf !== "object" || Array.isArray(conf)) return bad("conf must be an object");
  const extra = Object.keys(conf).filter((k) => !THEME_KEYS.includes(k));
  if (extra.length) return bad("not theme settings: " + extra.slice(0, 5).join(", "));
  if (!plain(conf)) return bad("conf: plain JSON only");
  const cj = JSON.stringify(conf);
  if (cj.length > MAX_CONF) return bad("conf too big (8 KB)");
  const preview = b64(o.preview);
  if (!preview) return bad("preview: base64 jpeg/webp");
  if (preview.length > MAX_PREVIEW) return bad("preview over 150 KB");
  if (!kind(preview)) return bad("preview is not a jpeg/webp");
  let wall = null;
  if (o.wall !== undefined && o.wall !== null) {
    wall = b64(o.wall);
    if (!wall) return bad("wall: base64 jpeg");
    if (wall.length > MAX_WALL) return bad("wall over 1 MB");
    if (!isJpeg(wall)) return bad("wall is not a jpeg");
  }
  const photos = [];
  if (o.walls !== undefined && o.walls !== null) {
    if (typeof o.walls !== "object" || Array.isArray(o.walls)) return bad("walls: an object of photos");
    const keys = Object.keys(o.walls);
    if (keys.length > MAX_PHOTOS) return bad("walls: at most " + MAX_PHOTOS + " photos");
    for (const k of keys) {
      if (!PHOTO_KEY.test(k)) return bad("walls: bad key " + k.slice(0, 20));
      const u = b64(o.walls[k]);
      if (!u) return bad("walls." + k + ": base64 jpeg");
      if (u.length > MAX_PHOTO) return bad("walls." + k + " over 350 KB");
      if (!isJpeg(u)) return bad("walls." + k + " is not a jpeg");
      photos.push([k, u]);
    }
  }
  return { ok: { name, by, lang: o.lang, conf, confJson: cj, preview, wall, photos } };
}

/** every address in it, nested ones too (loop.html?i=<picture>), is https on the project's own site: not worth a red line */
function ownSite(v) {
  let s = v; try { s = decodeURIComponent(v); } catch {}
  const all = [...s.matchAll(/(https?):\/\/([^\/?#\s"']+)/gi)];
  return all.length > 0 && all.every((m) => m[1].toLowerCase() === "https" && m[2].toLowerCase() === ALLOWED_HOST);
}

/** what the reviewer should look at twice */
export function flags(row) {
  const out = [], c = row.conf || {};
  if (c.wall === "web") {
    let host = "";
    try { const u = new URL(String(c.wurl)); host = u.protocol === "https:" ? u.hostname : ""; } catch {}
    if (host !== ALLOWED_HOST) out.push("web wall: wurl is not on " + ALLOWED_HOST + " (" + String(c.wurl).slice(0, 80) + ")");
  }
  const urls = [];
  (function walk(v) {
    if (typeof v === "string") { if (/https?:\/\//i.test(v) && !ownSite(v)) urls.push(v); }
    else if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === "object") for (const k of Object.keys(v)) { walk(k); walk(v[k]); }
  })(c);
  if (urls.length) out.push("URL in conf: " + urls.slice(0, 3).map((u) => u.slice(0, 80)).join(" | "));
  if ((row.name || "").length >= 20 || (row.by || "").length >= 20) out.push("very long name or sender");
  return out;
}

export async function senderHash(ip, day, salt = "") {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ip + "|" + day + "|" + salt));
  return [...new Uint8Array(d).slice(0, 12)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
export const today = () => new Date().toISOString().slice(0, 10);
/** every byte a submission keeps (the storage cap sums it) */
export const sizeOf = (t) => t.preview.length + (t.wall ? t.wall.length : 0) + t.photos.reduce((n, [, u]) => n + u.length, 0);
export function newId() { const b = new Uint8Array(4); crypto.getRandomValues(b); return [...b].map((x) => x.toString(16).padStart(2, "0")).join(""); }
export function tokenOk(header, token) {
  if (!token || token.length < 16) return false;
  const m = /^Bearer (.+)$/.exec(header || "");
  if (!m || m[1].length !== token.length) return false;
  let d = 0; for (let i = 0; i < token.length; i++) d |= m[1].charCodeAt(i) ^ token.charCodeAt(i);
  return d === 0;
}
