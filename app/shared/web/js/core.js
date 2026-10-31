// shared helpers, status chips, ADB gating
// The same page is served by three cores, and they answer the same /api calls:
//   mac    the program on the computer (app/desktop): many TVs, remote buttons without ADB, first-time setup
//   tv     the TV Setup app on the TV itself: one TV, opened from any browser after typing a code shown on the TV
//   phone  the phone app's Manage screen: apps, files, health and terminal only (the app has its own remote)
// What one core cannot do is marked data-for="..." in index.html and hidden by css/page.css; the few differences in behaviour are asked here.
const CORE = document.documentElement.dataset.core || 'pc';
document.documentElement.dataset.core = CORE;
// icon + words as separate spans, so narrow screens can show the icon alone (see .lbl in css/page.css)
// asks: its handler asks before doing anything, so the icon-only question below is not asked as well
const setLabel = (b, icon, text, asks) => { if (asks) b.dataset.asks = ''; b.replaceChildren(el('span', { className: 'ico', textContent: icon }), el('span', { className: 'lbl', textContent: ' ' + text })); if (!b.title) b.title = text; return b; };
const fmtSize = (n) => n > 1e9 ? (n / 1e9).toFixed(1) + ' GB' : n > 1e6 ? (n / 1e6).toFixed(1) + ' MB' : n > 1e3 ? Math.round(n / 1e3) + ' KB' : n + ' B';
const sleep = (ms) => new Promise((z) => setTimeout(z, ms));
// the tap a key gives under the finger, on a touch screen that can vibrate (Settings: Vibrate on key press, on by default)
const CAN_BUZZ = CORE !== 'phone' && !!navigator.vibrate && matchMedia('(pointer: coarse)').matches;   // the phone app has its own switch (Settings)
const buzzOn = () => { try { return localStorage.getItem('buzz') !== '0'; } catch { return true; } };
const kbdAutoOn = () => { try { return localStorage.getItem('kbdauto') !== '0'; } catch { return true; } };   // the TV's keyboard opens the typing box (remote.js)
const buzz = () => { if (CAN_BUZZ && buzzOn()) try { navigator.vibrate(15); } catch {} };
// Questions asked in the page, not with confirm() / prompt(): they look the same everywhere, and the computer app's window
// (a bare web view) has no dialogs of its own. ask(text) → true / false; ask(text, { value }) → the typed text, or null.
// { ok, danger, no }: the words and colour of the yes button, the words of the other. One question at a time; the button that asked keeps its spinner.
// askTime(text, minutes, max) → minutes, or null: an hours box and a minutes box, so nobody guesses which unit a number is.
let askQ = Promise.resolve();
function ask(text, o = {}) {
  const from = clicked;
  const run = () => new Promise((done) => {
    const typed = o.value !== undefined || !!o.fields;
    const boxes = o.fields ? o.fields.map((f) => el('input', { type: 'number', inputMode: 'numeric', min: 0, max: f.max, value: f.value, placeholder: '0' })) : null;
    const input = o.fields ? el('div', { className: 'fields' }, ...o.fields.flatMap((f, i) => [el('label', {}, boxes[i], el('span', { textContent: f.label }))]))
      : typed ? el('input', { value: o.value, spellcheck: false }) : null;
    // choices: rows to pick from that fill the box ({ value, name, tag, sub }: its name, a tag beside it, a line under it), drawn by
    // the page and not a <select>: the phone's WebView shows one as a system list, every part of a row run into one line
    const mark = () => pick && pick.querySelectorAll('.item').forEach((r) => r.classList.toggle('on', r.value === input.value.trim()));
    const pick = o.choices && el('div', { className: 'list askpick' }, ...o.choices.map((c) => el('button', { type: 'button', className: 'item pick', value: c.value,
      onclick: () => { input.value = c.value; mark(); } },
      el('div', { className: 'name' }, el('span', {}, el('b', { textContent: c.name }), c.tag ? el('span', { className: 'ltag', textContent: c.tag }) : null),
        c.sub ? el('small', { textContent: c.sub }) : null))));
    if (pick) { input.addEventListener('input', mark); input.placeholder = T('core.type_own'); }
    if (o.pin && input) { input.type = 'password'; input.inputMode = 'numeric'; input.autocomplete = 'new-password'; }   // the phone's number pad, dots
    const yes = el('button', { type: 'submit', className: o.danger ? 'danger' : 'primary', textContent: o.ok || 'OK' });
    const no = el('button', { type: 'button', textContent: o.no || T('common.cancel'), onclick: () => d.close() });
    const d = el('dialog', { className: 'ask' }, el('form', { className: 'card', onsubmit: (e) => { e.preventDefault(); d.returnValue = 'yes'; d.close(); } },
      el('p', { textContent: text }), o.warn ? el('p', { className: 'warnline', textContent: o.warn }) : null, pick || null, input, el('div', { className: 'acts' }, no, yes)));
    d.addEventListener('close', () => {
      d.remove();
      const ok = d.returnValue === 'yes';
      if (ok) { clicked = from; clickedAt = performance.now(); }       // its call starts now: the spinner goes on the button that asked
      done(typed ? (ok ? (boxes ? boxes.map((b) => b.value) : input.value) : null) : ok);
    });
    document.body.append(d); d.showModal(); mark();
    if (boxes) boxes[0].select(); else if (input) input.select(); else yes.focus();
  });
  const p = askQ.then(run); askQ = p.catch(() => {}); return p;
}
async function askTime(text, minutes, max = 1440) {
  const m = Math.round(minutes || 0);
  const v = await ask(text, { fields: [{ label: T('core.unit_h'), value: m ? String(Math.floor(m / 60)) : '', max: Math.floor(max / 60) }, { label: T('core.unit_min'), value: m ? String(m % 60) : '', max: 59 }] });
  if (!v) return null;
  const total = Math.round((+v[0] || 0) * 60 + (+v[1] || 0));
  if (total < 1 || total > max) { toast(T('core.pick_range', { n: max / 60 })); return null; }
  return total;
}

// A menu whose choices look like what they are: buttons (the phone app's menu() in Ui.kt, which the remote's menus mirror).
// items: ['@icon|Words', 'a line saying what it does', fn]; primary = the index of the main action (filled), or -1; current =
// the one in use now (drawn on, css/kit.css), or -1. The menu closes first, then fn runs, so fn can ask the next question.
// A menu opened from a choice of another (now, or once its list came in) has Back to it, and Esc goes there too.
let menuFrom = null;
function menuDlg(title, primary, items, current = -1, alone = false) {   // alone: a question of its own, never a Back to the last menu
  const from = !alone && menuFrom && menuFrom.title !== title && Date.now() - menuFrom.at < 8000 ? menuFrom.open : null;
  menuFrom = null;
  const icon = (name) => { const [n, tint] = name.split(':'); const s = el('span', { className: 'mi', innerHTML: '<svg class="ic" aria-hidden="true"><use href="#i-' + n + '"/></svg>' }); if (tint) s.style.color = tint; return s; };   // "dot:#e5484d": remote.json's tint
  const back = () => { d.close(); from(); };
  const d = el('dialog', { className: 'ask menu' }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, el('h2', { textContent: title }), el('div', { className: 'tools' },
      from ? el('button', { className: 'small', textContent: T('common.back'), onclick: back }) : null,
      el('button', { className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() }))),
    ...items.map(([token, sub, fn], i) => {
      const bar = token.indexOf('|'), ic = token[0] === '@' && bar > 0;
      return el('button', { className: 'mitem' + (i === primary ? ' primary' : i === current ? ' on' : ''), onclick: () => {
        d.close(); menuFrom = { title, at: Date.now(), open: () => menuDlg(title, primary, items, current) }; fn(); } },
        ic ? icon(token.slice(1, bar)) : null,
        el('span', { className: 'mt' }, el('b', { textContent: ic ? token.slice(bar + 1) : token }), sub ? el('small', { textContent: sub }) : null),
        el('span', { className: 'mgo', textContent: '›' }));
    })));
  d.addEventListener('close', () => d.remove());
  if (from) d.addEventListener('cancel', (e) => { e.preventDefault(); back(); });
  document.body.append(d); d.showModal();
  return d;
}

