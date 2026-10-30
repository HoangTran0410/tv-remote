// ⌨️ Quick keys (Phím nhanh) and the picker of what something opens. The same screen as TV Home's own (QuickKeys.kt / Picker.kt),
// so the TV, the phone, the computer and TV Home's web page edit one layout: 1-9 and 0, the colour keys, the power-on slot, each a
// tile with the logo of its target (js/target.js: an app, "pin:" shortcut or "tile:" live widget). A key this remote does not have
// (config.keysHave) is shown dead; one that already has something stays editable, so it can be cleared.
const QK_NUMBERS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 7];          // KEYCODE_1 .. 9, then 0 (HomeConfig.KEY_NUMBERS)
const QK_COLOURS = { 183: 'red', 184: 'green', 185: 'yellow', 186: 'blue' };
const QK_POWER = 0;                                                // the power-on slot's code: no key has it

// What a target is called: the app's name, the shortcut's or the live widget's title (from what TV Home lists), else its id
function qkLabel(c, apps, t) {
  const k = Target.kind(t);
  if (k === 'action') return T('home.qk_cc');
  if (k === 'tile') { const x = (c.tilesAvail || []).find((y) => y.key === t); return (x && x.title) || Target.id(t); }
  if (k === 'pin') { const x = (c.pinsAvail || []).find((y) => y.key === t); return (x && x.title) || Target.id(t); }
  const a = (apps || []).find((y) => y.pkg === t);
  return (a && a.label) || (k === 'app' ? appName(t) : t);
}

// The logo of a target: the app's own picture (a shortcut wears its app's), a live widget a letter on a colour of its own
function qkLogo(c, t) {
  const k = Target.kind(t);
  if (k === 'tile' || k === 'action') {
    const x = (c.tilesAvail || []).find((y) => y.key === t), word = k === 'action' ? T('home.qk_cc') : (x && x.title) || Target.id(t);
    return el('div', { className: 'appicon placeholder qklive', textContent: (word.match(/\p{L}|\p{N}/u) || ['?'])[0].toUpperCase() });
  }
  const pkg = Target.pkg(t), icon = appIcon(pkg);
  onMeta(pkg, (m) => icon.fill(m));
  return icon;
}

// [opts.actions]: also TV Home's own actions (a key only).
// A modal list of what can be opened: the apps, each one's shortcuts, the live widgets, with a search (no accents needed). It gives the
// target chosen, '' for "none" (only when [opts.none]) or null when closed. With [opts.toggle] a press calls it and the list stays
// open (the "add to home screen" use): [opts.has](target) says what is on, redrawn by the returned `refresh()` on the dialog.
function pickTarget(title, now, c, apps, opts = {}) {
  return new Promise((done) => {
    let picked = null;
    const fold = (t) => String(t || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[đĐ]/g, 'd').toLowerCase();
    const q = el('input', { type: 'search', placeholder: T('common.search_by_app_name'), spellcheck: false, autocomplete: 'off' });
    const none = el('p', { className: 'empty', textContent: T('apps.choose_none'), hidden: true });
    const items = [];
    const add = (t, label, owner, section) => items.push({ t, label, owner, section });
    for (const a of apps) add(a.pkg, a.label || appName(a.pkg), '', 'apps');
    if (opts.actions) add('do:controlcenter', T('home.qk_cc'), T('home.qk_over'), 'apps');   // a key's action (TV Home's Quick settings over any app), not for the power-on slot
    for (const p of c.pinsAvail || []) add(p.key, p.title, p.app ? appName(p.app) : '', 'pins');
    for (const x of c.tilesAvail || []) add(x.key, x.title, T('home.qk_live'), 'tiles');
    items.sort((a, b) => a.label.localeCompare(b.label));
    const sections = [['apps', 'home.qk_apps'], ['pins', 'home.qk_pins'], ['tiles', 'home.qk_tiles']];
    const btn = (it) => {
      const logo = qkLogo(c, it.t), name = el('span', { textContent: it.label });
      const b = el('button', { type: 'button', className: 'tile', title: it.t, onclick: () => {
        if (opts.toggle) { opts.toggle(it.t); setTimeout(paint, 250); return; }
        picked = it.t; d.close();
      } }, logo, name, ...(it.owner ? [el('small', { className: 'qktag', textContent: it.owner })] : []));
      b.item = it; return b;
    };
    const paint = () => tiles.forEach((b) => {
      const on = opts.toggle ? !!opts.has(b.item.t) : b.item.t === now;
      b.classList.toggle('on', on); b.setAttribute('aria-pressed', String(on));
    });
    const tiles = items.map(btn);
    const blocks = sections.map(([s, id]) => ({ s, head: el('h3', { className: 'qkhead', textContent: T(id) }), grid: el('div', { className: 'tiles choosegrid2' }) }));
    for (const b of tiles) blocks.find((x) => x.s === b.item.section).grid.append(b);
    const filter = () => {
      const s = fold(q.value.trim()); let n = 0;
      for (const b of tiles) { b.hidden = !!s && !fold(b.item.label).includes(s) && !fold(b.item.owner).includes(s); if (!b.hidden) n++; }
      for (const x of blocks) x.head.hidden = x.grid.hidden = !tiles.some((b) => b.item.section === x.s && !b.hidden);
      none.hidden = n > 0;
    };
    q.addEventListener('input', filter);
    q.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); const b = tiles.find((x) => !x.hidden); if (b) b.click(); } });   // the first one left
    const clear = opts.none ? [el('button', { type: 'button', className: 'small' + (!now ? ' on' : ''), textContent: T('home.qk_none'), onclick: () => { picked = ''; d.close(); } })] : [];
    const d = el('dialog', { className: 'ask appchoose qkpick' }, el('div', { className: 'card' },
      el('div', { className: 'card-head' }, el('h2', { textContent: title }), el('button', { type: 'button', className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
      q, ...clear, el('p', { className: 'note', textContent: T(opts.toggle ? 'home.qk_grid_hint' : 'home.qk_target_hint') }),
      el('div', { className: 'qklist' }, ...blocks.flatMap((x) => [x.head, x.grid]), none)));
    d.addEventListener('close', () => { d.remove(); done(picked); });
    d.refresh = paint;
    paint(); filter();
    document.body.append(d); d.showModal();
    if (matchMedia('(pointer: fine)').matches) q.focus(); else (tiles.find((b) => b.classList.contains('on')) || q).focus();
  });
}

