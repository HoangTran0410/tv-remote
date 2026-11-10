// 🔍 Find a feature (issue #29: "a lot of functionality, difficult to find"): the cards of every tab and the sections of
// Settings, read from the page itself (their titles are already in the page's language and only what this core and this TV
// show is listed), plus the user guide's words (js/find_topics.js) so "sleep" finds "Bedtime" too. A pick opens the tab and
// lights the card (goTo); a guide topic with no place on this page opens the guide.

// guide topic → where it is on the page: 'tab' (the tab), 'tab#id' (a card), '@id' (a Settings section). Kept true by find.test.js.
const FIND_AT = {
  remote: 'remote', typing: 'remote', clipboard: 'remote', voice: 'remote', picture: 'remote#screencard', quality: 'remote#screencard',
  listen: 'remote', screenshot: 'remote#screencard', notify: 'remote', draw: 'remote', photo: 'remote', video: 'remote', 'screen-dim': 'remote',
  apps: 'apps', tvapps: 'apps#tvappscard', files: 'files#filescard', health: 'health', privacy: 'health',
  optimize: 'tv#optcard', launchers: 'tv#launchcard', home: 'tv#launchcard', 'home-widgets': 'tv#launchcard', 'home-qr': 'tv#launchcard',
  'home-tiles': 'tv#launchcard', 'home-add': 'tv#launchcard', 'home-folders': 'tv#launchcard', 'home-pin': 'tv#launchcard',
  'home-themes': 'tv#launchcard', 'home-care': 'tv#launchcard', 'home-control': 'tv#launchcard', 'home-app-menu': 'tv#launchcard',
  'home-quickkeys': 'tv#launchcard', keymap: 'tv#keyscard', keyfix: 'tv#keyscard', channels: 'tv#chancard', games: 'tv#gamecard',
  away: 'tv#tscard', tvguard: 'tv#tvsetcard', awake: 'tv#tvsetcard', sleep: 'auto#sleepcard', bedtime: 'auto#bedcard',
  welcome: 'auto#welcomecard', limits: 'auto#limitcard', family: 'auto#kidscard', lock: 'auto#lockcard', schedule: 'auto#schedcard',
  scenes: 'auto#scenecard', telegram: '@tggroup', 'telegram-more': '@tggroup', hook: '@hookgroup', census: '@censusgroup',
};
const FIND_TABS = ['remote', 'apps', 'files', 'health', 'auto', 'tv'];

