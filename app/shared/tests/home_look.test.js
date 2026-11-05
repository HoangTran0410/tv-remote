// node --test app/shared/tests/   TV Home's two look choices (HomeConfig "tstyle" and "accent"): the page's palette and styles are the
// TV's, every word exists in every language, and the theme preview (js/home_walls.js homeThemePaint) draws each style and the accent.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const web = path.join(__dirname, '../web');
const js = fs.readFileSync(path.join(web, 'js/home_walls.js'), 'utf8');
const cfg = fs.readFileSync(path.join(__dirname, '../src/home/kotlin/dev/tvremote/home/HomeConfig.kt'), 'utf8');
const sb = {};
vm.createContext(sb);
vm.runInContext('var HOME_WALLS = { ocean: "#03192b,#0a4a66" }, HOME_WIDGETS = [], HOME_PLACES = ["top", "left", "right"]; function homePack() { return { cols: [] }; } function homeSize() { return "small"; }\n' + js + '\nthis.A = HOME_ACCENTS; this.S = HOME_TSTYLES; this.paint = homeThemePaint;', sb);
const langs = Object.keys(JSON.parse(fs.readFileSync(path.join(web, '../i18n/langs.json'), 'utf8')));

test('the page palette equals HomeConfig.ACCENTS and the styles equal TSTYLES', () => {
  const kt = Object.fromEntries([...cfg.match(/val ACCENTS = linkedMapOf\(([\s\S]*?)\)\n/)[1].matchAll(/"(\w+)" to "(#[0-9A-F]{6})"/g)].map((m) => [m[1], m[2]]));
  assert.equal(Object.keys(kt).length, 8);
  assert.deepEqual(JSON.parse(JSON.stringify(sb.A)), kt);
  assert.deepEqual([...sb.S], cfg.match(/val TSTYLES = listOf\(([^)]*)\)/)[1].match(/"(\w+)"/g).map((x) => x.replace(/"/g, '')));
});

test('every word of the two choices exists on the page and on the TV in every language', () => {
  const page = ['home.tstyle', 'home.accent', 'home.accent_none', ...sb.S.map((x) => 'home.tstyle_' + x), ...Object.keys(sb.A).map((x) => 'home.accent_' + x)];
  for (const l of langs) {
    const p = JSON.parse(fs.readFileSync(path.join(web, '../i18n', l + '.json'), 'utf8'));
    const t = JSON.parse(fs.readFileSync(path.join(web, '../i18n/apps', l, 'home.json'), 'utf8'));
    for (const k of page) { assert.ok(p[k], l + ' page ' + k); assert.ok(t[k.replace('home.', 'c_')], l + ' tv ' + k); }
  }
});

test('the preview draws a tile in each style, and the focus in the accent (an unknown style is the banner)', () => {
  for (const tstyle of ['banner', 'icon', 'glass', 'neon']) for (const focus of ['ring', 'glass']) {
    const strokes = []; let rects = 0;
    const ctx = new Proxy({}, {
      get: (t, k) => k === 'createLinearGradient' ? () => ({ addColorStop() {} }) : k === 'measureText' ? () => ({ width: 10 }) : k === 'stroke' ? () => strokes.push(t.strokeStyle) : k === 'roundRect' ? () => { rects++; } : (t[k] ?? (() => {})),
      set: (t, k, v) => { t[k] = v; return true; },
    });
    sb.paint({ width: 320, height: 180, getContext: () => ctx }, { wall: 'ocean', widgets: [], cols: 5, rows: 2, focus, tstyle, accent: 'teal' }, [], null, '');
    assert.ok(rects > 5, tstyle + ' drew tiles');
    assert.ok(strokes.includes('#0E9F92'), tstyle + ' ' + focus + ' edge in the accent');
  }
});
