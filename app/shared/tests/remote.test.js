// node --test app/shared/tests/   The remote screen exists twice: native Kotlin in the Phone app, and the web page (the Computer app
// and the TV web page). Both draw their keys and menu entries from ONE file, web/remote.json, so they cannot drift apart: a key or
// entry is added, reordered or renamed there once. What is left to check is that the file is sound and that every action it
// names has a handler on each side: here for the page (REMOTE_DO in js/remote_spec.js), in RemoteSpecTest.kt for the phone (the
// Phone app's phoneActions in PhoneRemote.kt).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '../../');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const spec = JSON.parse(read('shared/web/remote.json'));
const entries = [...Object.values(spec.rows).flat(2), ...Object.values(spec.menus).flat(), ...spec.simple.rows.flat()];

// remote_spec.js on its own, as the page would run it (no other script of the page is needed until a handler runs)
const book = (l) => JSON.parse(read('shared/i18n/' + l + '.json'));
function pageSpec(core, lang) {
  const words = book(lang);
  const sandbox = { CORE: core, LANG: lang, document: {}, console, T: (id) => words[id] ?? id };   // i18n.js's T(), enough here
  vm.createContext(sandbox);
  vm.runInContext(read('shared/web/js/remote_spec.js').replace('const REMOTE = ', 'var REMOTE = ').replace(/^const (\w+) =/gm, 'var $1 =') + '\nthis.REMOTE_DO = REMOTE_DO; this.remoteMenu = remoteMenu;', sandbox);
  sandbox.REMOTE = spec;                                              // the page loads the file itself (XHR); here it is read from disk
  return sandbox;
}
const webEntries = () => entries.filter((e) => !e.cores || e.cores.includes('web'));
const phoneEntries = () => entries.filter((e) => !e.cores || e.cores.includes('phone'));

test('remote.json: unique ids, a key or an action or a list on each entry, both languages', () => {
  const ids = entries.map((e) => e.id);
  assert.equal(new Set(ids).size, ids.length, 'ids are unique');
  assert.ok(entries.length > 30, 'found the entries');
  const caps = new Set([...read('shared/src/main/kotlin/dev/tvremote/core/remote/Remote.kt').matchAll(/setOf\(([^)]*)\)/g)].flatMap((m) => [...m[1].matchAll(/"(\w+)"/g)].map((x) => x[1])));
  for (const e of entries) {
    assert.equal([e.key, e.do, e.expand].filter(Boolean).length, 1, e.id + ': one of key / do / expand');
    for (const n of e.needs || []) assert.ok(caps.has(n), e.id + ': needs ' + n + ', which no kind of TV has (Remote.Kind.caps)');
    if (e.expand) continue;
    assert.ok(e.icon && e.t, e.id + ': icon and words (t)');
    for (const id of [e.t, e.sub, e.web && e.web.t, e.web && e.web.sub, e.phone && e.phone.t, e.phone && e.phone.sub].filter(Boolean))
      for (const l of ['vi', 'en']) assert.ok(typeof book(l)[id] === 'string' && book(l)[id].trim() === book(l)[id], e.id + ': ' + id + ' has no ' + l + ' words in i18n/' + l + '.json');
    for (const side of ['web', 'phone']) if (e[side]) assert.ok(e.why, e.id + ': says why it differs on the ' + side);
    for (const c of e.cores || []) assert.ok(c === 'web' || c === 'phone', e.id + ': cores are web or phone');
    if (e.cores) assert.ok(e.why, e.id + ': says why only some cores show it');
  }
});

test('remote.json: every icon is in the shared set, on the page and in the phone app', () => {
  const sprite = new Set([...read('shared/web/index.html').matchAll(/<symbol id="i-(\w+)"/g)].map((m) => m[1]));
  for (const e of entries.filter((x) => x.icon)) {
    assert.ok(sprite.has(e.icon), e.id + ': icon ' + e.icon + ' is not in the page\'s sprite (dev/gen/gen_icons.py)');
    assert.ok(fs.existsSync(path.join(root, 'android/src/main/res/drawable/ic_' + e.icon + '.xml')), e.id + ': no drawable ic_' + e.icon + ' in the phone app');
  }
});

