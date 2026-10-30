// 🏠 The order of TV Home's app grid, edited as the grid itself: the tiles in TV Home's columns, moved by dragging (a mouse at once,
// a finger after a short hold, so the page still scrolls under a swipe) or by a press on one and a press where it goes (the TV's
// own page has only the D-pad). Nothing goes to the TV until Save: one write, one rebuild, where a write per move made the TV
// redraw its grid at each step and the page wait for it.

// The grid as TV Home lays it out (HomeActivity.show): its old favourites (config "dock") first, then every other app, live widget
// and shortcut put on the grid ("tiles") not hidden, in "order", those it does not list after them by name. [apps]: [{pkg, label}].
// (A shortcut an app pinned by itself is not in the config: the TV puts it after the favourites, and this page never sees it.)
function homeGridOrder(c, apps) {
  const folders = c.folders || {};                                   // a folder is a tile; the apps in it are not on the grid
  const inFolder = new Set(Object.values(folders).flatMap((f) => f.apps || []));
  const tiles = (c.tiles || []).filter((t) => Target.kind(t) === 'tile' || Target.kind(t) === 'pin' || (/^folder:[a-z0-9]{1,8}$/.test(t) && folders[t.slice(7)]));
  const all = [...apps.filter((a) => !inFolder.has(a.pkg)).map((a) => ({ key: a.pkg, label: a.label || a.pkg })),
    ...tiles.map((t) => { const x = (c.tilesAvail || []).find((y) => y.key === t); return { key: t, label: t.startsWith('folder:') ? folders[t.slice(7)].name : (x && x.title) || Target.id(t) }; })]
    .sort((a, b) => { const x = a.label.toLowerCase(), y = b.label.toLowerCase(); return x < y ? -1 : x > y ? 1 : 0; });
  const have = new Set(all.map((a) => a.key)), hidden = new Set(c.hidden || []);
  const dock = (c.dock || []).filter((k) => have.has(k));
  const place = new Map((c.order || []).map((k, i) => [k, i]));
  const rest = all.filter((a) => !dock.includes(a.key) && !hidden.has(a.key)).map((a) => a.key);
  const at = (k) => place.has(k) ? place.get(k) : Infinity;
  rest.sort((a, b) => at(a) - at(b));                                // stable: those it does not list keep their A-Z
  return [...dock, ...rest];
}

// What Save sends: the whole order and no favourites (the TV's Move kept the first places in "dock"; here every place is the order's)
function homeOrderChange(keys) { return { dock: [], order: keys }; }

