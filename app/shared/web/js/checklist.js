// "Set up your TV" (card at the top of the TV tab): what is set up on this TV and what is missing, each missing item with the button
// that already exists for it. It asks the TV nothing itself and runs no timer: the loaders of the page tell it what they read
// (checkSet). Rows: checklist_rows.js. Hidden once the required rows are done, or hidden by the person (per TV, kept in this browser).

const CHECK_GO = {
  adb: () => adbHelp(), setup_install: () => installSetupApk(), setup_update: () => updateSetup(), gallery: () => $('#launchopen').click(),
  home_update: () => homeUpdate(), keys: async () => { if (await homeLink.write({ keyon: true })) homeCard(); },
  mic: async () => { if (await setupFor('mic') && await api('/api/rules', { do: 'mic', on: true })) { toast(T('mic.enabled')); loadHold(true); } },
  chan: () => chanInstall(), game: () => gameInstall(),
};
const CHECK_WORDS = { adb: 'check.b_adb', setup_install: 'check.b_install', setup_update: 'check.b_update', gallery: 'check.b_home', home_update: 'check.b_update',
  keys: 'check.b_on', mic: 'check.b_on', install: 'check.b_install', update: 'check.b_update' };
const CHECK_NAME = { adb: 'check.adb', setup: 'check.setup', home: 'check.home', mic: 'check.mic', keys: 'check.keys', chan: 'check.chan', game: 'check.game' };
const checkOff =() => { try { return JSON.parse(localStorage.getItem('checkOff') || '{}') || {}; } catch (e) { return {}; } };
let checkFacts = {}, checkTimer = 0;                                    // what the loaders last told (of the TV on screen)
// A loader that has read one thing tells it here (null: could not read); several tell within a moment make one draw.
function checkSet(key, value) { checkFacts[key] = value; clearTimeout(checkTimer); checkTimer = setTimeout(checkPaint, 250); }
function checkPaint() {
  const card = $('#checkcard'); if (!card) return;
  const key = curHost || '-', F = checkFacts;
  const rows = checklistRows({ adb: ADB.state, setup: F.setup || null, home: F.home || null, mic: F.mic === undefined ? null : F.mic, chan: F.chan || null, game: F.game || null });
  card.hidden = picking || NATIVE() || !!checkOff()[key] || !checklistOpen(rows);
  if (card.hidden) return;
  const done = rows.filter((r) => r.done).length;
  const hide = () => { const o = checkOff(); o[key] = 1; try { localStorage.setItem('checkOff', JSON.stringify(o)); } catch (e) { } checkPaint(); };
  card.replaceChildren(
    UI.head(T('check.title'), el('span', { className: 'muted', textContent: T('check.count', { done, n: rows.length }) }),
      el('button', { type: 'button', className: 'small', textContent: T('common.hide'), onclick: hide })),
    UI.list(rows.map((r) => UI.item({ icon: el('span', { className: 'mark', textContent: r.done ? '✓' : '' }), name: T(CHECK_NAME[r.id]),
      sub: r.optional && !r.done ? T('check.optional') : '',
      acts: r.act ? [UI.btn(T(CHECK_WORDS[r.act]), () => CHECK_GO[r.act === 'install' || r.act === 'update' ? r.id : r.act](), 'small' + (r.optional ? '' : ' primary'))] : [] })), ''));
}
onTvChange(() => { checkFacts = {}; checkPaint(); });
