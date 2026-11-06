// node --test app/shared/tests/   The tabs are made of the same few shapes (js/ui.js says which): these keep new markup
// from drifting into a look of its own, one inline style or one more control height at a time.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const web = path.join(__dirname, '../web');
const html = fs.readFileSync(path.join(web, 'index.html'), 'utf8');
const { css } = require('./styles.js');
const scripts = fs.readdirSync(path.join(web, 'js')).filter((f) => f.endsWith('.js')).map((f) => [f, fs.readFileSync(path.join(web, 'js', f), 'utf8')]);

test('no inline style in the page', () => {
  const bad = [...html.matchAll(/<(\w+)[^>]*\sstyle="([^"]*)"/g)]
    .filter(([, tag, s]) => !/^--c:#[0-9a-f]{6}$/i.test(s) && !(tag === 'svg' && s === 'display:none'))   // a swatch's colour is data
    .map((m) => m[0].slice(0, 90));
  assert.deepEqual(bad, [], 'give it a class in css/kit.css (UI kit) instead');
});

test('no style: in scripts building markup', () => {
  const bad = scripts.flatMap(([f, s]) => [...s.matchAll(/\bstyle:\s*['"`]/g)].map((m) => f + ':' + s.slice(0, m.index).split('\n').length));
  assert.deepEqual(bad, [], 'give it a class in css/kit.css (UI kit), or use UI.note / UI.empty / UI.warn');
});

test('every card starts with its title', () => {
  // the now playing strip, the D-pad and the Simple remote are parts of the remote; the files card opens on its places, which are its title
  const ok = ['playing', 'rc', 'simple', 'filescard'];
  const bad = [...html.matchAll(/<(section|details)\b([^>]*\bclass="card\b[^"]*"[^>]*)>\s*(?:<!--.*?-->\s*)?<(\w+)([^>]*)>/g)]
    .filter(([, , attrs, tag, rest]) => !/^(h1|h2|summary)$/.test(tag) && !/class="card-head"/.test(rest) && !ok.some((k) => attrs.includes(`id="${k}"`) || attrs.includes(`card ${k}`)))
    .map((m) => m[0].slice(0, 90));
  assert.deepEqual(bad, [], 'a card begins with <h2>, <div class="card-head"><h2>, or (folded) <summary>');
  // ui.js makes them; core.js has the card of a TV kind's intro; 'card stopped' is the whole page once the app quit
  const built = scripts.filter(([f]) => f !== 'ui.js' && f !== 'core.js')
    .flatMap(([f, s]) => [...s.matchAll(/el\('section', \{ className: 'card(?! stopped')/g)].map((m) => f + ':' + s.slice(0, m.index).split('\n').length));
  assert.deepEqual(built, [], 'build cards with UI.card (or UI.say for a card of one line)');
});

test('a button beside a field is as tall as the field', () => {
  const bad = [...html.matchAll(/<form class="line"[^>]*>(.*?)<\/form>/gs)].filter((m) => /class="small\b/.test(m[1])).map((m) => m[0].slice(0, 90));
  assert.deepEqual(bad, [], 'no .small in a form.line: the field is --ctl tall, so is its button');
});

test('control heights come from the tokens', () => {
  // made to be hit without looking (the remote), the page's base sizes, or a box of their own (not a control)
  const ok = new Set(['.chips button.small', '#pairerr', '.tabs button', 'button', '.screenbox', '.rb', '.rb.lab',
    'input[type=text], input:not([type]), input[type=url], input[type=search], input[type=password]', '.pill button', '.seg button', '.seg.mini button',
    '.setpick', '.vol button', '#screeninfo', '.drop', '.tsbox .go', '.scene', '.mitem', 'dialog.ask textarea']);
  const bad = [...css.matchAll(/([^{}]*)\{([^{}]*\bmin-height:\s*\d+px[^{}]*)\}/g)]
    .map((m) => m[1].trim().split('\n').pop().replace(/^.*\*\/\s*/, '').trim()).filter((sel) => !ok.has(sel));
  assert.deepEqual(bad, [], 'use min-height:var(--ctl) (a field and its button), var(--ctl-sm) (.small) or var(--chip)');
});

test('words starting with a colour emoji have a line icon for it', () => {
  // the title test above sees index.html only; buttons and titles the scripts build take their words from i18n (⭐ Manage
  // channels stayed a yellow star among line icons). Running text may keep these marks; plain marks (✓ ✕ ⏻) too need an icon
  const TEXT_MARKS = new Set(['⚠️', '💡', '📴', '🚫', '🔐', '📸', '🗓️', '⛔', '🔓'])   // + the Telegram bot's messages (Telegram.kt): chat text, not this page's;
  const ui = scripts.find(([f]) => f === 'ui.js')[1];
  const known = new Set([...ui.slice(ui.indexOf('const ICON = {'), ui.indexOf('};', ui.indexOf('const ICON = {'))).matchAll(/'([^']+)': \['/g)].map((m) => m[1]));
  const lead = /^\s*(\p{Emoji_Presentation}|\p{Extended_Pictographic}\uFE0F|[✓✔✕✖✗⏻⚠★☆♪↻↩▶⏸⏹⬇])/u;
  const words = JSON.parse(fs.readFileSync(path.join(__dirname, '../i18n/vi.json'), 'utf8'));
  const bad = Object.entries(words).map(([k, v]) => [k, typeof v === 'string' && v.match(lead)?.[1]])
    .filter(([, e]) => e && !known.has(e) && !known.has(e.replace('\uFE0F', '')) && !TEXT_MARKS.has(e)).map(([k, e]) => e + ' ' + k);
  assert.deepEqual(bad, [], 'add it to ICON in js/ui.js (and its path to dev/gen/gen_icons.py), or to TEXT_MARKS when it is only in running text');
});

test("a dialog's card does not scroll itself", () => {
  // the dialog scrolls (page.css); a card that does keeps its sticky head 14px down, the list showing through the gap
  const bad = [...css.matchAll(/([^{}]*)\{([^{}]*)\}/g)].filter(([, sel, body]) => /\boverflow(-y)?:\s*(auto|scroll)/.test(body)
    && sel.split(',').some((x) => /(dialog|ask)\b[^,]*\.card\s*$/.test(x.replace(/\/\*[\s\S]*?\*\//g, '').trim()))).map(([, sel]) => sel.trim());
  assert.deepEqual(bad, [], 'let the dialog scroll: drop overflow / max-height from its card');
});

test('a title or a tab starting with an emoji has an icon for it', () => {
  // ui.js draws these as line icons (ICON); an emoji it does not know would stay an emoji among icons
  const ui = scripts.find(([f]) => f === 'ui.js')[1];
  const known = new Set([...ui.slice(ui.indexOf('const ICON = {'), ui.indexOf('};', ui.indexOf('const ICON = {'))).matchAll(/'([^']+)': \['/g)].map((m) => m[1]));
  const lead = /^\s*([\p{Extended_Pictographic}←-⇿✀-➿][️]?)/u;
  const bad = [...html.matchAll(/<(h2|h3|summary)\b[^>]*>([^<]*)|<span class="ico">([^<]*)/g)]
    .map((m) => (m[2] ?? m[3] ?? '').match(lead)?.[1]).filter((e) => e && !known.has(e) && !known.has(e.replace('️', '')));
  assert.deepEqual([...new Set(bad)], [], 'add it to ICON in js/ui.js (and its path to dev/gen/gen_icons.py)');
});

test('the chart legend keeps its small keys', () => {
  // .st-key is a button only to be pressed: the tabs' rule that sizes every button must pass it by, or the legend swells
  const rules = css.split('}').filter((r) => /\.panel:not\(\[data-panel=remote\]\)[^{]*button/.test(r) && /min-height:var\(--ctl\)|padding:0 16px/.test(r));
  assert.ok(rules.length >= 2);
  for (const r of rules) assert.match(r, /:not\(\.st-key\)/, r.slice(0, 80));
});

test('cards put side by side in one slot are spaced (never glued)', () => {
  // kit.css spaces any plain div holding 2+ cards (a script's slot); this keeps that rule, and keeps scripts from filling
  // a slot that is not a plain div (a class of its own would lose the rule) with several cards at once
  assert.match(css, /:where\(.*:has\(> \.card ~ \.card\)\)\s*\{[^}]*gap:\s*14px/, 'kit.css lost the rule that spaces a wrapper of cards');
  const bad = [];
  for (const [f, s] of scripts) for (const m of s.matchAll(/\$\('#([\w-]+)'\)\.(?:replaceChildren|append)\(([^;\n]*)/g)) {
    if ((m[2].match(/\bUI\.(?:card|say)\(/g) || []).length < 2) continue;
    const tag = html.match(new RegExp(`<(\\w+)([^>]*\\bid="${m[1]}"[^>]*)>`));
    const cls = tag && tag[2].match(/class="([^"]*)"/);
    if (!tag || tag[1] !== 'div' || (cls && !/^(stack|two-up)$/.test(cls[1].trim()))) bad.push(`${f}: #${m[1]}`);
  }
  assert.deepEqual(bad, [], 'put the cards in a plain <div id> or .stack: it spaces them');
});
