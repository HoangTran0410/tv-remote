// node --test app/shared/tests/   The page's shared ground (js/core.js and a few loaders): a read of the TV left behind is dropped,
// pairing says why it failed, Several TVs outlives a blip, Copy works on plain http, a failed read says so, search ignores accents,
// a browser without <dialog> is told it is old, and the page loads one language file before its first paint.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const read = (f) => fs.readFileSync(path.join(__dirname, '../web', f), 'utf8');
const core = read('js/core.js');
// the source of one top-level function, up to its closing brace at column 0, or one top-level const line
const fn = (src, name) => { const m = src.match(new RegExp('^(async )?function ' + name + '\\b[\\s\\S]*?^}', 'm')); assert.ok(m, name); return m[0]; };
const line = (src, name) => { const m = src.match(new RegExp('^const ' + name + ' = .*$', 'm')); assert.ok(m, name); return m[0]; };

test('call(): a read of the old TV that answers after a TV change is dropped; a write and a TV-free read are kept', async () => {
  const waiting = [];
  const ctx = vm.createContext({
    CORE: 'pc', T: (x) => x, toast() {},
    fetch: () => new Promise((ok) => waiting.push(() => ok({ ok: true, status: 200, json: async () => ({ apps: ['old.tv'] }) }))),
  });
  vm.runInContext(['let tvGen = 0;', core.match(/^const TV_FREE = .*$/m)[0], fn(core, 'call'),
    'this.call = call; this.bump = () => { tvGen++; };'].join('\n'), ctx);
  const apps = ctx.call('/api/apps', null, true), tvs = ctx.call('/api/tvs', null, true), post = ctx.call('/api/key', { key: 'HOME' }, true);
  ctx.bump();                                                          // the person picked another TV
  waiting.forEach((go) => go());
  assert.equal(await apps, null, 'the old TV\'s apps never reach the new TV\'s page');
  assert.deepEqual(await tvs, { apps: ['old.tv'] }, 'the list of TVs is about no one TV');
  assert.ok(await post, 'what was sent is answered');
  const now = ctx.call('/api/apps', null, true); waiting[3]();
  assert.ok(await now, 'a read of the TV in front is kept');
});

test('pairing tells a wrong code, too many wrong codes and no network apart; TV Setup answers "locked"', () => {
  const why = new Function(line(core, 'PAIR_WHY') + '; return PAIR_WHY;')();
  assert.equal(why.wrong, 'core.wrong_code');
  assert.equal(why.locked, 'core.code_tries');
  assert.equal(why.net, 'core.no_tv');
  assert.match(core, /\.catch\(\(\) => \(\{ error: 'net' \}\)\)/, 'a request that never got an answer is "net", not "expired"');
  const kt = fs.readFileSync(path.join(__dirname, '../../tvsetup/src/main/kotlin/dev/tvremote/setup/RemoteService.kt'), 'utf8');
  const check = kt.slice(kt.indexOf('private fun pairCheck'), kt.indexOf('private fun pairInvite'));
  assert.match(check, /if \(code == null\) "locked" else "wrong"/);
  for (const l of ['vi', 'en', 'fr', 'ru', 'ar']) {
    const book = JSON.parse(fs.readFileSync(path.join(__dirname, '../i18n', l + '.json'), 'utf8'));
    for (const id of Object.values(why)) assert.ok(book[id], l + ': ' + id);
  }
});

test('Several TVs: one missed poll does not end the progress; ten say the connection was lost', async () => {
  const src = read('js/fleet.js');
  const run = async (answers) => {
    const rows = { a: {}, b: {} }, toasts = [];
    const ctx = vm.createContext({ fleetRow: rows, toasts, T: (x) => x, toast: (m) => toasts.push(m), sleep: async () => {}, friendly: (m) => m,
      FLEET_STEP: {}, FLEET_ADB: {}, fleetCheckoutWords: () => '', fleetCheckoutReport() {}, api: async () => answers.shift() || null });
    vm.runInContext(fn(src, 'fleetFollowing') + '\nthis.go = fleetFollowing;', ctx);
    await ctx.go({ id: 1, title: 'install x', status: 'running', tvs: { a: { status: 'running', msg: 'installing', pct: 40 }, b: { status: 'done', msg: 'ok' } } });
    return { rows, toasts };
  };
  const done = { id: 1, title: 'install x', status: 'done', tvs: { a: { status: 'done', msg: 'ok' }, b: { status: 'done', msg: 'ok' } } };
  const blip = await run([null, null, done]);
  assert.deepEqual(blip.toasts, ['fleet.done_all'], 'two misses, then the end is still told');
  const gone = await run([]);
  assert.deepEqual(gone.toasts, ['files.connection_lost']);
  assert.equal(gone.rows.a.className, 'fleetstep error', 'the TV still installing says it is not known');
  assert.equal(gone.rows.b.className, 'fleetstep done', 'the TV that had finished keeps its line');
});

