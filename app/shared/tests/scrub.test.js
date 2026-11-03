// node --test app/shared/tests/   js/scrub.js (what an error report must not carry) gives what tests/scrub_cases.json says;
// core/Scrub.kt is held to the same table (ScrubTest.kt).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ctx = vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname, '../web/js/scrub.js'), 'utf8') + '\nthis.Scrub = Scrub;', ctx);
const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'scrub_cases.json'), 'utf8')).cases;

test('every case of scrub_cases.json', () => {
  for (const c of cases) assert.equal(ctx.Scrub.text(c.in), c.out, c.name);
});
test('scrubbing twice changes nothing more', () => {
  for (const c of cases) if (c.again !== false) assert.equal(ctx.Scrub.text(c.out), c.out, c.name);
});
test('not a string: empty', () => {
  assert.equal(ctx.Scrub.text(undefined), '');
  assert.equal(ctx.Scrub.text(null), '');
});
// A TV box's old WebView (before Chrome 62/64) or Safari before 16.4 cannot parse a lookbehind or a named group: the whole file
// fails ("invalid group specifier name", census 0f54fa44: js/home_pin.js in TV Setup's Manage screen on an HK1 box). Every script a phone's browser loads is checked, not only scrub.js.
test('no page script uses a lookbehind or a named group (old WebView, old Safari)', () => {
  const dirs = [path.join(__dirname, '../web/js'), path.join(__dirname, '../../tvgames/src/main/assets/pad')];
  const bad = dirs.flatMap((d) => fs.readdirSync(d).filter((f) => f.endsWith('.js')).map((f) => path.join(d, f)))
    .filter((f) => /\(\?<[=!A-Za-z]/.test(fs.readFileSync(f, 'utf8').replace(/^\s*\/\/.*$/gm, '').replace(/ \/\/ .*$/gm, '')))
    .map((f) => path.relative(path.join(__dirname, '../..'), f));
  assert.deepEqual(bad, []);
});

// A selector a script hands to querySelector with :is() / :where() (Chrome 88) or :has() (Chrome 105) throws on the old WebViews the
// page still runs on (Chrome 80, js/old.js): fold.js's threw on a Xiaomi MiTV (census 328e5f39) and the Setup tab's cards never folded.
// In CSS the same selector only drops its rule.
test('no page script uses :is(), :where() or :has() in a selector (old WebView)', () => {
  const d = path.join(__dirname, '../web/js');
  const bad = fs.readdirSync(d).filter((f) => f.endsWith('.js'))
    .filter((f) => /:(is|where|has)\(/.test(fs.readFileSync(path.join(d, f), 'utf8').replace(/^\s*\/\/.*$/gm, '').replace(/ \/\/ .*$/gm, '')));
  assert.deepEqual(bad, []);
});
