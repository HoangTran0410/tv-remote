#!/usr/bin/env node
// Writes the user guide for AI agents: docs/guide/<lang>/index.md (every topic, one line each), docs/guide/<lang>/<id>.md (one topic),
// each also as <name>.html (what the copied prompt names: chat apps' readers open pages, not .md)
// and docs/guide/<lang>/all.md (all of them), from docs/guide.html (the one source). An agent reads the index (~3k tokens), then only
// the topics it needs, instead of the whole page (~80k tokens of HTML and animations). The guide's "Copy for AI" button points here.
// Run after editing guide.html; app/shared/tests/guide.test.js fails when these files differ from this output.
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '../..');
const SITE = 'https://tvremote.pages.dev';
const OUT = path.join(ROOT, 'docs/guide');
const LANGS = ['en', 'vi'];
const HEAD = {
  en: { title: 'TV Remote user guide (for AI agents)', how: 'Remote control for Android TV / Google TV (and LG, Samsung, Roku, VIDAA) with no server in between: a phone app, a computer app, a web page served by the TV, and TV apps. Find the topic below, then read only its page. The same guide for people: ' + SITE + '/guide' },
  vi: { title: 'Hướng dẫn TV Remote (cho trợ lý AI)', how: 'Điều khiển Android TV / Google TV (và LG, Samsung, Roku, VIDAA) không qua máy chủ nào: app điện thoại, app máy tính, trang web của TV và các app trên TV. Tìm mục dưới đây rồi chỉ đọc trang của mục đó. Bản cho người đọc: ' + SITE + '/guide' },
};

