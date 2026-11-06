// 🧳 Move to a new TV (the TV tab's card, next to "Copy settings"): one file for what the other cards keep apart. Taking reads each
// part through the route its own card uses (/api/home, /api/rules, /api/scenes, /api/channels/export, and /api/backup for the
// TV's own: the apps turned off, the apps installed); restoring shows what the file holds, the person ticks what to restore, and
// each part goes back through the same route (a part that fails says so, the rest go on). The file's logic is js/backup.js.
let bkFile = null, bkRes = {}, bkTodo = null, bkGen = 0, bkBusy = false;
const bkPick = new Set();
const BK_WORDS = { home: 'backup.p_home', rules: 'backup.p_rules', scenes: 'backup.p_scenes', channels: 'backup.p_channels', optimize: 'backup.p_optimize' };

async function bkTake() {
  const [h, r, s, c, tv] = await Promise.all([api('/api/home', null, true), api('/api/rules', null, true), api('/api/scenes', null, true),
    api('/api/channels/export', null, true), api('/api/backup', null, true)]);
  const o = { model: tv && tv.model || '', name: tcName(), made: new Date().toISOString(), tv,
    home: h && h.installed && h.config ? h.config : null, rules: r && !r.setup && !r.error ? r : null,
    scenes: s && Array.isArray(s.scenes) ? s.scenes : null, channels: c && !c.error ? c : null };
  const file = backupBuild(o);
  if (!Object.keys(file.parts).length) { toast(T('backup.read_failed')); return; }
  if (Object.keys(file.parts).length < 6) toast(T('backup.some_missing'));
  const a = el('a', { href: URL.createObjectURL(new Blob([JSON.stringify(file, null, 2)], { type: 'application/json' })), download: backupFileName(o.model || o.name, o.made) });
  document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 4000);
  toast(T('backup.saved'));
}

async function bkOpen(f) {
  let b = null;
  try { b = backupRead(JSON.parse(await f.text())); } catch (e) { b = null; }
  if (!b) { toast(T('backup.bad_file')); return; }
  bkFile = b; bkRes = {}; bkTodo = null; bkPick.clear();
  backupSummary(b).forEach((p) => { if (p.id !== 'apps') bkPick.add(p.id); });
  bkDraw(); bkLoadTodo();
}

// the apps of the file that this TV lacks, each with the way to get it
async function bkLoadTodo() {
  const gen = ++bkGen;
  if (!bkFile || !bkFile.parts.apps) return;
  const [tv, cat] = await Promise.all([tcReadTv(), api('/api/tvapps', null, true)]);
  if (gen !== bkGen) return;
  bkTodo = backupAppsTodo(bkFile.parts.apps, tv.apps, cat && cat.apps);
  bkDraw();
}

const bkOk = (text) => ({ ok: true, text });
const bkNo = (text) => ({ ok: false, text });
const bkJoin = (...a) => a.filter(Boolean).join(' · ');

async function bkDo(id) {
  const p = bkFile.parts[id];
  if (id === 'home' || id === 'rules') {
    const tv = await tcReadTv();
    if (id === 'rules' && !await setupFor('rules')) return bkNo(T('backup.f_setup'));
    const plan = tvCopyPlan(backupBundle(bkFile, id), tv.home, tv.rules, tv.apps, '');
    if (plan.groups.some((g) => g.state === 'needs_home')) return bkNo(T('backup.f_home'));
    if (plan.groups.some((g) => g.state === 'needs_setup')) return bkNo(T('backup.f_setup'));
    const pin = plan.groups.some((g) => g.state === 'needs_pin') ? T('backup.r_pin') : '';
    const left = plan.groups.reduce((n, g) => n + g.dropped.length, 0);
    const leftWords = left ? T('backup.r_left', { n: left }) : '';
    if (!tvCopyAny(plan)) return bkOk(bkJoin(T('backup.r_same'), leftWords, pin));
    let done = 0;
    if (Object.keys(plan.home).length) { if (!await api('/api/home', plan.home)) return bkNo(T('backup.f_failed')); done++; }
    for (const s of plan.steps) { if (!await api('/api/rules', s)) return bkNo(T('backup.f_stopped', { n: done })); done++; }
    return bkOk(bkJoin(T('backup.r_changed', { n: plan.groups.reduce((n, g) => n + g.changes, 0) }), leftWords, pin));
  }
  if (id === 'scenes') {
    const cur = await api('/api/scenes', null, true);
    if (!cur) return bkNo(T('backup.f_failed'));
    const r = await api('/api/scenes', { scenes: backupMergeScenes(cur.scenes, p) });
    return r ? bkOk(T('backup.r_scenes', { n: (r.scenes || []).length })) : bkNo(T('backup.f_failed'));
  }
  if (id === 'channels') {
    const r = await api('/api/channels/import', { mode: 'merge', doc: p });
    const i = r && r.imported;
    return i && i.ok ? bkOk(T('backup.r_channels', { n: i.added || 0 })) : bkNo(T('backup.f_failed'));
  }
  if (id === 'optimize') {
    const r = await api('/api/backup/restore', { optimize: p });
    if (!r) return bkNo(T('backup.f_failed'));
    const skip = (r.missing || []).length + (r.kept || []).length + (r.failed || []).length;
    return bkOk(bkJoin(T('backup.r_opt', { n: r.done || 0 }), skip ? T('backup.r_skip', { n: skip }) : ''));
  }
  return bkNo(T('backup.f_failed'));
}

