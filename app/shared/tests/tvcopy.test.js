// node --test app/shared/tests/   "Copy settings to another TV" (js/tvcopy.js): what is taken from TV A, and the plan for TV B.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { TVCOPY_LOOK, tvCopyTake, tvCopyRead, tvCopyPlan, tvCopyAny } = require('../web/js/tvcopy.js');

const ALL = ['look', 'grid', 'keys', 'bedtime', 'limits', 'sched', 'total', 'locks'];
const homeA = { wall: 'night', walls: ['night'], widgets: ['clock', 'qr1'], wsize: { clock: 'small', qr1: 'big' }, cols: 6, shade: 40,
  dock: ['a.one', 'b.two'], hidden: ['c.three'], order: ['a.one', 'tile:x.y/z', 'pin:b.two/p'], tiles: ['tile:x.y/z', 'pin:b.two/p'], names: { 'a.one': 'Uno', 'c.three': 'Tres' },
  folders: { f1: { name: 'Kids', apps: ['a.one', 'c.three'] } }, keyon: true, keyapps: { 183: 'a.one', 184: 'c.three', 185: 'do:controlcenter' }, autoon: true, autoapp: 'c.three', autoall: false,
  qrs: { qr1: { wifi: 'secret' } }, lang: 'vi' };
const rulesA = { bedtime: { on: true, ranges: [{ from: '22:00', to: '06:30' }], from: '22:00', to: '06:30' }, volume: [{ from: '22:00', to: '07:00', max: 20 }], ram: { on: true, pct: 85 },
  limits: [{ pkg: 'a.one', minutes: 60, used: 5 }, { pkg: 'c.three', minutes: 30 }], sched: [{ pkg: 'a.one', windows: [{ days: [6, 7], from: '08:00', to: '20:00' }], max: 90, now: true }, { pkg: 'c.three', block: true }],
  total: { min: 120, days: [1, 2, 3], warn: 5, used: 3 }, lock: { pin: true, apps: [{ pkg: 'a.one', label: 'Uno' }], mode: 'list', allow: ['a.one'] } };
const bundle = () => tvCopyTake(ALL, homeA, rulesA, 'Living room');
const emptyB = { bedtime: { on: false, ranges: [{ from: '22:00', to: '06:30' }] }, volume: [], ram: { on: false, pct: 90 }, limits: [], sched: [], total: { min: 0, days: [], warn: 5 }, lock: { pin: false, apps: [], mode: 'list', allow: [] } };
const row = (p, id) => p.groups.find((g) => g.id === id);

test('the look keys are HomeConfig.THEME_KEYS', () => {
  const kt = fs.readFileSync(path.join(__dirname, '../src/home/kotlin/dev/tvremote/home/HomeConfig.kt'), 'utf8');
  const m = /val THEME_KEYS = listOf\(([^)]*)\)/.exec(kt);
  assert.deepEqual([...m[1].matchAll(/"([a-z0-9]+)"/g)].map((x) => x[1]), TVCOPY_LOOK);
});

test('theme files on the page take every HomeConfig.THEME_KEYS key (an import dropped wplace)', () => {
  const js = fs.readFileSync(path.join(__dirname, '../web/js/home.js'), 'utf8');
  const m = /const HOME_THEME_KEYS = \[([^\]]*)\]/.exec(js);
  assert.deepEqual([...m[1].matchAll(/'([a-z0-9]+)'/g)].map((x) => x[1]), TVCOPY_LOOK);
});

test('only the ticked groups are taken, and what is not a setting is left out', () => {
  const b = tvCopyTake(['limits', 'keys'], homeA, rulesA, 'A');
  assert.deepEqual(b.groups.sort(), ['keys', 'limits']);
  assert.deepEqual(Object.keys(b.home).sort(), ['autoall', 'autoapp', 'autoon', 'keyapps', 'keyon']);
  assert.deepEqual(b.rules.limits, [{ pkg: 'a.one', minutes: 60 }, { pkg: 'c.three', minutes: 30 }]);   // "used" is a count of A's day
  assert.equal(JSON.stringify(bundle()).includes('secret'), false);                                     // the QR codes never travel
});

test('a file that is not ours is refused, ours is read back', () => {
  assert.equal(tvCopyRead({ hello: 1 }), null);
  assert.equal(tvCopyRead(null), null);
  const r = tvCopyRead(JSON.parse(JSON.stringify(bundle())));
  assert.equal(r.from, 'Living room'); assert.equal(r.groups.length, 8);
  assert.deepEqual(tvCopyRead({ tvcopy: 1, groups: ['look', 'nope'] }).groups, ['look']);
});

test('apps missing on B are dropped from the grid, keys and the names; its own apps stay', () => {
  const p = tvCopyPlan(bundle(), { cols: 4, tilesAvail: [{ key: 'tile:x.y/z' }] }, emptyB, ['a.one', 'b.two']);
  assert.deepEqual(p.home.dock, ['a.one', 'b.two']);
  assert.deepEqual(p.home.hidden, []);
  assert.deepEqual(p.home.folders.f1.apps, ['a.one']);
  assert.deepEqual(p.home.names, { 'a.one': 'Uno' });
  assert.deepEqual(p.home.keyapps, { 183: 'a.one', 185: 'do:controlcenter' });
  assert.equal(p.home.autoapp, '');
  assert.ok(row(p, 'grid').dropped.includes('c.three'));
  const q = tvCopyPlan(bundle(), { tilesAvail: [] }, emptyB, ['a.one', 'b.two']);
  assert.deepEqual(q.home.tiles, ['pin:b.two/p']);                                 // a live widget B does not offer
  const ours = tvCopyPlan(tvCopyTake(['grid'], { dock: ['dev.tvremote.setup'] }, null), {}, null, ['x']);
  assert.deepEqual(ours.home.dock, ['dev.tvremote.setup']);
});

