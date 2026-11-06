// The remote's keys and menu entries come from remote.json, the same file the Phone app's native remote reads (PhoneRemote.kt).
// This file reads it and holds REMOTE_DO: what each "do" name in the file does here. Kept free of the page's other scripts at
// load time (the arrows reach them only when run), so tests/remote.test.js can load it alone and check every name has a handler.
const REMOTE = (() => {
  const v = (() => { try { return new URL(document.currentScript.src).search; } catch { return ''; } })();   // ?v=stamp of this script, so an update brings the new file too
  for (let i = 0; i < 2; i++) try {                                   // synchronous: the keys are drawn before remote.js binds them
    const x = new XMLHttpRequest();                                   // twice: one dropped answer from a busy TV left a page with no keys (census 83256fe0)
    x.open('GET', 'remote.json' + v, false); x.send();
    if (x.status === 200) return JSON.parse(x.responseText);
  } catch { /* the next try, then none */ }
  return { rows: {}, menus: {} };
})();
// An entry as this page shows it: the "web" fields lay over the common ones, then the "kinds" override of the connected TV
// (t / icon only: VIDAA's settings key is named Menu); the kind is core.js's TV_KIND, the same one the phone reads (PhoneRemote.kt).
const remoteEntry = (e, kind = typeof TV_KIND === 'string' ? TV_KIND : 'android') => {
  const k = e.kinds && e.kinds[kind];
  return { ...e, ...e.web, ...k && { t: k.t || (e.web && e.web.t) || e.t, icon: k.icon || e.icon }, sub: (e.web && e.web.sub) || e.sub };
};
const remoteWords = (id) => T(id);                                 // "t" and "sub" are ids in i18n/<lang>.json
const remoteHere = (e) => !e.cores || e.cores.includes('web');   // "cores" says "phone" (the native remote) or "web" (the pages)
// What a guest let in by a shared QR needs for an entry (SCOPE in core.js): pressing anything is "control"; sending files and
// what the owner set up is "full"; a screenshot only looks. On the TV web page only: the apps are the owner's.
const REMOTE_ROLE = { shot: '', share: 'full', automation: 'full', file: 'full', media: 'full', cast: 'full' };
const remoteRole = (e) => { const r = e.do in REMOTE_ROLE ? REMOTE_ROLE[e.do] : 'control'; return r ? [r] : []; };
const remoteCan = (e) => typeof can !== 'function' || [...e.needs || [], ...remoteRole(e)].every(can);   // can(): what this TV does, and this browser may (core.js)

// A menu from remote.json as menuDlg wants it. ctx.when: the conditions an entry may name; ctx.expand: the lists that stand in for an
// "expand" entry (each already in menuDlg's form).
function remoteMenu(name, ctx = {}) {
  const out = [];
  for (const raw of REMOTE.menus[name]) {
    if (!remoteHere(raw) || !remoteCan(raw) || (raw.when && !{ native: typeof NATIVE === 'function' && NATIVE(), roku: typeof TV_KIND === 'string' && TV_KIND === 'roku', ...ctx.when }[raw.when])) continue;   // native: LG, Samsung, Roku, VIDAA
    if (raw.expand) { const f = (ctx.expand || {})[raw.expand]; if (f) out.push(...f()); continue; }
    const e = remoteEntry(raw);
    if (e.do === 'menu') {                                            // a group: opened with the same ctx (its lengths, its "when")
      const sub = remoteMenu(e.arg, ctx);
      if (sub.length === 1) out.push(sub[0]);                         // a group of one is that one (an LG: the screen off only)
      else if (sub.length) out.push(['@' + e.icon + '|' + remoteWords(e.t), e.sub ? remoteWords(e.sub) : '', () => menuDlg(T(REMOTE_GROUPS[e.arg]), -1, sub)]);
      continue;                                                       // none: nothing here (a guest, an LG)
    }
    out.push(['@' + e.icon + (e.tint ? ':' + e.tint : '') + '|' + remoteWords(e.t), e.sub ? remoteWords(e.sub) : '', () => REMOTE_DO[e.do](e.arg)]);
  }
  return out;
}

// the groups an entry opens ("do": "menu", "arg": its name): their titles
const REMOTE_GROUPS = { get: 'rkey.open_get', send: 'rkey.open_send', timerMore: 'rkey.timer_more', tools: 'rkey.tools', tvkeys: 'rkey.tools_keys' };
const APP_KEYS = new Set(['YOUTUBE', 'NETFLIX', 'PRIME']);
const REMOTE_DO = {
  shot: () => screenshot(),
  open: () => quickOpen(),
  apps: () => openInstalled(),
  menu: (m) => menuDlg(T(REMOTE_GROUPS[m]), -1, remoteMenu(m)),
  key: (k) => { buzz(); api('/api/key', { key: k }).then((r) => { if (r && r.noop) toast(T(APP_KEYS.has(k) ? 'remote.app_key_missing' : 'remote.key_no_reaction')); }); },   // a key from a menu (Tools); an app button the screen ignored: its app is not on the TV
  timer: () => timerMenu(),
  scenes: () => scenesMenu(),
  notify: () => notifyDialog(),
  privacy: () => trackingMenu(),
  rokuScreens: () => rokuScreens(),
  draw: () => { if (drawing) drawStop(); else if (!needAdb()) drawStart(); },
  search: () => askStoreSearch(),
  package: () => askStorePage(),
  link: () => askLink(),
  media: () => castPick('image/*,video/*'),
  file: () => castPick('audio/*,video/*,image/*'),
  store: (id) => storePage(id),
  timerPlus: () => timerPlus(),
  timerCancel: () => setSleep(0),
  timerCustom: () => timerCustom(),
  automation: (c) => goTo('auto', c),
  clip: () => clipDialog(),
  screenOff: () => screenOff(),
  dim: () => dimOpen(),
  vbox: () => videoOpen(),
  share: () => shareDialog(),
  inputs: () => inputsMenu(),
  homeCustom: () => homeOpen(),
  simple: () => setSimple(true),                                      // remote.js: the big keys of remote.json "simple" only
  voice: () => $('#voice').click(),                                   // the Simple remote's Speak: the top row's, its ways and its fallbacks
  rescue: async () => {               // a frozen TV: the app on screen is stopped (never a launcher / TV Setup), then Home
    if (await needAdbSoon()) return;
    const r = await api('/api/rescue', {});
    if (r) toast(r.stopped ? T('rescue.closed', { name: r.label || r.stopped }) : T('rescue.home'));
  },
};
