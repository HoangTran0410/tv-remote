'use strict';
// Cờ cá ngựa for TV Games: the board on the TV, each player's roll and horse buttons on their phone (the pad's {cards} layout with an
// empty hand: just big named buttons). Seats, bots, phones joining, the clock and scores: _lib/table.js; rules and bot: rules.js.
// The TV is the one authority: it rolls, it checks every button, a phone only asks.
const W = Object.assign({}, Table.W, Table.VI ? {
  roll: 'Gieo xúc xắc', rs: 'Gieo', pass: 'Bỏ lượt', horse: (n) => `Ngựa ${n}`, bad: 'Chưa làm được lúc này', cant: 'Ngựa này chưa đi được',
  yourRoll: 'Tới lượt bạn: gieo xúc xắc', pick: (d) => `Bạn gieo được ${d}: chọn ngựa`, nomove: (d) => `Gieo được ${d}: không có ngựa nào đi được`,
  rolling: 'Đang gieo…', going: 'Ngựa đang đi…', rollsOf: (n) => `${n} gieo xúc xắc`, got: (n, d) => `${n} gieo được ${d}`,
  nomoveTv: (n, d) => `${n} gieo ${d}: không đi được`, six: (n) => `${n} được gieo tiếp`, kick: (n, o) => `${n} đá ngựa của ${o}!`, out: (n) => `${n} ra quân`,
  home: (n) => `${n} đưa một ngựa về đích`, skipped: 'Bỏ lượt', homeOf: 'về đích', your: 'Bạn', won: (n) => `${n} đưa cả 4 ngựa về đích`,
} : {
  roll: 'Roll the dice', rs: 'Roll', pass: 'Pass', horse: (n) => `Horse ${n}`, bad: 'Not possible right now', cant: 'That horse cannot move',
  yourRoll: 'Your turn: roll the dice', pick: (d) => `You rolled ${d}: pick a horse`, nomove: (d) => `Rolled ${d}: no horse can move`,
  rolling: 'Rolling…', going: 'Horse on the move…', rollsOf: (n) => `${n} rolls`, got: (n, d) => `${n} rolled ${d}`,
  nomoveTv: (n, d) => `${n} rolled ${d}: no move`, six: (n) => `${n} rolls again`, kick: (n, o) => `${n} kicks ${o}'s horse!`, out: (n) => `${n} brings a horse out`,
  home: (n) => `${n} gets a horse home`, skipped: 'Pass', homeOf: 'home', your: 'You', won: (n) => `${n} got all 4 horses home`,
});
const TURN = 30, C = 45, STEP = 120;                           // seconds a phone has, px per board cell, ms per cell a horse walks
const $ = (s) => document.querySelector(s);
const h = (t, c, x) => { const e = document.createElement(t); if (c) e.className = c; if (x != null) e.textContent = x; return e; };
const clone = (p) => p.map((a) => a.slice());
const g = { pos: LU.fresh(), shown: LU.fresh(), phase: 'roll', dice: 0, moves: [], busy: false, rolling: false, msg: '', gen: 0, walking: null, face: 0 };
const later = (ms, f) => { const gen = g.gen; return setTimeout(() => { if (gen === g.gen && !table.done) f(); }, ms); };

// ---- the board geometry, in cells (centre of cell c,r = c+.5,r+.5). Everything is the top-left quarter turned a quarter at a time.
const rot = (k, x, y) => { for (let i = 0; i < k; i++) [x, y] = [15 - y, x]; return [x, y]; };
const rotC = (k, c, r) => { for (let i = 0; i < k; i++) [c, r] = [14 - r, c]; return [c, r]; };
const BASE = [[1, 6], [2, 6], [3, 6], [4, 6], [5, 6], [6, 5], [6, 4], [6, 3], [6, 2], [6, 1], [6, 0], [7, 0], [8, 0]];
const TRACK = []; for (let k = 0; k < 4; k++) BASE.forEach(([c, r]) => TRACK.push(rotC(k, c, r)));   // 52 cells, clockwise
const quarter = (s) => (s + 3) % 4;                              // seats: 1 top-left, 2 top-right, 3 bottom-right, 0 bottom-left
const xy = ([c, r]) => [(c + .5) * C, (r + .5) * C];
const STABLE = [[2, 2], [4, 2], [2, 4], [4, 4]];
const LANE = (s, i) => rotC(quarter(s), 1 + i, 7);
/** px position of horse at progress p of seat s (n: the horse, for its stable spot and its place at home). */
function where(s, hh, p) {
  const k = quarter(s);
  if (p < 0) { const [x, y] = rot(k, STABLE[hh][0], STABLE[hh][1]); return [x * C, y * C]; }
  if (p <= 50) return xy(TRACK[(LU.START[s] + p) % 52]);
  if (p < LU.END) return xy(LANE(s, p - 51));
  const [x, y] = rot(k, 6.55, 7.5 + (hh - 1.5) * .38);          // home: lined up on its own side of the centre
  return [x * C, y * C];
}