// What can be found right now: [{ title, where, hay (folded words), go }]
function findItems() {
  const shown = (e) => { for (let x = e; x && !x.matches('[data-panel], dialog'); x = x.parentElement) if (x.hidden || getComputedStyle(x).display === 'none') return false; return true; };
  const title = (h) => { const c = h.cloneNode(true); c.querySelectorAll('.needs, .muted, small, .ui-ic, svg, .sectstate').forEach((x) => x.remove()); return c.textContent.replace(/\s+/g, ' ').trim().replace(/\s*[·:]$/, ''); };   // "First-time setup ·" had its state after it
  const tabName = (t) => { const b = document.querySelector('nav.tabs [data-tab="' + t + '"]'); return b ? title(b.querySelector('.lbl:not([hidden])') || b) : ''; };
  const tabOk = (t) => { const b = document.querySelector('nav.tabs [data-tab="' + t + '"]'); return !!b && !b.hidden && getComputedStyle(b).display !== 'none'; };
  const first = (e, sels) => { for (const x of sels) { const f = e.querySelector(x); if (f) return f; } return null; };   // in this order, not the page's
  const items = [], byKey = {};
  for (const t of FIND_TABS) {
    if (!tabOk(t)) continue;
    const name = tabName(t);
    byKey[t] = items[items.push({ title: name, where: '', words: [], go: () => goTo(t) }) - 1];
    document.querySelectorAll('[data-panel="' + t + '"] section.card, [data-panel="' + t + '"] details.card').forEach((c) => {
      if (!shown(c)) return;
      const h = first(c, [':scope > h2', ':scope > .card-head h2', ':scope > summary h2', ':scope > summary', 'h2', 'h3']);
      const words = h ? title(h) : c.getAttribute('aria-label') || '';
      if (!words) { if (c.id) byKey[t + '#' + c.id] = byKey[t]; return; }   // a card with no title (Files' list): its tab
      const it = { title: words, where: name, words: [], go: () => goTo(t, c) };
      items.push(it); if (c.id) byKey[t + '#' + c.id] = it;
    });
  }
  const about = $('#about'), aboutBtn = $('#aboutbtn');
  if (about && aboutBtn && getComputedStyle(aboutBtn).display !== 'none') {
    const where = title(aboutBtn.querySelector('.lbl') || aboutBtn);
    // a section's hidden state is set when Settings opens (Telegram: the card, or the hint to install TV Setup, same title):
    // listed whatever it is now, once per title; the jump takes the one shown then
    const seen = new Set();
    about.querySelectorAll('details.sect').forEach((s) => {
      if (getComputedStyle(s).display === 'none' && !s.hidden) return;    // not on this core (data-for)
      const h = first(s, [':scope > summary h3', ':scope > summary']); if (!h) return;
      const key = h.dataset.i18n || s.id; if (seen.has(key)) return;
      const twins = () => [...about.querySelectorAll('details.sect')].filter((x) => (first(x, [':scope > summary h3', ':scope > summary'])?.dataset.i18n || x.id) === key);
      const it = { title: title(h), where, words: [], go: () => {
        aboutBtn.click();
        let n = 0; const open = () => { const x = twins().find((t) => !t.hidden);
          if (!x) return ++n < 10 && setTimeout(open, 200);              // the section waits for its state (/api/telegram)
          x.open = true; x.scrollIntoView({ block: 'center', behavior: 'smooth' }); };
        setTimeout(open, 300); } };
      seen.add(key); items.push(it); twins().forEach((t) => { byKey['@' + t.id] = it; });
    });
  }
  const guide = T('find.guide');
  for (const [id, vi, en, kw] of FIND_TOPICS) {
    const to = FIND_AT[id] || '', at = byKey[to] || (to[0] !== '@' && byKey[to.split('#')[0]]);   // its card hidden now (no ADB yet): its tab
    if (at) { at.words.push(vi, en, kw); continue; }
    if (window.ONTV) continue;                                       // TV Setup's screen opens no web pages
    items.push({ title: (LANG === 'vi' ? vi : en), guide: true, where: guide, words: [vi, en, kw],
      go: () => window.open('https://tvremote.pages.dev/' + (LANG === 'vi' ? '' : 'en/') + 'guide#' + id, '_blank') });
  }
  items.forEach((it) => { it.hay = findFold([it.title, it.where, ...it.words].join(' ')); it.head = findFold(it.title); });
  return items;
}

const findFold = (t) => foldText(t).replace(/[-‐'’.]/g, '');      // "wifi" finds Wi-Fi

// best first: every word found; the title over the guide's words, its start over its middle
function findRank(items, q) {
  const ws = findFold(q).split(/\s+/).filter(Boolean);
  if (!ws.length) return items.filter((it) => !it.guide);          // nothing typed: this page's own places, the guide only once asked
  const score = (it) => ws.every((w) => it.hay.includes(w)) ? ws.reduce((a, w) => a + (it.head.startsWith(w) ? 4 : it.head.includes(w) ? 2 : 0), 0) + (it.guide ? 0 : 1) : -1;
  return items.map((it) => [score(it), it]).filter(([s]) => s >= 0).sort((a, b) => b[0] - a[0]).map(([, it]) => it);
}

function findOpen() {
  const items = findItems();
  const q = el('input', { type: 'search', placeholder: T('find.placeholder'), spellcheck: false, autocomplete: 'off', 'aria-label': T('find.title') });
  const list = el('div', { className: 'list findrows' });
  const none = el('p', { className: 'empty', textContent: T('find.none'), hidden: true });
  const d = el('dialog', { className: 'ask finddlg' }, el('div', { className: 'card' },
    UI.head('🔍 ' + T('find.title'), el('button', { type: 'button', className: 'small iconbtn', textContent: '✕', title: T('common.close'),
      'aria-label': T('common.close'), onclick: () => d.close() })), q, list, none));
  let rows = [];
  const draw = () => {
    rows = findRank(items, q.value).map((it) => el('button', { type: 'button', className: 'item pick', onclick: () => { d.close(); it.go(); } },
      el('div', { className: 'name' }, el('span', { textContent: it.title }), it.where ? el('small', { className: 'muted', textContent: it.where }) : null)));
    list.replaceChildren(...rows); none.hidden = rows.length > 0;
  };
  q.addEventListener('input', draw);
  q.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); rows[0]?.click(); } });
  d.addEventListener('close', () => d.remove());
  draw();
  document.body.append(d); d.showModal();
  q.focus();                                                         // here typing is the point: the list is short once a word is in
}
$('#findbtn')?.addEventListener('click', findOpen);