// a tiny DOM: enough for el(), append, replaceChildren, querySelectorAll('dialog[open]') and execCommand
function dom() {
  const node = (tag) => ({ tag, kids: [], parent: null, open: false, failMsg: null,
    get childNodes() { return this.kids; },
    append(...k) { k.forEach((c) => { c.parent = this; this.kids.push(c); }); },
    replaceChildren(...k) { this.kids = []; this.append(...k); },
    remove() { if (this.parent) this.parent.kids = this.parent.kids.filter((c) => c !== this); this.parent = null; },
    contains(x) { return x === this || this.kids.some((c) => c.contains && c.contains(x)); },
    select() { doc.selected = this; }, setSelectionRange() {},
  });
  const body = node('body');
  const all = (n) => [n, ...n.kids.flatMap(all)];
  const doc = { body, selected: null, copied: null,
    querySelectorAll: (sel) => sel === 'dialog[open]' ? all(body).filter((n) => n.tag === 'dialog' && n.open) : [],
    execCommand(c) { if (c === 'copy' && doc.selected) { doc.copied = { text: doc.selected.value, inside: doc.selected.parent }; return true; } return false; } };
  const el = (tag, props = {}, ...kids) => { const n = Object.assign(node(tag), props); n.append(...kids.filter(Boolean)); return n; };
  return { doc, el, node };
}

test('Copy on the TV\'s plain-http page: no clipboard API, copied through a box inside the open dialog', async () => {
  const { doc, el, node } = dom();
  const toasts = [], ctx = vm.createContext({ document: doc, el, navigator: {}, T: (x) => x, toast: (m) => toasts.push(m) });
  vm.runInContext([fn(core, 'clipCopy'), fn(core, 'copyText'), 'this.clipCopy = clipCopy; this.copyText = copyText;'].join('\n'), ctx);
  const d = node('dialog'); d.open = true; doc.body.append(d);         // the backup codes' dialog (modal: outside it is inert)
  assert.equal(await ctx.clipCopy('1234 5678'), true);
  assert.equal(doc.copied.text, '1234 5678');
  assert.equal(doc.copied.inside, d, 'the box was in the dialog');
  assert.equal(d.kids.length, 0, 'and is gone again');
  await ctx.copyText('mac'); assert.deepEqual(toasts, ['health.copied']);
  for (const f of ['apps.js', 'kids.js', 'playing.js', 'health.js']) assert.ok(!/navigator\.clipboard/.test(read('js/' + f)), f + ' copies through clipCopy()');
});

test('failed(): a read that failed says so with Try again, which puts the placeholder back and reads again', () => {
  const { el, node } = dom();
  const ctx = vm.createContext({ el, T: (x) => x });
  vm.runInContext(fn(core, 'failed') + '\nthis.failed = failed;', ctx);
  const box = node('div'), wait = node('p'); box.append(wait);
  let again = 0;
  ctx.failed(box, () => again++);
  assert.equal(box.kids[0].textContent, 'core.read_failed');
  const shown = box.kids[0];
  ctx.failed(box, () => again++);                                     // the next poll fails too: left as it is
  assert.equal(box.kids[0], shown);
  box.kids[1].kids[0].onclick();
  assert.deepEqual([box.kids[0], again], [wait, 1]);
  for (const [f, call] of [['health.js', "failed($('#hwait')"], ['health.js', 'failed(box, () => loadRamApps'], ['tvset.js', "failed($('#tvset')"], ['apps.js', "failed($('#appslist')"]])
    assert.ok(read('js/' + f).includes(call), f + ': ' + call);
});