// On a narrow screen some buttons show their icon alone (.lbl folded away, css/page.css). A tap there first says what it does, so
// nobody acts on a guess at an icon; yes then does it. Not for ✕ and ⋯ (they say it by themselves), nor for buttons whose
// own handler asks already (data-asks): one question is enough.
let iconSure = false;
document.addEventListener('click', async (e) => {
  if (iconSure || !e.isTrusted || e.target.tagName === 'INPUT') return;   // a person's tap only: not our own click once sure, nor a label passing its click on
  const b = e.target.closest && e.target.closest('button, label.linkbtn'); if (!b || b.disabled || 'asks' in b.dataset) return;
  if (b.closest('.tabs')) return;                                      // a tab only shows a page: nothing to ask
  const ls = [...b.querySelectorAll(':scope > .lbl')], l = ls[0];      // the tv tab has a word per core, one of them hidden
  if (!l || ls.some((x) => x.getBoundingClientRect().width > 1)) return;
  const name = l.textContent.trim(); if (!name || /^(✕|⋯)$/.test(b.textContent.trim())) return;
  e.preventDefault(); e.stopImmediatePropagation();
  const ico = (b.querySelector(':scope > .ico') || {}).textContent || '', why = b.title && b.title.trim() !== name ? b.title.trim() : '';
  if (!await ask((ico + ' ' + (why || name)).trim() + '?', { ok: name, danger: b.classList.contains('danger') })) return;
  const input = b.tagName === 'LABEL' && b.querySelector('input');
  iconSure = true; try { (input || b).click(); } finally { iconSure = false; }
}, true);

// The computer's page is read fresh from disk, its server's code only at start: a newer page's calls then answer 404.
const STALE = T('core.stale');
// Every button that waits on the server shows it: the one just clicked spins until its call returns (css/kit.css .busy, shown
// only past 150 ms so quick calls do not flicker). Counted from the end of the click handler, or from the yes to an ask() in it.
let clicked = null, clickedAt = Infinity;
document.addEventListener('click', (e) => { clicked = e.target.closest && e.target.closest('button'); clickedAt = Infinity; setTimeout(() => { clickedAt = performance.now(); }, 0); }, true);
async function api(path, data, quiet, ms) {
  if (path === '/api/key' && typeof snapKey === 'function') snapKey();   // every key of every button comes through here: screenshot mode looks again soon after
  const b = clicked && !clicked.dataset.busy && !clicked.ownRing && performance.now() - clickedAt < 1000 ? clicked : null;
  if (b) { b.classList.add('busy'); b.busyN = (b.busyN || 0) + 1; }
  try { return await call(path, data, quiet, ms); } finally { if (b && !--b.busyN) b.classList.remove('busy'); }
}
// "adb:allow", "adb: <Java's words>": what a person can do about it
const adbWords = (status, e) => status === 503 && /^adb/.test(e) && !/adb (pair|tls)/.test(e)
  ? (/allow/.test(e) ? T('core.adb_allow') : T('core.adb_not_yet')) : e;
// A read that must not hang (a TV gone silent holds the socket): ms opts in, never for an upload, a download, an install or a wait
// on slow TV work, which end when they end. AbortSignal.timeout is missing in old WebViews: a timer and a controller do the same.
function timeoutSignal(ms) {
  if (typeof AbortSignal !== 'undefined' && AbortSignal.timeout) return AbortSignal.timeout(ms);
  const c = new AbortController(); setTimeout(() => c.abort(), ms); return c.signal;
}
async function call(path, data, quiet, ms) {
  try {
    const r = await fetch(path, { ...(data ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) } : {}), ...(ms ? { signal: timeoutSignal(ms) } : {}) });
    if (r.status === 401 && CORE === 'tv') { refresh(); return null; }   // this browser is no longer paired with the TV
    if (!r.ok) {
      let e = r.statusText; try { e = (await r.json()).error || e; } catch {}
      if (!quiet && needSetup(e)) return null;                         // asked as a question with its button, not a toast
      if (!quiet && /channels:old/.test(e)) { chanOld(); return null; }   // TV Channels is there but too old for the link: Update, not "missing"
      if (r.status === 403 && e === 'scope') { refresh(); if (!quiet) toast(scopeWhy()); return null; }   // the owner may have just changed it
      if (r.status === 404 && CORE === 'pc') e = STALE;
      if (adbWords(r.status, e) !== e) { e = adbWords(r.status, e); setTimeout(refresh, 300); }
      if (!quiet) toast(T('common.error_msg', { msg: friendly(e) })); return null;
    }
    return await r.json();
  } catch { if (!quiet) toast(T('core.server_unreachable')); return null; }
}

// A small menu under a button (⋯ on each app): items are [icon, words, fn, class]; null items are left out. A click elsewhere,
// Esc or scrolling closes it; the same button again too.
let popEl = null;
function closePop() { if (popEl) { popEl.remove(); popEl = null; } }
function popover(anchor, items) {
  const again = popEl && popEl.anchor === anchor; closePop(); if (again) return;
  const m = el('div', { className: 'pop', role: 'menu' }, ...items.filter(Boolean).map(([icon, text, fn, cls]) =>
    setLabel(el('button', { className: cls || '', role: 'menuitem', onclick: () => { closePop(); fn(); } }), icon, text)));
  const layer = !!m.showPopover; if (layer) m.popover = 'manual';      // the top layer: over a modal dialog too (⋯ in the launcher gallery)
  m.anchor = anchor; (layer && anchor.closest('dialog') || document.body).append(m); popEl = m; if (layer) m.showPopover();   // in the dialog: outside a modal one it would be inert
  const r = anchor.getBoundingClientRect(), w = m.offsetWidth, h = m.offsetHeight, sx = layer ? 0 : scrollX, sy = layer ? 0 : scrollY;
  m.style.left = sx + Math.max(8, Math.min(innerWidth - w - 8, r.right - w)) + 'px';
  m.style.top = sy + (r.bottom + h + 8 > innerHeight && r.top > h + 8 ? r.top - h - 4 : r.bottom + 4) + 'px';
  m.querySelector('button').focus({ preventScroll: true });
}
document.addEventListener('click', (e) => { if (popEl && !popEl.contains(e.target) && !popEl.anchor.contains(e.target)) closePop(); }, true);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && popEl) { const a = popEl.anchor; closePop(); a.focus(); } });
addEventListener('scroll', (e) => { if (popEl && !popEl.contains(e.target)) closePop(); }, true);
addEventListener('resize', closePop);

