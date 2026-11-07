// The building blocks of every tab but the remote (whose keys are laid by hand, to be hit without looking), so the tabs
// read the same: the markup in index.html follows the same shapes. Checked by tests/ui.test.js.
//
//   card        section.card: a head (h2, its tools on the right), then the body; cards space their own children
//   fold card   details.card: a summary (plain words, 14px), opened to the body
//   say         a card with one line: still reading, nothing there, cannot
//   group       div.group: h3 then its controls, one setting or one part of a card
//   note        p.note: a short line of explanation, under a title or a control
//   state       p.state: what is on now ("TV turns off at 23:30")
//   empty       p.empty: the line an empty list shows
//   warn        p.warnline: something to fix
//   list        div.list of div.item: icon, name (+ small line), acts
//   line        div.line: a field and the button beside it (--ctl)
//   chips       div.places: a choice among several, the current one .on (--chip)
//   busy        UI.busy(button, words): a turning ring and what it does, until the returned function puts it back
//   actions     div.actions: a card's main buttons (--ctl); tools in a head and acts in a list are .small (--ctl-sm)
//
// No style="" or style: in markup: a look that is needed twice is a class here and in css/kit.css ("UI kit").
const UI = {
  card(title, o = {}, ...body) {
    const c = el(o.fold ? 'details' : 'section', { className: 'card' + (o.cls ? ' ' + o.cls : '') });
    if (o.id) c.id = o.id;
    if (o.fold) c.append(el('summary', { textContent: title }));
    else c.append(UI.head(title, ...(o.tools || [])));
    c.append(...body.filter((k) => k != null));
    return c;
  },
  head: (title, ...tools) => el('div', { className: 'card-head' }, typeof title === 'string' ? el('h2', { textContent: title }) : title,
    tools.length ? el('div', { className: 'tools' }, ...tools) : null),
  /** A card with only a line in it: still reading, or nothing to show */
  /** A button at work: a turning ring in its icon's place and words of what it does; the returned function puts it back */
  busy(b, words) {
    const was = [...b.childNodes];
    b.disabled = true; b.ownRing = true; b.replaceChildren(el('span', { className: 'minispin' }), el('span', { textContent: words.replace(/^[^\p{L}\p{N}]+/u, '') }));   // its ⏳ would be a second icon
    return () => { b.disabled = false; b.ownRing = false; b.replaceChildren(...was); };   // ownRing: api() adds no second ring
  },
  say: (text, cls = 'note') => el('section', { className: 'card' }, el('p', { className: cls, textContent: text })),
  group: (title, ...body) => el('div', { className: 'group' }, title ? el('h3', { textContent: title }) : null, ...body),
  note: (text) => el('p', { className: 'note', textContent: text }),
  state: (text) => el('p', { className: 'state', textContent: text }),
  empty: (text) => el('p', { className: 'empty', textContent: text }),
  warn: (text) => el('p', { className: 'warnline', textContent: text }),
  list: (rows, emptyText) => rows.length ? el('div', { className: 'list' }, ...rows) : UI.empty(emptyText),
  /** {icon, name, sub, extra (more under the name), acts: [buttons]} */
  item: (o) => el('div', { className: 'item' }, o.icon || null,
    el('div', { className: 'name' }, el('span', { textContent: o.name }), o.sub ? el('small', { textContent: o.sub }) : null, ...(o.extra || [])),
    o.acts && o.acts.length ? el('div', { className: 'acts' }, ...o.acts) : null),
  line: (...kids) => el('div', { className: 'line' }, ...kids),
  /** [{label, on, fn, title, disabled}] */
  chips: (choices) => el('div', { className: 'places' }, ...choices.map((c) =>
    el('button', { className: c.on ? 'on' : '', textContent: c.label, onclick: c.fn, title: c.title || '', disabled: !!c.disabled }))),
  actions: (...btns) => el('div', { className: 'actions' }, ...btns.filter(Boolean)),
  /** A button that is greyed while its work runs. kind: 'primary', 'danger', 'small', or several */
  btn: (label, fn, kind = '') => el('button', { className: kind, textContent: label, onclick: async (e) => {
    const b = e.currentTarget; b.disabled = true; try { await fn(e); } finally { b.disabled = false; } } }),
};

