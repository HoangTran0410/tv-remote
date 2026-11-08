// Family mode, shown as "Quick setup" at the top of the Kids mode card (one idea for a parent, not two cards): it sets a parent's preset in one go (apps for kids, TV time a day, a bedtime, a PIN)
// through the rules the other cards already write (kids mode `do:'kids'`, `do:'total'`, `do:'bedtime'`, the PIN), and keeps on the TV what it
// changed, so "turn off" removes exactly that and nothing the person made by hand. Nothing runs until used (tests/family.test.js).

const FAMILY_TIMES = [60, 90, 120, 180];
const FAMILY_ALL_DAYS = [1, 2, 3, 4, 5, 6, 7];
const sameRange = (a, b) => a.from === b.from && a.to === b.to;

// ---- pure: from the rules on the TV and the form to the steps (rule changes) and the record of what they set ----
// rules: RULES (lock {mode, allow, methods}, total {min, days}, bedtime {on, ranges | from, to}); form: {allow: [pkg], min: 0 | minutes, bed: null | {from, to}}
function familyBedRanges(r) { const b = (r && r.bedtime) || {}; return b.ranges || (b.from ? [{ from: b.from, to: b.to }] : []); }
function familyPlan(rules, form) {
  const steps = [], rec = { v: 1, lock: null, total: null, bed: null };
  const lock = (rules && rules.lock) || {}, m = lock.methods || {};
  const allow0 = lock.allow || [], added = (form.allow || []).filter((p, i, a) => a.indexOf(p) === i && !allow0.includes(p));
  if ((form.allow || []).length) {
    rec.lock = { mode0: lock.mode === 'kids' ? 'kids' : 'list', added, allow: [...allow0, ...added] };
    steps.push({ do: 'kids', mode: 'kids', allow: rec.lock.allow, methods: { pin: true, totp: !!m.totp, ask: !!m.ask } });
  }
  if (form.min > 0) {
    const t = (rules && rules.total) || {};
    rec.total = { min0: t.min || 0, days0: t.days && t.days.length ? t.days : FAMILY_ALL_DAYS, min: form.min };
    steps.push({ do: 'total', min: form.min, days: rec.total.days0 });
  }
  if (form.bed) {
    const ranges = familyBedRanges(rules), b = (rules && rules.bedtime) || {}, has = ranges.some((x) => sameRange(x, form.bed));
    rec.bed = { range: form.bed, added: !has, on0: !!b.on };
    const next = has ? ranges : [...ranges, form.bed];
    steps.push({ do: 'bedtime', on: true, ranges: next, from: next[0].from, to: next[0].to });
  }
  return { steps, rec: (rec.lock || rec.total || rec.bed) ? rec : null };
}
// the steps that take back only what `rec` set and still stands; a rule the person changed since is left alone
function familyUndo(rules, rec) {
  const steps = [];
  if (!rec) return steps;
  const lock = (rules && rules.lock) || {}, m = lock.methods || {};
  if (rec.lock && lock.mode === 'kids') {
    const allow = (lock.allow || []).filter((p) => !rec.lock.added.includes(p));
    steps.push({ do: 'kids', mode: rec.lock.mode0, allow, methods: { pin: true, totp: !!m.totp, ask: !!m.ask } });
  }
  const t = (rules && rules.total) || {};
  if (rec.total && t.min === rec.total.min) steps.push({ do: 'total', min: rec.total.min0, days: rec.total.min0 ? rec.total.days0 : t.days });
  if (rec.bed && rec.bed.added) {
    const left = familyBedRanges(rules).filter((x) => !sameRange(x, rec.bed.range));
    if (left.length !== familyBedRanges(rules).length) {
      const on = left.length > 0 && rec.bed.on0;
      steps.push({ do: 'bedtime', on, ranges: left, from: left.length ? left[0].from : '22:00', to: left.length ? left[0].to : '06:30' });
    }
  }
  return steps;
}
// what is on now of the record: [{id, vars}] for the summary lines
function familySummary(rules, rec) {
  const out = [];
  if (!rec) return out;
  const lock = (rules && rules.lock) || {};
  if (rec.lock && lock.mode === 'kids') out.push({ id: 'family.sum_apps', vars: { n: (lock.allow || []).length } });
  const t = (rules && rules.total) || {};
  if (rec.total && t.min) out.push({ id: 'family.sum_time', vars: { min: t.min } });
  if (rec.bed && familyBedRanges(rules).some((x) => sameRange(x, rec.bed.range)) && ((rules.bedtime || {}).on)) out.push({ id: 'family.sum_bed', vars: rec.bed.range });
  return out;
}

if (typeof module !== 'undefined') module.exports = { familyPlan, familyUndo, familySummary, familyBedRanges };

