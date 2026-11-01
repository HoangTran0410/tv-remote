// app names, icons and the apps tab
// The remote's "now open" line: the app on the TV's screen, from its remote link or ADB; hidden when the TV does not say.
let nowApp = '';
function showNowApp(id, label) {
  if (id + '|' + (label || '') === nowApp) return; nowApp = id + '|' + (label || '');
  const box = $('#nowapp'); box.hidden = !id; if (!id) return;
  const pkg = /^[\w]+(\.[\w]+)+$/.test(id) && !label, icon = appIcon(id), name = el('b', { textContent: label || appName(id) });
  box.replaceChildren(el('small', { textContent: T('apps.now_open') }), icon, name);
  if (!pkg) icon.fill({ label: label || id });                         // an LG / Roku id: its name's first letter
  if (id === 'dev.tvremote.games' && can('adb')) box.append(el('button', { className: 'small', textContent: T('rkey.pad_chip'), onclick: () => gamePad() }));   // the pad page of TV Games (its QR on the TV); looked up on the press: games.js loads later, and the first /api/state can come back first on a slow TV
  if (pkg) onMeta(id, (m) => { if (nowApp.startsWith(id + '|')) { icon.fill(m); put(name, 'textContent', appName(id)); } });
}
function seedMeta(apps) { apps.forEach((a) => { if (a.meta && !META.has(a.pkg)) META.set(a.pkg, a.meta); }); }
// The button that stands for a chosen app in a form (its value the package): its icon and name, or "Pick an app…" when none.
function appChoice(btn, pkg) {
  btn.value = pkg || '';
  if (!pkg) { btn.replaceChildren(el('span', { textContent: T('auto.pick_app') })); return; }
  const icon = appIcon(pkg), name = el('span', { textContent: appName(pkg) });
  onMeta(pkg, (m) => { icon.fill(m); put(name, 'textContent', appName(pkg)); });
  btn.replaceChildren(icon, name);
}
const lazyMeta = new IntersectionObserver((entries) => entries.forEach((e) => { if (e.isIntersecting) { lazyMeta.unobserve(e.target); e.target.loadMeta(); } }), { rootMargin: '300px' });

// Open on the TV → Open an installed app: the TV's apps as tiles, with a search
async function openInstalled() {
  if (!NATIVE() && await needAdbSoon()) return;                       // an Android TV lists its apps over ADB; an LG / Samsung by its link
  if (!quickApps.length) { const r = await api('/api/apps'); if (!r) return; seedMeta(r.apps); quickApps = r.apps.map((a) => a.pkg); }
  const p = await chooseApp(T('rkey.open_apps'), quickApps);
  if (p) openOnTv(p, appName(p));
}

// quick launch tiles on the Remote tab
let quickLoaded = false, quickApps = [];                              // quickApps: the TV's apps, for the Open on the TV menu too
async function loadQuick() {
  quickLoaded = true;
  const gen = appsGen, r = await api('/api/apps', null, true);
  if (gen !== appsGen) return;                                       // the TV changed on the way (appsStale cleared quickLoaded; a newer read may be running)
  if (!r) { quickLoaded = false; return; }
  seedMeta(r.apps); quickApps = r.apps.map((a) => a.pkg);
  const missing = r.apps.filter((a) => !META.has(a.pkg));
  if (missing.length) {                                               // first run: wait for every name/icon, then draw once so tiles never jump
    let done = 0;
    const note = el('div', { className: 'muted' }); $('#quick').replaceChildren(note);
    const tick = () => { note.textContent = T('apps.reading_names', { done, total: missing.length }); };
    tick(); await Promise.all(missing.map((a) => new Promise((ok) => onMeta(a.pkg, () => { done++; tick(); ok(); }))));
    if (gen !== appsGen) return;
  }
  const tiles = r.apps.slice().sort((a, b) => appName(a.pkg).localeCompare(appName(b.pkg))).map((a) => {
    const icon = appIcon(a.pkg); icon.fill(META.get(a.pkg) || {});
    return el('button', { className: 'tile', title: a.pkg, onclick: () => openOnTv(a.pkg, appName(a.pkg)) }, icon, el('span', { textContent: appName(a.pkg) }));
  });
  const gear = el('div', { className: 'appicon', textContent: '⚙' });
  tiles.push(el('button', { className: 'tile', onclick: () => openOnTv('settings', T('page.settings')) }, gear, el('span', { textContent: T('apps.tv_settings') })));
  $('#quick').replaceChildren(...tiles); filterQuick();
}