function paintBoard() {
  const cv = $('#bg'), x = cv.getContext('2d'), U = 90;
  x.fillStyle = '#f3ecd9'; x.fillRect(0, 0, 15 * U, 15 * U);
  const cellRect = (c, r, fill, line) => { x.fillStyle = fill; x.fillRect(c * U + 2, r * U + 2, U - 4, U - 4); if (line) { x.strokeStyle = line; x.lineWidth = 3; x.strokeRect(c * U + 3.5, r * U + 3.5, U - 7, U - 7); } };
  const star = (c, r, col) => { x.fillStyle = col; x.font = `${U * .62}px system-ui`; x.textAlign = 'center'; x.textBaseline = 'middle'; x.fillText('★', (c + .5) * U, (r + .56) * U); };
  for (let s = 0; s < 4; s++) {
    const k = quarter(s), col = Table.COLORS[s];
    const [ax, ay] = rot(k, 0, 0), [bx, by] = rot(k, 6, 6);
    x.fillStyle = col; x.fillRect(Math.min(ax, bx) * U, Math.min(ay, by) * U, 6 * U, 6 * U);
    const [cx0, cy0] = rot(k, 1, 1), [cx1, cy1] = rot(k, 5, 5);
    x.fillStyle = '#fffdf6'; x.beginPath(); x.roundRect(Math.min(cx0, cx1) * U, Math.min(cy0, cy1) * U, 4 * U, 4 * U, U * .5); x.fill();
    STABLE.forEach(([sx, sy]) => { const [px, py] = rot(k, sx, sy); x.fillStyle = col + '55'; x.beginPath(); x.arc(px * U, py * U, U * .5, 0, 7); x.fill(); x.strokeStyle = col; x.lineWidth = 4; x.stroke(); });
  }
  for (let i = 0; i < 52; i++) cellRect(TRACK[i][0], TRACK[i][1], '#fffdf6', '#c9c0a8');
  for (let s = 0; s < 4; s++) {
    const col = Table.COLORS[s];
    for (let i = 0; i < 5; i++) { const [c, r] = LANE(s, i); cellRect(c, r, col, null); }
    const [c, r] = TRACK[LU.START[s]]; cellRect(c, r, col, null);
  }
  LU.SAFE.forEach((i) => { const [c, r] = TRACK[i]; star(c, r, LU.START.includes(i) ? '#ffffffdd' : '#b9b09a'); });
  // the centre: a triangle per seat pointing in from its own side
  const m = 7.5 * U;
  for (let s = 0; s < 4; s++) {
    const k = quarter(s), p = (a, b) => { const [px, py] = rot(k, a, b); return [px * U, py * U]; };
    const a = p(6, 6), b = p(6, 9);
    x.fillStyle = Table.COLORS[s]; x.beginPath(); x.moveTo(a[0], a[1]); x.lineTo(b[0], b[1]); x.lineTo(m, m); x.closePath(); x.fill();
    x.strokeStyle = '#fffdf6'; x.lineWidth = 3; x.stroke();
  }
  x.strokeStyle = '#8b826a'; x.lineWidth = 4; x.strokeRect(0, 0, 15 * U, 15 * U);
}

// ---- horses (DOM, moved with transforms)
const toks = [];                                                  // toks[s][h] = element
function buildToks() {
  const root = $('#toks');
  for (let s = 0; s < 4; s++) {
    toks[s] = [];
    for (let k = 0; k < 4; k++) { const e = h('div', 'tok', String(k + 1)); e.style.setProperty('--pc', Table.COLORS[s]); root.append(e); toks[s][k] = e; place(s, k, ...where(s, k, -1)); }
  }
}
const place = (s, k, x, y) => { toks[s][k].style.transform = `translate(${x}px,${y}px)`; };
let ghosts = [];