test('remote.json: every key is a name the servers know', () => {
  const known = new Set([...read('shared/src/main/kotlin/dev/tvremote/core/scenes/Scenes.kt').matchAll(/"([A-Z_]+)" to \d+/g)].map((m) => m[1]));
  for (const e of entries.filter((x) => x.key)) assert.ok(known.has(e.key), e.id + ': ' + e.key + ' is not in Scenes.KEYS');
});

test('every action in remote.json has a handler on the web page', () => {
  const { REMOTE_DO } = pageSpec('pc', 'en');
  for (const e of webEntries().filter((x) => x.do)) assert.equal(typeof REMOTE_DO[e.do], 'function', e.id + ': no REMOTE_DO.' + e.do);
  const named = new Set(entries.filter((x) => x.do).map((x) => x.do));
  for (const k of Object.keys(REMOTE_DO)) assert.ok(named.has(k), 'REMOTE_DO.' + k + ' is not used by remote.json');
});

test('every action in remote.json has a handler in the phone app (PhoneRemote.kt)', () => {
  const src = read('android/src/main/kotlin/dev/tvremote/phone/remote/PhoneRemote.kt');
  const have = new Set([...src.matchAll(/^\s+"(\w+)" to act\b/gm)].map((m) => m[1]));
  for (const e of phoneEntries().filter((x) => x.do)) assert.ok(have.has(e.do), e.id + ': no phoneActions["' + e.do + '"]');
  // (RemoteSpecTest.kt checks the same against the real map)
});

test('the menus are built the same way the page does, in both languages', () => {
  for (const lang of ['vi', 'en']) {
    const p = pageSpec('pc', lang);
    const open = p.remoteMenu('open'), words = open.map((i) => i[0]);
    assert.equal(open.length, spec.menus.open.filter((e) => !e.cores && !e.expand).length);   // the phone-only Cast entry is not on the page; no TV apps given
    assert.ok(words.every((w) => /^@\w+\|.+/.test(w)));
    assert.ok(!words.some((w) => /^@cast\|/.test(w)));
    const lengths = () => [['Tắt sau 30 phút', '', () => {}]], more = () => [['Tắt sau 15 phút', '', () => {}]];
    const timer = p.remoteMenu('timer', { when: { timerOn: true }, expand: { lengths, lengthsMore: more } });
    assert.deepEqual(Array.from(timer, (i) => i[0].split('|')[0]), ['@plus', '@close', 'Tắt sau 30 phút', '@timer', '@eye_off', '@moon']);   // other times and the screen: groups
    assert.deepEqual(Array.from(p.remoteMenu('timer', { expand: { lengths: () => [] } }), (i) => i[0].split('|')[0]), ['@timer', '@eye_off', '@moon']);   // other times: the custom one alone, shown as itself
    assert.match(timer[3][0], lang === 'en' ? /Other times/ : /Thời gian khác/);
    const media = p.remoteMenu('send').find((i) => i[0].startsWith('@image|'));
    assert.match(media[0], lang === 'en' ? /from this device/ : /từ máy này/);   // the page's own words, not the phone's
  }
});

test('on an LG or Samsung TV the menus leave out what it cannot do, and list its apps', () => {
  const p = pageSpec('pc', 'en');
  const lg = new Set(['keys', 'text', 'apps', 'app_list', 'volume_set', 'link', 'screen_off', 'wol', 'scenes']);
  p.can = (c) => c === 'control' || c === 'full' || lg.has(c);       // core.js's can(): an owner, and the caps from /api/state
  const open = p.remoteMenu('open').map((i) => i[0]);
  assert.ok(open[0].startsWith('@apps|'), 'its apps first: ' + open);
  assert.ok(open.some((w) => w.startsWith('@link|')));
  assert.ok(!open.some((w) => /^@(store|send)\|/.test(w)), 'no Play Store or ADB groups: ' + open);
  const timer = Array.from(p.remoteMenu('timer'), (i) => i[0]);   // the page's own array (another realm): copied to compare
  assert.deepEqual(timer, ['@eye_off|Screen off / on'], 'the screen group holds the screen off only: shown as itself: ' + timer);
});