// ---------- apps tab ----------
let appsLoaded = false, appsAll = [], appsInfo = new Map(), appsOffOpen = false;          // pkg -> { off, keep, version }
const appsStale = () => { quickLoaded = appsLoaded = false; };      // installed or removed elsewhere (files.js, Optimize TV): read again
let appsGen = 0;                                                      // bumped when the TV changes: a list still on its way from the old TV is dropped
onTvChange(() => {
  appsGen++; appsSysBusy = false; quickApps = []; appsStale(); META.clear(); metaFailed.clear(); appsIcons.clear();
  $('#quick').replaceChildren(el('div', { className: 'muted', textContent: T('remote.apps_appear_when_adb') }));
  $('#appslist').replaceChildren();
});
let appsBoot = new Set();                                             // the apps that start by themselves when the TV turns on
let appsFilter = 'all';
let appsSys = null, appsSysBusy = false;                             // the system apps, read once a search needs them (the chip off): null = not read yet
async function loadAppsSys() {
  if (appsSysBusy) return; appsSysBusy = true;
  const gen = appsGen, r = await api('/api/apps?system=1', null, true);
  if (gen !== appsGen) return;
  appsSysBusy = false; appsSys = r ? r.apps : [];
  appsSys.forEach((a) => { if (!appsInfo.has(a.pkg)) appsInfo.set(a.pkg, { off: !!a.off, keep: !!a.keep, version: a.version || '' }); });
  seedMeta(appsSys); renderApps();
}
onTvChange(() => { appsSys = null; });
// the chips above the list: each narrows it to one kind of app, with how many there are. The same words and colour mark
// those apps in the list (APP_TAGS), so a colour on a row says what the chip says.
const APPS_FILTERS = [
  ['all', T('apps.all'), () => true],
  ['open', T('apps.open_now'), (p) => recentsOpen.has(p), T('apps.open_now_why')],   // recents.js
  ['boot', T('apps.autostart'), (p, i) => !i.off && appsBoot.has(p), T('apps.autostart_why')],
  ['keep', T('apps.must_keep'), (p, i) => i.keep, T('apps.must_keep_why')],
  ['off', T('apps.turned_off'), (p, i) => i.off, T('apps.turned_off_why')],
];
const appTags = (p, i) => APPS_FILTERS.filter((f) => f[0] !== 'all' && f[2](p, i))
  .map(([key, label, , why]) => el('span', { className: 'tag ' + key, textContent: label, title: why }));