/** Put every horse where g.shown says (the walking one is left to the walk). Horses sharing a cell shrink and sit apart. */
function drawToks(t) {
  const by = {};
  for (let s = 0; s < 4; s++) for (let k = 0; k < 4; k++) {
    const p = g.shown[s][k], key = p >= 0 && p <= 50 ? 'c' + ((LU.START[s] + p) % 52) : p > 50 && p < LU.END ? 'l' + s + p : 'x' + s + k;
    (by[key] = by[key] || []).push([s, k, p]);
  }
  const can = !t.done && !g.busy && g.phase === 'move' && t.human(t.turn) ? g.moves : [];
  for (const key in by) {
    const list = by[key], n = list.length;
    list.forEach(([s, k, p], i) => {
      const e = toks[s][k];
      e.classList.toggle('small', n > 1);
      e.classList.toggle('can', s === t.turn && can.includes(k));
      if (g.walking && g.walking.s === s && g.walking.k === k) return;
      let [x, y] = where(s, k, p);
      if (n > 1) { x += ((i % 2) - .5) * 20; y += (Math.floor(i / 2) - (n > 2 ? .5 : 0)) * 20; }
      place(s, k, x, y);
    });
  }
  ghosts.forEach((e) => e.remove()); ghosts = [];
  if (can.length) can.forEach((k) => {
    const s = t.turn, to = LU.step(g.shown[s][k], g.dice);
    if (to == null) return;
    const [x, y] = where(s, k, to), e = h('div', 'ghost', String(k + 1));
    e.style.setProperty('--pc', Table.COLORS[s]); e.style.left = x + 'px'; e.style.top = y + 'px'; $('#toks').prepend(e); ghosts.push(e);
  });
}

// ---- the die
const PIPS = [[], [4], [0, 8], [0, 4, 8], [0, 2, 6, 8], [0, 2, 4, 6, 8], [0, 2, 3, 5, 6, 8]];
let dieT = 0;
function face(d) { const kids = $('#die').children; for (let i = 0; i < 9; i++) kids[i].classList.toggle('on', PIPS[d].includes(i)); g.face = d; }
function startDie() {
  const el = $('#die'); el.classList.remove('idle'); el.classList.add('roll');
  clearInterval(dieT); dieT = setInterval(() => face(1 + Math.floor(Math.random() * 6)), 90);
}
function stopDie() { clearInterval(dieT); $('#die').classList.remove('roll'); face(g.dice); }

// ---- the flow of a turn: roll -> (pick a horse) -> walk -> maybe kick -> same seat again on a 6, else the next seat
const nm = (t, s) => t.name(s);
const table = Table({
  seats: 4, turnSecs: TURN, botMs: [600, 1100], cards: true, remote: { mount: '#side' },
  deal(t) {
    g.gen++; g.pos = LU.fresh(); g.shown = LU.fresh(); g.phase = 'roll'; g.dice = 0; g.moves = []; g.busy = false; g.rolling = false; g.walking = null;
    clearInterval(dieT); t.turn = t.lastWinner >= 0 ? t.lastWinner : 0; g.msg = '';
    toks.forEach((row, s) => row.forEach((e, k) => { e.classList.remove('walk', 'land'); }));
  },
  bot(t, s) {
    if (g.busy) return;
    if (g.phase === 'roll') roll(t, s);
    else if (g.moves.length) move(t, s, LU.bot(g.pos, s, g.dice));
  },
  timeUp(t, s) {
    if (g.busy) return;
    if (g.phase === 'roll') roll(t, s);
    else if (g.moves.length) move(t, s, LU.bot(g.pos, s, g.dice));
  },
  tick: clock,
  act(t, s, m) {
    if (s !== t.turn) return W.notyet;
    if (g.busy) return W.bad;
    let why = '';
    if (m.act === 'roll') { if (g.phase !== 'roll') return W.bad; roll(t, s); }
    else if (m.act === 'pass') { if (g.phase !== 'move' || g.moves.length) return W.bad; endTurn(t, s); }
    else if (/^m[0-3]$/.test(m.act || '')) {
      if (g.phase !== 'move') return W.bad;
      const k = +m.act[1];
      if (!g.moves.includes(k)) return W.cant;
      move(t, s, k);
    } else return W.bad;
    if (!why) t.say(s, '');
    return why;
  },
  view: (t, s) => view(t, s), draw,
});

