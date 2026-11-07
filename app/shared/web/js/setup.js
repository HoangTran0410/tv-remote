// optimise, language, quit, and the TV tab (saved TVs, scan, first-time setup)
// ---------- one-click TV setup (Setup.kt) ----------
async function runSetup(mode, choice) {
  if (mode === 'undo' && !await ask(T('setup.undo_ask'), { ok: T('setup.undo_ok') })) return;
  const btns = [$('#setupapply'), $('#setupundo')], log = $('#setuplog');
  btns.forEach((b) => { b.disabled = true; }); log.hidden = false; log.textContent = T('setup.running');
  let j = await api('/api/setup', { mode, lang: LANG, ...(choice || {}) });
  for (let misses = 0; j && j.status === 'running' && misses < 10;) {          // ten failed polls in a row: give up waiting, not the TV
    await sleep(1000); const r = await api('/api/job?id=' + j.id, null, true);
    if (!r) { misses++; continue; }
    misses = 0; j = r; log.textContent = j.log || T('setup.running'); log.scrollTop = log.scrollHeight;
  }
  if (j && j.status === 'running') j = null;
  if (j) { log.textContent = (j.log || '') + (j.status === 'done' ? '\n' + T('setup.done_line') : '\n❌ ' + T('setup.error_msg', { msg: friendly(j.msg) })); toast(j.status === 'done' ? T('setup.finished') : T('setup.error_msg', { msg: friendly(j.msg) })); appsStale(); applyAdb(); }
  else log.textContent += '\n❌ ' + T('setup.lost_connection');
  btns.forEach((b) => { b.disabled = false; }); restoreState();
}
// "Optimize TV" asks first what to do: what this TV has, suggested apps ticked, any other app the person wants off
function optRow(name, sub, checked, data) {
  const box = el('input', { type: 'checkbox', checked }); Object.assign(box.dataset, data);
  return el('label', { className: 'optrow' }, box, el('span', {}, el('b', { textContent: name }), sub ? el('small', { textContent: sub }) : null));
}
async function chooseOptimize() {
  const body = $('#optbody'); body.replaceChildren(el('p', { className: 'muted', textContent: T('setup.optimize_looking') }));
  put('#optgo', 'disabled', true); $('#optdlg').showModal();
  const p = await api('/api/setup/plan');
  if (!p) { body.replaceChildren(el('p', { textContent: T('setup.optimize_unreadable') })); return; }
  const usingHome = p.home === 'dev.tvremote.home';
  const parts = [el('h3', { textContent: T('setup.general') }),
    optRow(T('setup.anim_off'), p.anim ? T('setup.anim_faster') : T('setup.anim_already'), true, { what: 'anim' }),
    optRow(usingHome ? T('setup.tvhome_in_use') : T('setup.tvhome_use'), (p.tvhome ? T('setup.tvhome_note') : T('setup.tvhome_note_dl')), true, { what: 'home' })];
  if (p.suggest.length) parts.push(el('h3', { textContent: T('setup.turn_off_suggested') }), ...p.suggest.map((a) => optRow(a.label, a.pkg, true, { pkg: a.pkg })));
  if (p.apps.length) {
    const list = el('div', { className: 'optbox' }, ...p.apps.map((a) => optRow(a.label, a.pkg, false, { pkg: a.pkg })));
    const filter = el('input', { className: 'filter', type: 'search', placeholder: T('setup.filter_by_name'), oninput: () => {
      const q = filter.value.trim().toLowerCase(); list.querySelectorAll('.optrow').forEach((r) => { r.hidden = q && !r.textContent.toLowerCase().includes(q); });
    } });
    parts.push(el('h3', { textContent: T('setup.turn_off_more') }), el('p', { className: 'note', textContent: T('setup.turn_off_note') }), p.apps.length > 7 ? filter : null, list);
  }
  body.replaceChildren(...parts.filter(Boolean)); put('#optgo', 'disabled', false);
}
$('#optgo').addEventListener('click', () => {
  const boxes = [...$('#optbody').querySelectorAll('input[type=checkbox]')], on = (w) => boxes.some((b) => b.dataset.what === w && b.checked);
  const choice = { anim: on('anim'), home: on('home'), disable: boxes.filter((b) => b.dataset.pkg && b.checked).map((b) => b.dataset.pkg) };
  $('#optdlg').close(); runSetup('apply', choice);
});
$('#optclose').addEventListener('click', () => $('#optdlg').close());
$('#optcancel').addEventListener('click', () => $('#optdlg').close());
$('#setupapply').addEventListener('click', () => chooseOptimize());
$('#setupundo').addEventListener('click', () => runSetup('undo'));
// the script that does Undo with only adb: offered while there is something to restore; the server names the file and says "download"
async function restoreState() {
  if (ADB.state !== 'ready') return put('#setuprestore', 'hidden', true);
  const s = await api('/api/setup/restore', null, true); put('#setuprestore', 'hidden', !(s && s.has));
}
onTvChange(() => put('#setuprestore', 'hidden', true));
$('#setuprestore').addEventListener('click', () => {
  const a = el('a', { href: 'api/setup/restore.sh', download: '' }); document.body.append(a); a.click(); a.remove();
});

// ---------- settings: theme (js/theme.js) and language (i18n.js: one button per file in LANGS, each in its own words) ----------
$('#langpick').append(...Object.entries(LANGS).map(([k, name]) => { const b = el('button', { textContent: name, lang: k }); b.dataset.v = k; return b; }));
function showPicks() {
  $('#themepick').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === THEME));
  $('#langpick').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === LANG));
}
$('#themepick').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) { setTheme(b.dataset.v); showPicks(); } });
// a tap under the finger on phones (Chrome on Android; Safari on iPhone has no vibration for pages)
put('#buzzgroup', 'hidden', !CAN_BUZZ);
const showBuzz = () => $('#buzzpick').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === (buzzOn() ? '1' : '0')));
showBuzz();
$('#buzzpick').addEventListener('click', (e) => { const b = e.target.closest('button'); if (!b) return; try { localStorage.setItem('buzz', b.dataset.v); } catch {} showBuzz(); buzz(); });
const showKbdAuto = () => $('#kbdautopick').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === (kbdAutoOn() ? '1' : '0')));
showKbdAuto();
$('#kbdautopick').addEventListener('click', (e) => { const b = e.target.closest('button'); if (!b) return; try { localStorage.setItem('kbdauto', b.dataset.v); } catch {} showKbdAuto(); });
$('#langpick').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b && b.dataset.v !== LANG) setLang(b.dataset.v); });
showPicks();
if (window.LANG_KEEP && window.LANG_KEEP.settings) setTimeout(() => $('#aboutbtn').click(), 0);   // it reloaded for the new language: back where it was

