// Every "which app is Home?" asks with MATCH_DEFAULT_ONLY (dev/flows/home-screen.md "Asking Android which app is Home"):
// without it a HOME entry that is not DEFAULT drops the saved Home (shell) or answers the picker (an app).
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '../../..');

function kt(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { if (e.name !== 'build' && e.name !== 'test') kt(p, out); }
    else if (e.name.endsWith('.kt')) out.push(p);
  }
  return out;
}

test('HOME is resolved with MATCH_DEFAULT_ONLY everywhere', () => {
  const bad = [];
  for (const f of kt(path.join(root, 'app'))) {
    fs.readFileSync(f, 'utf8').split('\n').forEach((l, i) => {
      const at = path.relative(root, f) + ':' + (i + 1);
      if (/resolveActivity\(.*CATEGORY_HOME\),\s*0\)/.test(l)) bad.push(at + ' resolveActivity(HOME, 0)');
      if (/resolve-activity[^"]*category\.HOME/.test(l) && !/--query-flags 0x10000/.test(l) && !/\*\/\*\) echo/.test(l)) bad.push(at + ' resolve-activity HOME without --query-flags');
    });
  }
  assert.deepStrictEqual(bad, []);
});
