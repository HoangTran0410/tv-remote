// node --test app/shared/tests/   "Select all" of a group of backgrounds (js/home.js homeWallsAll) gives what tests/walls_all_cases.json
// says, the same table HomeConfigTest reads for HomeConfig.wallsAll: the page and the TV pick the same set.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const src = fs.readFileSync(path.join(__dirname, '../web/js/home.js'), 'utf8');
const from = src.indexOf('const HOME_WALLS_MAX'), to = src.indexOf('const HOME_EVERY');
const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(src.slice(from, to).replace(/^const /gm, 'var ') + '\nthis.f = homeWallsAll;', sandbox);
for (const c of JSON.parse(fs.readFileSync(path.join(__dirname, 'walls_all_cases.json'), 'utf8')).cases) test(c.name, () => {
  const r = JSON.parse(JSON.stringify(sandbox.f(c.set, c.group)));
  assert.deepEqual(r.walls, c.walls);
  assert.equal(r.full, c.full);
});
