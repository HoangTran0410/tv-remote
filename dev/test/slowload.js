#!/usr/bin/env node
// The page loaded as a slow TV sends it: each script comes late, one after another, while the first /api answers come back
// at once. Code that runs before the later files are read (a poll started at the top of a file, an answer handled while the
// rest still loads) throws "X is not defined": census 5b47665e loadTvset, 6ca924d6 stopped, 91edf904 gamePad.
//
//   node dev/test/slowload.js [--delay=250 ms a script] [--wait=8 s after the last one]
//
// No TV and no server, as audit.js: the working tree's page, /api answered by the demo's made-up TV (docs/demo/mock.js) with
// TV Games on the screen. Each core (pc, tv, phone, the TV's own Manage) and each start tab. Exit 1 on any script error.
const fs = require('fs'), path = require('path');
let pw;
try { pw = require(path.join(__dirname, '..', 'node_modules', 'playwright')); } catch { try { pw = require('playwright'); } catch {
  console.error('needs Playwright: npm install --prefix dev playwright'); process.exit(2); } }

const arg = (k, d) => { const a = process.argv.find((x) => x.startsWith('--' + k + '=')); return a ? a.split('=')[1] : d; };
const ROOT = path.join(__dirname, '..', '..'), WEB = path.join(ROOT, 'app/shared/web'), DOCS = path.join(ROOT, 'docs');
const HOST = 'http://slow.test', DELAY = +arg('delay', 250), WAIT = +arg('wait', 8) * 1000;
const TYPES = { html: 'text/html; charset=utf-8', js: 'text/javascript', css: 'text/css', json: 'application/json', png: 'image/png',
  webp: 'image/webp', jpg: 'image/jpeg', svg: 'image/svg+xml', woff2: 'font/woff2', sig: 'text/plain' };
const RUNS = [['pc', 'remote'], ['pc', 'apps'], ['pc', 'tv'], ['tv', 'remote'], ['tv', 'health'], ['phone', 'apps'], ['phone', 'auto'], ['ontv', 'apps']];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function serve(host, core) {
  let queue = Promise.resolve();                                     // scripts one after another, as a slow link hands them over
  return async (route) => {
    const u = new URL(route.request().url());
    const file = (f) => fs.existsSync(f) && fs.statSync(f).isFile() ? route.fulfill({ body: fs.readFileSync(f), contentType: TYPES[f.split('.').pop()] || 'application/octet-stream' }) : route.fulfill({ status: 404, body: 'not found' });
    if (u.origin !== HOST) return route.abort();
    if (u.pathname.startsWith('/api/')) {
      const r = await host().evaluate((q) => window.demoApi(q), { method: route.request().method(), path: u.pathname, search: u.search, body: route.request().postData() || '' })
        .catch((e) => ({ status: 500, type: 'application/json', body: JSON.stringify({ error: String(e) }) }));
      let body = r.body;
      if (u.pathname === '/api/state') try { body = JSON.stringify({ ...JSON.parse(body), app: 'dev.tvremote.games' }); } catch {}
      return route.fulfill({ status: r.status, contentType: r.type, body });
    }
    if (u.pathname === '/demo/host.html') return route.fulfill({ contentType: TYPES.html, body: '<!doctype html><script src="mock.js"></script>' });
    if (u.pathname === '/app/' || u.pathname === '/app/index.html') {
      const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8').replace('<html ', `<html data-core="${core === 'ontv' ? 'tv' : core}" `);
      return route.fulfill({ contentType: TYPES.html, body: html });
    }
    if (u.pathname.endsWith('.js') && u.pathname.startsWith('/app/')) { const turn = queue.then(() => sleep(DELAY)); queue = turn; await turn; }
    if (u.pathname.startsWith('/app/i18n/')) return file(path.join(WEB, '../i18n', decodeURIComponent(u.pathname.slice(10))));
    if (u.pathname.startsWith('/app/')) return file(path.join(WEB, decodeURIComponent(u.pathname.slice(5))));
    return file(path.join(DOCS, decodeURIComponent(u.pathname)));
  };
}

(async () => {
  const browser = await pw.chromium.launch({ channel: 'chrome' }).catch(() => pw.chromium.launch());   // the installed Chrome
  const faults = [];
  for (const [core, tab] of RUNS) {
    const ctx = await browser.newContext({ viewport: { width: 1100, height: 900 } });
    let hostPage = null;
    await ctx.route('**/*', serve(() => hostPage, core));
    hostPage = await ctx.newPage();
    await hostPage.goto(HOST + '/demo/host.html');
    await hostPage.waitForFunction(() => typeof window.demoApi === 'function');
    await ctx.addInitScript(({ core }) => {
      if (location.pathname.startsWith('/demo/')) return;
      window.open = () => null;
    }, { core });
    const page = await ctx.newPage();
    const errors = new Set();
    page.on('pageerror', (e) => errors.add(String(e.message || e).slice(0, 160) + ' · ' + (String(e.stack || '').split('\n').find((l) => l.includes('/app/')) || '').trim().replace(HOST, '')));
    page.on('dialog', (d) => d.dismiss().catch(() => {}));
    const t0 = Date.now();
    await page.goto(HOST + '/app/?tab=' + tab + (core === 'ontv' ? '&ontv=1' : ''), { waitUntil: 'load', timeout: 120000 });
    const loaded = Date.now() - t0;
    await sleep(WAIT);
    const shown = await page.evaluate(() => { const b = document.querySelector('.errbox'); return b && !b.hidden ? b.textContent.slice(0, 160) : ''; }).catch(() => '');
    if (shown && ![...errors].some((e) => shown.includes(e.split(' · ')[0]))) errors.add('error box: ' + shown);
    console.log((errors.size ? 'WRONG ' : 'ok    ') + core.padEnd(6) + tab.padEnd(8) + 'loaded in ' + loaded + ' ms' + [...errors].map((e) => '\n      - ' + e).join(''));
    for (const e of errors) faults.push(`${core} ${tab}: ${e}`);
    await ctx.close();
  }
  await browser.close();
  console.log(faults.length ? `WRONG (${faults.length})` : 'OK: no script error while the page loads slowly');
  process.exit(faults.length ? 1 : 0);
})();
