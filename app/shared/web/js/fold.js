// Long tabs (Automation, Setup) read as a list: each card with an id folds to its title and one line of what is on (the user,
// 2026-11-02: eleven cards in a row were too much to take in). A card that is on starts open; a tap on a title opens or folds it,
// and that choice is kept in this browser only (a convenience, never needed). A renderer says what is on with foldSum(id, words, on);
// off, or without one: its p.state, else what it is for (FOLD_WHAT), else its p.note (one line). Nothing runs until a tab with cards is drawn: one MutationObserver per panel, no timer.

const FOLD_PANELS = '[data-panel=auto], [data-panel=tv]';
const FOLD_CARD = 'section.card[id]:not([data-nofold])';
const FOLD_WHAT = {                                                    // what a card is for, shown while it has nothing on ("Not set" says too little)
  sleepcard: 'fold.what_sleep', bedcard: 'fold.what_bed', limitcard: 'fold.what_limit', lockcard: 'fold.what_lock', kidscard: 'fold.what_kids',
  totalcard: 'fold.what_total', schedcard: 'fold.what_sched', volcard: 'fold.what_vol', ramcard: 'fold.what_ram', idlecard: 'fold.what_idle', welcomecard: 'fold.what_welcome',
  scenecard: 'fold.what_scenes', tvsetcard: 'fold.what_tvset', optcard: 'fold.what_opt', keyscard: 'fold.what_keys', tvcopycard: 'fold.what_tvcopy', backupcard: 'fold.what_backup',
};
let foldSums = {};                                                    // card id -> { text, on } for the TV in use
const foldMine = (() => { try { return JSON.parse(localStorage.getItem('fold') || '{}') || {}; } catch (e) { return {}; } })();   // card id -> open, the viewer's own choice

/** What a card has on now, shown under its title while folded; `on` opens it unless the viewer folded it. */
function foldSum(id, text, on) {
  const o = foldSums[id];
  if (o && o.text === (text || '') && o.on === !!on) return;
  foldSums[id] = { text: text || '', on: !!on }; foldMark();
}
const foldHead = (card) => card.querySelector(':scope > h2, :scope > .card-head > h2');
function foldMark() {
  for (const card of document.querySelectorAll(FOLD_PANELS.split(', ').map((p) => p + ' ' + FOLD_CARD).join(', '))) {   // no :is(): Chrome 88+, a MiTV's WebView threw (census 328e5f39)
    const h = foldHead(card); if (!h) continue;
    const s = foldSums[card.id], st = card.querySelector('p.state:not([hidden])'), note = card.querySelector(':scope > p.note');
    const open = card.id in foldMine ? foldMine[card.id] : !!(s && s.on);
    if (card.classList.contains('folded') === open) card.classList.toggle('folded', !open);
    if (!card.classList.contains('foldable')) card.classList.add('foldable');
    if (h.tabIndex !== 0) h.tabIndex = 0;
    if (h.getAttribute('role') !== 'button') h.setAttribute('role', 'button');
    if (h.getAttribute('aria-expanded') !== String(open)) h.setAttribute('aria-expanded', String(open));
    const sum = (s && s.on && s.text) || (st && st.textContent.trim()) || (FOLD_WHAT[card.id] && T(FOLD_WHAT[card.id]))
      || (s && s.text) || (note && note.textContent.trim()) || '';
    if (h.dataset.sum !== sum) h.dataset.sum = sum;
  }
}
function foldToggle(card) {
  foldMine[card.id] = card.classList.contains('folded');
  try { localStorage.setItem('fold', JSON.stringify(foldMine)); } catch (e) { }
  foldMark();
}
if (typeof document !== 'undefined' && typeof MutationObserver !== 'undefined') {
  let queued = false;                                                // one pass per batch of changes, after the renderers ran
  const later = () => { if (!queued) { queued = true; queueMicrotask(() => { queued = false; foldMark(); }); } };
  document.querySelectorAll(FOLD_PANELS).forEach((p) => new MutationObserver(later).observe(p, { childList: true, subtree: true, characterData: true }));
  const hit = (e) => { const h = e.target.closest('h2[aria-expanded]'); const c = h && h.closest('.foldable'); return c && foldHead(c) === h ? c : null; };
  document.addEventListener('click', (e) => { if (e.target.closest('a, button, input, select')) return; const c = hit(e); if (c) foldToggle(c); });
  document.addEventListener('keydown', (e) => { if (e.key !== 'Enter' && e.key !== ' ') return; const c = hit(e); if (c) { e.preventDefault(); foldToggle(c); } });
  onTvChange(() => { foldSums = {}; });
  later();
}
if (typeof module !== 'undefined') module.exports = { foldSum, foldMark };
