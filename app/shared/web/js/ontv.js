// ---------- the page on the TV itself (TV Setup → Quản lý trên TV, ManageActivity): driven by the TV's remote ----------
// No mouse and no touch: the D-pad moves the focus to the nearest control in that direction, OK presses it, Back (tvBack,
// called by the app) closes what is open first. Elsewhere this file does nothing.
if (window.ONTV) (() => {
  const SEL = 'button, a[href], input, select, textarea, summary, [tabindex]:not([tabindex="-1"])';
  const DIR = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] };
  const seen = (e) => { if (e.disabled || e.closest('[hidden], [inert]')) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden'; };
  // an open "..." menu (popover() in core.js) keeps the focus in it, over a dialog too; else an open dialog does
  const scope = () => (typeof popEl !== 'undefined' && popEl && popEl.isConnected && popEl) || [...document.querySelectorAll('dialog[open]')].pop() || document;
  // a field drawn as something else (a file picker inside a label that looks like a button) is reached through its label
  const stand = (e) => {
    if (seen(e) || !e.matches('input, select, textarea')) return e;
    const l = e.closest('label') || (e.id && document.querySelector(`label[for="${e.id}"]`));
    if (l && l.tabIndex < 0) l.tabIndex = 0;                         // a label takes no focus by itself
    return l;
  };
  const items = () => [...new Set([...scope().querySelectorAll(SEL)].map(stand))].filter((e) => e && seen(e) && !(e.closest('details:not([open])') && e.tagName !== 'SUMMARY' && !e.closest('summary')));
  // what stays on screen while the page scrolls (the tabs, once stuck) is placed where it sits in the page, between what is
  // above and below it: so the row above, scrolled out of sight under it, still comes first going up
  let pins = new Map();                                              // per key press: the same parents are asked again and again
  const shift = (e) => {
    if (!e || e === document.body) return 0;
    if (!pins.has(e)) {
      const c = getComputedStyle(e);
      let d = 0;
      if (c.position === 'sticky' && e.getBoundingClientRect().top <= parseFloat(c.top) + 1) {   // stuck: how far it is from its place
        const was = e.getBoundingClientRect().top, keep = e.style.position;
        e.style.position = 'static'; d = e.getBoundingClientRect().top - was; e.style.position = keep;
      }
      pins.set(e, d + shift(e.parentElement));
    }
    return pins.get(e);
  };
  const start = () => { col = null; const s = scope(); return (s === document && document.querySelector('[data-tab][aria-selected="true"]')) || items()[0]; };
  // a list drawn again (a choice made in it, a refresh) takes the focused control away: the focus goes back to the control
  // drawn in its place (same words, nearest to where it was), not to the tabs at the top
  let last = null;
  document.addEventListener('focusin', (ev) => { const e = ev.target, r = e.getBoundingClientRect && e.getBoundingClientRect(); if (r && e !== document.body) last = { e, text: e.textContent.trim(), x: r.left + r.width / 2 + scrollX, y: r.top + r.height / 2 + scrollY }; }, true);
  const back = () => {
    if (!last || last.e.isConnected) return null;
    let best = null, score = Infinity;
    for (const e of items()) {
      const r = e.getBoundingClientRect(), s = Math.hypot(r.left + r.width / 2 + scrollX - last.x, r.top + r.height / 2 + scrollY - last.y) + (e.textContent.trim() === last.text ? 0 : 200);
      if (s < score) { score = s; best = e; }
    }
    return score < 400 ? best : null;
  };
  // a text field the focus passes over keeps the TV's keyboard down (it would take the arrows); OK opens it (keydown below)
  const TEXT = 'textarea, input:not([type]), input[type=text], input[type=search], input[type=number], input[type=password], input[type=url], input[type=email], input[type=tel]';
  const quiet = (e) => { if (e.matches(TEXT) && !e.dataset.im) { e.dataset.im = e.inputMode || '-'; e.inputMode = 'none'; } };
  document.addEventListener('focusout', (ev) => { const e = ev.target; if (e.dataset && e.dataset.im) { e.inputMode = e.dataset.im === '-' ? '' : e.dataset.im; delete e.dataset.im; } }, true);
  // what had the focus before a dialog opened: closing it goes back there (Settings, a row's "..." menu), not to the tabs
  let opener = null;
  document.addEventListener('focusin', (ev) => { if (ev.target !== document.body && !ev.target.closest('dialog, [role=menu]')) opener = ev.target; }, true);
  const home = () => (opener && opener.isConnected && seen(opener) && scope().contains(opener)) ? opener : start();
  const go = (e) => { if (!e) return false; quiet(e); e.focus({ preventScroll: true }); e.scrollIntoView({ block: 'center', inline: 'nearest' }); return true; };   // the middle, as the TV apps (TvScroll): at the edge nobody sees more waits below

  // Left / right: the nearest control on the same row, or none (the focus stays). Up / down: the next row that way, and on it
  // the control under the column the focus keeps since the last move sideways (a wide search box does not throw it off).
  // the card a control sits in, and whether two cards share a column (the tabs lay cards out in 2 or 3 columns, js-free)
  const lane = (e) => e.closest('.card');
  const sameLane = (e, f) => {                                       // a control in no card (the page's foot) is in none
    if (!lane(e) || !lane(f)) return false;
    const p = lane(e).getBoundingClientRect(), q = lane(f).getBoundingClientRect();
    return Math.min(p.right, q.right) - Math.max(p.left, q.left) > Math.min(p.width, q.width) / 2;
  };
  let col = null;
  function next(from, [dx, dy]) {
    const a = from.getBoundingClientRect();
    pins = new Map();
    const base = shift(from), at = (e) => { const r = e.getBoundingClientRect(), d = shift(e) - base; return d ? new DOMRect(r.x, r.y + d, r.width, r.height) : r; };
    const all = items().filter((e) => e !== from && !e.contains(from)).map((e) => [e, at(e)]);
    if (dx) {
      let best = null, score = Infinity;
      for (const [e, b] of all) {
        const edge = dx > 0 ? b.left - a.right : a.left - b.right, over = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (edge < -a.width / 2 || (b.left + b.right - a.left - a.right) * dx <= 0 || over < Math.min(a.height, b.height) * 0.3) continue;
        const s = Math.max(0, edge) + Math.abs(b.top + b.bottom - a.top - a.bottom) / 8;
        if (s < score) { score = s; best = e; }
      }
      if (!best) for (const [e, b] of all) {                         // nothing level with it: the nearest in the next column of cards
        const edge = dx > 0 ? b.left - a.right : a.left - b.right;
        if (edge < 0 || !lane(e) || lane(e) === lane(from)) continue;
        const s = edge + Math.max(0, b.top - a.bottom, a.top - b.bottom) * 2;
        if (s < score) { score = s; best = e; }
      }
      if (best) { const b = best.getBoundingClientRect(); col = (b.left + b.right) / 2; }
      return best;
    }
    let ahead = all.filter(([, b]) => (dy > 0 ? b.top >= a.top + a.height / 2 : b.bottom <= a.bottom - a.height / 2))
      .sort((p, q) => dy > 0 ? p[1].top - q[1].top : q[1].bottom - p[1].bottom);
    const own = ahead.filter(([e]) => sameLane(e, from));               // cards in columns: down the same column first
    if (own.length && !ahead[0]?.[0].closest('.tabs')) ahead = own;     // ...unless the tab bar is the next row: it is in no card, and skipped it went to the header's buttons
    if (!ahead.length) return null;
    const near = ahead[0][1], row = ahead.filter(([, b]) => dy > 0 ? b.top < near.bottom - 2 : b.bottom > near.top + 2);   // the row: what overlaps the nearest one
    const x = Math.min(Math.max(col ?? a.left + 1, a.left), a.right);
    let best = null, score = Infinity;
    for (const [e, b] of row) {
      const s = (b.left > x ? b.left - x : b.right < x ? x - b.right : 0) * 4 + Math.abs((dy > 0 ? b.top : b.bottom) - (dy > 0 ? near.top : near.bottom));
      if (s < score) { score = s; best = e; }
    }
    // into a row of tabs from outside: the open one, not whichever stands above the column
    const tabs = best && !from.closest('[role=tablist], .tabs') && best.closest('[role=tablist], .tabs');
    return (tabs && tabs.querySelector('[aria-selected="true"]')) || best;
  }

  document.addEventListener('keydown', (ev) => {
    const d = DIR[ev.key]; let cur = document.activeElement;
    if (!d) {
      // OK on what Enter does not press by itself (a checkbox, a radio, a label)
      if (ev.key === 'Enter' && cur && cur.matches('input[type=checkbox], input[type=radio], label')) { ev.preventDefault(); cur.click(); }
      else if (ev.key === 'Enter' && cur && cur.matches(TEXT) && cur.inputMode === 'none') {   // OK on a field: now the keyboard
        ev.preventDefault(); cur.inputMode = cur.dataset.im === '-' ? '' : cur.dataset.im; delete cur.dataset.im; cur.blur(); cur.focus();
      }
      // Done / Enter in a field (the keyboard's or a phone's): the page acts on it first, then the keyboard goes and the focus
      // steps out, to the button beside it (Use, Set) or below; the keyboard up kept the arrows and the person was stuck in it
      else if (ev.key === 'Enter' && cur && cur.matches(TEXT)) {
        const f = cur;
        setTimeout(() => { if (document.activeElement === f) { if (!go(next(f, [1, 0]) || next(f, [0, 1]))) { quiet(f); f.blur(); f.focus(); } } }, 0);
      }
      return;
    }
    const typing = cur && (cur.matches('textarea') || (cur.matches('input') && !cur.matches('[type=checkbox], [type=radio], [type=range], [type=button], [type=submit], [type=file]')));
    if (typing && d[0]) { const v = cur.value || '', at = cur.selectionStart; if (at != null && ((d[0] < 0 && at > 0) || (d[0] > 0 && at < v.length))) return; }   // the caret moves inside the words first
    if (cur && cur.matches('input[type=range]') && d[0]) return;    // a slider takes left / right
    ev.preventDefault();
    if (!cur || cur === document.body || !seen(cur) || !scope().contains(cur)) { const e = back(); if (!e) { go(start()); return; } go(e); cur = e; }
    if (!go(next(cur, d)) && d[1]) window.scrollBy({ top: d[1] * innerHeight * 0.4, behavior: 'smooth' });   // nothing that way: a long text still scrolls
  }, true);

  // Back: a dialog closes (as Esc would), then the focus goes back to the tabs; at the tabs the app leaves the page
  window.tvBack = () => {
    if (typeof popEl !== 'undefined' && popEl && popEl.isConnected) { const a = popEl.anchor; closePop(); go(a); return true; }   // the "..." menu: closed, the focus back on its button
    const d = [...document.querySelectorAll('dialog[open]')].pop();
    if (d) { if (d.dispatchEvent(new Event('cancel', { cancelable: true }))) d.close(); setTimeout(() => go(home()), 50); return true; }
    if (!document.activeElement?.closest('.tabs')) return go(start());
    return false;
  };
  // a dialog that opens takes the focus to its first control
  new MutationObserver(() => {
    const d = [...document.querySelectorAll('dialog[open]')].pop();
    if (d && !d.contains(document.activeElement)) setTimeout(() => { if (d.open && !d.contains(document.activeElement)) go(items()[0]); }, 60);
    else if (!d && document.activeElement === document.body) { const e = back() || (opener && home()); if (e) { quiet(e); e.focus({ preventScroll: true }); } }   // drawn again: keep the ring on screen
  })
    .observe(document.body, { subtree: true, attributes: true, attributeFilter: ['open'], childList: true });
  addEventListener('load', () => setTimeout(() => go(start()), 300));
})();
