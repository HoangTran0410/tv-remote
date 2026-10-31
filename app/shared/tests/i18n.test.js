// node --test app/shared/tests/   The page's words by id (js/i18n.js): i18n/vi.json lists every one, each other language the
// same ids with the same {placeholders}; index.html names an id for each of its words and keeps the Vietnamese of vi.json
// (so it never drifts); every id is used; scripts write no words of their own.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const web = path.join(__dirname, '../web');
const read = (f) => fs.readFileSync(path.join(web, f), 'utf8');
const i18n = read('js/i18n.js');
const LIST = JSON.parse(read('../i18n/langs.json'));              // THE list of languages (the page, TV Home's web copy and every native app read it)
const LANGS = Object.keys(LIST);
const book = (l) => JSON.parse(read('../i18n/' + l + '.json'));
const vi = book('vi');
const VI = /[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]/i;
const holes = (s) => [...new Set([...(typeof s === 'object' ? Object.values(s).join(' ') : s).matchAll(/\{(\w+)\}/g)].map((m) => m[1]))].sort().join(',');

test('one file per language in LANGS, each with the ids of vi.json and the same {placeholders}', () => {
  const files = fs.readdirSync(path.join(web, '../i18n')).filter((f) => f.endsWith('.json') && f !== 'langs.json').map((f) => f.slice(0, -5)).sort();
  assert.deepEqual(files, [...LANGS].sort(), 'i18n/<code>.json and its line in i18n/langs.json come together');
  assert.deepEqual(LANGS.slice(0, 2).sort(), ['en', 'vi'], 'vi and en are in the list');
  assert.equal(LANGS[0], 'vi', 'Vietnamese first');
  for (const [c, n] of Object.entries(LIST)) assert.ok(/^[a-z]{2,3}$/.test(c) && typeof n === 'string' && n.trim(), 'langs.json: ' + c);
  assert.ok(!/const LANGS = \{\s*vi/.test(i18n.replace(/return \{ vi[^}]*\};/, '')), 'js/i18n.js reads langs.json, it holds no list of its own');
  for (const l of LANGS) {
    const b = book(l), bad = [];
    for (const [id, s] of Object.entries(b)) {
      if (!(id in vi)) bad.push(`${l}: ${id} is not in vi.json`);
      else if (typeof s !== 'string' && !(s && typeof s.other === 'string')) bad.push(`${l}: ${id} is neither words nor { one, other }`);
      else if (holes(s) !== holes(vi[id])) bad.push(`${l}: ${id} has {${holes(s)}}, vi.json {${holes(vi[id])}}`);
      if (!/^[a-z0-9_]+(\.[a-z0-9_]+)+$/.test(id)) bad.push(`${l}: ${id}: ids are area.words, lower case`);
    }
    if (l === 'en') for (const id of Object.keys(vi)) if (!(id in b)) bad.push(`en: ${id} missing (English is the fallback of every other language)`);
    assert.deepEqual(bad, []);
  }
});