async function loadApps(quiet) {                                       // quiet: the list stays on screen while it is read again
  if (!quiet) $('#appslist').replaceChildren(el('div', { className: 'muted', textContent: T('apps.loading') }));
  const gen = appsGen, r = await api('/api/apps' + ($('#appssys').checked ? '?system=1' : ''));
  if (gen !== appsGen) return;
  if (!r) { if (!quiet) $('#appslist').replaceChildren(); return; }
  appsSys = null; appsLoaded = true; appsAll = r.apps.map((a) => a.pkg); appsInfo = new Map(r.apps.map((a) => [a.pkg, { off: !!a.off, keep: !!a.keep, version: a.version || '' }])); seedMeta(r.apps); renderApps();
  const b = await api('/api/apps/boot', null, true);                 // after the list: a tag is worth no wait
  if (b && gen === appsGen) { appsBoot = bootRead(b.text); renderApps(); }
}
// The list is drawn again on every refresh (the open apps every few seconds): each app keeps its icon, so it does not go
// back to a letter while its picture loads again
const appsIcons = new Map();
function appsIcon(pkg, off) {
  const k = pkg + (off ? ' off' : '');
  if (!appsIcons.has(k)) appsIcons.set(k, appIcon(pkg));
  return appsIcons.get(k);
}
function renderApps() {
  const q = $('#appsq').value.trim().toLowerCase();
  lazyMeta.disconnect();
  const fit = APPS_FILTERS.find((f) => f[0] === appsFilter) || APPS_FILTERS[0];
  const found = appsAll.filter((p) => !q || p.toLowerCase().includes(q) || appName(p).toLowerCase().includes(q));
  const sys = $('#appssys').checked, open = fit[0] === 'open';
  $('#appsfilter').replaceChildren(...APPS_FILTERS.map(([key, label, ok, why]) => el('button', { className: key + (key === fit[0] ? ' on' : ''), type: 'button', title: why || '',
    onclick: () => { appsFilter = key; renderApps(); if (key === 'open') recentsNow(true); } }, label,
    el('b', { textContent: key === 'open' ? recentsList(q).length : found.filter((p) => ok(p, appsInfo.get(p) || {})).length }))),   // a system app open (Settings) counts too
    el('button', { className: 'sys' + (sys ? ' on' : ''), type: 'button', ariaPressed: String(sys), title: T('apps.show_system'),   // which apps are listed at all: a switch, not a kind
      onclick: () => { $('#appssys').checked = !sys; loadApps(); } }, (sys ? '✓ ' : '') + T('apps.system_apps')));
  const mk = (pkg) => {
    const info = appsInfo.get(pkg) || {};
    const icon = appsIcon(pkg, info.off), title = el('span', { textContent: META.get(pkg)?.label || pkg.split('.').pop() });
    // one button for what is wanted most, everything else behind ⋯
    const more = el('button', { className: 'small iconbtn', title: T('page.more_actions'), ariaLabel: T('page.more_actions'), textContent: '⋯', onclick: (e) => appMenu(e.currentTarget, pkg, info, (name) => {
      if (name === 'uninstall') { appsAll = appsAll.filter((p) => p !== pkg); appsInfo.delete(pkg); }   // the list as it is, less one row: no reading every app again
      else appsInfo.set(pkg, { ...info, off: name === 'disable' });
      renderApps(); quickLoaded = false; loadQuick();
    }) });
    const row = el('div', { className: 'item' + (info.off ? ' off' : '') + (open ? ' proc' : '') }, icon, el('div', { className: 'name' },
      el('div', { className: 'nameline' }, title, ...(open ? recentsTag(pkg) : appTags(pkg, info))), open ? el('small', { textContent: recentsSub(pkg), title: pkg }) : el('small', { className: 'pkg', title: pkg }, el('span', { textContent: pkg }), ...(info.version ? [el('span', { className: 'ver', textContent: info.version })] : []))),
      el('div', { className: 'acts' }, ...(open ? recentsActs(pkg) : []), more));
    row.loadMeta = () => onMeta(pkg, (m) => { icon.fill(info.off ? { label: m.label } : m); if (m.label) title.textContent = m.label; });   // a disabled app has no picture to give
    if (META.has(pkg)) row.loadMeta(); else lazyMeta.observe(row);
    row.off = !!info.off;
    return row;
  };
  const rows = (open ? recentsList(q) : found.filter((p) => fit[2](p, appsInfo.get(p) || {}))   // open: newest first, as the TV keeps them
    .sort((a, b) => (appsInfo.get(a)?.off ? 1 : 0) - (appsInfo.get(b)?.off ? 1 : 0) || appName(a).localeCompare(appName(b)))).map(mk);   // turned off: last
  // a search also looks in the system apps (YouTube itself is one) and lists what it finds apart, folded, with each package shown
  if (q && !sys && !open && appsSys === null) loadAppsSys();
  const have = new Set(appsAll), sysRows = q && !sys && !open && appsSys ? appsSys.map((a) => a.pkg).filter((p) => !have.has(p)
    && (p.toLowerCase().includes(q) || appName(p).toLowerCase().includes(q)) && fit[2](p, appsInfo.get(p) || {}))
    .sort((a, b) => appName(a).localeCompare(appName(b))).map(mk) : [];
  const sysFold = sysRows.length ? [el('details', { className: 'appsoff appssysfound', open: !rows.length },
    el('summary', { textContent: T('apps.system_found', { n: sysRows.length }) }), ...sysRows)] : [];
  // the disabled apps (Optimize TV's, or "Disable" here) fold away at the end, so "Turn on" is at hand but out of the way
  const fold0 = fit[0] === 'all';                                    // a chip picked: its apps, all in plain sight
  const on = fold0 ? rows.filter((r) => !r.off) : rows, off = fold0 ? rows.filter((r) => r.off) : [];
  const none = !$('#appssys').checked && (fit[0] === 'keep' || fit[0] === 'boot') ? T('apps.none_system_hint') : T('apps.none');
  const fold = off.length ? [el('details', { className: 'appsoff', open: !!q || appsOffOpen, ontoggle: (e) => { if (!q) appsOffOpen = e.target.open; } },
    el('summary', { textContent: T('apps.off_title', { n: off.length }) }), el('p', { className: 'muted', textContent: T('apps.off_note') }), ...off)] : [];
  $('#appslist').replaceChildren(...(on.length || off.length || sysFold.length ? on : [el('div', { className: 'muted', textContent: open ? T('recents.none') : none })]), ...fold, ...sysFold, ...(open ? recentsTail() : []));
}
$('#appsq').addEventListener('input', renderApps);
$('#appsf').addEventListener('submit', (e) => e.preventDefault());
$('#appssys').addEventListener('change', loadApps);
$('#appsreload').addEventListener('click', () => { loadApps(); recentsNow(true); });

