#!/usr/bin/env node
// Writes app/shared/web/js/find_topics.js: the user guide's topics (id, Vietnamese and English title, its search words)
// for the page's feature search (js/find.js), from docs/guide.html (the one source). guide_md.js runs it too;
// app/shared/tests/find.test.js fails when the file differs from this output.
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '../..');
const OUT = path.join(ROOT, 'app/shared/web/js/find_topics.js');

const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', middot: '·', rarr: '→', hellip: '…', ndash: '–', mdash: '—' };
const text = (h) => h.replace(/<[^>]+>/g, '').replace(/&(\w+);/g, (m, e) => ENT[e] ?? m).replace(/\s+/g, ' ').trim();

function build() {
  const html = fs.readFileSync(path.join(ROOT, 'docs/guide.html'), 'utf8');
  const rows = [...html.matchAll(/<article class="topic" id="([^"]+)"([^>]*)><h3>([\s\S]*?)<\/h3>/g)].map(([, id, attrs, h]) => {
    const one = (cls) => text((h.match(new RegExp(`<span class="${cls}">([\\s\\S]*?)</span>`)) || [])[1] || '');
    return [id, one('vi'), one('en'), text((attrs.match(/data-kw="([^"]*)"/) || [])[1] || '')];
  });
  return '// Made by dev/gen/find_topics.js from docs/guide.html: do not edit. [id, vi, en, search words] per topic (js/find.js).\n'
    + 'const FIND_TOPICS = [\n' + rows.map((r) => '  ' + JSON.stringify(r) + ',\n').join('') + '];\n';
}

if (require.main === module) { fs.writeFileSync(OUT, build()); console.log('wrote', path.relative(ROOT, OUT)); }
module.exports = { build, OUT };