// A phone or computer can be newer than TV Setup on the TV. What needs which TV Setup is SetupNeed.kt's table; any call that
// meets one (its error says "setup:old|missing:<feature>:<min>:<have>") asks to update or install TV Setup, with the button.
// A page that knows before starting asks setupFor(feature). The same names as SetupNeed.name.
// ids of i18n/*.json: the words of each feature, said in the question below
const SETUP_NEEDS = { schedule: 'core.need_schedule', schedule_windows: 'core.need_schedule_windows', rules: 'core.need_rules', draw: 'core.need_draw', invite: 'core.need_invite', ram: 'core.need_ram', pic: 'core.need_pic', volume: 'core.need_volume', bedtimes: 'core.need_bedtimes', notify: 'core.need_notify', awake: 'core.need_awake', files: 'core.need_files', welcome: 'core.need_welcome', launcher: 'core.need_launcher', lock: 'core.need_lock', dim: 'core.need_dim', unextra: 'core.need_unextra', home_usage: 'core.need_home_usage', holdkeys: 'core.need_holdkeys', mic: 'core.need_mic', checkout: 'core.need_checkout', telegram: 'core.need_telegram', kids: 'core.need_kids', homekey: 'core.need_homekey', keytest: 'core.need_keytest', support: 'core.need_support', scenepin: 'core.need_scenepin' };
const SETUP_RE = /setup:(old|missing):(\w+):([0-9.]*):([0-9.]*)/;
const setupSay = (m) => { const what = SETUP_NEEDS[m[2]] ? T(SETUP_NEEDS[m[2]]) : m[2];
  return m[1] === 'missing' ? T('core.setup_missing', { what }) : T('core.setup_old', { what, min: m[3], have: m[4] }); };
function needSetup(why) {
  const m = String(why || '').match(SETUP_RE); if (!m) return false;
  const old = m[1] === 'old';
  ask(setupSay(m), { ok: old ? T('page.update') : T('core.install_setup') }).then((yes) => { if (yes) (old ? updateSetup : installSetupApk)(); });
  return true;
}
const setupFor = async (feature) => !!await api('/api/setup/need?f=' + feature);