test('a guest from a shared QR: "remote" opens apps but sends no files and shares nothing, "view" presses nothing', () => {
  const p = pageSpec('tv', 'en'), guest = (scope) => (c) => c === 'full' ? false : c === 'control' ? scope === 'remote' : true;   // core.js's can()
  p.can = guest('remote');
  const open = p.remoteMenu('open').map((i) => i[0]);
  assert.ok(open.some((w) => w.startsWith('@link|')), 'opening a link is pressing a key: ' + open);
  assert.ok(!open.some((w) => /^@(image|music)\|/.test(w)), 'no files sent: ' + open);
  const more = [...(spec.rows.more || []).flat(), ...(spec.menus.tools || [])].map(p.remoteEntry);   // Share lives under Tools
  assert.ok(!p.remoteCan(more.find((e) => e.do === 'share')), 'only the owner shares');
  assert.ok(p.remoteCan(more.find((e) => e.do === 'shot')));
  p.can = guest('view');
  assert.equal(p.remoteMenu('open').length, 0);
  assert.ok(p.remoteCan(more.find((e) => e.do === 'shot')), 'a screenshot only looks');
  assert.ok(!p.remoteCan(more.find((e) => e.do === 'draw')));
});

test('every menu entry has an action: a bare "key" entry in a menu crashed the phone (its menus run e.do)', () => {
  for (const [name, list] of Object.entries(spec.menus))
    for (const e of list) assert.ok(e.do || e.expand, `menus.${name}: ${e.id} has no "do" (use "do": "key", "arg": …)`);
});

test('remote.json "kinds": a known kind, t and icon only, a why; VIDAA reads Menu, the others unchanged (issue #8)', () => {
  const kinds = [...read('shared/src/main/kotlin/dev/tvremote/core/remote/Remote.kt').matchAll(/const val \w+ = "(\w+)"/g)].map((m) => m[1]);
  const sprite = new Set([...read('shared/web/index.html').matchAll(/<symbol id="i-(\w+)"/g)].map((m) => m[1]));
  let n = 0;
  for (const e of entries.filter((x) => x.kinds)) for (const [k, o] of Object.entries(e.kinds)) {
    n++;
    assert.ok(kinds.includes(k) && k !== 'android', e.id + ': kinds.' + k + ' is not a kind with its own TV');
    assert.deepEqual(Object.keys(o).filter((f) => !['t', 'icon', 'why'].includes(f)), [], e.id + ': kinds override t and icon only');
    assert.ok(o.why && (o.t || o.icon), e.id + ': kinds.' + k + ' says why and overrides something');
    if (o.t) for (const l of ['vi', 'en']) assert.equal(typeof book(l)[o.t], 'string', e.id + ': ' + o.t + ' missing in ' + l);
    if (o.icon) assert.ok(sprite.has(o.icon) && fs.existsSync(path.join(root, 'android/src/main/res/drawable/ic_' + o.icon + '.xml')), e.id + ': icon ' + o.icon);
  }
  assert.ok(n > 0);
  const settings = entries.find((e) => e.id === 'settings');
  for (const lang of ['en', 'vi']) {
    const words = book(lang), page = (kind) => { const s = pageSpec('web', lang); s.TV_KIND = kind; return vm.runInContext('remoteEntry(' + JSON.stringify(settings) + ')', s); };
    assert.equal(words[page('vidaa').t], 'Menu'); assert.equal(page('vidaa').icon, 'menu');
    for (const k of ['android', 'lg', 'samsung', 'roku']) { assert.equal(page(k).t, 'rkey.settings'); assert.equal(page(k).icon, 'settings'); }
  }
});

