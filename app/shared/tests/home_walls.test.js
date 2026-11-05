// node --test app/shared/tests/   TV Home's drawn backgrounds: the recipes the page previews with (js/home_walls.js) and the ones the TV
// paints (WallSpec.kt) are the same numbers, every scene has its words and its place in HomeConfig.ART, and the painter runs on a canvas.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const web = path.join(__dirname, '../web');
const js = fs.readFileSync(path.join(web, 'js/home_walls.js'), 'utf8');
const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(js + '\nthis.HOME_ART = HOME_ART; this.paint = homeArtPaint;', sandbox);
const art = JSON.parse(JSON.stringify(sandbox.HOME_ART));

const kt = fs.readFileSync(path.join(__dirname, '../src/home/kotlin/dev/tvremote/home/WallSpec.kt'), 'utf8');
const ktSpec = JSON.parse(kt.match(/"""([\s\S]*?)"""/)[1]);
const cfg = fs.readFileSync(path.join(__dirname, '../src/home/kotlin/dev/tvremote/home/HomeConfig.kt'), 'utf8');
const ids = cfg.match(/val ART = listOf\(([^)]*)\)/)[1].match(/"(\w+)"/g).map((x) => x.replace(/"/g, ''));

test('the page and the TV hold the same recipes (python3 dev/gen/gen_wallspec.py when this fails)', () => {
  assert.deepEqual(ktSpec, art);
});

test('every scene is in HomeConfig.ART, with its words in both languages and on the TV', () => {
  assert.deepEqual(Object.keys(art).sort(), [...ids].sort());
  const vi = JSON.parse(fs.readFileSync(path.join(web, '../i18n/vi.json'), 'utf8')), en = JSON.parse(fs.readFileSync(path.join(web, '../i18n/en.json'), 'utf8'));
  const strings = ['en', 'vi'].map((l) => fs.readFileSync(path.join(web, '../i18n/apps', l, 'home.json'), 'utf8'));
  for (const k of [...ids, 'bing']) {
    assert.ok(vi['home.wall_' + k] && en['home.wall_' + k], 'page words for ' + k);
    for (const s of strings) assert.ok(s.includes(`"wall_${k}":`), 'TV words for ' + k);
  }
});

test('the web page wall: in the whitelist, with its words on the page (the TV panel tile opens the QR screen: the address is pasted on a phone)', () => {
  assert.match(cfg, /val WALLS = [^\n]*\bWEB\b/);
  assert.match(cfg, /const val WURL_MAX = 500/);
  const vi = JSON.parse(fs.readFileSync(path.join(web, '../i18n/vi.json'), 'utf8')), en = JSON.parse(fs.readFileSync(path.join(web, '../i18n/en.json'), 'utf8'));
  assert.match(cfg, /val WSNAPS = listOf\(1, 5, 15, 30, 60\)/);   // the page's chips (home.js) offer the same five
  assert.match(fs.readFileSync(path.join(web, 'js/home.js'), 'utf8'), /\[1, 5, 15, 30, 60\]/);
  for (const k of ['wall_web', 'web', 'web_ph', 'web_set', 'web_clear', 'web_bad', 'web_note', 'web_mode', 'web_mode_live', 'web_mode_snap', 'web_every', 'web_every_n', 'web_keep', 'web_mode_note_live', 'web_mode_note_snap', 'web_mode_note_keep']) assert.ok(vi['home.' + k] && en['home.' + k], k);
});

test('a scene stays readable: a veil factor and stops in order', () => {
  for (const [k, a] of Object.entries(art)) {
    assert.ok(a.shade >= 0.3 && a.shade <= 1, k + ' shade');
    assert.ok(a.bg.length >= 2 && a.bg.every(([c, at], i) => /^#[0-9a-f]{6}$/i.test(c) && at >= 0 && at <= 1 && (i === 0 || at >= a.bg[i - 1][1])), k + ' sky');
  }
});

test('the painter draws every scene without error on a recording context', () => {
  const calls = [];
  const grad = { addColorStop() {} };
  const ctx = new Proxy({}, { get: (_, n) => (n.startsWith('create') ? () => grad : (...a) => calls.push(n)), set: () => true });
  for (const k of Object.keys(art)) { calls.length = 0; sandbox.paint(ctx, 128, 72, k); assert.ok(calls.includes('fillRect') || calls.includes('fill'), k); }
});

test('the preview draws one clock, as TV Home does (HomeConfig.barClock / barDate, HomeBarConfigTest.oneClockOnTheScreen)', () => {
  const sb = {}; vm.createContext(sb); vm.runInContext(js + '\nthis.bar = homeBar;', sb);
  const b = (o) => JSON.parse(JSON.stringify(sb.bar(o)));
  assert.deepEqual(b({ widgets: ['weather'] }), { clock: true, date: true });
  assert.deepEqual(b({ widgets: ['clock'] }), { clock: false, date: false });
  assert.deepEqual(b({ widgets: ['calendar'] }), { clock: true, date: false });
  assert.equal(b({ widgets: ['clock'], dstyle: 'own' }).date, true);
  assert.equal(b({ showClock: false }).clock, false);
  assert.equal(b({ widgets: ['lunar'] }).clock, true);
});

test('the preview puts the widgets where "wplace" says (HomeConfig.WPLACES): row above, column left / right, row under the grid', () => {
  const hj = fs.readFileSync(path.join(web, 'js/home.js'), 'utf8');
  const part = hj.slice(hj.indexOf('const HOME_WIDGETS'), hj.indexOf('const HOME_LAYOUTS')).replace(/^(let|const) /gm, 'var ');
  const sb = {}; vm.createContext(sb);
  vm.runInContext(hj.match(/^const HOME_WALLS = [\s\S]*?\};/m)[0].replace('const ', 'var ') + '\n' + part + '\n' + js + '\nthis.paint = homeThemePaint; this.col = homePackColumn;', sb);
  const draw = (conf) => {
    const boxes = []; const grad = { addColorStop() {} };
    const ctx = new Proxy({ roundRect: (x, y, bw, bh, r) => boxes.push({ x, y, bw, bh, r }) }, {
      get: (t, n) => (n in t ? t[n] : n.startsWith('create') ? () => grad : () => {}), set: () => true });
    sb.paint({ getContext: () => ctx, width: 960, height: 540 }, { wall: 'x', cols: 5, ...conf }, [], null, '', null);
    const wid = boxes.filter((b) => Math.abs(b.r - 540 * 0.035) < 0.01), tile = boxes.filter((b) => Math.abs(b.r - 540 * 0.03) < 0.01);
    return { wid, tile };
  };
  const base = { widgets: ['clock', 'weather', 'calendar'] };
  const top = draw({ ...base, wplace: 'top' }), none = draw(base);
  assert.deepEqual(none.wid, top.wid);                                     // a missing key is today's row
  assert.deepEqual(none.tile, top.tile);
  assert.ok(top.wid.length && top.wid.every((b) => b.y + b.bh <= Math.min(...top.tile.map((t) => t.y)) + 1));
  const left = draw({ ...base, wplace: 'left' }), right = draw({ ...base, wplace: 'right' });
  assert.ok(left.wid.length >= 2 && left.wid.every((b) => b.x + b.bw <= Math.min(...left.tile.map((t) => t.x)) + 1), 'column left of the tiles');
  assert.ok(right.wid.every((b) => b.x >= Math.max(...right.tile.map((t) => t.x + t.bw)) - 1), 'column right of the tiles');
  assert.ok(new Set(left.wid.map((b) => b.x)).size === 1, 'one column');
  const p = JSON.parse(JSON.stringify(sb.col(['clock', 'weather', 'calendar'], ['weather', 'calendar'], false, 378)));
  assert.deepEqual(p.cols.map((c) => c.weight), [90, 180]);                // the twin of HomeConfigTest.sideColumnPacking
  assert.deepEqual(p.waiting, ['calendar']);
});