function roll(t, s) {
  g.dice = LU.roll(g.pos, s); g.phase = 'move'; g.moves = []; g.busy = true; g.rolling = true; g.msg = W.rollsOf(nm(t, s));
  startDie(); Sfx.play('dice'); t.changed();
  later(750, () => {
    g.rolling = false; stopDie(); g.moves = LU.legal(g.pos, s, g.dice);
    if (!g.moves.length) {
      g.busy = false; g.msg = W.nomoveTv(nm(t, s), g.dice); t.changed();
      later(1500, () => endTurn(t, s));                          // the phone may tap Pass sooner
    } else if (g.moves.length === 1 && t.human(s)) {              // one choice: no need to ask
      g.msg = W.got(nm(t, s), g.dice); t.changed();
      later(450, () => move(t, s, g.moves[0]));
    } else { g.busy = false; g.msg = W.got(nm(t, s), g.dice); t.turnTo(s); }
  });
}
function endTurn(t, s) {
  g.gen++; g.phase = 'roll'; g.busy = false; g.moves = []; g.msg = '';
  t.turnTo((s + 1) % 4);
}
function move(t, s, k) {
  if (k == null || k < 0 || !g.moves.includes(k)) return;
  const d = g.dice, r = LU.apply(g.pos, s, k, d);
  g.moves = []; g.busy = true; g.msg = '';
  const steps = LU.path(r.from, r.to).map((p) => where(s, k, p));
  const e = toks[s][k]; g.walking = { s, k }; e.classList.add('walk'); if (r.from < 0) e.style.transitionDuration = '.3s';
  t.changed();
  const walk = (i) => {
    if (i < steps.length) { Sfx.play('move'); place(s, k, ...steps[i]); later(r.from < 0 ? 320 : STEP, () => walk(i + 1)); return; }
    e.classList.remove('walk'); e.style.transitionDuration = ''; g.walking = null; g.shown = clone(g.pos);
    e.classList.remove('land'); void e.offsetWidth; e.classList.add('land');
    const who = nm(t, s);
    if (r.kicked.length) {
      const [vs] = r.kicked[0]; g.msg = W.kick(who, nm(t, vs)); Sfx.play('hit', 1); t.buzz(s, 200); r.kicked.forEach(([ks]) => t.buzz(ks, 300));
    } else if (r.home) { g.msg = W.home(who); Sfx.play('score'); }
    else if (r.from < 0) { g.msg = W.out(who); Sfx.play('pickup'); }
    drawToks(t); draw(t);
    later(r.kicked.length ? 900 : 350, () => {
      if (LU.done(g.pos, s)) { finish(t, s); return; }
      g.phase = 'roll'; g.busy = false;
      if (d === 6) { g.msg = W.six(who); t.turnTo(s); } else t.turnTo((s + 1) % 4);
    });
  };
  walk(0);
}
function finish(t, s) {
  const rest = [0, 1, 2, 3].filter((x) => x !== s).sort((a, b) => LU.total(g.pos, b) - LU.total(g.pos, a));
  g.busy = true; g.msg = W.won(nm(t, s)); t.over([s].concat(rest));
}

// ---- the phones
function view(t, s) {
  const me = s === t.turn && !t.done;
  if (t.done) return { cards: { hand: [], note: t.order.indexOf(s) >= 0 ? W.place[t.order.indexOf(s)] : '', turn: false, acts: [{ id: 'again', t: W.again, main: true }] } };
  let note, acts;
  if (!me) { note = W.wait(nm(t, t.turn)); acts = [{ id: 'roll', t: W.roll, s: W.rs, off: true }]; }
  else if (g.phase === 'roll') { note = W.yourRoll; acts = [{ id: 'roll', t: W.roll, s: W.rs, main: true, off: g.busy }]; }
  else if (g.rolling) { note = W.rolling; acts = [{ id: 'roll', t: W.roll, s: W.rs, off: true }]; }
  else if (!g.moves.length && !g.busy) { note = W.nomove(g.dice); acts = [{ id: 'pass', t: W.pass, main: true }]; }
  else { note = g.busy ? W.going : W.pick(g.dice); acts = [0, 1, 2, 3].map((k) => ({ id: 'm' + k, t: W.horse(k + 1), i: '🐴', main: !g.busy && g.moves.includes(k), off: g.busy || !g.moves.includes(k) })); }
  return { cards: { hand: [], note, turn: me && !g.busy, acts } };
}

