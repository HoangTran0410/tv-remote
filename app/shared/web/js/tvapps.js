// 📦 TV Apps (TvAppsApi.kt): free, open-source apps for a TV from the project's signed list (docs/apps/apps.json). Each one is
// downloaded here from its author's own release, checked against its author's certificate, and installed over ADB; the app updates
// itself afterwards. The TV Apps app on the TV shows the same list (app/tvapps).
let tvappsRunning = false, tvappsCat = '';
onTvChange(() => { tvappsRunning = false; tvappsCat = ''; $('#tvappsbody').replaceChildren(); });

const tvappsWords = (m) => (m && (m[LANG] || m.en)) || '';
const TVAPPS_WHY = { sdk: 'launcher.why_sdk', abi: 'launcher.why_abi' };
const tvappsWhy = (a) => T(TVAPPS_WHY[a.why] || TVAPPS_WHY.abi, { android: androidName(a.min_sdk) });

function tvappsRow(a) {
  const icon = el('img', { className: 'tvappsicon', src: a.icon, alt: '', loading: 'lazy' });
  icon.onerror = () => icon.classList.add('none');
  const act = a.installed
    ? el('button', { className: 'small', textContent: T('tvapps.open'), onclick: () => a.setup ? tvappsSetup(a) : tvappsOpen(a) })
    : el('button', { className: 'small primary', disabled: !!a.why || tvappsRunning, textContent: T('tvapps.install'), onclick: () => a.setup ? tvappsSetup(a) : tvappsInstall(a) });
  const more = el('button', { type: 'button', className: 'small iconbtn', textContent: '⋯', title: T('page.more_actions'), 'aria-label': T('page.more_actions'), onclick: (e) => popover(e.currentTarget, [
    ['ℹ️', T('launcher.details'), () => tvappsDetails(a)],
    a.installed && !a.why && !a.setup ? ['↻', T('tvapps.reinstall'), () => tvappsInstall(a)] : null,
    ['🔗', T('launcher.source'), () => open(a.source, '_blank', 'noopener')],
  ].filter(Boolean)) });
  return UI.item({ icon, name: a.name, sub: tvappsWords(a.desc),
    extra: [a.installed ? (a.disabled ? el('small', { className: 'warn', textContent: T('launcher.disabled') }) : el('small', { className: 'ok', textContent: T('tvapps.on_tv') })) : a.why ? el('small', { className: 'warn', textContent: tvappsWhy(a) }) : null],
    acts: [act, more] });
}

function tvappsDetails(a) {
  const notes = tvappsWords(a.notes);
  const d = el('dialog', { className: 'ask' }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, el('h2', { textContent: a.name }), el('button', { type: 'button', className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
    el('p', { textContent: tvappsWords(a.desc) }),
    notes ? el('p', { className: 'note', textContent: notes }) : null,
    a.why ? el('p', { className: 'warnline', textContent: tvappsWhy(a) }) : null,
    el('p', { className: 'note' }, el('a', { href: a.source, target: '_blank', rel: 'noopener noreferrer', textContent: T('launcher.source') }),
      a.license ? ' · ' + T('launcher.license', { l: a.license }) : '')));
  d.addEventListener('close', () => d.remove());
  document.body.append(d); d.showModal();
}

let tvappsList = null;
function tvappsDraw() {
  const r = tvappsList, body = $('#tvappsbody');
  if (!r) return;
  const shown = r.apps.filter((a) => !tvappsCat || a.cat === tvappsCat);
  const chips = el('div', { className: 'fchips' }, ...[{ id: '', name: null }, ...r.cats].map((c) => el('button', { type: 'button',
    className: c.id === tvappsCat ? 'on' : '', 'aria-pressed': String(c.id === tvappsCat), textContent: c.name ? tvappsWords(c.name) : T('tvapps.all'),
    onclick: () => { tvappsCat = c.id; tvappsDraw(); } })));
  body.replaceChildren(chips, UI.list(shown.map(tvappsRow), T('tvapps.none')), UI.note(T('tvapps.updated', { date: r.updated })));
}

async function tvappsLoad(fresh) {
  const body = $('#tvappsbody');
  if (!tvappsList) body.replaceChildren(UI.empty(T('launcher.loading')));
  const r = await api('/api/tvapps' + (fresh ? '?fresh=1' : ''), null, true);
  if (!r) { if (!tvappsList) body.replaceChildren(UI.warn(T('tvapps.offline')), UI.actions(UI.btn(T('tvapps.retry'), () => tvappsLoad(true)))); return; }
  tvappsList = r; tvappsDraw();
}

$('#tvappsopen').addEventListener('click', async () => { $('#tvappsdlg').showModal(); await tvappsLoad(false); });
$('#tvappsclose').addEventListener('click', () => $('#tvappsdlg').close());

// An app TV Setup installs on a screen of its own ("setup": TV Setup Voice, by its rule, which also removes it): that card of the
// Setup tab, where it is switched on and its language picked. Without TV Setup it cannot run: said, with TV Setup to install first.
const TVAPPS_SETUP = { voice: 'keyscard' };
async function tvappsSetup(a) {
  if (!tvappsList?.has_setup) {
    const setup = tvappsList?.apps.find((x) => x.pkg === 'dev.tvremote.setup');
    if (await ask(T('tvapps.needs_setup', { name: a.name }), { ok: T('tvapps.install_setup') }) && setup) tvappsInstall(setup);
    return;
  }
  $('#tvappsdlg').close();
  goTo('tv', TVAPPS_SETUP[a.setup]);
}

async function tvappsOpen(a) {
  if (await api('/api/app/launch', { pkg: a.pkg })) toast(T('apps.what_name', { what: T('apps.open'), name: a.name }));
}

// like a launcher's: the steps show in the page's Progress card with Stop, the list closes
async function tvappsInstall(a) {
  if (tvappsRunning) return;
  let j = await api('/api/tvapps/install', { id: a.id });
  if (!j) return;
  tvappsRunning = true; $('#tvappsdlg').close();
  const label = T('tvapps.job', { name: a.name }), id = j.id, stop = () => api('/api/cancel', { id }, true);
  const show = (x) => showJob({ ...x, id }, label, stop);
  const step = (x) => x.step === 'install' ? { status: 'running', msg: T('launcher.step_install') } : { status: 'running', msg: T('launcher.step_download', { pct: x.pct || 0 }), pct: x.pct || 0 };
  show(step(j));
  for (let misses = 0; j && j.status === 'running' && misses < 10;) {
    show(step(j));
    await sleep(1000); const r = await api('/api/job?id=' + id, null, true);
    if (!r) { misses++; continue; }
    misses = 0; j = r;
  }
  tvappsRunning = false;
  if (!j || j.status === 'running') show({ status: 'error', msg: T('tvapps.lost'), pct: 0 });
  else if (j.status === 'done') { show({ status: 'done', msg: T('tvapps.done', { name: a.name }), pct: 100 }); toast(T('tvapps.done', { name: a.name })); appsStale(); }
  else if (j.status === 'cancelled') show({ status: 'cancelled', msg: friendly(j.msg), pct: 0 });
  else show({ status: 'error', msg: friendly(j.msg), pct: 0 });
  tvappsList = null;
}