const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', rsaquo: '›', larr: '←', rarr: '→', middot: '·', hellip: '…', ndash: '–', mdash: '—', times: '×', copy: '©' };
const unent = (s) => s.replace(/&(#x?[0-9a-f]+|\w+);/gi, (m, e) => e[0] === '#' ? String.fromCodePoint(e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : +e.slice(1)) : ENT[e] ?? m);
const attr = (tag, name) => { const m = tag.match(new RegExp('\\s' + name + '="([^"]*)"')); return m ? unent(m[1]) : null; };

// The article's HTML as a tree: { tag, attrs (the opening tag's text), kids } and strings
function tree(html) {
  const root = { tag: 'root', kids: [] }, stack = [root];
  const VOID = new Set(['img', 'br', 'hr', 'input', 'source', 'wbr', 'use', 'path', 'circle', 'rect']);
  for (const m of html.matchAll(/<!--[\s\S]*?-->|<(\/?)([a-zA-Z0-9]+)([^>]*?)(\/?)>|([^<]+)/g)) {
    if (m[5] !== undefined) { stack[stack.length - 1].kids.push(unent(m[5])); continue; }
    if (!m[2]) continue;
    const tag = m[2].toLowerCase();
    if (m[1]) { const i = stack.map((n) => n.tag).lastIndexOf(tag); if (i > 0) stack.length = i; continue; }
    const node = { tag, attrs: m[3], kids: [] };
    stack[stack.length - 1].kids.push(node);
    if (!VOID.has(tag) && !m[4]) stack.push(node);
  }
  return root;
}

const cls = (n) => (attr(n.attrs || '', 'class') || '').split(/\s+/);
// left out: the other language, the animations and drawn screens (.ga-box, .gst-box: their steps are the .ga's list, kept),
// screenshots, buttons, svg, the topic's emoji
const skip = (n, lang) => { const c = cls(n); return c.includes(lang === 'en' ? 'vi' : 'en') || c.some((x) => /^(ga|gst)-(box|stage|replay)$/.test(x)) || c.includes('gi')
  || ['img', 'button', 'svg', 'script', 'style', 'kbd'].includes(n.tag); };

function link(href) {
  if (!href) return '';
  if (href.startsWith('#')) return href.slice(1) + '.md';
  const g = href.match(/^(?:guide|\/guide|https:\/\/tvremote\.pages\.dev\/guide)(?:\.html)?#(.+)$/);
  if (g) return g[1] + '.md';
  return /^[a-z]+:/.test(href) ? href : SITE + '/' + href.replace(/^\//, '');
}

// inline text of a node: **b**, `code`, [a](url); white space folded
function inline(n, lang) {
  if (typeof n === 'string') return n.replace(/\s+/g, ' ');
  if (skip(n, lang)) return '';
  const t = n.kids.map((k) => inline(k, lang)).join('');
  if (n.tag === 'br') return ' ';
  if (n.tag === 'b' || n.tag === 'strong') return t.trim() ? '**' + t.trim() + '**' : '';
  if (n.tag === 'code') return t.trim() ? '`' + t.trim() + '`' : '';
  if (n.tag === 'a') { const u = link(attr(n.attrs, 'href')); return u && t.trim() ? '[' + t.trim() + '](' + u + ')' : t; }
  return t;
}
const clean = (s) => s.replace(/[ \t]+/g, ' ').replace(/ ([,.;:!?)])/g, '$1').trim();

// blocks of a node: paragraphs, lists, tables, code
function blocks(n, lang, out) {
  for (const k of n.kids) {
    if (typeof k === 'string') { if (k.trim()) out.push(clean(k)); continue; }
    if (skip(k, lang)) continue;
    const c = cls(k);
    if (k.tag === 'h3') continue;                                    // the file's title
    if (k.tag === 'h4') out.push('### ' + clean(inline(k, lang)));
    else if (k.tag === 'p') { const t = clean(inline(k, lang)); if (t) out.push((c.includes('gnote') || c.includes('gwarn') ? '> ' : '') + t); }
    else if (k.tag === 'ol' || k.tag === 'ul') {
      let i = 0;
      const items = k.kids.filter((x) => typeof x !== 'string' && x.tag === 'li' && !skip(x, lang)).map((li) => {
        const sub = []; blocks(li, lang, sub);
        return (k.tag === 'ol' ? ++i + '. ' : '- ') + sub.join(' ').replace(/\n+/g, ' ');
      }).filter((s) => s.replace(/^(\d+\.|-) /, '').trim());
      if (items.length) out.push(items.join('\n'));
    } else if (k.tag === 'table') {
      const rows = [];
      const walk = (x) => { if (typeof x === 'string' || skip(x, lang)) return; if (x.tag === 'tr') rows.push(x.kids.filter((c2) => typeof c2 !== 'string' && (c2.tag === 'td' || c2.tag === 'th')).map((c2) => clean(inline(c2, lang)).replace(/\|/g, '\\|'))); else x.kids.forEach(walk); };
      walk(k);
      if (rows.length) out.push(['| ' + rows[0].join(' | ') + ' |', '|' + rows[0].map(() => ' --- |').join(''), ...rows.slice(1).map((r) => '| ' + r.join(' | ') + ' |')].join('\n'));
    } else if (k.tag === 'pre') out.push('```\n' + k.kids.map((x) => typeof x === 'string' ? x : inline(x, lang).replace(/`/g, '')).join('').trim() + '\n```');
    else if (['div', 'figure', 'figcaption', 'section', 'li', 'details', 'summary', 'span', 'tbody', 'thead'].includes(k.tag)) {
      const sub = []; blocks(k, lang, sub);
      // a div of words only (a step's text) is one line; one holding lists or tables keeps its blocks
      if (sub.length && k.kids.every((x) => typeof x === 'string' || ['span', 'b', 'a', 'code', 'i', 'em', 'u', 'small', 'br'].includes(x.tag))) out.push(clean(inline(k, lang)));
      else out.push(...sub);
    } else { const t = clean(inline(k, lang)); if (t) out.push(t); }
  }
  return out;
}

function topics(html) {
  const out = [];
  let group = null;
  for (const m of html.matchAll(/<h2 class="gh"[^>]*>([\s\S]*?)<\/h2>|<article class="topic"([^>]*)>([\s\S]*?)<\/article>/g)) {
    if (m[1] !== undefined) { group = tree(m[1]); continue; }
    const t = tree('<article' + m[2] + '>' + m[3] + '</article>').kids[0];
    const h3 = t.kids.find((k) => typeof k !== 'string' && k.tag === 'h3');
    out.push({ id: attr(m[2], 'id'), kw: attr(m[2], 'data-kw') || '', group, h3, node: t });
  }
  return out;
}

/**
 * The same text as a page: the chat apps' web readers (ChatGPT, Gemini) open HTML but refused the .md ("cannot load this Markdown
 * format"). The Markdown stays as it is inside <pre>, its links made real, extensionless as Cloudflare Pages serves them.
 */
function page(md, lang) {
  const esc = (x) => x.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const title = esc(md.match(/^# (.*)/)[1]);
  const body = esc(md).replace(/\[([^\]]+)\]\(([\w-]+)\.md\)/g, (m, t, id) => '[<a href="' + (id === 'index' ? './' : id) + '">' + t + '</a>]');
  return '<!doctype html><html lang="' + lang + '"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
    + '<title>' + title + '</title><pre style="white-space:pre-wrap;font:15px/1.5 sans-serif;max-width:760px;margin:24px auto;padding:0 16px">' + body + '</pre></html>\n';
}

function build() {
  const html = fs.readFileSync(path.join(ROOT, 'docs/guide.html'), 'utf8');
  const files = {};
  for (const lang of LANGS) {
    const all = [], index = ['# ' + HEAD[lang].title, '', HEAD[lang].how];
    let last = null;
    for (const t of topics(html)) {
      const title = clean(inline(t.h3, lang));
      const body = blocks(t.node, lang, []).join('\n\n');
      const page = '# ' + title + '\n\n' + body + '\n';
      files[lang + '/' + t.id + '.md'] = page;
      all.push(page.replace(/^# /, '## ').replace(/\]\(([\w-]+)\.md\)/g, '](#$1)'));
      const g = clean(inline(t.group, lang));
      if (g !== last) { index.push('', '## ' + g); last = g; }
      // the first plain sentence; a topic that is only a table or steps: its first step
      const lines = body.split('\n'), first = lines.find((l) => /^[^#>|`-]/.test(l) && !/^\d+\. /.test(l)) || (lines.find((l) => /^\d+\. /.test(l)) || '').replace(/^\d+\. /, '');
      const plain = first.replace(/\*\*|`/g, '').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
      const short = plain.length > 140 ? plain.slice(0, plain.lastIndexOf(' ', 137)) + '…' : plain;
      index.push('- [' + title + '](' + t.id + '.md): ' + short + (t.kw ? ' _(' + t.kw + ')_' : ''));   // kw: the page search's words, vi without accents + en
    }
    files[lang + '/index.md'] = index.join('\n') + '\n';
    files[lang + '/all.md'] = '# ' + HEAD[lang].title + '\n\n' + all.join('\n') ;
  }
  for (const f of Object.keys(files)) if (!f.endsWith('/all.md')) files[f.replace(/\.md$/, '.html')] = page(files[f], f.slice(0, 2));
  files['README.md'] = '# Generated: do not edit\n\nEvery file here is written by `node dev/gen/guide_md.js` from `docs/guide.html`, the one source.\n' +
    'Edit guide.html, then run the script (it wipes this folder first); `app/shared/tests/guide.test.js` fails when these files are stale.\n';
  return files;
}

if (require.main === module) {
  const files = build();
  fs.rmSync(OUT, { recursive: true, force: true });
  for (const [f, s] of Object.entries(files)) { fs.mkdirSync(path.dirname(path.join(OUT, f)), { recursive: true }); fs.writeFileSync(path.join(OUT, f), s); }
  console.log(Object.keys(files).length + ' files in docs/guide/');
}
module.exports = { build };
