// Pure functions of the census Worker (no Worker APIs, so node can test them).
export const MAX_BODY = 4096;
const UUID4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const APPS = ["setup", "phone", "pc", "home", "channels", "games", "tvapps"];   // home: TV Home pings (a TV with only TV Home counts); channels / games / tvapps: errors only
const OKS = ["adb", "video", "optimize"];

// A string cleaned for storage: no control characters, trimmed, at most 64 characters. Not a string -> "".
export function str(x, max = 64) {
  if (typeof x !== "string") return "";
  return x.replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);
}

const flag = (x) => (x === true ? 1 : x === false ? 0 : null);

// body text -> the row to store, or null (malformed). Unknown fields are dropped.
export function validate(text) {
  if (typeof text !== "string" || text.length > MAX_BODY) return null;
  let b;
  try { b = JSON.parse(text); } catch { return null; }
  if (!b || typeof b !== "object" || Array.isArray(b)) return null;
  if (typeof b.id !== "string" || !UUID4.test(b.id)) return null;
  if (!APPS.includes(b.app)) return null;
  const t = b.tv;
  if (!t || typeof t !== "object" || Array.isArray(t)) return null;
  const brand = str(t.brand), model = str(t.model);
  if (!brand || !model) return null;
  if (emulator(t)) return null;                                       // the project's own test AVDs showed on the site as a TV model
  const sdk = Number.isInteger(t.sdk) && t.sdk > 0 && t.sdk < 100 ? t.sdk : null;
  const ok = b.ok && typeof b.ok === "object" && !Array.isArray(b.ok) ? b.ok : {};
  const row = {
    id: b.id.toLowerCase(), app: b.app, v: str(b.v, 32),
    brand, model, device: str(t.device), board: str(t.board), hw: str(t.hw), soc: str(t.soc),
    android: str(typeof t.android === "number" ? String(t.android) : t.android, 16), sdk,
    leanback: t.leanback === true ? 1 : 0,
  };
  for (const k of OKS) row[k] = flag(ok[k]);
  return row;
}

export async function hashId(id) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(id));
  return [...new Uint8Array(d)].slice(0, 8).map((x) => x.toString(16).padStart(2, "0")).join("");
}

export const today = (now = new Date()) => now.toISOString().slice(0, 10);

// Kind of device from its names: a projector, a box / stick, else a TV set. `leanback` is not used: the phone and computer apps
// also report LG / Samsung / Roku TVs, which are not leanback.
const PROJ = /projector|\bproj\b|xgimi|wanbo|dangbei|nebula|benq|epson|hy3[0-9]{2}|\bmogo\b|\bhalo\b|horizon pro|\bz6x?\b|lumi/i;
const BOX = /\b(box|stick|stb|dongle|chromecast|streamer|shield|x96|h96|mecool|ugoos|tanix|beelink|s905\w*|s922\w*|amlogic|rockchip|fpt|viettel|vtvcab|mytv|vnpt|k\+|tv ?box|settop|set-top|firetv|fire tv|aftm?\w*|roku (ultra|express|streaming)|apple tv)\b|mi ?box|tv ?box|\bdv\d{4}|\bmxq|\bt95|\bh96|\bkm\d|\bbox\b/i;
export function kindOf(brand, model, device) {
  const s = [brand, model, device].join(" ");
  if (PROJ.test(s)) return "projector";
  return BOX.test(s) ? "box" : "tv";
}

const verKey = (a) => parseFloat(a) || 0;

// An Android emulator (Google's images: ranchu / goldfish hardware, sdk_* or "Android SDK built for" models, emu* / generic* devices)
export function emulator(t) {
  const s = (k) => (typeof t[k] === "string" ? t[k].toLowerCase() : "");
  return /^(ranchu|goldfish)/.test(s("hw")) || /^vsoc/.test(s("soc")) || s("board") === "cutf"   // cutf / vsoc: Cuttlefish, a cloud emulator (sent as a OnePlus 8 Pro)
    || /^goldfish/.test(s("board")) || /^(sdk_|android sdk built for)/.test(s("model"))
    || /^(emu64|emulator|generic)/.test(s("device"));
}

// rows: SELECT brand, model, device, android, COUNT(*) n GROUP BY brand, model, device, android. Only what the site shows: the ok
// flags and last day stay in D1 for a later dashboard (they were 52% of the reply, unread)
// TV Setup / TV Home also run on phones and tablets: such a report stays in D1 but is not a TV on the site's list. Every report is an Android device read over ADB (getprop, Census.kt), so no leanback feature = a phone or tablet, whichever app sent it
// (the phone app connected to a phone sent a Samsung SM-S908E)
export const listed = (r) => !(Number(r.leanback) === 0 && r.leanback !== null && r.leanback !== undefined);

export function aggregate(rows, now = new Date()) {
  const by = new Map();
  for (const r of rows) {
    if (!listed(r)) continue;
    const key = r.brand + "\u0000" + r.model;
    let m = by.get(key);
    if (!m) by.set(key, (m = { brand: r.brand, model: r.model, count: 0, android: new Set(), devices: new Set(), tv: 0, box: 0, projector: 0 }));
    const n = Number(r.n) || 0;
    m.count += n;
    if (r.android) m.android.add(r.android);
    if (r.device && r.device !== r.model && m.devices.size < 4) m.devices.add(r.device);
    m[kindOf(r.brand, r.model, r.device)] += n;
  }
  const models = [...by.values()].map((m) => ({
    brand: m.brand, model: m.model, count: m.count,
    android: [...m.android].sort((a, b) => verKey(b) - verKey(a)),
    devices: [...m.devices].sort(),
    kind: ["tv", "box", "projector"].reduce((k, x) => (m[x] > m[k] ? x : k), "tv"),
  })).sort((a, b) => b.count - a.count || a.brand.localeCompare(b.brand) || a.model.localeCompare(b.model));
  return { updated: today(now), installs: models.reduce((s, m) => s + m.count, 0), models };
}