// The errors people actually meet, from any core , in words they can act on; anything else as it came.
const FRIENDLY = [
  [SETUP_RE, setupSay],
  [/the home network is out of reach under the VPN/, T('core.fr_vpn_blocked')],   // Lan.BLOCKED: the phone's Manage screen
  [/notify:nooverlay/, T('core.fr_notify_no_overlay')],
  [/telegram: bad token/, T('telegram_ui.bad_token')],
  [/welcome:toobig/, () => T('core.fr_welcome_toobig', { max: fmtSize(200 * 1048576) })],   // Welcome.MAX_BYTES
  [/welcome:kind/, T('core.fr_welcome_kind')],
  [/welcome:unreadable/, T('core.fr_welcome_unreadable')],
  [/welcome:nothing/, T('core.fr_welcome_nothing')],
  [/welcome:nofolder/, T('core.fr_welcome_nofolder')],
  [/no YouTube and no Google search/, T('core.fr_no_search')],
  [/^unsupported: (LG|Samsung|Roku|Hisense VIDAA)$/, (m) => T('core.fr_unsupported', { brand: m[1] })],
  [/vidaa: the certificate from Hisense/, T('core.fr_vidaa_cert_needed')],
  [/vidaa: no certificate in this file/, T('core.fr_vidaa_no_cert')],
  [/this TV keeps that volume itself/, T('core.fr_volume_locked')],   // Scenes.volume, a stream other than media
  [/turned itself back off/, T('core.fr_turned_back_off')],   // TvShell.setting, ON_ONLY
  [/roku: control by mobile apps is off/, T('core.fr_roku_blocked')],   // Roku.LIMITED: newer Roku OS keeps phones out
  [/this TV did not take the switch/, T('core.fr_input_not_taken')],   // Inputs.switch
  [/this TV cannot switch inputs from here/, T('core.fr_input_cannot')],
  [/^bad input$/, T('core.fr_input_bad')],
  [/^refused$/, T('core.fr_tv_refused')],
  [/^not accepted$/, T('core.fr_not_accepted')],
  [/SSLHandshake|CertPathValidator|Chain validation|CERTIFICATE_VERIFY|not yet valid/i, T('core.fr_secure_failed')],
  [/UnknownHost|Name or service not known|nodename nor servname|getaddrinfo/i, T('core.fr_no_internet')],
  [/SocketTimeout|timed out|ConnectException|Connection reset/i, T('core.fr_net_slow')],
  [/stopped answering|stopped taking data/i, T('core.fr_tv_stopped')],
  [/copy to the TV incomplete/i, T('core.fr_copy_incomplete')],
  [/stream closed|socket closed|broken pipe/i, T('core.fr_connection_lost')],   // ADB cut: another TV chosen, or the network dropped
  [/no sign-in link/i, T('core.fr_no_signin_link')],
  [/storage full: file=(\d+) free=(\d+) need=(\d+) apk=([01])/, (m) => T(m[4] === '1' ? 'core.fr_storage_apk' : 'core.fr_storage_file', { free: fmtSize(+m[2]), size: fmtSize(+m[1]), need: fmtSize(+m[3]) })],
  [/INSUFFICIENT_STORAGE|No space left/i, T('core.fr_no_space')],
  [/no app that can open/i, T('core.fr_no_viewer_app')],
  [/cannot be opened \(no launcher screen\)|no launcher activity/i, T('core.fr_no_launcher')],
  [/adb pair: wrong code/i, T('core.fr_pair_wrong_code')],
  [/adb pair: not a pairing port/i, T('core.fr_pair_wrong_port')],
  [/adb tls: this device is not paired/i, T('core.fr_not_paired')],
  [/adb tls: this app cannot pair/i, T('core.fr_cannot_pair')],
  [/not a backup/i, T('core.fr_not_backup')],
  [/^protected: the TV needs this app/i, T('core.fr_protected_app')],
  [/^locked: the system holds these permissions/i, T('priv.locked')],
  [/^factory app: not opened/i, T('errors.factory_app')],
  [/install blocked by this TV's firmware|disabled from pm/i, T('core.fr_install_blocked')],
  [/removed by the TV right after install/i, T('core.fr_removed_after_install')],
  // the check before installing (apk_fits / Apk.fits), then what pm says when it was not caught there
  [/apk needs sdk (\d+), the tv has sdk (\d+)/i, (m) => T('core.fr_sdk_low', { need: androidName(m[1]), have: androidName(m[2]) })],
  [/apk is built for (\S+), the tv runs (\S+)/i, (m) => T('core.fr_abi_other', { apk: m[1].replace(/,/g, ', '), tv: m[2].replace(/,/g, ', ') })],
  [/not a changeable permission|SYSTEM_FIXED|POLICY_FIXED|cannot (grant|revoke) system/i, T('core.fr_perm_fixed')],
  [/has not requested permission|Unknown permission/i, T('core.fr_perm_not_asked')],
  [/cannot copy: split/, T('core.fr_copy_split')],
  [/cannot copy: manifest/, T('core.fr_copy_odd')],
  [/copy closed: (\S+)/, (m) => T('core.fr_copy_closed', { pkg: m[1] })],
  [/INSTALL_FAILED_CONFLICTING_PROVIDER|INSTALL_FAILED_DUPLICATE_PERMISSION/, T('core.fr_copy_conflict')],
  [/builtin app: (\S+)/, (m) => T('core.fr_builtin_app', { pkg: m[1] })],
  [/INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match/i, T('core.fr_signature_differs')],
  [/INSTALL_FAILED_VERSION_DOWNGRADE/i, T('core.fr_newer_installed')],
  [/INSTALL_FAILED_OLDER_SDK/i, T('core.fr_older_sdk')],
  [/TV Setup is up to date/i, T('core.fr_setup_latest')],
  [/an update of TV Setup is already running/i, T('core.fr_setup_updating')],
  [/TV Setup is not installed/i, T('core.fr_setup_missing')],
  [/the update did not start on the TV/i, T('core.fr_update_not_started')],
  [/could not write the update script/i, T('core.fr_update_script_failed')],
  [/INSTALL_FAILED_NO_MATCHING_ABIS/i, T('core.fr_abi_mismatch')],
  [/INSTALL_FAILED_VERIFICATION_FAILURE/i, T('core.fr_play_protect')],   // PlayProtect.kt
  // the launcher gallery (LauncherInstall.kt): the way it ended, in the order they can match
  [/launcher:rolledback:(\w+)/, (m) => T(LAUNCH_KEPT.includes(m[1]) ? 'core.fr_launcher_kept' : 'core.fr_launcher_back', { why: T(LAUNCH_WENT[m[1]] || 'launcher.why_user') })],
  [/apps:cert/, T('core.fr_tvapps_cert')],                         // AppList (TV Apps)
  [/apps:noapk/, T('core.fr_tvapps_noapk')],
  [/apps:offline/, T('core.fr_tvapps_offline')],
  [/launcher:home/, T('core.fr_launcher_home')],
  [/launcher:play/, T('core.fr_launcher_play')],
  [/launcher:self/, T('core.fr_launcher_self')],
  [/launcher:fit/, T('core.fr_launcher_fit')],
  [/launcher:install:(.*)/, (m) => T('core.fr_launcher_install', { msg: friendly(m[1]) })],
  [/home:missing/, T('core.fr_home_missing')],
  [/home:pin_old/, T('errors.pin_old')],
  [/pin_bad_link/, T('errors.pin_bad_link')],
  [/pin_no_app/, T('errors.pin_no_app')],
  [/pin_failed/, T('errors.pin_failed')],
  [/channels:missing/, T('core.fr_channels_missing')],
  [/channels:old/, T('core.fr_channels_old')],
  [/channels:file_big/, T('core.fr_channels_file_big')],
  [/channels:bad_link/, T('core.fr_channels_bad_link')],
  [/channels:none/, T('core.fr_channels_none')],
  [/games:missing/, T('core.fr_games_missing')],
  [/games:none/, T('core.fr_games_none')],
  [/tvapps:none/, T('core.fr_tvapps_none')],
  [/home:video_size|video size/, T('core.fr_home_video_size')],
  [/home:media_big/, T('core.fr_home_media_big')],
  [/home:media_missing/, T('core.fr_home_media_missing')],
  [/home:video_full/, T('core.fr_home_video_full')],
  [/home:video_space/, T('core.fr_home_video_space')],
  [/home:video_busy/, T('core.fr_home_video_busy')],
  [/home:video_old/, T('core.fr_home_video_old')],
  [/home:video_inuse|\binuse$/, T('core.fr_home_video_inuse')],
  [/home:video_(video|gif|transcode|type)|video type/, T('core.fr_home_video_bad')],
  [/home:photos_full/, T('home.photos_full', { n: 10 })],   // before home:photo, which it starts with
  [/home:video$/, T('core.fr_home_photo')],
  [/home:photo/, T('core.fr_home_photo')],
  [/launcher list not signed/i, T('core.fr_launcher_list')],
  [/launcher file does not match/i, T('core.fr_launcher_file')],
];
const LAUNCH_KEPT = ['wifi', 'wifi_unknown', 'cast', 'zte', 'board', 'boot', 'keys'];   // refused before anything changed (LauncherSwap.REFUSED)
const LAUNCH_WENT = { crash: 'launcher.why_crash', home: 'launcher.why_home', timeout: 'launcher.why_timeout', interrupted: 'launcher.why_interrupted', user: 'launcher.why_user',
  wifi: 'launcher.why_wifi', wifi_unknown: 'launcher.why_wifi_unknown', cast: 'launcher.why_cast', zte: 'launcher.why_zte', board: 'launcher.why_board', boot: 'launcher.why_boot', keys: 'launcher.why_keys' };
const ANDROID = { 21: '5.0', 22: '5.1', 23: '6', 24: '7', 25: '7.1', 26: '8', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16' };
const androidName = (sdk) => ANDROID[sdk] || 'SDK ' + sdk;
const friendly = (m) => { m = String(m || ''); const f = FRIENDLY.find(([re]) => re.test(m)); return !f ? m : typeof f[1] === 'function' ? f[1](m.match(f[0])) : f[1]; };

// ---------- status ----------
let curHost = null;
// Another TV: tvChanged() (setup.js) runs these, each file forgetting what it kept about the old TV in its own
const TV_RESETS = [];
const onTvChange = (fn) => TV_RESETS.push(fn);
// The list of TVs comes first, as in the phone app: shown whenever no TV is chosen, or when "Change TV" asks for it.
// It always opens on the list; "Change TV" comes back to it, and only then is there a TV to go back to.
let picking = false, wantPick = CORE === 'pc' && !(window.LANG_KEEP && !window.LANG_KEEP.picking), cameFromTv = false, justPicked = null, pickedAt = 0, scannedOnce = false;
// The list of TVs is opened and left only through these (setup.js, diagnose.js), so the four above stay in step
function pickOpen() { wantPick = true; cameFromTv = true; }          // from a TV: the list offers the way back to it
function pickLeave() { wantPick = false; cameFromTv = false; }
function pickGo(host) { pickLeave(); justPicked = host; pickedAt = Date.now(); }   // refresh() then opens its remote (or its steps)
function pickCancel() { justPicked = null; wantPick = true; }
function setPicking(on, tvName) {
  const b = $('#pickback'), back = cameFromTv && !!tvName;
  put(b, 'hidden', !back); if (back) put(b, 'textContent', T('core.back_to', { name: tvName }));
  if (on === picking) return;
  picking = on; document.querySelector('main').classList.toggle('picking', on);
  if (on) {
    if (screenOn) stopScreen();
    document.querySelector('main').classList.remove('wide');
    loadTvs();
    if (!scannedOnce) { scannedOnce = true; $('#scan').click(); }       // look around without being asked, once
  } else showTab(currentTab);
}
let ADB = { state: 'unknown' }, autoView = new URLSearchParams(location.search).has('view');
// a few words on the chip, so the header stays on one line on a phone; the whole sentence on hover
const ADB_TEXT = {
  ready: ['ok', '✓ ADB', T('core.adb_ready')],
  unauthorized: ['warn', T('core.adb_wait_short'), T('core.adb_wait_long')],
  closed: ['bad', T('core.adb_closed_short'), T('core.adb_closed_long')],
  offline: ['warn', 'ADB offline', 'ADB: offline'],
  error: ['bad', T('core.adb_error_short'), T('core.adb_error_long')],
  unknown: ['', 'ADB ...', 'ADB: ...'],
};
// Where an install / update button would be when GitHub could not be asked (the core says `offline`): one small line that is also the
// retry, so a missing button never reads as "nothing to update". [btn] the button, [off] whether to show it, [retry] asks again.
function lookOffline(btn, off, retry) {
  if (!btn.lookOff) { btn.lookOff = el('button', { type: 'button', className: 'small', onclick: () => retry(), textContent: T('page.look_offline') }); btn.before(btn.lookOff); }
  put(btn.lookOff, 'hidden', !off);
}
function setChip(id, cls, text, long) { const c = $(id); put(c, 'className', 'chip ' + cls); put(c.lastElementChild, 'textContent', text); put(c, 'title', long || text); }

let STATE = {};
let tvOn = false;                                                    // the power button asks before turning a TV that is on off
let volHold = 0;                                                      // don't yank the slider while the user drags it
// a shared QR's link (Invite.kt) carries an invite after #: it pairs this browser once, instead of a code; taken off the address
// at once, so a bookmark or a screenshot of it holds nothing
const INVITE = (location.hash.match(/invite=([0-9a-f]{32})/) || [])[1];
if (INVITE) history.replaceState(null, '', location.pathname + location.search);
let inviteBad = false, invited = INVITE ? fetch('/api/pair/invite', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ invite: INVITE }) })
  .then((x) => x.ok).catch(() => false) : null;