// The editor: [box] is filled; [face](key) a tile's picture, [name](key) its words; [save](change) writes it. Returns draw(c, apps),
// called with every new answer from the TV: while something is moved and not saved, the moved order stays.
function homeOrderBox(box, face, name, save) {
  let base = [], keys = [], cols = 5, picked = -1, dropped = 0;
  const grid = el('div', { className: 'homeorder', role: 'list' });
  const note = el('p', { className: 'note', textContent: T('home.order_note') });
  const ok = el('button', { type: 'button', className: 'primary', textContent: T('home.order_save'), onclick: async () => {
    const back = UI.busy(ok, T('home.order_saving'));
    try { await save(homeOrderChange(keys)); base = keys.slice(); } finally { back(); }
    paint();
  } });
  const undo = el('button', { type: 'button', textContent: T('common.cancel'), onclick: () => { keys = base.slice(); picked = -1; paint(); } });
  const acts = el('div', { className: 'actions end' }, undo, ok);
  box.replaceChildren(note, grid, acts);
  const dirty = () => keys.join() !== base.join();

  const move = (from, to) => { if (from === to || to < 0 || to >= keys.length) return; const [k] = keys.splice(from, 1); keys.splice(to, 0, k); };
  const cell = (k, i) => {
    const b = el('button', { type: 'button', className: 'cell' + (i === picked ? ' on' : ''), role: 'listitem',
      'aria-pressed': String(i === picked), title: name(k) }, face(k), el('span', { textContent: name(k) }));
    b.dataset.i = i;
    b.onclick = () => {                                               // press, then press where it goes (D-pad, or a tap)
      if (Date.now() - dropped < 400) return;                         // the click a drag ends with is not a pick
      if (picked < 0) picked = i; else { move(picked, i); picked = -1; }
      paint(); grid.querySelector('[data-i="' + i + '"]')?.focus();
    };
    drag(b);
    return b;
  };
  function paint() {
    grid.style.setProperty('--cols', cols);
    grid.replaceChildren(...keys.map((k, i) => cell(k, i)));
    ok.disabled = undo.disabled = !dirty();
    put(note, 'textContent', T(picked >= 0 ? 'home.order_where' : dirty() ? 'home.order_dirty' : 'home.order_note'));
  }

  // dragging: the tile under the finger takes the dragged one's place at once (the grid is the preview); a finger needs a short
  // hold first, a mouse only a few pixels of travel. The page scrolls by itself near its top and bottom edge. The moves are heard
  // on the window: moving the dragged node in the page drops any pointer capture on it (a captured drag stopped after one step).
  function drag(b) {
    let id = null, hold = 0, on = false, x0 = 0, y0 = 0, scroll = 0, lastY = 0;
    const stopScroll = (e) => { if (on) e.preventDefault(); };
    const start = () => { on = true; picked = -1; b.classList.add('lift'); grid.classList.add('dragging'); if (navigator.vibrate) navigator.vibrate(15); };
    const over = (x, y) => {
      const t = document.elementFromPoint(x, y), c = t && t.closest('.homeorder .cell');
      if (!c || c === b) return;
      const from = +b.dataset.i, to = +c.dataset.i;
      move(from, to);
      [...grid.querySelectorAll('.cell')].forEach((n) => { const i = +n.dataset.i; if (n === b) n.dataset.i = to; else if (from < to && i > from && i <= to) n.dataset.i = i - 1; else if (from > to && i >= to && i < from) n.dataset.i = i + 1; });
      to > from ? c.after(b) : c.before(b);                            // the node moves, not a repaint: the drag goes on with it
    };
    const edge = () => { cancelAnimationFrame(scroll); if (!on) return; const h = innerHeight, d = lastY < 60 ? -8 : lastY > h - 60 ? 8 : 0; if (d) { scrollBy(0, d); scroll = requestAnimationFrame(edge); } };
    const moved = (e) => {
      if (e.pointerId !== id) return;
      const far = Math.hypot(e.clientX - x0, e.clientY - y0);
      if (!on) { if (far > 8) clearTimeout(hold); if (e.pointerType === 'mouse' && far > 4) start(); if (!on) return; }
      lastY = e.clientY; over(e.clientX, e.clientY); edge();
    };
    const end = (e) => {
      if (e.pointerId !== id) return;
      id = null; clearTimeout(hold); cancelAnimationFrame(scroll);
      removeEventListener('pointermove', moved); removeEventListener('pointerup', end); removeEventListener('pointercancel', end);
      b.removeEventListener('touchmove', stopScroll);
      if (!on) return;
      if (e.type === 'pointerup') over(e.clientX, e.clientY);         // where it was let go, even with no move reported there
      on = false; dropped = Date.now(); b.classList.remove('lift'); grid.classList.remove('dragging');
      paint();
    };
    b.addEventListener('pointerdown', (e) => {
      if (e.button > 0 || id !== null) return;
      id = e.pointerId; x0 = e.clientX; y0 = e.clientY; lastY = y0;
      if (e.pointerType !== 'mouse') hold = setTimeout(start, 280);
      addEventListener('pointermove', moved); addEventListener('pointerup', end); addEventListener('pointercancel', end);
      b.addEventListener('touchmove', stopScroll, { passive: false });
    });
    b.addEventListener('contextmenu', (e) => { if (e.pointerType !== 'mouse' || on) e.preventDefault(); });   // a long press is the drag, not the phone's menu
    b.addEventListener('dragstart', (e) => e.preventDefault());       // the browser's own drag (of the app's <img>) would take the pointer from ours
  }

  return (c, apps) => {
    cols = c.cols || 5;
    const fresh = !dirty();
    base = homeGridOrder(c, apps);
    if (fresh) keys = base.slice();
    else keys = [...keys.filter((k) => base.includes(k)), ...base.filter((k) => !keys.includes(k))];   // an app came or went meanwhile
    if (!grid.querySelector('.lift')) paint();
  };
}

if (typeof module !== 'undefined') module.exports = { homeGridOrder, homeOrderChange };
