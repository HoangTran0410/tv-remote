#!/usr/bin/env node
// The page while its server fails: every /api request down, or answering 500, or some paths failing while the rest work (a TV
// dropping off mid-poll, an old TV Setup, a route the core lacks). api() and the page's own fetch().catch(() => null) give null
// then, and code that reads a field of it throws: census e4ac11c3 (r.always), da621472, the bedtime switch (saveBeds). The unit
// tests and audit.js only ever see answers that worked, so this is the one place that path runs.
//
//   node dev/test/apifail.js [--wait=6 s on each tab] [--only=flaky]
//
// No TV and no server, as slowload.js: the working tree's page, /api from the demo's made-up TV (docs/demo/mock.js), then broken.
// Each core, every tab in turn. Exit 1 on any script error or a shown error box.
const fs = require('fs'), path = require('path');
let pw;
try { pw = require(path.join(__dirname, '..', 'node_modules', 'playwright')); } catch { try { pw = require('playwright'); } catch {
  console.error('needs Playwright: npm install --prefix dev playwright'); process.exit(2); } }

const arg = (k, d) => { const a = process.argv.find((x) => x.startsWith('--' + k + '=')); return a ? a.split('=')[1] : d; };
const ROOT = path.join(__dirname, '..', '..'), WEB = path.join(ROOT, 'app/shared/web'), DOCS = path.join(ROOT, 'docs');
const HOST = 'http://fail.test', WAIT = +arg('wait', 6) * 1000;
const TYPES = { html: 'text/html; charset=utf-8', js: 'text/javascript', css: 'text/css', json: 'application/json', png: 'image/png',
  webp: 'image/webp', jpg: 'image/jpeg', svg: 'image/svg+xml', woff2: 'font/woff2', sig: 'text/plain' };
const CORES = ['pc', 'tv', 'phone', 'ontv'];
const TABS = ['remote', 'apps', 'files', 'health', 'auto', 'tv'];
// down: the network fails (fetch rejects). 500: the server answers with an error. flaky-N: a fixed half of the paths fail (by a hash
// of the path and N), the others work, so the page gets past its first state and into the code that assumed every answer came.
const MODES = ['down', '500', 'flaky-1', 'flaky-2', 'flaky-3'].filter((m) => !arg('only') || m.startsWith(arg('only')));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const hash = (s) => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return h >>> 0; };
// What the page needs to load at all: without /api/state no page is drawn, which is its own screen (tested by the cores' own runs).
const KEEP = new Set(['/api/state']);

function serve(host, core, mode) {
  return async (route) => {
    const u = new URL(route.request().url());
    const file = (f) => fs.existsSync(f) && fs.statSync(f).isFile() ? route.fulfill({ body: fs.readFileSync(f), contentType: TYPES[f.split('.').pop()] || 'application/octet-stream' }) : route.fulfill({ status: 404, body: 'not found' });
    if (u.origin !== HOST) return route.abort();
    if (u.pathname.startsWith('/api/')) {
      const fail = !KEEP.has(u.pathname) && (!mode.startsWith('flaky') || hash(u.pathname + mode) % 2 === 0);
      if (fail && mode === 'down') return route.abort('connectionrefused');
      if (fail) return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"test: failed on purpose"}' });
      const r = await host().evaluate((q) => window.demoApi(q), { method: route.request().method(), path: u.pathname, search: u.search, body: route.request().postData() || '' })
        .catch((e) => ({ status: 500, type: 'application/json', body: JSON.stringify({ error: String(e) }) }));
      return route.fulfill({ status: r.status, contentType: r.type, body: r.body });
    }
    if (u.pathname === '/demo/host.html') return route.fulfill({ contentType: TYPES.html, body: '<!doctype html><script src="mock.js"></script>' });
    if (u.pathname === '/app/' || u.pathname === '/app/index.html') {
      const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8').replace('<html ', `<html data-core="${core === 'ontv' ? 'tv' : core}" `);
      return route.fulfill({ contentType: TYPES.html, body: html });
    }
    if (u.pathname.startsWith('/app/i18n/')) return file(path.join(WEB, '../i18n', decodeURIComponent(u.pathname.slice(10))));
    if (u.pathname.startsWith('/app/')) return file(path.join(WEB, decodeURIComponent(u.pathname.slice(5))));
    return file(path.join(DOCS, decodeURIComponent(u.pathname)));
  };
}

(async () => {
  const browser = await pw.chromium.launch({ channel: 'chrome' }).catch(() => pw.chromium.launch());
  const faults = new Map();                                          // one line per error, with every run it showed in
  for (const mode of MODES) for (const core of CORES) {
    const ctx = await browser.newContext({ viewport: { width: 1100, height: 900 } });
    let hostPage = null;
    await ctx.route('**/*', serve(() => hostPage, core, mode));
    hostPage = await ctx.newPage();
    await hostPage.goto(HOST + '/demo/host.html');
    await hostPage.waitForFunction(() => typeof window.demoApi === 'function');
    await ctx.addInitScript(() => { if (!location.pathname.startsWith('/demo/')) window.open = () => null; });
    const page = await ctx.newPage();
    const errors = new Set();
    page.on('pageerror', (e) => errors.add(String(e.message || e).slice(0, 160) + ' · ' + (String(e.stack || '').split('\n').find((l) => l.includes('/app/')) || '').trim().replace(HOST, '')));
    page.on('dialog', (d) => d.dismiss().catch(() => {}));
    await page.goto(HOST + '/app/?tab=remote' + (core === 'ontv' ? '&ontv=1' : ''), { waitUntil: 'load', timeout: 60000 });
    for (const tab of TABS) {
      await page.evaluate((t) => { const b = document.querySelector(`[data-tab="${t}"]`); if (b && !b.closest('[hidden]')) b.click(); }, tab).catch(() => {});
      await sleep(WAIT);
    }
    const shown = await page.evaluate(() => { const b = document.querySelector('.errbox'); return b && !b.hidden ? b.textContent.slice(0, 160) : ''; }).catch(() => '');
    if (shown && ![...errors].some((e) => shown.includes(e.split(' · ')[0]))) errors.add('error box: ' + shown);
    console.log((errors.size ? 'WRONG ' : 'ok    ') + mode.padEnd(8) + core + [...errors].map((e) => '\n      - ' + e).join(''));
    for (const e of errors) faults.set(e, [...(faults.get(e) || []), mode + ' ' + core]);
    await ctx.close();
  }
  await browser.close();
  console.log(faults.size ? `WRONG (${faults.size} errors)` + [...faults].map(([e, w]) => '\n  ' + e + '\n    in ' + w.join(', ')).join('')
    : 'OK: no script error while the server fails');
  process.exit(faults.size ? 1 : 0);
})();
