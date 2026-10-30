// 🏠 TV Home, the project's own launcher (app/tvhome), set up from here (Home.kt → HomeReceiver on the TV): favourite apps in
// their order, apps left off its grid, the background (a built-in one or a photo from this device), the clock. Each change
// is sent at once and shows on the TV's screen as it comes.
let homeNow = null;
// Where the settings are read and written (js/home_link.js): through the app that serves this page, over ADB, or straight to TV Home's own
// page (home.html sets HOME_DIRECT). Everything below asks homeLink, so both ways are the same page.
const homeLink = homeLinkFor(!!window.HOME_DIRECT, (path, data, quiet) => api(path, data, quiet), homeSend);
onTvChange(() => { homeNow = null; homeShots = { mine: {}, ready: {}, now: null }; homeFaces.clear(); homeCard(); });

// the gradients TV Home draws (HomeActivity.wallColors), small enough to tell them apart
const HOME_WALLS = { sky: '#0b1238,#3873bc', night: '#0b0f1a,#18223f', ocean: '#03192b,#0a4a66', dusk: '#1a0f2e,#7a3b3b', forest: '#06140f,#133b2d', aurora: '#071a1f,#2b2a6b', black: '#000,#000',
  sunset: '#1f0d1c,#a14a4a', ember: '#190b08,#7a2e12', plum: '#150b22,#5a2f7a', midnight: '#050a1a,#14307a', teal: '#041a1a,#0e5f5f', slate: '#0f1218,#3a4558', graphite: '#1c1c1e,#1c1c1e', navy: '#0b1d3a,#0b1d3a', wine: '#3a0f1f,#3a0f1f', moss: '#14261b,#14261b' };
const HOME_WALL_WORDS = { sky: 'home.wall_sky', night: 'home.wall_night', ocean: 'home.wall_ocean', dusk: 'home.wall_dusk', forest: 'home.wall_forest', aurora: 'home.wall_aurora', black: 'home.wall_black',
  sunset: 'home.wall_sunset', ember: 'home.wall_ember', plum: 'home.wall_plum', midnight: 'home.wall_midnight', teal: 'home.wall_teal', slate: 'home.wall_slate', graphite: 'home.wall_graphite', navy: 'home.wall_navy', wine: 'home.wall_wine', moss: 'home.wall_moss' };
// The person's own wall (HomeConfig "own", "wcol" 1..4 colours, "wdir"): the TV's directions as CSS, radial to the far corners as GradientDrawable
const HOME_WDIRS = { tl_br: 'to bottom right', tb: 'to bottom', lr: 'to right', bl_tr: 'to top right', br_tl: 'to top left', bt: 'to top', rl: 'to left', tr_bl: 'to bottom left', radial: '' };
const HOME_WDIR_WORDS = { tl_br: 'home.wdir_tl_br', tb: 'home.wdir_tb', lr: 'home.wdir_lr', bl_tr: 'home.wdir_bl_tr', br_tl: 'home.wdir_br_tl', bt: 'home.wdir_bt', rl: 'home.wdir_rl', tr_bl: 'home.wdir_tr_bl', radial: 'home.wdir_radial' };
const homeOwnCols = (c) => (c.wcol || '0b1d3a,14307a,7a2e12').split(',');
function homeOwnCss(c, dir) {
  const cs = homeOwnCols(c).map((x) => '#' + x), d = dir || c.wdir || 'tl_br';
  if (cs.length === 1) cs.push(cs[0]);
  return d === 'radial' ? 'radial-gradient(circle,' + cs + ')' : 'linear-gradient(' + (HOME_WDIRS[d] || HOME_WDIRS.tl_br) + ',' + cs + ')';
}
// The scenes TV Home paints itself (HomeConfig.ART; recipes in home_walls.js), the day's Bing picture, the person's photos (photo, photo2..)
Object.assign(HOME_WALL_WORDS, { dawn: 'home.wall_dawn', peaks: 'home.wall_peaks', dunes: 'home.wall_dunes', waves: 'home.wall_waves', candy: 'home.wall_candy', peach: 'home.wall_peach', meadow: 'home.wall_meadow', mist: 'home.wall_mist', bokeh: 'home.wall_bokeh', canyon: 'home.wall_canyon', bing: 'home.wall_bing' });
const HOME_WEBMODE = { live: 'home.web_mode_live', snap: 'home.web_mode_snap' };   // HomeConfig.WMODES, with its words
const HOME_EVERY = { 'off': 'home.every_off', '30m': 'home.every_30m', '1h': 'home.every_1h', '6h': 'home.every_6h', 'day': 'home.every_day', 'show': 'home.every_show' };   // HomeConfig.EVERY, with its words
// Remote keys that can open an app (HomeConfig.KEY_COLOURS / KEY_DEAD / KEY_NUMBERS), with their words; a dead key is listed only
// when TV Setup offers it on this TV (config.keysDead: the dead keys, and the YouTube / Netflix / Prime buttons even while they work)
const HOME_KEY_WORDS = { 183: 'home.key_red', 184: 'home.key_green', 185: 'home.key_yellow', 186: 'home.key_blue', 170: 'home.key_tv', 178: 'home.key_input',
  190: 'home.key_btn3', 191: 'home.key_btn4', 195: 'home.key_btn8', 192: 'home.key_btn5', 284: 'home.key_allapps', 176: 'home.key_settings' };
const homeKeyName = (code) => HOME_KEY_WORDS[code] ? T(HOME_KEY_WORDS[code]) : String(code - 7);   // KEYCODE_0 is 7
const homeThemeName = (t) => (t.names || {})[window.LANG] || (t.names || {}).en || t.id;   // a ready-made theme's name in the page's language (the signed list's)
const HOME_THEME_KEYS = ['wall', 'walls', 'wurl', 'wmode', 'wsnap', 'wkeep', 'wevery', 'shade', 'wfit', 'widgets', 'wsize', 'wopt', 'cols', 'rows', 'focus', 'motion', 'anim', 'clock24', 'greet'];   // HomeConfig.THEME_KEYS
const HOME_THEME_FILE_MAX = 32768;                                 // a theme file is a few KB; more is not one
const HOME_THEMES_MAX = 12;                                           // HomeConfig.THEMES_MAX
const HOME_PHOTO_MAX = 10;                                            // HomeConfig.PHOTO_MAX
let homeShots = { mine: {}, ready: {}, now: null };                  // the theme previews' backgrounds (TV Home's themeshots), by theme id; now: {key, jpg} the wall in use
const homeFaces = new Map();                                         // a theme's drawn preview, by what it is made of: drawn once, put back on every redraw
const homeThumbs = {};                                                // a photo's small picture by id + its stamp: asked of the TV once
const HOME_WIDGETS = ['system', 'usage', 'calendar', 'lunar', 'timer', 'weather', 'air', 'recent', 'adblock', 'clock', 'market', 'news', 'prayer'];          // HomeConfig.WIDGETS, in the order drawn
// A TV Home with "wsizes" sizes each widget (big / small) and packs them (homePack); one older than that has only the preset layouts below
const HOME_BIG_KINDS = ['system', 'usage', 'calendar', 'weather', 'air', 'market', 'news', 'prayer', 'qr1', 'qr2', 'qr3', 'qr4'];     // HomeConfig.defaultSize: these are big until chosen otherwise
const HOME_W = { big: 100, cal: 120, small: 90, budget: 415, floor: 190 };   // HomeConfig.W_BIG / W_CAL / W_SMALL / BUDGET / FLOOR
const homeSize = (c, k) => (c.wsize && (c.wsize[k] === 'big' || c.wsize[k] === 'small')) ? c.wsize[k] : (HOME_BIG_KINDS.includes(k) ? 'big' : 'small');
// HomeConfig.pack, the same rule (tests/home_pack_cases.json holds the cases both are tested with): walk the widgets in order, a big
// widget is a column, two small ones in a row stack in one, a small one alone is a narrow column; the first column past the budget and
// all after it wait. A pinned note is the last small widget and keeps its place: the widgets before it give way, from the end.
function homePack(keys, big, note) {
  const run = (ks) => {
    const cols = []; let sum = 0, i = 0;
    while (i < ks.length) {
      const k = ks[i], isBig = big.includes(k);
      const group = !isBig && i + 1 < ks.length && !big.includes(ks[i + 1]) ? [k, ks[i + 1]] : [k];
      const w = !isBig ? HOME_W.small : k === 'calendar' ? HOME_W.cal : HOME_W.big;
      if (sum + w > HOME_W.budget) break;
      cols.push({ keys: group, weight: w }); sum += w; i += group.length;
    }
    return { cols, waiting: ks.slice(i) };
  };
  if (!note) return run(keys);
  for (let n = keys.length; n >= 0; n--) {
    const p = run([...keys.slice(0, n), 'note']);
    if (!p.waiting.length) return { cols: p.cols, waiting: keys.slice(n) };
  }
  return { cols: [], waiting: keys };
}
const HOME_LAYOUTS = { row: 'home.layout_row', big: 'home.layout_big', big2: 'home.layout_big2', flip: 'home.layout_flip', quad: 'home.layout_quad' };   // HomeConfig.LAYOUTS
// HomeConfig.COLS: [share of the width %, widgets stacked] per column; "row" is drawn as it looks (it has no fixed places)
const HOME_COLS = { row: [[33, 1], [33, 1], [34, 2]], big: [[58, 1], [42, 2]], big2: [[36, 1], [36, 1], [28, 1]], flip: [[42, 2], [58, 1]], quad: [[50, 2], [50, 2]] };
const HOME_OLD_LAYOUTS = ['row', 'big'];                              // a TV Home without "wlayouts" has only these
const HOME_ROW_CARDS = 3, HOME_LARGE = ['system', 'usage', 'calendar', 'weather'];   // HomeConfig.ROW_CARDS, Widgets.BIG: what "row" shows
const homeSlots = (k) => k === 'row' || !HOME_COLS[k] ? 0 : HOME_COLS[k].reduce((n, c) => n + c[1], 0);   // HomeConfig.slots: what a fixed layout shows (a pinned note takes one)
const HOME_WIDGET_WORDS = { system: 'home.w_system', usage: 'home.w_usage', calendar: 'home.w_calendar', lunar: 'home.w_lunar', timer: 'home.w_timer', weather: 'home.w_weather', air: 'home.w_air', recent: 'home.w_recent', adblock: 'home.w_adblock', clock: 'home.w_clock', market: 'home.w_market', news: 'home.w_news', prayer: 'home.w_prayer' };
const HOME_STYLE_WORDS = { classic: 'home.style_classic', light: 'home.style_light', minimal: 'home.style_minimal', roman: 'home.style_roman', glass: 'home.style_glass', tonal: 'home.style_tonal' };   // HomeConfig.STYLES

// The packed layout drawn small: a column per weight, its widgets stacked, big ones filled (sizes in css/remote.css)
// [x]: the settings dialog's strip, { mark: the widget framed, conf: the settings the widgets are drawn with (tonal warm, a light clock pale) }
function packGlyph(p, big, x = {}) {
  const g = el('div', { className: 'packview', role: 'img', 'aria-label': T('home.pack_alt') });
  const sum = Math.max(HOME_W.floor, p.cols.reduce((n, c) => n + c.weight, 0));
  for (const c of p.cols) {
    const col = el('b'); col.style.setProperty('--w', c.weight / sum * 100);
    for (const k of c.keys) col.append(el('s', { className: [big.includes(k) ? 'big' : '', k === x.mark ? 'mark' : '', x.conf && ['tonal', 'solid', 'none'].includes(homeOpt(x.conf, k, 'look')) ? homeOpt(x.conf, k, 'look') : '', x.conf && k === 'clock' && homeOpt(x.conf, k, 'theme') === 'light' ? 'pale' : ''].filter(Boolean).join(' ') }, ...(x.mark ? [el('em', { textContent: HOME_WIDGET_WORDS[k] ? T(HOME_WIDGET_WORDS[k]).split(':')[0] : k })] : [])));
    g.append(col);
  }
  return g;
}

// An older TV Home's layout drawn small: its columns, the widgets stacked in each (sizes in css/remote.css)
function layGlyph(k) {
  const g = el('i', { className: 'layglyph', 'aria-hidden': 'true' });
  for (const [pct, n] of HOME_COLS[k]) {
    const col = el('b'); col.style.setProperty('--w', pct);
    for (let i = 0; i < n; i++) col.append(el('s'));
    g.append(col);
  }
  return g;
}

// The words the schema names by id (HomeConfig.options: label `home.o_<option>`, value `home.ov_<value>`), listed so the id check sees them used
const HOME_OPT_WORDS = ['home.o_size', 'home.o_face', 'home.o_theme', 'home.o_look', 'home.ov_big', 'home.ov_small', 'home.ov_classic', 'home.ov_minimal', 'home.ov_roman', 'home.ov_modern', 'home.ov_digital', 'home.ov_month', 'home.ov_date',
  'home.ov_dark', 'home.ov_light', 'home.ov_glass', 'home.ov_tonal', 'home.ov_solid', 'home.ov_none', 'home.ov_off', 'home.ov_on',
  'home.o_layout', 'home.o_show', 'home.o_btc', 'home.o_src', 'home.ov_card', 'home.ov_ticker', 'home.ov_all', 'home.ov_gold', 'home.ov_rates', 'home.ov_auto', 'home.ov_mine', 'home.ov_both',
  'home.o_method', 'home.o_asr', 'home.o_hijri', 'home.ov_mwl', 'home.ov_isna', 'home.ov_egypt', 'home.ov_ummalqura', 'home.ov_karachi', 'home.ov_jakim', 'home.ov_kemenag', 'home.ov_singapore',
  'home.ov_turkey', 'home.ov_tehran', 'home.ov_standard', 'home.ov_hanafi', 'home.ov_hijri_m1', 'home.ov_hijri_0', 'home.ov_hijri_p1'];
// A widget's options, from the schema its TV Home reports ("wschema": HomeConfig.options): what was chosen, else the default
const homeOpt = (c, k, id) => {
  const o = ((c.wschema || {})[k] || []).find((x) => x.id === id);
  const v = id === 'size' ? homeSize(c, k) : (((c.wopt || {})[k] || {})[id]);
  return o && o.values.some((x) => x.id === v) ? v : o ? o.default : '';
};

