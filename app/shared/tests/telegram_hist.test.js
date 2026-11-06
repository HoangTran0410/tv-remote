// node --test app/shared/tests/   js/telegram.js, the bot's history in Settings: a double-tapped More asks one page, not the
// same offset twice; an answer for another chat (or another TV) picked meanwhile is dropped, not appended to the new list.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../web/js/telegram.js'), 'utf8');

function page() {
  const stub = () => new Proxy(function () {}, { get: (t, k) => (k === 'hidden' || k === 'open' ? false : stub()), apply: () => stub(), set: () => true });
  const calls = [], resets = [];
  const sandbox = {
    T: (id) => id, CORE: 'pc', SCOPE: 'full', LANG: 'en', LOCALE: 'en',
    $: () => stub(), el: () => stub(), put() {}, toast() {}, ask: async () => false, setupFor: async () => false,
    UI: { list: () => stub(), item: () => stub(), chips: () => stub() },
    onTvChange: (f) => resets.push(f), setInterval() {}, setTimeout() {}, clearTimeout() {},
    // each call waits until the test answers it
    api: (q) => new Promise((done) => calls.push({ q, done })),
  };
  vm.createContext(sandbox);
  vm.runInContext(src.replace(/^(let|const) /gm, 'var ') + '\nthis.hist = tgHist; this.items = () => tgItems; this.pick = (c) => { tgChat = c; };', sandbox);
  return { sandbox, calls, resets };
}
const rows = (from, n, tag = '') => ({ items: Array.from({ length: n }, (_, i) => ({ cmd: tag + (from + i), t: 0 })), total: 100 });
const tick = () => new Promise((r) => setImmediate(r));

test('a double-tapped More asks once', async () => {
  const p = page();
  p.sandbox.hist(true); p.calls[0].done(rows(0, 20)); await tick();
  p.sandbox.hist(false); p.sandbox.hist(false);
  assert.equal(p.calls.length, 2, 'the second More waits for the first');
  assert.match(p.calls[1].q, /offset=20/);
  p.calls[1].done(rows(20, 20)); await tick();
  assert.equal(p.sandbox.items().length, 40);
});

test("another chat picked while More is in flight: the old chat's rows are dropped", async () => {
  const p = page();
  p.sandbox.hist(true); p.calls[0].done(rows(0, 20, 'a')); await tick();
  p.sandbox.hist(false);                                             // More for chat a, slow
  p.sandbox.pick(7); p.sandbox.hist(true);                           // chat 7 picked
  p.calls[2].done(rows(0, 5, 'b')); await tick();
  p.calls[1].done(rows(20, 20, 'a')); await tick();                  // the late answer of chat a
  assert.deepEqual(p.sandbox.items().map((h) => h.cmd), ['b0', 'b1', 'b2', 'b3', 'b4']);
});

test('another TV: an answer in flight for the old one is dropped', async () => {
  const p = page();
  p.sandbox.hist(true);
  p.resets.forEach((f) => f());
  p.calls[0].done(rows(0, 20)); await tick();
  assert.equal(p.sandbox.items().length, 0);
});