// ⋯ one app's actions, for the Apps tab's rows and Health's privacy list. info: { keep, off }; done(name) once the app was turned
// on / off or removed; skip: actions the caller shows as buttons of its own
function appMenu(btn, pkg, info, done, skip = []) {
  const run = async (name, label) => {
    if (name === 'uninstall' && !await ask(T('apps.uninstall_ask', { name: appName(pkg) }), { ok: T('apps.remove'), danger: true })) return;
    if (name === 'disable' && !await ask(T('apps.disable_ask', { name: appName(pkg) }), { ok: T('apps.disable_app') })) return;
    if (name === 'clear' && !await ask(T('apps.clear_ask', { name: appName(pkg) }), { ok: T('apps.clear_data'), danger: true })) return;
    const r = await api('/api/app/' + name, { pkg }); if (!r) return;
    toast(T('apps.what_name', { what: label, name: appName(pkg) }));
    if (!['launch', 'stop', 'clear'].includes(name)) done(name);
  };
  const no = (name) => skip.includes(name);
  popover(btn, [
    info.off ? (no('enable') ? null : ['↩', T('apps.turn_on'), () => run('enable', T('apps.turn_on'))]) : ['▶', T('apps.open'), () => run('launch', T('apps.open'))],
    ['ℹ️', T('apps.details'), () => appDetails(pkg, info.keep)],
    ['📋', T('apps.copy_package'), () => copyText(pkg)],
    info.keep || info.off ? null : ['⏹', T('apps.stop'), () => run('stop', T('apps.stop'))],   // 🔒 must keep: the TV needs it, so no stop / clear / off / remove
    info.off ? null : ['📜', T('apps.view_log'), () => appLog(pkg)],
    ['💾', T('apps.backup'), () => appBackup(pkg)],
    info.keep || info.off ? null : ['🧽', T('apps.clear_data'), () => run('clear', T('apps.clear_data'))],
    info.keep || info.off || no('disable') ? null : ['⏸', T('apps.disable_app'), () => run('disable', T('apps.disable_app'))],
    info.keep || no('uninstall') ? null : ['🗑', T('apps.remove'), () => run('uninstall', T('apps.remove')), 'danger'],
  ]);
}

