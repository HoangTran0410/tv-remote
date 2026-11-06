// 🧳 Move to a new TV (the part with no page in it, so node can test it: tests/backup.test.js). One file holds what the other cards
// keep apart: {v, made, from: {model}, parts: {home, rules, scenes, channels, optimize, apps}}. The page (backup_ui.js) reads each
// part through the route its own card uses and puts it back the same way; this file builds and reads the file, and decides.
// Home and rules reuse js/tvcopy.js (what is taken, what is left out, the plan for the new TV). No secret goes in: a PIN is only a
// salted hash on the TV, the Authenticator and Telegram keys are never read; the app locks keep the list of apps only.
// A file from a newer version: parts and fields this version does not know are ignored, never an error.
(function (root) {
  const tc = typeof window !== 'undefined' ? window : require('./tvcopy.js');
  const VERSION = 1;
  const PARTS = ['home', 'rules', 'scenes', 'channels', 'optimize', 'apps'];       // the order the page lists them
  const HOME_GROUPS = ['look', 'grid', 'keys'], RULES_GROUPS = ['bedtime', 'limits', 'sched', 'total', 'locks'];
  const PKG = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
  const list = (a) => (Array.isArray(a) ? a : []);
  const obj = (o) => (o && typeof o === 'object' && !Array.isArray(o) ? o : null);

  // [o]: model, made (ISO date), name (the TV's name), home (TV Home's config), rules (/api/rules' answer), scenes (array), channels
  // (the TV Channels export), tv (/api/backup's answer: optimize, apps). A part that could not be read is missing from the file.
  function backupBuild(o) {
    const parts = {}, from = o.name || o.model || '';
    if (o.home) { const b = tc.tvCopyTake(HOME_GROUPS, o.home, null, from); if (b.groups.length) parts.home = { groups: b.groups, fields: b.home }; }
    if (o.rules) { const b = tc.tvCopyTake(RULES_GROUPS, null, o.rules, from); if (b.groups.length) parts.rules = { groups: b.groups, fields: b.rules }; }
    if (Array.isArray(o.scenes)) parts.scenes = o.scenes;
    if (obj(o.channels)) parts.channels = o.channels;
    if (o.tv && Array.isArray(o.tv.optimize)) parts.optimize = o.tv.optimize.filter((p) => PKG.test(p));
    if (o.tv && Array.isArray(o.tv.apps)) parts.apps = o.tv.apps.filter((a) => a && PKG.test(a.pkg)).map((a) => ({ pkg: a.pkg, label: String(a.label || a.pkg) }));
    return { v: VERSION, made: o.made || '', from: { model: o.model || '', name: o.name || '' }, parts };
  }

  // The file read back: only the parts this version knows and that have the right shape; null when it is not a backup of ours.
  function backupRead(x) {
    x = obj(x);
    if (!x || typeof x.v !== 'number' || !obj(x.parts)) return null;
    const p = x.parts, parts = {};
    const grouped = (g, allowed) => obj(g) && Array.isArray(g.groups) && obj(g.fields) ? { groups: g.groups.filter((id) => allowed.includes(id)), fields: g.fields } : null;
    const h = grouped(p.home, HOME_GROUPS), r = grouped(p.rules, RULES_GROUPS);
    if (h && h.groups.length) parts.home = h;
    if (r && r.groups.length) parts.rules = r;
    if (Array.isArray(p.scenes) && p.scenes.some(obj)) parts.scenes = p.scenes.filter(obj);
    if (obj(p.channels) && (list(p.channels.favourites).length || list(p.channels.groups).length)) parts.channels = p.channels;
    if (Array.isArray(p.optimize) && p.optimize.some((q) => PKG.test(q))) parts.optimize = p.optimize.filter((q) => PKG.test(q));
    if (Array.isArray(p.apps) && p.apps.some((a) => a && PKG.test(a.pkg))) parts.apps = p.apps.filter((a) => a && PKG.test(a.pkg)).map((a) => ({ pkg: a.pkg, label: String(a.label || a.pkg) }));
    if (!Object.keys(parts).length) return null;
    const f = obj(x.from) || {};
    return { v: x.v, made: String(x.made || ''), from: { model: String(f.model || ''), name: String(f.name || '') }, parts };
  }

  // What the preview lists: [{ id, n }] in page order, n = what the part holds (groups, scenes, favourites + groups, apps).
  function backupSummary(file) {
    return PARTS.filter((id) => file.parts[id]).map((id) => {
      const p = file.parts[id];
      const n = id === 'home' || id === 'rules' ? p.groups.length : id === 'channels' ? list(p.favourites).length + list(p.groups).length : p.length;
      return { id, n };
    });
  }

  // The bundle js/tvcopy.js plans from, for 'home' or 'rules'.
  function backupBundle(file, id) {
    const p = file.parts[id];
    if (!p) return null;
    return tc.tvCopyRead({ tvcopy: 1, from: file.from.name || file.from.model, date: file.made, groups: p.groups, home: id === 'home' ? p.fields : {}, rules: id === 'rules' ? p.fields : {} });
  }

  // Scenes: one with the same id is replaced, the others are added (as the Scenes card's own import).
  function backupMergeScenes(old, incoming) {
    const out = list(old).slice();
    for (const sc of incoming) {
      const i = sc && sc.id ? out.findIndex((o) => o.id === sc.id) : -1;
      if (i >= 0) out[i] = sc; else out.push(sc);
    }
    return out;
  }

  // The apps to bring back on the new TV: those not on it yet. [have] the packages there (null: unknown, all are listed), [catalog] TV Apps'
  // list ({ id, pkg, name }): a known app is installed from there (checked against its maker's certificate), the others open in the store.
  function backupAppsTodo(apps, have, catalog) {
    const cat = new Map(list(catalog).map((a) => [a.pkg, a]));
    const there = have ? new Set(have) : null;
    return list(apps).filter((a) => !there || !there.has(a.pkg)).map((a) => {
      const c = cat.get(a.pkg);
      return c ? { pkg: a.pkg, label: a.label, how: 'tvapps', id: c.id, name: c.name } : { pkg: a.pkg, label: a.label, how: 'store' };
    });
  }

  const fileName = (model, made) => 'tv-backup-' + String(model || 'tv').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') + '-' + (made || '').slice(0, 10) + '.json';

  root.BACKUP_PARTS = PARTS;
  root.backupBuild = backupBuild;
  root.backupRead = backupRead;
  root.backupSummary = backupSummary;
  root.backupBundle = backupBundle;
  root.backupMergeScenes = backupMergeScenes;
  root.backupAppsTodo = backupAppsTodo;
  root.backupFileName = fileName;
  if (typeof module !== 'undefined') module.exports = root;
})(typeof window !== 'undefined' ? window : (typeof module !== 'undefined' ? module.exports : this));