let stateMiss = 0, adbSoon = 0, adbSoonMs = 125;
// the server was stopped from the page (setup.js's Quit); declared here, before its first reader: setup.js loads ~30 scripts later,
// and a key pressed while the page loaded (the TV's PIN typed just before Manage opened) reached warmTv first: "stopped is not defined"
let stopped = false;
function serverStopped() { stopped = true; }
let refreshSeq = 0;                                                  // a slower older read must not undo a newer one (tvChanged would fire twice)
let refreshP = null;                                                  // the newest read: a call it replaced hands its caller this one, so `await refresh()` ends with STATE fresh
function refresh() { const p = refreshRead(); refreshP = p; return p; }
async function refreshRead() {
  if (stopped) return;
  let locked = false;
  if (invited) { inviteBad = !await invited; invited = null; }     // the invite first: then this very read is let in
  const seq = ++refreshSeq;
  const sig = timeoutSignal(15000);                                   // a busy or Tailscale TV answers slowly: that is not "no TV"
  const s = await fetch('/api/state', { signal: sig }).then((r) => { locked = r.status === 401; return r.json(); }).catch(() => null);
  if (seq !== refreshSeq) return refreshP;
  if (!s && sig && sig.aborted && ++stateMiss < 3) return;            // a slow answer keeps the last STATE and chips for two waits; a TV gone off the network (no reply at all) still ends in "disconnected"
  if (CORE === 'tv') setLocked(locked, s);
  if (locked && inviteBad) { inviteBad = false; put('#pairerr', 'textContent', T('core.qr_expired')); }
  if (locked) return;
  tvOn = !!(s && s.connected && s.is_on); STATE = s || {};
  if (s) applyKind(s);
  if (!s && ++stateMiss < 2) { setTimeout(refresh, 1500); return; }    // one miss is often this device waking (its Wi-Fi back a moment later)
  if (s) stateMiss = 0;
  if (!s) {
    setChip('#chipTv', 'bad', CORE === 'pc' ? T('core.server_stopped') : T('core.disconnected'), CORE === 'pc' ? T('core.server_stopped') : T('core.tv_disconnected')); setChip('#chipAdb', '', 'ADB ?');
    put('#info', 'textContent', CORE === 'pc' ? T('core.no_server') : T('core.no_tv')); return;
  }
  const adbUp = s.adb && s.adb.state === 'ready';                   // over ADB the TV answers even when the remote-button service does not
  setChip('#chipTv', s.connected && s.is_on ? 'ok' : s.connected || adbUp ? 'warn' : 'bad', s.connected ? (s.is_on ? T('core.tv_on') : T('core.screen_off')) : adbUp ? T('core.keys_over_adb') : T('core.disconnected'),
    s.connected ? (s.is_on ? T('core.tv_is_on') : T('core.tv_screen_off')) : adbUp ? T('core.buttons_over_adb') : T('core.tv_disconnected'));
  ADB = s.adb || { state: 'unknown' };
  if (ADB.state === 'unknown' && !adbSoon) adbSoon = setTimeout(() => { adbSoon = 0; refresh(); }, adbSoonMs = Math.min(adbSoonMs * 2, 2000));   // connecting: told the moment it is up, not at the next 4 s tick
  if (ADB.state !== 'unknown') adbSoonMs = 125;
  const [cls, txt, long] = ADB_TEXT[ADB.state] || ADB_TEXT.unknown; setChip('#chipAdb', cls, txt, long + (ADB.detail ? ' · ' + ADB.detail : ''));
  put($('#chipAdb'), 'hidden', NATIVE());                                    // no ADB on this kind of TV: nothing to report
  if (NATIVE() && !s.connected) setChip('#chipTv', s.asking ? 'warn' : 'bad', s.asking ? T('core.waiting_tv') : s.paired === false ? T('core.not_paired') : T('core.disconnected'));
  const tvName = s.name || (s.device && s.device.model) || (s.host ? 'TV ' + s.host : '');
  if (CORE === 'pc') setPicking(!s.host || wantPick, tvName);
  if (s.version) put($('#ver'), 'textContent', (s.app_name || 'TV Remote') + ' ' + s.version + (s.released ? ' · ' + s.released : ''));
  put(document, 'title', tvName && !picking ? tvName + ' · Remote' : 'TV Remote');
  put($('#name'), 'textContent', picking ? 'TV Remote' : tvName);
  put($('#info'), 'textContent', picking ? T('core.choose_tv') : [CORE === 'pc' ? s.host : '', s.asking ? T('core.look_choose_allow') : s.paired === false ? T('core.remote_not_paired') : s.error && !adbUp ? T('core.remote_not_connected') : '',
    s.scope === 'view' ? T('core.view_only') : s.scope === 'remote' ? T('core.remote_only') : ''].filter(Boolean).join(' · ') || T('core.connected'));   // a guest knows what it was given
  showNowApp(picking ? '' : s.app || '', s.app_label);
  if (justPicked && s.host === justPicked) {                          // straight to the remote when the TV is ready, to the first-time steps when it is not
    const ready = s.connected || (s.adb && s.adb.state === 'ready') || s.asking;   // LG / Samsung asking: the remote tab says what to do
    if (ready || Date.now() - pickedAt > 6000) { justPicked = null; showTab(ready ? 'remote' : 'tv'); }   // a TV takes a few seconds to answer
    else { setTimeout(refresh, 1000); if (s.name) put('#conname', 'textContent', s.name); }
  }
  if (!justPicked && $('#connecting').open) $('#connecting').close();
  if (s.host !== curHost) { const first = curHost === null; curHost = s.host; if (!first) tvChanged(); }
  if (s.volume && Date.now() > volHold) {
    put('#volv', 'textContent', s.volume.level);
    $('#r-mute').classList.toggle('on', !!s.volume.muted); put('#r-mute', 'title', s.volume.muted ? T('core.muted_tip') : T('core.mute'));
  }
  applyAdb(); allowAlert(); checkAsk(); diagWatch(s);
  if ($('#assist').open && (ADB.state === 'ready' || ADB.state === 'unauthorized')) {   // the TV has opened its ADB port: nothing left to help with
    $('#assist').close(); toast(ADB.state === 'ready' ? T('core.adb_connected') : T('core.adb_on_look'));
  }
}