async function bkRestore() {
  const ids = BACKUP_PARTS.filter((id) => bkFile.parts[id] && bkPick.has(id));
  if (!ids.length) { toast(T('backup.pick_some')); return; }
  if (!await ask(T('backup.ask'), { ok: T('backup.restore') })) return;
  bkBusy = true; bkRes = {};
  for (const id of ids) {                                             // one part failing never stops the others
    try { bkRes[id] = await bkDo(id); } catch (e) { bkRes[id] = bkNo(friendly(e && e.message)); }
    bkDraw();
  }
  bkBusy = false; bkDraw(); bkLoadTodo();
}

function bkDraw() {
  const box = $('#backup'); if (!box) return;
  const file = el('input', { type: 'file', accept: '.json,application/json', hidden: true, onchange: (e) => { const f = e.target.files[0]; e.target.value = ''; if (f) bkOpen(f); } });
  const kids = [UI.note(T('backup.note')), UI.actions(UI.btn(T('backup.save'), bkTake, 'primary'), UI.btn(T('backup.open'), async () => file.click())), file];
  if (bkFile) {
    const sum = backupSummary(bkFile).filter((p) => p.id !== 'apps');
    kids.push(UI.group(T('backup.held', { name: bkFile.from.name || bkFile.from.model || '?', date: bkFile.made.slice(0, 10) })));
    kids.push(UI.chips(sum.map((p) => ({ label: T(BK_WORDS[p.id]), on: bkPick.has(p.id), disabled: bkBusy,
      fn: (e) => { bkPick.has(p.id) ? bkPick.delete(p.id) : bkPick.add(p.id); e.currentTarget.classList.toggle('on', bkPick.has(p.id)); } }))));
    kids.push(UI.list(sum.map((p) => {
      const r = bkRes[p.id];
      return UI.item({ name: T(BK_WORDS[p.id]), sub: r ? (r.ok ? '✓ ' : '✗ ') + r.text : T('backup.count', { n: p.n }) });
    })));
    if (sum.length) kids.push(UI.actions(UI.btn(T('backup.restore'), bkRestore, 'primary')));
    if (bkFile.parts.apps) {
      kids.push(UI.group(T('backup.apps_h'), UI.note(T('backup.apps_note'))));
      kids.push(!bkTodo ? UI.empty('…') : UI.list(bkTodo.map((a) => UI.item({ name: a.label, sub: a.pkg,
        acts: [a.how === 'tvapps' ? UI.btn(T('backup.apps_tvapps'), () => tvappsInstall({ id: a.id, name: a.name }), 'small') : UI.btn(T('backup.apps_store'), async () => storePage(a.pkg), 'small')] })), T('backup.apps_none')));
    }
    kids.push(UI.actions(UI.btn(T('backup.forget'), async () => { bkFile = bkTodo = null; bkRes = {}; bkGen++; bkDraw(); }, 'small')));
  }
  box.replaceChildren(...kids);
}
onTvChange(() => { bkTodo = null; bkRes = {}; bkGen++; bkDraw(); bkLoadTodo(); });   // the file stays: it is meant to cross to the other TV
bkDraw();