// ℹ️ one app in detail: version, dates, memory, start at boot, and the permissions a person can give or take back
async function appDetails(pkg, keepHint) {
  const body = $('#infobody');
  put('#infotitle', 'textContent', 'ℹ️ ' + appName(pkg)); body.replaceChildren(el('p', { className: 'muted', textContent: T('health.reading_from_the_tv') }));
  if (!$('#infodlg').open) $('#infodlg').showModal();
  const r = await api('/api/app/info?pkg=' + encodeURIComponent(pkg));
  if (!r || !$('#infodlg').open) return;
  const a = appInfoRead(r.text), keep = (appsInfo.get(pkg) || {}).keep ?? keepHint;   // from Health the Apps tab may not be read yet
  if (!a.found) { body.replaceChildren(el('p', { className: 'muted', textContent: T('apps.gone') })); return; }
  const kv = el('dl', { className: 'kv' });
  const add = (k, v) => { if (v) kv.append(el('dt', { textContent: k }), el('dd', { className: 'v', textContent: v })); };
  add(T('apps.version'), a.version + (a.code ? ' (' + a.code + ')' : ''));
  add(T('apps.needs_android'), a.minSdk ? T('apps.or_newer', { v: androidName(a.minSdk) }) : '');
  add(T('apps.built_for'), a.targetSdk ? androidName(a.targetSdk) : '');
  add(T('apps.installed_at'), a.installed.slice(0, 16));
  add(T('apps.updated_at'), a.updated !== a.installed ? a.updated.slice(0, 16) : '');
  add(T('apps.kind'), a.system ? T(a.updatedSystem ? 'apps.system_updated' : 'apps.system_app') : T('apps.user_installed'));
  add(T('apps.installed_from'), a.installer === 'com.android.vending' ? 'Google Play' : a.installer ? appName(a.installer) : '');
  add(T('apps.running'), a.pids.length ? (a.ramKb ? T('apps.yes_ram', { size: fmtSize(a.ramKb * 1024) }) : T('apps.yes')) : T('apps.no'));
  add(T('apps.starts_with_tv'), a.boot ? T('apps.yes') : T('apps.no'));
  const parts = [kv];
  if (a.perms.length) {
    parts.push(el('h3', { textContent: T('apps.permissions') }));
    if (keep) parts.push(el('p', { className: 'muted', textContent: T('apps.keep_perms') }));
    for (const p of a.perms) {
      const seg = el('div', { className: 'seg mini' }, ...[[true, T('apps.allow')], [false, T('apps.no')]].map(([on, label]) => el('button', {
        className: p.granted === on ? 'on' : '', textContent: label, disabled: p.locked,
        onclick: async () => {
          if (p.granted === on) return;
          if (keep && !on && !await ask(T('apps.keep_perm_ask', { name: appName(pkg), perm: permName(p.name) }), { ok: T('priv.revoke') })) return;
          const x = await api('/api/app/perm', { pkg, perm: p.name, on });
          if (!x) return;
          if (/^ok$/m.test(x.text)) toast(T(on ? 'apps.perm_allowed' : 'apps.perm_off', { name: permName(p.name) })); else toast(T('apps.perm_failed', { why: friendly(x.text.trim()) }));
          appDetails(pkg, keepHint);
        } })));
      const twin = a.perms.filter((q) => permName(q.name) === permName(p.name)).length > 1;   // fine and coarse location, Bluetooth's three: told apart by their own names
      parts.push(el('div', { className: 'permrow' }, el('span', { title: p.name }, permName(p.name), twin ? el('small', { className: 'muted', textContent: p.name.replace(/^.*\./, '') }) : null),
        p.locked ? el('small', { className: 'muted', textContent: T('apps.system_holds') }) : null, seg));
    }
  } else parts.push(el('p', { className: 'muted', textContent: T('apps.no_perms') }));
  body.replaceChildren(...parts);
}
$('#infoclose').addEventListener('click', () => $('#infodlg').close());

// 💾 the app as a .tar: its APKs and its folders in shared storage. What it keeps in its private folder needs root to read.
function appBackup(pkg) {
  const a = el('a', { href: '/api/app/backup?pkg=' + encodeURIComponent(pkg) }); document.body.append(a); a.click(); a.remove();
  toast(T('apps.backing_up', { name: appName(pkg) }));
}
// 📥 a backup back onto this TV (or onto another one: that is how an app moves between TVs)
$('#restorefile').addEventListener('change', async (e) => {
  const f = e.target.files[0]; e.target.value = ''; if (!f) return;
  if (!/\.tar$/i.test(f.name)) { toast(T('apps.pick_tar')); return; }
  if (await ask(T('apps.restore_ask', { name: f.name }), { ok: T('apps.restore') }))
    upload(f, 'restore', '', () => { quickLoaded = false; loadApps(); loadQuick(); }, e.target.closest('label'));
});
// 🧹 the system's own cache cleaner for every app: nothing anyone would miss, apps just rebuild what they need
$('#appsclean').addEventListener('click', async () => {
  const r = await api('/api/clean', {});
  if (!r) return;
  const freed = cleanFreed(r.text);
  toast(freed === null ? T('apps.cleaned') : freed > 1e6 ? T('apps.cleaned_freed', { size: fmtSize(freed) }) : T('apps.already_clean'));
});