// ---------- the kind of TV: Android TV, LG, Samsung or Roku (Remote.kt: Kind) ----------
// An LG or Samsung TV is driven through its own remote link and has no ADB: what needs a capability it lacks is marked
// data-needs="cap ..." and hidden (cards), greyed (buttons, keys it has no name for: a press says why) or, for a tab, kept
// greyed with a card of what it does on an Android TV ([data-kindintro], KIND_INTRO): a feature to find out about, not a gap.
let TV_KIND = 'android', CAPS = null, kindSig = '';
// A browser let in by a shared QR may have less than the owner (Scope.kt in TV Setup): "remote" presses keys and opens apps,
// "view" only watches. The page marks those parts data-needs="control" (pressing anything) or "full" (the owner's), as for a
// TV that cannot; the other tabs are left out for a guest, as they are the owner's only.
let SCOPE = 'full';
const NATIVE = () => TV_KIND !== 'android';
const can = (cap) => cap === 'full' ? SCOPE === 'full' : cap === 'control' ? SCOPE !== 'view' : !CAPS || CAPS.has(cap);
const KIND_NAME = { android: 'Android TV', lg: 'LG', samsung: 'Samsung', roku: 'Roku', vidaa: 'Hisense VIDAA' };
const scopeWhy = () => SCOPE === 'view' ? T('core.scope_view') : T('core.scope_owner');
const kindWhy = () => SCOPE !== 'full' ? scopeWhy() : T('core.only_android');
function unsupported(e, off) {
  if (e.matches('[data-tab]')) { e.toggleAttribute('data-kind-tab', off); return; }
  if (e.matches('input, select, fieldset')) { e.disabled = off; return; }
  if (!e.matches('button')) { e.classList.toggle('kind-off', off); return; }
  if (off && !('kindOff' in e.dataset)) { e.dataset.kindOff = e.title; e.title = kindWhy(); e.setAttribute('aria-disabled', 'true'); }
  else if (!off && 'kindOff' in e.dataset) { e.title = e.dataset.kindOff; if (!e.title) e.removeAttribute('title'); delete e.dataset.kindOff; e.removeAttribute('aria-disabled'); }
}
// a greyed button still answers a press, with why: not disabled, so the press reaches here before its own handlers
for (const ev of ['pointerdown', 'click']) document.addEventListener(ev, (e) => {
  if (!e.target.closest?.('[data-kind-off]')) return;
  e.preventDefault(); e.stopImmediatePropagation();
  if (ev === 'click') toast(kindWhy());
}, true);
const KIND_INTRO = {
  screen: [T('core.intro_screen'), [T('core.intro_screen_1'), T('core.intro_screen_2'), T('core.intro_screen_3'), T('core.intro_screen_4')]],
  apps: [T('core.intro_apps'), [T('core.intro_apps_1'), T('core.intro_apps_2'), T('core.intro_apps_3')]],
  files: [T('core.intro_files'), [T('core.intro_files_1'), T('core.intro_files_2'), T('core.intro_files_3')]],
  health: [T('core.intro_health'), [T('core.intro_health_1'), T('core.intro_health_2'), T('core.intro_health_3')]],
  rules: [T('core.intro_rules'), [T('core.intro_rules_1'), T('core.intro_rules_2'), T('core.intro_rules_3'), T('core.intro_rules_4')]],
};
function kindIntro(key) {
  const [title, items] = KIND_INTRO[key];
  return el('section', { className: 'card kintro' },
    el('div', { className: 'card-head' }, el('h2', {}, title, key === 'screen' ? el('span', { className: 'badge', textContent: T('core.beta') }) : ''),
      el('span', { className: 'badge', textContent: kindWhy() })),
    el('ul', {}, ...items.map((t) => el('li', { textContent: t }))));
}
// What the TVs said and the errors shown (WireLog.kt): what a real TV did is what tells a bug apart. Copied, then the site's
// page where it is pasted and sent on (a GitHub issue or a message): no log in the link, it leaves only when the person sends it.
async function sendWireLog() {
  let text;
  try { const r = await fetch('/api/wirelog'); if (!r.ok) throw 0; text = await r.text(); } catch { toast(T('core.log_unreadable')); return; }
  let ok = false;
  try { await navigator.clipboard.writeText(text); ok = true; } catch {}
  if (!ok) { const a = el('textarea', { value: text }); document.body.append(a); a.select(); try { ok = document.execCommand('copy'); } catch {} a.remove(); }
  window.open(LOG_PAGE, '_blank');
  toast(ok ? T('core.log_copied') : T('core.log_copy_failed'));
}
const LOG_PAGE = 'https://tvremote.pages.dev/log';
function applyKind(s) {
  const scope = s.scope || 'full', kind = s.kind || 'android', sig = kind + '|' + scope + '|' + (s.caps || []).join() + '|' + (s.keys || []).join();
  if (sig === kindSig) return;
  kindSig = sig; TV_KIND = kind; CAPS = s.caps ? new Set(s.caps) : null; SCOPE = scope;
  document.documentElement.dataset.kind = kind; document.documentElement.dataset.scope = scope;
  document.querySelectorAll('nav.tabs [data-tab]').forEach((t) => { t.hidden = scope !== 'full' && t.dataset.tab !== 'remote'; });
  if (scope !== 'full' && currentTab && currentTab !== 'remote') showTab('remote');   // a guest now: the other tabs are the owner's
  const keys = kind !== 'android' && s.keys ? new Set(s.keys) : null;
  document.querySelectorAll('[data-needs]').forEach((e) => unsupported(e, !e.dataset.needs.split(' ').every(can)));
  document.querySelectorAll('[data-k]').forEach((b) => unsupported(b, !!keys && !keys.has(b.dataset.k)));
  document.querySelectorAll('[data-kindintro]').forEach((b) => { b.hidden = !NATIVE(); b.replaceChildren(...NATIVE() ? [kindIntro(b.dataset.kindintro)] : []); });
  if (currentTab) showTab(currentTab);                                // a tab now greyed shows its card, and back
}

// The phone app opens some of the page's dialogs from its remote (PhoneRemote.kt, AdbHelp.kt): closing one goes straight back
// there, not to the manage screen behind it, unless it led on to something here (another dialog, a TV Setup update)
async function fromRemote(open) {
  await open();
  const left = () => setTimeout(() => {
    if (!document.querySelector('dialog[open]') && !(typeof setupBusy !== 'undefined' && setupBusy)) fetch('/api/phone/back', { method: 'POST' });
  }, 300);
  const d = [...document.querySelectorAll('dialog[open]')].pop();
  if (d) d.addEventListener('close', left, { once: true }); else left();   // none: it stopped short (no ADB, TV Setup not wanted)
}

