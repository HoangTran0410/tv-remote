#!/usr/bin/env node
// The page's layout on real rendering, in Chrome AND WebKit (Safari, the Mac app's window, an iPhone): the faults a look at one
// browser misses. See dev/test/AGENTS.md.
//
//   npm install --prefix dev playwright && npx --prefix dev playwright install webkit     (once)
//   TVR_PASS=... [LANGS=fr] node dev/test/layout.js [URL]       default http://127.0.0.1:8779/: the computer app's test server, whose first saved
//                                                TV (the TV emulator) it opens
//
// For Chromium and WebKit x widths 1100 and 400 x every tab + the settings dialog, in English (longer words than Vietnamese)
// and Vietnamese, it reports:
//   - the page scrolling sideways;
//   - a control or title whose words are cut (ellipsis, or more text than room), or a button whose label wraps;
//   - something sticking out of its card; two cards on top of each other (the masonry columns);
//   - a row of chips that wrapped; two cards one under the other with under 8 px between (glued: a slot holding several cards needs the gap);
// and, left alone a few seconds on each tab (at 1100 px), what the page keeps doing by itself:
//   - a write that changed nothing (the same text or attribute again: still a change for every observer and WebKit's styles);
//   - one element changed more than 3 times a second, or an animation started over and over (a running line that wrapped
//     its own span did both, 20 times a second: the page jerked in WebKit);
//   - a running line inside another.
// A long title plays (/api/playing is answered here), so the now playing strip and its running line are on the page.
// Screenshots of each go to /tmp/tvr-layout/<engine>-<lang>-<width>-<tab>.png to look at. Exit 1 if anything was found.
const path = require('path');
let pw;
try { pw = require(path.join(__dirname, '..', 'node_modules', 'playwright')); } catch { try { pw = require('playwright'); } catch {
  console.error('needs Playwright: npm install --prefix dev playwright && npx --prefix dev playwright install webkit'); process.exit(2); } }

const URL_ = (process.argv[2] || 'http://127.0.0.1:8779/').replace(/\/?$/, '/');
const KEY = process.env.TVR_PASS || '';
const TABS = ['remote', 'apps', 'files', 'health', 'auto', 'tv'];
const OUT = '/tmp/tvr-layout';

// runs in the page: what is wrong under root
// before the page's own scripts: what changes, and how
function watch() {
  window.__tvr = { m: new Map(), same: new Map(), anim: 0 };
  const name = (n) => { if (n.nodeType !== 1) n = n.parentElement; if (!n) return '?';
    const p = n.parentElement, me = n.tagName.toLowerCase() + (n.id ? '#' + n.id : n.classList.length ? '.' + [...n.classList].join('.') : '');
    return n.id || !p ? me : (p.id ? '#' + p.id : p.tagName.toLowerCase() + (p.classList.length ? '.' + p.classList[0] : '')) + ' > ' + me; };
  const inc = (map, k) => map.set(k, (map.get(k) || 0) + 1);
  addEventListener('DOMContentLoaded', () => new MutationObserver((list) => {
    for (const r of list) {
      if (r.target.closest?.('#screenbox, canvas, video') || r.target.parentElement?.closest('#screenbox')) continue;   // the picture changes by itself
      const k = name(r.target) + (r.attributeName ? ' @' + r.attributeName : '');
      inc(window.__tvr.m, k);
      const same = r.type === 'attributes' ? r.target.getAttribute(r.attributeName) === r.oldValue
        : r.type === 'characterData' ? r.target.data === r.oldValue
        : r.addedNodes.length && r.removedNodes.length && [...r.addedNodes].map((x) => x.textContent).join() === [...r.removedNodes].map((x) => x.textContent).join() && [...r.addedNodes].every((x) => x.nodeType === 3);
      if (same) inc(window.__tvr.same, k);
    }
  }).observe(document, { subtree: true, childList: true, characterData: true, characterDataOldValue: true, attributes: true, attributeOldValue: true }));
  const animate = Element.prototype.animate;
  Element.prototype.animate = function () { window.__tvr.anim++; return animate.apply(this, arguments); };
}
const IDLE = 4000;
// after idle ms of nobody touching the page
function busy(idle) {
  const t = window.__tvr, out = [];
  for (const [k, n] of t.same) out.push(`wrote the same again ${n}x: ${k}`);
  for (const [k, n] of t.m) if (n > 3 * idle / 1000) out.push(`changed ${n}x in ${idle / 1000} s: ${k}`);
  if (t.anim > 2) out.push(`${t.anim} animations started`);
  for (const e of document.querySelectorAll('.rin .rin')) out.push('running line in a running line: ' + (e.parentElement.closest('[id]')?.id || '?'));
  return out;
}

