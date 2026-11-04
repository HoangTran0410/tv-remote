// Spoken or typed sentences the remote carries out itself, before handing anything to the TV's Assistant: "mở YouTube",
// "tắt tiếng", "hẹn giờ tắt 30 phút", the name of a scene... Faster than the Assistant, and works on TVs without one.
// The phone app has the same rules (app/shared .../Voice.kt). Tests: app/shared/tests/voice.test.js.
(function (root) {
  // lower case, no accents, no punctuation: "Mở YouTube!" and "mo youtube" read the same
  const fold = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[đĐ]/g, 'd').toLowerCase()
    .replace(/[^\p{L}\p{N} ]/gu, ' ').replace(/\s+/g, ' ').trim();

  // "tivi" / "ti vi" read as "tv": the form every phrase of the list is kept in
  const norm = (s) => fold(s).replace(/\bti ?vi\b/g, 'tv');
  const esc = (x) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const alt = (l) => [...l].sort((x, y) => y.length - x.length).map(esc).join('|');

  // The words are data: the app serves the signed list (docs/voice/phrases.json, the same one Voice.kt reads) at voice/phrases.json.
  // What a key or a verb does is code here and in Voice.kt. Compiled once per list.
  const compiled = new WeakMap();
  function settingWords(langs, ids) {
    const m = new Map();
    for (const l of langs) for (const [id, ps] of Object.entries(l.settings || {})) if (id in ids) for (const p of ps) { const w = norm(p); if (w) m.set(w, id); }
    return m;
  }
  function compile(list) {
    if (compiled.has(list)) return compiled.get(list);
    const langs = Object.values(list.lang);
    const words = (a) => (a || []).map(norm).filter(Boolean);
    const all = (...path) => [...new Set(langs.flatMap((l) => words(path.reduce((o, k) => (o ? o[k] : undefined), l))))];
    const keys = new Map();
    for (const l of langs) for (const [key, ps] of Object.entries(l.keys || {})) for (const p of words(ps)) keys.set(p, key);
    const tune = all('channel', 'tune');
    const noise = alt(all('open', 'noise'));
    const vol = (dir) => new RegExp('^(?:' + alt(all('volume', dir)) + ')(?: (?:(?:len|xuong|them|by|to) )?(\\d+))?$');
    const c = {
      rev: list.rev, fillers: all('fillers').sort((x, y) => y.length - x.length), keys,
      volUp: vol('up'), volDown: vol('down'),
      timerCancel: new Set(all('timer', 'cancel')), hours: new Set(all('timer', 'hours')),
      timerSet: new RegExp('^(?:' + alt(all('timer', 'set')) + ') (\\d+) ?(' + alt([...all('timer', 'hours'), ...all('timer', 'minutes')]) + ')?$'),
      open: new RegExp('^(?:' + alt([...all('open', 'verbs'), ...tune]) + ')(?: (?:' + noise + '))? (.+)$'),
      close: new RegExp('^(?:' + alt(all('close', 'verbs')) + ') (?:(?:' + noise + ') )?(.+)$'),
      appWord: new RegExp('^(?:(?:' + alt(all('open', 'verbs')) + ') )?(?:' + noise + ') (.+)$'),   // "mở ứng dụng X": X is an app's name, matched loosely (near)
      tune: new RegExp('^(?:' + alt(tune) + ') (.+)$'),
      channel: new RegExp('^(?:(?:' + alt(all('channel', 'verbs')) + ') )?(?:' + alt(all('channel', 'words')) + ') (.+)$'),
      channelNames: new RegExp('^(?:' + (list.channel_names || []).join('|') + ')$'),
      neverClose: new Set(list.never_close || []),
      apps: (list.apps || []).map((a) => ({ label: a.label, pkgs: a.pkgs || [], names: new Set(words(a.names)), search: a.search || null, tube: a.tube || null })),
      settings: list.settings || {}, settingWords: settingWords(langs, list.settings || {}),     // a Settings page by the list's id: "wifi", "bluetooth"
      searchBare: all('search', 'verbs').length ? new RegExp('^(?:' + alt(all('search', 'verbs')) + ') (.+)$') : null,   // "tìm X": X searched in the app picked (prefs.search)
      connect: all('connect').length ? new RegExp('^(.+) (?:' + alt(all('connect')) + ') (.+)$') : null,   // "X on Y": X and Y, split at the last connector
    };
    compiled.set(list, c);
    return c;
  }

  // politeness and filler at either end ("giúp tôi", "đi", "nhé", "please") off; a sentence that is only filler stays
  function strip(s, c) {
    for (let again = true; again;) {
      again = false;
      for (const f of c.fillers) {
        if (s.startsWith(f + ' ')) { s = s.slice(f.length + 1); again = true; break; }
        if (s.endsWith(' ' + f)) { s = s.slice(0, -f.length - 1); again = true; break; }
      }
    }
    return s;
  }

  // an app called [want]: its alias in the list (a package the TV has), else the installed names, loosely
  function findApp(want, apps, c, loose, prefs) {
    const alias = c.apps.find((a) => a.names.has(want));
    if (alias && alias.tube === 'main' && prefs && prefs.youtube) { const own = (apps || []).find((a) => a.pkg === prefs.youtube); if (own) return { pkg: own.pkg, name: own.name }; }   // "YouTube" means the client the person picked
    const named = (apps || []).map((a) => ({ a, n: fold(a.name) })).filter((x) => x.n);
    const fuzzy = (w) => {
      const hit = named.find((x) => x.n === w) || named.find((x) => x.n.startsWith(w) || w.startsWith(x.n + ' '))
        || named.find((x) => w.length >= 3 && x.n.includes(w)) || named.find((x) => x.n.length >= 3 && w.includes(x.n));
      return hit ? { pkg: hit.a.pkg, name: hit.a.name } : null;
    };
    if (alias) {
      for (const p of alias.pkgs) { const have = named.find((x) => x.a.pkg === p); if (have || !named.length) return { pkg: p, name: have ? have.a.name : alias.label }; }
      return named.length ? fuzzy(fold(alias.label)) : null;
    }
    return loose ? fuzzy(want) : null;
  }
  // the words of [text] that [x] (folded) came from, with their accents ("sơn tùng"), else [x]: a search is better in the person's own spelling
  function spelled(text, x) {
    const ws = x.split(' '), toks = String(text).trim().split(/\s+/).map((t) => t.replace(/[^\p{L}\p{N}]/gu, '')).filter(Boolean), f = toks.map(fold);
    for (let i = 0; i + ws.length <= f.length; i++) if (ws.every((w, j) => f[i + j] === w)) return toks.slice(i, i + ws.length).join(' ');
    return x;
  }

  // "<open verb>? X <connector> Y" with Y an app (an alias, or an installed name in full) and X more than that app: X is looked up inside Y
  function searchIn(text, s, apps, c, prefs) {
    if (!c.connect) return null;
    const o = c.open.exec(s), m = c.connect.exec(o ? o[1] : s);
    if (!m) return null;
    const x = c.searchBare && c.searchBare.exec(m[1]) ? c.searchBare.exec(m[1])[1] : m[1];   // "tìm sơn tùng trên youtube": the search word is not part of the query
    const known = (w) => findApp(w, apps, c, false, prefs) || (apps || []).filter((a) => fold(a.name) === w).map((a) => ({ pkg: a.pkg, name: a.name }))[0] || null;
    const target = known(m[2]);
    if (!target) return null;
    const kx = known(x);
    if (kx && kx.pkg === target.pkg) return null;                   // "mở youtube trên youtube"
    return searchCommand(target, spelled(text, x), c.apps.find((a) => a.pkgs.includes(target.pkg)) || c.apps.find((a) => a.names.has(m[2])));
  }
  // [query] in [target]: the web search of the alias that owns it, null when it has none (the app can then only be opened)
  const searchCommand = (target, query, alias) => ({ kind: 'search', pkg: target.pkg, name: target.name, query, url: alias && alias.search ? alias.search.replace('{q}', encodeURIComponent(query)) : null });
  // "tìm X" with no app said, when the person picked one to search in (prefs.search, among the apps); else null: the TV's own search
  function bareSearch(text, s, apps, c, prefs) {
    const own = prefs && prefs.search && (apps || []).find((a) => a.pkg === prefs.search), m = own && c.searchBare && c.searchBare.exec(s);
    return m ? searchCommand({ pkg: own.pkg, name: own.name }, spelled(text, m[1]), c.apps.find((a) => a.pkgs.includes(own.pkg))) : null;
  }
  // what [want] names among the installed apps and the pins ([{key, title, app, link}]), best match only. Quality: 0 the whole name, 1 a start of it,
  // 2 [want] inside the name, 3 the name inside [want]. One target at the best quality: it. A pin named like an app that matched too: the app.
  // Several at the same quality: a 'choice' of up to MAX_CHOICES; this is the only place a list can come from (Voice.kt best()).
  const MAX_CHOICES = 3;
  // a name as it sounds: no spaces or marks, "y" as "i"; the recognizer splits and respells brand names ("VietMITV" heard as "Việt MyTV")
  const sound = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '').replace(/y/g, 'i');
  // the installed apps whose name sounds closest to [want], a few letters off, asked only after the word "app": at most a quarter of the
  // letters, one for a short name; several as close: a 'choice' (Voice.kt near())
  function near(want, apps) {
    const w = sound(want);
    if (w.length < 3) return null;
    const off = (apps || []).map((a) => { const n = sound(fold(a.name)); return [a, n, n ? edits(w, n) : 99]; })
      .filter((x) => x[2] <= Math.max(1, Math.floor(Math.min(w.length, x[1].length) / 4)));
    if (!off.length) return null;
    const top = Math.min(...off.map((x) => x[2]));
    const at = off.filter((x) => x[2] === top).map((x) => x[0])
      .sort((x, y) => x.name.length - y.name.length || (x.name < y.name ? -1 : x.name > y.name ? 1 : 0)).map((a) => ({ kind: 'app', pkg: a.pkg, name: a.name }));
    return at.length === 1 ? at[0] : { kind: 'choice', options: at.slice(0, MAX_CHOICES) };
  }
  // letters to add, drop or change to turn a into b (Levenshtein)
  function edits(a, b) {
    let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
      const cur = [i];
      for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = cur;
    }
    return prev[b.length];
  }
  function best(want, apps, pins, exactOnly, withApps) {
    const heard = sound(want);
    const quality = (n) => !n ? null : (n === want || heard.length >= 4 && sound(n) === heard) ? 0 : exactOnly ? null : (n.startsWith(want) || want.startsWith(n + ' ')) ? 1
      : want.length >= 3 && n.includes(want) ? 2 : n.length >= 3 && want.includes(n) ? 3 : null;
    const hits = [];
    if (withApps) for (const a of apps || []) { const q = quality(fold(a.name)); if (q != null) hits.push([q, { kind: 'app', pkg: a.pkg, name: a.name }]); }
    for (const p of pins || []) { const q = quality(fold(p.title)); if (q != null) hits.push([q, { kind: 'pin', key: p.key, name: p.title, app: p.app || '', link: p.link || null }]); }
    if (!hits.length) return null;
    const top = Math.min(...hits.map((h) => h[0]));
    let at = hits.filter((h) => h[0] === top).map((h) => h[1]);
    const appNames = new Set(at.filter((x) => x.kind === 'app').map((x) => fold(x.name)));
    at = at.filter((x) => x.kind !== 'pin' || !appNames.has(fold(x.name)));   // a pin named like an app that matched too is that app's twin: the app
    const seen = new Set();
    at = at.filter((x) => { const id = x.kind + ':' + (x.pkg || x.key); if (seen.has(id)) return false; seen.add(id); return true; });
    at.sort((x, y) => x.name.length - y.name.length || (x.name < y.name ? -1 : x.name > y.name ? 1 : 0));   // the shortest name first (the closest), the same order as Voice.kt
    return at.length === 1 ? at[0] : { kind: 'choice', options: at.slice(0, MAX_CHOICES) };
  }
  const chName = (w) => w.replace(/([a-z]) (\d)/g, '$1$2');

  // apps: [{pkg, name}]; scenes: [{id, name}]; list: the phrases (default: the app's, read once); pins: TV Home's pinned tiles [{key, title, app, link}].
  // Returns what to do, or null for "ask the Assistant".
  function voiceCommand(text, apps, scenes, list, pins, prefs) {
    const raw = fold(text);
    if (!raw) return null;
    const scene = (scenes || []).find((x) => fold(x.name) === raw || fold('canh ' + x.name) === raw || fold('scene ' + x.name) === raw);
    if (scene) return { kind: 'scene', scene };
    list = list || phrases();
    if (!list) return null;
    const c = compile(list), s = strip(norm(text), c);
    const said = (scenes || []).find((x) => (x.say || []).some((p) => { const n = norm(p); return n && (n === norm(text) || n === s); }));   // a scene's own phrases ("Câu gọi"): theirs win over the built-in commands
    if (said) return { kind: 'scene', scene: said };
    if (c.keys.has(s)) return { kind: 'key', key: c.keys.get(s), times: 1 };
    let m = c.volUp.exec(s);
    if (m) return { kind: 'key', key: 'VOL_UP', times: Math.min(+(m[1] || 3), 15) };
    m = c.volDown.exec(s);
    if (m) return { kind: 'key', key: 'VOL_DOWN', times: Math.min(+(m[1] || 3), 15) };
    if (c.timerCancel.has(s)) return { kind: 'timer', min: 0 };
    m = c.timerSet.exec(s);
    if (m) return { kind: 'timer', min: Math.min(+m[1] * (c.hours.has(m[2] || '') ? 60 : 1), 720) };
    m = c.close.exec(s);
    if (m) { const hit = findApp(m[1], apps, c, true, prefs); if (hit && !c.neverClose.has(hit.pkg)) return { kind: 'close', pkg: hit.pkg, name: hit.name }; }
    const found = searchIn(text, s, apps, c, prefs);
    if (found) return found;
    const o = c.open.exec(s), verb = o ? o[1] : null, want = verb == null ? s : verb;
    let hit = findApp(want, apps, c, false, prefs);
    if (hit) return { kind: 'app', pkg: hit.pkg, name: hit.name };
    // a name said in full: an installed app (after "open" only: a bare word is not asked to open anything), else a pin; this before the channels, a tile the person named "VTV3" is theirs
    const whole = best(want, apps, pins, true, verb != null);
    if (whole) return whole;
    m = c.appWord.exec(s);
    if (m) { const hit2 = best(m[1], apps, pins, false, true) || near(m[1], apps); if (hit2) return hit2; }   // "app" said: a name close to one installed
    if (c.settingWords.has(want)) { const id = c.settingWords.get(want); return { kind: 'setting', id, actions: c.settings[id] }; }   // after the apps: an app named "Wifi" is the app
    // before the apps by name: "mở kênh vtv1" is a channel, not an app called "kenh vtv1"
    m = c.channel.exec(s);
    if (m) return { kind: 'channel', name: c.channelNames.test(m[1]) ? chName(m[1]) : m[1] };
    if (c.channelNames.test(want)) return { kind: 'channel', name: chName(want) };
    m = c.tune.exec(s);
    if (m) return { kind: 'channel', name: m[1] };
    if (verb != null) { const b = best(verb, apps, pins, false, true); if (b) return b; }
    return bareSearch(text, s, apps, c, prefs);                      // last: "tìm vtv3" is a channel first, then a search in the app picked
  }

  // True when the sentence asks to open or close something: the phone then asks the TV for its app names first
  function wantsApp(text, list, prefs) {
    list = list || phrases();
    if (!list) return false;
    const c = compile(list), s = strip(norm(text), c);
    return c.open.test(s) || c.close.test(s) || !!(prefs && prefs.search && c.searchBare && c.searchBare.test(s));
  }

  // the list the app serves, read once and kept (a page reload takes a new one)
  let loaded = null;
  function phrases() {
    if (root.VOICE_PHRASES) return root.VOICE_PHRASES;
    if (loaded || typeof XMLHttpRequest === 'undefined' || typeof document === 'undefined') return loaded;
    try { const x = new XMLHttpRequest(); x.open('GET', 'voice/phrases.json', false); x.send(); loaded = JSON.parse(x.responseText); } catch (e) { loaded = null; }
    return loaded;
  }

  // the link TV Channels (dev.tvremote.channels) takes; spaces as %20
  const channelLink = (name) => 'tvchannels://find/' + encodeURIComponent(name);

  root.fold = fold;
  root.channelLink = channelLink;
  root.voiceCommand = voiceCommand;
  root.voiceWantsApp = wantsApp;
  if (typeof module !== 'undefined') module.exports = { fold, norm, voiceCommand, wantsApp, channelLink };
})(typeof window !== 'undefined' ? window : globalThis);