test('with B\'s apps unknown nothing is dropped', () => {
  const p = tvCopyPlan(bundle(), {}, emptyB, null);
  assert.deepEqual(p.home.hidden, ['c.three']);
});

test('the look leaves out A\'s own photo, video and QR widgets', () => {
  const b = tvCopyTake(['look'], { wall: 'photo2', walls: ['photo2'], widgets: ['clock', 'qr1'], wsize: { clock: 'big', qr1: 'big' }, cols: 5 }, null);
  const p = tvCopyPlan(b, { wall: 'night', walls: ['night'], cols: 4 }, null, []);
  assert.equal(p.home.wall, undefined); assert.equal(p.home.walls, undefined);
  assert.deepEqual(p.home.widgets, ['clock']); assert.deepEqual(p.home.wsize, { clock: 'big' }); assert.equal(p.home.cols, 5);
  const v = tvCopyTake(['look'], { wall: 'vid:0123abcd', walls: ['vid:0123abcd'] }, null);
  assert.deepEqual(tvCopyPlan(v, {}, null, []).home, {});
});

test('what is already the same on B is "same", and sends nothing', () => {
  const b = tvCopyTake(['look', 'limits'], homeA, rulesA, 'A');
  const same = tvCopyPlan(b, { ...homeA, widgets: ['clock'], wsize: { clock: 'small' } }, rulesA, ['a.one', 'c.three']);
  assert.equal(row(same, 'look').state, 'same'); assert.equal(row(same, 'limits').state, 'same');
  assert.deepEqual(same.home, {}); assert.deepEqual(same.steps, []); assert.equal(tvCopyAny(same), false);
});

test('rules: B ends like A, a limit only B had goes, apps B lacks are not set', () => {
  const b = { ...emptyB, limits: [{ pkg: 'gone.app', minutes: 10 }, { pkg: 'a.one', minutes: 60 }] };
  const p = tvCopyPlan(bundle(), homeA, b, ['a.one']);
  assert.deepEqual(row(p, 'limits').dropped, ['c.three']);
  assert.deepEqual(p.steps.filter((s) => s.do === 'limit'), [{ do: 'limit', pkg: 'gone.app', minutes: 0 }]);
  assert.deepEqual(p.steps.filter((s) => s.do === 'sched'), [{ do: 'sched', pkg: 'a.one', on: true, windows: [{ days: [6, 7], from: '08:00', to: '20:00' }], max: 90 }]);
  assert.deepEqual(p.steps.find((s) => s.do === 'bedtime'), { do: 'bedtime', on: true, ranges: [{ from: '22:00', to: '06:30' }] });
  assert.deepEqual(p.steps.find((s) => s.do === 'volume'), { do: 'volume', ranges: [{ from: '22:00', to: '07:00', max: 20 }] });
  assert.deepEqual(p.steps.find((s) => s.do === 'ram'), { do: 'ram', on: true, pct: 85 });
  assert.deepEqual(p.steps.find((s) => s.do === 'total'), { do: 'total', min: 120, days: [1, 2, 3], warn: 5 });
});

test('a daily total of nothing removes B\'s', () => {
  const b = tvCopyTake(['total'], null, { total: { min: 0, days: [] } });
  const p = tvCopyPlan(b, null, { ...emptyB, total: { min: 60, days: [], warn: 5 } }, null);
  assert.deepEqual(p.steps, [{ do: 'total', min: 0 }]);
});

test('locks need a PIN on B: asked, never copied; typed once it is set before the locks', () => {
  const none = tvCopyPlan(bundle(), homeA, emptyB, ['a.one']);
  assert.equal(row(none, 'locks').state, 'needs_pin');
  assert.equal(none.steps.some((s) => s.do === 'lock' || s.do === 'pin'), false);
  assert.equal(tvCopyPlan(bundle(), homeA, emptyB, ['a.one'], '12').groups.find((g) => g.id === 'locks').state, 'needs_pin');   // too short
  const set = tvCopyPlan(bundle(), homeA, emptyB, ['a.one'], '2468');
  const at = (d) => set.steps.findIndex((s) => s.do === d);
  assert.deepEqual(set.steps[at('pin')], { do: 'pin', pin: '2468' });
  assert.ok(at('pin') < at('lock'));
  const has = tvCopyPlan(bundle(), homeA, { ...emptyB, lock: { pin: true, apps: [{ pkg: 'x.old' }], mode: 'list', allow: [] } }, ['a.one']);
  assert.equal(has.steps.some((s) => s.do === 'pin'), false);
  assert.deepEqual(has.steps.filter((s) => s.do === 'lock'), [{ do: 'lock', pkg: 'a.one', on: true }, { do: 'lock', pkg: 'x.old', on: false }]);
});

test('a TV without TV Setup or TV Home is said, not skipped silently', () => {
  const p = tvCopyPlan(bundle(), null, null, null);
  assert.equal(row(p, 'look').state, 'needs_home'); assert.equal(row(p, 'bedtime').state, 'needs_setup');
  assert.deepEqual(p.steps, []); assert.deepEqual(p.home, {});
});