test('search ignores accents: "truyen hinh" finds "Truyền hình" in Apps, Files, Quick launch and Open apps', () => {
  const ctx = vm.createContext({});
  vm.runInContext([line(core, 'foldText'), line(core, 'foldHas'), 'this.foldText = foldText; this.foldHas = foldHas;'].join('\n'), ctx);
  const q = ctx.foldText('truyen hinh');
  assert.ok(ctx.foldHas(q, 'vn.fpt.tv', 'Truyền hình FPT'));
  assert.ok(ctx.foldHas(ctx.foldText('DUONG'), 'Đường lên đỉnh'));
  assert.ok(!ctx.foldHas(q, 'com.netflix', 'Netflix'));
  assert.ok(ctx.foldHas('', 'anything'), 'an empty box shows all');
  for (const f of ['apps.js', 'files.js', 'recents.js']) assert.ok(!/\.toLowerCase\(\)\.includes\(q\)/.test(read('js/' + f)), f + ' searches through foldHas()');
});

test('old.js: a browser that runs the syntax but has no <dialog> showModal (Safari 14-15.3) is told to update', () => {
  const src = read('js/old.js');
  const run = (dialog) => {
    const proto = () => ({ prototype: { replaceChildren() {} } });
    const ctx = vm.createContext({ Element: proto(), DocumentFragment: proto(), Document: proto(), location: { search: '' }, navigator: { language: 'en', userAgent: '' },
      document: { addEventListener() {} } });
    ctx.window = ctx;
    if (dialog) ctx.HTMLDialogElement = function () {}, ctx.HTMLDialogElement.prototype.showModal = () => {};
    vm.runInContext(src, ctx);
    return !!ctx.OLD_BROWSER;
  };
  assert.equal(run(true), false, 'a browser with <dialog> runs the page');
  assert.equal(run(false), true, 'one without it gets the update message');
});

test('i18n.js: one language file before the first paint; English only the first time an id is missing', () => {
  const books = { fr: { 'a.x': 'Bonjour' }, en: { 'a.x': 'Hello', 'a.y': 'Only in English' }, vi: { 'a.z': 'Chỉ tiếng Việt' } };
  const loaded = [];
  function XMLHttpRequest() {}
  XMLHttpRequest.prototype.open = function (m, url) { this.url = url; };
  XMLHttpRequest.prototype.send = function () {
    const f = this.url.split('?')[0].replace('i18n/', '').replace('.json', '');
    if (f === 'langs') { this.status = 200; this.responseText = JSON.stringify({ vi: 'Tiếng Việt', en: 'English', fr: 'Français' }); return; }
    loaded.push(f); this.status = 200; this.responseText = JSON.stringify(books[f] || {});
  };
  const root = { nodeType: 1, lang: '', dir: '', matches: () => false, querySelectorAll: () => [] };
  const ctx = vm.createContext({ XMLHttpRequest, URLSearchParams, Intl, JSON, location: { search: '?lang=fr' }, navigator: { language: 'fr' },
    localStorage: { getItem: () => null }, sessionStorage: { getItem: () => null, removeItem() {} },
    document: { currentScript: { src: 'http://x/js/i18n.js?v=1' }, documentElement: root, body: null, addEventListener() {} } });
  ctx.window = ctx;
  vm.runInContext(read('js/i18n.js'), ctx);
  assert.deepEqual(loaded, ['fr'], 'only the viewer\'s language at first');
  assert.equal(ctx.T('a.x'), 'Bonjour');
  assert.deepEqual(loaded, ['fr']);
  assert.equal(ctx.T('a.y'), 'Only in English');
  assert.deepEqual(loaded, ['fr', 'en']);
  assert.equal(ctx.T('a.z'), 'Chỉ tiếng Việt');
  assert.equal(ctx.T('a.none'), 'a.none');
  assert.deepEqual(loaded, ['fr', 'en', 'vi'], 'each fallback read once');
});

test('isStale(): only the router\'s own 404 on the Computer app means "page newer than server"; a route\'s own 404 keeps its words', () => {
  const mk = (core) => new Function('CORE', line(core, 'isStale') + '; return isStale;');
  const pc = mk(core)('pc'), tv = mk(core)('tv');
  assert.equal(pc(404, 'not found'), true);
  assert.equal(pc(404, 'Not Found'), true);
  for (const e of ['no such job', 'unknown job', 'no picture', 'no icon', 'no such scene']) assert.equal(pc(404, e), false, e);
  assert.equal(pc(500, 'not found'), false);
  assert.equal(tv(404, 'not found'), false);
});
