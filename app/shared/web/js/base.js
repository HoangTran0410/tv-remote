// The few helpers every script of the page leans on, shared by the whole page (index.html) and TV Home's own page (home.html).
const $ = (s) => document.querySelector(s);
// No server answer is handled before every script of the page is read. A slow TV hands the files over one by one while the
// first /api/state is back at once: code run from it then met names of later files ("gamePad / loadTvset is not defined",
// census 91edf904, 5b47665e). The request still leaves at once; only its answer waits. dev/test/slowload.js loads it so.
const pageRead = new Promise((done) => document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', done, { once: true }) : done());
if (window.fetch) { const fetchNow = window.fetch; window.fetch = (...a) => fetchNow(...a).then(async (r) => { await pageRead; return r; }, async (e) => { await pageRead; throw e; }); }
const el = (tag, props = {}, ...kids) => { const e = Object.assign(document.createElement(tag), props); kids.forEach((k) => k != null && e.append(k)); return e; };
// over a modal dialog too (TV Home's settings said nothing under it): a popover shown again goes to the top of the top layer;
// a WebView without popovers (an older TV's) gets it inside the open dialog, which is in the top layer itself
const toastEl = $('#toast');                                         // kept: a dialog that removes itself takes it along
function toTop(t) {
  if (t.showPopover) { if (t.classList.contains('up')) t.hidePopover(); t.showPopover(); t.classList.add('up'); return; }
  const d = [...document.querySelectorAll('dialog[open]')].pop() || document.body;
  if (t.parentNode !== d) d.append(t);
}
let toastT;
function toast(m) { const t = toastEl; t.textContent = m; t.classList.add('show'); toTop(t); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('show'), Math.min(10000, Math.max(2500, 70 * m.length))); }   // time to read it: a long message with the next step stays longer
// Every write to the page that may repeat goes through here (tests/page.test.js): only when it changes. The same value
// written again is still a change, for the page's observers (icons, running lines, i18n) and WebKit's styles: the now
// playing bar did it 5 times a second, a running line wrapped itself 20 times a second.
function put(e, k, v) {
  if (typeof e === 'string') e = $(e);
  if (e && e[k] !== v) e[k] = v;
}