// 📜 an app's own log, live: every 1.5 s the lines since the last one shown
let logPkg = '', logTimer = 0, logSt = null, logPaused = false;
function appLog(pkg) {
  logPkg = pkg; logSt = { last: '', seen: new Set() }; logPaused = false;
  put('#logtitle', 'textContent', '📜 ' + appName(pkg)); $('#logpre').replaceChildren(); put('#lognote', 'hidden', true);
  setLabel($('#logpause'), '⏸', T('apps.pause')); $('#logdlg').showModal(); pollLog();
}
async function pollLog() {
  clearTimeout(logTimer);
  if (!$('#logdlg').open) return;
  const pkg = logPkg;
  if (!logPaused && !document.hidden) {                               // a log left open in a hidden tab costs the TV nothing
    const r = await api('/api/log?pkg=' + encodeURIComponent(pkg) + '&since=' + encodeURIComponent(logSt.last), null, true);
    if (pkg !== logPkg || !$('#logdlg').open) return;
    if (r) {
      const { pid, lines } = logNext(logSt, r.text), pre = $('#logpre');
      put('#lognote', 'hidden', !!pid);
      const end = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;   // follow the end unless the reader scrolled up
      pre.append(...lines.map((l) => el('div', { className: 'l' + logLevel(l), textContent: l })));
      while (pre.childElementCount > 3000) pre.firstElementChild.remove();
      if (end) pre.scrollTop = pre.scrollHeight;
    }
  }
  logTimer = setTimeout(pollLog, 1500);
}
$('#logpause').addEventListener('click', () => { logPaused = !logPaused; setLabel($('#logpause'), logPaused ? '▶' : '⏸', T(logPaused ? 'apps.resume' : 'apps.pause')); });
$('#logcopy').addEventListener('click', async () => {
  const text = [...$('#logpre').children].map((d) => d.textContent).join('\n');
  try { await navigator.clipboard.writeText(text); toast(T('apps.log_copied')); } catch { toast(T('playing.copy_failed')); }
});
$('#logopen').addEventListener('click', async () => { if (await api('/api/app/launch', { pkg: logPkg })) toast(T('apps.what_name', { what: T('apps.open'), name: appName(logPkg) })); });
$('#logclose').addEventListener('click', () => $('#logdlg').close());
$('#logdlg').addEventListener('close', () => clearTimeout(logTimer));


// the search box above the quick-launch grid: tiles whose name does not contain the words are hidden
function filterQuick() { const q = $('#quickq').value.trim().toLowerCase(); document.querySelectorAll('#quick .tile').forEach((b) => { b.hidden = !!q && !b.textContent.toLowerCase().includes(q); }); }
$('#quickq').addEventListener('input', filterQuick);