// ---------- ADB gating + banners ----------
// The page from the TV works through the TV Setup app's own ADB session: without it nothing works, and there is no other machine to blame.
const BANNERS_TV = {
  closed: ['bad', T('core.tvb_closed_title'), T('core.tvb_closed_text')],
  unauthorized: ['warn', T('core.tvb_asking_title'), T('core.tvb_asking_text')],
  unknown: ['warn', T('core.adb_connecting'), ''],
};
BANNERS_TV.error = BANNERS_TV.offline = BANNERS_TV.closed;
const BANNERS = {
  closed: ['bad', T('core.pcb_closed_title'), T('core.pcb_closed_text')],
  unauthorized: ['warn', T('core.pcb_asking_title'), T('core.pcb_asking_text')],
  offline: ['warn', T('core.pcb_offline_title'), T('core.pcb_offline_text')],
  error: ['bad', T('core.pcb_error_title'), T('core.pcb_error_text')],
  unknown: ['warn', T('core.adb_connecting'), ''],
};
let bannerKey = '', scenesLoaded = false;
const bannerAgain = () => { bannerKey = ''; };                      // drawn again on the next tick, whatever it was
function adbNow(r) { ADB = r; bannerAgain(); }                        // ADB changed here (a button), not in a tick
onTvChange(() => { scenesLoaded = false; bannerAgain(); });
function buildBanner() {
  if (ADB.state === 'none') return el('span');                        // LG / Samsung: no ADB to turn on
  const set = CORE === 'tv' ? BANNERS_TV : BANNERS, [cls, title, text] = set[ADB.state] || set.unknown;   // the phone reaches the TV itself, as a computer does: no TV Setup to blame
  const retry = el('button', { className: 'small primary', textContent: T('core.retry_connection'), onclick: async (e) => {
    const done = UI.busy(e.currentTarget, T('core.connecting'));
    const r = await api('/api/adb/connect', {}); if (r) { ADB = r; bannerKey = ''; applyAdb(); refresh(); }
    done();
  } });
  if (ADB.state === 'unknown' || !ADB.state) {                       // still connecting: a quiet line, Retry only once it drags on
    retry.className = 'small'; retry.textContent = T('core.try_again'); retry.hidden = true;
    setTimeout(() => { retry.hidden = false; }, 8000);
    return el('div', { className: 'banner wait' }, el('span', { className: 'minispin' }), el('span', { textContent: title }), retry);
  }
  if (CORE !== 'pc') {                                                // the TV's own page and the phone app: no computer to help, so the steps on the TV, and what the TV said
    const detail = ADB.detail ? el('p', { className: 'note', textContent: T('core.adb_detail', { d: ADB.detail }) }) : null;
    const how = el('button', { type: 'button', className: 'small primary', textContent: T('core.tvb_help_btn'), onclick: () => showAdbGuide() });
    // TV Setup on a TV with Wireless debugging only (a Chromecast with Google TV): the 6-digit code is typed here, from a phone or computer
    const pair = CORE === 'tv' && ADB.pair ? el('button', { type: 'button', className: 'small primary', textContent: T('setup.wireless_choice'), onclick: () => wirelessPair() }) : null;
    if (pair) how.className = 'small';
    retry.className = 'small';
    return el('div', { className: 'banner ' + cls }, el('b', { textContent: title }), el('p', { textContent: text }), detail, el('div', { className: 'actions' }, pair, how, retry));
  }
  const helpMe = ADB.state === 'closed' || ADB.state === 'error' ? el('button', { className: 'small primary', textContent: T('core.adb_help_me'), onclick: () => adbHelp() }) : null;
  if (helpMe) retry.className = 'small';
  const loss = el('p'); loss.append(el('b', { textContent: T('core.lost_features') + ' ' }), T('core.lost_features_list'));
  return el('div', { className: 'banner ' + cls }, el('b', { textContent: title }), el('p', { textContent: text }), loss,
    el('div', { className: 'actions' }, helpMe, retry, el('button', { className: 'small', textContent: T('core.how_adb'), onclick: () => { const h = $('#help'); h.open = true; h.scrollIntoView({ behavior: 'smooth', block: 'center' }); } })));
}
function applyAdb() {
  const ready = ADB.state === 'ready';
  if (bannerKey !== ADB.state) {                                      // rebuild only on change so buttons are not replaced mid-click
    bannerKey = ADB.state;
    document.querySelectorAll('[data-adb-banner]').forEach((b) => b.replaceChildren(...(ready ? [] : [buildBanner()])));
    if (ready && currentTab === 'tv' && typeof tsState === 'function') { tsState(); restoreState(); loadTvset(); loadHold(); homeCard(); chanCard(); gameCard(); }   // the cards that ask over ADB: opened before it was up, they had stayed empty
  }
  // a tab already shown with its data keeps it, scroll and all, under a veil that says ADB is coming back (or why not, with Retry);
  // the banner in place of the tab only when nothing was shown yet
  const key = curHost || '-';
  document.querySelectorAll('.panel').forEach((p) => {
    if (p.dataset.panel === 'remote' || p.dataset.panel === 'tv' || p.dataset.panel === 'kind') return;
    if (ready && !p.hidden && p.dataset.seen !== key) p.dataset.seen = key;
    const held = !ready && ADB.state !== 'none' && !NATIVE() && p.dataset.seen === key;
    p.classList.toggle('held', held);
    p.querySelectorAll('[data-adb-banner]').forEach((b) => { b.hidden = held; });
    let v = p.querySelector(':scope > .veil');
    if (!held) { if (v) v.remove(); return; }
    if (!v) { v = el('div', { className: 'veil' }); p.append(v); }
    if (v.dataset.k !== ADB.state) { v.dataset.k = ADB.state; v.replaceChildren(el('div', { className: 'veilcard' }, buildBanner())); }
  });
  document.querySelectorAll('[data-adb-gated]').forEach((g) => {   // data-native: works without ADB on LG / Samsung
    g.hidden = !ready && !(NATIVE() && 'native' in g.dataset) && !(g.tagName !== 'BUTTON' && g.closest('.panel.held'));
  });
  document.querySelectorAll('.adbnote').forEach((n) => put(n, 'hidden', ready));   // "· needs ADB": said only while it is missing
  const noadb = !ready && ADB.state !== 'unknown' && !NATIVE();         // not while still connecting; LG / Samsung hide those tabs instead
  $('.tabs').classList.toggle('noadb', noadb);
  document.querySelectorAll('.tabs [data-needs-adb]').forEach((b) => { if (noadb) b.title = T('core.needs_adb_tip'); else b.removeAttribute('title'); });
  const typing = ready || NATIVE() && can('text');                    // typing goes through ADB, or LG's / Samsung's own keyboard link
  put('#kbd', 'disabled', !typing); put($('#kbdoff'), 'hidden', typing);         // no half-working box without it
  put('#screenbtn', 'disabled', !ready && !screenOn);
  if (!ready && screenOn) stopScreen(T('core.adb_stopped_view'));
  if (ready && autoView) { autoView = false; startScreen(); }           // bookmark ?view=1 to start watching right away
  if (ready && !picking && setupChecked !== (curHost || '-')) checkSetup();   // a newer TV Setup: offered once per TV (auto.js, its setupChecked)
  if (NATIVE() && STATE.connected) { if (!quickLoaded && can('app_list')) loadQuick(); if (!scenesLoaded) { scenesLoaded = true; loadScenes(); } }   // the TV's apps from its remote link
  if (ready) { if (!quickLoaded) loadQuick(); if (currentTab === 'apps') { if (!appsLoaded) loadApps(); recentsNow(); } if (currentTab === 'files' && !filesLoaded) loadFiles(cwd); if (!scenesLoaded) { scenesLoaded = true; loadScenes(); } if (currentTab === 'auto' && !autoLoaded) autoTab(); if ($('#setlist').open) loadSettings(); }
  if (!ready && listenOn) stopListen(T('core.adb_stopped_listen'));
}


