// The TV apps keep app/DESIGN-TV.md: no new raw colour in a view (named constants only), one tv_style.xml, every word in both languages, icons generated.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', '..');
const APPS = ['tvsetup', 'tvhome', 'tvchannels', 'tvgames', 'tvapps'];
const walk = (d) => fs.readdirSync(d, { withFileTypes: true })
  .flatMap((e) => e.isDirectory() ? walk(path.join(d, e.name)) : e.name.endsWith('.kt') ? [path.join(d, e.name)] : []);

// Raw colours outside a named constant (`val CARD = 0xFF1B2028.toInt()`), per file, as they were when the rule came in
// (DESIGN-TV.md "Known drift"). A count may only go down: lower it here when a file is cleaned.
const RAW = {
  tvsetup: { 'DrawLayer.kt': 2, 'LauncherConfirmActivity.kt': 6, 'MainActivity.kt': 3, 'ManageActivity.kt': 3, 'NotifyLayer.kt': 3, 'PairActivity.kt': 6,
    'PinActivity.kt': 5, 'WarnActivity.kt': 6 },
  tvhome: { 'Customize.kt': 25, 'Drop.kt': 7, 'Frost.kt': 3, 'HomeActivity.kt': 53, 'HomeDream.kt': 1, 'TopBar.kt': 2, 'WallArt.kt': 3,
    'Widgets.kt': 4, 'CalendarWidget.kt': 7, 'ClockWidget.kt': 7, 'LunarWidget.kt': 2, 'SystemWidget.kt': 2, 'UsageWidget.kt': 2,
    'WeatherWidget.kt': 6, 'WidgetViews.kt': 2, 'Zoom.kt': 3 },
  tvchannels: { 'BrowseActivity.kt': 4, 'Pins.kt': 2, 'PlayActivity.kt': 8, 'ReminderNotice.kt': 1 },
  tvgames: { 'GameActivity.kt': 4, 'GameCards.kt': 4, 'LibraryActivity.kt': 34, 'LobbyTheme.kt': 1, 'LobbyView.kt': 46, 'Pins.kt': 3,
    'WebGameActivity.kt': 4 },
  tvapps: {},
};
const COLOUR = /0x[0-9A-Fa-f]{8}\b|Color\.(rgb|argb|parseColor)\(/g;
const NAMED = /^\s*(private |internal |public )?(const )?va[lr] [A-Z][A-Z0-9_]*\s*[:=]/;
const raw = (f) => fs.readFileSync(f, 'utf8').split('\n')
  .reduce((n, l) => NAMED.test(l) ? n : n + (l.replace(/\/\/.*$/, '').match(COLOUR) || []).length, 0);

test('no new raw colour in the TV apps\' views: a colour is a named constant or tv_style.xml', () => {
  const bad = [];
  for (const a of APPS) for (const f of walk(path.join(ROOT, 'app', a, 'src', 'main'))) {
    const n = raw(f), allowed = RAW[a][path.basename(f)] || 0;
    if (n > allowed) bad.push(`app/${a}/${path.relative(path.join(ROOT, 'app', a), f)}: ${n} raw colours (allowed ${allowed})`);
    if (n < allowed) bad.push(`app/${a}/.../${path.basename(f)}: down to ${n}: lower RAW in tv_design.test.js`);
  }
  assert.deepStrictEqual(bad, [], 'name it beside the others (Channels Ui, Setup BG…BAD, Home\'s consts) or use tv_style.xml:\n' + bad.join('\n'));
});

// Views that draw themselves (onDraw / dispatchDraw), per file: a hand-drawn view is how a new screen drifts from the
// components (DESIGN-TV.md §3): the RAM tile and its own frame, 2026-10-23. A count may only go down; a new one needs a reason here.
const DRAWN = {
  tvsetup: { 'DrawLayer.kt': 1, 'MicLevel.kt': 1 },                       // drawing over the TV: strokes; the voice card's live sound graph: nothing to reuse
  tvhome: { 'CalendarWidget.kt': 1, 'ClockWidget.kt': 1, 'LunarWidget.kt': 1, 'UsageWidget.kt': 1, 'WeatherWidget.kt': 1,
    'WidgetViews.kt': 2, 'HomeActivity.kt': 1, 'QrView.kt': 1, 'Zoom.kt': 1, 'Customize.kt': 1, 'LiveWidgets.kt': 1,
    'Tonal.kt': 2 },                                                  // the scalloped badge and the bar with its dot: no stock view has either
  android: { 'RemoteScreen.kt': 1, 'IconButton.kt': 1, 'AirMouse.kt': 2, 'Drawing.kt': 1 },
};

test('no new hand-drawn view: build from the components (DESIGN-TV.md §3, phone: Ui.kt)', () => {
  const bad = [];
  for (const a of ['tvsetup', 'tvhome', 'tvchannels', 'tvgames', 'tvapps', 'android']) {
    for (const f of walk(path.join(ROOT, 'app', a, 'src', 'main'))) {
      const n = (fs.readFileSync(f, 'utf8').match(/override fun (onDraw|dispatchDraw)\(/g) || []).length;
      const allowed = (DRAWN[a] || {})[path.basename(f)] || 0;
      if (n > allowed) bad.push(`app/${a}/.../${path.basename(f)}: ${n} drawn view(s), ${allowed} allowed: reuse a component, or add it to DRAWN with why`);
      if (n < allowed) bad.push(`app/${a}/.../${path.basename(f)}: down to ${n}: lower DRAWN in tv_design.test.js`);
    }
  }
  assert.deepEqual(bad, []);
});

// The shared look (app/shared/src/tvui): tv_style.xml and Tv.kt are the ONE copy; channels and games build them in (res.srcDirs / srcDirs).
const TVUI = path.join(ROOT, 'app', 'shared', 'src', 'tvui');

test('tv_style.xml: one copy in tvui; no app has its own; tv, channels and games build tvui/res in', () => {
  for (const a of APPS) {
    assert.ok(!fs.existsSync(path.join(ROOT, 'app', a, 'src/main/res/values/tv_style.xml')), `app/${a} has its own tv_style.xml: delete it, tvui's is built in`);
    const gradle = fs.readFileSync(path.join(ROOT, 'app', a, 'build.gradle.kts'), 'utf8');
    assert.match(gradle, /shared\/src\/tvui\/kotlin/, `${a} must compile tvui's Kotlin`);
    if (a !== 'tvhome') assert.match(gradle, /res\.srcDirs\("\.\.\/shared\/src\/tvui\/res"\)/, `${a} must build tvui/res in`);   // TV Home reads no R.*.tv_* (it takes the constants)
  }
});

test('Tv.kt\'s colours are tv_style.xml\'s, and it holds no raw colour outside a named constant', () => {
  const xml = fs.readFileSync(path.join(TVUI, 'res/values/tv_style.xml'), 'utf8');
  const kt = fs.readFileSync(path.join(TVUI, 'kotlin/dev/tvremote/tvui/Tv.kt'), 'utf8');
  const kv = (n) => (kt.match(new RegExp('const val ' + n + ' = 0x([0-9A-Fa-f]{8})')) || [])[1];
  for (const [xmlName, k] of [['tv_bg', 'BG'], ['tv_card', 'CARD'], ['tv_ink', 'TEXT'], ['tv_dim', 'DIM'], ['tv_ring_accent', 'RING_ACCENT']]) {
    const x = (xml.match(new RegExp('name="' + xmlName + '">#([0-9A-Fa-f]{8})')) || [])[1];
    assert.ok(x && kv(k), xmlName + ' / ' + k + ' missing');
    assert.strictEqual(kv(k).toUpperCase(), x.toUpperCase(), `${k} in Tv.kt is not ${xmlName} of tv_style.xml`);
  }
  for (const f of walk(TVUI)) assert.strictEqual(raw(f), 0, `${path.basename(f)}: a raw colour: name it in Tv.kt`);
});

// The components live in tvui and nowhere else: a hand-built look-alike row / sheet / panel is how the apps drifted (dev/decisions/apps.md "One shared UI set", 2026-10-23).
test('no app defines a component tvui already has', () => {
  const names = ['row', 'sheet', 'sheetTitle', 'sheetNote', 'states', 'ring', 'field', 'panel', 'panelBg', 'scrim', 'menuButton', 'withIcon',
    'pill', 'pillText', 'focusBg', 'heading', 'label'];   // (a game's own initials() / a pad button() are other things)
  // TV Setup and TV Home have local helpers that share a short name (a nested `fun row(` of a page, `label()`), so for them only the
  // names that are unmistakably tvui's: the tile focus, the glass, the motion, the ring, the glass menu and panel
  const own = ['states', 'ringDrawable', 'panelBg', 'scrim', 'menuButton', 'focusBg', 'pillText', 'sheetTitle'];
  const bad = [];
  for (const a of APPS) for (const f of walk(path.join(ROOT, 'app', a, 'src', 'main'))) {
    const src = fs.readFileSync(f, 'utf8');
    for (const n of (a === 'tvchannels' || a === 'tvgames' || a === 'tvapps') ? names : own) if (new RegExp('fun (\\w+\\.)?' + n + '\\(').test(src)) bad.push(`app/${a}/.../${path.basename(f)}: fun ${n}( : use tvui's`);
    if (/object Ui\b/.test(src)) bad.push(`app/${a}/.../${path.basename(f)}: object Ui: the look is tvui's TvUi`);
    if (/(object Motion|class Spring|class Glass|object TvGlass|open class TvTile)\b/.test(src)) bad.push(`app/${a}/.../${path.basename(f)}: Motion / Spring / Glass / TvTile are tvui's`);
    // the colours tvui owns, named again (Setup's BG / CARD / INK, Home's RING_ACCENT / GLASS_* / PANEL_*)
    if (/\b(private |internal )?(const )?va[lr] (BG|CARD|RING_ACCENT|RING_OUT|GLASS_FOCUS|GLASS_FILL|GLASS_EDGE|PANEL_TOP|PANEL_BOTTOM)\b/.test(src)) bad.push(`app/${a}/.../${path.basename(f)}: a look constant tvui has: use TvUi / TvGlass`);
  }
  assert.deepStrictEqual(bad, []);
});

test('TV Home and TV Setup colour RAM and storage through ONE level function (HomeConfig.level), not their own thresholds', () => {
  const cfg = fs.readFileSync(path.join(ROOT, 'app/shared/src/home/kotlin/dev/tvremote/home/HomeConfig.kt'), 'utf8');
  assert.match(cfg, /const val WARN_AT = 70/); assert.match(cfg, /const val BAD_AT = 85/);
  const w = fs.readFileSync(path.join(ROOT, 'app/tvhome/src/main/kotlin/dev/tvremote/home/widgets/Widgets.kt'), 'utf8');
  assert.match(w, /HomeConfig\.level\(/, 'the System card must colour its rings and bars by HomeConfig.level');
  assert.ok(!/availMem/.test(w.replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/it\.totalMem - it\.availMem/, '')), 'the card reads MemAvailable (/proc/meminfo); availMem counts cached apps as free (only as a fallback)');
});

// every word of TV Home, TV Channels and TV Games in every language: i18n.test.js (i18n/apps/<lang>/<module>.json)

test('the TV apps\' line icons all come from dev/gen/gen_icons.py', () => {
  const bad = [];
  for (const a of APPS) {
    const d = path.join(ROOT, 'app', a, 'src/main/res/drawable');
    for (const f of fs.existsSync(d) ? fs.readdirSync(d) : []) {
      if (/^ic_.*\.xml$/.test(f) && !fs.readFileSync(path.join(d, f), 'utf8').includes('generated by dev/gen/gen_icons.py')) bad.push(`app/${a}/${f}`);
    }
  }
  assert.deepStrictEqual(bad, [], 'add it to dev/gen/gen_icons.py, never by hand');
});