// ---------- icons: the page is written with emoji (short, and i18n.js keys on them), drawn as line icons from the sprite
// (dev/gen/gen_icons.py) in titles, tabs and buttons, where an emoji looks different on every system and an old TV has some
// missing. A title's icon takes its own colour (--h, a hue), a button's the button's. Emoji not listed here stay emoji;
// so do the remote's keys, app tiles, scene icons and running text.
const ICON = {
  '🎛️': ['sliders', 210], '🎮': ['gamepad', 220], '📱': ['apps', 265], '📁': ['folder', 38], '🩺': ['pulse', 350], '⏰': ['alarm', 20], '📺': ['tv', 195],
  '🧭': ['compass', 175], '🖥️': ['monitor', 210], '🚀': ['rocket', 265], '⌨️': ['keyboard', 230], '🎬': ['scene', 300], '📹': ['camera', 330], '📷': ['camera', 330], '🎞️': ['scene', 300],
  '🌙': ['moon', 250], '😴': ['timer', 280], '⏳': ['hourglass', 38], '🔊': ['vol_up', 160], '🔇': ['mute', 160], '⚡': ['zap', 45], '🔅': ['sun', 45],
  '🌐': ['globe', 200], '🛡️': ['shield', 150], '👁️': ['eye', 210], '🔁': ['refresh', 200], '🔄': ['refresh', 200], '↻': ['refresh', 200], '↷': ['rotate', 200],
  '⚙️': ['gauge', 30], '📊': ['chart', 210], '📶': ['wifi', 190], '🧩': ['cpu', 275], '🧠': ['memory', 300], '⬇️': ['download', 205], '⬇': ['download', 205],
  '⬆️': ['upload', 205], '📥': ['upload', 205], '📦': ['package', 30], '🖼️': ['image', 330], '🔠': ['type', 230], '🏠': ['home', 140],
  '💤': ['power', 260], '🗂️': ['files', 38], '❓': ['help', 0], 'ℹ️': ['info', 210], '✏️': ['pen', 40], '✎': ['pen', 40],
  '🔍': ['search', 210], '🗑': ['trash', 0], '📋': ['clipboard', 210], '🧹': ['sparkle', 180], '🔥': ['flame', 15], '💾': ['save', 210],
  '🔗': ['link', 210], '🎧': ['headphones', 290], '🐞': ['bug', 0], '🤖': ['bot', 265], '📝': ['list', 210], '🔒': ['lock', 0], '📅': ['calendar', 160],
  '📜': ['log', 210], '🔌': ['plug', 45], '💬': ['message', 200], '🎤': ['mic', 0], '🛒': ['store', 140], '🔧': ['wrench', 30],
  '📲': ['phone', 265], '🧽': ['eraser', 0], '⏸': ['pause', 210], '⏹': ['stop', 0], '☀️': ['wake', 45], '⏱️': ['timer', 280],
  '🛠️': ['settings', 210], '🔔': ['bell', 45], '▶': ['play', 210], '↩': ['undo', 210], '✅': ['check', 145], '🌡️': ['thermometer', 15], '🔵': ['bluetooth', 220],
  // plain marks too (the user, 2026-10-25): a font draws them at its own size and baseline, an old TV's not at all
  '✓': ['check', 145], '✔': ['check', 145], '✕': ['close', 0], '✖': ['close', 0], '✗': ['close', 0], '⏻': ['power', 260], '⚠': ['alert', 38],
  '★': ['star', 45], '☆': ['star', 45], '♪': ['music', 290],
  '⭐': ['star', 45], '📤': ['send', 205], '📂': ['folder', 38], '🆕': ['sparkle', 180], '📡': ['wifi', 190], '📍': ['pin', 0], '📌': ['pin', 0], '➕': ['plus', 145],
};
const TAB_HUE = { remote: 220, apps: 265, files: 38, health: 350, auto: 20, tv: 175 };
// where: titles (coloured) and buttons; not inside these
const ICON_TITLES = 'h2, h3, details > summary, .setrow > b, .step b, .qr > b, .tabs .ico';
const ICON_BTNS = 'button, label.filebtn, a.linkbtn, #toast';
// and the marks before a state's words (✓ done, ✗ failed, the ADB chip, a quick key's ⏻)
const ICON_MARKS = '#chipAdb, .chk .mark, .fleetstep, .ltag, .qkbadge, .tsok, .step .mark, li.ok, li.bad, li.yes, li.no';
const ICON_SKIP = '.rc, .tiles, #scenetpl, #sceneicon, #steps .acts code, .item .ic0';
const ICON_KEYS = Object.keys(ICON).sort((a, b) => b.length - a.length);
UI.icon = (name, hue) => {
  const s = el('span', { className: 'ui-ic i-' + name + (hue != null ? ' hue' : '') });
  if (hue != null) s.style.setProperty('--h', hue);
  s.innerHTML = '<svg class="ic" aria-hidden="true"><use href="#i-' + name + '"/></svg>';
  return s;
};
function iconize(e) {
  if (e.closest(ICON_SKIP) && !e.closest('#kbdpane') || e.querySelector(':scope > .ui-ic')) return;
  const title = e.matches(ICON_TITLES);
  const w = document.createTreeWalker(e, NodeFilter.SHOW_TEXT);
  for (let t; (t = w.nextNode());) {
    const s = t.nodeValue.trimStart(); if (!s) continue;
    const k = ICON_KEYS.find((k) => s.startsWith(k)); if (!k) return;   // the first words do not start with a known emoji
    const tab = e.closest('.tabs [data-tab]'), [name, hue] = ICON[k];
    t.nodeValue = s.slice(k.length).replace(/^️/, '').trimStart();
    if (t.parentNode.classList.contains('ico') && !t.nodeValue) t.parentNode.replaceChildren(UI.icon(name, tab ? TAB_HUE[tab.dataset.tab] : null));
    else {
      const ic = UI.icon(name, title ? hue : null);
      if (!t.nodeValue && !e.textContent.trim()) ic.classList.add('solo');   // an icon-only button: no gap after it, or it sits left of centre
      t.parentNode.insertBefore(ic, t);
    }
    return;
  }
}
function iconizeIn(n) {
  if (n.nodeType === 3) n = n.parentElement;
  if (!n || n.nodeType !== 1) return;
  const all = ICON_TITLES + ', ' + ICON_BTNS + ', ' + ICON_MARKS;
  const own = n.closest(all); if (own) iconize(own);
  n.querySelectorAll(all).forEach(iconize);
}
// after i18n.js (loaded first, so its observer runs first): it translates with the emoji still there
iconizeIn(document.body);
new MutationObserver((list) => { for (const m of list) m.type === 'characterData' ? iconizeIn(m.target) : m.addedNodes.forEach(iconizeIn); })
  .observe(document.body, { subtree: true, childList: true, characterData: true });

