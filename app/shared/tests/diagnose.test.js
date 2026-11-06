// node --test app/shared/tests/   js/diagnose.js: the self-check runs once the TV has stayed out of reach for two polls (not on
// the first miss), never for a TV that is asking or not paired yet or whose ADB is up, asks /api/diagnose with the saved TV's
// kind and name, and shows the one conclusion with only the buttons this core has.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../web/js/diagnose.js'), 'utf8');

function node() {
  const n = { hidden: true, className: '', children: [], classList: { contains: (c) => n.className.split(' ').includes(c) }, replaceChildren(...c) { n.children = c; } };
  return n;
}
function page(core = 'pc') {
  const box = node(), calls = [];
  const el = (tag, props = {}, ...kids) => Object.assign(node(), { tag, kids }, props, { hidden: false });
  const sandbox = {
    T: (id, a) => { let s = JSON.parse(fs.readFileSync(path.join(__dirname, '../i18n/vi.json'), 'utf8'))[id]; if (s == null) return id; return a ? s.replace(/\{(\w+)\}/g, (m, k) => (k in a ? a[k] : m)) : s; },
    CORE: core, picking: false, LANG: 'vi', wantPick: false, cameFromTv: false,
    $: () => box, el, refresh() {}, put: (e, k, v) => { (typeof e === 'string' ? box : e)[k] = v; }, selectTv: (...a) => calls.push(['select', ...a]), adbHelp() {}, api: async () => ({}),
    AbortController, URLSearchParams,
    fetch: async (url, o) => { calls.push(['fetch', url]); return { ok: true, json: async () => sandbox.answer }; },
    answer: { code: 'adb_off', title: 't', fix: 'f', ip: '1.2.3.4', kind: 'android', actions: ['adbhelp', 'retry'], found: null, checks: [{ ok: true, note: 'a' }, { ok: false, note: 'b' }, { ok: null, note: 'c' }] },
  };
  vm.createContext(sandbox);
  vm.runInContext(src.replace(/^(let|const) /gm, 'var ') + '\nthis.diagWatch = diagWatch; this.diagNeeded = diagNeeded;', sandbox);
  return { box, calls, sandbox, watch: (s) => sandbox.diagWatch(s) };
}
const down = { host: '192.168.1.20', kind: 'android', name: 'Living room', connected: false, adb: { state: 'closed' } };
const tick = () => new Promise((r) => setTimeout(r, 5));

test('one miss is not enough, two are', async () => {
  const p = page();
  p.watch(down); await tick();
  assert.equal(p.calls.length, 0);
  p.watch(down); await tick();
  assert.equal(p.calls.length, 1);
  const url = new URL('http://x' + p.calls[0][1].replace('/api/diagnose', '/d'));
  assert.equal(url.searchParams.get('ip'), '192.168.1.20');
  assert.equal(url.searchParams.get('name'), 'Living room');
  assert.equal(url.searchParams.get('kind'), 'android');
  p.watch(down); p.watch(down); await tick();
  assert.equal(p.calls.length, 1, 'once per outage');
});

test('nothing to diagnose when connected, asking, unpaired, ADB ready, on the TV core or while choosing', () => {
  const p = page();
  for (const s of [{ ...down, connected: true }, { ...down, asking: true }, { ...down, paired: false }, { ...down, adb: { state: 'ready' } }, { ...down, host: '' }])
    assert.equal(p.sandbox.diagNeeded(s), false);
  assert.equal(p.sandbox.diagNeeded(down), true);
  assert.equal(page('tv').sandbox.diagNeeded(down), false);
  p.sandbox.picking = true;
  assert.equal(p.sandbox.diagNeeded(down), false);
});

test('the conclusion is shown, with the buttons this core has', async () => {
  const p = page();
  p.watch(down); p.watch(down); await tick();
  assert.equal(p.box.hidden, false);
  assert.match(p.box.className, /warn/);
  const labels = (b) => b.children.at(-1).kids.map((k) => k.textContent);
  assert.deepEqual(labels(p.box), ['🧭 Bật ADB giúp tôi', '🔄 Thử lại', '📋 Gửi nhật ký']);   // the log last: a way to get help when the fix does not work
  const q = page('phone');
  q.watch(down); q.watch(down); await tick();
  assert.deepEqual(labels(q.box), ['🔄 Thử lại', '📋 Gửi nhật ký']);  // no ADB help and no scan outside the computer app
});

test('a TV found at a new address offers to use it', async () => {
  const p = page();
  p.sandbox.answer = { ...p.sandbox.answer, code: 'moved', actions: ['use', 'retry'], found: { ip: '192.168.1.31', name: 'Living room' } };
  p.watch(down); p.watch(down); await tick();
  const use = p.box.children.at(-1).kids[0];
  assert.match(use.textContent, /192\.168\.1\.31/);
  use.onclick();
  assert.deepEqual(p.calls.at(-1), ['select', '192.168.1.31', 'Living room', 'android']);
});

test('the banner goes away when the TV answers, and a new outage is checked again', async () => {
  const p = page();
  p.watch(down); p.watch(down); await tick();
  p.watch({ ...down, connected: true });
  assert.equal(p.box.hidden, true);
  p.watch(down); p.watch(down); await tick();
  assert.equal(p.calls.filter((c) => c[0] === 'fetch').length, 2);
});
