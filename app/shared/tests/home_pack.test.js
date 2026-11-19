// node --test app/shared/tests/   TV Home's card packing (js/home.js homePack) gives what tests/home_pack_cases.json says, the same
// table HomeConfigTest reads for HomeConfig.pack: the dialog's miniature and the TV never disagree about what fits.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../web/js/home.js'), 'utf8');
const from = src.indexOf('const HOME_BIG_KINDS'), to = src.indexOf('const HOME_LAYOUTS');
const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(src.slice(from, to).replace(/^(let|const) /gm, 'var ') + '\nthis.homePack = homePack; this.homeSize = homeSize; this.W = HOME_W;', sandbox);
const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'home_pack_cases.json'), 'utf8')).cases;

for (const c of cases) test(c.name, () => {
  const p = JSON.parse(JSON.stringify(sandbox.homePack(c.keys, c.big, !!c.note)));   // out of the vm: plain arrays
  assert.deepEqual(p.cols.map((x) => x.keys), c.cols);
  assert.deepEqual(p.waiting, c.waiting);
  assert.ok(p.cols.reduce((n, x) => n + x.weight, 0) <= sandbox.W.budget);
});

test('a size is the one chosen, else the card kind\'s', () => {
  assert.equal(sandbox.homeSize({}, 'system'), 'big');
  assert.equal(sandbox.homeSize({}, 'lunar'), 'small');
  assert.equal(sandbox.homeSize({ wsize: { system: 'small', lunar: 'big', timer: 'huge' } }, 'system'), 'small');
  assert.equal(sandbox.homeSize({ wsize: { system: 'small', lunar: 'big', timer: 'huge' } }, 'lunar'), 'big');
  assert.equal(sandbox.homeSize({ wsize: { timer: 'huge' } }, 'timer'), 'small');
});

// "top2": HomeConfig.packRows / homePackRows, the same "rows" table HomeConfigTest reads
vm.runInContext(src.slice(src.indexOf('function homePackRows'), src.indexOf('function homePackColumn')) + '\nthis.homePackRows = homePackRows;', sandbox);
for (const c of JSON.parse(fs.readFileSync(path.join(__dirname, 'home_pack_cases.json'), 'utf8')).rows) test(c.name, () => {
  const p = JSON.parse(JSON.stringify(sandbox.homePackRows(c.keys, c.big, !!c.note, c.rows)));
  assert.deepEqual(p.map((l) => l.cols.map((x) => x.keys)), c.lines);
  assert.deepEqual(p.at(-1).waiting, c.waiting);
});
