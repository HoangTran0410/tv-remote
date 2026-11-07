// node --test app/shared/tests/   The page's error box (js/errbox.js): an uncaught error or a rejected promise shows one box with
// the message, the stack and Copy; a resource that failed to load and the cross-origin "Script error." do not.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page(extra = {}) {
  const listeners = {}, mk = (tag) => ({ tag, children: [], className: '', textContent: '', attrs: {}, on: {},
    append(...c) { this.children.push(...c); }, setAttribute(k, v) { this.attrs[k] = v; }, addEventListener(e, f) { this.on[e] = f; },
    remove() { this.removed = true; const i = body.children.indexOf(this); if (i >= 0) body.children.splice(i, 1); }, focus() {}, select() {} });
  const body = mk('body'), clip = [];
  const window = { addEventListener: (e, f) => { listeners[e] = f; } };
  const sandbox = { window, document: { createElement: mk, body, execCommand: () => true }, location: { href: 'http://x/', search: '' },
    navigator: { userAgent: 'test', clipboard: { writeText: (t) => { clip.push(t); return Promise.resolve(); } } }, ...extra };
  vm.createContext(sandbox);
  if (extra.fetch) vm.runInContext(fs.readFileSync(path.join(__dirname, '../web/js/scrub.js'), 'utf8'), sandbox);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../web/js/errbox.js'), 'utf8'), sandbox);
  const text = (e, out = []) => { out.push(e.textContent); e.children.forEach((c) => text(c, out)); return out.join('\n'); };
  return { listeners, body, clip, text, window };
}

test('an uncaught error shows a box with the message and the stack', () => {
  const p = page();
  p.listeners.error({ message: 'x is not a function', error: Object.assign(new Error('x is not a function'), { stack: 'Error: x\n    at a (a.js:1:1)\n    at b (b.js:2:2)' }), lineno: 1 });
  assert.equal(p.body.children.length, 1);
  const t = p.text(p.body.children[0]);
  assert.match(t, /x is not a function/);
  assert.match(t, /at a \(a\.js:1:1\)/);
  assert.match(t, /err\.title/);                                      // T() is not loaded here: the id stands in for the words
});

test('a rejected promise shows one too', () => {
  const p = page();
  p.listeners.unhandledrejection({ reason: new Error('nobody awaited me') });
  assert.match(p.text(p.body.children[0]), /nobody awaited me/);
});

test('a resource that failed to load, the cross-origin "Script error." and ResizeObserver are ignored', () => {
  const p = page();
  p.listeners.error({ message: '', target: { nodeType: 1, tagName: 'IMG' } });
  p.listeners.error({ message: 'Script error.', lineno: 0 });
  p.listeners.error({ message: 'ResizeObserver loop completed with undelivered notifications.' });
  assert.equal(p.body.children.length, 0);
});

test('an error whose every frame is the page itself (a script the browser put in) is ignored; one from js/ is not', () => {
  const p = page();
  const inj = Object.assign(new Error('Ma'), { stack: 'Error: Ma\n@http://x/:518:344\nNi@http://x/:198:221\n@http://x/:199:338' });
  p.listeners.error({ message: 'Ma', error: inj, lineno: 518 });
  p.listeners.unhandledrejection({ reason: Object.assign(new Error('Ma'), { stack: 'Error: Ma\n    at Ni (http://x/:198:221)' }) });
  p.listeners.error({ message: 'Uncaught ReferenceError: zaloJSV2 is not defined', error: Object.assign(new ReferenceError('zaloJSV2 is not defined'), { stack: 'ReferenceError: zaloJSV2 is not defined\n    at <anonymous>:1:2' }), lineno: 1 });
  assert.equal(p.body.children.length, 0);
  p.listeners.error({ message: 'boom', error: Object.assign(new Error('boom'), { stack: 'Error: boom\n    at go (http://x/js/core.js?v=1:3:4)\n    at http://x/:1:1' }), lineno: 3 });
  assert.equal(p.body.children.length, 1);
});