// rows: SELECT day, app, active, new FROM days ORDER BY day -> one entry a day, apps side by side, and the running total of installs
export function series(rows, now = new Date()) {
  const by = new Map();
  for (const r of rows) {
    let d = by.get(r.day);
    if (!d) by.set(r.day, (d = { day: r.day, active: {}, new: {} }));
    if (r.active != null) d.active[r.app] = Number(r.active) || 0;
    d.new[r.app] = Number(r.new) || 0;
  }
  let total = 0;
  const days = [...by.values()].sort((a, b) => (a.day < b.day ? -1 : 1)).map((d) => {
    total += Object.values(d.new).reduce((s, n) => s + n, 0);
    return { ...d, total };
  });
  return { updated: today(now), installs: total, days };
}

// ---------------------------------------------------------------------------------------------------- crash reports
// GitHub's download count of what people install: the apps (.apk) and the computer app (.zip, .tar.gz). Not versions.json,
// SHA256SUMS(.sig): every app fetches those on each update check, ~70% of all downloads and none of them a person
export const INSTALLER = /\.(apk|zip|tar\.gz)$/i;
export function downloads(releases) {
  let n = 0;
  for (const r of releases || []) for (const a of r.assets || []) if (INSTALLER.test(a.name)) n += a.download_count || 0;
  return n;
}

export const MAX_CRASH = 24 * 1024;
export const CRASH_PER_DAY = 20;
export const CRASH_ROWS = 5000;
export const CRASH_IDS = 200;                                         // distinct installs counted per (sig, v)
const WHERE = ["kotlin", "page"];
// KidGuard (another repo, same owner) shares the crash table since 2026-11-12; tv-remote's own tools leave its rows out
// (crashes.py without --app kidguard, crashwatch: a separate macOS notice, never news.txt). Pings stay tv-remote only.
const CRASH_APPS = [...APPS, "kidguard"];

// Text with its line breaks kept (a stack), no other control characters, cut to max.
export function text(x, max) {
  if (typeof x !== "string") return "";
  return x.replace(/\r\n?/g, "\n").replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, " ").trim().slice(0, max);
}

// body text -> the crash to store, or null (malformed, or an emulator: like a ping).
export function validateCrash(body) {
  if (typeof body !== "string" || body.length > MAX_CRASH) return null;
  let b;
  try { b = JSON.parse(body); } catch { return null; }
  if (!b || typeof b !== "object" || Array.isArray(b)) return null;
  if (typeof b.id !== "string" || !UUID4.test(b.id)) return null;
  if (!CRASH_APPS.includes(b.app) || !WHERE.includes(b.where)) return null;
  const d = b.dev && typeof b.dev === "object" && !Array.isArray(b.dev) ? b.dev : {};
  if (emulator(d)) return null;
  const kind = str(b.kind, 120);
  if (!kind) return null;
  const sdk = Number.isInteger(d.sdk) && d.sdk > 0 && d.sdk < 100 ? d.sdk : null;
  return {
    id: b.id.toLowerCase(), app: b.app, v: str(b.v, 32), loc: b.where, kind, msg: text(b.msg, 300), stack: text(b.stack, 8192),
    brand: str(d.brand), model: str(d.model), android: str(typeof d.android === "number" ? String(d.android) : d.android, 16), sdk,
    os: str(d.os), arch: str(d.arch, 32), java: str(d.java, 32),
  };
}

// Frames of the standard libraries say nothing of where the app broke.
const FRAMEWORK = /^(java|javax|jdk|sun|kotlin|kotlinx|android|androidx|com\.android|libcore|dalvik|org\.chromium)\./;
const NOISE = /^(<anonymous>|\[native code\]|native|eval|async)?$/;

// One stack line -> "where it is" without line numbers or the host, or "" when it is not a frame / not meaningful.
export function frame(line) {
  let s = String(line).trim();
  if (!s || /^(Caused by|Suppressed|\.\.\.)/.test(s)) return "";
  if (/^at\s/.test(s)) s = s.slice(3).trim();                          // Java and Chrome
  else if (/^[^\s(]*@/.test(s)) s = s.replace("@", " (") + ")";        // Firefox / Safari: fn@url:1:2
  else return "";
  s = s.replace(/https?:\/\/[^/\s)]+/g, "").replace(/[?#][^\s):]*/g, "");   // host, query
  s = s.replace(/(:\d+){1,2}(?=\)|$)/g, "");                         // :line  or  :line:col
  s = s.trim();
  if (NOISE.test(s) || FRAMEWORK.test(s)) return "";
  return s;
}

// The text that makes two crashes "the same": app, kind and the first 5 meaningful frames (all frames if none is).
export function sigText(app, kind, stack) {
  const lines = String(stack || "").split("\n");
  let f = lines.map(frame).filter(Boolean).slice(0, 5);
  if (!f.length) f = lines.map((l) => l.trim()).filter((l) => /^at\s|@/.test(l)).slice(0, 5);
  return [app, kind, ...f].join("\n");
}

export async function crashSig(app, kind, stack) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(sigText(app, kind, stack)));
  return [...new Uint8Array(d)].slice(0, 12).map((x) => x.toString(16).padStart(2, "0")).join("");
}

// Same length of work whatever the guess, so the secret is not given away by timing.
export async function sameSecret(given, secret) {
  if (!secret || !given) return false;
  const h = async (s) => new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
  const [a, b] = [await h(given), await h(secret)];
  let x = 0;
  for (let i = 0; i < a.length; i++) x |= a[i] ^ b[i];
  return x === 0;
}
