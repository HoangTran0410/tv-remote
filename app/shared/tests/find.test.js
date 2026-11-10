// The feature search (js/find.js): its guide topics are the guide's (dev/gen/find_topics.js), and every place it sends a topic
// to is still on the page, in the tab it names.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const web = path.join(__dirname, '../web');
const gen = require('../../../dev/gen/find_topics.js');
const html = fs.readFileSync(path.join(web, 'index.html'), 'utf8');
const src = fs.readFileSync(path.join(web, 'js/find.js'), 'utf8');
const FIND_AT = Function(src.match(/const FIND_AT = (\{[\s\S]*?\n\});/)[0] + 'return FIND_AT;')();
const FIND_TABS = Function(src.match(/const FIND_TABS = \[[^\]]*\];/)[0] + 'return FIND_TABS;')();

test('js/find_topics.js is made from docs/guide.html (node dev/gen/find_topics.js)', () => {
  assert.strictEqual(fs.readFileSync(gen.OUT, 'utf8'), gen.build());
});

test('FIND_AT names guide topics, and places that are on the page', () => {
  const ids = new Set([...gen.build().matchAll(/^ {2}\["([^"]+)"/gm)].map((m) => m[1]));
  // the panel each card id sits in: the last data-panel before it
  const panelOf = (id) => { const i = html.indexOf('id="' + id + '"'); if (i < 0) return null; const ps = [...html.slice(0, i).matchAll(/data-panel="(\w+)"/g)]; return ps.length ? ps[ps.length - 1][1] : null; };
  const about = html.slice(html.indexOf('<dialog id="about"'), html.indexOf('</dialog>', html.indexOf('<dialog id="about"')));
  const bad = [];
  for (const [topic, at] of Object.entries(FIND_AT)) {
    if (!ids.has(topic)) bad.push(topic + ': no such guide topic');
    if (at[0] === '@') { if (!about.includes('<details class="sect" id="' + at.slice(1) + '"')) bad.push(topic + ': no Settings section ' + at); continue; }
    const [tab, card] = at.split('#');
    if (!FIND_TABS.includes(tab)) bad.push(topic + ': no tab ' + tab);
    if (card && panelOf(card) !== tab) bad.push(topic + ': card #' + card + ' is not in the ' + tab + ' tab');
  }
  assert.deepStrictEqual(bad, []);
});

test('FIND_TABS are the tab bar', () => {
  assert.deepStrictEqual([...html.matchAll(/<button role="tab" data-tab="(\w+)"/g)].map((m) => m[1]), FIND_TABS);
});
