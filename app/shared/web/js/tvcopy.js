// 📋 Copy settings from one TV to another (the part with no page in it, so node can test it: tests/tvcopy.test.js). The page
// (tvcopy_ui.js) reads TV A through /api/home and /api/rules, keeps what the person ticked as a bundle, and after switching to TV B
// applies the plan this file makes with the same two routes: no new /api. Per-TV things stay out: a photo or video wall is a file
// of A, the QR widgets hold a Wi-Fi password, the PIN is only a salted hash on the TV (it is typed again, never read).
(function (root) {
  // HomeConfig.THEME_KEYS, kept equal by tests/tvcopy.test.js
  const LOOK = ['wall', 'walls', 'wurl', 'wcol', 'wdir', 'wmode', 'wsnap', 'wkeep', 'wevery', 'shade', 'wfit', 'wfade',
    'widgets', 'wsize', 'wopt', 'wplace', 'cols', 'center', 'rows', 'focus', 'tstyle', 'accent', 'motion', 'anim', 'clock24', 'greet'];
  const GRID = ['dock', 'hidden', 'order', 'folders', 'tiles', 'names'];
  const KEYS = ['keyon', 'keyapps', 'autoon', 'autoapp', 'autolast', 'autoall'];
  const isQr = (w) => /^qr[0-9]+$/.test(w);                              // HomeQr.KEYS: a code (a Wi-Fi password) is not copied
  // id, where it lives (home: TV Home's settings, rules: TV Setup's), the words (tvcopy.g_<id>)
  const GROUPS = [
    { id: 'look', at: 'home' }, { id: 'grid', at: 'home' }, { id: 'keys', at: 'home' },
    { id: 'bedtime', at: 'rules' }, { id: 'limits', at: 'rules' }, { id: 'sched', at: 'rules' },
    { id: 'total', at: 'rules' }, { id: 'locks', at: 'rules' },
  ];
  const HOME_FIELDS = { look: LOOK, grid: GRID, keys: KEYS };
  const PIN = /^[0-9]{4,8}$/;

  const same = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));
  function canon(v) {
    if (Array.isArray(v)) return v.map(canon);
    if (v && typeof v === 'object') { const o = {}; Object.keys(v).sort().forEach((k) => { o[k] = canon(v[k]); }); return o; }
    return v === undefined ? null : v;
  }
  const isFile = (w) => /^photo\d*$/.test(w) || /^vid:[0-9a-f]{8}$/.test(w);   // a wall that is a file on A's disk
  // the package a target or key names: "pkg", "pin:pkg/id", "tile:provider/id"; '' for TV Home's own action ("do:...") and the empty
  function targetPkg(t) {
    t = String(t || '');
    if (!t || /^do:/.test(t) || /^folder:/.test(t)) return '';
    const m = /^(?:pin|tile):([^/]+)\//.exec(t);
    return m ? m[1] : t;
  }
  // is a target usable on TV B? [have] is B's installed packages (null: unknown, so everything stays); the project's own apps always
  const ours = (p) => /^dev\.tvremote\./.test(p);
  function present(have, t, tilesAvail) {
    const p = targetPkg(t);
    if (!p || !have || ours(p)) return true;
    if (/^tile:/.test(t) && tilesAvail) return tilesAvail.includes(t);
    return have.has(p);
  }

  // What to keep from TV A: only the ticked groups' data. [home] is /api/home's config, [rules] /api/rules' answer.
  function tvCopyTake(ids, home, rules, from) {
    const b = { tvcopy: 1, from: from || '', date: new Date().toISOString().slice(0, 10), groups: [], home: {}, rules: {} };
    for (const g of GROUPS) {
      if (!ids.includes(g.id)) continue;
      const h = home || {}, r = rules || {};
      if (g.at === 'home' && home) { b.groups.push(g.id); for (const k of HOME_FIELDS[g.id]) if (h[k] !== undefined) b.home[k] = h[k]; }
      if (g.at === 'rules' && rules) {
        b.groups.push(g.id);
        if (g.id === 'bedtime') { b.rules.bedtime = r.bedtime; b.rules.volume = r.volume; b.rules.ram = r.ram; if (r.idle_h !== undefined) b.rules.idle_h = r.idle_h; }
        if (g.id === 'limits') b.rules.limits = (r.limits || []).map((l) => ({ pkg: l.pkg, minutes: l.minutes }));
        if (g.id === 'sched') b.rules.sched = (r.sched || []).map((s) => (s.block ? { pkg: s.pkg, block: true } : { pkg: s.pkg, windows: s.windows, max: s.max }));
        if (g.id === 'total') b.rules.total = { min: (r.total || {}).min || 0, days: (r.total || {}).days || [], warn: (r.total || {}).warn };
        if (g.id === 'locks') {
          const l = r.lock || {};
          b.rules.lock = { apps: (l.apps || []).map((a) => a.pkg), mode: l.mode, allow: l.allow || [] };
        }
      }
    }
    return b;
  }
  // A file or a page's memory read back: the bundle, or null when it is not one of ours
  function tvCopyRead(x) {
    if (!x || x.tvcopy !== 1 || !Array.isArray(x.groups)) return null;
    return { tvcopy: 1, from: String(x.from || ''), date: String(x.date || ''), groups: x.groups.filter((g) => GROUPS.some((y) => y.id === g)),
      home: x.home && typeof x.home === 'object' ? x.home : {}, rules: x.rules && typeof x.rules === 'object' ? x.rules : {} };
  }

  // The plan for TV B. [bHome] [bRules] are B's own answers (rules null: TV Setup is missing or old), [bApps] its packages (array or null),
  // [pin] a PIN the person typed for the locks. Returns { groups: [{ id, state, total, changes, dropped }], home, steps }:
  // state: 'same' (nothing differs), 'change', 'needs_pin' (locks, B has none and none was typed), 'needs_setup' (rules, no TV Setup),
  // 'needs_home' (TV Home is not on B). `home` is the one /api/home body, `steps` the /api/rules bodies in order.
  function tvCopyPlan(bundle, bHome, bRules, bApps, pin) {
    const have = bApps ? new Set(bApps) : null;
    const avail = bHome && Array.isArray(bHome.tilesAvail) ? bHome.tilesAvail.map((t) => t.key) : null;
    const out = { groups: [], home: {}, steps: [] };
    const keep = (a) => (a || []).filter((t) => present(have, t, avail));
    for (const id of bundle.groups) {
      const g = GROUPS.find((y) => y.id === id); if (!g) continue;
      const row = { id, state: 'same', total: 0, changes: 0, dropped: [] };
      out.groups.push(row);
      if (g.at === 'home') {
        if (!bHome) { row.state = 'needs_home'; continue; }
        const n = {};
        for (const k of HOME_FIELDS[id]) {
          if (bundle.home[k] === undefined) continue;
          let v = bundle.home[k];
          if (id === 'look' && (k === 'wall' || k === 'walls')) {
            const ws = [].concat(v);
            if (ws.some(isFile)) { if (k === 'wall') row.dropped.push('wall'); continue; }   // B keeps its own picture
          }
          if (id === 'look' && k === 'widgets') v = (v || []).filter((w) => !isQr(w));
          if (id === 'look' && k === 'wsize') { const o = {}; Object.keys(v || {}).forEach((w) => { if (!isQr(w)) o[w] = v[w]; }); v = o; }
          if (k === 'dock' || k === 'hidden' || k === 'order' || k === 'tiles') { const kept = keep(v); kept.length !== v.length && v.filter((t) => !kept.includes(t)).forEach((t) => row.dropped.push(targetPkg(t) || t)); v = kept; }
          if (k === 'names' || k === 'keyapps') {
            const o = {}; Object.keys(v || {}).forEach((x) => { if (k === 'names' ? present(have, x, avail) : present(have, v[x], avail)) o[x] = v[x]; else row.dropped.push(targetPkg(k === 'names' ? x : v[x]) || x); }); v = o;
          }
          if (k === 'folders') {
            const o = {};
            Object.keys(v || {}).forEach((f) => { const e = JSON.parse(JSON.stringify(v[f])); const ap = keep(e.apps); (e.apps || []).filter((a) => !ap.includes(a)).forEach((a) => row.dropped.push(targetPkg(a) || a)); e.apps = ap; o[f] = e; });
            v = o;
          }
          if (k === 'autoapp' && v && !present(have, v, avail)) { row.dropped.push(targetPkg(v)); v = ''; }
          row.total++;
          if (!same(v, bHome[k])) { row.changes++; n[k] = v; }
        }
        // a dock/order/tiles change goes with the others of its group, so TV Home's clean sees the whole grid at once
        if (id === 'grid' && row.changes) for (const k of GRID) if (bundle.home[k] !== undefined && n[k] === undefined && k !== 'names') n[k] = keepVal(bundle.home[k], k, keep);
        Object.assign(out.home, n);
        row.dropped = uniq(row.dropped);
        row.state = row.changes ? 'change' : 'same';
        continue;
      }
      if (!bRules) { row.state = 'needs_setup'; continue; }
      const r = bundle.rules, b = bRules;
      if (id === 'bedtime') {
        const bt = r.bedtime || {}, ranges = (bt.ranges || []).map((x) => ({ from: x.from, to: x.to }));
        const bb = b.bedtime || {};
        const idle = r.idle_h !== undefined && b.idle_h !== undefined;   // both TV Setups know "turn off when idle" (0.46.1+); an older one is left as it is
        row.total = idle ? 4 : 3;
        if (!same({ on: !!bt.on, ranges }, { on: !!bb.on, ranges: (bb.ranges || []).map((x) => ({ from: x.from, to: x.to })) })) { row.changes++; out.steps.push({ do: 'bedtime', on: !!bt.on, ranges }); }
        if (!same(r.volume || [], b.volume || [])) { row.changes++; out.steps.push({ do: 'volume', ranges: r.volume || [] }); }
        if (r.ram && !same({ on: !!r.ram.on, pct: r.ram.pct }, { on: !!(b.ram || {}).on, pct: (b.ram || {}).pct })) { row.changes++; out.steps.push({ do: 'ram', on: !!r.ram.on, pct: r.ram.pct }); }
        if (idle && (r.idle_h | 0) !== (b.idle_h | 0)) { row.changes++; out.steps.push({ do: 'idle', hours: r.idle_h | 0 }); }
      }
      if (id === 'limits') {
        const a = r.limits || [], bl = new Map((b.limits || []).map((l) => [l.pkg, l.minutes]));
        a.forEach((l) => { if (!present(have, l.pkg)) { row.dropped.push(l.pkg); return; } row.total++; if (bl.get(l.pkg) !== l.minutes) { row.changes++; out.steps.push({ do: 'limit', pkg: l.pkg, minutes: l.minutes }); } });
        bl.forEach((m, pkg) => { if (!a.some((l) => l.pkg === pkg)) { row.changes++; out.steps.push({ do: 'limit', pkg, minutes: 0 }); } });   // B ends like A: a limit only B had goes
      }
      if (id === 'sched') {
        const a = r.sched || [], bs = new Map((b.sched || []).map((s) => [s.pkg, s]));
        const key = (s) => (s.block ? { block: true } : { windows: (s.windows || []).map((w) => ({ days: w.days, from: w.from, to: w.to })), max: s.max || 0 });
        a.forEach((s) => {
          if (!present(have, s.pkg)) { row.dropped.push(s.pkg); return; }
          row.total++;
          if (!bs.has(s.pkg) || !same(key(s), key(bs.get(s.pkg)))) { row.changes++; out.steps.push(Object.assign({ do: 'sched', pkg: s.pkg, on: true }, key(s))); }
        });
        bs.forEach((s, pkg) => { if (!a.some((x) => x.pkg === pkg)) { row.changes++; out.steps.push({ do: 'sched', pkg, on: false }); } });
      }
      if (id === 'total') {
        const t = r.total || {}, bt = b.total || {};
        row.total = 1;
        if (!same({ min: t.min || 0, days: t.min ? t.days || [] : [], warn: t.min ? t.warn : 0 }, { min: bt.min || 0, days: bt.min ? bt.days || [] : [], warn: bt.min ? bt.warn : 0 })) {
          row.changes++;
          out.steps.push(t.min ? { do: 'total', min: t.min, days: t.days && t.days.length < 7 ? t.days : undefined, warn: t.warn } : { do: 'total', min: 0 });
        }
      }
      if (id === 'locks') {
        const l = r.lock || {}, bl = b.lock || {};
        const hasPin = !!bl.pin;
        if (!hasPin && !(pin && PIN.test(pin))) { row.state = 'needs_pin'; row.total = (l.apps || []).length; continue; }
        const pre = !hasPin ? [{ do: 'pin', pin }] : [];
        const bApps2 = new Set((bl.apps || []).map((x) => x.pkg)), steps = [];
        (l.apps || []).forEach((p) => { if (!present(have, p)) { row.dropped.push(p); return; } row.total++; if (!bApps2.has(p)) { row.changes++; steps.push({ do: 'lock', pkg: p, on: true }); } });
        bApps2.forEach((p) => { if (!(l.apps || []).includes(p)) { row.changes++; steps.push({ do: 'lock', pkg: p, on: false }); } });
        const allow = (l.allow || []).filter((p) => present(have, p));
        if (l.mode && (l.mode === 'list' || l.mode === 'kids') && (l.mode !== bl.mode || !same(allow, bl.allow || []))) {
          row.changes++; steps.push({ do: 'kids', mode: l.mode, allow });
        }
        if (!hasPin) { row.changes++; steps.unshift(...pre); }
        out.steps.push(...steps);
      }
      row.dropped = uniq(row.dropped);
      row.state = row.changes ? 'change' : 'same';
    }
    return out;
  }
  const uniq = (a) => a.filter((x, i) => a.indexOf(x) === i);
  function keepVal(v, k, keep) {
    if (k === 'folders') { const o = {}; Object.keys(v || {}).forEach((f) => { const e = JSON.parse(JSON.stringify(v[f])); e.apps = keep(e.apps); o[f] = e; }); return o; }
    return keep(v);
  }
  // true when applying would change something on B
  const tvCopyAny = (plan) => plan.groups.some((g) => g.state === 'change');

  root.TVCOPY_GROUPS = GROUPS;
  root.TVCOPY_LOOK = LOOK;
  root.tvCopyTake = tvCopyTake;
  root.tvCopyRead = tvCopyRead;
  root.tvCopyPlan = tvCopyPlan;
  root.tvCopyAny = tvCopyAny;
  if (typeof module !== 'undefined') module.exports = root;
})(typeof window !== 'undefined' ? window : (typeof module !== 'undefined' ? module.exports : this));
