// Android's usage events are read in ONE place, Usage.events (app/tvsetup .../setup/rules/Usage.kt): a TV that booted with its clock
// ahead keeps its current stretch of events in the future, and a plain queryEvents(begin, now) comes back empty until the
// next reboot (dev/decisions/apps.md "Usage events and a clock that ran ahead"). A second direct call would bring that back.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');                   // app/

function kotlin(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { if (e.name !== 'build' && e.name !== '.gradle') kotlin(p, out); }
    else if (/\.(kt|java)$/.test(e.name)) out.push(p);
  }
  return out;
}

test('usage events are only queried through Usage.events', () => {
  const bad = kotlin(ROOT)
    .filter((p) => !p.endsWith(path.join('setup', 'rules', 'Usage.kt')))
    .filter((p) => /\.queryEvents\(/.test(fs.readFileSync(p, 'utf8')))
    .map((p) => path.relative(ROOT, p));
  assert.deepStrictEqual(bad, [], 'use Usage.events(c, begin, now) { e -> } instead of queryEvents');
});