// a row of chips wider than its card scrolls sideways: the chosen one brought into view, the side with more faded, and the
// mouse wheel turned sideways over it (a computer has no swipe)
const CHIP_ROWS = '.places, .fchips, .seg, nav.tabs', chipSeen = new WeakSet();
// right to left (Arabic), a row starts at its right and scrollLeft runs from 0 there down to minus the overflow: the far side is
// measured from where the row starts, and the chosen chip is brought in by its place on screen
const RTL = () => document.documentElement.dir === 'rtl';
const chipEdges = (r) => { const max = r.scrollWidth - r.clientWidth, more = max > 2, pos = Math.abs(r.scrollLeft), rtl = RTL();
  r.classList.toggle('more-l', more && (rtl ? pos < max - 2 : pos > 2)); r.classList.toggle('more-r', more && (rtl ? pos > 2 : pos < max - 2)); };
const chipBy = (r, c) => { const a = r.getBoundingClientRect(), b = c.getBoundingClientRect(); return { l: b.left - a.left, r: a.right - b.right }; };
const chipFit = (r) => { const on = r.querySelector(':scope > .on, :scope > [aria-selected=true]');
  if (on && r.scrollWidth > r.clientWidth) {
    if (RTL()) { const d = chipBy(r, on); r.scrollLeft += (d.l - d.r) / 2; }
    else r.scrollLeft = Math.max(0, on.offsetLeft - r.offsetLeft - (r.clientWidth - on.offsetWidth) / 2);
  }
  chipEdges(r); };