// The grid itself: [host] gets the slots, [send] writes a change of the config. Redrawn by the page on every config it gets.
function quickKeysDraw(host, c, apps, send) {
  const have = Array.isArray(c.keysHave) ? c.keysHave : null;      // null: not known, every key counts
  const ka = c.keyapps || {};
  const target = (code) => code === QK_POWER ? (c.autoapp || '') : (ka[code] || '');
  const dead = (code) => !!have && code !== QK_POWER && !have.includes(code) && !target(code);
  const choose = async (code, words) => {
    const p = await pickTarget(words, target(code), c, apps, { none: true, actions: code !== QK_POWER });
    if (p === null) return;
    if (code === QK_POWER) { send({ autoapp: p, autoon: !!p }); return; }   // the slot alone decides: a target switches it on, none switches it off
    const n = { ...ka }; if (p) n[code] = p; else delete n[code];
    send({ keyapps: n, ...(p ? { keyon: true } : {}) });            // the first one chosen switches the keys on: nobody asks twice
  };
  const slot = (code) => {
    const t = target(code), words = code === QK_POWER ? T('home.qk_power') : homeKeyName(code);
    const isNum = QK_NUMBERS.includes(code), isColour = !!QK_COLOURS[code];
    const badge = el('span', { className: 'qkbadge ' + (QK_COLOURS[code] || ''), textContent: code === QK_POWER ? '⏻' : isNum ? words : '' });
    const art = t ? qkLogo(c, t) : el('div', { className: 'appicon placeholder qkplus', textContent: '+' });
    const note = dead(code) ? T('home.qk_no_key') : !isNum && !isColour && code !== QK_POWER ? words : '';   // a key without a badge says its name here
    const b = el('button', { type: 'button', className: 'qk' + (t ? ' set' : '') + (dead(code) ? ' dead' : ''), disabled: dead(code), onclick: () => choose(code, words),
      title: dead(code) ? words + ' · ' + T('home.qk_no_key') : words, 'aria-label': words + (t ? ': ' + qkLabel(c, apps, t) : '') }, badge, art,
      el('span', { className: 'qkname', textContent: t ? qkLabel(c, apps, t) : isNum ? '' : words }),
      ...(note ? [el('small', { className: 'qktag', textContent: note })] : []));
    return b;
  };
  const extra = (c.keysDead || []).filter((k) => HOME_KEY_WORDS[k]);
  host.className = c.focus === 'ring' ? 'qkgrid focus-ring' : 'qkgrid focus-glass';
  host.replaceChildren(...[...QK_NUMBERS, ...Object.keys(QK_COLOURS).map(Number), QK_POWER, ...extra].map(slot));
}