// 🤖 "which apps can go?": the TV's app lists and memory as a ready question for an AI (or a knowledgeable friend). Nobody can
// tell a Sony service from bloatware by its package name; an AI that knows Android TV mostly can. The text stays editable.
function aiPrompt(r) {
  const vi = window.LANG !== 'en';
  const named = (p) => r.labels && r.labels[p] ? `${p} (${r.labels[p]})` : p;
  const ours = ['dev.tvremote.setup', 'com.tailscale.ipn'].filter((p) => r.user.includes(p));
  const mem = r.memory.map((m) => `${m.pkg}: ${m.mb} MB`).join('\n');
  return (vi ? [
    `Mình có TV ${r.device}, Android ${r.android}, RAM ${r.ram_mb} MB. Mình muốn TV chạy mượt hơn bằng cách tắt (disable) hoặc gỡ những app không cần.`,
    'Xem danh sách bên dưới và chia thành 3 nhóm, mỗi app kèm một lý do ngắn:',
    '1. An toàn để tắt/gỡ (bloatware, demo, quảng cáo, app trùng chức năng)',
    '2. Tắt được nếu không dùng tính năng đó (nói rõ sẽ mất tính năng gì)',
    '3. Không được đụng (hệ thống, Google Play Services, bàn phím, launcher đang dùng, Chromecast, cài đặt, nguồn phát TV...)',
    'Ưu tiên "tắt" (pm disable-user --user 0) vì bật lại được; chỉ đề nghị gỡ với app tự cài. Cuối cùng xếp các app nên tắt theo mức RAM lấy lại được.',
    'Người nhà vẫn dùng: (điền vào, vd YouTube, Netflix, tìm kiếm bằng giọng nói, xem truyền hình bằng ăng-ten)',
    'Cuối câu trả lời, ghi riêng danh sách app id nên tắt (mỗi dòng một id) trong một khối code, để mình dán vào ô "Bật / tắt / gỡ hàng loạt".',
    ours.length ? `Đừng đề xuất tắt ${ours.join(', ')}: mình dùng để điều khiển TV.` : '',
    '', '== App đang tốn RAM nhất lúc này ==', mem,
    '', `== App tự cài (${r.user.length}) ==`, r.user.map(named).join('\n'),
    '', `== App hệ thống đang bật (${r.system.length}) ==`, r.system.join('\n'),
    '', `== App đã tắt sẵn (${r.disabled.length}) ==`, r.disabled.join('\n') || '(không có)',
  ] : [
    `I have a ${r.device} TV, Android ${r.android}, ${r.ram_mb} MB of RAM. I want it to run smoother by disabling or uninstalling apps it does not need.`,
    'Look at the lists below and sort the apps into 3 groups, each with a short reason:',
    '1. Safe to disable/uninstall (bloatware, demos, ads, duplicates)',
    '2. Can be disabled if that feature is not used (say which feature is lost)',
    '3. Do not touch (system, Google Play Services, keyboard, the launcher in use, Chromecast, settings, TV inputs...)',
    'Prefer "disable" (pm disable-user --user 0), which can be undone; suggest uninstalling only for user-installed apps. Finally, rank the apps to disable by the memory they would give back.',
    'My family still uses: (fill in, e.g. YouTube, Netflix, voice search, antenna TV)',
    'At the end, list the app ids to disable on their own (one per line) in a code block, so I can paste them into "Turn on / off / remove in bulk".',
    ours.length ? `Do not suggest disabling ${ours.join(', ')}: I use them to control the TV.` : '',
    '', '== Apps using the most memory right now ==', mem,
    '', `== User-installed apps (${r.user.length}) ==`, r.user.map(named).join('\n'),
    '', `== System apps that are on (${r.system.length}) ==`, r.system.join('\n'),
    '', `== Apps already disabled (${r.disabled.length}) ==`, r.disabled.join('\n') || '(none)',
  ]).filter((l, i, a) => l !== '' || a[i - 1] !== '').join('\n');
}
$('#aiapps').addEventListener('click', async (e) => {                // the TV's memory survey takes a few seconds: the button says so until the text is ready
  const done = UI.busy(e.currentTarget, T('apps.getting_list'));
  try {
    const r = await api('/api/apps/report'); if (!r) return;
    $('#aitext').value = aiPrompt(r); $('#aidlg').showModal();
  } finally { done(); }
});
$('#aiclose').addEventListener('click', () => $('#aidlg').close());
$('#aicopy').addEventListener('click', async () => {
  const t = $('#aitext'); let ok = false;
  try { await navigator.clipboard.writeText(t.value); ok = true; } catch {}
  if (!ok) { t.focus(); t.select(); try { ok = document.execCommand('copy'); } catch {} }   // plain-HTTP pages (the TV's) have no clipboard API
  toast(T(ok ? 'apps.ai_copied' : 'apps.ai_copy_failed'));
});

// 📝 in bulk: paste package names (or a whole AI answer: anything shaped like com.abc.xyz counts), see where each one stands,
// then turn them all off, back on, or remove them. One app after another through the same calls as the buttons on each row.
let bulkKnown = null;                                                // pkg -> { off, keep, version } for every package on the TV
const bulkPkgs = () => [...new Set(($('#bulktext').value.match(/\b[a-zA-Z][\w]*(?:\.[a-zA-Z_][\w]*)+\b/g) || [])
  .filter((p) => p.split('.').every((part) => part.length >= 2)))];   // "e.g" is not a package; anything not on the TV shows as such