const chipObs = new ResizeObserver((list) => list.forEach((e) => chipFit(e.target)));
function chipRows(n) {
  if (n.nodeType !== 1) return;
  const rows = n.matches(CHIP_ROWS) ? [n] : [...n.querySelectorAll(CHIP_ROWS)];
  const own = n.closest && n.parentElement && n.parentElement.closest(CHIP_ROWS); if (own) rows.push(own);   // a chip added or made .on
  for (const r of rows) {
    if (!chipSeen.has(r)) { chipSeen.add(r); chipObs.observe(r); r.addEventListener('scroll', () => chipEdges(r), { passive: true });
      r.addEventListener('wheel', (e) => { if (r.scrollWidth <= r.clientWidth || Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
        const left = r.scrollLeft; r.scrollLeft += e.deltaY; if (r.scrollLeft !== left) e.preventDefault(); }, { passive: false });
      r.addEventListener('focusin', (e) => {   // the D-pad or Tab on a chip half under the fade: browsers leave it there
        let c = e.target; while (c && c.parentElement !== r) c = c.parentElement; if (!c) return; const room = 40;
        if (RTL()) { const d = chipBy(r, c); if (d.l < room) r.scrollLeft -= room - d.l; else if (d.r < room) r.scrollLeft += room - d.r; return; }
        const x = c.offsetLeft - r.offsetLeft, w = c.offsetWidth;
        if (x - room < r.scrollLeft) r.scrollLeft = x - room; else if (x + w + room > r.scrollLeft + r.clientWidth) r.scrollLeft = x + w + room - r.clientWidth; }); }
    requestAnimationFrame(() => chipFit(r));
  }
}
chipRows(document.body);
new MutationObserver((list) => { for (const m of list) { m.addedNodes.forEach(chipRows); if (m.type === 'attributes' && !m.target.matches(CHIP_ROWS)) chipRows(m.target); } })   // not the row's own fade classes
  .observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'aria-selected'] });

// A block shown or hidden slides open / shut, its parent's gap with it, instead of pushing everything below in one jump;
// at once when motion is reduced. A second press halfway turns it back from where it is.
function slide(n, show) {
  const from = n.hidden ? 0 : n.getBoundingClientRect().height;
  if (n._slide) n._slide.cancel();
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) { n.hidden = !show; return; }
  n.hidden = false;
  const full = n.getBoundingClientRect().height, gap = parseFloat(getComputedStyle(n.parentElement).rowGap) || 0;
  const at = (h) => ({ height: h + 'px', marginTop: (full ? (h / full - 1) * gap : 0) + 'px', opacity: full ? h / full : 1 });
  n.classList.add('sliding'); n.classList.toggle('shutting', !show);
  const a = n._slide = n.animate([at(from), at(show ? full : 0)], { duration: 220, easing: 'cubic-bezier(.2,.8,.2,1)' });
  const done = () => { if (n._slide !== a) return; n._slide = null; n.classList.remove('sliding', 'shutting'); };
  a.onfinish = () => { if (!show) n.hidden = true; done(); };
  a.oncancel = done;
}