// The widget itself at the chosen options, drawn small: the clock as a face (10:10) in its face and theme, any other widget in its look (glass, tonal, solid, none)
function homeCardPrev(k, c) {
  const big = homeSize(c, k) === 'big';
  if (k === 'clock') {
    const face = homeOpt(c, k, 'face'), light = homeOpt(c, k, 'theme') === 'light';
    let t = '';
    const look = ({ glass: ' look-glass', solid: ' look-solid' }[homeOpt(c, k, 'look') || 'glass'] || '');
    if (face === 'digital')                                           // a screen: the red date line over the digits (ClockFace.digital)
      return el('div', { className: 'wprev wclock digital' + (light ? ' light' : ' dark') + (big ? ' big' : '') + look, role: 'img', 'aria-label': T('home.w_clock'),
        innerHTML: '<svg viewBox="0 0 100 100" aria-hidden="true"><rect x="1" y="14" width="98" height="72" rx="14" class="disc"/><text x="50" y="38" class="date">27</text><text x="50" y="62" class="digits">10:10</text></svg>' });
    if (face === 'modern') for (let i = 0; i < 60; i++) {            // every minute, the hours bright (ClockFace.modern)
      if (i % 5 === 0) continue;
      const a = i * 6 * Math.PI / 180;
      t += `<line x1="${50 + 44 * Math.sin(a)}" y1="${50 - 44 * Math.cos(a)}" x2="${50 + 47 * Math.sin(a)}" y2="${50 - 47 * Math.cos(a)}" class="tick min"/>`;
    }
    for (let i = 0; i < 12; i++) {
      const quarter = i % 3 === 0, a = i * 30 * Math.PI / 180;
      if (face === 'minimal' && !quarter) continue;
      if (face === 'roman' && !quarter) continue;
      const r1 = face === 'minimal' ? 38 : quarter ? 41 : 44, x1 = 50 + 46 * Math.sin(a), y1 = 50 - 46 * Math.cos(a);
      t += `<line x1="${50 + r1 * Math.sin(a)}" y1="${50 - r1 * Math.cos(a)}" x2="${x1}" y2="${y1}" class="tick"/>`;
    }
    const num = face === 'roman' ? [['XII', 50, 25], ['III', 75, 50], ['VI', 50, 75], ['IX', 25, 50]] : face === 'classic' ? [['12', 50, 25], ['3', 75, 50], ['6', 50, 75], ['9', 25, 50]]
      : face === 'modern' ? Array.from({ length: 12 }, (_, i) => [String(i + 1), 50 + 31 * Math.sin((i + 1) * Math.PI / 6), 50 - 31 * Math.cos((i + 1) * Math.PI / 6)]) : [];
    t += num.map(([n, x, y]) => `<text x="${x}" y="${y}" class="num">${n}</text>`).join('');
    if (face === 'modern') t += '<text x="50" y="70" class="date">27</text>';
    const thin = face === 'minimal';
    t += `<line x1="50" y1="50" x2="${50 + 22 * Math.sin(305 * Math.PI / 180)}" y2="${50 - 22 * Math.cos(305 * Math.PI / 180)}" class="hand ${thin ? 'thin' : ''}"/><line x1="50" y1="50" x2="${50 + 34 * Math.sin(60 * Math.PI / 180)}" y2="${50 - 34 * Math.cos(60 * Math.PI / 180)}" class="hand ${thin ? 'thin' : ''}"/><circle cx="50" cy="50" r="3" class="pin"/>`;
    return el('div', { className: 'wprev wclock ' + face + (light ? ' light' : ' dark') + (big ? ' big' : '') + look, role: 'img', 'aria-label': T('home.w_clock'), innerHTML: `<svg viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="49" class="disc"/>${t}</svg>` });
  }
  return el('div', { className: 'wprev wcard ' + (homeOpt(c, k, 'look') || 'glass') + (big ? ' big' : '') },
    el('small', { textContent: HOME_WIDGET_WORDS[k] ? T(HOME_WIDGET_WORDS[k]) : k }), el('b', { textContent: '··' }), el('i'));
}

// One dialog for every widget, drawn from the schema: the layout strip of the widgets that are on with this one framed, the widget at the
// chosen options, a row of chips per option. Each press goes to the TV at once ([send]) and repaints the strip and the widget here.
function homeWidgetDlg(k, c0, title, send) {
  const w = c0.widgets || [], opts = (c0.wschema || {})[k] || [];
  const c = { ...c0, wsize: { ...(c0.wsize || {}) }, wopt: JSON.parse(JSON.stringify(c0.wopt || {})) };
  const strip = el('div', { className: 'wstrip' }), stage = el('div', { className: 'wstage' }), rows = el('div', { className: 'wopts' });
  const d = el('dialog', { className: 'ask wset' }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, el('h2', { textContent: title }), el('div', { className: 'tools' },
      el('button', { type: 'button', className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() }))),
    strip, stage, rows));
  const set = (o, v) => {
    if (o.store === 'wsize') {
      c.wsize = Object.fromEntries([...new Set([...HOME_WIDGETS, ...w])].map((x) => [x, x === k ? v : homeSize(c, x)]));
      send({ wsize: c.wsize });
    } else {
      c.wopt[k] = { ...(c.wopt[k] || {}), [o.id]: v };
      send({ wopt: { [k]: { [o.id]: v } } });
    }
    paint();
  };
  const paint = () => {
    const bigs = w.filter((x) => homeSize(c, x) === 'big');
    strip.replaceChildren(packGlyph(homePack(w, bigs, !!c.note), bigs, { mark: k, conf: c }));
    stage.replaceChildren(homeCardPrev(k, c));
    rows.replaceChildren(...opts.map((o) => el('div', { className: 'wopt' }, el('span', { className: 'lbl', textContent: T(o.label) }),
      el('div', { className: 'fchips', role: 'group', 'aria-label': T(o.label) }, ...o.values.map((v) => {
        const on = homeOpt(c, k, o.id) === v.id;
        return el('button', { type: 'button', className: on ? 'on' : '', 'aria-pressed': String(on), textContent: T(v.w), onclick: () => set(o, v.id) });
      })))));
  };
  paint();
  d.addEventListener('close', () => d.remove());
  document.body.append(d); d.showModal();
}

// The 🏠 card's line and its Customize button: only where TV Home is on the TV
async function homeCard() {
  const line = $('#homestate'), btn = $('#homeopen');
  if (!line) return;
  const r = ADB.state === 'ready' ? await homeLink.read(false, true) : null;
  homeNow = r && r.installed ? r : null;
  line.hidden = btn.hidden = !homeNow;
  if (homeNow) put(line, 'textContent', T(homeNow.home ? 'home.state_on' : 'home.state_off', { v: homeNow.version }));
  const upd = $('#homeupd');
  upd.hidden = !(homeNow && homeNow.latest);
  if (!upd.hidden) put(upd, 'textContent', T('home.update', { v: homeNow.latest }));
  lookOffline(upd, !!(homeNow && homeNow.offline), homeCard);
}

// a newer TV Home from the latest release, put over the one on the TV: its settings stay
async function homeUpdate() {
  const n = await homeLink.update();
  if (n && n.installed) toast(T('home.updated', { v: n.version }));
  homeCard();
}

