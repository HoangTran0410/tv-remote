// 📋 Copy settings to another TV (the TV tab's card): take the ticked groups from this TV, switch to the other TV (or keep a file),
// see what would change there, apply. TV Home's settings go through /api/home and TV Setup's rules through /api/rules, as every
// other card does; the choosing and merging is js/tvcopy.js (tested in node). The bundle lives in this page only (and a file the
// person saves): the TV web page of another TV is another origin, so a file is how it crosses there.
let tcHeld = null, tcHost = null, tcPlan = null, tcPin = '', tcGen = 0;
const TC_GROUP = { look: 'tvcopy.g_look', grid: 'tvcopy.g_grid', keys: 'tvcopy.g_keys', bedtime: 'tvcopy.g_bedtime', limits: 'tvcopy.g_limits',
  sched: 'tvcopy.g_sched', total: 'tvcopy.g_total', locks: 'tvcopy.g_locks' };
const TC_STATE = { same: 'tvcopy.s_same', needs_pin: 'tvcopy.s_needs_pin', needs_setup: 'tvcopy.s_needs_setup', needs_home: 'tvcopy.s_needs_home' };
const tcPick = new Set(TVCOPY_GROUPS.map((g) => g.id).filter((id) => id !== 'locks'));   // the PIN's locks are opt-in

async function tcReadTv() {
  const [h, r, a] = await Promise.all([api('/api/home', null, true), api('/api/rules', null, true), api('/api/apps', null, true)]);
  return { home: h && h.installed && h.config ? h.config : null, rules: r && !r.setup && !r.error ? r : null, apps: a && a.apps ? a.apps.map((x) => x.pkg) : null };
}
const tcName = () => ($('#name') && $('#name').textContent) || '';

async function tcTake() {
  if (!tcPick.size) { toast(T('tvcopy.pick_some')); return; }
  const tv = await tcReadTv();
  const b = tvCopyTake([...tcPick], tv.home, tv.rules, tcName());
  if (!b.groups.length) { toast(T('tvcopy.read_failed')); return; }
  if (b.groups.length < tcPick.size) toast(T('tvcopy.some_missing'));
  tcHeld = b; tcHost = curHost; tcPlan = null; tcPin = '';
  tcDraw();
}

async function tcCheck() {
  const gen = ++tcGen;
  const tv = await tcReadTv();
  if (gen !== tcGen) return;                                          // the TV was switched meanwhile
  tcPlan = tvCopyPlan(tcHeld, tv.home, tv.rules, tv.apps, tcPin);
  tcDraw();
}

async function tcApply() {
  if (!tcPlan || !tvCopyAny(tcPlan)) return;
  if (!await ask(T('tvcopy.ask'), { ok: T('tvcopy.apply') })) return;
  let done = 0;
  if (Object.keys(tcPlan.home).length) { if (!await api('/api/home', tcPlan.home)) return; done++; }
  for (const s of tcPlan.steps) { if (!await api('/api/rules', s)) { toast(T('tvcopy.stopped', { n: done })); await tcCheck(); return; } done++; }
  toast(T('tvcopy.done'));
  await tcCheck();
}

function tcSave() { return saveFile('tvcopy-' + tcHeld.date + '.json', JSON.stringify(tcHeld, null, 2)); }
async function tcOpen(f) {
  let b = null;
  try { b = tvCopyRead(JSON.parse(await f.text())); } catch (e) { b = null; }
  if (!b || !b.groups.length) { toast(T('tvcopy.bad_file')); return; }
  tcHeld = b; tcHost = null; tcPlan = null; tcPin = '';
  tcDraw();
}

function tcRowWords(r) {
  return r.state === 'change' ? T('tvcopy.s_change', { n: r.changes }) : T(TC_STATE[r.state]);
}

function tcDraw() {
  const box = $('#tvcopy'); if (!box) return;
  const file = el('input', { type: 'file', accept: '.json,application/json', hidden: true, onchange: (e) => { const f = e.target.files[0]; e.target.value = ''; if (f) tcOpen(f); } });
  const pick = UI.chips(TVCOPY_GROUPS.map((g) => ({ label: T(TC_GROUP[g.id]), on: tcPick.has(g.id), fn: (e) => {
    tcPick.has(g.id) ? tcPick.delete(g.id) : tcPick.add(g.id); e.currentTarget.classList.toggle('on', tcPick.has(g.id)); } })));
  const kids = [UI.note(T('tvcopy.note')), pick, UI.actions(UI.btn(T('tvcopy.take'), tcTake, 'primary'), UI.btn(T('tvcopy.open'), async () => file.click())), file];
  if (tcHeld) {
    kids.push(UI.group(T('tvcopy.held', { name: tcHeld.from || '?', date: tcHeld.date, n: tcHeld.groups.length })));
    if (tcHost !== null && tcHost === curHost) kids.push(UI.warn(T('tvcopy.same_tv')));
    else {
      if (tcPlan) {
        kids.push(UI.list(tcPlan.groups.map((r) => UI.item({ name: T(TC_GROUP[r.id]),
          sub: tcRowWords(r) + (r.dropped.length ? ' · ' + T('tvcopy.s_dropped', { apps: r.dropped.slice(0, 5).map((p) => (p === 'wall' ? T('tvcopy.wall') : appName(p))).join(', ') }) : '') }))));
        if (tcPlan.groups.some((r) => r.state === 'needs_pin')) {
          const pin = el('input', { type: 'password', inputMode: 'numeric', autocomplete: 'new-password', placeholder: T('tvcopy.pin'), value: tcPin, maxLength: 8,
            onchange: () => { tcPin = pin.value.trim(); tcCheck(); } });
          kids.push(UI.line(pin));
        }
        if (!tvCopyAny(tcPlan)) kids.push(UI.state(T('tvcopy.nothing')));
      }
      kids.push(UI.actions(UI.btn(T('tvcopy.check'), tcCheck), tcPlan && tvCopyAny(tcPlan) ? UI.btn(T('tvcopy.apply'), tcApply, 'primary') : null));
    }
    kids.push(UI.actions(UI.btn(T('tvcopy.save'), async () => tcSave(), 'small'), UI.btn(T('tvcopy.forget'), async () => { tcHeld = tcPlan = null; tcDraw(); }, 'small')));
  }
  box.replaceChildren(...kids);
}
onTvChange(() => { tcPlan = null; tcGen++; tcDraw(); });             // the bundle stays: it is meant to cross to the other TV
tcDraw();
