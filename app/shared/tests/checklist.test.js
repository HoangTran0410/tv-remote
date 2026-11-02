// node --test app/shared/tests/   The "Set up your TV" card (js/checklist_rows.js): which rows a TV gets and what each button does.
const test = require('node:test');
const assert = require('node:assert/strict');
const { checklistRows, checklistOpen } = require('../web/js/checklist_rows.js');
const ids = (rows) => rows.map((r) => r.id + (r.done ? '+' : '-') + (r.act ? ':' + r.act : ''));
const full = { adb: 'ready', setup: { installed: true, latest: '' }, home: { installed: true, home: true, latest: '', keysOk: true, keyon: true }, mic: true, chan: { installed: true }, game: { installed: true } };

test('without ADB only the ADB row, with its button', () => {
  const r = checklistRows({ ...full, adb: 'unknown' });
  assert.deepEqual(ids(r), ['adb-:adb']);
  assert.ok(checklistOpen(r));
});

test('everything done: card closed', () => {
  const r = checklistRows(full);
  assert.deepEqual(ids(r), ['adb+', 'setup+', 'home+', 'mic+', 'keys+', 'chan+', 'game+']);
  assert.ok(!checklistOpen(r));
});

test('what is not read yet has no row; no TV Setup means no mic row', () => {
  assert.deepEqual(ids(checklistRows({ adb: 'ready', setup: null, home: null, mic: null, chan: null, game: null })), ['adb+']);
  assert.deepEqual(ids(checklistRows({ adb: 'ready', setup: { installed: false, latest: '1.0' }, home: null, mic: false })), ['adb+', 'setup-:setup_install']);
});

test('TV Setup and TV Home: install, update, set as home', () => {
  const r = checklistRows({ ...full, setup: { installed: true, latest: '2.0' }, home: { installed: true, home: false, latest: '' } });
  assert.deepEqual(ids(r).slice(1, 3), ['setup-:setup_update', 'home-:gallery']);
  assert.equal(checklistRows({ ...full, home: { installed: false } }).find((x) => x.id === 'home').act, 'gallery');
  assert.equal(checklistRows({ ...full, home: { installed: true, home: true, latest: '1.1', keysOk: true, keyon: true } }).find((x) => x.id === 'home').act, 'home_update');
});

test('mic and Quick keys rows, off: a button each; Quick keys needs a TV Home that has them', () => {
  const r = checklistRows({ ...full, mic: false, home: { ...full.home, keyon: false } });
  assert.deepEqual(ids(r).filter((x) => /^(mic|keys)/.test(x)), ['mic-:mic', 'keys-:keys']);
  assert.ok(!checklistRows({ ...full, home: { ...full.home, keysOk: false } }).some((x) => x.id === 'keys'));
});

test('TV Channels and TV Games are optional: they never open the card alone', () => {
  const r = checklistRows({ ...full, chan: { installed: false, latest: '1.0' }, game: { installed: true, latest: '2.0' } });
  assert.deepEqual(ids(r).slice(-2), ['chan-:install', 'game-:update']);
  assert.ok(!checklistOpen(r));
  assert.ok(!checklistRows({ ...full, chan: { installed: false, latest: '' } }).find((x) => x.id === 'chan').act);   // no release carries it yet: no button
});