async function homeOpen() {
  if (!homeLink.direct && await needAdbSoon()) return;                // TV Home's own page has no ADB to wait for
  let r = await homeLink.read(true); if (!r) return;
  const keepThumbs = (n) => { if (n.shots) homeShots = { mine: n.shots.mine || {}, ready: n.shots.ready || {}, now: n.shots.now || null };
    for (const [id, b] of Object.entries(n.thumbs || {})) { const p = (n.photos || []).find((x) => x.id === id) || ((n.video || {}).items || []).map((v) => ({ id: v.id, v: v.added })).find((x) => x.id === id); if (p) homeThumbs[id + ':' + p.v] = b; } };
  keepThumbs(r);
  if (!r.installed) { homeCard(); toast(T('home.missing')); goTo('tv', 'launchcard'); return; }   // from the remote's More: where to get it
  const apps = () => r.apps.map((a) => a.pkg);
  const send = async (change) => {
    const n = await homeLink.write(change);
    if (n && n.installed) { r = n; homeNow = n; draw(); }
  };
  const after = async (n) => { r = n; homeNow = n; draw(); const t = await homeLink.read(true); if (t && t.installed) { keepThumbs(t); r = t; homeNow = t; draw(); } };   // a photo came or went: its small picture
  const dropPhoto = async (id) => { const n = await homeLink.drop(id); if (n && n.installed) await after(n); };
  // on the TV itself (TV Setup's page) there is no ✕ to aim at with the D-pad: OK held on a photo asks, then deletes, as on TV Home
  const holdDrop = (b, id) => {
    let t = 0, held = false;
    b.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      if (e.repeat || held) { e.preventDefault(); return; }
      t = setTimeout(async () => { held = true; if (!await ask(T('home.photo_del_q'), { ok: T('home.photo_remove'), danger: true })) return;
        const box = b.closest('.homewalls'), at = [...box.children].findIndex((c) => c.contains(b));
        await dropPhoto(id);
        const near = box.children[at] || box.children[at - 1];      // its neighbour, not the top of the dialog
        if (near) (near.querySelector('button') || near).focus();
      }, 600);
    });
    b.addEventListener('keyup', (e) => { if (e.key === 'Enter') clearTimeout(t); });
    b.addEventListener('click', (e) => { if (held) { held = false; e.stopImmediatePropagation(); e.preventDefault(); } }, true);
  };

  const row = (pkg, acts) => {
    const icon = Target.kind(pkg) === 'app' ? appIcon(pkg) : qkLogo(r.config, pkg), name = el('span', { textContent: appLabel(pkg) });
    if (!isTile(pkg) && Target.kind(pkg) === 'app') onMeta(pkg, (m) => { icon.fill(m); put(name, 'textContent', appLabel(pkg)); });   // a live widget is no app: TV Setup names it
    return el('div', { className: 'item' }, icon, el('div', { className: 'name' }, name), el('div', { className: 'acts' }, ...acts));
  };
  const isTile = (p) => /^tile:[^/]+\/[a-z][a-z0-9_]{0,15}$/.test(p);
  const appLabel = (pkg) => { const own = (r.config.names || {})[pkg]; if (own) return own; if (homeIsFolder(pkg)) return ((r.config.folders || {})[pkg.slice(7)] || {}).name || T('home.folder_default'); if (Target.kind(pkg) === 'pin') return qkLabel(r.config, r.apps, pkg); if (isTile(pkg)) { const t = (r.config.tilesAvail || []).find((x) => x.key === pkg); return (t && t.title) || pkg.split('/').pop(); } const a = r.apps.find((x) => x.pkg === pkg); return appName(pkg) !== pkg ? appName(pkg) : (a && a.label) || pkg; };
  const tool = (text, title, fn, off) => el('button', { type: 'button', className: 'small iconbtn', textContent: text, title, 'aria-label': title, disabled: !!off, onclick: fn });

  // live widgets (HomeConfig "tiles"): what TV Setup offers, each on or off the app grid; only a TV Home that reports "tilesOk"
  const tilesHead = el('h3', { textContent: T('home.tiles_head') }), tilesNote = el('p', { className: 'note' }), tilesList = el('div', { className: 'list' });
  // no favourites any more: TV Home is one grid, and its order is edited as that grid (home_order.js), saved in one go
  const hidden = el('div', { className: 'list' });
  // the person's own names for apps (HomeConfig "names"); a TV Home older than them reports none, and the part stays hidden
  const names = el('div', { className: 'list' }), namesNote = el('p', { className: 'empty', textContent: T('home.rename_none') });
  const setName = async (p) => {
    const v = await ask(T('home.rename_q'), { value: appLabel(p), ok: T('home.rename_ok') });
    if (v !== null && v !== undefined) send({ names: { ...(r.config.names || {}), [p]: String(v).trim() } });
  };
  const addName = el('button', { type: 'button', className: 'small', textContent: T('home.rename_add'), onclick: async () => {
    const p = await chooseApp(T('home.pick_rename'), apps()); if (p) setName(p);
  } });
  const namesHead = el('div', { className: 'card-head' }, el('h3', { textContent: T('home.names') }), addName);
  const orderHead = el('h3', { textContent: T('home.order') }), orderBox = el('div', { className: 'group' });
  // folders on the grid (HomeConfig "folders"): every edit is one change made by HomeFolders (the TV's Folders.kt does the same) and sent whole
  const foldHead = el('div', { className: 'card-head' }, el('h3', { textContent: T('home.folders_head') })), foldNote = el('p', { className: 'note', textContent: T('home.folders_note') }), foldBox = el('div', { className: 'list' });
  const foldName = (id) => (r.config.folders[id] || {}).name || T('home.folder_default');
  const foldApply = (change) => { if (change) send(change); };
  const addFold = el('button', { type: 'button', className: 'small', textContent: T('home.folder_new'), onclick: async () => {
    const c = r.config, free = apps().filter((x) => !c.dock.includes(x) && !HomeFolders.folderOf(c, x));
    const p = await chooseApp(T('home.folder_first'), free); if (!p) return;
    const n = await ask(T('home.folder_name_q'), { value: T('home.folder_default'), ok: T('home.folder_create') });
    if (n !== null && n !== undefined) foldApply(HomeFolders.create(c, HomeFolders.newId(c), String(n), p));
  } });
  foldHead.append(addFold);
  function drawFolders(c) {
    const on = !!c.foldersOk; foldHead.hidden = foldNote.hidden = foldBox.hidden = !on;
    if (!on) return;
    addFold.disabled = !HomeFolders.canCreate(c);
    const ids = Object.keys(c.folders || {}).sort();
    foldBox.replaceChildren(...(ids.length ? ids : []).map((id) => {
      const f = c.folders[id];
      const rename = el('button', { type: 'button', className: 'small', textContent: T('home.folder_rename'), onclick: async () => {
        const n = await ask(T('home.folder_name_q'), { value: foldName(id), ok: T('home.folder_rename') });
        if (n !== null && n !== undefined) foldApply(HomeFolders.rename(r.config, id, String(n)));
      } });
      const del = el('button', { type: 'button', className: 'small danger', textContent: T('home.folder_delete'), onclick: async () => {
        if (await ask(T('home.folder_delete_q', { name: foldName(id) }), { ok: T('home.folder_delete'), danger: true })) foldApply(HomeFolders.delete(r.config, id));
      } });
      const add = el('button', { type: 'button', className: 'small', textContent: T('home.folder_add_app'), onclick: async () => {
        const p = await chooseApp(T('home.folder_add_app'), apps().filter((x) => !r.config.dock.includes(x) && !(r.config.folders[id] || { apps: [] }).apps.includes(x)));
        if (p) foldApply(HomeFolders.add(r.config, id, p));
      } });
      const rows = f.apps.map((pkg, i) => row(pkg, [
        tool('◀', T('home.folder_left'), () => foldApply(HomeFolders.reorder(r.config, id, pkg, i - 1)), i === 0),
        tool('▶', T('home.folder_right'), () => foldApply(HomeFolders.reorder(r.config, id, pkg, i + 1)), i === f.apps.length - 1),
        tool('⇄', T('home.folder_move_to'), async () => {
          const others = Object.keys(r.config.folders).filter((x) => x !== id);
          const to = others.length ? await ask(T('home.folder_move_to'), { value: '', choices: others.map((x) => ({ value: x, name: foldName(x) })), ok: T('home.folder_move') }) : null;
          if (to && r.config.folders[to.trim()]) foldApply(HomeFolders.add(r.config, to.trim(), pkg));
        }, Object.keys(c.folders).length < 2),
        tool('✕', T('home.folder_out'), () => foldApply(HomeFolders.remove(r.config, id, pkg))),
      ]));
      return el('div', { className: 'group' }, el('div', { className: 'card-head' }, el('h4', { textContent: f.name || T('home.folder_default') }), rename, add, del),
        rows.length ? el('div', { className: 'list' }, ...rows) : el('p', { className: 'empty', textContent: T('home.folder_empty') }));
    }));
  }
  const orderFace = (k) => { if (homeIsFolder(k)) return el('div', { className: 'appicon placeholder' }, UI.icon('folder')); if (Target.kind(k) !== 'app') return qkLogo(r.config, k); const i = appIcon(k); onMeta(k, (m) => i.fill(m)); return i; };
  const orderDraw = homeOrderBox(orderBox, orderFace, (k) => appLabel(k), (change) => send(change));
  const addHidden = el('button', { type: 'button', className: 'small', textContent: T('home.hide'), onclick: async () => {
    const p = await chooseApp(T('home.pick_hide'), apps().filter((x) => !r.config.hidden.includes(x)));
    if (p) send({ hidden: [...r.config.hidden, p] });
  } });
  const walls = el('div', { className: 'fchips homewalls' }), holdNote = el('p', { className: 'note', textContent: T('home.photo_hold'), hidden: true });
  // right over the pictures, filled: a tile among thirty went unseen, and in the head over "Change automatically" nobody
  // looked back up for it (eyes go down to the pictures)
  const upload = el('button', { type: 'button', className: 'primary', textContent: T('home.upload'), onclick: () => window.ONTV && homeLink.canQr ? homeLink.qr() : file.click() });   // on the TV: TV Home's QR, a photo comes from a phone
  // a video or GIF as the background (HomeConfig vid:..., vfps / vres; no sound: a wall is a picture; app/tvhome wall/Videos.kt): the media library
  const vfile = el('input', { type: 'file', accept: 'video/mp4,video/webm,image/gif,.mp4,.webm,.gif', hidden: true, onchange: async () => {
    const f = vfile.files[0]; vfile.value = ''; if (!f) return;
    const mx = (r.video || {}).max || 0;
    if (mx && f.size > mx) { toast(T('home.v_big', { mb: Math.round(mx / 1048576) })); return; }
    const back = UI.busy(vupload, T('home.v_up', { pct: 0 }));
    try { const n = await homeVideo(f, (t) => put(vstate, 'textContent', t)); if (n) await after(n); } finally { back(); put(vstate, 'textContent', ''); }
  } });
  const vupload = el('button', { type: 'button', className: 'primary', textContent: T('home.vupload'), onclick: () => window.ONTV && homeLink.canQr ? homeLink.qr() : vfile.click() });
  const vstate = el('p', { className: 'state', role: 'status' }), vperf = el('p', { className: 'state', role: 'status' }), vnote = el('p', { className: 'note', textContent: T('home.v_note') });
  const vfpsHead = el('h3', { textContent: T('home.v_fps') }), vfps = el('div', { className: 'fchips' });
  const vresHead = el('h3', { textContent: T('home.v_res') }), vres = el('div', { className: 'fchips' });
  // the backgrounds by where they come from, each under its own title with the settings that belong to it (a video's sound only
  // with the videos); `walls` is the colours (and, on a TV Home without rotation, the only box)
  const wallBox = () => el('div', { className: 'fchips homewalls' }), wallHead = (id) => el('h3', { textContent: T(id) });
  const wOnline = wallBox(), wPhotos = wallBox(), wVideos = wallBox(), wScenes = wallBox();
  const hOnline = wallHead('home.wh_online'), hPhotos = wallHead('home.wh_photos'), hVideos = wallHead('home.wh_videos'), hScenes = wallHead('home.wh_scenes'), hColours = wallHead('home.wh_colours');
  // the own wall's colours and direction: each colour the browser's own picker, the TV follows a moment after (as the veil's slider)
  const ownHead = el('h3', { textContent: T('home.wall_own') }), ownStops = el('div', { className: 'ownstops' }), ownDir = el('div', { className: 'fchips htype owndirs' }), ownDirHead = el('h3', { textContent: T('home.own_dir') });
  const ownNote = el('p', { className: 'note', textContent: T('home.own_note') });
  let ownWait = 0;
  const ownSend = (cs, dir) => {
    const c = r.config, ch = { wcol: cs.join(','), wdir: dir || c.wdir || 'tl_br' };
    if (c.wevery === 'off') ch.wall = 'own';                          // seen at once; in a set it is picked like any other
    clearTimeout(ownWait); ownWait = setTimeout(() => send(ch), 150);
  };
  function drawOwn(c) {
    const on = !!c.ownOk && !!c.wrotate;
    for (const x of [ownHead, ownStops, ownDirHead, ownDir, ownNote]) x.hidden = !on;
    if (!on) return;
    const cs = homeOwnCols(c);
    if (ownStops.contains(document.activeElement)) return;            // a picker open: redrawn under it, it would close
    ownStops.replaceChildren(...cs.map((x, i) => {
      const pick = el('input', { type: 'color', value: '#' + x, title: T('home.own_pick'), 'aria-label': T('home.own_pick'), oninput: () => { const n = homeOwnCols(r.config); n[i] = pick.value.slice(1); ownSend(n); } });
      const del = el('button', { type: 'button', className: 'small iconbtn', title: T('home.own_del'), 'aria-label': T('home.own_del'), innerHTML: '<svg class="ic" aria-hidden="true"><use href="#i-close"/></svg>',
        onclick: () => ownSend(homeOwnCols(r.config).filter((_, j) => j !== i)) });
      del.hidden = cs.length < 2;
      return el('span', { className: 'ownstop' }, pick, del);
    }), ...(cs.length < 4 ? [el('button', { type: 'button', className: 'small', onclick: () => ownSend([...cs, cs[cs.length - 1]]) },
      UI.icon('plus'), T('home.own_add'))] : []));
    ownDir.hidden = ownDirHead.hidden = cs.length < 2;
    ownDir.replaceChildren(...Object.keys(HOME_WDIRS).map((k) => {
      const on = (c.wdir || 'tl_br') === k, dot = el('i'); dot.style.background = homeOwnCss(c, k);
      return el('button', { type: 'button', className: on ? 'on' : '', 'aria-pressed': String(on), onclick: () => ownSend(cs, k) }, dot, T(HOME_WDIR_WORDS[k]));
    }));
  }
  const photoAct = el('div', { className: 'actions' }, upload), videoAct = el('div', { className: 'actions' }, vupload);
  const everyHead = el('h3', { textContent: T('home.rotate') }), every = el('div', { className: 'fchips homeevery' }), everyNote = el('p', { className: 'note' });
  const file = el('input', { type: 'file', accept: 'image/*', multiple: true, hidden: true, onchange: async () => {
    // one at a time (the TV takes a photo per ADB push); a TV Home without rotation has room for one only
    const room = r.config.wrotate ? HOME_PHOTO_MAX - (r.photos || []).length : 1;
    const picked = [...file.files]; file.value = '';
    if (picked.length > room) toast(T('home.photos_full', { n: HOME_PHOTO_MAX }));
    const go = picked.slice(0, Math.max(room, 0));
    for (let i = 0; i < go.length; i++) {                             // the button turns and says which one goes, the TV's push takes seconds
      const back = UI.busy(upload, go.length > 1 ? T('home.sending_n', { i: i + 1, n: go.length }) : T('home.sending_photo'));
      try { await homePhoto(go[i], (n) => { r = n; homeNow = n; }); } finally { back(); }
    }
    if (picked.length) after(r);
  } });
  const clock = el('input', { type: 'checkbox', onchange: () => send({ clock24: clock.checked }) });
  const greet = el('input', { type: 'checkbox', onchange: () => send({ greet: greet.checked }) });
  // what the greeting says (HomeConfig "gstyle"): the time of day, that and a name, or the person's own words; only a TV Home that reports "greetOk"
  const gstyleHead = el('h3', { textContent: T('home.gstyle') }), gstyle = el('div', { className: 'fchips' });
  const greetField = (key, max, ph) => {
    const box = el('input', { type: 'text', maxLength: max, placeholder: T(ph), 'aria-label': T(ph) });
    // Save only while the words differ from the TV's: a field that is already saved has no button beside it
    const save = el('button', { type: 'button', className: 'primary', textContent: T('home.greet_save'), hidden: true, onclick: () => { box.dataset.saved = box.value.trim(); save.hidden = true; send({ [key]: box.value.trim() }); } });
    const dirty = () => { save.hidden = box.value.trim() === (box.dataset.saved || ''); };
    box.addEventListener('input', dirty);
    box.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !save.hidden) save.click(); });
    box.sync = (v) => { box.dataset.saved = v || ''; if (document.activeElement !== box) box.value = v || ''; dirty(); };
    return [box, UI.line(box, save)];
  };
  const [gname, gnameLine] = greetField('gname', 24, 'home.gname_ph'), [gtext, gtextLine] = greetField('gtext', 60, 'home.gtext_ph');   // HomeConfig.GNAME_MAX / GTEXT_MAX
  // the header's second line (HomeConfig "dstyle" / "dtext") and each line's type (HEAD_TYPE / HEAD_FLAGS); only a TV Home that reports "headOk"
  const hsplit = el('hr', { className: 'hsplit' });
  const dstyleHead = el('h3', { textContent: T('home.dstyle') }), dstyle = el('div', { className: 'fchips' });
  const [dtext, dtextLine] = greetField('dtext', 60, 'home.dtext_ph');
  const HEAD_SAYS = { time: 'home.gstyle_time', name: 'home.gstyle_name', date: 'home.gstyle_date', lunar: 'home.gstyle_lunar', own: 'home.gstyle_own' };
  // {date} {lunar} …: a press puts the word where the cursor is (HomeConfig.TOKENS; TV Home fills them in)
  const tokens = (box) => el('div', { className: 'fchips htokens' }, el('span', { className: 'muted', textContent: T('home.h_tokens') }),
    ...['greet', 'date', 'weekday', 'day', 'month', 'year', 'lunar', 'canchi'].map((k) => el('button', { type: 'button', className: 'small', textContent: '{' + k + '}', onclick: () => {
      const a = box.selectionStart ?? box.value.length, b = box.selectionEnd ?? a, w = '{' + k + '}';
      box.value = (box.value.slice(0, a) + w + box.value.slice(b)).slice(0, 60); box.focus(); box.setSelectionRange(a + w.length, a + w.length); box.dispatchEvent(new Event('input'));
    } })));
  const gtokens = tokens(gtext), dtokens = tokens(dtext);
  const gtype = el('div', { className: 'fchips htype' }), dtype = el('div', { className: 'fchips htype' });
  const paintType = (box, w, c) => {
    const chip = (k, v, id) => el('button', { type: 'button', className: c[w + k] === v ? 'on' : '', 'aria-pressed': String(c[w + k] === v), onclick: () => send({ [w + k]: v }) }, T(id));
    const flag = (k, id) => el('button', { type: 'button', className: c[w + k] ? 'on' : '', 'aria-pressed': String(!!c[w + k]), onclick: () => send({ [w + k]: !c[w + k] }) }, T(id));
    const grp = (...b) => el('span', { className: 'hgrp' }, ...b);   // size, style, colour: each wraps as a whole, never split across lines
    box.replaceChildren(grp(chip('size', 's', 'home.h_s'), chip('size', 'm', 'home.h_m'), chip('size', 'l', 'home.h_l')),
      grp(flag('bold', 'home.h_bold'), flag('italic', 'home.h_italic'), flag('caps', 'home.h_caps')),
      grp(chip('color', 'white', 'home.h_white'), chip('color', 'dim', 'home.h_dim'), chip('color', 'red', 'home.h_red')));
  };
  // what the first screen draws, each can go (HomeConfig.SHOWS); an older TV Home does not report them, so none is offered to it
  const SHOWS = ['showClock', 'showDate', 'showNet', 'showGear', 'showAdd', 'showSuggest', 'showWatch'];
  const shows = Object.fromEntries(SHOWS.map((k) => [k, el('input', { type: 'checkbox', onchange: () => send({ [k]: shows[k].checked }) })]));
  const showHead = el('h3', { className: 'secthead', textContent: T('home.g_sec_shown') });
  const watchSortHead = el('h3', { textContent: T('home.watch_sort') }), watchSort = el('div', { className: 'fchips' });   // the Play Next order (HomeConfig "watchSort")
  const watchAtHead = el('h3', { textContent: T('home.watch_at') }), watchAt = el('div', { className: 'fchips' });   // where the Play Next items go (HomeConfig "watchAt"); only a TV Home that reports it
  const anim = el('input', { type: 'checkbox', onchange: () => send({ anim: anim.checked }) });
  const infold = el('input', { type: 'checkbox', onchange: () => send({ infold: infold.checked }) });   // HomeConfig "infold", default off
  const shift = el('input', { type: 'checkbox', onchange: () => send({ shift: shift.checked }) });   // HomeConfig "shift", default off
  const dream = el('input', { type: 'checkbox', onchange: () => send({ dream: dream.checked }) });
  const dfaceHead = el('h3', { textContent: T('home.dface') }), dface = el('div', { className: 'fchips' });   // the screensaver's look (HomeConfig "dface"); only a TV Home that reports it
  // remote keys that open an app, and the app that opens each time the TV turns on (HomeConfig keyon / keyapps / autoon / autoapp).
  // Both default off. TV Home answers a key on its own screen; TV Setup does it everywhere and the power-on app: said under each
  const keyOn = el('input', { type: 'checkbox', onchange: () => send({ keyon: keyOn.checked }) });
  const keysHead = el('h3', { textContent: T('home.keys') }), keysNote = el('p', { className: 'note' }), keysList = el('div', { className: 'list' });
  const keysOld = el('button', { type: 'button', className: 'small', onclick: homeUpdate, textContent: T('home.keys_old') });   // a TV Home from before them
  const autoNote = el('p', { className: 'note' });
  const autoAll = el('input', { type: 'checkbox', onchange: () => send({ autoall: autoAll.checked }) });   // HomeConfig autoall: also over an app or input the TV woke into
  const autoAllBox = el('label', { className: 'check' }, autoAll, el('span', { textContent: T('home.auto_all') })), autoAllNote = el('p', { className: 'note' });
  // the widgets over the favourites, in the order they have on the TV (the first on the left); usage and timer read TV Setup, so a
  // TV Setup too old for them is offered first. Chosen: a list to reorder; the others: a switch each, which adds one at the end
  const wTurnOn = async (w) => {
    if ((w === 'usage' || w === 'timer' || w === 'recent') && !await homeLink.setup('home_usage')) return false;
    send({ widgets: [...r.config.widgets, w] });
    return true;
  };
  const wLines = HOME_WIDGETS.map((w) => {
    const box = el('input', { type: 'checkbox', onchange: async () => { if (!await wTurnOn(w)) box.checked = false; } });
    return el('label', { className: 'check' }, box, el('span', { textContent: T(HOME_WIDGET_WORDS[w]) }));
  });
  const wHead = el('h3', { textContent: T('home.widgets') }), wList = el('div', { className: 'list homew' });
  const layHead = el('h3', { textContent: T('home.layout') }), lay = el('div', { className: 'fchips laychips' }), layNote = el('p', { className: 'note' });
  // TV Home's words: the TV's language, or one chosen for it (HomeConfig.LANGS); absent from a TV Home older than the choice
  const langHead = el('h3', { textContent: T('home.lang') }), langs = el('div', { className: 'fchips' });
  // how the focused app shows (HomeConfig "focus"); absent from a TV Home older than the glass
  const focusHead = el('h3', { textContent: T('home.focus') }), focus = el('div', { className: 'fchips' });
  // app tiles per row (HomeConfig "cols", 4..8); only a TV Home that reports "colsOk"
  const colsHead = el('h3', { textContent: T('home.cols') }), cols = el('div', { className: 'fchips' });
  // rows of apps on the first screen (HomeConfig "rows", tenths, 0 = by itself), up to "rowsMax" the TV measured; a TV Home that reports "rowsOk"
  const rowsIn = el('input', { type: 'number', min: '1', step: '0.1', inputMode: 'decimal', 'aria-label': T('home.rows') });
  const rowsSet = el('button', { type: 'button', className: 'primary', textContent: T('home.rows_set'), onclick: () => { const v = parseFloat(String(rowsIn.value).replace(',', '.')); if (v >= 1) send({ rows: Math.round(v * 10) / 10 }); } });
  const rowsAuto = el('button', { type: 'button', textContent: T('home.rows_auto'), onclick: () => { rowsIn.value = ''; send({ rows: 0 }); } });
  rowsIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') rowsSet.click(); });
  const rowsHead = el('h3', { textContent: T('home.rows') }), rowsLine = UI.line(rowsIn, rowsSet, rowsAuto), rowsNote = el('p', { className: 'note' });
  // how much moves (HomeConfig "motion"): "" calm on a TV with little memory; absent from a TV Home older than the levels
  const motionHead = el('h3', { textContent: T('home.motion') }), motion = el('div', { className: 'fchips' });
  const packHead = el('h3', { textContent: T('home.pack') }), packBox = el('div', { className: 'packwrap' }), packNote = el('p', { className: 'note', textContent: T('home.pack_note') });
  // the family's note, pinned on the TV's home screen until it is taken down (HomeConfig.NOTE_MAX)
  const note = el('input', { type: 'text', maxLength: 140, placeholder: T('home.note_ph'), 'aria-label': T('home.note') });
  const pin = el('button', { type: 'button', className: 'primary', textContent: T('home.note_pin'), onclick: () => send({ note: note.value.trim() }) });
  const unpin = el('button', { type: 'button', textContent: T('home.note_clear'), onclick: () => { note.value = ''; send({ note: '' }); } });
  note.addEventListener('keydown', (e) => { if (e.key === 'Enter') pin.click(); });
  // the weather widget's city (HomeConfig.PLACE_MAX); empty = the TV finds its own place
  const place = el('input', { type: 'text', maxLength: 60, placeholder: T('home.place_ph'), 'aria-label': T('home.place') });
  const placeSet = el('button', { type: 'button', className: 'primary', textContent: T('home.place_set'), onclick: () => send({ place: place.value.trim() }) });
  const placeAuto = el('button', { type: 'button', textContent: T('home.place_auto'), onclick: () => { place.value = ''; send({ place: '' }); } });
  place.addEventListener('keydown', (e) => { if (e.key === 'Enter') placeSet.click(); });
  const placeHead = el('h3', { textContent: T('home.place') }), placeLine = UI.line(place, placeSet, placeAuto);
  // the News widget's own RSS links (HomeConfig.FEEDS_MAX, "feeds"): a list, added one by one; the widget reads them when its Sources say Mine or Both
  const feedIn = el('input', { type: 'url', maxLength: 300, placeholder: T('home.feeds_ph'), 'aria-label': T('home.feeds') });
  const feedAdd = el('button', { type: 'button', className: 'primary', textContent: T('home.feeds_add'), onclick: () => {
    const v = feedIn.value.trim(), l = (homeNow && homeNow.config && homeNow.config.feeds) || [];
    if (!/^https?:\/\/\S+$/i.test(v)) { toast(T('home.feeds_bad')); return; }
    if (!l.includes(v)) send({ feeds: [...l, v] });
    feedIn.value = '';
  } });
  feedIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') feedAdd.click(); });
  const feedHead = el('h3', { textContent: T('home.feeds') }), feedLine = UI.line(feedIn, feedAdd), feedList = el('div', { className: 'list' }), feedNote = el('p', { className: 'note', textContent: T('home.feeds_note') });
  const noteHead = el('h3', { textContent: T('home.note') }), noteLine = UI.line(note, pin, unpin);
  // QR widgets (HomeConfig "qrs", home_qr.js): a code of anything, up to four, each its own widget among the others (order, size, off in the list above)
  const qrAdd = el('button', { type: 'button', className: 'small', textContent: T('home.qr_add'), onclick: async () => {
    const k = HOME_QR_KEYS.find((x) => !(r.config.qrs || {})[x]);
    const qr = k && await homeQrAsk(null);
    if (qr) send({ qrs: { ...r.config.qrs, [k]: qr }, widgets: [...r.config.widgets, k] });
  } });
  const qrHead = el('div', { className: 'card-head' }, el('h3', { textContent: T('home.qr') }), qrAdd), qrList = el('div', { className: 'list' }), qrNote = el('p', { className: 'note', textContent: T('home.qr_note') });
  // a web page as the background and the screensaver (HomeConfig.wurl, http or https): asked in its own box when its tile is pressed
  // (a field at the end of thirty tiles went unseen), tried first (homeWebAsk); while it shows, its address and a way to change it
  const webAsk = async () => { const u = await homeWebAsk(r.config.wurl || ''); if (u) send({ wurl: u, wall: 'web' }); };
  const webNow = el('span'), webEdit = el('button', { type: 'button', className: 'small', textContent: T('home.web_change'), onclick: webAsk });
  const webOff = el('button', { type: 'button', className: 'small', textContent: T('home.web_clear'), onclick: () => send({ wurl: '' }) });
  const webHead = el('h3', { textContent: T('home.web') }), webLine = el('div', { className: 'item' }, el('div', { className: 'name' }, webNow), el('div', { className: 'acts' }, webEdit, webOff));
  // how the page updates (HomeConfig wmode / wsnap / wkeep): live behind Home, or a picture taken every few minutes (frost and dim as on a photo)
  const webModeHead = el('h3', { textContent: T('home.web_mode') }), webMode = el('div', { className: 'fchips' });
  const webEveryHead = el('h3', { textContent: T('home.web_every') }), webEvery = el('div', { className: 'fchips' });
  const webKeepBox = el('input', { type: 'checkbox', onchange: () => send({ wkeep: webKeepBox.checked }) });
  const webKeep = el('label', { className: 'check' }, webKeepBox, el('span', { textContent: T('home.web_keep') }));
  const webModeNote = el('p', { className: 'note' });
  // the veil over a photo, a scene or the page (HomeConfig "shade", 0..100 % by 5, 50 the usual one): a busy one fights Home's
  // words; the TV follows the slider while it moves (its last place a moment after), as the dimmer does
  const shadeOut = el('output');
  let shadeWait = 0;
  const shadeIn = el('input', { type: 'range', min: 0, max: 100, step: 5, ariaLabel: T('home.shade'), oninput: () => {
    shadeOut.textContent = shadeIn.value + '%';
    clearTimeout(shadeWait); shadeWait = setTimeout(() => send({ shade: +shadeIn.value }), 150);
  } });
  const shadeHead = el('h3', { textContent: T('home.shade') }), shade = el('div', { className: 'vol boost' }, shadeIn, shadeOut);
  const shadeNote = el('p', { className: 'note', textContent: T('home.shade_note') });
  // how a photo fills the screen (HomeConfig "wfit"): cropped, or whole over itself blurred (a portrait photo)
  const fitHead = el('h3', { textContent: T('home.fit') }), fit = el('div', { className: 'fchips' });
  const fitNote = el('p', { className: 'note', textContent: T('home.fit_note') });
  const gal = el('div', { className: 'homegal' }), credit = el('p', { className: 'note', textContent: T('home.gal_note') });
  const galHead = el('h3', { textContent: T('home.gallery') });
  // the site's moving backgrounds and pages (wallpapers.json "links"): each sets the web wall to its address, as TV Home's own "Suggested"
  const sugHead = el('h3', { textContent: T('home.wh_suggest') }), sugBox = el('div', { className: 'homegal' }), sugNote = el('p', { className: 'note', textContent: T('home.sug_note') });
  sugHead.hidden = sugBox.hidden = sugNote.hidden = true;
  const markSug = (c) => { for (const b of sugBox.children) { const on = c.wall === 'web' && c.wurl === b.dataset.url; b.classList.toggle('on', on); b.setAttribute('aria-pressed', String(on)); } };
  homeGallery(gal, credit, galHead, (n) => { r = n; homeNow = n; draw(); }, { box: sugBox, head: sugHead, note: sugNote, pick: (u) => send({ wall: 'web', wurl: u, wmode: 'live' }), mark: () => markSug(r.config) });

  // "Add to home screen": a picker of the apps (hidden ones too), their shortcuts and the live widgets; a press puts one on the grid or
  // takes it off and the list stays open. Shortcuts and live widgets are in config "tiles" (the order they have on the grid), an app is
  // on the grid unless it is in "hidden"
  const addHome = el('button', { type: 'button', className: 'small', textContent: T('home.qk_addhome'), onclick: async () => {
    const has = (t) => { const c = r.config; return Target.kind(t) === 'app' ? !c.hidden.includes(t) : (c.tiles || []).includes(t); };
    const toggle = (t) => {
      const c = r.config;
      if (Target.kind(t) === 'app') send(c.hidden.includes(t) ? { hidden: c.hidden.filter((x) => x !== t) } : { hidden: [...c.hidden, t], dock: c.dock.filter((x) => x !== t) });
      else send((c.tiles || []).includes(t) ? { tiles: c.tiles.filter((x) => x !== t) } : { tiles: [...(c.tiles || []), t] });
    };
    pickTarget(T('home.qk_addhome'), '', r.config, r.apps, { toggle, has });
  } });
  function drawTiles(c) {
    const on = !!c.tilesOk || !!c.pinsOk; tilesHead.hidden = tilesNote.hidden = tilesList.hidden = addHome.hidden = !on;
    if (!on) return;
    const have = c.tiles || [], offer = c.tilesAvail || [];
    tilesNote.textContent = T(c.tilesState === 'untrusted' ? 'home.tiles_untrusted' : offer.length || have.length || c.pinsOk ? 'home.tiles_note' : 'home.tiles_missing');
    tilesList.replaceChildren(...have.filter((k) => !homeIsFolder(k)).map((k) => row(k, [tool('✕', T('home.tile_remove'), () => send({ tiles: have.filter((x) => x !== k) }))])));   // a live widget whose provider is away stays, to take off
  }
  // the home screen as the settings now make it, at the TV's shape, over every group (CleanTV's idea, 2026-10-27): each change shows
  // here at once, no look over at the TV. The same painter as the themes' pictures; the wall's own picture when the page has one
  const prevCv = el('canvas', { width: 640, height: 360, role: 'img', 'aria-label': T('home.preview') }), prev = el('div', { className: 'homeprev' }, prevCv);
  let prevAt = 0, prevAsked = '', prevTries = 0;
  function drawPrev(c) {
    const [W, H] = homeShape(), h = Math.round(640 * H / W), at = ++prevAt;
    if (prevCv.height !== h) prevCv.height = h;
    const built = (c.themesBuiltin || []).find((t) => t.id === c.theme), look = built && built.look, tiles = c.tiles || [];
    // the wall's picture: TV Home's shot of the wall in use (a TV Home that sends one), else a photo's small picture, else the theme's
    const key = c.wall + '|' + (c.wurl || '') + (c.fitOk ? '|' + (c.wfit || '') : ''), now = (homeShots.now && homeShots.now.key === key && homeShots.now.jpg) || '';
    const p = (r.photos || []).find((x) => x.id === c.wall), thumb = now || (p && homeThumbs[p.id + ':' + p.v]) || (c.theme && homeShots[built ? 'ready' : 'mine'][c.theme]) || '';
    // a new wall, or the first read: TV Home takes its shot then; a read a moment later brings it (only a TV Home that sends `now`),
    // up to 3 (a whole photo's blur, a page's snap: a weak box may need more than the first 2 s)
    if (!now && homeShots.now && (prevAsked !== key || prevTries < 3) && !HOME_ART[c.wall] && !HOME_WALLS[c.wall]) {
      prevTries = prevAsked === key ? prevTries + 1 : 1; prevAsked = key;
      setTimeout(async () => { const t = await homeLink.read(true, true); if (t && t.installed && d.isConnected) { keepThumbs(t); r = t; homeNow = t; drawPrev(r.config); } }, 2000);
    }
    const paint = (img) => { if (at === prevAt) homeThemePaint(prevCv, c, tiles, img, look, new Date()); };
    paint(null);
    if (thumb) { const img = new Image(); img.onload = () => paint(img); img.src = 'data:image/jpeg;base64,' + thumb; }
  }
  function draw() {
    const c = r.config;
    drawPrev(c);
    drawTiles(c); drawFolders(c);
    drawThemes(c);
    orderHead.hidden = orderBox.hidden = c.order === undefined;      // a TV Home older than its own order
    if (c.order !== undefined) orderDraw(c, r.apps);
    hidden.replaceChildren(...c.hidden.map((p) => row(p, [el('button', { type: 'button', className: 'small', textContent: T('home.show'), onclick: () => send({ hidden: c.hidden.filter((x) => x !== p) }) })])));
    namesHead.hidden = names.hidden = c.names === undefined;
    names.replaceChildren(...Object.keys(c.names || {}).map((p) => row(p, [
      el('button', { type: 'button', className: 'small', textContent: T('home.rename_edit'), onclick: () => setName(p) }),
      el('button', { type: 'button', className: 'small', textContent: T('home.rename_orig'), onclick: () => { const n = { ...c.names }; delete n[p]; send({ names: n }); } })])));
    if (c.names && !Object.keys(c.names).length) names.append(namesNote);
    if (!c.hidden.length) hidden.append(el('p', { className: 'empty', textContent: T('home.none_hidden') }));
    upload.disabled = !!c.wrotate && (r.photos || []).length >= HOME_PHOTO_MAX;
    for (const x of [hOnline, wOnline, hScenes, wScenes, hColours]) x.hidden = !c.wrotate;      // a TV Home without rotation has the one box
    if (!c.wrotate) { wPhotos.hidden = wVideos.hidden = true; wOnline.replaceChildren(); wScenes.replaceChildren(); }
    if (c.wrotate) drawRotation(c); else walls.replaceChildren(...(r.photo ? [el('button', { type: 'button', className: c.wall === 'photo' ? 'on' : '', onclick: () => send({ wall: 'photo' }) }, el('i', { className: 'add', textContent: '🖼️' }), el('span', { textContent: T('home.wall_photo') }))] : []),
    ...Object.keys(HOME_WALLS).map((w) => {
      const dot = el('i'); dot.style.setProperty('--c', 'linear-gradient(135deg,' + HOME_WALLS[w] + ')');
      return el('button', { type: 'button', className: c.wall === w ? 'on' : '', onclick: () => send({ wall: w }) }, dot, el('span', { textContent: T(HOME_WALL_WORDS[w]) }));
    }));
    drawOwn(c); markSug(c);
    langHead.hidden = langs.hidden = c.lang === undefined;
    // each language by its own name (langs.json), as TV Home's Customize shows it: a new language needs no id and no code here
    langs.replaceChildren(...[['', T('home.lang_tv')], ...Object.entries(LANGS)].map(([k, name]) =>
      el('button', { type: 'button', className: (c.lang || '') === k ? 'on' : '', onclick: () => send({ lang: k }) }, name)));
    focusHead.hidden = focus.hidden = c.focus === undefined;
    focus.replaceChildren(...[['glass', 'home.focus_glass'], ['ring', 'home.focus_ring']].map(([k, id]) =>
      el('button', { type: 'button', className: (c.focus || 'glass') === k ? 'on' : '', onclick: () => send({ focus: k }) }, T(id))));
    colsHead.hidden = cols.hidden = !c.colsOk;
    cols.replaceChildren(...[4, 5, 6, 7, 8].map((n) =>
      el('button', { type: 'button', className: (c.cols || 5) === n ? 'on' : '', 'aria-pressed': String((c.cols || 5) === n), onclick: () => send({ cols: n }) }, String(n))));
    rowsHead.hidden = rowsLine.hidden = rowsNote.hidden = !c.rowsOk;
    if (document.activeElement !== rowsIn) rowsIn.value = c.rows >= 1 ? String(c.rows) : '';
    rowsIn.placeholder = T('home.rows_auto'); if (c.rowsMax >= 1) rowsIn.max = String(c.rowsMax);
    rowsNote.textContent = c.rowsMax >= 1 ? T('home.rows_note', { n: c.rowsMax }) : T('home.rows_note0');
    motionHead.hidden = motion.hidden = c.motion === undefined || c.anim === false;
    motion.replaceChildren(...[['', 'home.motion_auto'], ['calm', 'home.motion_calm'], ['full', 'home.motion_full']].map(([k, id]) =>
      el('button', { type: 'button', className: (c.motion || '') === k ? 'on' : '', onclick: () => send({ motion: k }) }, T(id))));
    shadeHead.hidden = shade.hidden = shadeNote.hidden = !c.shadeOk;
    fitHead.hidden = fit.hidden = fitNote.hidden = !c.fitOk;
    fit.replaceChildren(...[['cover', 'home.fit_cover'], ['contain', 'home.fit_contain']].map(([k, id]) => {
      const on = (c.wfit || 'cover') === k;
      return el('button', { type: 'button', className: on ? 'on' : '', 'aria-pressed': String(on), onclick: () => send({ wfit: k }) }, T(id));
    }));
    if (document.activeElement !== shadeIn) { shadeIn.value = String(c.shade ?? 50); shadeOut.textContent = (c.shade ?? 50) + '%'; }
    const vOk = !!r.video;                                        // a TV Home from before video has no library
    vupload.hidden = vstate.hidden = videoAct.hidden = hVideos.hidden = !vOk;
    const pl = homePerf((r.video || {}).perf, c.wall); vperf.hidden = !pl;
    if (pl) put(vperf, 'textContent', T(pl[0], pl[1]));
    const hasVideo = vOk && ((r.video || {}).items || []).length > 0;
    for (const x of [vFold, vnote]) x.hidden = !vOk;
    if (hasVideo !== vFold.hadVideo) { vFold.hadVideo = hasVideo; vFold.open = hasVideo; }   // open with a video; folded without (for the next upload)
    const chips = (box, key, list, word) => box.replaceChildren(...list.map((k) => el('button', { type: 'button', className: (c[key] || '') === k ? 'on' : '', 'aria-pressed': String((c[key] || '') === k), textContent: word(k), onclick: () => send({ [key]: k }) })));
    chips(vfps, 'vfps', ['', '60', '30', '24', '15'], (k) => k ? T('home.v_fps_n', { n: k }) : T('home.v_orig'));
    chips(vres, 'vres', ['', '1080', '720', '480'], (k) => k ? T('home.v_res_n', { n: k }) : T('home.v_res_screen'));
    everyHead.hidden = every.hidden = !c.wrotate; if (!c.wrotate) everyNote.hidden = true;
    shift.checked = c.shift === true; shift.closest('label').hidden = c.shift === undefined;
    infold.checked = c.infold === true; infold.closest('label').hidden = c.infold === undefined;   // an older TV Home does not report it
    for (const k of SHOWS) { shows[k].checked = c[k] !== false; shows[k].closest('label').hidden = c[k] === undefined; }
    showHead.hidden = c.showClock === undefined;
    watchAtHead.hidden = watchAt.hidden = c.watchAt === undefined;
    watchSortHead.hidden = watchSort.hidden = c.watchSort === undefined;
    watchSort.replaceChildren(...[['default', 'home.watch_sort_default'], ['latest', 'home.watch_sort_latest']].map(([k, id]) =>
      el('button', { type: 'button', className: c.watchSort === k ? 'on' : '', 'aria-pressed': String(c.watchSort === k), onclick: () => send({ watchSort: k }) }, T(id))));
    watchAt.replaceChildren(...[['bottom', 'home.watch_at_bottom'], ['top', 'home.watch_at_top'], ['under1', 'home.watch_at_under1'], ['tiles', 'home.watch_at_tiles']].map(([k, id]) =>
      el('button', { type: 'button', className: c.watchAt === k ? 'on' : '', 'aria-pressed': String(c.watchAt === k), onclick: () => send({ watchAt: k }) }, T(id))));
    clock.checked = c.clock24; greet.checked = c.greet; anim.checked = c.anim !== false;
    const gOk = !!c.greetOk && c.greet !== false;
    gstyleHead.hidden = gstyle.hidden = !gOk;
    const hOk = !!c.headOk, says = (k) => k !== 'date' && k !== 'lunar' || hOk;
    gstyle.replaceChildren(...['time', 'name', 'date', 'lunar', 'own'].filter(says).map((k) =>
      el('button', { type: 'button', className: c.gstyle === k ? 'on' : '', 'aria-pressed': String(c.gstyle === k), onclick: () => send({ gstyle: k }) }, T(HEAD_SAYS[k]))));
    gnameLine.hidden = !gOk || c.gstyle !== 'name'; gtextLine.hidden = !gOk || c.gstyle !== 'own';
    gtokens.hidden = gtextLine.hidden || !hOk; gtype.hidden = !gOk || !hOk;
    if (hOk) paintType(gtype, 'g', c);
    const dOk = hOk && c.showDate !== false;
    hsplit.hidden = dstyleHead.hidden = dstyle.hidden = dtype.hidden = !dOk;
    dstyle.replaceChildren(...['time', 'date', 'lunar', 'own'].map((k) =>
      el('button', { type: 'button', className: c.dstyle === k ? 'on' : '', 'aria-pressed': String(c.dstyle === k), onclick: () => send({ dstyle: k }) }, T(HEAD_SAYS[k]))));
    dtextLine.hidden = dtokens.hidden = !dOk || c.dstyle !== 'own';
    if (dOk) paintType(dtype, 'd', c);
    gname.sync(c.gname); gtext.sync(c.gtext); dtext.sync(c.dtext);
    dream.checked = !!c.dream;
    dream.closest('label').hidden = c.dream === undefined;           // a TV Home older than the screensaver
    dfaceHead.hidden = dface.hidden = c.dface === undefined;
    dface.replaceChildren(...[['big', 'home.dface_big'], ['modern', 'home.ov_modern'], ['digital', 'home.ov_digital']].map(([k, id]) =>
      el('button', { type: 'button', className: c.dface === k ? 'on' : '', 'aria-pressed': String(c.dface === k), onclick: () => send({ dface: k }) }, T(id))));
    const ka = c.keyapps || {}, kOk = !!c.keysOk;                     // an older TV Home has neither: shown, with its update
    keyOn.checked = !!c.keyon;
    keyOn.disabled = !kOk; keysOld.hidden = kOk;
    keysNote.textContent = kOk ? T(!c.keysSetup ? 'home.keys_need_setup' : 'home.keys_everywhere') : '';
    autoNote.textContent = kOk ? T(!c.keysSetup ? 'home.auto_need_setup' : 'home.auto_note') : '';
    keysList.hidden = !kOk;
    autoAll.checked = !!c.autoall;
    autoAllBox.hidden = autoAllNote.hidden = !kOk || c.autoall === undefined;      // a TV Home from before it does not keep it
    autoAll.disabled = !c.keysAll;                                                 // nor does a TV Setup from before it
    autoAllNote.textContent = T(!c.keysSetup ? 'home.auto_need_setup' : !c.keysAll ? 'home.auto_all_old' : c.autoapp ? 'home.auto_all_app' : 'home.auto_all_home');
    if (kOk) quickKeysDraw(keysList, c, r.apps, send);              // the slots: 1-9 and 0, the colour keys, the power-on app
    const w = c.widgets;                                              // absent: a TV Home older than the widgets
    const qrs = c.qrs || {};                                          // the QR widgets' fields, by key (absent: a TV Home older than them)
    const has = (k) => !!w && w.includes(k);
    wLines.forEach((l, i) => { l.hidden = !w || has(HOME_WIDGETS[i]) || (HOME_WIDGETS[i] === 'weather' && c.place === undefined) || ((HOME_WIDGETS[i] === 'market' || HOME_WIDGETS[i] === 'news') && !c.infoOk) || (HOME_WIDGETS[i] === 'air' && !((c.wschema && c.wschema.air) || (c.wstyles && c.wstyles.air))) || ((HOME_WIDGETS[i] === 'recent' || HOME_WIDGETS[i] === 'adblock' || HOME_WIDGETS[i] === 'prayer') && !(c.wschema && c.wschema[HOME_WIDGETS[i]])); });   // weather, air: a TV Home older than them has no card
    wHead.hidden = !w;
    const sized = !!c.wsizes;                                         // this TV Home sizes the widgets; an older one has the preset layouts
    const bigs = sized && w ? w.filter((k) => homeSize(c, k) === 'big') : [];
    const packed = sized && w ? homePack(w, bigs, !!c.note) : null;
    const places = sized ? 0 : homeSlots(c.wlayout), slots = c.note ? places - 1 : places;
    const waits = (k, i) => sized ? (packed.waiting.includes(k) ? T('home.w_waits_pack') : '')
      : places ? (i >= slots ? T('home.w_waits', { n: slots }) : '')
      : HOME_LARGE.includes(k) && w.slice(0, i).filter((x) => HOME_LARGE.includes(x)).length >= HOME_ROW_CARDS ? T('home.w_waits_row', { n: HOME_ROW_CARDS }) : '';
    const wmove = (i, j) => { const n = w.slice(); [n[i], n[j]] = [n[j], n[i]]; send({ widgets: n }); };
    const wsize = (k, v) => send({ wsize: Object.fromEntries([...new Set([...HOME_WIDGETS, ...(w || [])])].map((x) => [x, x === k ? v : homeSize(c, x)])) });   // a newer TV Home's widgets keep theirs
    const sizeChips = (k) => el('span', { className: 'sizechips', role: 'group', 'aria-label': T('home.size') },
      ...['big', 'small'].map((v) => el('button', { type: 'button', className: 'small' + (homeSize(c, k) === v ? ' on' : ''), textContent: T(v === 'big' ? 'home.size_big' : 'home.size_small'), 'aria-pressed': String(homeSize(c, k) === v), onclick: () => wsize(k, v) })));
    // a widget with looks (the TV Home lists them in "wstyles"): a row of chips under its name; the first is the default
    const styleOf = (k) => (c.wstyle && c.wstyle[k]) || c.wstyles[k][0];
    const wstyle = (k, v) => send({ wstyle: { ...(c.wstyle || {}), [k]: v } });
    const styleChips = (k) => el('div', { className: 'fchips wstyle', role: 'group', 'aria-label': T('home.style') },
      ...c.wstyles[k].map((v) => el('button', { type: 'button', className: styleOf(k) === v ? 'on' : '', 'aria-pressed': String(styleOf(k) === v), textContent: HOME_STYLE_WORDS[v] ? T(HOME_STYLE_WORDS[v]) : v, onclick: () => wstyle(k, v) })));
    const gear = (k) => tool('🛠️', T('home.wset'), () => homeWidgetDlg(k, c, HOME_WIDGET_WORDS[k] ? T(HOME_WIDGET_WORDS[k]) : qrs[k] ? homeQrName(qrs[k]) : k, send));
    wList.replaceChildren(...(w || []).map((k, i) => el('div', { className: 'item' },
      el('div', { className: 'name' }, el('span', { textContent: HOME_WIDGET_WORDS[k] ? T(HOME_WIDGET_WORDS[k]) : qrs[k] ? homeQrName(qrs[k]) : T('home.w_newer', { k }) }), ...(waits(k, i) ? [el('small', { textContent: waits(k, i) })] : [])),
      el('div', { className: 'acts' },
        ...(c.wschema ? [gear(k)] : sized ? [sizeChips(k)] : []),
        tool('↑', T('home.up'), () => wmove(i, i - 1), i === 0),
        tool('↓', T('home.down'), () => wmove(i, i + 1), i === w.length - 1),
        tool('✕', T('home.w_off'), () => send({ widgets: w.filter((x) => x !== k) }))),
      ...(!c.wschema && c.wstyles && c.wstyles[k] ? [styleChips(k)] : []))));
    wList.hidden = !w || !w.length;
    packHead.hidden = packBox.hidden = packNote.hidden = !sized || !w || !(w.length || c.note);
    if (packed) packBox.replaceChildren(packGlyph(packed, bigs));
    layHead.hidden = lay.hidden = sized || c.wlayout === undefined || !w;      // a TV Home older than the layouts; one with sizes has no presets
    layNote.hidden = layHead.hidden || !places;
    put(layNote, 'textContent', T('home.layout_note', { n: places }));
    lay.replaceChildren(...Object.keys(HOME_LAYOUTS).filter((k) => (c.wlayouts || HOME_OLD_LAYOUTS).includes(k)).map((k) => el('button', { type: 'button', className: c.wlayout === k ? 'on' : '', onclick: () => send({ wlayout: k }) },
      layGlyph(k), el('span', { textContent: T(HOME_LAYOUTS[k]) }))));
    webHead.hidden = webLine.hidden = !(c.wrotate && c.wweb && c.wurl);   // a TV Home older than the web page; none set yet: its tile asks
    const onWeb = c.wall === 'web' && c.wmode !== undefined, snap = c.wmode === 'snap';   // a TV Home older than the modes has none
    webModeHead.hidden = webMode.hidden = webModeNote.hidden = !onWeb;
    webEveryHead.hidden = webEvery.hidden = webKeep.hidden = !(onWeb && snap);
    webMode.replaceChildren(...Object.keys(HOME_WEBMODE).map((m) => el('button', { type: 'button', className: c.wmode === m ? 'on' : '', 'aria-pressed': String(c.wmode === m), textContent: T(HOME_WEBMODE[m]), onclick: () => send({ wmode: m }) })));
    webEvery.replaceChildren(...[1, 5, 15, 30, 60].map((n) => el('button', { type: 'button', className: c.wsnap === n ? 'on' : '', 'aria-pressed': String(c.wsnap === n), textContent: T('home.web_every_n', { n }), onclick: () => send({ wsnap: n }) })));   // HomeConfig.WSNAPS
    webKeepBox.checked = !!c.wkeep;
    webModeNote.textContent = T(!snap ? 'home.web_mode_note_live' : c.wkeep ? 'home.web_mode_note_keep' : 'home.web_mode_note_snap');
    put(webNow, 'textContent', c.wurl || '');
    placeHead.hidden = placeLine.hidden = c.place === undefined || !has('weather');   // a TV Home older than the weather; and only while the widget is on
    const feeds = c.feeds || [];
    feedHead.hidden = feedLine.hidden = feedNote.hidden = !c.infoOk || !has('news');   // a TV Home older than the News widget; and only while the widget is on
    feedList.hidden = feedHead.hidden || !feeds.length;
    feedAdd.disabled = feeds.length >= 5; feedAdd.title = feeds.length >= 5 ? T('home.feeds_full') : '';
    feedList.replaceChildren(...feeds.map((u) => el('div', { className: 'item' }, el('div', { className: 'name' }, el('span', { textContent: u })),
      el('div', { className: 'acts' }, tool('✕', T('home.remove'), () => send({ feeds: feeds.filter((x) => x !== u) }))))));
    if (document.activeElement !== place) place.value = c.place || '';
    placeAuto.disabled = !c.place;
    const qrKeys = HOME_QR_KEYS.filter((k) => qrs[k]);
    qrHead.hidden = qrNote.hidden = !c.qrOk;                          // a TV Home older than the QR widgets
    qrList.hidden = !c.qrOk || !qrKeys.length;
    qrAdd.disabled = qrKeys.length >= HOME_QR_KEYS.length;
    qrList.replaceChildren(...qrKeys.map((k) => el('div', { className: 'item' },
      el('div', { className: 'name' }, el('span', { textContent: homeQrName(qrs[k]) }), ...(has(k) ? [] : [el('small', { textContent: T('home.qr_hidden_now') })])),
      el('div', { className: 'acts' },
        tool('✎', T('home.qr_edit'), async () => { const card = await homeQrAsk(qrs[k]); if (card) send({ qrs: { ...qrs, [k]: card } }); }),
        el('button', { type: 'button', className: 'small', textContent: T(has(k) ? 'home.qr_hide' : 'home.qr_show'), onclick: () => send({ widgets: has(k) ? w.filter((x) => x !== k) : [...w, k] }) }),
        tool('✕', T('home.remove'), () => { const n = { ...qrs }; delete n[k]; send({ qrs: n, widgets: w.filter((x) => x !== k) }); })))));
    noteHead.hidden = noteLine.hidden = c.note === undefined;     // a TV Home older than the note
    if (document.activeElement !== note) note.value = c.note || '';
    unpin.disabled = !c.note;
    sums(c);
  }

  // A TV Home that rotates (config.wrotate): the scenes drawn here by the same recipe the TV uses, the colours, Bing's picture
  // and the person's photos, each a tile. Rotation off: one is chosen. On: every tile is in or out of the set (at least one stays).
  function drawRotation(c) {
    const multi = c.wevery !== 'off', set = c.walls || [c.wall];
    const isOn = (k) => multi ? set.includes(k) : c.wall === k;
    const pick = (k) => {
      if (k === 'web') return webAsk();                               // always the box with the address (the one in use filled in, checked again): a line for it under the pictures was out of sight
      if (set.includes('web')) return send({ wall: k });                // leaving the page: the set starts again from this one
      if (!multi) return send({ wall: k });
      const n = set.includes(k) ? set.filter((x) => x !== k) : [...set, k];
      if (n.length) send({ walls: n });
    };
    const tile = (k, face, name, extra) => {
      const b = el('button', { type: 'button', className: isOn(k) ? 'on' : '', 'aria-pressed': String(isOn(k)), onclick: () => pick(k) }, face, el('span', { textContent: name }), ...(multi && c.wall === k ? [el('span', { className: 'nowtag', textContent: T('home.wall_now') })] : []));   // "showing now": filled, unlike the lit tile (in the set)
      return extra ? el('span', { className: 'wallcell' }, b, extra) : b;
    };
    const art = HOME_ART_IDS.map((k) => {
      const cv = el('canvas', { width: 128, height: 72 }); homeArtPaint(cv.getContext('2d'), 128, 72, k);
      return tile(k, el('i', {}, cv), T(HOME_WALL_WORDS[k]));
    });
    const plain = Object.keys(HOME_WALLS).map((k) => {
      const dot = el('i'); dot.style.setProperty('--c', 'linear-gradient(135deg,' + HOME_WALLS[k] + ')');
      return tile(k, dot, T(HOME_WALL_WORDS[k]));
    });
    if (c.ownOk) { const dot = el('i'); dot.style.setProperty('--c', homeOwnCss(c)); plain.unshift(tile('own', dot, T('home.wall_own'))); }   // first: the one the person made
    const bing = tile('bing', el('i', { className: 'add', textContent: '🌐' }), T('home.wall_bing'));
    const webTile = c.wweb ? [tile('web', el('i', { className: 'add', textContent: '🖥️' }), T('home.wall_web'))] : [];
    const photos = (r.photos || []).map((p, i) => {
      const t = homeThumbs[p.id + ':' + p.v], face = el('i', {}, t ? el('img', { src: 'data:image/jpeg;base64,' + t, alt: '' }) : '🖼️');
      const del = el('button', { type: 'button', className: 'small iconbtn wallx', textContent: '✕', title: themeUses(c, p.id) ? T('home.v_in_use') : T('home.photo_remove'), 'aria-label': T('home.photo_remove'), disabled: themeUses(c, p.id), onclick: () => dropPhoto(p.id) });
      const cell = tile(p.id, face, T('home.wall_photo_n', { n: i + 1 }), del);
      if (window.ONTV) { del.hidden = true; holdDrop(cell.firstChild, p.id); }
      return cell;
    });
    const videos = ((r.video || {}).items || []).map((v) => {
      const t = homeThumbs[v.id + ':' + v.added], face = el('i', {}, t ? el('img', { src: 'data:image/jpeg;base64,' + t, alt: '' }) : '🎬');
      const used = isOn(v.id) || c.wall === v.id || set.includes(v.id) || themeUses(c, v.id);         // TV Home refuses to delete one in use; the button says so before
      const del = el('button', { type: 'button', className: 'small iconbtn wallx', textContent: '✕', title: used ? T('home.v_in_use') : T('home.v_remove'), 'aria-label': T('home.v_remove'), disabled: used, onclick: () => dropPhoto(v.id) });
      const cell = tile(v.id, face, v.name || T('home.wall_video'), del);
      if (window.ONTV) { del.hidden = true; if (!used) holdDrop(cell.firstChild, v.id); }
      return cell;
    });
    holdNote.hidden = !(window.ONTV && (photos.length || videos.length));
    wOnline.replaceChildren(bing, ...webTile); wPhotos.replaceChildren(...photos); wVideos.replaceChildren(...videos);   // as on the TV (HomeConfig.WALLS)
    wPhotos.hidden = !photos.length; wVideos.hidden = !videos.length;
    wScenes.replaceChildren(...art); walls.replaceChildren(...plain);
    every.replaceChildren(...Object.keys(HOME_EVERY).map((e) => el('button', { type: 'button', className: c.wevery === e ? 'on' : '', 'aria-pressed': String(c.wevery === e), textContent: T(HOME_EVERY[e]), onclick: () => send({ wevery: e }) })));
    const shownName = (() => { const t = [...wOnline.querySelectorAll('.nowtag'), ...wPhotos.querySelectorAll('.nowtag'), ...wVideos.querySelectorAll('.nowtag'), ...wScenes.querySelectorAll('.nowtag'), ...walls.querySelectorAll('.nowtag')][0]; return t ? t.previousSibling.textContent : ''; })();
    put(everyNote, 'textContent', multi ? T('home.rot_state', { n: set.length, every: T(HOME_EVERY[c.wevery]).toLowerCase(), name: shownName }) + ' ' + T(set.length < 2 && set[0] !== 'bing' ? 'home.rotate_pick' : 'home.rotate_set') : '');   // Bing alone turns through its last 8 days
    everyNote.hidden = !multi;
  }

  // TV Home's own page (home.html) has nothing else to show: the settings are the page itself, not a box over an empty one.
  // Seven groups as on the TV (Customize.kt): a row each with what is set now under its name, a page of controls behind it. All
  // at once was a page of forty controls nobody found anything in. On TV Home's own page the phone's Back goes back to the list.
  // Themes (HomeConfig "theme"; ready-made ones and the person's, kept on the TV): one tap switches the background and its set, the veil, the widgets,
  // the grid, the focus, the motion and the clock at once. A saved one keeps its photo / video (the TV refuses to delete one a theme shows).
  const themeReady = el('div', { className: 'fchips homewalls themes' }), themeReadyHead = el('h3', { textContent: T('home.theme_ready') }), themeMineHead = el('h3', { textContent: T('home.theme_mine') }), themeCommHead = el('h3', { textContent: T('home.theme_community') }), themeComm = el('div', { className: 'fchips homewalls themes' }), themeMine = el('div', { className: 'fchips homewalls themes' });
  const themeName = el('input', { type: 'text', maxLength: 24, placeholder: T('home.theme_name_ph'), 'aria-label': T('home.theme_name') });
  const themeSave = el('button', { type: 'button', className: 'primary', textContent: T('home.theme_save_look'), onclick: () => { const n = themeName.value.trim(); if (n) { send({ theme: { save: n } }); themeName.value = ''; } } });
  themeName.addEventListener('keydown', (e) => { if (e.key === 'Enter') themeSave.click(); });
  const themeNote = el('p', { className: 'note', textContent: T('home.theme_note') }), themeHint = el('p', { className: 'note', textContent: T('home.theme_hint') });
  // import: the file's settings go to TV Home as ordinary settings with {theme:{save}} in the same send (HomeConfig.merge reads the keys first), so the TV needs no update
  const themeFile = el('input', { type: 'file', accept: '.json,application/json', hidden: true });
  const themeImport = el('button', { type: 'button', textContent: T('home.theme_import'), onclick: () => themeFile.click() });
  // a long wait (fetching and sending the file's photos and videos) says where it is and can be stopped
  const themeJob = el('p', { className: 'state', role: 'status', hidden: true }), themeStop = el('button', { type: 'button', className: 'small', textContent: T('common.cancel'), hidden: true });
  let themeAbort = false;
  themeStop.onclick = () => { themeAbort = true; themeStop.disabled = true; };
  const mediaSay = (s) => put(themeJob, 'textContent', T(s.kind === 'video' ? 'home.theme_media_up_v' : 'home.theme_media_up_p', { i: s.i, n: s.n }));
  const mediaBlob = async (it) => {                                  // a data: URL decoded, or the address fetched here (its host is on the backgrounds' list)
    const x = await fetch(it.value);
    if (!x.ok) throw new Error('home:media_fetch');
    const len = +x.headers.get('content-length') || 0;
    if (len > HOME_MEDIA_MAX) throw new Error('home:media_big');
    return x.blob();
  };
  const mediaJpeg = async (blob) => {                                // a png / webp in a file: the TV's size, as a photo from the phone would be
    const img = await photoOpen(blob), [W, H] = homeShape();
    return photoBlob(img, W, H, { z: 1, cx: 0.5, cy: 0.5 }, 0.85);
  };
  themeFile.addEventListener('change', async () => {
    const f = themeFile.files[0]; themeFile.value = '';
    if (!f) return;
    if (f.size > HOME_MEDIA_FILE_MAX) { toast(T('home.theme_import_big')); return; }
    let d = null;
    try { d = JSON.parse(await f.text()); } catch (err) { d = null; }
    const name = d && typeof d.name === 'string' ? d.name.replace(/\s+/g, ' ').trim().slice(0, 24) : '';
    if (!d || d.tvtheme !== 1 || !name || !d.conf || typeof d.conf !== 'object' || Array.isArray(d.conf)) { toast(T('home.theme_import_bad')); return; }
    if (f.size > HOME_THEME_FILE_MAX && !d.media) { toast(T('home.theme_import_big')); return; }
    const mine = r.config.themes || [];
    if (mine.length >= HOME_THEMES_MAX && !mine.some((t) => t.name.toLowerCase() === name.toLowerCase())) { toast(T('home.theme_full')); return; }
    const conf = {};
    for (const k of HOME_THEME_KEYS) if (k in d.conf) conf[k] = d.conf[k];
    // its own photos and videos (media): checked against the room TV Home has, then uploaded one by one, and the keys in the settings follow
    const vid = r.video;
    const room = { photos: r.config.wrotate ? HOME_PHOTO_MAX - (r.photos || []).length : 1, videos: vid ? (vid.libmax || 12) - (vid.items || []).length : 0, videoMax: vid ? vid.max : 0 };
    const plan = homeMediaPlan(conf, d.media, room);
    if (plan.error) { toast(T(plan.error.id, plan.error.args)); return; }
    let keys = {};
    if (plan.items.length) {
      themeAbort = false; themeStop.disabled = false; themeStop.hidden = themeJob.hidden = false; themeImport.disabled = true;
      try {
        keys = await homeMediaImport(plan.items, {
          blobOf: mediaBlob, toJpeg: mediaJpeg, say: mediaSay, cancelled: () => themeAbort, read: () => homeLink.read(false, true), drop: (id) => homeLink.drop(id),
          photo: (blob) => homeLink.photo(blob),
          video: async (blob, nm) => {
            const n = await homeLink.video(new File([blob], nm, { type: blob.type }), (pct) => put(themeJob, 'textContent', T('home.v_up', { pct })));
            return homeVideoWait(n, (t) => put(themeJob, 'textContent', t), true);
          },
        });
      } catch (e) {
        if (e.message !== 'cancelled') toast(T('common.error_msg', { msg: homeMediaWhy(e.message) })); else toast(T('home.theme_media_cancelled'));
        const t = await homeLink.read(true); if (t && t.installed) await after(t);
        return;
      } finally { themeStop.hidden = themeJob.hidden = true; themeImport.disabled = false; put(themeJob, 'textContent', ''); }
      const t = await homeLink.read(true); if (t && t.installed) { keepThumbs(t); r = t; homeNow = t; }
    }
    const re = homeMediaRemap(conf, keys);
    await send({ ...re.conf, theme: { save: name } });
    toast(T(re.lost ? 'home.theme_imported_wall' : 'home.theme_imported', { name }));
  });
  const themeLine = el('div', { className: 'group' }, themeName, el('div', { className: 'actions' }, themeSave, themeImport, themeFile), el('div', { className: 'line' }, themeJob, themeStop));
  const homeFaceIO = () => homeFaceIO.io || (homeFaceIO.io = new IntersectionObserver((es) => { for (const e of es) if (e.isIntersecting) { homeFaceIO.io.unobserve(e.target); const f = e.target.paintNow; e.target.paintNow = null; e.target.dataset.lazy = ''; if (f) f(); } }, { rootMargin: '300px 0px' }));
  // a theme's preview (homeThemePaint): a small canvas of its own home screen, the background picture TV Home kept for it under it; made once per look
  const themeFace = (id, conf, mine, look, tiles) => {
    const shot = homeShots[mine ? 'mine' : 'ready'][id], p = !shot && (r.photos || []).find((x) => x.id === conf.wall), thumb = shot || (p && homeThumbs[p.id + ':' + p.v]) || '';
    const key = [id, mine, JSON.stringify(conf), tiles.join(), look || '', thumb.length].join('|');
    let cv = homeFaces.get(key);
    if (!cv) {
      cv = el('canvas', { width: 320, height: 180 });
      homeFaces.set(key, cv);
      if (homeFaces.size > 80) homeFaces.delete(homeFaces.keys().next().value);
      // painted (and its picture decoded) only once it is near the screen: a list of 40 themes costs the few in view; without
      // IntersectionObserver at once. Kept in homeFaces, so a redraw of the list reuses the painted one.
      const paint = () => { homeThemePaint(cv, conf, tiles, null, look);
        if (thumb) { const img = new Image(); img.decoding = 'async'; img.onload = () => homeThemePaint(cv, conf, tiles, img, look); img.src = 'data:image/jpeg;base64,' + thumb; } };
      if ('IntersectionObserver' in window) { cv.dataset.lazy = '1'; homeFaceIO().observe(cv); cv.paintNow = paint; } else paint();
    }
    return el('i', {}, cv);
  };
  const themeUses = (c, id) => [...(c.themes || []), ...(c.before ? [{ conf: c.before }] : [])].some((t) => t.conf.wall === id || (t.conf.walls || []).includes(id));   // HomeConfig.themeUses
  // what a theme sets, read only: name: value rows in the details dialog (the TV shows the same on a long press). Under the tiles it
  // opened below the fold, a long way from the ⓘ: nothing seemed to happen (phone, 2026-10-25)
  const themeWall = (k) => HOME_WALL_WORDS[k] ? T(HOME_WALL_WORDS[k]) : k === 'bing' ? T('home.wall_bing') : k === 'web' ? T('home.wall_web') : /^vid:/.test(k) ? (((r.video || {}).items || []).find((v) => v.id === k) || {}).name || T('home.wall_video')
    : k === 'photo' ? T('home.wall_photo') : /^photo\d+$/.test(k) ? T('home.wall_photo_n', { n: +k.slice(5) }) : k;
  const themeRows = (conf) => {
    const on = (v) => T(v ? 'home.td_on' : 'home.td_off'), none = T('home.td_none');
    const set = conf.walls || [], size = conf.wsize || {}, sty = conf.wstyle || {}, looks = (k) => Object.values((conf.wopt || {})[k] || {}).filter((v) => /^[a-z]+$/.test(v)).map((v) => { const w = HOME_OPT_WORDS.find((x) => x === 'home.ov_' + v); return w ? T(w) : v; });   // a theme keeps the options that are not the default
    const cards = (conf.widgets || []).map((k) => (HOME_WIDGET_WORDS[k] ? T(HOME_WIDGET_WORDS[k]) : k) + ' (' + [T(size[k] === 'big' ? 'home.size_big' : 'home.size_small'), ...(sty[k] ? [HOME_STYLE_WORDS[sty[k]] ? T(HOME_STYLE_WORDS[sty[k]]) : sty[k]] : looks(k))].join(', ') + ')');
    return [
      [T('home.wall'), themeWall(conf.wall) + (set.length > 1 ? ' · ' + T('home.td_set', { n: set.length, every: T(HOME_EVERY[conf.wevery] || HOME_EVERY.off).toLowerCase() }) : '')],
      [T('home.shade'), (conf.shade ?? 50) + '%'],
      [T('home.widgets'), cards.length ? cards.join(', ') : none],
      [T('home.cols'), String(conf.cols || 5)], [T('home.rows'), conf.rows >= 1 ? String(conf.rows) : T('home.rows_auto')],
      [T('home.focus'), T(conf.focus === 'ring' ? 'home.focus_ring' : 'home.focus_glass')],
      [T('home.anim'), on(conf.anim !== false)],
      [T('home.motion'), T(conf.motion === 'calm' ? 'home.motion_calm' : conf.motion === 'full' ? 'home.motion_full' : 'home.motion_auto')],
      [T('home.clock24'), on(conf.clock24 !== false)], [T('home.greet'), on(conf.greet !== false)],
    ];
  };
  function drawThemes(c) {
    const showInfo = (id, name, conf) => {
      put('#infotitle', 'textContent', 'ⓘ ' + name);
      $('#infobody').replaceChildren(el('div', { className: 'list' }, ...themeRows(conf).map(([k, v]) => el('div', { className: 'item' }, el('div', { className: 'name' }, el('span', { textContent: k }), el('small', { textContent: v }))))));
      const mineTheme = (c.themes || []).some((t) => t.id === id);
      if (id !== 'before') {
        // its own photos and videos can go in the file (home_media.js); on by default, the size shown, off = the settings only
        const wt = homeMediaWeigh(conf, r), has = wt.photos + wt.videos > 0;
        const withBox = el('input', { type: 'checkbox', checked: true });
        const what = [wt.photos ? T('home.theme_media_n_photos', { n: wt.photos }) : '', wt.videos ? T('home.theme_media_n_videos', { n: wt.videos }) : ''].filter(Boolean).join(', ');
        const sizeWords = wt.bytes ? (wt.exact ? '' : '≥ ') + homeMediaMb(wt.bytes) : '';
        const state = el('p', { className: 'state', role: 'status', hidden: true }), stop = el('button', { type: 'button', className: 'small', textContent: T('common.cancel'), hidden: true });
        let abort = false; stop.onclick = () => { abort = true; stop.disabled = true; };
        const exp = el('button', { type: 'button', className: 'small', textContent: T('home.theme_export') });
        exp.onclick = async () => {
          if (has && withBox.checked && wt.over) { toast(T('home.theme_media_over', { max: HOME_MEDIA_MAX / 1048576 })); return; }
          abort = false; stop.disabled = false; stop.hidden = state.hidden = !(has && withBox.checked && homeLink.direct); exp.disabled = true;
          try { await exportTheme(id, name, conf, has && withBox.checked, (s) => put(state, 'textContent', T('home.theme_media_dl', s)), () => abort); }
          catch (e) { toast(e.message === 'cancelled' ? T('home.theme_media_cancelled') : T('common.error_msg', { msg: homeMediaWhy(e.message) })); }
          finally { stop.hidden = state.hidden = true; exp.disabled = false; }
        };
        $('#infobody').append(...[has ? el('label', { className: 'check' }, withBox, el('span', { textContent: T('home.theme_media_inc', { what, size: sizeWords ? ' · ' + sizeWords : '' }) })) : null,
          el('div', { className: 'actions' }, exp, mineTheme && homeLink.canShare ? el('button', { type: 'button', className: 'small', textContent: T('home.share'), onclick: () => shareForm(id, name) }) : null, stop), state]);   // the TV itself cannot take its own screenshot: only from a client
      }
      if (!$('#infodlg').open) $('#infodlg').showModal();
    };
    // a download from the server (it names the file, as the scenes backup; works in the phone app's web view); on TV Home's own page
    // there is no such route, so the page makes the file itself
    const saveAs = (href, nm = '') => { const a = el('a', { href, download: nm }); document.body.append(a); a.click(); a.remove(); };
    const exportTheme = async (id, name, conf, withMedia, say, cancelled) => {
      if (homeLink.themeFileUrl && !homeLink.direct) return saveAs(homeLink.themeFileUrl(id, name, withMedia));   // the host streams the file (core/ontv/ThemeFile.kt)
      const file = name.replace(/[\\/:*?"<>|]/g, '_') + '.tvtheme.json';
      let media = {};
      if (withMedia) {
        const sizes = {}; for (const p of r.photos || []) sizes[p.id] = p.size || 0; for (const v of (r.video || {}).items || []) sizes[v.id] = v.size || 0;
        media = await homeMediaExport(conf, sizes, {
          cancelled, say,
          blobOf: async (k) => { const x = await fetch(homeLink.mediaUrl(k)); if (!x.ok) throw new Error('home:media_missing'); return x.blob(); },
          dataUrl: (b) => new Promise((ok, no) => { const fr = new FileReader(); fr.onload = () => ok(fr.result); fr.onerror = () => no(new Error('read')); fr.readAsDataURL(b); }),
        });
      }
      const url = URL.createObjectURL(new Blob(homeMediaFile(name, conf, media), { type: 'application/json' }));
      saveAs(url, file); setTimeout(() => URL.revokeObjectURL(url), 60000);
    };
    // share to the community store: a form (the display name is kept on this page), then the job on the server side (api/home/theme/share) is asked
    // every second: its step in words, Cancel while it runs, the end in words a person can act on
    const shareBy = () => { try { return localStorage.getItem('shareBy') || ''; } catch (e) { return ''; } };
    const shareErr = (code) => ({ by: T('home.share_err_by'), home_missing: T('home.share_err_home_missing'), home_old: T('home.share_err_home_old'), theme_missing: T('home.share_err_theme_missing'),
      video: T('home.share_err_video'), not_home: T('home.share_err_not_home'), shot: T('home.share_err_shot'), photo_missing: T('home.share_err_photo_missing'), photo_big: T('home.share_err_photo_big'),
      too_big: T('home.share_err_too_big'), network: T('home.share_err_network'), limit: T('home.share_err_limit'), queue: T('home.share_err_queue'), rejected: T('home.share_err_rejected'),
      server: T('home.share_err_server') }[code] || T('home.share_err_tv'));
    const shareStep = (s) => ({ apply: T('home.share_step_apply'), shot: T('home.share_step_shot'), photo: T('home.share_step_photo'), send: T('home.share_step_send') }[s] || '');
    const shareForm = (id, name) => {
      const by = el('input', { type: 'text', maxLength: 24, value: shareBy(), placeholder: T('home.share_by_ph'), 'aria-label': T('home.share_by') });
      const status = el('p', { className: 'state' }), fail = el('p', { className: 'warnline', hidden: true });
      const send = el('button', { type: 'button', className: 'primary', textContent: T('home.share_send') });
      const cancel = el('button', { type: 'button', textContent: T('common.cancel') });
      const box = el('div', { className: 'group' }, el('h3', { textContent: T('home.share') }), el('p', { className: 'note', textContent: T('home.share_note') }), el('div', { className: 'line' }, by, send), status, fail, el('div', { className: 'actions' }, cancel));
      $('#infobody').replaceChildren(box);
      let running = false, stop = false;
      const end = (msg, bad) => { running = false; send.disabled = by.disabled = false; cancel.textContent = T('common.close'); status.textContent = bad ? '' : msg; fail.hidden = !bad; fail.textContent = bad ? msg : ''; };
      cancel.onclick = async () => { if (running) { stop = true; cancel.disabled = true; await homeLink.share({ cancel: true }); } else $('#infodlg').close(); };
      send.onclick = async () => {
        const who = by.value.replace(/\s+/g, ' ').trim();
        if (!who) { fail.hidden = false; fail.textContent = shareErr('by'); by.focus(); return; }
        try { localStorage.setItem('shareBy', who); } catch (e) { }
        running = true; stop = false; send.disabled = by.disabled = true; cancel.disabled = false; cancel.textContent = T('common.cancel'); fail.hidden = true; status.textContent = shareStep('apply');
        const r0 = await homeLink.share({ id, by: who });
        if (!r0) return end(shareErr('tv'), true);
        for (;;) {
          await new Promise((ok) => setTimeout(ok, 900));
          const j = await homeLink.share(null);
          if (!j) return end(shareErr('tv'), true);
          if (j.state === 'run') { status.textContent = shareStep(j.step); continue; }
          if (j.state === 'done') return end(T('home.share_done'), false);
          if (j.state === 'cancelled' || stop) return end(T('home.share_cancelled'), false);
          return end(shareErr(j.error), true);
        }
      };
      by.addEventListener('keydown', (e) => { if (e.key === 'Enter') send.click(); });
    };
    const tile = (id, name, conf, mine, look, tiles = [], by = '') => {
      const on = c.theme === id;
      const b = el('button', { type: 'button', className: on ? 'on' : '', 'aria-pressed': String(on), title: name, onclick: () => send({ theme: { apply: id } }) }, themeFace(id, conf, mine, look, tiles), el('span', { textContent: name }), ((t) => t.length ? el('small', { className: 'badge' }, ...t.flatMap((x) => [UI.icon(x.icon), el('span', { textContent: T(x.id, x.args) })])) : null)(homeMediaBadges(conf || {})), by ? el('small', { className: 'by', textContent: T('home.theme_by', { name: by }) }) : null);
      const info = el('button', { type: 'button', className: 'small iconbtn wallx info', textContent: 'ⓘ', title: T('home.theme_info'), 'aria-label': T('home.theme_info'), onclick: () => showInfo(id, name, conf) });
      if (!mine) return el('span', { className: 'wallcell' }, b, info);
      const del = el('button', { type: 'button', className: 'small iconbtn wallx', textContent: '✕', title: T('home.theme_del'), 'aria-label': T('home.theme_del'),
        onclick: async () => { if (await ask(T('home.theme_del_q'), { ok: T('home.theme_del'), danger: true })) send({ theme: { del: id } }); } });
      return el('span', { className: 'wallcell' }, b, info, del);
    };
    const built = c.themesBuiltin || [];
    themeReady.replaceChildren(...built.filter((t) => !t.community).map((t) => tile(t.id, homeThemeName(t), t.conf, false, t.look, t.tiles)));
    themeComm.replaceChildren(...built.filter((t) => t.community).map((t) => tile(t.id, homeThemeName(t), t.conf, false, t.look, t.tiles, t.by || '')));   // the store's approved themes, with the sender's name
    themeCommHead.hidden = themeComm.hidden = !themeComm.children.length;
    // the look that was on before the last switch (TV Home keeps it when it matched no saved theme), then the person's own
    const own = [...(c.before ? [{ id: 'before', name: T('home.theme_before'), conf: c.before }] : []), ...(c.themes || [])];
    themeMine.replaceChildren(...own.map((t) => tile(t.id, t.name, t.conf, true)));
    themeMineHead.hidden = themeMine.hidden = !own.length;
    themeSave.disabled = (c.themes || []).length >= HOME_THEMES_MAX;
    put(themeNote, 'textContent', T(themeSave.disabled ? 'home.theme_full' : 'home.theme_note'));
  }

  const page = homeLink.direct;
  // one block of a page (.hsect): its head, if any, is a small-capitals title; a block with nothing shown hides itself (CSS :has)
  const sect = (head, ...kids) => el('div', { className: 'hsect' }, ...(head ? [typeof head === 'string' ? el('h3', { className: 'secthead', textContent: T(head) }) : head] : []), ...kids);
  const check = (box, words) => el('label', { className: 'check' }, box, el('span', { textContent: T(words) }));
  const vFold = UI.card(T('home.v_head'), { fold: true }, vfpsHead, vfps, vresHead, vres);   // the video's own settings, in the videos' part
  const GROUPS = [
    ['themes', 'star', 45, 'home.themes', [themeHint, themeMineHead, themeMine, themeLine, themeNote, themeReadyHead, themeReady, themeCommHead, themeComm]],
    ['bg', 'image', 330, 'home.wall', [everyHead, every, everyNote, shadeHead, shade, shadeNote, fitHead, fit, fitNote,
      hOnline, wOnline, sugHead, sugBox, sugNote, webHead, webLine, webModeHead, webMode, webEveryHead, webEvery, webKeep, webModeNote,
      hPhotos, photoAct, wPhotos, holdNote, galHead, gal, credit,
      hVideos, videoAct, vstate, vperf, wVideos, vFold, vnote, file, vfile,
      hScenes, wScenes, hColours, walls, ownHead, ownStops, ownDirHead, ownDir, ownNote]],
    ['look', 'sparkle', 180, 'home.g_look', [sect('home.sec_show', focusHead, focus, motionHead, motion, check(anim, 'home.anim'), check(clock, 'home.clock24')),
      sect('home.sec_head', check(greet, 'home.greet'), gstyleHead, gstyle, gnameLine, gtextLine, gtokens, gtype, hsplit, dstyleHead, dstyle, dtextLine, dtokens, dtype),
      sect(showHead, check(shows.showClock, 'home.show_clock'), check(shows.showDate, 'home.show_date'), check(shows.showNet, 'home.show_net'), check(shows.showGear, 'home.show_gear'),
        check(shows.showAdd, 'home.show_add'), check(shows.showSuggest, 'home.show_suggest'), check(shows.showWatch, 'home.show_watch'), check(infold, 'home.show_infold'), check(shift, 'home.show_shift'), watchAtHead, watchAt, watchSortHead, watchSort),
      sect('home.sec_grid', colsHead, cols, rowsHead, rowsLine, rowsNote, orderHead, orderBox, foldHead, foldNote, foldBox),
      sect(null, tilesHead, tilesNote, addHome, tilesList),
      sect(null, el('div', { className: 'card-head' }, el('h3', { textContent: T('home.hidden') }), addHidden), hidden),
      sect(null, namesHead, names)]],
    ['widgets', 'chart', 210, 'home.g_widgets', [sect(null, wHead, wList, ...wLines), sect(null, packHead, packBox, packNote), sect(null, layHead, lay, layNote, placeHead, placeLine),
      sect(null, feedHead, feedLine, feedList, feedNote), sect(null, noteHead, noteLine), sect(null, qrHead, qrList, qrNote)]],
    ['system', 'power', 260, 'home.g_system', [sect(null, check(dream, 'home.dream'), dfaceHead, dface)]],
    ['keys', 'keyboard', 230, 'home.keys', [keysOld, check(keyOn, 'home.keys_on'), keysNote, keysList, autoNote, autoAllBox, autoAllNote]],
    ['lang', 'globe', 200, 'home.lang', [langs]],
  ];
  keysHead.hidden = langHead.hidden = true;                           // the page's own title says it
  const title = el('h2', { textContent: T('home.title') });
  const backBtn = el('button', { type: 'button', className: 'small', textContent: T('common.back'), hidden: true, onclick: () => page ? history.back() : go(null) });
  const subs = {}, rows = {}, pages = {};
  const menu = el('div', { className: 'homemenu' }, ...GROUPS.map(([k, icon, hue, words]) => {
    subs[k] = el('small');
    return (rows[k] = el('button', { type: 'button', className: 'mitem', onclick: () => go(k) },
      el('span', { className: 'mi' }, UI.icon(icon, hue)), el('span', { className: 'mt' }, el('b', { textContent: T(words) }), subs[k]), el('span', { className: 'mgo', textContent: '›' })));
  }));
  const body = el('div', { className: 'homepages' }, menu, ...GROUPS.map(([k, , , , kids]) => (pages[k] = el('div', { className: 'homepage', hidden: true }, ...kids))));
  let at = null;
  function go(k, push = true) {
    const from = at; at = k;
    menu.hidden = !!k; prev.hidden = ['keys', 'lang', 'system'].includes(k);   // nothing there the home screen shows
    for (const [g, v] of Object.entries(pages)) v.hidden = g !== k;
    put(title, 'textContent', k ? T(GROUPS.find((g) => g[0] === k)[3]) : T('home.title'));
    backBtn.hidden = !k;
    if (page && push && k) history.pushState({ homeGroup: k }, '');
    (page ? document.scrollingElement : d).scrollTop = 0;
    const first = k ? pages[k].querySelector('button:not([hidden]):not(:disabled), input:not(:disabled)') : rows[from];
    if (first && (window.ONTV || !k)) first.focus({ preventScroll: true });   // back on the list: on the row it came from; a phone gets no keyboard popping up
  }
  const onPop = () => { if (at) go(null, false); };
  if (page) addEventListener('popstate', onPop);
  const onOff = (v) => T(v ? 'home.sum_on' : 'home.sum_off');
  const wallWord = (c) => c.wall === 'web' ? T('home.wall_web') : /^photo\d*$/.test(c.wall) ? T('home.wall_photo_n', { n: (+c.wall.slice(5) || 1) })
    : /^vid:/.test(c.wall) ? (((r.video || {}).items || []).find((v) => v.id === c.wall) || {}).name || T('home.wall_video')
    : c.wall === 'bing' ? T('home.wall_bing') : HOME_WALL_WORDS[c.wall] ? T(HOME_WALL_WORDS[c.wall]) : c.wall;
  // the line under each group's name, as the TV's Customize writes it (summary())
  function sums(c) {
    rows.themes.hidden = !c.themesOk;                                // a TV Home older than the themes
    put(subs.themes, 'textContent', !c.themesOk ? '' : !c.theme ? T('home.theme_custom') : ((c.themes || []).find((t) => t.id === c.theme) || {}).name || homeThemeName((c.themesBuiltin || []).find((t) => t.id === c.theme) || { id: c.theme }));
    const set = c.wrotate && c.wevery && c.wevery !== 'off' && c.wall !== 'web';
    put(subs.bg, 'textContent', [set ? T('home.sum_bg_set', { n: (c.walls || []).length }) : wallWord(c), c.shadeOk ? T('home.sum_shade', { n: c.shade ?? 50 }) : ''].filter(Boolean).join(' · '));
    put(subs.look, 'textContent', [c.focus !== undefined ? T(c.focus === 'ring' ? 'home.focus_ring' : 'home.focus_glass') : '',
      c.colsOk ? T('home.sum_cols', { n: c.cols || 5 }) : '', c.rowsOk ? (c.rows >= 1 ? T('home.sum_rows', { n: c.rows }) : T('home.sum_rows_auto')) : '',
      c.hidden.length ? T('home.sum_hidden', { n: c.hidden.length }) : ''].filter(Boolean).join(' · '));
    put(subs.widgets, 'textContent', c.widgets ? T('home.sum_widgets', { n: c.widgets.length }) : '');
    put(subs.system, 'textContent', T('home.sum_system', { dream: onOff(!!c.dream) }));
    put(subs.keys, 'textContent', T('home.sum_keys', { keys: onOff(!!c.keyon), auto: onOff(!!c.autoon) }));
    put(subs.lang, 'textContent', LANGS[c.lang] || T('home.lang_tv'));
    rows.lang.hidden = c.lang === undefined;                        // a TV Home older than the choice
    rows.widgets.hidden = !c.widgets;
  }
  const d = el('dialog', { className: 'ask homeask' + (page ? ' page' : '') }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, title, el('div', { className: 'tools' }, backBtn,
      page ? null : el('button', { type: 'button', className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() }))),
    prev, body));
  d.addEventListener('cancel', (e) => { if (at && !page) { e.preventDefault(); go(null); } });   // Esc, or the TV's Back: to the list first
  const prevTick = setInterval(() => { if (!prev.hidden) drawPrev(r.config); }, 60000);   // its clock goes on with the TV's
  d.addEventListener('close', () => { clearInterval(prevTick); d.remove(); if (page) { put('main', 'hidden', false); removeEventListener('popstate', onPop); } homeCard(); });
  draw();
  document.body.append(d);
  if (page) { put('main', 'hidden', true); d.show(); } else d.showModal();
  if (!window.ONTV) document.activeElement?.blur();                   // opening focuses the first row: ringed, it read as picked; the D-pad needs it
}

