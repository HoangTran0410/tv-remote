// node --test app/shared/tests/   sleepMinutes (js/auto.js) reads each text as tests/sleep_cases.json says, the table SleepTest.kt (the Phone app's copy) reads too.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../web/js/auto.js'), 'utf8');
const from = src.indexOf('function sleepMinutes');
const to = src.indexOf('\n}\n', from) + 3;
assert.ok(from > 0 && to > from, 'sleepMinutes moved');
const box = {};
vm.createContext(box);
vm.runInContext(src.slice(from, to) + '\nthis.sleepMinutes = sleepMinutes;', box);
const table = JSON.parse(fs.readFileSync(path.join(__dirname, 'sleep_cases.json'), 'utf8'));
const [h, m] = table.now.split(':').map(Number);

for (const c of table.cases) test(`sleepMinutes ${JSON.stringify(c.in)} at ${table.now}`, () => {
  assert.equal(box.sleepMinutes(c.in, new Date(2026, 9, 24, h, m, 0, 0)), c.min);
});

// zoneShift: the TV's zone minus this device's, so "23:30" and the times shown are the TV's wall clock
const zf = src.indexOf('function zoneShift');
const zt = src.indexOf('\n}\n', zf) + 3;
assert.ok(zf > 0 && zt > zf, 'zoneShift moved');
vm.runInContext(src.slice(zf, zt) + '\nthis.zoneShift = zoneShift;', box);
test('zoneShift turns this device\'s wall clock into the TV\'s', () => {
  const t = Date.UTC(2026, 10, 20, 15, 30);                                 // 22:30 in Hanoi (no summer time)
  const here = new Date(t + box.zoneShift('Asia/Ho_Chi_Minh', t));
  assert.equal(here.getHours() + ':' + here.getMinutes(), '22:30');
  const local = Intl.DateTimeFormat().resolvedOptions().timeZone;
  assert.equal(box.zoneShift(local, t), 0);                                 // the same zone: nothing moves
  assert.equal(box.zoneShift('', t), 0);
  assert.equal(box.zoneShift('Not/AZone', t), 0);                           // unknown to this browser: this device's
  const ny = new Date(t + box.zoneShift('America/New_York', t));            // EST in November: 10:30
  assert.equal(ny.getHours() + ':' + ny.getMinutes(), '10:30');
  const sleepTv = box.sleepMinutes('23:00', new Date(t + box.zoneShift('Asia/Ho_Chi_Minh', t)));
  assert.equal(sleepTv, 30);                                                // 23:00 on the TV is half an hour away, whatever zone this device is in
});