// ---------- about ----------
$('#aboutbtn').addEventListener('click', async () => {
  showPicks(); $('#about').showModal();
  const r = await fetch('/api/about').then((x) => x.json()).catch(() => null);
  if (r && r.version) put('#aboutver', 'textContent', r.version + (r.released ? ' · ' + r.released : ''));
  // scan with a phone: the Android app, and (when the TV Setup app is serving it) this TV's own remote page
  $('#qrs').replaceChildren(...[qrBlock(T('setup.qr_app'), T('setup.qr_app_long'), APP_APK),
    r && r.tv_web ? qrBlock(T('setup.qr_remote'), T('setup.qr_remote_note'), r.tv_web) : null].filter(Boolean));
});
const APP_APK = 'https://tvremote.pages.dev/dl/tv-remote.apk';         // the site sends it on to the latest release: short to read out, a small QR
// a QR with its title and line; the link under it unless [hide] (an invite is for scanning, not for reading out)
const qrBlock = (title, note, link, hide) => { const s = qrSvg(link, 170); return s ? el('div', { className: 'qr' }, s, el('b', { textContent: title }), ...[].concat(note).flatMap((n, i) => [i ? el('br') : null, el('span', { className: 'muted', textContent: n })]).filter(Boolean),   // note: one line, or several kept apart
  hide ? null : el('br'), hide ? null : el('a', { href: link, target: '_blank', rel: 'noopener', textContent: link.replace(/^https?:\/\//, '').replace(/^github\.com\/.*\//, '') })) : null; };   // the file's name: the whole release link is wider than the box

// ---------- share (⋯ More keys → Chia sẻ): a QR that opens this TV's web page already paired, and one for the phone app ----------
// The invite is one-time and lasts ten minutes (Invite.kt); without TV Setup (or an older one) only the app's QR is shown.
// It carries what the one who scans may do (Scope.kt in TV Setup); picking another makes a new QR, as one invite is out at a time.
const SCOPE_WORDS = {
  full: [T('setup.scope_full'), T('setup.scope_full_tip')],
  remote: [T('setup.scope_remote'), T('setup.scope_remote_tip')],
  view: [T('setup.scope_view'), T('setup.scope_view_tip')],
};
const scopeChips = (now, fn) => UI.chips(Object.keys(SCOPE_WORDS).map((k) => ({ label: SCOPE_WORDS[k][0], title: SCOPE_WORDS[k][1], on: k === now, fn: () => fn(k) })));
// two tabs: the TV (a one-time QR, with what the scanner may do) and the app (the APK's QR), as on the phone (Share.kt)
async function shareDialog() {
  let scope = 'remote';                                               // what a guest on the sofa needs; the owner can pick more
  const r = await api('/api/share', { scope }) || {};
  const qr = el('div', { className: 'qrs' }), pick = el('div'), how = el('div');
  let simple = false, last = r.url;                                   // simple: the link opens straight in the Simple remote (remote.js, ?simple=1)
  const warn = { tailscale: T('setup.warn_tailscale'), tailscale_only: T('setup.warn_tailscale_only') }[r.warn];
  const tv = el('div', { className: 'sharetab' },
    warn ? UI.warn(warn) : null,
    r.setup === 'missing' ? UI.note(T('setup.share_no_setup')) : null,
    r.setup === 'old' ? UI.note(T('setup.share_old_setup')) : null,
    r.setup === 'old' ? UI.actions(UI.btn(T('setup.update_setup'), () => { d.close(); return updateSetup(); }, 'small')) : null,
    r.url ? UI.note(T('setup.scanner_may')) : null, r.url ? pick : null, r.url ? how : null, qr);
  const app = el('div', { className: 'sharetab', hidden: true }, el('div', { className: 'qrs' }, qrBlock(T('setup.qr_app'), T('setup.qr_app_short'), APP_APK)));
  const tabs = el('div', { className: 'seg' });
  const show = (k) => {
    tabs.replaceChildren(...[['tv', T('setup.share_tv')], ['app', T('setup.share_app')]].map(([id, label]) =>
      el('button', { className: id === k ? 'on' : '', textContent: label, onclick: () => show(id) })));
    tv.hidden = k !== 'tv'; app.hidden = k !== 'app';
  };
  const d = el('dialog', { className: 'ask share' }, el('div', { className: 'card' },
    UI.head(T('setup.share_title'), el('button', { className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
    tabs, tv, app));
  const wifi = r.warn !== 'tailscale_only';                           // the link is the TV's Wi-Fi address: say so, unless only Tailscale reaches it (warned above)
  const withSimple = (url) => { if (!simple || scope === 'view') return url; try { const u = new URL(url); u.searchParams.set('simple', '1'); return String(u); } catch { return url; } };
  const draw = (url) => { last = url; how.hidden = scope === 'view'; qr.replaceChildren(...[url ? qrBlock(scope === 'view' ? T('setup.qr_view') : T('setup.qr_open'),
    [wifi ? T('setup.same_wifi') : null, T('setup.one_time')].filter(Boolean), withSimple(url), true) : null].filter(Boolean)); };
  const howChips = () => how.replaceChildren(UI.chips([{ label: T('setup.share_full'), on: !simple, fn: () => { simple = false; howChips(); draw(last); } },
    { label: T('rkey.simple'), title: T('setup.share_simple_tip'), on: simple, fn: () => { simple = true; howChips(); draw(last); } }]));   // the same one-time invite: only the link's ending changes
  howChips();
  const choose = async (k) => {
    if (k === scope) return; scope = k; pick.replaceChildren(scopeChips(scope, choose));
    qr.classList.add('stale');                                        // the old QR fades while the TV makes the new invite: never scanned under the wrong choice
    const n = await api('/api/share', { scope, again: true });
    if (k !== scope) return;                                          // another chip was tapped meanwhile: its answer draws
    qr.classList.remove('stale'); draw(n && n.url);                   // on a failure no QR rather than the last one under the wrong choice
  };
  pick.replaceChildren(scopeChips(scope, choose)); draw(r.url); show('tv');
  d.addEventListener('close', () => d.remove());
  document.body.append(d); d.showModal();
}
if (CORE === 'pc') fetch('/api/about').then((x) => x.json()).then((r) => {
  if (r.version) put('#ver', 'textContent', 'TV Remote ' + r.version + (r.released ? ' · ' + r.released : ''));
  if (r.stale) put('#stale', 'hidden', false);                            // its code was updated after it started: new buttons would answer "not found"
}).catch(() => {});

// ---------- updates of the computer app (Updater.kt): it checks and downloads by itself; this shows it and starts the swap ----------
let updLater = false, updPoll = null;
function showUpdate(u) {
  const b = $('#update');
  b.hidden = !u || !u.version || (updLater && u.state !== 'installing');
  if (b.hidden) return;
  put('#updtitle', 'textContent', T('setup.update_title', { version: u.version, current: u.current }));
  put('#updnote', 'textContent', u.state === 'downloading' ? T('setup.update_downloading', { pct: u.pct })
    : u.state === 'installing' ? T('setup.update_installing')
    : u.state === 'error' ? T('setup.update_failed', { err: friendly(u.error) })
    : !u.auto ? T(u.move ? 'setup.update_move' : 'setup.update_manual')
    : T('setup.update_ready'));
  put('#updgo', 'textContent', u.auto ? T('page.update') : u.move ? T('setup.move_btn') : T('setup.download_update'));
  put('#updacts', 'hidden', u.state === 'installing' || u.state === 'downloading' && !updPoll);
  clearTimeout(updPoll); updPoll = null;
  if (u.state === 'downloading' || u.state === 'installing') updPoll = setTimeout(pollUpdate, 1000);
}
const pollUpdate = () => fetch('/api/update').then((x) => x.json()).then(showUpdate).catch(() => { updPoll = setTimeout(pollUpdate, 2000); });   // gone while it swaps
if (CORE === 'pc') { pollUpdate(); setInterval(() => { if (!updPoll) pollUpdate(); }, 30 * 60000); }
$('#updlater').addEventListener('click', () => { updLater = true; put('#update', 'hidden', true); });
$('#updgo').addEventListener('click', async () => {
  const u = await api('/api/update'); if (!u) return;
  if (!u.auto && u.move) { const r = await api('/api/update', { do: 'move' }); if (r) put('#updnote', 'textContent', T('setup.update_installing')); return; }   // Mover.kt: copies, quits, opens the copy
  if (!u.auto) { window.open(u.url, '_blank'); return; }
  if (!await ask(T('setup.update_ask', { version: u.version }) + updBrief(u), { ok: T('updater.update_to', { version: u.version }) })) return;
  const r = await api('/api/update', { do: 'install' }); if (r) showUpdate(r);
});
// the notes in the page's language, made short by the core (Release.brief: one body holds every app and both languages)
function updBrief(u) {
  const b = u.brief && (u.brief[window.LANG === 'vi' ? 'vi' : 'en']);
  if (!b || !b.items || !b.items.length) return '';
  return '\n\n' + T('updater.whats_new', { version: u.version }) + '\n' + b.items.map((s) => '• ' + s).join('\n') + (b.more ? '\n' + T('updater.more', { n: b.more }) : '');
}
$('#updcheck').addEventListener('click', async () => {
  put('#updcheckmsg', 'textContent', T('setup.checking'));
  const u = await api('/api/update', { do: 'check' });
  if (!u) { put('#updcheckmsg', 'textContent', ''); return; }
  updLater = false; showUpdate(u);
  put('#updcheckmsg', 'textContent', !u.packaged ? T('setup.update_source') : u.error ? T('setup.update_check_failed', { err: friendly(u.error) })
    : u.version ? T('setup.update_found', { version: u.version }) : T('setup.update_latest', { current: u.current }));
});
// ---------- Home Assistant · Phím tắt (Hook.kt): its own switch and token, shown only to the owner ----------
let hookNow = null;
function hookShow(h) {
  hookNow = h;
  $('#hookpick').querySelectorAll('button').forEach((b) => b.classList.toggle('on', (b.dataset.v === 'on') === !!(h && h.on)));
  put('#hookbody', 'hidden', !(h && h.on));
  if (h && h.on) $('#hookurl').value = h.shortcut;
}
async function hookLoad() {
  put('#hookgroup', 'hidden', SCOPE !== 'full');
  if (SCOPE !== 'full' || CORE === 'phone') return;                    // the phone has no hook (#hookgroup data-for="pc tv"): its 404 was asked on every About
  hookShow(await api('/api/hook/config', null, true));
}
const hookCopy = async (text, done) => { if (await clipCopy(text)) toast(done); else { $('#hookurl').select(); toast(T('setup.copy_failed')); } };   // clipCopy: the TV's page is plain http
$('#aboutbtn').addEventListener('click', hookLoad);
$('#hookpick').addEventListener('click', async (e) => {
  const b = e.target.closest('button'); if (!b || (b.dataset.v === 'on') === !!(hookNow && hookNow.on)) return;
  const h = await api('/api/hook/config', { do: b.dataset.v });
  if (h) hookShow(h);
});
$('#hookcopyurl').addEventListener('click', () => hookNow && hookCopy(hookNow.shortcut, T('setup.copied_link')));
$('#hookcopyyaml').addEventListener('click', () => hookNow && hookCopy(hookNow.yaml, T('setup.copied_yaml')));
$('#hooknew').addEventListener('click', async () => {
  if (!await ask(T('setup.newtoken_ask'), { ok: T('setup.newtoken_ok') })) return;
  const h = await api('/api/hook/config', { do: 'new' });
  if (h) { hookShow(h); toast(T('setup.newtoken_done')); }
});
// ---------- Send anonymous stats (Census.kt): one switch, on by default ----------
const censusShow = (c) => $('#censuspick').querySelectorAll('button').forEach((b) => b.classList.toggle('on', (b.dataset.v === '1') === !(c && c.on === false)));
$('#aboutbtn').addEventListener('click', async () => { put('#censusgroup', 'hidden', SCOPE !== 'full'); if (SCOPE === 'full') censusShow(await api('/api/census', null, true)); });
$('#censuspick').addEventListener('click', async (e) => {
  const b = e.target.closest('button'); if (!b) return;
  const c = await api('/api/census', { on: b.dataset.v === '1' });
  if (c) censusShow(c);
});
$('#aboutclose').addEventListener('click', () => $('#about').close());
// Settings' long parts fold to a title and one line (the user, 2026-11-03: too much text at once): what is chosen
// (Allow / Off), else what it says now, else its first note. One observer on the dialog: the renderers above stay as they are.
function sectSums() {
  for (const d of document.querySelectorAll('#about details.sect')) {
    const pick = d.querySelector('.places button.on'), st = d.querySelector('p.state:not(:empty)'), ver = d.querySelector('#aboutver'), note = d.querySelector('p.note');
    const t = ((pick && pick.textContent) || (st && !st.closest('[hidden]') && st.textContent) || (ver && ver.textContent) || (note && note.textContent) || '').trim();
    const s = d.querySelector(':scope > summary .sum'); if (s && s.textContent !== t) s.textContent = t;
  }
}
new MutationObserver(sectSums).observe($('#about'), { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['class', 'hidden'] });
sectSums();
$('#sendlog').addEventListener('click', sendWireLog);
// ---------- Let support look (SupportRelay), 30 min, read only: this computer app (About), or the TV (the TV card; TV Setup, over ADB but on the TV) ----------
const supPath = (what) => '/api/support/' + (what === 'app' ? 'app' : 'tv');
async function supShow(what, s) {                                     // the code to read out, with Stop
  if (!s) return;
  if (s.failed) { toast(s.failed === 'none_left' ? T('support.none_left') : s.failed === 'no_net' ? T(what === 'app' ? 'support.no_net_here' : 'support.no_net') : friendly(s.failed)); return; }
  supMark(what, s);
  if (!s.open) return;
  if (!await ask(T('support.code_title') + ': ' + s.code.replace(/(\d{4})(\d{4})/, '$1 $2') + '\n\n' + T('support.read_out_stop', { min: s.min }), { ok: T('support.stop'), danger: true, no: T('common.close') })) return;
  supMark(what, await api(supPath(what), { act: 'stop' }));
  toast(T('support.stopped'));
}
function supMark(what, s) {                                           // the button says the code while support looks
  const b = $(what === 'app' ? '#supapp' : '#suptv');
  const text = s && s.open ? T('support.stop_sub', { code: s.code, min: s.min }) : T(what === 'app' ? 'support.this_app' : 'support.short');
  if (what === 'app') b.textContent = text;
  else b.replaceChildren(el('span', { className: 'ico', textContent: '👁️' }), el('span', { className: 'lbl', textContent: ' ' + text }));   // the top bar's icon + label
  b.classList.toggle('on', !!(s && s.open));
}
for (const what of ['app', 'tv']) $(what === 'app' ? '#supapp' : '#suptv').addEventListener('click', async () => {
  const now = await api(supPath(what), null, true);
  if (now && now.open) return supShow(what, now);
  const said = await askSaid(); if (!said) return;
  supShow(what, await api(supPath(what), { act: 'start', problem: said.problem, text: said.text }));
});
$('#aboutbtn').addEventListener('click', async () => {             // the phone's own row is native (InfoScreens), the TV's is in the TV card
  if (CORE === 'pc') supMark('app', await api('/api/support/app', null, true));
});
$('#about').addEventListener('click', (e) => { if (e.target === $('#about')) $('#about').close(); });   // click outside the box

// ---------- stop the local server from the page (`stopped` lives in core.js) ----------
$('#quit').addEventListener('click', async () => {
  if (!await ask(T('setup.quit_ask'), { ok: T('setup.quit_ok') })) return;
  $('#about').close();
  if (screenOn) stopScreen();
  if (!await api('/api/shutdown', {})) return;
  serverStopped();
  document.querySelector('main').replaceChildren(el('section', { className: 'card stopped' },
    el('h1', { textContent: T('setup.stopped_title') }), el('p', { className: 'muted', textContent: T('setup.stopped_note') })));
});

// ---------- TVs: saved list, discovery, first-time setup ----------
function tvChanged() {                                                // another TV: forget everything cached about the old one
  if (screenOn) stopScreen();
  if (listenOn) stopListen();
  TV_RESETS.forEach((f) => f());
  if (currentTab === 'tv') loadSteps();
  if (picking) loadTvs();
}
function connecting(name, host) {                                   // covers the remote until the TV answers (see refresh)
  put('#conname', 'textContent', name); put('#constep', 'textContent', T('setup.finding_tv', { host }));
  if (!$('#connecting').open) $('#connecting').showModal();
}
$('#concancel').addEventListener('click', () => { pickCancel(); $('#connecting').close(); refresh(); });
$('#connecting').addEventListener('cancel', (e) => { e.preventDefault(); $('#concancel').click(); });   // Esc: the same as Cancel
async function selectTv(host, name, kind) {
  const r = await api('/api/tv/select', kind ? { host, kind } : { host }); if (!r) return;
  if (r.host) host = r.host;                                         // "ip:5877" typed: the TV is the address, the port is kept for ADB
  connecting(name || host, host); pickGo(host); showTab('remote'); await sleep(1200); await refresh(); loadSteps();
}
$('#switchtv').addEventListener('click', () => { pickOpen(); refresh(); });
$('#pickback').addEventListener('click', () => { pickLeave(); refresh(); });
const openTv = (host, current, name) => { if (host === current) { pickGo(host); refresh(); } else selectTv(host, name); };
// A TV asleep in deep standby answers nothing, so its remote cannot open: woken from the list (Wake-on-LAN, Wake.kt), then opened
async function wakeTv(b, t) {
  b.disabled = true; toast(T('remote.waking'));
  const r = await api('/api/wake', { host: t.host }); b.disabled = false; if (!r) return;
  toast(r.ok ? T('remote.tv_ready') : T('remote.tv_not_up'));
  if (r.ok) openTv(t.host, null, t.name);
}
let savedTvs = [];                                                     // the saved list, for the scan to know a TV's other address
async function loadTvs() {
  const r = await api('/api/tvs', null, true); if (!r) return;
  savedTvs = r.tvs;
  const rows = r.tvs.map((t) => el('div', { className: 'item', onclick: (e) => { if (!e.target.closest('button')) openTv(t.host, r.current, t.name); } },
    el('div', { className: 'name' }, el('span', { textContent: t.name || t.host }), t.host === r.current && cameFromTv ? el('span', { className: 'badge ok', textContent: T('setup.in_use') }) : null,
      el('small', { textContent: t.host + (t.lan && t.lan !== t.host ? ' · ' + T('setup.at_home', { lan: t.lan }) : '') })),
    el('div', { className: 'acts' },
      setLabel(el('button', { className: 'small primary', onclick: () => openTv(t.host, r.current) }), '▶', OPEN_TV),
      CORE === 'pc' ? setLabel(el('button', { className: 'small', title: T('remote.wake_the_tv_wake'), onclick: (e) => wakeTv(e.currentTarget, t) }), '☀️', T('remote.wake')) : null,
      setLabel(el('button', { className: 'small danger', onclick: async () => { if (await ask(T('setup.forget_ask', { name: t.name || t.host }), { ok: T('setup.delete'), danger: true })) { await api('/api/tv/remove', { host: t.host }); await refresh(); loadTvs(); loadSteps(); } } }), '🗑', T('setup.delete'), true))));
  $('#tvlist').replaceChildren(...rows);
  put('#savedcard', 'hidden', !rows.length); $('#pickcols').classList.toggle('solo', !rows.length);   // nothing saved: no empty box, just the search
  if (typeof fleetCheck === 'function') fleetCheck(rows.length);   // fleet.js comes later in the page: a list drawn before it loads is counted there
  if (typeof tvstatMount === 'function') tvstatMount(r.tvs, rows);  // tvstatus.js (later in the page; mounts a list drawn before it itself): on / standby / off under each name
}
function scanRow(f) {
  const native = f.kind && f.kind !== 'android';                      // LG / Samsung: found by SSDP, no ADB to report
  const row = el('div', { className: 'item', onclick: (e) => { if (!e.target.closest('button')) selectTv(f.ip, f.name, f.kind); } },
    el('div', { className: 'name' }, el('span', { textContent: f.name || f.ip }),
      el('div', { className: 'badges' },                              // a line of their own: wrapped beside a long name they looked lopsided
      el('span', { className: 'badge' + (f.remote ? ' ok' : ''), textContent: native ? 'TV ' + KIND_NAME[f.kind] : f.remote ? 'Android TV' : T('setup.adb_only') }),
      native ? el('span', { className: 'badge', textContent: T(f.kind === 'vidaa' ? 'setup.experimental_vidaa' : 'setup.experimental') }) : null,
      native ? null : f.adb ? el('span', { className: 'badge ok', textContent: T('setup.adb_on') }) : el('span', { className: 'badge', textContent: T('setup.adb_off') })),
      el('small', { textContent: f.ip + (f.via === 'tailscale' ? ' · ' + T('setup.via_tailscale') : ' · ' + T('setup.same_network')) + (f.tailscale ? ' · Tailscale ' + f.tailscale : '') })),
    el('div', { className: 'acts' }, setLabel(el('button', { className: 'small primary', onclick: () => selectTv(f.ip, f.name, f.kind) }), '▶', OPEN_TV)));
  row.dataset.ip = f.ip; return row;
}
// The scan runs on the computer; asked again every 400 ms, each TV shows up as soon as it answers instead of after the
// whole network (and a second pass when the first finds nothing)
$('#scan').addEventListener('click', async () => {
  const list = $('#scanlist'), done = UI.busy($('#scan'), T('setup.scanning')); list.replaceChildren(); put('#scaninfo', 'textContent', ''); put('#scannone', 'hidden', true);
  let r = await api('/api/discover?new=1');
  while (r) {
    for (const f of r.found) {                                        // one row per TV: a saved one is known by both its addresses
      const tv = savedTvs.find((t) => t.host === f.ip || t.lan === f.ip || (f.tailscale && t.host === f.tailscale));
      const key = tv ? tv.host : f.ip;
      if (!list.querySelector(`[data-tv="${key}"]`)) { const row = scanRow(f); row.dataset.tv = key; list.append(row); }
    }
    if (r.done) break;
    await sleep(400);
    r = await api('/api/discover', null, true);
  }
  done(); if (!r) return;
  put('#scaninfo', 'textContent', r.found.length ? '' : T('setup.no_tv_found'));
  put('#scannone', 'hidden', !!r.found.length);
});
// Nothing found (often: TV Setup not on the TV yet, or the TV off): install it by its Downloader code, type the address, or scan again
$('#scaninstall').addEventListener('click', async () => {
  if (await ask(await dlTip(), { ok: T('setup.none_guide') })) window.open('https://tvremote.pages.dev' + (LANG === 'vi' ? '' : '/en') + '/guide#install-tv', '_blank');
});
$('#scanip').addEventListener('click', () => $('#ipin').focus());
$('#scanagain').addEventListener('click', () => $('#scan').click());
$('#ipf').addEventListener('submit', (e) => { e.preventDefault(); const ip = $('#ipin').value.trim(); if (ip) selectTv(ip); });
$('#setuprefresh').addEventListener('click', () => loadSteps());

// ---------- turning ADB on for a TV that has it off, with the remote buttons alone (the phone app's assistant) ----------
const HOMES = [['Projectivy Launcher', 'com.spocky.projengmenu'], ['ATV Launcher', 'ca.dstudio.atvlauncher.free'], ['FLauncher', 'me.efesser.flauncher']];
let assistGen = 0;
function assistShow(title, ...kids) {
  assistGen++; put('#assisttitle', 'textContent', title); $('#assistbody').replaceChildren(...kids.filter(Boolean));
  if (!$('#assist').open) $('#assist').showModal();
}
const choice = (title, sub, fn, cls = '') => el('button', { className: 'choice ' + cls, onclick: fn }, el('b', { textContent: title }), sub ? el('small', { textContent: sub }) : null);
const say = (text) => el('p', { textContent: text });
// A how-to as numbered steps, one short line each: a wall of text put people off (2026-10-23). The words to look for are
// bold, a Settings path reads as arrows; a paragraph after a blank line is a small note under the steps.
const rich = (t) => t.split(/(“[^”]*”| → )/).filter(Boolean).map((p) => p === ' → ' ? el('span', { className: 'arr', textContent: '→' })
  : p.startsWith('“') ? el('b', { textContent: p.slice(1, -1) }) : p);
function steps(text) {
  const [list, ...notes] = text.split('\n\n');
  return [el('ol', { className: 'asteps' }, ...list.split('\n').map((l) => el('li', {}, el('span', {}, ...rich(l.replace(/^\d+\.\s*/, '')))))),
    ...notes.map((n) => el('p', { className: 'anote' }, ...rich(n)))];
}
const fold = (title, text) => el('details', { className: 'afold' }, el('summary', { textContent: title }), ...steps(text));
// Turning ADB on by hand. A TV whose maker hides the switch somewhere else (Xiaomi's China models, TCL's) is told from its
// name and model: its own steps come first, the usual way folded under them; the other makers' ways wait under one fold.
const BRANDS = { xiaomi: [/xiaomi|\bmi ?tv|mitv|redmi/i, 'setup.xiaomi_title', 'setup.xiaomi_steps'], tcl: [/\btcl\b|ffalcon/i, 'setup.tcl_title', 'setup.tcl_steps'],
  shield: [/\bshield\b|nvidia/i, 'setup.shield_title', 'setup.shield_steps'] };
function tvBrand(more = '') {
  const d = STATE.device || {}, words = [STATE.name, d.manufacturer, d.model, more].join(' ');
  return Object.keys(BRANDS).find((b) => BRANDS[b][0].test(words)) || '';
}
function adbGuide(more = '') {
  const b = tvBrand(more), mine = BRANDS[b];
  return [
    ...(mine ? [el('p', { className: 'amore', textContent: T('setup.for_this_tv', { name: T(mine[1]) }) }), ...steps(T(mine[2])), fold(T('setup.adb_general'), T('setup.adb_steps'))]
      : steps(T('setup.adb_steps'))),
    el('p', { className: 'amore', textContent: T('setup.adb_more') }),
    fold(T('setup.adb_fix_title'), T('setup.adb_fix')),
    el('details', { className: 'afold' }, el('summary', { textContent: T('setup.adb_other_tvs') }),
      ...Object.keys(BRANDS).filter((k) => k !== b).map((k) => fold(T(BRANDS[k][1]), T(BRANDS[k][2])))),
  ];
}
// Its own dialog: the TV page's ADB notice, and the phone app's help (AdbHelp.kt adbSteps, [more] = the maker and model the TV
// gave over the remote protocol while ADB is off)
function showAdbGuide(more = '') { assistShow(T('core.tvb_help_btn'), ...adbGuide(more)); }
const row = (...kids) => el('div', { className: 'assistrow' }, ...kids.filter(Boolean));
const assist = (do_, extra) => api('/api/assist', { do: do_, ...extra });
$('#assistclose').addEventListener('click', () => $('#assist').close());
$('#assist').addEventListener('close', () => { assistGen++; if (CORE === 'pc') api('/api/assist', { do: 'stop' }, true); });   // only the Computer app has the route: elsewhere a 404 each time   // closed any way (button, Esc): nothing keeps running unseen

let dlCodes = null;                                                   // the Downloader codes (/api/downloader: signed on the site, else packed)
async function dlTip() { dlCodes = dlCodes || await api('/api/downloader', null, true) || {}; return T('setup.dl_tip', { code: dlCodes.setup || '' }); }
async function adbHelp() {
  const tip = await dlTip();
  if (!STATE.connected) {                                             // everything here goes through the remote buttons
    assistShow(T('setup.turn_on_adb'), say(STATE.paired === false ? T('setup.pair_first')
      : T('setup.remote_unreachable')),
      row(act(T('setup.go_pair'), async () => { $('#assist').close(); showTab('tv'); }, 'primary')), say(T('setup.or_by_hand')), ...adbGuide(), say(tip),
      CORE !== 'tv' ? choice(T('setup.wireless_choice'), '', wirelessPair) : null);
    return;
  }
  if (CORE !== 'pc') {                                                // the ways below go through /api/assist, the Computer app's only (Server.kt): a 404 here (census KM7)
    assistShow(T('setup.turn_on_adb'), ...adbGuide(),
      CORE !== 'tv' ? say(tip) : null,                                // on the TV page TV Setup is the one answering: no install tip
      CORE !== 'tv' ? choice(T('setup.wireless_choice'), T('setup.wireless_note'), wirelessPair) : null);
    return;
  }
  assistShow(T('setup.turn_on_adb'),
    choice(T('setup.auto_install'), T('setup.auto_install_note'), () => installSetup(), 'primary'),
    choice(T('setup.by_hand'), T('setup.by_hand_note'), findSettings),
    choice(T('setup.other_home'), T('setup.other_home_note'), otherHome),
    CORE !== 'tv' ? choice(T('setup.wireless_choice'), T('setup.wireless_note'), wirelessPair) : null,
    choice(T('setup.open_play'), '', () => assist('open', { what: 'https://play.google.com/store/apps' })),
    choice(T('setup.open_pkg'), T('setup.open_pkg_note'), async () => { const p = await ask(T('setup.pkg_ask'), { value: 'com.example.app' }); if (p && p.trim()) assist('open', { what: p.trim() }); }),
    choice(T('setup.direct_link'), T('setup.direct_link_note'), installDirect),
    say(tip),
    say(T('setup.adb_done_note')));
}
const WIRELESS_STEPS = T('setup.wireless_steps');
// Wireless debugging (Android 11+): the TV shows a 6-digit code and an IP:port to pair with; the port to connect to is found by itself
async function wirelessPair() {
  const addr = el('input', { type: 'text', placeholder: T('setup.ph_addr'), inputMode: 'decimal', autocomplete: 'off' });
  const code = el('input', { type: 'text', placeholder: T('setup.ph_code6'), inputMode: 'numeric', maxLength: 7, autocomplete: 'off' });
  const port = el('input', { type: 'text', placeholder: T('setup.ph_port'), inputMode: 'numeric', autocomplete: 'off' });
  const status = el('p', { className: 'note' });
  const pair = async () => {
    const m = addr.value.trim().match(/^(?:(.+):)?(\d{2,5})$/);
    if (!m || (code.hidden ? !/^\d{2,5}$/.test(port.value.trim()) : !/^\s*\d{3}\s?\d{3}\s*$/.test(code.value))) {   // paired already: only the connect port is left
      status.textContent = code.hidden ? T('setup.enter_port') : T('setup.enter_addr_code'); return;
    }
    status.textContent = T('setup.pairing');
    const r = await api('/api/adb/pair', { host: m[1] || '', port: +m[2], code: code.hidden ? '' : code.value, connectPort: +port.value || 0 });
    if (!r) { status.textContent = ''; return; }
    if (r.needPort) { status.textContent = T('setup.need_port'); code.value = ''; code.hidden = true; port.hidden = false; port.focus(); return; }
    adbNow({ state: r.state }); $('#assist').close(); toast(T('setup.paired_connected')); await refresh();
  };
  port.hidden = true;
  assistShow(T('setup.wireless_title'), say(WIRELESS_STEPS), UI.line(addr), UI.line(code), UI.line(port), status,
    row(act(T('setup.pair'), pair, 'primary'), act(T('setup.other_ways'), async () => adbHelp())));
  const f = await api('/api/adb/discover', null, true);              // prefilled when the TV announces itself on the network
  const p = f && (f.pairing || []).find((s) => s.ip === f.host || s.self) || f && (f.pairing || [])[0];   // self: TV Setup's own TV (it dials 127.0.0.1)
  if (p && !addr.value) addr.value = p.ip + ':' + p.port;
}
function adbSteps() {
  assistShow(T('setup.steps_title'), say(T('setup.steps_use_keys')), ...adbGuide(),
    row(act(T('setup.connect_now'), async () => { const r = await api('/api/adb/connect', {}); if (r) { adbNow(r); await refresh(); } }, 'primary'), act(T('setup.other_ways'), async () => adbHelp())));
}
function lookOnHome() {
  assistShow(T('setup.look_title'), say(T('setup.look_body')),
    row(act(T('setup.found_it'), async () => adbSteps(), 'primary'), act(T('setup.none_other_home'), async () => otherHome()), act(T('setup.other_ways'), async () => adbHelp())));
}
async function findSettings() {
  assistShow(T('setup.open_settings'), say(T('setup.trying_settings'))); const gen = assistGen;
  const r = await assist('settings'); if (gen !== assistGen) return;
  if (!r) return adbHelp();
  if (r.ok) return adbSteps();
  if (r.blind) return assistShow(T('setup.open_settings'), say(T('setup.settings_opened_q')), row(act(T('setup.yes'), async () => adbSteps(), 'primary'), act(T('setup.no'), async () => lookOnHome())));
  lookOnHome();
}
// TV Home is not on the Play Store, so "installed?" has one honest test: the TV does not bring it to the front. Then it is
// put on through Downloader like TV Setup. A TV that does not say what is on its screen cannot tell: the person sees it.
const HOME_PKG = 'dev.tvremote.home';
const homeSeen = () => assistShow('TV Home', say(T('setup.home_look_own')),
  row(act(T('setup.adb_steps_btn'), async () => adbSteps(), 'primary'), act(T('setup.reopen'), async () => { await openHome(); }), act(T('setup.choose_another'), async () => otherHome())));
async function openHome() {
  if (STATE.app !== HOME_PKG) {
    assistShow('TV Home', say(T('setup.opening_on_tv'))); const gen = assistGen;
    const r = await assist('open', { what: HOME_PKG, play: false }); if (gen !== assistGen) return;
    if (!r) return adbHelp();
    if (!r.ok && r.front) return installSetup('home');
  }
  homeSeen();
}
function otherHome() {
  assistShow(T('setup.via_other_home'), choice('TV Home', STATE.app === HOME_PKG ? T('setup.opening_on_tv') : T('setup.home_own_sub'), () => openHome()), ...HOMES.map(([name, pkg]) => choice(name, STATE.app === pkg ? T('setup.opening_on_tv') : T('setup.installed_open_else_play'), () => {
    assist('open', { what: pkg });
    assistShow(name, say(T('setup.home_look_body')),
      row(act(T('setup.adb_steps_btn'), async () => adbSteps(), 'primary'), act(T('setup.reopen'), async () => { await assist('open', { what: pkg }); }), act(T('setup.choose_another'), async () => otherHome())));
  })), row(act(T('setup.other_ways'), async () => adbHelp())));
}
// The old short way, kept as a fallback: some TVs install what their browser downloaded, others (TCL) sit on a white page.
// A minute with no answer (and no installer in front): offer the Downloader ways.
async function installDirect() {
  assistShow(T('setup.direct_title'), say(T('setup.sending_link'))); const gen = assistGen;
  const r = await assist('direct'); if (gen !== assistGen) return;
  if (!r) return adbHelp();
  assistShow(T('setup.direct_title'), say(T('setup.direct_body')),
    row(act(T('setup.installed_done'), async () => { await assist('open', { what: 'dev.tvremote.setup' }); }, 'primary'), act(T('setup.other_way'), async () => adbHelp())));
  const shown = assistGen;
  for (let waited = 0; ; waited += 5) {
    await sleep(5000); if (shown !== assistGen) return;               // any button, or closing, moves on
    if (waited < 60) continue;
    const s = await api('/api/assist', null, true); if (shown !== assistGen) return;
    if (s && /installer/.test(s.front || '')) continue;               // the TV's installer is up: the person is busy there
    return assistShow(T('setup.tv_failed_title'), say(T('setup.tv_failed_body')),
      row(act(T('setup.via_downloader'), async () => installSetup(), 'primary'), act(T('setup.other_ways'), async () => adbHelp())), say(await dlTip()));
  }
}
// The app Downloader puts on the TV: its code key (core/Downloader.kt), package, and the words around the install.
const DL_APPS = {
  setup: { title: 'setup.install_setup_title', look: 'setup.look_body_tv', open: 'setup.open_setup', pkg: 'dev.tvremote.setup' },
  home: { title: 'setup.install_home_title', look: 'setup.look_body_home', open: 'setup.open_home', pkg: HOME_PKG },
};
async function installSetup(key = 'setup') {
  const app = DL_APPS[key], again = async () => installSetup(key);
  if (!await assist('setup', { app: key })) return;
  const msg = say(T('setup.starting')); msg.classList.add('setupmsg');   // the words change as it goes; the buttons below must not jump
  const done = el('button', { className: 'small', textContent: T('setup.installed_done'), onclick: async () => { setDone(true); await assist('installed'); } });
  const setDone = (busy) => { done.disabled = busy; done.textContent = (busy ? T('setup.checking_busy') : T('setup.installed_done')); };
  const hands = el('p', { className: 'note', textContent: T('setup.dont_press') });   // a key pressed in the middle sends the job elsewhere
  assistShow(T(app.title), msg, hands, row(done, act(T('page.stop'), async () => { await assist('stop'); await sleep(400); adbHelp(); })));
  const gen = assistGen; let misses = 0;
  for (;;) {
    await sleep(1000); if (gen !== assistGen) return;
    const r = await api('/api/assist', null, true); if (gen !== assistGen) return;
    misses = r ? 0 : misses + 1;                                      // ten in a row: say so, instead of waiting for ever
    if (misses >= 10) return assistShow(T(app.title), say(T('setup.server_unreachable')), row(act(T('setup.retry'), again, 'primary'), act(T('setup.other_ways'), async () => adbHelp())));
    if (!r || r.status === 'running') { if (r) { if (r.msg) msg.textContent = (r.step ? T('setup.step_of', { step: r.step, steps: r.steps }) + ' · ' : '') + r.msg; setDone(!!r.checking); } continue; }
    if (r.status === 'moved') return assistShow(T(app.title), say(T('setup.moved')),
      row(act(T('setup.retry'), again, 'primary'), act(T('setup.other_ways'), async () => adbHelp())));
    if (r.status === 'look') return assistShow(T('allow.look_at_the_tv'), say(T(app.look)),
      row(act(T(app.open), async () => { if (key === 'home') await openHome(); else await assist('open', { what: app.pkg }); }, 'primary'), act(T('setup.redo'), again), act(T('setup.other_ways'), async () => adbHelp())));
    return assistShow(T(app.title), say(r.msg || T('setup.cant_do')), row(act(T('setup.retry'), again, 'primary'), act(T('setup.other_ways'), async () => adbHelp())));
  }
}

// ---------- Tailscale: one button installs it on the TV, switches it on and fetches its sign-in link (Tailscale.kt) ----------
// Nobody types on the TV: the link opens in a browser that is signed in to Tailscale, where one click adds the TV.
const OPEN_TV = T('setup.ts_connect');                            // the list's buttons: short, one word
const TS_PITCH = T('setup.ts_pitch');
const TS_STEP = { download: T('setup.ts_download'), install: T('setup.ts_install'), start: T('setup.ts_start') };
let tsGen = 0;
function tsShow(...kids) { $('#tsbody').replaceChildren(...kids.filter(Boolean)); if (!$('#ts').open) $('#ts').showModal(); }
async function tailscaleGo() {
  const gen = ++tsGen, msg = el('span', { textContent: T('setup.starting') }), bar = el('div', { className: 'bar' }, el('i'));
  tsShow(el('p', { className: 'tswait' }, el('span', { className: 'minispin' }), msg), bar, el('p', { className: 'muted tsnote', textContent: T('setup.ts_closable') }));
  let j = await api('/api/tailscale/start', { lang: LANG }), misses = 0;
  while (j && j.status === 'running' && misses < 10) {
    const k = Object.keys(TS_STEP).find((w) => (j.msg || '').startsWith(w)); msg.textContent = k ? TS_STEP[k] : T('setup.ts_working'); bar.firstChild.style.width = (j.pct || 0) + '%';
    await sleep(1000); if (gen !== tsGen) return;
    const r = await api('/api/job?id=' + j.id, null, true); if (r) { j = r; misses = 0; } else misses++;
  }
  if (gen !== tsGen) return;
  if (!j || j.status !== 'done') return tsShow(el('p', { textContent: T('setup.ts_failed', { why: j && j.status === 'error' ? friendly(j.msg) : T('setup.tv_lost') }) }), row(act(T('setup.retry'), async () => tailscaleGo(), 'primary')));
  if (j.ip) return tsDone(j.ip);
  // the QR code and two short steps; what each step means in full sits in its tooltip
  const qr = qrSvg(j.url, 168), step = (n, text, tip) => el('li', { title: tip }, el('b', { textContent: n }), el('span', { textContent: text }));
  tsShow(el('div', { className: 'tslogin' },
    qr ? el('div', { className: 'tsqr', title: T('setup.ts_qr_tip') }, qr, el('span', { textContent: T('setup.ts_scan') })) : null,
    el('div', { className: 'tsside' },
      el('ol', { className: 'tssteps' },
        step('1', T('setup.ts_step1'), T('setup.ts_step1_tip')),
        step('2', T('setup.ts_step2'), T('setup.ts_step2_tip'))),
      el('a', { className: 'go primary', href: j.url, target: '_blank', rel: 'noopener', textContent: T('setup.ts_open_login') }))),
    el('p', { className: 'tswait' }, el('span', { className: 'minispin' }), el('span', { textContent: T('setup.ts_waiting') })));
  for (;;) {
    await sleep(2500); if (gen !== tsGen || !$('#ts').open) return;
    const s = await api('/api/tailscale', null, true); if (s && s.ip) return tsDone(s.ip);
  }
}
function tsDone(ip) {
  tsShow(el('div', { className: 'tsdone' }, el('div', { className: 'tsok', textContent: '✓' }), el('b', { textContent: T('setup.ts_joined') }), el('code', { textContent: ip })),
    el('p', { className: 'muted tsnote', textContent: CORE === 'pc' ? T('setup.ts_save_note') : T('setup.ts_away_note') }),
    el('p', { className: 'tstip', title: T('setup.ts_expiry_tip') },
      el('span', { textContent: T('setup.ts_expiry') })),
    row(el('a', { className: 'linkbtn small', href: 'https://login.tailscale.com/admin/machines', target: '_blank', rel: 'noopener', textContent: T('setup.ts_admin') }), act(T('remote.done'), async () => $('#ts').close(), 'primary')));
  tsState(); if (CORE === 'pc') loadSteps();
}
async function tsState() {                                             // the card on the tv / phone cores (the computer has its step 2)
  if (CORE === 'pc' || ADB.state !== 'ready') return;
  const s = await api('/api/tailscale', null, true); if (!s) return;
  put('#tsstate', 'textContent', s.ip ? T('setup.ts_state_joined', { ip: s.ip })
    : s.installed ? T('setup.ts_state_installed') : TS_PITCH);
  put('#tsgo', 'hidden', !!s.ip); put('#tsgo', 'textContent', s.installed ? T('setup.ts_btn_on') : T('tv.install_and_sign_in'));
}
$('#tsgo').addEventListener('click', tailscaleGo);
$('#tsclose').addEventListener('click', () => $('#ts').close());
$('#ts').addEventListener('close', () => { tsGen++; });

let stepsBusy = false, pairing = false, stepsTv = 0;                 // stepsTv: which TV a load in flight was for
// another TV: its steps start over (pairing begun on the old one is not this one's; an old answer in flight is dropped)
onTvChange(() => { stepsTv++; stepsBusy = false; pairing = false; stepsSig = ''; });
function step(n, state, title, text, ...actions) {
  return el('div', { className: 'step ' + state }, el('div', { className: 'mark', textContent: state === 'ok' ? '✓' : n }),
    el('div', { className: 'body' }, el('b', { textContent: title }), el('p', { textContent: text }), actions.filter(Boolean).length ? el('div', { className: 'actions' }, ...actions.filter(Boolean)) : null));
}
const act = (label, fn, cls = '', name = '') => el('button', { className: 'small ' + cls, textContent: label, title: name, onclick: async (e) => { const b = e.currentTarget; b.disabled = true; try { await fn(); } finally { b.disabled = false; } } });
async function loadSteps() {
  if (stepsBusy || CORE !== 'pc') return; stepsBusy = true; const tv = stepsTv;
  const s = await api('/api/setup/status', null, true);
  if (tv !== stepsTv) return;                                         // the TV changed meanwhile: the new TV's own load draws (and owns stepsBusy)
  stepsBusy = false;
  if (!s) return;
  put($('#setuphost'), 'textContent', s.host ? (s.model ? s.model + ' · ' : '') + s.host : T('setup.no_tv_chosen'));
  if (!s.host) { $('#steps').replaceChildren(el('p', { className: 'muted', textContent: T('setup.pick_a_tv') })); return; }
  if (s.kind && s.kind !== 'android') { showSteps(nativeSteps(s)); return; }
  const adb = s.adb.state, ready = adb === 'ready', out = [];
  // ADB. Not connected: it is "more" (apps, picture, files), the keys come first (they need no ADB)
  const keysFirst = !ready;
  out.push(ready ? step(1, 'ok', T('setup.adb_connected'), T('setup.allowed_by_tv'))
    : adb === 'unauthorized' ? step(1, 'wait', T('setup.tv_waiting_allow'), T('setup.tv_waiting_allow_note'), act(T('setup.pressed_retry'), async () => { await api('/api/adb/connect', {}); await loadSteps(); }, 'primary'))
    : step(1, 'todo', T('setup.turn_on_adb_title'), keysFirst ? T('setup.turn_on_adb_more') : T('setup.turn_on_adb_note'),
        act(T('setup.turn_on_adb_help'), async () => adbHelp(), 'primary'), act(T('setup.connect_now'), async () => { await api('/api/adb/connect', {}); await loadSteps(); })));
  // 2. Tailscale
  if (!ready) out.push(step(2, 'todo', T('setup.ts_step_title'), T('setup.ts_step_later')));
  else if (s.tailscale_ip) out.push(step(2, 'ok', T('setup.ts_in_network'), T('setup.ts_ip_note', { ip: s.tailscale_ip })));
  else out.push(step(2, 'todo', T('setup.ts_step_title'), TS_PITCH, act(T('tv.install_and_sign_in'), async () => tailscaleGo(), 'primary')));
  out[out.length - 1].classList.add('optional');                     // only for away from home: the steps count as done without it
  // 3. remote pairing
  const r = s.remote;
  if (r.connected) out.push(step(3, 'ok', T('setup.remote_paired'), keysFirst ? T('setup.keys_no_adb') : T('setup.keys_work')));
  else if (ready && !pairing) out.push(step(3, 'ok', T('setup.remote_via_adb'), T('setup.pair_spare'),   // keys already work: pairing is only a spare way in
    act(T('setup.pair'), async () => { if (await api('/api/pair/start', {})) { pairing = true; await loadSteps(); } })));
  else if (r.paired === false || pairing) {
    const code = el('input', { placeholder: T('setup.ph_code6c'), maxLength: 6, autocomplete: 'off' });
    out.push(step(3, 'wait', T('setup.pair_remote'), (keysFirst && !pairing ? T('setup.keys_first') + ' ' : '') + (pairing ? T('setup.tv_showing_code6') : T('setup.press_pair_code6')),
      pairing ? code : null,
      pairing ? act(T('setup.confirm'), async () => { const x = await api('/api/pair/finish', { code: code.value.trim() }); if (x) { pairing = false; toast(T('setup.paired')); await refresh(); await loadSteps(); } }, 'primary') : null,
      act(pairing ? T('setup.new_code') : T('setup.pair'), async () => { if (await api('/api/pair/start', {})) { pairing = true; await loadSteps(); } }, pairing ? '' : 'primary')));
  } else out.push(step(3, 'todo', T('setup.remote_buttons'), r.error ? T('setup.cant_reach_6466') : T('setup.connecting'), act(T('setup.try_pair'), async () => { if (await api('/api/pair/start', {})) { pairing = true; await loadSteps(); } })));
  // 4. save
  const move = s.tailscale_ip && s.tailscale_ip !== s.host;
  if (s.saved && !move) out.push(step(4, 'ok', T('setup.saved_name', { name: s.saved.name }), T('setup.saved_next') + (s.saved.mac ? ' ' + T('setup.mac_saved') : '')));
  else {
    const name = el('input', { placeholder: T('setup.ph_name'), value: (s.saved && s.saved.name) || s.model || '' });
    out.push(step(4, 'todo', move ? T('setup.save_move') : T('setup.save_tv'), move ? T('setup.save_move_note', { ip: s.tailscale_ip }) : T('setup.save_note'),
      UI.line(name, act(T('scenedlg.save'), async () => { const x = await api('/api/tv/save', { name: name.value }); if (x) { toast(x.moved ? T('setup.saved_moved', { host: x.tv.host }) : T('setup.saved')); await refresh(); await loadSteps(); } }, 'primary'))));
  }
  if (keysFirst) out.unshift(out.splice(2, 1)[0]);                   // pairing is out[2] here: first
  out.forEach((n, i) => { const m = n.querySelector('.mark'); if (m.textContent !== '✓') m.textContent = i + 1; });
  showSteps(out);
}
let stepsSig = '', stepsHost = '';
function showSteps(out) {
  if (document.activeElement && $('#steps').contains(document.activeElement) && document.activeElement.tagName === 'INPUT') return;   // don't wipe what is being typed
  const sig = out.map((n) => n.outerHTML).join('');                   // the poll often brings the same steps: leave them be
  if (sig === stepsSig) return;
  stepsSig = sig;
  // all done: one line, opened on a click, so the tab starts with the TV
  const done = out.every((n) => n.classList.contains('ok') || n.classList.contains('optional'));
  const host = $('#setuphost').textContent, fold = $('#steps > details.stepsdone');
  const open = fold && host === stepsHost ? fold.open : false;
  stepsHost = host;
  $('#steps').replaceChildren(...(done ? [el('details', { className: 'stepsdone', open }, el('summary', { textContent: T('setup.setup_done') }), ...out)] : out));
}
// Hisense VIDAA: newer TVs want the certificate of Hisense's own app (never shipped with ours), then a 4-digit code.
function vidaaStep(r) {
  const ask = act(pairing ? T('setup.new_code') : T('setup.pair'), async () => { if (await api('/api/pair/start', {})) { pairing = true; await loadSteps(); } }, pairing ? '' : 'primary');
  if (/certificate from Hisense/.test(r.error || '')) {
    const f = el('input', { type: 'file', accept: '.apk,.p12,.pfx', hidden: true, onchange: async () => {
      const file = f.files[0]; if (!file) return;
      const x = await fetch('/api/vidaa/cert', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file }).then((y) => y.json().catch(() => ({}))).catch(() => ({}));
      if (x.ok) { toast(T('setup.cert_received')); await sleep(1500); await loadSteps(); } else toast(friendly(x.error || 'vidaa: no certificate in this file'));
    } });
    return step(1, 'todo', T('setup.cert_needed'), T('setup.cert_note'), f, act(T('setup.choose_file'), async () => f.click(), 'primary'));
  }
  if (r.paired === false || pairing) {
    const code = el('input', { placeholder: T('setup.ph_code4'), maxLength: 4, inputMode: 'numeric', autocomplete: 'off' });
    return step(1, 'wait', T('setup.pair_with_tv'), pairing ? T('setup.tv_showing_code4') : T('setup.press_pair_code4'), pairing ? code : null,
      pairing ? act(T('setup.confirm'), async () => { const x = await api('/api/pair/finish', { code: code.value.trim() }); if (x) { pairing = false; toast(T('setup.paired')); await refresh(); await loadSteps(); } }, 'primary') : null, ask);
  }
  return step(1, 'todo', T('setup.connect_tv'), r.error ? T('setup.cant_connect') : T('setup.connecting'), r.error ? ask : null);
}
// An LG, Samsung or Roku TV: no ADB, no code to type. LG and Samsung ask themselves (a Roku asks nothing) whether to let this computer in; then save it.
function nativeSteps(s) {
  const r = s.remote, out = [], again = act(T('setup.ask_again'), async () => { if (await api('/api/pair/start', {})) { toast(T('allow.look_at_the_tv')); await sleep(800); await loadSteps(); } }, 'primary');
  out.push(r.connected ? step(1, 'ok', T('setup.tv_allowed'), T('setup.keys_work'))
    : s.kind === 'vidaa' ? vidaaStep(r)
    : r.asking ? step(1, 'wait', T('setup.look_choose_allow'), T('setup.tv_asking_allow'))
    : r.error === 'refused' ? step(1, 'todo', T('setup.tv_refused'), T('setup.refused_note'), again)
    : r.error === 'not accepted' ? step(1, 'todo', T('setup.nobody_allowed'), T('setup.nobody_note'), again)
    : /control by mobile apps/.test(r.error || '') ? step(1, 'todo', T('setup.roku_blocking'), friendly(r.error))
    : step(1, 'todo', T('setup.connect_tv'), r.error ? T('setup.cant_connect') : T('setup.connecting'), r.error ? again : null));
  if (s.saved) out.push(step(2, 'ok', T('setup.saved_name', { name: s.saved.name }), T('setup.saved_next') + (s.saved.mac ? ' ' + T('setup.mac_saved') : '')));
  else {
    const name = el('input', { placeholder: T('setup.ph_name'), value: s.model || '' });
    out.push(step(2, 'todo', T('setup.save_tv'), T('setup.save_note'),
      name, act(T('scenedlg.save'), async () => { const x = await api('/api/tv/save', { name: name.value }); if (x) { toast(T('setup.saved')); await refresh(); await loadSteps(); } }, 'primary')));
  }
  return out;
}
setInterval(() => { if (currentTab === 'tv' && !document.hidden && !stopped) loadSteps(); }, 6000);

// The first tab once every script is in: the TV tab fills its cards from home.js and tvset.js, which come after this one
document.addEventListener('DOMContentLoaded', () => {
  const startTab = (window.LANG_KEEP && window.LANG_KEEP.tab) || location.hash.slice(1) || new URLSearchParams(location.search).get('tab') || (CORE === 'phone' ? 'apps' : '');
  if (['remote', 'apps', 'files', 'health', 'auto', 'tv'].includes(startTab)) showTab(startTab); else if (startTab === 'install') showTab('files');
  else if (startTab === 'term') { showTab('files'); $('#termcard').open = true; }   // the terminal used to be a tab of its own
  document.documentElement.classList.add('tabbed');                   // the right tab is open: show it (theme.js)
  const want = new URLSearchParams(location.search).get('card');       // TV Home sends people to one card (the kids lock's PIN): scroll to it, focus its first control
  // a card filled from the rules (the kids card's Add app) may come after 900 ms: looked for again a few times
  const go = (n) => { const c = document.getElementById(want); if (!c) { if (n < 10) setTimeout(() => go(n + 1), 400); return; } if (c.tagName === 'DETAILS') c.open = true; c.scrollIntoView({ block: c.matches('button') ? 'center' : 'start' }); (c.matches('button, input, select, a') ? c : c.querySelector('button, input, select, a'))?.focus(); };
  manageHow().then(() => { if (/^[a-z]+$/.test(want || '')) setTimeout(() => go(0), 900); });   // after the question: a modal dialog keeps the focus from the card
});
// ---------- on the TV itself (Manage on this TV): first, here with the remote or on a phone ----------
// A phone types and scrolls better than a D-pad (the user, 2026-10-26). The QR is a one-time full invite (Invite.kt) to this same
// tab and card, so the phone lands where the TV was going; Manage is behind TV Setup's PIN already (Guard.RULE_SCREENS).
// No question where no phone beside the TV could open it: TV Setup on a phone, the web remote off, no Wi-Fi address.
async function manageHow() {
  if (!window.ONTV || CORE !== 'tv' || matchMedia('(pointer:coarse)').matches) return;
  const r = await api('/api/share', { scope: 'full' }, true) || {};
  if (!r.url || r.warn === 'tailscale_only') return;
  const q = new URLSearchParams(), at = new URLSearchParams(location.search);
  for (const k of ['tab', 'card']) if (/^[a-z]+$/.test(at.get(k) || '')) q.set(k, at.get(k));
  const url = String(q) ? r.url.replace('/#', '/?' + q + '#') : r.url;
  return new Promise((done) => {
    const d = el('dialog', { className: 'ask how' }, el('div', { className: 'card' },
      el('h3', { textContent: T('setup.how_title') }),
      el('div', { className: 'qrs' }, qrBlock(T('setup.how_phone'), [T('setup.same_wifi'), T('setup.how_note')], url, true)),
      UI.actions(UI.btn(T('setup.how_tv'), () => d.close(), 'primary'))));
    d.addEventListener('close', () => { d.remove(); done(); });
    document.body.append(d); d.showModal();
  });
}

// One check at a time, and none while the page is hidden: a TV that is slow to answer must not pile them up.
let polling = false;
applyAdb(); refresh(); setInterval(async () => { if (polling || document.hidden) return; polling = true; try { await refresh(); } finally { polling = false; } }, 4000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
homeHint();
if (window.LANG_KEEP && window.LANG_KEEP.y) setTimeout(() => window.scrollTo(0, window.LANG_KEEP.y), 400);   // after the tab has filled in

// ---------- devices paired with this TV (the TV web page only: TV Setup keeps the list, RemoteService /api/paired) ----------
const agoText = (t) => {
  const m = Math.max(0, (Date.now() - t) / 60000);
  if (m < 2) return T('setup.ago_now');
  if (m < 60) return T('setup.ago_min', { n: Math.round(m) });
  if (m < 2880) return T('setup.ago_hour', { n: Math.round(m / 60) });
  return T('setup.ago_day', { n: Math.round(m / 1440) });
};
async function loadPaired() {
  const box = $('#paired'); if (!box || CORE !== 'tv') return;
  const r = await api('/api/paired', null, true); if (!r) return;
  const out = async (body, me) => { if (await api('/api/paired/forget', body)) { if (me) refresh(); else loadPaired(); } };
  const owner = SCOPE === 'full';
  const setScope = async (d, k) => { if (k !== d.scope && await api('/api/paired/scope', { id: d.id, scope: k })) loadPaired(); };
  const rows = r.devices.map((d) => UI.item({
    name: d.name || T('setup.old_device'),
    sub: [d.created ? T('setup.paired_at', { when: new Date(d.created).toLocaleString(LOCALE, { dateStyle: 'short', timeStyle: 'short' }) }) : '',
      d.me ? T('setup.this_device') : T('setup.used_ago', { ago: agoText(d.seen) }), d.ip].filter(Boolean).join(' · '),
    extra: owner && d.kind === 'web' && !d.me ? [scopeChips(d.scope || 'full', (k) => setScope(d, k))] : [],   // what it may do: Scope.kt
    acts: [el('button', { className: 'small danger', textContent: T('setup.unpair'), title: T('setup.forget_title'),
      onclick: async () => { if (await ask((d.me ? T('setup.forget_me_ask', { name: d.name || T('setup.this_device') }) : T('setup.forget_dev_ask', { name: d.name || T('setup.this_device') })), { ok: T('setup.forget_ok'), danger: true })) out({ id: d.id }, d.me); } })],
  }));
  box.replaceChildren(UI.card(T('setup.paired_devices'), { tools: owner && r.devices.length > 1 ? [el('button', { className: 'small danger', textContent: T('setup.remove_all'), title: T('setup.remove_all_title'),
    onclick: async () => { if (await ask(T('setup.remove_all_ask'), { ok: T('setup.remove_all'), danger: true })) out({ all: true }, true); } })] : [] },
    UI.list(rows, T('setup.none_paired'))));
}