// The web page for the background, in a box of its own: the address, then the TV (or, on a host's page, this device) tries it
// (WebCheck.kt) and says what it found, the page's title when it opened. Use is pressed once: it tries first, and a page that
// failed asks to be used anyway once the reason shows (a page that needs the TV's own login, a host not on the TV's Wi-Fi).
// The address, or null.
const HOME_WEB_WHY = { bad: 'home.web_why_bad', dns: 'home.web_why_dns', refused: 'home.web_why_refused', timeout: 'home.web_why_timeout', ssl: 'home.web_why_ssl',
  cleartext: 'home.web_why_cleartext', status: 'home.web_why_status', loop: 'home.web_why_loop', type: 'home.web_why_type', net: 'home.web_why_net' };   // WebCheck.kt's "why"
function homeWebAsk(now) {
  return new Promise((done) => {
    let tried = '', good = false;
    const input = el('input', { type: 'url', inputMode: 'url', maxLength: 500, value: now, placeholder: T('home.web_ph'), 'aria-label': T('home.web'), autocomplete: 'off' });
    const state = el('p', { className: 'state', hidden: true }), warn = el('p', { className: 'warnline', hidden: true });
    const tryBtn = el('button', { type: 'button', textContent: T('home.web_try') });
    const use = el('button', { type: 'button', className: 'primary', textContent: T('home.web_set') });
    const cancel = el('button', { type: 'button', textContent: T('common.cancel'), onclick: () => d.close() });
    const d = el('dialog', { className: 'ask homeweb' }, el('div', { className: 'card' },
      el('h2', { textContent: T('home.web') }),
      el('p', { className: 'note', textContent: T('home.web_note') }),
      UI.line(input, tryBtn), state, warn,
      el('p', { className: 'note', textContent: T(homeLink.direct ? 'home.web_from_tv' : 'home.web_from_here') }),
      el('div', { className: 'actions' }, cancel, use)));
    let answer = null;
    const clean = () => input.value.trim();
    const shown = (ok, words) => { state.hidden = !ok; warn.hidden = ok; put(ok ? state : warn, 'textContent', words); };
    const test = async () => {
      const u = clean();
      if (!/^https?:\/\/[^\s/?#]+\S*$/i.test(u)) { shown(false, T('home.web_bad')); input.focus(); return false; }
      const back = UI.busy(tryBtn, T('home.web_trying')); use.disabled = true;
      let a;
      try { a = await homeLink.check(u); } finally { back(); use.disabled = false; }
      tried = u; good = !!(a && a.ok);
      if (!a) shown(false, T('home.web_why_net'));
      else if (a.ok) shown(true, a.title ? T('home.web_ok_title', { title: a.title }) : T('home.web_ok'));
      else shown(false, T(HOME_WEB_WHY[a.why] || 'home.web_why_net', { code: a.code || '', type: a.type || '' }));
      put(use, 'textContent', T(good ? 'home.web_set' : 'home.web_anyway'));
      return good;
    };
    tryBtn.onclick = test;
    use.onclick = async () => {
      const u = clean();
      if (u !== tried && !await test()) return;                       // failed just now: the reason shows, and the button is Use anyway
      answer = u; d.close();
    };
    input.addEventListener('input', () => { if (clean() !== tried) { state.hidden = warn.hidden = true; put(use, 'textContent', T('home.web_set')); } });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); use.click(); } });
    d.addEventListener('close', () => { d.remove(); done(answer); });
    document.body.append(d); d.showModal();
    if (!window.ONTV) input.focus();
    if (now) test();                                                  // the page in use: is it still there
  });
}

