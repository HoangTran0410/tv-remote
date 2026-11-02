// node --test app/shared/tests/   Family mode's pure logic (js/family.js): the preset over the existing rules, and the undo that
// takes back only what it set.
const test = require('node:test');
const assert = require('node:assert/strict');
const { familyPlan, familyUndo, familySummary } = require('../web/js/family.js');

const empty = { lock: { mode: 'list', allow: [], methods: { pin: true, totp: false, ask: false }, pin: true }, total: {}, bedtime: { on: false } };
const form = { allow: ['a.kids', 'b.kids'], min: 120, bed: { from: '21:00', to: '07:00' } };
const apply = (r, steps) => { // what TV Setup does with the steps, enough for these rules
  r = JSON.parse(JSON.stringify(r));
  for (const s of steps) {
    if (s.do === 'kids') r.lock = { ...r.lock, mode: s.mode, allow: s.allow, methods: s.methods };
    if (s.do === 'total') r.total = s.min ? { min: s.min, days: s.days } : {};
    if (s.do === 'bedtime') r.bedtime = { on: s.on, ranges: s.ranges };
  }
  return r;
};

test('plan on empty rules sets kids mode, total and bedtime', () => {
  const { steps, rec } = familyPlan(empty, form);
  assert.deepEqual(steps.map((s) => s.do), ['kids', 'total', 'bedtime']);
  assert.deepEqual(steps[0].allow, ['a.kids', 'b.kids']);
  assert.equal(steps[1].min, 120);
  assert.deepEqual(steps[2].ranges, [{ from: '21:00', to: '07:00' }]);
  assert.ok(rec);
});

test('undo returns the rules to how they were', () => {
  const { steps, rec } = familyPlan(empty, form);
  const on = apply(empty, steps);
  const off = apply(on, familyUndo(on, rec));
  assert.equal(off.lock.mode, 'list');
  assert.deepEqual(off.lock.allow, []);
  assert.deepEqual(off.total, {});
  assert.equal(off.bedtime.on, false);
  assert.deepEqual(off.bedtime.ranges, []);
});

test('rules made by hand survive: allowed app, other bedtime', () => {
  const mine = { lock: { mode: 'kids', allow: ['mine'], methods: { pin: true, totp: true, ask: false }, pin: true },
    total: {}, bedtime: { on: true, ranges: [{ from: '23:00', to: '05:00' }] } };
  const { steps, rec } = familyPlan(mine, form);
  assert.equal(steps[0].methods.totp, true);
  const on = apply(mine, steps);
  assert.deepEqual(on.lock.allow, ['mine', 'a.kids', 'b.kids']);
  const off = apply(on, familyUndo(on, rec));
  assert.equal(off.lock.mode, 'kids');
  assert.deepEqual(off.lock.allow, ['mine']);
  assert.deepEqual(off.bedtime.ranges, [{ from: '23:00', to: '05:00' }]);
  assert.equal(off.bedtime.on, true);
});

test('an app already allowed is not removed; a total changed since is left alone', () => {
  const r0 = { ...empty, lock: { ...empty.lock, mode: 'kids', allow: ['a.kids'] } };
  const { steps, rec } = familyPlan(r0, form);
  let on = apply(r0, steps);
  on = apply(on, [{ do: 'total', min: 45, days: [1] }]);               // a parent edits the total afterwards
  const off = apply(on, familyUndo(on, rec));
  assert.deepEqual(off.lock.allow, ['a.kids']);
  assert.equal(off.total.min, 45);
});

test('a previous total and its days come back', () => {
  const r0 = { ...empty, total: { min: 200, days: [6, 7] } };
  const { steps, rec } = familyPlan(r0, { allow: [], min: 90, bed: null });
  assert.deepEqual(steps.map((s) => s.do), ['total']);
  assert.deepEqual(steps[0].days, [6, 7]);
  const on = apply(r0, steps), off = apply(on, familyUndo(on, rec));
  assert.deepEqual(off.total, { min: 200, days: [6, 7] });
});

test('an empty form plans nothing; an old TV Setup bedtime (from/to) reads as one range', () => {
  assert.equal(familyPlan(empty, { allow: [], min: 0, bed: null }).rec, null);
  const old = { ...empty, bedtime: { on: true, from: '22:00', to: '06:00' } };
  assert.deepEqual(familyPlan(old, form).steps[2].ranges.map((x) => x.from), ['22:00', '21:00']);
});

test('the summary lists only what still stands', () => {
  const { steps, rec } = familyPlan(empty, form);
  const on = apply(empty, steps);
  assert.deepEqual(familySummary(on, rec).map((s) => s.id), ['family.sum_apps', 'family.sum_time', 'family.sum_bed']);
  const gone = apply(on, [{ do: 'total', min: 0 }]);
  assert.deepEqual(familySummary(gone, rec).map((s) => s.id), ['family.sum_apps', 'family.sum_bed']);
});