// ---------- the TV asks "Allow USB debugging?" for another device: answer it from here (for its key only) ----------
let askSeen = '', askAt = 0;
async function checkAsk() {
  if (ADB.state !== 'ready' || document.hidden || Date.now() - askAt < 20000) return;   // dumpsys window on the TV each time; another device asking is rare
  askAt = Date.now();
  const r = await fetch('/api/adb/prompt').then((x) => x.ok ? x.json() : null).catch(() => null);
  const fp = (r && r.fingerprint) || '';
  put($('#askother'), 'hidden', !fp || picking); if (fp !== askSeen) { askSeen = fp; put('#askfp', 'textContent', fp); }
}
async function answerAsk(yes) {
  const fp = askSeen; if (!fp) return;
  if (yes && !await ask(T('core.allow_ask') + '\n\n' + fp, { ok: T('core.allow') })) return;
  put('#askyes', 'disabled', true); put('#askno', 'disabled', true);
  const r = await api(yes ? '/api/adb/allow' : '/api/adb/deny', { fingerprint: fp });
  put('#askyes', 'disabled', false); put('#askno', 'disabled', false);
  if (r) toast(yes ? (r.result === 'allowed' ? T('core.device_allowed') : T('core.allow_failed')) : T('core.declined'));
  askAt = 0; checkAsk();
}
$('#askyes').addEventListener('click', () => answerAsk(true));
$('#askno').addEventListener('click', () => answerAsk(false));

// ---------- "Look at the TV": full screen while the TV is asking whether to allow debugging ----------
let allowLater = false;                                               // "Later" was pressed: not again for this round of asking
function allowAlert() {
  const d = $('#allow'), asking = ADB.state === 'unauthorized';
  if (!asking) { allowLater = false; if (d.open) d.close(); return; }
  if (d.open || allowLater || picking) return;
  if ($('#assist').open) $('#assist').close();
  d.showModal();
}
$('#allowlater').addEventListener('click', () => { allowLater = true; $('#allow').close(); });
$('#allow').addEventListener('cancel', () => { allowLater = true; });   // Esc

// ---------- the page from the TV: one code, shown on the TV, lets this browser in for good ----------
function setLocked(on, s) {
  const m = document.querySelector('main'); if (on === m.classList.contains('locked')) return;
  m.classList.toggle('locked', on); put('#pair', 'hidden', !on);
  if (on) { if (screenOn) stopScreen(); m.classList.remove('wide'); put('#name', 'textContent', (s && s.name) || 'TV Remote'); put('#info', 'textContent', T('core.not_paired_yet')); put('#pairf', 'hidden', true); put('#pairerr', 'textContent', ''); }
  else showTab(currentTab);
}
$('#pairstart').addEventListener('click', async () => {
  const r = await api('/api/pair/start', {}); if (!r) return;
  put('#pairf', 'hidden', false); put('#pairerr', 'textContent', T(r.shown === false ? 'core.code_open_setup' : 'core.code_2min'));   // false: the TV could not put it up
  $('#paircode').value = ''; $('#paircode').focus();
});
$('#pairf').addEventListener('submit', async (e) => {
  e.preventDefault();
  const r = await fetch('/api/pair/finish', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: $('#paircode').value }) }).then(async (x) => ({ ok: x.ok, ...(await x.json().catch(() => ({}))) })).catch(() => ({}));
  if (r.ok) { put('#pairerr', 'textContent', ''); refresh(); return; }
  put('#pairerr', 'textContent', r.error === 'wrong' ? T('core.wrong_code') : T('core.code_expired')); if (r.error !== 'wrong') put('#pairf', 'hidden', true); $('#paircode').select();
});

// ---------- the TV's page on a phone: it can live on the home screen like an app (manifest.json) ----------
// Said once; a browser that is already showing it as an app, or a computer, is not told.
function homeHint() {
  const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone;
  let seen = false; try { seen = !!localStorage.getItem('homehint'); } catch {}
  if (CORE !== 'tv' || window.ONTV || standalone || seen || !matchMedia('(pointer:coarse)').matches) return;
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  put('#homehow', 'textContent', ios ? T('core.home_ios') : T('core.home_chrome'));
  put('#homehint', 'hidden', false);
}
$('#homeok').addEventListener('click', () => { put('#homehint', 'hidden', true); try { localStorage.setItem('homehint', '1'); } catch {} });

// ---------- the TV web page: a hand on the remote wakes the TV's input session ----------
// TV Setup stops its scrcpy process after 3 minutes nobody uses it (polls do not count); this wakes it before the first key.
let warmAt = -1e9;
function warmTv() {
  if (CORE !== 'tv' || stopped || document.hidden || currentTab !== 'remote' || performance.now() - warmAt < 20000) return;
  warmAt = performance.now(); fetch('/api/warm', { method: 'POST' }).catch(() => {});
}
// once every script has run: a key reaching the page while it loads (the TV's PIN typed just before Manage opened) found
// `stopped` / `currentTab` not declared yet and threw "is not defined"
document.addEventListener('DOMContentLoaded', () => {
  ['pointerdown', 'keydown'].forEach((ev) => document.addEventListener(ev, warmTv, true));
  document.addEventListener('visibilitychange', warmTv);
});

// ---------- this device slept (a laptop's lid, a phone's screen) ----------
// Its timers stood still and its streams broke under it: not the TV's fault. A stream that ends right after gets one more go
// instead of "the TV stopped sending" (what Deskhub learnt: a watchdog must not count its own device's sleep).
let tickAt = Date.now(), wokeAt = -1e9;
const wakeUsed = {};                                                 // per stream: the picture and the sound each get their go
const slept = () => Date.now() - tickAt > 10000;                      // ticks every 2 s; a hidden tab's slower ticks count too (harmless)
setInterval(() => { if (slept()) wokeAt = performance.now(); tickAt = Date.now(); }, 2000);
/** True once per sleep for [what], a stream that broke within 20 s of waking: start it again rather than give up. */
function afterWake(what) {
  if (slept()) { wokeAt = performance.now(); tickAt = Date.now(); }  // the stream noticed before the tick did
  if (performance.now() - wokeAt > 20000 || wakeUsed[what] === wokeAt) return false;
  wakeUsed[what] = wokeAt; return true;
}

// A line in the app's own log (About → Send the log): what went wrong inside the page, where nobody sees its console
function pageSaid(said) { try { fetch('/api/wirelog', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ said: said + ' · ' + navigator.userAgent.replace(/^Mozilla\/5\.0 /, '') }) }).catch(() => {}); } catch {} }
