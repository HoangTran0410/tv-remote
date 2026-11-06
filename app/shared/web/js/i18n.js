// The page's words, in the language the viewer picked. Each is an id in i18n/<lang>.json: vi.json is the source and lists every
// one, en.json and any other language the same ids (missing ones fall back to English, then Vietnamese). In index.html an
// element names its id (data-i18n, data-i18n-html for words with <b> or <a> in them, data-i18n-title / -aria-label /
// -placeholder / -alt) and keeps its Vietnamese words, so nothing flashes; the scripts ask T('id', { n: 3 }).
// A new language: i18n/<code>.json and its line in i18n/langs.json (and the apps' words: i18n/apps/<code>/). tests/i18n.test.js keeps them in step.
// Words that come from elsewhere (the server, the TV) arrive in this language already: the page's tvr-lang cookie (Words.kt).
// The list of languages is ONE file, i18n/langs.json ({ "vi": "Tiếng Việt", "en": "English" }, in the order of the buttons), read at
// once like the words below; if it cannot be read the page still has the two it began with.
const LANGS = (() => {
  try {
    const x = new XMLHttpRequest(); x.open('GET', 'i18n/langs.json' + (() => { try { return new URL(document.currentScript.src).search; } catch { return ''; } })(), false); x.send();
    const l = x.status === 200 ? JSON.parse(x.responseText) : null;
    if (l && l.en) return l;
  } catch {}
  return { vi: 'Tiếng Việt', en: 'English' };
})();
(() => {
  let stored = null;
  try { stored = localStorage.getItem('lang'); } catch {}
  let asked = null;                                                  // the phone app says which language its own screens use
  try { asked = new URLSearchParams(location.search).get('lang'); } catch {}
  const own = ((navigator.language || 'vi').toLowerCase().split('-')[0]);
  const LANG = LANGS[asked] ? asked : LANGS[stored] ? stored : LANGS[own] ? own : 'en';
  window.LANG = LANG;
  // dates and times as that language writes them (English day first, as before); speech with its region (vi-VN, fr-FR: a recognizer
  // wants one), from Intl, so a new language needs no line here
  window.LOCALE = LANG === 'en' ? 'en-GB' : LANG;
  window.SPEECH_LANG = (() => { try { const l = new Intl.Locale(LANG).maximize(); return l.language + '-' + l.region; } catch { return LANG; } })();
  // The page reloads to switch language, so where the viewer is (tab, whether the TV list was open, scroll) is handed over once
  // through sessionStorage: without it the computer app opened on the TV list again, kicking the viewer off the TV.
  window.LANG_KEEP = null;
  try { window.LANG_KEEP = JSON.parse(sessionStorage.getItem('langKeep')); sessionStorage.removeItem('langKeep'); } catch {}
  window.setLang = (l) => {
    try { localStorage.setItem('lang', l); } catch {}
    try { sessionStorage.setItem('langKeep', JSON.stringify({ tab: typeof currentTab === 'string' ? currentTab : '', picking: typeof picking !== 'undefined' && picking, settings: !!document.querySelector('#about[open]') })); } catch {}
    location.reload();
  };
  document.documentElement.lang = LANG;
  // right to left for a language written so (Arabic): told by its own name in langs.json, so a new one needs no list here.
  // css/rtl.css turns the page; the remote's keys and the TV's picture keep their sides (◀ is the TV's left in every language)
  document.documentElement.dir = /[֐-ࣿיִ-﷿ﹰ-ﻼ]/.test(LANGS[LANG] || '') ? 'rtl' : 'ltr';
  try { document.cookie = 'tvr-lang=' + LANG + '; path=/; max-age=31536000; SameSite=Strict'; } catch {}   // the server's words come in it (Words.kt)

  // read at once, as remote.json is: the page is drawn in its words from the first frame. ?v= of this script: an update brings new words
  const v = (() => { try { return new URL(document.currentScript.src).search; } catch { return ''; } })();
  const load = (l) => {
    try { const x = new XMLHttpRequest(); x.open('GET', 'i18n/' + l + '.json' + v, false); x.send(); return x.status === 200 ? JSON.parse(x.responseText) : {}; } catch { return {}; }
  };
  // only the viewer's language before the first paint; English, then Vietnamese, the first time an id is missing from it (a
  // language a few ids behind): one blocking read instead of three
  const order = [...new Set([LANG, 'en', 'vi'])], books = [load(order[0])];
  let rule = null;
  try { rule = new Intl.PluralRules(LANG); } catch {}
  /** The words of [id] in the viewer's language; {name} in them is args.name; a { one, other } entry is picked by args.n. */
  window.T = (id, args) => {
    let s;
    for (let i = 0; i < order.length; i++) {
      if (i === books.length) books.push(load(order[i]));
      if (Object.prototype.hasOwnProperty.call(books[i], id)) { s = books[i][id]; break; }
    }
    if (s == null) return id;
    if (typeof s === 'object') s = s[rule && args && args.n != null ? rule.select(Number(args.n)) : 'other'] ?? s.other;
    return args ? s.replace(/\{(\w+)\}/g, (m, k) => (k in args ? String(args[k]) : m)) : s;
  };
  const SLOTS = ['title', 'aria-label', 'placeholder', 'alt'];
  /** Its own words: the last of its own lines of text (an icon or a field beside it stays). */
  const words = (e, s) => {
    let slot = null;
    for (const n of e.childNodes) if (n.nodeType === 3 && n.nodeValue.trim()) slot = n;
    if (!slot) { e.append(s); return; }
    const m = /^(\s*)[\s\S]*?(\s*)$/.exec(slot.nodeValue);
    slot.nodeValue = m[1] + s + m[2];
  };
  const done = new WeakSet();                                         // once each: a script may write new words and add it again
  const put = (e) => {
    if (done.has(e)) return;
    done.add(e);
    const id = e.getAttribute('data-i18n'); if (id) words(e, T(id));
    const h = e.getAttribute('data-i18n-html'); if (h) e.innerHTML = T(h);
    for (const a of SLOTS) { const k = e.getAttribute('data-i18n-' + a); if (k) e.setAttribute(a, T(k)); }
  };
  const SEL = '[data-i18n], [data-i18n-html], ' + SLOTS.map((a) => '[data-i18n-' + a + ']').join(', ');
  /** Puts the words of every element with an id under [root] (and root itself): the page at once, a template's copy as it is added. */
  window.i18nPut = (root) => { if (LANG === 'vi' || !root || root.nodeType !== 1) return; if (root.matches(SEL)) put(root); root.querySelectorAll(SEL).forEach(put); };
  if (typeof document !== 'undefined' && document.documentElement && document.documentElement.querySelectorAll) i18nPut(document.documentElement);

  // what a script adds later (a template's copy, a dialog) gets its words as it comes in
  const start = () => new MutationObserver((list) => { for (const m of list) m.addedNodes.forEach(i18nPut); })
    .observe(document.body, { subtree: true, childList: true });
  if (LANG === 'vi') return;                                          // the markup is already in Vietnamese
  if (document.body) start(); else document.addEventListener('DOMContentLoaded', start);

})();
