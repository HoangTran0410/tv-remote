// The user guide (docs/guide.html) stays short enough to read on a phone: a wall of text was the user's complaint.
// A longer topic gets steps, a table (`.cmp`) or one-line tips (`ul.gli`), not more sentences.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const HTML = fs.readFileSync(path.join(__dirname, '../../../docs/guide.html'), 'utf8');
const BLOCK_MAX = 40;                                                // words in one paragraph, step, tip or cell, per language
const TOPIC_MAX = 250;                                               // English words of prose in one topic: tables, code and animations not counted

const words = (h) => h.replace(/<pre[\s\S]*?<\/pre>/g, '').replace(/<[^>]+>/g, ' ').split(/\s+/).filter(Boolean).length;
const topics = [...HTML.matchAll(/<article class="topic" id="([^"]+)"[^>]*>([\s\S]*?)<\/article>/g)].map(([, id, body]) => ({ id, body }));
const spans = (body, lang) => [...body.matchAll(new RegExp(`<span class="${lang}">([\\s\\S]*?)</span>(?=<span class="(?:vi|en)">|<pre|</div>|</p>|</li>|</td>|</th>|</h3>|</h4>|$)`, 'g'))].map((m) => m[1]);

test('the guide has topics', () => assert.ok(topics.length > 30, topics.length));

test(`no paragraph, step, tip or cell over ${BLOCK_MAX} words`, () => {
  const long = [];
  for (const t of topics) for (const lang of ['vi', 'en']) for (const s of spans(t.body, lang)) {
    const n = words(s);
    if (n > BLOCK_MAX) long.push(`${t.id} (${lang}, ${n}): ${s.replace(/<[^>]+>/g, '').slice(0, 60)}…`);
  }
  assert.deepStrictEqual(long, [], 'split these into steps, a table or tips');
});

test(`no topic over ${TOPIC_MAX} words of prose`, () => {
  const prose = (b) => b.replace(/<table[\s\S]*?<\/table>/g, '').replace(/<figure[\s\S]*?<\/figure>/g, '');
  const long = topics.map((t) => [t.id, spans(prose(t.body), 'en').reduce((a, s) => a + words(s), 0)]).filter(([, n]) => n > TOPIC_MAX);
  assert.deepStrictEqual(long, [], 'cut it, or move the rare cases into a table');
});

// docs/guide/ (the guide for AI agents) is written by dev/gen/guide_md.js from guide.html: run it after editing the page
test('the side list names every topic once', () => {
  const side = HTML.slice(HTML.indexOf('gs-group'), HTML.indexOf('<section class="ggroup"'));
  assert.deepStrictEqual([...side.matchAll(/data-t="([^"]+)"/g)].map((m) => m[1]).sort(), topics.map((t) => t.id).sort());
});

test('docs/guide/*.md match guide.html', () => {
  const { build } = require('../../../dev/gen/guide_md.js');
  const dir = path.join(__dirname, '../../../docs/guide');
  const want = build();
  const have = fs.readdirSync(dir, { recursive: true }).filter((f) => fs.statSync(path.join(dir, f)).isFile());
  assert.deepStrictEqual(have.sort(), Object.keys(want).sort(), 'run: node dev/gen/guide_md.js');
  for (const [f, s] of Object.entries(want)) assert.strictEqual(fs.readFileSync(path.join(dir, f), 'utf8'), s, f + ' is stale: run node dev/gen/guide_md.js');
});

test('every topic has data-v, data-src and an entry in docs/data/since.json (release.sh stamps both)', () => {
  const since = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../docs/data/since.json'), 'utf8')).topics;
  const bad = [...HTML.matchAll(/<article class="topic" id="([^"]+)"([^>]*)>/g)]
    .filter(([, id, attrs]) => !(id in since) || !/ data-v="[^"]+"/.test(attrs) || !/ data-src="/.test(attrs)).map(([, id]) => id);
  assert.deepStrictEqual(bad, []);
});

// A card of the home page's "And more" row and the guide topic it opens show the same picture: one shot per feature, so
// the two never drift apart (the guide had its own, older shots while the home page had new ones).
test('each "And more" card\'s picture is in the guide topic it links', () => {
  const index = fs.readFileSync(path.join(__dirname, '../../../docs/index.html'), 'utf8');
  const row = index.slice(index.indexOf('id="car"'), index.indexOf('</section>', index.indexOf('id="car"')));
  const cards = [...row.matchAll(/<a class="fc[^"]*" href="guide#([a-z0-9-]+)"[^>]*>\s*<img data-lang-src="img\/([a-z0-9-]+)-LANG\.webp/g)];
  assert.ok(cards.length > 20, cards.length);
  const missing = [];
  for (const [, id, img] of cards) {
    const t = topics.find((x) => x.id === id);
    if (!t) { missing.push(`${img}: no topic #${id}`); continue; }
    for (const lang of ['vi', 'en']) if (!t.body.includes(`src="img/${img}-${lang}.webp`)) missing.push(`#${id} lacks img/${img}-${lang}.webp`);
  }
  assert.deepStrictEqual(missing, [], 'put the card\'s picture in its topic (<figure class="gshot">)');
});