// index.html as a tree, enough to find an element's own words
function tree(html) {
  const VOID = new Set('area base br col embed hr img input link meta source track wbr use path circle rect line polyline polygon'.split(' '));
  const root = { kids: [], attrs: {} }, stack = [root], re = /<!--[\s\S]*?-->|<(script|style)\b[\s\S]*?<\/\1>|<\/?[a-zA-Z][^>]*>|[^<]+|</g;
  for (let m; (m = re.exec(html));) {
    const s = m[0], top = stack[stack.length - 1];
    if (s.startsWith('<!--') || /^<(script|style)\b/i.test(s)) continue;
    if (s.startsWith('</')) { const n = s.slice(2, -1).trim().toLowerCase(); while (stack.length > 1) { const e = stack.pop(); e.end = m.index; if (e.name === n) break; } continue; }
    if (s.startsWith('<') && s.length > 1) {
      const name = s.match(/^<([a-zA-Z][\w-]*)/)[1].toLowerCase(), attrs = {};
      for (const a of s.slice(name.length + 1).replace(/\/?>$/, '').matchAll(/([\w:-]+)(?:="([^"]*)")?/g)) attrs[a[1]] = a[2] ?? '';
      const e = { name, attrs, kids: [], start: m.index + s.length, line: html.slice(0, m.index).split('\n').length };
      top.kids.push(e);
      if (!VOID.has(name) && !s.endsWith('/>')) stack.push(e);
    } else top.kids.push(s);
  }
  return root;
}
const html = read('index.html');
const all = [];
(function walk(e) { for (const k of e.kids) if (typeof k === 'object') { all.push(k); if (k.name !== 'svg') walk(k); } })(tree(html));
const norm = (s) => s.replace(/\s+/g, ' ').trim();
const SLOTS = ['title', 'aria-label', 'placeholder', 'alt'];

test('index.html: each word has an id, and keeps the words vi.json gives it', () => {
  const bad = [];
  for (const e of all) {
    const at = `index.html:${e.line} <${e.name}${e.attrs.id ? '#' + e.attrs.id : ''}>`;
    for (const a of SLOTS) {
      const id = e.attrs['data-i18n-' + a], v = e.attrs[a];
      if (id && !(id in vi)) bad.push(`${at} data-i18n-${a}="${id}": not in vi.json`);
      else if (id && v !== vi[id]) bad.push(`${at} ${a}="${v}" but vi.json ${id} = "${vi[id]}"`);
      else if (!id && v && VI.test(v)) bad.push(`${at} ${a}="${v}" has no data-i18n-${a}`);
    }
    if (e.attrs['data-i18n-html']) {
      const id = e.attrs['data-i18n-html'];
      if (!(id in vi)) bad.push(`${at} data-i18n-html="${id}": not in vi.json`);
      else if (norm(html.slice(e.start, e.end)) !== norm(vi[id])) bad.push(`${at} says "${norm(html.slice(e.start, e.end))}" but vi.json ${id} = "${vi[id]}"`);
      continue;
    }
    const own = e.kids.filter((k) => typeof k === 'string' && k.trim()), id = e.attrs['data-i18n'];
    if (id && !(id in vi)) bad.push(`${at} data-i18n="${id}": not in vi.json`);
    else if (id && norm(own[own.length - 1] || '') !== vi[id]) bad.push(`${at} says "${norm(own[own.length - 1] || '')}" but vi.json ${id} = "${vi[id]}"`);
    else if (!id && own.some((k) => VI.test(k)) && !all.some((p) => p.attrs['data-i18n-html'] && p.start <= e.start && e.start < p.end))
      bad.push(`${at} "${norm(own.join(' '))}" has no data-i18n`);
  }
  assert.deepEqual(bad, [], 'give it an id in i18n/vi.json (and en.json), name it on the element; the words in index.html are those of vi.json');
});

const kotlin = () => {                                          // the apps' Kotlin and Java ask for the same ids (Words.kt)
  const out = [], walk = (d) => { for (const f of fs.readdirSync(d, { withFileTypes: true })) {
    if (f.isDirectory()) { if (f.name !== 'build') walk(path.join(d, f.name)); } else if (/\.(kt|java)$/.test(f.name)) out.push(fs.readFileSync(path.join(d, f.name), 'utf8')); } };
  for (const app of ['android', 'tvsetup', 'shared', 'desktop']) { const d = path.join(web, '../..', app, 'src/main'); if (fs.existsSync(d)) walk(d); }
  return out.join('\n');
};

test('every id is used', () => {
  const src = [html, read('home.html'), read('stats.js'), read('remote.json').replace(/"/g, "'"), ...fs.readdirSync(path.join(web, 'js')).filter((f) => f !== 'i18n.js').map((f) => read('js/' + f))].join('\n');
  const kt = kotlin();
  // an id is named on an element, or as a string in a script: T('a.b'), T(x ? 'a.b' : 'a.c'), a table of ids; in Kotlin T("a.b"),
  // Words.get(lang, "a.b"), t("a.b"), or any quoted "a.b" that is an id
  const named = new Set([...src.matchAll(/data-i18n(?:-[\w-]+)?="([^"]+)"/g)].map((m) => m[1]).concat([...src.matchAll(/'([a-z0-9_]+\.[a-z0-9_]+)'/g)].map((m) => m[1]).filter((id) => id in vi))
    .concat([...kt.matchAll(/"([a-z0-9_]+\.[a-z0-9_]+)"/g)].map((m) => m[1]).filter((id) => id in vi)));
  assert.deepEqual(Object.keys(vi).filter((id) => !named.has(id)), [], 'no element, no T() and no Kotlin names it: remove it from every i18n/*.json');
  const asked = [...src.matchAll(/\bT\('([^']+)'/g)].map((m) => m[1]);
  assert.deepEqual([...new Set(asked)].filter((id) => !(id in vi)), [], 'T() asks for an id missing from i18n/vi.json');
  const ktAsked = [...kt.matchAll(/(?:\bT|\bt|Words\.get\([^,()]+,|Words\.t)\(?\s*"([a-z0-9_]+\.[a-z0-9_]+)"/g)].map((m) => m[1]);
  assert.deepEqual([...new Set(ktAsked)].filter((id) => !(id in vi)), [], 'Kotlin asks for an id missing from i18n/vi.json');
});

test('scripts write no words of their own: every word is an id', () => {
  // the old way (a table turning Vietnamese sentences into English on the rendered page) is gone: a sentence in a script would
  // show in Vietnamese to every other language. Left alone: old.js (runs on browsers too old for the rest, carries both
  // languages), voice.js (what people say, matched, not shown), the request apps.js writes for an AI (both languages, editable).
  const SKIP = new Set(['i18n.js', 'old.js', 'voice.js']), bad = [];
  for (const f of fs.readdirSync(path.join(web, 'js')).filter((x) => x.endsWith('.js') && !SKIP.has(x))) {
    let inAi = false;
    read('js/' + f).split('\n').forEach((line, i) => {
      if (/^function aiPrompt\b/.test(line)) inAi = true; else if (inAi && /^}/.test(line)) inAi = false;
      if (inAi) return;
      const code = line.replace(/\/\/.*$/, '');
      for (const m of code.matchAll(/'((?:[^'\\]|\\.)*)'|`((?:[^`\\]|\\.)*)`|"((?:[^"\\]|\\.)*)"/g))
        if (VI.test(m[1] ?? m[2] ?? m[3])) bad.push(`js/${f}:${i + 1}: ${(m[1] ?? m[2] ?? m[3]).slice(0, 60)}`);
    });
  }
  assert.deepEqual(bad, [], 'give these words an id in i18n/*.json and write T(\'id\')');
  assert.ok(!/\bconst (EN|RULES) =|window\.tr\b/.test(i18n), 'no table of sentences in i18n.js');
});

// The native apps' words: i18n/apps/<lang>/<module>.json, built into res/values-<lang> by app/gradle/i18n.gradle
const MODULES = ['android', 'channels', 'games', 'home', 'tvapps', 'tvkeys', 'tvui'];
const fmt = (s) => [...new Set([...(typeof s === 'object' ? Object.values(s).join(' ') : Array.isArray(s) ? s.join(' ') : s).matchAll(/%(?:\d+\$)?[sdf%]/g)].map((m) => m[0]))].sort().join(',');
const FREE = new Set(['lunar_year']);                                  // numbered args: a language may use more of them (en leaves the zodiac out)
const shape = (v) => (Array.isArray(v) ? 'array' : v && typeof v === 'object' ? 'plurals' : typeof v);

test('apps/<lang>/<module>.json: every language of langs.json has every module with the keys and %s / %1$d of en', () => {
  const apps = path.join(web, '../i18n/apps');
  assert.deepEqual(fs.readdirSync(apps).filter((d) => fs.statSync(path.join(apps, d)).isDirectory()).sort(), [...LANGS].sort(), 'a folder per language of langs.json');
  const bad = [];
  for (const m of MODULES) {
    const en = JSON.parse(read(`../i18n/apps/en/${m}.json`));
    for (const l of LANGS) {
      const f = `apps/${l}/${m}.json`;
      if (!fs.existsSync(path.join(apps, '..', f))) { bad.push(f + ' missing'); continue; }
      const text = fs.readFileSync(path.join(apps, '..', f), 'utf8'), b = JSON.parse(text);
      const ids = [...text.matchAll(/^ {2}"([^"]+)":/gm)].map((x) => x[1]);   // JSON.parse keeps the last of two: one id read as two things
      for (const k of ids.filter((k, i) => ids.indexOf(k) !== i)) bad.push(`${f}: ${k} twice`);
      for (const k of Object.keys(en)) {
        if (!(k in b)) { bad.push(`${f}: ${k} missing`); continue; }
        if (shape(b[k]) !== shape(en[k])) bad.push(`${f}: ${k} is a ${shape(b[k])}, en a ${shape(en[k])}`);
        else if (shape(en[k]) === 'array' && b[k].length !== en[k].length) bad.push(`${f}: ${k} has ${b[k].length} items, en ${en[k].length}`);
        else if (shape(en[k]) === 'plurals' && !('other' in b[k])) bad.push(`${f}: ${k} has no "other"`);
        else if (fmt(b[k]) !== fmt(en[k]) && !FREE.has(k)) bad.push(`${f}: ${k} has {${fmt(b[k])}}, en {${fmt(en[k])}}`);
      }
      for (const k of Object.keys(b)) if (!(k in en)) bad.push(`${f}: ${k} is not in en`);
    }
  }
  assert.deepEqual(bad, []);
  const tvui = Object.keys(JSON.parse(read('../i18n/apps/en/tvui.json')));   // tvui.json is merged into home, games, channels and tv: no id of theirs may repeat
  for (const m of ['channels', 'games', 'home', 'tvapps']) assert.deepEqual(tvui.filter((k) => k in JSON.parse(read(`../i18n/apps/en/${m}.json`))), [], 'tvui ids clash with ' + m);
});

test('no hand-written strings.xml: the words of the native apps come from i18n/apps (app/gradle/i18n.gradle)', () => {
  const bad = [];
  for (const a of ['android', 'tvchannels', 'tvgames', 'tvhome', 'tvapps', 'tvkeys']) {
    const res = path.join(__dirname, '../..', a, 'src/main/res');
    for (const d of fs.existsSync(res) ? fs.readdirSync(res) : []) if (d.startsWith('values')) for (const f of fs.readdirSync(path.join(res, d)))
      if (/<(string|plurals|string-array)\b/.test(fs.readFileSync(path.join(res, d, f), 'utf8'))) bad.push(`app/${a}/src/main/res/${d}/${f}`);
  }
  assert.deepEqual(bad, [], 'move these words to i18n/apps/<lang>/<module>.json');
});
