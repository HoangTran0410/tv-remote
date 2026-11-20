// The page's Quick keys list the same dead keys as TV Home's own screen: QK_DEAD (js/quickkeys.js) is HomeConfig.KEY_DEAD.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const kt = fs.readFileSync(path.join(root, 'src/home/kotlin/dev/tvremote/home/HomeConfig.kt'), 'utf8');
const js = fs.readFileSync(path.join(root, 'web/js/quickkeys.js'), 'utf8');
const nums = (s) => s.split(',').map((x) => Number(x.trim()));

test('QK_DEAD in quickkeys.js equals HomeConfig.KEY_DEAD', () => {
  const k = kt.match(/val KEY_DEAD = listOf\(([^)]*)\)/);
  const j = js.match(/const QK_DEAD = \[([^\]]*)\]/);
  assert.ok(k && j);
  assert.deepEqual(nums(j[1]), nums(k[1]));
});
