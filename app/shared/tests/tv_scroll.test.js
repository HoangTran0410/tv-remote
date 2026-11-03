// The TV apps' scroll views bring the D-pad's focus to the middle (tvui TvScroll / TvHScroll): Android's own ScrollView stops the
// focused row at the edge, and a person cannot tell more rows wait beyond it (the user, 2026-11-03). A bare ScrollView /
// HorizontalScrollView is allowed only where the view does its own scrolling: it overrides computeScrollDeltaToGetChildRectOnScreen
// or requestChildRectangleOnScreen within its first lines (TV Home's grid, TV Games' library).
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '../../..');
const DIRS = ['app/tvsetup/src/main', 'app/tvhome/src/main', 'app/tvchannels/src/main', 'app/tvgames/src/main', 'app/tvapps/src/main',
  'app/shared/src/tvui', 'app/shared/src/home'].map((d) => path.join(ROOT, d));
const BARE = /(?<![\w.])(?:android\.widget\.)?(?:Horizontal)?ScrollView\(/;
const OWN = /computeScrollDeltaToGetChildRectOnScreen|requestChildRectangleOnScreen/;

function kt(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) kt(p, out); else if (e.name.endsWith('.kt') && e.name !== 'TvScroll.kt') out.push(p);
  }
  return out;
}

test('every scroll view of the TV apps centers the focus, or scrolls by itself', () => {
  const bad = [];
  for (const f of DIRS.flatMap((d) => kt(d))) {
    const lines = fs.readFileSync(f, 'utf8').split('\n');
    lines.forEach((l, i) => {
      if (!BARE.test(l) || /^\s*(\/\/|\*)/.test(l)) return;
      if (OWN.test(lines.slice(i, i + 4).join('\n'))) return;
      bad.push(`${path.relative(ROOT, f)}:${i + 1}: ${l.trim().slice(0, 90)}  -> TvScroll / TvHScroll (dev.tvremote.tvui)`);
    });
  }
  assert.deepStrictEqual(bad, []);
});

test('the check sees a bare one', () => {
  assert.ok(BARE.test('val s = ScrollView(this).apply {') && BARE.test('object : android.widget.HorizontalScrollView(a) {'));
  assert.ok(!BARE.test('val s = TvScroll(this)'));
});