// ---- the TV
function clock(t) { const k = document.querySelector('.seat.turn .timer i'); if (k) k.style.width = Math.max(0, t.left / TURN * 100) + '%'; }
// the window's size from clientWidth/Height: innerWidth/innerHeight grow in a TV's WebView once the 1280 px stage overflows it
function fit() { const d = document.documentElement, w = d.clientWidth || innerWidth, h = d.clientHeight || innerHeight; const k = Math.min(w / 1280, h / 720); const st = $('#stage'); st.style.transform = `scale(${k})`; st.style.left = (w - 1280 * k) / 2 + 'px'; st.style.top = (h - 720 * k) / 2 + 'px'; }
function draw(t) {
  if (!t.ok) {
    const e = h('div'); e.id = 'err'; e.append(h('b', '', t.pad ? W.old : W.nopad), h('span', '', t.pad ? W.oldh : '')); $('#stage').replaceChildren(e); return;
  }
  const cur = t.done ? -1 : t.turn;
  $('#die').style.setProperty('--pc', cur >= 0 ? Table.COLORS[cur] : '#888');
  if (!g.rolling) { $('#die').classList.toggle('idle', cur < 0 || g.phase === 'roll' || !g.dice); if (g.dice && g.face !== g.dice) face(g.dice); }
  const msg = $('#msg');
  if (t.done) msg.replaceChildren(h('b', '', g.msg));
  else if (g.busy || g.phase === 'move' || g.msg) msg.replaceChildren(h('b', '', g.msg));
  else msg.replaceChildren(h('b', '', W.rollsOf(nm(t, t.turn))));
  const order = t.done ? t.order : [];
  $('#seats').replaceChildren(...t.seats.map((s) => {
    const e = h('div', 'seat' + (s === cur ? ' turn' : '')), r = order.indexOf(s);
    e.style.setProperty('--pc', Table.COLORS[s]);
    const hm = h('div', 'home'), ps = g.shown[s];
    ps.forEach((p) => hm.append(h('i', p === LU.END ? 'in' : p >= 0 ? 'out' : '')));
    hm.append(h('em', '', ps.filter((p) => p === LU.END).length + '/4 ' + W.homeOf));
    e.append(h('span', 'dot'), h('div', 'name', (t.human(s) ? '' : '🤖 ') + nm(t, s)), h('div', 'tot', W.total + ' ' + (t.scores[s] > 0 ? '+' : '') + t.scores[s]), hm);
    if (r >= 0) e.append(h('span', 'place', W.place[r]));
    if (s === cur && t.human(s)) { const k = h('div', 'timer'); k.append(h('i')); k.firstChild.style.width = (t.left / TURN * 100) + '%'; e.append(k); }
    return e;
  }));
  drawToks(t);
  const ov = $('#over');
  ov.hidden = !t.done || !t.games || !!g.walking;
  if (!ov.hidden) {
    ov.replaceChildren(h('h1', '', '🏆 ' + W.win(nm(t, t.order[0]))), ...t.order.map((s, r) => {
      const e = h('div', 'row' + (r ? '' : ' win')), p = t.points(r); e.style.setProperty('--pc', Table.COLORS[s]);
      e.append(h('span', 'pl', W.place[r]), h('span', 'dot'), h('span', 'nm', nm(t, s)), h('span', 'pt', (p > 0 ? '+' : '') + p + ' ' + W.pts), h('span', 'tt', String(t.scores[s])));
      return e;
    }), h('p', '', W.newg));
  }
}
(function init() {
  const die = $('#die'); for (let i = 0; i < 9; i++) die.append(h('i'));
  fit(); addEventListener('resize', fit); addEventListener('load', fit);
  if (!table.ok) { draw(table); return; }
  paintBoard(); buildToks(); face(6);
  table.go();
})();
