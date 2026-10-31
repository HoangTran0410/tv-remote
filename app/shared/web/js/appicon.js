// App names and pictures, and the picker of an app (chooseApp): shared by the apps tab and TV Home's settings page (home.html).
// ---------- app names + icons (read from the APK on the TV, cached by the server) ----------
// APP_PATHS: where the names and pictures come from; TV Home's own page sets its own (js/home_link.js) before this file runs
const APP_PATHS = window.APP_PATHS || { meta: '/api/app/meta?pkg=', icon: '/api/app/icon?pkg=' };
const META = new Map(), metaWaiters = new Map(), metaQueue = [], metaFailed = new Map();   // failed: asked again after 30 s, not never
let metaBusy = 0;
function appName(pkg) { const m = META.get(pkg); return (m && m.label) || pkg; }
function pumpMeta() {
  while (metaBusy < 4 && metaQueue.length) {
    const pkg = metaQueue.shift(); metaBusy++;
    api(APP_PATHS.meta + encodeURIComponent(pkg), null, true).then((m) => {
      metaBusy--; if (m) META.set(pkg, m); else metaFailed.set(pkg, Date.now()); (metaWaiters.get(pkg) || []).forEach((fn) => fn(m || {})); metaWaiters.delete(pkg); pumpMeta();
    });
  }
}
function onMeta(pkg, fn) {
  if (META.has(pkg)) { fn(META.get(pkg)); return; }
  if (Date.now() - (metaFailed.get(pkg) || 0) < 30000) { fn({}); return; }
  if (!metaWaiters.has(pkg)) { metaWaiters.set(pkg, []); metaQueue.push(pkg); }
  metaWaiters.get(pkg).push(fn); pumpMeta();
}
// An app's picture, or a placeholder until there is one (and for good when there is none, or it fails to load): the first
// letter of its name on a colour taken from the package name, so apps without a logo still look apart.
function appIcon(pkg) {
  const box = el('div', { className: 'appicon placeholder' });
  const hue = [...pkg].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
  const letter = (name) => {
    const ch = ((name || pkg.split('.').pop()).match(/\p{L}|\p{N}/u) || ['?'])[0].toUpperCase();
    if (box.className === 'appicon placeholder' && box.textContent === ch && !box.children.length) return;   // already so: a rewrite flashes and wakes observers
    box.replaceChildren(); box.className = 'appicon placeholder'; box.style.background = `hsl(${hue} 45% 32%)`;
    box.textContent = ch;
  };
  letter('');
  box.fill = (m) => {
    const was = box.filled; box.filled = [m.icon, m.label, m.bg, m.adaptive, m.banner].join('|');
    if (was === box.filled) return;                                   // drawn so already: a new picture would flash its letter first
    if (!m.icon) { letter(m.label); return; }
    const img = el('img', { src: APP_PATHS.icon + encodeURIComponent(pkg), alt: '' });   // not lazy: hidden until it loads, a lazy image never would (Chrome)
    img.onerror = () => letter(m.label);
    img.onload = () => {
      box.replaceChildren(img); box.className = 'appicon'; box.style.background = m.bg || '';
      box.classList.toggle('adaptive', !!m.adaptive); box.classList.toggle('wide', !!m.banner);
    };
    letter(m.label); box.append(img); img.style.display = 'none';            // shown once it has loaded
    img.addEventListener('load', () => { img.style.display = ''; });
  };
  return box;
}
// A modal to pick one of [pkgs] (the TV's apps) by its tile, with a search over the names (no accents needed): the package,
// or null when closed. [now]: the one picked before, marked.
function chooseApp(title, pkgs, now, how = {}) {   // [how]: {label(p), icon(p)} for keys that are no app (a live widget, a shortcut)
  return new Promise((done) => {
    const nameOf = how.label || appName;
    let picked = null;
    const fold = (t) => String(t || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[đĐ]/g, 'd').toLowerCase();
    const q = el('input', { type: 'search', placeholder: T('common.search_by_app_name'), spellcheck: false, autocomplete: 'off' });
    const none = el('p', { className: 'empty', textContent: T('apps.choose_none'), hidden: true });
    const named = (p) => nameOf(p) !== p;                             // a bare package name goes last
    const tiles = pkgs.slice().sort((a, b) => named(b) - named(a) || nameOf(a).localeCompare(nameOf(b))).map((p) => {
      const icon = how.icon ? how.icon(p) : appIcon(p), name = el('span', { textContent: nameOf(p) });
      if (!how.icon) onMeta(p, (m) => { icon.fill(m); put(name, 'textContent', appName(p)); });
      const b = el('button', { type: 'button', className: 'tile' + (p === now ? ' on' : ''), title: p, onclick: () => { picked = p; d.close(); } }, icon, name);
      b.pkg = p; return b;
    });
    const filter = () => {
      const s = fold(q.value.trim()); let n = 0;
      tiles.forEach((t) => { t.hidden = !!s && !fold(nameOf(t.pkg)).includes(s) && !t.pkg.includes(s); if (!t.hidden) n++; });
      none.hidden = n > 0;
    };
    q.addEventListener('input', filter);
    q.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); const t = tiles.find((x) => !x.hidden); if (t) t.click(); } });   // the first one left
    const d = el('dialog', { className: 'ask appchoose' }, el('div', { className: 'card' },
      el('div', { className: 'card-head' }, el('h2', { textContent: title }), el('button', { type: 'button', className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
      q, el('div', { className: 'tiles choosegrid' }, ...tiles), none));
    d.addEventListener('close', () => { d.remove(); done(picked); });
    filter();
    document.body.append(d); d.showModal();
    if (matchMedia('(pointer: fine)').matches) q.focus(); else (tiles.find((t) => t.classList.contains('on')) || q).focus();   // a phone: no keyboard over half the list
  });
}