// The wallpaper gallery (docs/home/wallpapers.json, signed): small pictures to choose from; the one chosen is downloaded here,
// checked against the list and sent to the TV like a photo of the person's own. The credit shows under the pictures.
async function homeGallery(box, credit, head, done, sug) {
  const g = await homeLink.walls(true);                              // none on TV Home's own page: the hosts download the gallery
  const lang = (document.documentElement.lang || 'en').slice(0, 2);
  const links = (g && g.links) || [];
  if (sug && links.length) {
    sug.box.replaceChildren(...links.map((l) => {
      const face = l.thumb ? el('img', { src: l.thumb, alt: '', loading: 'lazy' }) : el('i', { className: 'mi', innerHTML: '<svg class="ic" aria-hidden="true"><use href="#i-globe"/></svg>' });
      const tag = l.kind === 'video' ? T('home.sug_video') : l.kind === 'page' ? T('home.sug_page') : '';
      const b = el('button', { type: 'button', title: [l.name[lang] || l.name.en, l.credit, l.license].filter(Boolean).join(' · '), onclick: () => sug.pick(l.url) },
        face, el('span', { textContent: l.name[lang] || l.name.en }), ...(tag ? [el('small', { className: 'badge', textContent: tag })] : []));
      b.dataset.url = l.url;
      return b;
    }));
    sug.head.hidden = sug.box.hidden = sug.note.hidden = false; sug.mark();
  }
  if (!g || !g.walls || !g.walls.length) { box.hidden = credit.hidden = head.hidden = true; return; }   // no list (offline): no empty heading either
  const note = (w) => T('home.credit', { name: w.name[lang] || w.name.en, credit: w.credit, license: w.license, kb: w.kb });
  box.replaceChildren(...g.walls.map((w) => {
    const pic = el('img', { src: w.thumb, alt: '', loading: 'lazy' });
    const b = el('button', { type: 'button', title: note(w), onclick: async () => {
      if (box.dataset.busy) return;
      box.dataset.busy = '1'; box.classList.add('busy'); toast(T('home.sending_photo'));
      const n = await homeLink.wall(w.id);
      delete box.dataset.busy; box.classList.remove('busy');
      if (n && n.installed) { done(n); toast(T('home.photo_done')); }
    } }, pic, el('span', { textContent: w.name[lang] || w.name.en }));
    const show = () => put(credit, 'textContent', note(w));
    b.addEventListener('pointerenter', show); b.addEventListener('focus', show);
    return b;
  }));
}