async function bulkStatus(force) {
  if (bulkKnown && !force) return bulkKnown;
  const r = await api('/api/apps?system=1', null, true); if (!r) return null;
  seedMeta(r.apps); bulkKnown = new Map(r.apps.map((a) => [a.pkg, { off: !!a.off, keep: !!a.keep, version: a.version || '' }]));
  return bulkKnown;
}
function bulkWhere(pkg) {
  const k = bulkKnown && bulkKnown.get(pkg);
  return !bulkKnown ? ['', '…'] : !k ? ['bad', T('apps.not_on_tv')] : k.keep ? ['warn', T('apps.tv_needs_skip')] : k.off ? ['', T('apps.is_off')] : ['ok', T('apps.is_on')];
}
function renderBulk() {
  const pkgs = bulkPkgs();
  $('#bulklist').replaceChildren(...pkgs.map((p) => {
    const [cls, word] = bulkWhere(p);
    return el('div', { className: 'item bulkrow' }, el('div', { className: 'name' }, el('span', { textContent: appName(p) }), el('small', { textContent: p })), el('span', { className: 'chip ' + cls, textContent: word }));
  }));
  ['#bulkoff', '#bulkon', '#bulkdel'].forEach((id) => { $(id).disabled = !pkgs.length; });
  if (!pkgs.length) put('#bulklog', 'textContent', $('#bulktext').value.trim() ? T('apps.no_ids') : '');
}
let bulkT = 0;
$('#bulktext').addEventListener('input', () => { clearTimeout(bulkT); bulkT = setTimeout(async () => { renderBulk(); await bulkStatus(); renderBulk(); }, 300); });
$('#bulkopen').addEventListener('click', async () => { $('#bulkdlg').showModal(); renderBulk(); await bulkStatus(true); renderBulk(); });
$('#bulkclose').addEventListener('click', () => $('#bulkdlg').close());
async function bulkRun(action, label) {
  await bulkStatus();
  const pkgs = bulkPkgs().filter((p) => { const k = bulkKnown.get(p); return k && !(action !== 'enable' && k.keep) && !(action === 'disable' && k.off) && !(action === 'enable' && !k.off); });
  if (!pkgs.length) { put('#bulklog', 'textContent', T('apps.bulk_none', { what: label.toLowerCase() })); return; }
  if (action === 'uninstall' && !await ask(T('apps.bulk_remove_ask', { n: pkgs.length }), { ok: T('apps.remove'), danger: true })) return;
  if (action === 'disable' && !await ask(T('apps.bulk_disable_ask', { n: pkgs.length }), { ok: T('apps.disable_app') })) return;
  const btns = ['#bulkoff', '#bulkon', '#bulkdel'].map((id) => $(id)); btns.forEach((b) => { b.disabled = true; });
  let ok = 0; const bad = [];
  for (let i = 0; i < pkgs.length; i++) {
    put('#bulklog', 'textContent', T('apps.bulk_progress', { what: label, i: i + 1, n: pkgs.length, name: appName(pkgs[i]) }));
    const r = await api('/api/app/' + action, { pkg: pkgs[i] }, true);
    if (r) ok++; else bad.push(pkgs[i]);
  }
  put('#bulklog', 'textContent', bad.length ? T('apps.bulk_done_bad', { what: label, ok, n: pkgs.length, bad: bad.join(', ') }) : T('apps.bulk_done', { what: label, ok, n: pkgs.length }));
  btns.forEach((b) => { b.disabled = false; });
  await bulkStatus(true); renderBulk(); quickLoaded = false; loadQuick(); if (appsLoaded) loadApps(true);
}
$('#bulkoff').addEventListener('click', () => bulkRun('disable', T('apps.turn_off')));
$('#bulkon').addEventListener('click', () => bulkRun('enable', T('apps.turn_on')));
$('#bulkdel').addEventListener('click', () => bulkRun('uninstall', T('apps.remove')));