function scan(root) {
  const out = [], seen = new Set();
  const shown = (e) => { const r = e.getBoundingClientRect(); if (!r.width || !r.height) return false;
    for (let x = e; x; x = x.parentElement) { const s = getComputedStyle(x); if (s.display === 'none' || s.visibility === 'hidden' || +s.opacity < 0.05) return false; } return true; };
  const name = (e) => (e.id ? '#' + e.id : e.tagName.toLowerCase() + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\s+/).join('.') : ''))
    + ' "' + (e.innerText || e.value || e.title || '').trim().replace(/\s+/g, ' ').slice(0, 40) + '"';
  const add = (kind, e, more) => { const k = kind + name(e); if (seen.has(k)) return; seen.add(k); out.push(kind + ': ' + name(e) + (more ? ' ' + more : '')); };
  if (document.documentElement.scrollWidth > innerWidth + 1) add('page scrolls sideways', document.documentElement, `${document.documentElement.scrollWidth}px > ${innerWidth}px`);
  for (const e of root.querySelectorAll('button, .chip, summary, h1, h2, h3, label, .tab, select, .item .name, .item b')) {
    if (!shown(e) || e.closest('.rc, canvas, video, .pop')) continue;
    const s = getComputedStyle(e);
    if (e.scrollWidth > e.clientWidth + 1 && s.overflowX !== 'visible' || s.textOverflow === 'ellipsis' && e.scrollWidth > e.clientWidth + 1) add('words cut', e);
    if (e.tagName === 'BUTTON' && e.innerText.trim() && !/\n/.test(e.innerText.trim())) {   // its words on more than one line
      const lines = new Set(); const w = document.createTreeWalker(e, NodeFilter.SHOW_TEXT);
      for (let t = w.nextNode(); t; t = w.nextNode()) { if (!t.data.trim()) continue; const rg = document.createRange(); rg.selectNodeContents(t);
        for (const q of rg.getClientRects()) if (q.width > 1) lines.add(Math.round(q.top + q.height / 2)); }
      const ys = [...lines].sort((p, q) => p - q); if (ys.length > 1 && ys[ys.length - 1] - ys[0] > parseFloat(s.fontSize) * 0.8) add('label wraps', e);
    }
  }
  for (const card of root.querySelectorAll('.card')) {
    if (!shown(card)) continue;
    const c = card.getBoundingClientRect();
    for (const e of card.querySelectorAll('button, input, select, .chip, img, .item, .line')) {
      if (!shown(e) || e.closest('.scroll, .list, .table, pre, .pop, [style*="overflow"]')) continue;
      let clipped = false; for (let x = e.parentElement; x && x !== card; x = x.parentElement) { const o = getComputedStyle(x).overflowX; if (o !== 'visible') { clipped = true; break; } }
      if (clipped) continue;
      const r = e.getBoundingClientRect();
      if (r.right > c.right + 1 || r.left < c.left - 1) add('sticks out of its card', e, `(${Math.round(r.left)}–${Math.round(r.right)} in ${Math.round(c.left)}–${Math.round(c.right)})`);
    }
  }
  const cards = [...root.querySelectorAll('.panel:not([hidden]) .card, dialog[open] .card')].filter((e) => shown(e) && !e.parentElement.closest('.card'));
  for (let i = 0; i < cards.length; i++) for (let j = i + 1; j < cards.length; j++) {
    const a = cards[i].getBoundingClientRect(), b = cards[j].getBoundingClientRect();
    if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 4 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 4) add('cards overlap', cards[i], 'and ' + name(cards[j]));
  }
  for (let i = 0; i < cards.length; i++) for (let j = 0; j < cards.length; j++) {   // glued: one right under another with (almost) no gap
    const a = cards[i].getBoundingClientRect(), b = cards[j].getBoundingClientRect(), gap = b.top - a.bottom;
    if (i !== j && Math.min(a.right, b.right) - Math.max(a.left, b.left) > 4 && gap > -4 && gap < 8) add('cards glued (gap ' + Math.round(gap) + 'px)', cards[i], 'and ' + name(cards[j]));
  }
  for (const row of root.querySelectorAll('.places, .fchips, .chips')) {
    const kids = [...row.children].filter(shown);
    if (kids.length > 1 && new Set(kids.map((k) => Math.round(k.getBoundingClientRect().top))).size > 1 && getComputedStyle(row).flexWrap !== 'nowrap') add('chips wrap', row);
  }
  return out;
}

