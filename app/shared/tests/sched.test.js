// node --test app/shared/tests/   The app schedule's windows (js/auto.js): an answer saved before windows reads as one window, and the
// summary line lists them all. Rules.kt (RulesTest) holds the rule itself; the dialog's look is checked in Chrome.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../web/js/auto.js'), 'utf8');
// only the pure helpers: from dayName to schedText, with made-up day words
const from = src.indexOf('const dayName');
const to = src.indexOf('let schedWins');
assert.ok(from > 0 && to > from, 'the schedule helpers moved');
const box = { T: (id) => ({ 'auto.every_day': 'Every day' }[id] || id.replace('auto.day_', 'D')) };
vm.createContext(box);
vm.runInContext(src.slice(from, to).replace(/^const /gm, 'var ') + '\nthis.api = { schedWindows, schedText, dayText, SCHED_WINDOWS_MAX };', box);
const { schedWindows, schedText, dayText, SCHED_WINDOWS_MAX } = box.api;

test('an old answer (days, from, to) is one window', () => {
  const w = schedWindows({ days: [1, 2, 3, 4, 5], from: '16:00', to: '18:00', max: 30 });
  assert.deepEqual(JSON.parse(JSON.stringify(w)), [{ days: [1, 2, 3, 4, 5], from: '16:00', to: '18:00' }]);
  assert.equal(schedText({ days: [1, 2, 3, 4, 5], from: '16:00', to: '18:00' }), 'D1 – D5 16:00–18:00');
});

test('windows are listed in order, each with its days', () => {
  const x = { windows: [{ days: [1, 2, 3, 4, 5], from: '16:00', to: '18:00' }, { days: [6, 7], from: '08:00', to: '22:00' }] };
  assert.equal(schedText(x), 'D1 – D5 16:00–18:00 · D6, D7 08:00–22:00');
  assert.equal(schedText({ windows: [{ days: [1, 2, 3, 4, 5, 6, 7], from: '22:00', to: '02:00' }] }), 'Every day 22:00–02:00');   // across midnight is just the two times
});

test('a block or an empty answer has no window; the cap is 8 like Rules.WINDOWS_MAX', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(schedWindows({ block: true }))), []);
  assert.equal(dayText([1]), 'D1');
  assert.equal(SCHED_WINDOWS_MAX, 8);
  assert.match(fs.readFileSync(path.join(__dirname, '../../tvsetup/src/main/kotlin/dev/tvremote/setup/rules/Rules.kt'), 'utf8'), /const val WINDOWS_MAX = 8/);
});