// ---- the card ----
// The card's code at the top level, not inside an if block: a page saw famGet missing from renderFamily (census 7ce62d6c, the
// Computer app's WebKitGTK on Linux); block-scoped declarations were the one thing only this file did. Nothing here runs in node.
const FM = { open: false, allow: [], min: 120, bedOn: true, from: '21:00', to: '07:00' };     // the form's defaults
// the record of what it set lives on the TV (rules "family"), so every phone and computer sees the same card and can turn it off
const famGet = () => (RULES && RULES.family) || null;
const famPut = (rec) => kidsSend(rec ? { do: 'family', rec } : { do: 'family' });
const famRun = async (steps) => {                                  // each step through the rules call the other cards use; stops at the first that fails
  for (const s of steps) if (!await kidsSend(s)) return false;
  return true;
};

async function familyOn() {
  const r = RULES; if (!r || !await kidsGate() || !await setupFor('family')) return;
  if (!FM.allow.length) return toast(T('family.pick_one'));
  if (FM.bedOn && FM.from === FM.to) return toast(T('family.bed_bad'));
  if (FM.bedOn && familyBedRanges(r).length && !await setupFor('bedtimes')) return;
  if (!(r.lock && r.lock.pin) && !await askPin()) return;          // kids mode opens with a PIN
  const plan = familyPlan(RULES, { allow: FM.allow, min: FM.min, bed: FM.bedOn ? { from: FM.from, to: FM.to } : null });
  if (!plan.rec) return;
  if (await famRun(plan.steps) && await famPut(plan.rec)) { FM.open = false; toast(T('family.done')); }
  if (RULES) renderFamily(RULES);
}
async function familyOff(rec) {
  if (!await ask(T('family.off_ask'), { ok: T('family.off_btn'), danger: true })) return;
  if (await famRun(familyUndo(RULES, rec)) && await famPut(null)) toast(T('family.off_done'));
  if (RULES) renderFamily(RULES);
}

function renderFamily(r) {
  const card = $('#familybox'); if (!card) return;                  // kids.js puts it at the top of #kidscard
  const rec = famGet(), sum = familySummary(r, rec);
  if (rec && sum.length) {                                         // on: what it set, still standing
    card.replaceChildren(UI.group(T('family.title'), UI.state(T('family.on_state')),
      ...sum.map((s) => UI.note(T(s.id, s.id === 'family.sum_time' ? { time: mins(s.vars.min) } : s.vars))),
      UI.note(T('family.pin_stays')),
      UI.actions(act(T('family.off_btn'), () => familyOff(rec), 'danger'))));
    return;
  }
  if (!FM.open) {                                                  // folded: the kids card below is the main thing
    card.replaceChildren(UI.group(T('family.title'), UI.note(T('family.note')),
      UI.actions(UI.btn(T('family.start'), () => { FM.open = true; renderFamily(r); }, 'primary'))));
    return;
  }
  const names = FM.allow.map((p) => {
    const icon = appIcon(p); onMeta(p, (m) => icon.fill(m));
    return el('div', { className: 'item' }, icon, el('div', { className: 'name' }, el('span', { textContent: appName(p) })),
      el('div', { className: 'acts' }, act('🗑', () => { FM.allow = FM.allow.filter((x) => x !== p); renderFamily(r); }, 'danger', T('auto.kids_allow_remove'))));
  });
  const from = el('input', { type: 'time', value: FM.from, 'aria-label': T('family.bed_from') }), to = el('input', { type: 'time', value: FM.to, 'aria-label': T('family.bed_to') });
  from.addEventListener('change', () => { FM.from = from.value; }); to.addEventListener('change', () => { FM.to = to.value; });
  card.replaceChildren(UI.group(T('family.title'), UI.note(T('family.note'))),
    UI.group(T('family.apps'), UI.list(names, T('family.apps_none')),
      UI.actions(act(T('family.apps_add'), async () => {
        if (!await kidsGate()) return;
        if (!pickApps.length) await loadPickApps();
        const pkg = await chooseApp(T('family.apps_pick'), choosable().filter((p) => !FM.allow.includes(p))); if (!pkg) return;
        FM.allow = [...FM.allow, pkg]; renderFamily(r);
      }))),
    UI.group(T('family.time'), UI.chips([...FAMILY_TIMES.map((m) => ({ label: mins(m), on: FM.min === m, fn: () => { FM.min = m; renderFamily(r); } })),
      { label: T('family.time_none'), on: FM.min === 0, fn: () => { FM.min = 0; renderFamily(r); } }])),
    UI.group(T('family.bed'), UI.chips([{ label: T('family.bed_set'), on: FM.bedOn, fn: () => { FM.bedOn = true; renderFamily(r); } },
      { label: T('family.bed_none'), on: !FM.bedOn, fn: () => { FM.bedOn = false; renderFamily(r); } }]),
      FM.bedOn ? UI.line(from, to) : null),
    UI.note(T((r.lock && r.lock.pin) ? 'family.pin_have' : 'family.pin_need')),
    UI.actions(UI.btn(T('family.on_btn'), familyOn, 'primary'), act(T('common.cancel'), () => { FM.open = false; renderFamily(r); })));
}
if (typeof document !== 'undefined') onTvChange(() => { FM.allow = []; FM.open = false; });   // only in the page; node's tests load the plans above