// The TV's real shape for a preview, from what TV Home reports (snapshot "display": the panel's physical size): 1920 wide, as tall as
// the panel is. Without it (an older TV Home) 16:9.
function homeShape() {
  const d = homeNow && homeNow.display;
  return d && d.w > 0 && d.h > 0 ? [1920, Math.max(1, Math.round(1920 * d.h / d.w))] : [1920, 1080];
}

// The one line about how the video plays on THIS TV (TV Home measures it for 20 s after each start: wall/Videos.kt Verdict, `video.perf`
// {id, fps, want, cpu, level: ok|choppy|slow, off}): [id, words' values] or null (nothing measured for the wall on now).
function homePerf(perf, wall) {
  if (!perf || !wall || perf.id !== wall) return null;
  const a = { fps: perf.fps, want: perf.want, cpu: perf.cpu };
  return perf.off ? ['home.v_perf_off', a] : perf.level === 'ok' ? ['home.v_perf_ok', a] : ['home.v_perf_bad', a];
}

// A video or GIF as the background: shown in the TV's frame first (the TV covers the screen with it, as this box does), then sent
// (progress by the bytes), then waited for while the TV makes it light (progress by TV Home's job). [say] writes the state line.
async function homeVideo(f, say) {
  try {
    if (!await homeVideoAsk(f)) return null;
    return await homeVideoWait(await homeLink.video(f, (pct) => say(T('home.v_up', { pct }))), say);
  } catch (e) { toast(T('common.error_msg', { msg: friendly(e.message) })); return null; }
}

