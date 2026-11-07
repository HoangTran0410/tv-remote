// The page's last line of defence: an error nobody caught (a script bug, a rejected promise nobody awaited) shows a box with the
// message and stack and a Copy button, so what went wrong is on the screen to read or send, not only in a console nobody has open.
// Loaded first (before i18n.js), so it also sees a failure of the scripts after it; the words are asked when the box is drawn.
// The Android apps have the same thing for the native side (app/shared/src/guard); tests/errbox.test.js runs this file.
(() => {
  const word = (id) => (typeof T === 'function' ? T(id) : id);
  const LINES = 6;                                                    // of the stack on screen; Copy has all of it
  let box = null, repeats = 0, shown = '', title, body, more, copyBtn, full = '', sentLine = null;
  const reported = new Set();                                         // errors already handed to the core in this page's life

  /** What Copy puts on the clipboard: when, where and the whole stack. */
  const report = (msg, stack) => [new Date().toISOString(), (typeof location !== 'undefined' ? location.href : ''),
    (typeof navigator !== 'undefined' ? navigator.userAgent : ''), '', msg, stack || ''].join('\n');

  /** Resource errors (img/script onerror), the cross-origin "Script error." (no message, no line: nothing to read) and the
   *  ResizeObserver notice (harmless, every browser sends it) are not worth a box. Neither is a stack whose every frame is the
   *  page itself or an evaluated string: index.html has no script of its own and js/ evaluates none, so that is one a browser or an add-on put in (census 52ad8714). */
  const injected = (stack) => {
    const doc = typeof location !== 'undefined' ? String(location.href).split('#')[0] : '';
    const files = String(stack || '').split('\n').map((l) => (/(\S+?):\d+:\d+\)?\s*$/.exec(l.replace(/^.*?[@(]/, '')) || [])[1]).filter(Boolean);
    return !!doc && files.length > 0 && files.every((f) => f === doc || f === '<anonymous>');   // <anonymous>: evaluated by the app hosting the page (Zalo's zaloJSV2, census 11e6a1d8)
  };
  const ignored = (e) => {
    if (e.target && e.target !== window && e.target.nodeType) return true;       // an element failed to load: its own onerror handles it
    if (injected(e.error && e.error.stack)) return true;
    const m = String(e.message || '');
    if (m === 'Script error.' && !e.error && !e.lineno) return true;
    return /^ResizeObserver loop/.test(m);
  };

  const copy = () => {
    const done = () => { copyBtn.textContent = word('err.copied'); };
    try { navigator.clipboard.writeText(full).then(done, fallback); } catch { fallback(); }
    function fallback() {                                              // http on the LAN has no navigator.clipboard
      try { const t = document.createElement('textarea'); t.value = full; document.body.append(t); t.select(); document.execCommand('copy'); t.remove(); done(); } catch {}
    }
  };

  const close = () => { if (box) box.remove(); box = null; repeats = 0; shown = ''; sentLine = null; };

  /** Hand the error to the core (POST /api/crash), which sends it with the anonymous-stats switch; the box says "sent" if it was.
   *  Scrubbed here as well (scrub.js), so an address or a folder name never even crosses the network to the core. Fire and forget. */
  function sendCrash(key, msg, stack, kind) {
    if (reported.has(key) || typeof fetch !== 'function' || typeof Scrub === 'undefined') return;
    reported.add(key);
    const scrub = (t, n) => Scrub.text(t).slice(0, n);
    try {
      fetch('/api/crash', { method: 'POST', headers: { 'Content-Type': 'application/json' }, keepalive: true,
        body: JSON.stringify({ kind: scrub(kind || 'Error', 120), msg: scrub(msg, 300), stack: scrub(stack || '', 8192) }) })
        .then((r) => (r.ok ? r.json() : null)).then((j) => { if (j && j.sent && box && shown === key) markSent(); }, () => {});
    } catch {}
  }

  function markSent() {
    if (sentLine) return;
    sentLine = document.createElement('div');
    sentLine.className = 'errbox-sent';
    sentLine.textContent = word('err.sent');
    box.insertBefore ? box.insertBefore(sentLine, box.children[box.children.length - 1]) : box.append(sentLine);
  }

  function show(msg, stack, kind, local) {
    msg = String(msg || '').slice(0, 500);
    full = report(msg, stack);
    const key = msg + '\n' + String(stack || '').split('\n')[0];
    if (box && key === shown) {                                       // the same error again: one box, counted
      repeats++;
      more.textContent = '×' + (repeats + 1);
      return;
    }
    if (!box) {
      box = document.createElement('div');
      box.className = 'errbox';
      box.setAttribute('role', 'alert');
      title = document.createElement('b');
      more = document.createElement('span');
      body = document.createElement('pre');
      const row = document.createElement('div');
      copyBtn = document.createElement('button');
      const closeBtn = document.createElement('button');
      copyBtn.addEventListener('click', copy);
      closeBtn.addEventListener('click', close);
      closeBtn.textContent = word('err.close');
      row.className = 'errbox-row';
      row.append(copyBtn, closeBtn);
      box.append(title, more, body, row);
      document.body.append(box);
      try { if (/[?&]ontv=1/.test(location.search)) closeBtn.focus(); } catch {}   // the TV's D-pad needs a focus to start from
    }
    repeats = 0; shown = key; sentLine = null;
    if (!local) sendCrash(key, msg, stack, kind);
    title.textContent = word('err.title');
    more.textContent = '';
    copyBtn.textContent = word('err.copy');
    body.textContent = [msg].concat(String(stack || '').split('\n').filter((l) => l.trim()).slice(0, LINES)).join('\n');
  }

  /** One of the page's own scripts did not load (a weak TV dropped the request): every script after it then fails with "X is not
   *  defined" (census 34503a65), which is no fault of the code. Load the page again once; if it fails again within a minute, say so. */
  let missing = '';
  const RELOADED = 'tvr-reloaded';
  function scriptMissing(src) {
    if (missing) return;
    missing = src;
    let again = false;
    try { again = Date.now() - Number(sessionStorage.getItem(RELOADED) || 0) < 60000; sessionStorage.setItem(RELOADED, String(Date.now())); } catch {}
    if (!again) { try { location.reload(); return; } catch {} }
    show(word('err.partial'), src, 'Load', true);
  }

  window.addEventListener('error', (e) => {                           // capture: a script element's failure does not bubble
    const t = e.target;
    if (t && t !== window && t.tagName === 'SCRIPT' && t.src && t.src.indexOf('/js/') >= 0) return scriptMissing(t.src);
    if (missing && e.error && e.error.name === 'ReferenceError') return;   // what the missing script would have defined
    if (ignored(e)) return;
    show(e.message || (e.error && e.error.message) || 'Error', e.error && e.error.stack, e.error && e.error.name);
  }, true);
  window.addEventListener('unhandledrejection', (e) => {
    const r = e.reason;
    if (injected(r && r.stack)) return;
    show((r && r.message) || String(r), r && r.stack, r && r.name);
  });
  window.ErrBox = { show, close, ignored };
})();