test('the same error again is one box, counted; another one replaces the words', () => {
  const p = page();
  const e = { message: 'boom', error: Object.assign(new Error('boom'), { stack: 'Error: boom\n    at f (f.js:1:1)' }) };
  p.listeners.error(e); p.listeners.error(e); p.listeners.error(e);
  assert.equal(p.body.children.length, 1);
  assert.match(p.text(p.body.children[0]), /×3/);
  p.listeners.error({ message: 'other', error: new Error('other') });
  assert.equal(p.body.children.length, 1);
  assert.match(p.text(p.body.children[0]), /other/);
});

test('Copy puts the whole report on the clipboard and Close removes the box', async () => {
  const p = page();
  p.listeners.error({ message: 'boom', error: Object.assign(new Error('boom'), { stack: 'Error: boom\n' + Array.from({ length: 20 }, (_, i) => `    at f${i} (f.js:${i}:1)`).join('\n') }) });
  const box = p.body.children[0], row = box.children[3];
  row.children[0].on.click();
  await Promise.resolve();
  assert.match(p.clip[0], /boom/);
  assert.match(p.clip[0], /at f19/);                                  // the screen shows 6 lines, the clipboard all of it
  assert.doesNotMatch(p.text(box), /at f19/);
  row.children[1].on.click();
  assert.equal(p.body.children.length, 0);
});

test('the error is posted to /api/crash scrubbed, once, and the box says so when the core sent it', async () => {
  const posts = [];
  const p = page({ fetch: (url, o) => { posts.push({ url, body: JSON.parse(o.body), keepalive: o.keepalive }); return Promise.resolve({ ok: true, json: () => Promise.resolve({ sent: true }) }); } });
  const e = { message: 'connect to 192.168.1.9 failed', error: Object.assign(new Error('x'), { name: 'TypeError', stack: 'TypeError: x\n    at f (http://10.0.0.2:8765/js/f.js?k=SECRET:1:1)\n    at /Users/bob/a.js:2:2' }) };
  p.listeners.error(e); p.listeners.error(e);
  await new Promise((r) => setTimeout(r, 5));
  assert.equal(posts.length, 1);
  assert.equal(posts[0].url, '/api/crash'); assert.equal(posts[0].keepalive, true);
  const b = posts[0].body;
  assert.equal(b.kind, 'TypeError'); assert.equal(b.msg, 'connect to x.x.x.x failed');
  assert.ok(!/192\.168|10\.0\.0|SECRET|bob/.test(JSON.stringify(b)), JSON.stringify(b));
  assert.match(p.text(p.body.children[0]), /err\.sent/);
});

test('a failed report is silent: no box line, no throw', async () => {
  const p = page({ fetch: () => Promise.reject(new Error('offline')) });
  p.listeners.error({ message: 'boom', error: new Error('boom') });
  await new Promise((r) => setTimeout(r, 5));
  assert.doesNotMatch(p.text(p.body.children[0]), /err\.sent/);
});

test('a script of the page that did not load: the page loads again once, its "not defined" errors are no box and no report', () => {
  let reloads = 0, sent = 0; const store = {};
  const extra = { sessionStorage: { getItem: (k) => store[k] || null, setItem: (k, v) => { store[k] = v; } },
    fetch: () => { sent++; return Promise.resolve({ ok: false }); }, location: { href: 'http://x/', search: '', reload: () => { reloads++; } } };
  const p = page(extra);
  const script = { tagName: 'SCRIPT', src: 'http://x/js/home_link.js', nodeType: 1 };
  p.listeners.error({ target: script });
  assert.equal(reloads, 1);
  p.listeners.error({ message: 'homeLinkFor is not defined', error: Object.assign(new ReferenceError('homeLinkFor is not defined'), { stack: 'ReferenceError: x\n    at http://x/js/home.js:7:18' }), lineno: 7 });
  assert.equal(p.body.children.length, 0);
  assert.equal(sent, 0);
  const q = page(extra);                                              // it failed again just after the reload: say so, still no report
  q.listeners.error({ target: script });
  assert.equal(reloads, 1);
  assert.match(q.text(q.body.children[0]), /err\.partial/);
  assert.equal(sent, 0);
});