// A line of text wider than its place slides to its end and back instead of ending in "…" (the phone app's marquee): song
// titles, package names, setting values, scene names. Checked whenever one is added, changes or is resized; a plain "…"
// when motion is reduced. Its words are put in a span.rin, the part that moves.
// never a span.rin itself: ".scene span:last-child" took the one it had just made, wrapped it again, and again, 20 times a
// second (the whole page jerked in WebKit, and its slides stood still until the mouse moved)
const RUN = ['#pltitle', '#plsub', '.nowapp b', '.item .name small.pkg', '.setval'].map((r) => r + ':not(.rin)').join(', '), runSeen = new WeakSet(), runT0 = new Map();
const runObs = new ResizeObserver((list) => list.forEach((e) => runFit(e.target)));
function runFit(t) {
  if (t.classList.contains('rin')) return;
  if (!runSeen.has(t)) { runSeen.add(t); runObs.observe(t); }
  let s = t.firstChild, fresh = false;
  if (!t.clientWidth) {                                               // its tab hidden: WebKit drops the moving layer and, the same animation
    if (s?.run) { s.run.cancel(); s.run = null; delete t.dataset.over; }   // kept, drew it again only when something else moved (the Mac app)
    return;                                                           // shown again: a new one, from where it was (runT0)
  }
  if (!(t.childNodes.length === 1 && s.nodeType === 1 && s.classList.contains('rin'))) {
    if (!t.textContent.trim() || t.querySelector('input, button, select')) return;
    s = el('span', { className: 'rin' }); s.append(...t.childNodes); t.append(s); fresh = true;
  }
  // its own width, not the box's scrollWidth: that counts the copy that follows it round (::after). Less than a pixel over is
  // the rounding of clientWidth (80.08 px of words in an 80.08 px box read as 81 > 80): "Google TV" slid with room to spare
  const over = Math.max(0, Math.ceil(s.getBoundingClientRect().width - 1) - t.clientWidth), same = t.dataset.at === s.textContent;
  if (String(over) === t.dataset.over && same && !fresh) return;     // started again on every call (each second's change on the page) it hitched in WebKit
  t.dataset.over = over; t.dataset.at = s.textContent;
  t.classList.toggle('run', over > 0);
  runSlide(t, s, over > 0);
}
// As the phone app's marquee: one way round, a copy of the words following a gap behind (::after, from data-t), a pause at the
// start of each round. A transform with its distance in px, run by the compositor: a margin was drawn on the page's own
// thread and jerked in the Mac app; the box's contain:paint keeps WebKit drawing the whole moving line (without it the words
// went blank as they slid). Drawn again (a list redrawn, the same title) it carries on where it was ([t0]).
// Three rounds, then it rests at the start (Android's marqueeRepeatLimit), and only while on screen: forever and everywhere, the
// Apps tab's long package names kept the phone's RenderThread at ~31% of a core with nothing changing (the user's phone grew hot
// left on Manage; a CSS animation costs the same, every frame is drawn again either way). A line's rounds start the first time it
// is seen; a touch or the mouse on it runs it again.
const RUN_PX_S = 30, RUN_REST = 1500, RUN_ROUNDS = 3;
const runVis = new IntersectionObserver((list) => list.forEach((e) => { const s = e.target.firstChild; if (s?.nodeType === 1) runPlay(e.target, s, e.isIntersecting); }));
function runSlide(t, s, on) {
  if (s.run) { s.run.cancel(); s.run = null; }
  if (!on || matchMedia('(prefers-reduced-motion: reduce)').matches || s.closest('.setrow.full')) { delete s.dataset.t; s.ms = 0; runVis.unobserve(t); return; }
  const gap = Math.max(40, Math.round(t.clientWidth / 3)); s.dist = Math.ceil(s.getBoundingClientRect().width) + gap;
  s.dataset.t = s.textContent; s.style.setProperty('--gap', gap + 'px');
  s.ms = RUN_REST + s.dist / RUN_PX_S * 1000;
  runVis.unobserve(t); runVis.observe(t);                              // says at once whether it is on screen: runPlay
  t.onpointerenter = () => { if (!s.run || s.run.playState !== 'finished') return; runT0.set(s.textContent, performance.now()); s.run.currentTime = 0; s.run.play(); };
}
/** On screen: its rounds, carried on from where they were when drawn again ([runT0]); off screen: nothing runs. */
function runPlay(t, s, seen) {
  if (s.run) { s.run.cancel(); s.run = null; }
  if (!seen || !s.ms) return;
  const w = s.textContent, ms = s.ms;
  if (!runT0.has(w)) { if (runT0.size > 300) runT0.clear(); runT0.set(w, performance.now()); }
  s.run = s.animate([{ transform: 'none', offset: 0 }, { transform: 'none', offset: RUN_REST / ms }, { transform: 'translateX(' + -s.dist + 'px)', offset: 1 }],
    { duration: ms, iterations: RUN_ROUNDS });
  s.run.currentTime = Math.min(performance.now() - runT0.get(w), ms * RUN_ROUNDS);   // after its rounds it stays at rest
}
let runQ = new Set(), runT = 0;
function runLater(n) {
  if (n.nodeType === 3) n = n.parentElement;
  if (!n || n.nodeType !== 1) return;
  const own = n.closest(RUN); if (own) runQ.add(own);
  n.querySelectorAll(RUN).forEach((t) => runQ.add(t));
  if (!runT) runT = requestAnimationFrame(() => { runT = 0; const q = runQ; runQ = new Set(); q.forEach(runFit); });
}
new MutationObserver((list) => { for (const m of list) { if (m.type === 'characterData') runLater(m.target); else { runLater(m.target); m.addedNodes.forEach(runLater); } } })
  .observe(document.body, { childList: true, subtree: true, characterData: true });
runLater(document.body);