// Until TV Home's job ends: the answer's video.job {pct, error}. A few failed reads in a row end the wait (the TV went away).
async function homeVideoWait(n, say, quiet = false) {   // quiet: one step of a theme's import, which says its own end
  let lost = 0;
  while (n && n.video && n.video.job && n.video.job.error == null && n.video.job.pct < 100 && lost < 5) {
    say(T('home.v_make', { pct: n.video.job.pct }));
    await new Promise((ok) => setTimeout(ok, 1500));
    const t = await homeLink.read(false, true);
    if (t) { n = t; lost = 0; } else lost++;
  }
  const j = n && n.video && n.video.job;
  if (j && j.error) throw new Error('home:video_' + j.error);
  if (!quiet) toast(T('home.v_done'));
  return n;
}

function homeVideoAsk(f) {
  return new Promise((ok) => {
    const [W, H] = homeShape(), url = URL.createObjectURL(f);
    const gif = /\.gif$/i.test(f.name) || f.type === 'image/gif';
    const m = gif ? el('img', { src: url, alt: '' }) : el('video', { src: url, loop: true, playsInline: true });
    if (!gif) { m.muted = true; m.autoplay = true; m.play().catch(() => {}); }
    const box = el('div', { className: 'vidprev' }, m); box.style.setProperty('--ar', W + ' / ' + H);
    let yes = false;
    const d = el('dialog', { className: 'ask' }, el('div', { className: 'card' },
      el('h2', { textContent: T('home.v_title') }), box, el('p', { className: 'note', textContent: T('home.v_hint') }),
      el('div', { className: 'actions' },
        el('button', { type: 'button', textContent: T('common.cancel'), onclick: () => d.close() }),
        el('button', { type: 'button', className: 'primary', textContent: '🎬 ' + T('home.crop_send'), onclick: () => { yes = true; d.close(); } }))));
    d.addEventListener('close', () => { URL.revokeObjectURL(url); d.remove(); ok(yes); });
    document.body.append(d); d.showModal();
  });
}