test('remote.json "simple": the big keys only, each a key the servers know or an action both sides have, drawn by both', () => {
  const rows = spec.simple.rows, all = rows.flat();
  assert.ok(rows.length >= 4 && all.every((e) => e.id.startsWith('simple-')), 'rows of simple-* entries');
  assert.deepEqual(all.filter((e) => e.expand).map((e) => e.expand), ['pad'], 'one D-pad (arrows + OK), the side\'s own');
  for (const k of ['POWER', 'BACK', 'HOME', 'VOL_UP', 'VOL_DOWN', 'MUTE']) assert.ok(all.some((e) => e.key === k), k + ' is in the Simple remote');
  assert.ok(all.every((e) => !e.when || e.when === 'voice'), 'the only condition is "voice" (remote.js, Simple.kt)');
  assert.ok(all.every((e) => !e.cores), 'the same keys on the page and the phone');
  assert.ok(spec.menus.tools.some((e) => e.do === 'simple'), 'Tools turns it on');
  // the page draws it from the file (js/remote.js), no list of its own
  const js = read('shared/web/js/remote.js'), html = read('shared/web/index.html');
  assert.match(js, /REMOTE\.simple\.rows/);
  assert.match(js, /raw\.expand === 'pad'/);
  assert.ok(/id="simplekeys"/.test(html) && /id="simpleexit"/.test(html), 'its box and its leave button are on the page');
  const block = js.slice(js.indexOf('// ---------- the Simple remote'), js.indexOf('// ---------- the TV\'s own keyboard came up'));
  assert.ok(block.length > 200 && !/'[A-Z_]{3,}'/.test(block), 'setSimple names no key by hand');
  const kt = read('android/src/main/kotlin/dev/tvremote/phone/remote/Simple.kt');
  assert.match(kt, /remoteSpec\(\)\.simple/);
  assert.ok(!/"VOL_UP"|"POWER"/.test(kt), 'Simple.kt names no key by hand');
});

test('the page draws the Simple remote: every row, its keys bound like the others, the D-pad copied, and a 2 s hold to leave', () => {
  // a tiny DOM, enough for the drawing part of remote.js
  const made = [];
  const node = (tag) => { const n = { tag, kids: [], dataset: {}, classList: { add() {}, remove() {}, toggle() {}, contains: () => false }, attrs: {},
    append(...k) { this.kids.push(...k); }, querySelector: () => node('small'), addEventListener() {}, setAttribute() {}, cloneNode() { return { ...node('div'), cloned: true }; } }; made.push(n); return n; };
  const box = node('div'), sandbox = { REMOTE: spec, CORE: 'pc', TV_KIND: 'android', T: (id) => book('en')[id] ?? id };
  sandbox.el = (tag, props, ...kids) => { const n = node(tag); Object.assign(n, props); if (props && props.className) n.className = props.className; n.kids.push(...kids); return n; };
  sandbox.$ = (q) => q === '#simplekeys' ? box : node('div');
  sandbox.document = { querySelectorAll: () => [], documentElement: {} };
  vm.createContext(sandbox);
  vm.runInContext(read('shared/web/js/remote_spec.js').replace(/^const REMOTE = [\s\S]*?\n\}\)\(\);\n/m, '').replace(/^const (\w+) =/gm, 'var $1 ='), sandbox);
  const src = read('shared/web/js/remote.js'), start = src.indexOf('const remoteKey'), end = src.indexOf('// A key the connected TV names differently');
  vm.runInContext(src.slice(start, end).replace(/^const (\w+) =/gm, 'var $1 ='), sandbox);
  assert.equal(box.kids.length, spec.simple.rows.length, 'one line per row');
  box.kids.forEach((line, i) => assert.equal(line.kids.length, spec.simple.rows[i].length, 'row ' + i));
  const keys = box.kids.flatMap((l) => l.kids).filter((b) => !b.cloned);
  assert.deepEqual(keys.map((b) => b.dataset.k).filter(Boolean), spec.simple.rows.flat().filter((e) => e.key).map((e) => e.key));
  assert.ok(keys.every((b) => /\brb lab\b/.test(b.className) && b.id.startsWith('r-simple-')));
  assert.equal(box.kids.flatMap((l) => l.kids).filter((b) => b.cloned).length, 1, 'the D-pad, a copy of the hand-laid one');
  assert.match(src, /setTimeout\(\(\) => \{ stop\(\); buzz\(\); setSimple\(false\); \}, 2000\)/, 'leaving takes a 2 s hold');
  assert.match(src, /localStorage\.setItem\('simple'/, 'remembered on this browser');
  assert.match(src, /q\.has\('simple'\)/, 'the share link\'s ?simple=1');
  assert.match(read('shared/web/js/setup.js'), /searchParams\.set\('simple', '1'\)/, 'Share can make that link');
});
