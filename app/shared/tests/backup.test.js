// node --test app/shared/tests/   "Move to a new TV" (js/backup.js): the file built, read back (unknown things ignored), the merge, the apps to bring.
const test = require('node:test');
const assert = require('node:assert/strict');
const { BACKUP_PARTS, backupBuild, backupRead, backupSummary, backupBundle, backupMergeScenes, backupAppsTodo, backupFileName } = require('../web/js/backup.js');
const { tvCopyPlan } = require('../web/js/tvcopy.js');

const home = { wall: 'night', dock: ['a.one'], keyon: true, keyapps: { 183: 'a.one' }, qrs: { qr1: { wifi: 'secret' } } };
const rules = { bedtime: { on: true, ranges: [{ from: '22:00', to: '06:30' }] }, volume: [], limits: [{ pkg: 'a.one', minutes: 60, used: 4 }], sched: [], total: { min: 0 },
  lock: { pin: true, hash: 'SECRET', totp: 'SECRET', apps: [{ pkg: 'a.one', label: 'Uno' }], mode: 'list', allow: [] }, telegram: { token: 'SECRET' } };
const full = () => backupBuild({ model: 'BRAVIA 4K', name: 'Living room', made: '2026-11-07T10:00:00Z', home, rules, scenes: [{ id: 's1', name: 'Movie', steps: [] }],
  channels: { favourites: [{ id: 'x' }], groups: [] }, tv: { optimize: ['com.bloat.one', 'not a package'], apps: [{ pkg: 'a.one', label: 'Uno' }, { pkg: 'bad' }] } });

test('the file has the version, the TV and every part that could be read', () => {
  const f = full();
  assert.equal(f.v, 1); assert.equal(f.from.model, 'BRAVIA 4K');
  assert.deepEqual(Object.keys(f.parts).sort(), [...BACKUP_PARTS].sort());
  assert.deepEqual(f.parts.optimize, ['com.bloat.one']);
  assert.deepEqual(f.parts.apps, [{ pkg: 'a.one', label: 'Uno' }]);
});

test('no secret is in the file', () => {
  const s = JSON.stringify(full());
  assert.ok(!s.includes('SECRET') && !s.includes('wifi'), 'PIN hash, Authenticator, Telegram token and QR Wi-Fi passwords stay out');
  assert.deepEqual(full().parts.rules.fields.lock, { apps: ['a.one'], mode: 'list', allow: [] });
});

test('a part that could not be read is missing, not empty', () => {
  const f = backupBuild({ model: 'X', made: '2026-11-07', scenes: [], home: null, rules: null, channels: null, tv: null });
  assert.deepEqual(Object.keys(f.parts), ['scenes']);
});

test('reading back: the same parts; unknown parts and fields are ignored; a newer version is read', () => {
  const f = JSON.parse(JSON.stringify(full()));
  f.v = 7; f.parts.hologram = { x: 1 }; f.parts.scenes[0].future = true; f.extra = 1; f.parts.home.groups.push('mystery');
  const r = backupRead(f);
  assert.equal(r.v, 7);
  assert.ok(!('hologram' in r.parts));
  assert.deepEqual(r.parts.home.groups, ['look', 'grid', 'keys'].filter((g) => full().parts.home.groups.includes(g)));
  assert.deepEqual(backupSummary(r).map((p) => p.id), BACKUP_PARTS);
});

test('what is not a backup is null', () => {
  for (const x of [null, [], 'x', {}, { v: 1 }, { v: 1, parts: {} }, { v: 1, parts: { scenes: [] } }, { v: 'one', parts: { scenes: [{ id: 1 }] } }, { tvcopy: 1, groups: [] }])
    assert.equal(backupRead(x), null);
});

test('one bad part does not drop the others', () => {
  const r = backupRead({ v: 1, parts: { home: 'oops', scenes: [{ id: 's' }, 5], optimize: ['a.b', 3, '../x'] } });
  assert.deepEqual(Object.keys(r.parts).sort(), ['optimize', 'scenes']);
  assert.deepEqual(r.parts.scenes, [{ id: 's' }]); assert.deepEqual(r.parts.optimize, ['a.b']);
});

test('home and rules plan through tvcopy for the new TV', () => {
  const r = backupRead(JSON.parse(JSON.stringify(full())));
  const bHome = { dock: [], keyon: false };
  const p = tvCopyPlan(backupBundle(r, 'home'), bHome, null, ['a.one'], '');
  assert.ok(p.home.dock && p.home.keyon === true);
  const q = tvCopyPlan(backupBundle(r, 'rules'), null, { bedtime: { on: false, ranges: [] }, volume: [], limits: [], sched: [], total: {}, lock: {} }, ['a.one'], '');
  assert.equal(q.groups.find((g) => g.id === 'locks').state, 'needs_pin');
  assert.ok(q.steps.some((s) => s.do === 'limit' && s.pkg === 'a.one'));
});

test('scenes merge: same id replaced, others added', () => {
  const m = backupMergeScenes([{ id: 'a', name: 'old' }, { id: 'b' }], [{ id: 'a', name: 'new' }, { id: 'c' }, { name: 'no id' }]);
  assert.deepEqual(m.map((s) => s.name || s.id), ['new', 'b', 'c', 'no id']);
});

test('apps to bring: only those not there, TV Apps first when it knows the app', () => {
  const apps = [{ pkg: 'a.one', label: 'Uno' }, { pkg: 'b.two', label: 'Due' }, { pkg: 'c.three', label: 'Tre' }];
  const t = backupAppsTodo(apps, ['a.one'], [{ id: 'due', pkg: 'b.two', name: 'Due app' }]);
  assert.deepEqual(t, [{ pkg: 'b.two', label: 'Due', how: 'tvapps', id: 'due', name: 'Due app' }, { pkg: 'c.three', label: 'Tre', how: 'store' }]);
  assert.equal(backupAppsTodo(apps, null, null).length, 3);
});

test('file name', () => assert.equal(backupFileName('BRAVIA 4K', '2026-11-07T10:00:00Z'), 'tv-backup-bravia-4k-2026-11-07.json'));