// A photo as the background: made the TV's size here (a phone's 12 MP photo would take a while over ADB and fill the
// TV's memory), shown in the TV's frame first so the crop can be moved (photofit.js, the same as TV Home's own phone page), then sent
async function homePhoto(f, done) {
  try {
    const img = await photoOpen(f);
    const [W, H] = homeShape();
    const c = (homeNow && homeNow.config) || {};
    const view = await homeCropAsk(img, W, H, c.fitOk ? c.wfit === 'contain' || img.height > img.width : null);
    if (!view) return false;                                          // cancelled: nothing sent, nothing said
    toast(T('home.sending_photo'));
    const blob = await photoBlob(img, W, H, view, 0.85);
    let n = await homeLink.photo(blob);
    if (view.whole && c.wfit !== 'contain') n = (await homeLink.write({ wfit: 'contain' })) || n;   // whole: shown whole (all photos: wfit is one setting)
    done(n); toast(T('home.photo_done'));
    return true;
  } catch (e) { toast(T('common.error_msg', { msg: friendly(e.message) })); return false; }
}

// A picture put to [path] (the JPEG the crop made); the answer is TV Home's settings. The X-Tvh header is what a page of another site
// cannot send without a preflight: TV Home's own page takes no cross-site post.
async function homeSend(path, blob, onPct) {
  if (onPct) return homeSendProgress(path, blob, onPct);
  const x = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream', 'X-Tvh': '1' }, body: blob });
  const n = await x.json().catch(() => ({}));
  if (!x.ok) throw new Error(n.error || x.statusText);
  return n;
}

// Same with the bytes counted (fetch cannot say how far an upload is): a video of 100 MB takes a while. The direct page's refusals
// come as bare words (size, full, space, busy, type): said as the hosts say them, so one table of words (friendly) serves both.
function homeSendProgress(path, blob, onPct) {
  return new Promise((ok, no) => {
    const x = new XMLHttpRequest();
    x.open('POST', path);
    x.setRequestHeader('Content-Type', 'application/octet-stream'); x.setRequestHeader('X-Tvh', '1');
    x.upload.onprogress = (e) => { if (e.lengthComputable) onPct(Math.min(99, Math.round(100 * e.loaded / e.total))); };
    x.onerror = () => no(new Error('network'));
    x.onload = () => {
      let n = {}; try { n = JSON.parse(x.responseText); } catch (e) { /* not JSON */ }
      if (x.status >= 200 && x.status < 300) ok(n);
      else no(new Error(/^(size|full|space|busy|type)$/.test(n.error || '') ? 'home:video_' + n.error : n.error || x.statusText));
    };
    x.send(blob);
  });
}

// The photo in the TV's shape, to move and widen before it is sent; resolves with the crop's view, or null when cancelled. [whole]
// (a TV Home with "fitOk"; null: none) starts on "whole photo" instead of the crop: the two are chips over the frame.
function homeCropAsk(img, W, H, whole) {
  return new Promise((ok) => {
    let view = null;
    const cv = el('canvas', { className: 'photocrop', width: 640, height: Math.round(640 * H / W) });
    const hint = el('p', { className: 'note' });
    const bCover = el('button', { type: 'button', onclick: () => pick(false) }, T('home.fit_cover'));
    const bWhole = el('button', { type: 'button', onclick: () => pick(true) }, T('home.fit_contain'));
    const modes = el('div', { className: 'fchips', hidden: whole == null }, bCover, bWhole);
    const d = el('dialog', { className: 'ask' }, el('div', { className: 'card' },
      el('h2', { textContent: T('home.crop_title') }), modes, cv, hint,
      el('div', { className: 'actions' },
        el('button', { type: 'button', textContent: T('common.cancel'), onclick: () => d.close() }),
        el('button', { type: 'button', className: 'primary', textContent: '🖼️ ' + T('home.crop_send'), onclick: () => { view = pv; d.close(); } }))));
    d.addEventListener('close', () => { d.remove(); ok(view); });
    document.body.append(d); d.showModal();
    const pv = photoCrop(cv, img);
    const pick = (w) => {
      pv.whole = !!w; pv.redraw();
      for (const [b, on] of [[bCover, !w], [bWhole, !!w]]) { b.className = on ? 'on' : ''; b.setAttribute('aria-pressed', String(on)); }
      hint.textContent = T(w ? 'home.fit_note' : 'home.crop_hint');
    };
    pick(whole);
    if (whole != null) (whole ? bWhole : bCover).focus();             // the dialog's own first focus would ring the other chip
  });
}

$('#homeopen')?.addEventListener('click', homeOpen);                  // the hosts' page has the buttons; TV Home's own opens the settings at once (homeweb.js)
$('#homeupd')?.addEventListener('click', homeUpdate);
$('#homepin')?.addEventListener('click', async () => { if (homeLink.direct || !await needAdbSoon()) homePin(); });