(async () => {
  require('fs').mkdirSync(OUT, { recursive: true });
  let bad = 0;
  for (const engine of ['chromium', 'webkit']) {
    const browser = await (engine === 'chromium' ? pw.chromium.launch({ channel: 'chrome' }).catch(() => pw.chromium.launch()) : pw.webkit.launch());   // the installed Chrome
    for (const lang of (process.env.LANGS || 'en,vi').split(',')) for (const width of [1100, 400]) {
      const page = await browser.newPage({ viewport: { width, height: 900 } });
      await page.addInitScript(watch);
      await page.route('**/api/playing*', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify({ state: 'playing', title: 'A long title that never fits in the strip, so its line runs', sub: 'A long channel name that does not fit either', app: 'YouTube', pkg: 'com.google.android.youtube.tv', pos: 60000, dur: 3600000, speed: 1, seek: true }) }));
      const errors = [];
      page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 160)));
      await page.goto(URL_ + '?lang=' + lang + (KEY ? '&k=' + KEY : ''));
      await page.waitForTimeout(3000);
      // the picker first: the test server's own first saved TV (an emulator; never a TV of the house)
      await page.evaluate(() => { const b = document.querySelector('#tvlist .item button.primary'); if (b && b.offsetParent) b.click(); });
      await page.waitForTimeout(4000);
      for (const tab of TABS.concat('settings')) {
        if (tab === 'settings') await page.evaluate(() => document.getElementById('aboutbtn')?.click());
        else await page.evaluate((t) => { showTab(t); scrollTo(0, 0); }, tab);
        await page.waitForTimeout(tab === 'health' ? 6000 : 1500);
        const list = await page.evaluate(`(${scan})(document.querySelector('dialog[open]') || document.body)`);
        if (width === 1100) {
          await page.evaluate(() => { window.__tvr.m.clear(); window.__tvr.same.clear(); window.__tvr.anim = 0; });
          await page.waitForTimeout(IDLE);
          list.push(...await page.evaluate(`(${busy})(${IDLE})`));
        }
        for (const f of list) { bad++; console.log(`${engine.padEnd(8)} ${lang} ${String(width).padStart(4)} ${tab.padEnd(8)} ${f}`); }
        await page.screenshot({ path: `${OUT}/${engine}-${lang}-${width}-${tab}.png`, fullPage: true });
        if (tab === 'settings') await page.evaluate(() => document.querySelector('dialog[open]')?.close());
      }
      for (const e of [...new Set(errors)]) { bad++; console.log(`${engine.padEnd(8)} ${lang} ${String(width).padStart(4)} script error: ${e}`); }
      await page.close();
    }
    await browser.close();
  }
  console.log(bad ? `${bad} found; screenshots in ${OUT}` : `ok; screenshots in ${OUT}`);
  process.exit(bad ? 1 : 0);
})();
