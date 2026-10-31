// node --test app/shared/tests/   The message on the TV (js/notify.js): its clamps are the ones Notify.kt makes, its choices are
// remembered on the page, and it is a scene step.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '../../');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

function page(store) {
  const localStorage = store === 'throws'
    ? { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); } }
    : { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); } };
  const sandbox = { localStorage, console };
  vm.createContext(sandbox);
  vm.runInContext(read('shared/web/js/notify.js').replace(/^(const|function|async function) /gm, (m) => (m.startsWith('const') ? 'var ' : m))
    + '\nthis.api = { notifyCorner, notifySeconds, notifyPrefs, notifySave, NOTIFY_CORNERS, NOTIFY_SECONDS };', sandbox);
  return sandbox.api;
}

test('the corner is one of four, else top right', () => {
  const { notifyCorner } = page({});
  for (const c of ['top-left', 'top-right', 'bottom-left', 'bottom-right']) assert.equal(notifyCorner(c), c);
  for (const bad of [undefined, null, '', 'middle', 'TOP-LEFT', 5]) assert.equal(notifyCorner(bad), 'top-right');
});

test('the seconds are whole and between 1 and 60, else 5', () => {
  const { notifySeconds } = page({});
  assert.equal(notifySeconds(undefined), 5);
  assert.equal(notifySeconds(null), 5);   // (Notify.kt: not a number)
  assert.equal(notifySeconds('abc'), 5);
  assert.equal(notifySeconds(''), 5);
  assert.equal(notifySeconds(NaN), 5);
  assert.equal(notifySeconds(0), 1);
  assert.equal(notifySeconds(-30), 1);
  assert.equal(notifySeconds(9999), 60);
  assert.equal(notifySeconds('10'), 10);
  assert.equal(notifySeconds(2.6), 3);
});

test('the same corners as Notify.kt, and chips inside its limits', () => {
  const { NOTIFY_CORNERS, NOTIFY_SECONDS } = page({});
  const kt = read('shared/src/main/kotlin/dev/tvremote/core/ontv/Notify.kt');
  const corners = [...kt.match(/val CORNERS = listOf\(([^)]*)\)/)[1].matchAll(/"([\w-]+)"/g)].map((m) => m[1]);
  assert.deepEqual([...NOTIFY_CORNERS].map((c) => c[0]), corners);
  const [lo, hi] = ['MIN_SECONDS', 'MAX_SECONDS'].map((n) => +kt.match(new RegExp('const val ' + n + ' = (\\d+)'))[1]);
  assert.ok(NOTIFY_SECONDS.every((n) => n >= lo && n <= hi));
  assert.deepEqual([...NOTIFY_SECONDS], [3, 5, 10, 30]);
  assert.ok(kt.includes('const val CORNER = "top-right"') && kt.includes('const val SECONDS = 5'));
});

test('the last choice is remembered, and storage that throws is no problem', () => {
  const store = {};
  const a = page(store);
  assert.deepEqual({ ...a.notifyPrefs() }, { corner: 'top-right', seconds: 5 });
  a.notifySave({ corner: 'bottom-left', seconds: 30 });
  assert.deepEqual({ ...page(store).notifyPrefs() }, { corner: 'bottom-left', seconds: 30 });
  assert.deepEqual({ ...page({ notify: '{"corner":"x","seconds":7}' }).notifyPrefs() }, { corner: 'top-right', seconds: 5 });   // not a chip: the default
  assert.deepEqual({ ...page({ notify: 'not json' }).notifyPrefs() }, { corner: 'top-right', seconds: 5 });
  const blocked = page('throws');
  assert.deepEqual({ ...blocked.notifyPrefs() }, { corner: 'top-right', seconds: 5 });
  assert.doesNotThrow(() => blocked.notifySave({ corner: 'top-left', seconds: 3 }));
});

test('it is in Tools, a scene step, and a feature that needs a newer TV Setup', () => {
  const spec = JSON.parse(read('shared/web/remote.json'));
  const e = spec.menus.tools.find((x) => x.do === 'notify');   // moved from More keys to Tools: rarely used
  assert.ok(e && e.icon === 'bell' && e.needs.includes('adb'));
  const html = read('shared/web/index.html'), auto = read('shared/web/js/auto.js'), core = read('shared/web/js/core.js');
  assert.match(html, /<option value="notify"[ >]/);
  assert.ok(auto.includes("case 'notify'") && auto.includes("k === 'notify'"));
  assert.ok(/SETUP_NEEDS = \{[^}]*notify:/.test(core));
  assert.ok(read('shared/src/main/kotlin/dev/tvremote/core/ontv/SetupNeed.kt').includes('"notify" to "0.4.2"'));
  assert.ok(read('tvsetup/src/main/kotlin/dev/tvremote/setup/link/Scope.kt').includes('"/api/notify"'));
});
