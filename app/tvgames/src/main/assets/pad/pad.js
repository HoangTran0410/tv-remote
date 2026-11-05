'use strict';
const K = new URLSearchParams(location.search).get('k') || '';
// W (the words) comes from words.js: app/shared/i18n/apps/<lang>/pad.json, picked by the phone's language
const COLORS = ['#3b82f6', '#ef4444', '#22c55e', '#eab308'];
const $ = (s) => document.querySelector(s);
const store = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} },
};
let cid = store.get('tvg-cid') || '';
if (!cid) { cid = Math.random().toString(36).slice(2, 10) + Date.now().toString(36); store.set('tvg-cid', cid); }
$('#pF').textContent = W.pf; $('#pS').textContent = W.ps; $('#pC').textContent = W.pr; $('#tpHint').textContent = W.ph; $('#nmOk').textContent = W.sv; $('#nmNo').setAttribute('aria-label', W.cx); $('#lblHaptic').textContent = W.h; $('#lblAdd').textContent = W.a; $('#lblReload').textContent = W.rl; $('#tipText').textContent = W.t;
$('#lblBack').textContent = W.b; $('#lblHome').textContent = W.g; $('#shTitle').textContent = W.t; $('#sh1').textContent = W.s1; $('#sh2').textContent = W.s2; $('#sh3').textContent = W.s3; $('#fs').setAttribute('aria-label', W.fs); $('#send').textContent = W.s; $('#lblStick').textContent = W.k;
$('#lobbyHint').textContent = W.lb; $('#countLbl').textContent = W.cl; $('#offTitle').textContent = W.rs; $('#offHint').textContent = W.rsh; $('#nm').placeholder = W.nm; $('#nm').maxLength = 40;
$('#awayTitle').textContent = W.aw; $('#awayHint').textContent = W.awh; $('#reopen').textContent = W.awo;
$('#add').href = 'add?k=' + encodeURIComponent(K);

// ---- haptics: vibrate() where there is one, the iOS 17.4+ checkbox-switch tick elsewhere
let haptics = store.get('tvg-haptic') !== '0';
const iosTick = !('vibrate' in navigator) && typeof CSS !== 'undefined' && CSS.supports && CSS.supports('-webkit-touch-callout', 'none');
// Android Chrome: navigator.vibrate() needs a user gesture (every call here is inside a touchstart handler) and the phone's own "Touch
// feedback" / "Vibration on touch" setting ON: with it off Chrome says true but the system drops the pulse (measured on a POCO, HyperOS:
// vibrator_manager "ignored_for_settings"). A page cannot override that. iPhone has no vibrate(): the hidden switch is toggled with a
// click() (iOS 17.4+ ticks for a switch), which Safari only honours inside a real tap's own event, so the ticks may be missed on a fast
// multi-touch; nothing more can be done from a web page. Pulses are 15 ms: shorter ones are lost on many phones' motors.
function buzz(ms) {
  if (!haptics) return;
  if ('vibrate' in navigator) navigator.vibrate(ms ? Math.max(ms, 15) : 15);
  else if (iosTick) { try { $('#ios').click(); } catch (e) {} }
}
function setHaptic(on) { haptics = on; store.set('tvg-haptic', on ? '1' : '0'); $('#sw').classList.toggle('on', on); }
setHaptic(haptics);

// ---- the player's name: kept here (localStorage) and sent with every hello, so the same phone is the same player after a reconnect
// or after TV Games was closed and opened again; tapping the chip edits it. Empty = "Player N". Max 16 characters (emoji count one).
let myName = store.get('tvg-name') || '', mySlot = 0;
const clean = (v) => [...String(v || '').replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, ' ').replace(/\s+/g, ' ').trim()].slice(0, 16).join('').trim();
function setName(v, tell) {
  myName = clean(v); store.set('tvg-name', myName);
  if (tell) send('name ' + myName);
  show(mySlot);
}
function editName(on) {
  $('#who').hidden = on; $('#nameform').hidden = !on; $('#top').classList.toggle('editing', on);
  if (on) { $('#nm').value = myName; try { $('#nm').focus(); $('#nm').select(); } catch (e) {} }
}
// Save / Cancel buttons (a keyboard's Done is missing on some phones): they act on touchstart, like the other actions, and keep the
// field focused (no blur first); tapping anywhere else blurs the field, which saves, so a typed name is never lost. Escape cancels.
function nameSave() { if ($('#nameform').hidden) return; setName($('#nm').value, true); editName(false); buzz(12); }
function nameCancel() { if ($('#nameform').hidden) return; editName(false); }
[['#nmOk', nameSave], ['#nmNo', nameCancel]].forEach(([sel, fn]) => {
  const b = $(sel);
  b.addEventListener('touchstart', (e) => { e.preventDefault(); fn(); }, { passive: false });
  b.addEventListener('mousedown', (e) => e.preventDefault());
  b.addEventListener('click', (e) => { e.preventDefault(); fn(); });
});
$('#nm').addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.preventDefault(); nameCancel(); } });
$('#nameform').addEventListener('submit', (e) => { e.preventDefault(); setName($('#nm').value, true); editName(false); buzz(12); });
$('#nm').addEventListener('blur', () => { if (!$('#nameform').hidden) { setName($('#nm').value, true); editName(false); } });

// ---- connection
let away = false, canOpen = false, closed = false, first_hello = true, ws = null, retry = 150, layout = null, sysName = 'nes', mode = 'nav', lastPong = 0, gaveUp = false, downSince = 0, replaced = false, quick = [];
// Two modes: the pad (a game is running) and navigate (a remote for TV Games itself: library, start / pause cards). It follows
// the TV (a game starts -> pad, back to the library -> navigate); the 🎮 / 🧭 button flips it by hand.
function setMode(m) {
  mode = m; document.body.classList.toggle('nav', m === 'nav');
  $('#mode').textContent = m === 'nav' ? '🎮' : '🧭';
  releaseAll(); relayout(); lockLandscape();
}

// What the TV is running decides the pad: nes / snes / gba / pce / sms / md / ps (a system's `pad` in Systems.kt) or web (the look of ps).
// A web game may then change it (tvpad.layout): {choices:[..]} four big answer buttons, {text:true} a field + Send,
// {buttons:[..]} only these, {dpad:false} no D-pad / stick, {cards:{..}} a hand of cards (renderCards), {pointer:true} the touchpad
// (+ side). null = the usual one.
const SYS = {
  nes:  { stick: false, face: 'pair',    sh: [[], []],                    mid: ['SELECT', 'START'] },
  snes: { stick: false, face: 'diamond', sh: [['L'], ['R']],              mid: ['SELECT', 'START'] },
  gba:  { stick: false, face: 'pair',    sh: [['L'], ['R']],              mid: ['SELECT', 'START'] },
  arcade: { stick: false, face: 'diamond', sh: [['L'], ['R']], mid: ['SELECT', 'START'] },
  ps:   { stick: true,  face: 'diamond', sh: [['L2', 'L'], ['R', 'R2']],  mid: ['SELECT', 'START'] },
  pce:  { stick: false, face: 'pair',    sh: [[], []],                    mid: ['SELECT', 'START'] },   // II I, Select, Run
  sms:  { stick: false, face: 'pair',    sh: [[], []],                    mid: ['START'] },             // 1 2 (Game Gear: Start)
  md:   { stick: false, face: 'tri',     sh: [[], ['X', 'Y', 'Z']],       mid: ['SELECT', 'START'] },   // A B C, X Y Z above, Mode
};
SYS.web = SYS.ps;
const FACE = { pair: ['B', 'A'], diamond: ['X', 'A', 'B', 'Y'], tri: ['A', 'B', 'C'] };
const SKIN = {                                                // face colours, per look
  snes: { X: '#3b6fe0', Y: '#2fa05a', A: '#e5484d', B: '#e0a100' },
  ps: { X: '#2fa05a', Y: '#d65aa8', A: '#e5484d', B: '#3b6fe0' },
};
const GLYPH = { arcade: { SELECT: 'Coin' }, pce: { B: 'II', A: 'I', START: 'Run' }, sms: { B: '1', A: '2' }, md: { SELECT: 'Mode' }, ps: { X: '△', Y: '□', A: '○', B: '✕', L: 'L1', R: 'R1' } };
const look = () => (sysName === 'web' ? 'ps' : SYS[sysName] ? sysName : 'ps');
let stickOn = false;
const stickKey = () => 'tvg-stick-' + look();
function wantStick() { const v = store.get(stickKey()); return v == null ? SYS[look()].stick : v === '1'; }
function mkBtn(cls, b) {
  const e = document.createElement('button'); e.className = cls; e.dataset.b = b;
  e.textContent = (GLYPH[look()] && GLYPH[look()][b]) || { SELECT: 'Select', START: 'Start' }[b] || b;
  const c = SKIN[look()] && SKIN[look()][b]; if (c && cls.includes('fb')) e.style.setProperty('--bc', c);
  return e;
}
// {pointer, aspect: w/h}: the touchpad takes the shape of what it steers (air hockey: the player's half, near square), else a long
// strip on a phone held sideways makes a finger's path look unlike the mallet's
let tpAspect = 0;
function fitTpad() {
  const el = $('#tpad'); el.style.width = el.style.height = el.style.alignSelf = el.style.justifySelf = '';
  if (!tpAspect || !document.body.classList.contains('ptr')) return;
  const r = el.getBoundingClientRect(); if (!r.width || !r.height) return;   // the grid cell it would fill
  const w = Math.min(r.width, r.height * tpAspect);
  el.style.width = w + 'px'; el.style.height = w / tpAspect + 'px'; el.style.alignSelf = el.style.justifySelf = 'center';
}
window.addEventListener('resize', () => setTimeout(fitTpad, 60));
function relayout() {
  const l = layout || {}, base = SYS[look()];
  const ch = Array.isArray(l.choices) && l.choices.length ? l.choices.filter((x) => 'ABCD'.includes(x)) : null;
  document.body.classList.toggle('ptr', !!l.pointer && mode === 'pad'); $('#pS').hidden = Array.isArray(l.buttons) && !l.buttons.includes('B'); if (!l.pointer) tpId = null;
  // {pointer, side:'left'|'right'}: the touchpad on that half, the side the player's own piece is on the TV (air hockey); no A nor B: no buttons
  $('#pF').hidden = Array.isArray(l.buttons) && !l.buttons.includes('A'); $('#pbtn').hidden = $('#pF').hidden && $('#pS').hidden;
  $('#ptr').dataset.side = l.side === 'left' || l.side === 'right' ? l.side : '';
  gyroSync();
  tpAspect = l.pointer && +l.aspect > 0.2 && +l.aspect < 5 ? +l.aspect : 0; fitTpad();
  $('#choices').hidden = !ch; $('#textrow').hidden = !l.text;
  const cd = l.cards && Array.isArray(l.cards.hand) ? l.cards : null;
  document.body.classList.toggle('cards', !!cd && mode === 'pad'); if (cd) renderCards(cd);
  $('#pad').classList.toggle('hid', !!ch); $('#pad').classList.toggle('txt', !!l.text);
  document.querySelectorAll('#choices [data-b]').forEach((el) => { el.hidden = !ch || !ch.includes(el.dataset.b); });
  const ok = (b) => !Array.isArray(l.buttons) || l.buttons.includes(b);
  const fill = (sel, cls, names) => { const el = $(sel); el.replaceChildren(...names.filter(ok).map((b) => mkBtn(cls, b))); };
  fill('#face', 'btn fb', FACE[base.face]); $('#face').className = base.face;
  fill('#shl', 'btn sb', base.sh[0]); fill('#shr', 'btn sb', base.sh[1]); fill('#mid', 'btn pill', base.mid);
  stickOn = wantStick() && l.dpad !== false && mode === 'pad';
  $('#dpad').classList.toggle('off', stickOn || l.dpad === false);
  $('#sw2').classList.toggle('on', wantStick());
  placeStick(); releaseAll();
}
// ---- the floating stick: a ring that appears where the thumb lands (left half in landscape, upper half in portrait)
const stk = $('#stick'), knob = stk.querySelector('.knob');
let SR = 56, sRest = { x: 0, y: 0 }, sId = null, sOrg = null, lastA = '0 0', lastT = 0, pend = null;
const portrait = () => matchMedia('(orientation:portrait)').matches;
function placeStick() {
  stk.hidden = !stickOn; if (!stickOn) return;
  const r = $('#left').getBoundingClientRect(); SR = Math.max(40, Math.min(64, Math.round(r.width * 0.4)));
  sRest = { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  stk.style.width = stk.style.height = SR * 2 + 'px'; moveStick(sRest.x, sRest.y, 0, 0); stk.style.opacity = sId == null ? '.55' : '1';
}
function moveStick(cx, cy, dx, dy) {
  stk.style.left = cx - SR + 'px'; stk.style.top = cy - SR + 'px';
  knob.style.transform = 'translate(' + dx + 'px,' + dy + 'px)';
}
let sDir = '';
function stickTick(ix, iy) {                                  // one tick when the stick enters a new one of 8 directions (or the edge), not while it moves
  const m = Math.hypot(ix, iy), d = m < 35 ? '' : ['E', 'SE', 'S', 'SW', 'W', 'NW', 'N', 'NE'][Math.round(Math.atan2(iy, ix) / (Math.PI / 4) + 8) % 8];
  if (d !== sDir) { sDir = d; if (d) buzz(15); }
}
function sendAxis(ix, iy, force) {
  stickTick(ix, iy); pingIfStale();
  const v = ix + ' ' + iy; if (v === lastA) return;
  const now = performance.now();
  if (!force && now - lastT < 16) { clearTimeout(pend); pend = setTimeout(() => sendAxis(ix, iy, true), 16 - (now - lastT)); return; }
  clearTimeout(pend); lastA = v; lastT = now; send('a ' + v);
}
function stickStart(t) { sId = t.identifier; sOrg = { x: t.clientX, y: t.clientY }; stk.classList.add('on'); stk.style.opacity = '1'; buzz(8); moveStick(sOrg.x, sOrg.y, 0, 0); }
function stickMove(t) {
  let dx = t.clientX - sOrg.x, dy = t.clientY - sOrg.y; const d = Math.hypot(dx, dy);
  if (d > SR) { dx *= SR / d; dy *= SR / d; }
  moveStick(sOrg.x, sOrg.y, dx, dy); sendAxis(Math.round(dx / SR * 100), Math.round(dy / SR * 100));
}
function stickEnd() { if (sId == null) return; sId = null; sDir = ''; stk.classList.remove('on'); sendAxis(0, 0, true); placeStick(); }
window.addEventListener('resize', () => setTimeout(placeStick, 50));
// ---- the lobby (before a game starts): the TV shows the seats, the pad shows a big Ready toggle; buttons pressed here only light the seat on the TV
let lobbyOn = false, iReady = false, lobbyMax = 4;
function syncReady() { const b = $('#ready'); b.textContent = iReady ? W.rdOn : W.rd; b.classList.toggle('on', iReady); }
function setLobby(on, ready, max) {
  lobbyOn = !!on; iReady = lobbyOn && !!ready; if (max) lobbyMax = max;
  const spec = lobbyOn && mySlot > lobbyMax;                    // more phones than the game has seats: this one watches
  document.body.classList.toggle('lobby', lobbyOn); $('#lobbybar').hidden = !lobbyOn;
  $('#ready').hidden = spec; $('#lobbyHint').textContent = spec ? W.sp.replace('{n}', lobbyMax) : W.lb;
  syncReady(); showCount(0);
}
function showCount(n) { $('#count').hidden = !n; $('#countN').textContent = n || ''; if (n) buzz(20); }
function say(t) { const e = $('#say'); e.textContent = t; e.hidden = !t; clearTimeout(say.t); if (t) say.t = setTimeout(() => { e.hidden = true; }, 6000); }
const held = new Set();
function send(s) { if (ws && ws.readyState === 1) ws.send(s); }
// away: the TV is on another screen (the process and this link live on, but no TV Games screen takes the presses); closed: TV Games quit
function setAway(on, open) {
  away = !!on; canOpen = !!open;
  document.body.classList.toggle('away', away || closed);
  $('#away').hidden = !(away || closed);
  $('#awayTitle').textContent = closed ? W.cs : W.aw;
  $('#awayHint').textContent = closed ? W.csh : W.awh;
  $('#reopen').hidden = closed || !canOpen;
  if (!closed && !canOpen) $('#awayHint').textContent = W.awr;
  if (away || closed) { $('#ms').textContent = '—'; $('#ms').className = ''; for (const b of Array.from(held)) press(b, false); }
}
function show(slot) {
  const who = $('#who');
  mySlot = slot > 0 ? slot : mySlot;
  document.body.classList.toggle('offline', slot === 0 && mySlot > 0);
  who.classList.toggle('me', slot > 0);
  if (slot > 0) {
    who.textContent = myName || (W.p + ' ' + slot);
    const r = document.documentElement.style; r.setProperty('--c', COLORS[slot - 1]); r.setProperty('--glow', COLORS[slot - 1] + '77');
  } else who.textContent = slot === -1 ? W.f : mySlot > 0 ? W.rc : W.c;
}
// the TV said "scan again" (new connection code): stop trying, show it
// Two tabs (or two Chrome windows) of ONE phone share the player id, and the TV hands the seat to the newest, so each kick makes the other
// reconnect and kick back: a loop of two sockets every half second (seen on a POCO with 3 pad tabs). The TV now says {replaced} to the
// one it drops; this page then waits for a tap. Against an older TV the same loop is caught here: 3 links that lived under 3 s in a row.
function replacedBy(w = ws, kicked = false) {                  // w: the socket the TV dropped, not a newer one of this page
  replaced = true; $('#offTitle').textContent = kicked ? W.kk : W.rp; $('#offHint').textContent = kicked ? W.kkh : W.rph; $('#off').dataset.act = 'take'; $('#off').hidden = false;
  try { w.onclose = null; w.close(); } catch (e) {}
}
function rescan() { gaveUp = true; delete $('#off').dataset.act; $('#off').hidden = false; try { ws.close(); } catch (e) {} }
// after a drop: ask the server with a plain request why: 403 = the code changed (rescan), anything else = just try again
function lost() {
  if (gaveUp || replaced) return;
  if (closed || away) quick = [];
  else if (opened && Date.now() - opened < 3000) { quick.push(1); if (quick.length >= 3) { replacedBy(); return; } } else quick = [];
  if (away) setAway(false, false);                              // the link broke while the TV was away: now it is a plain reconnect
  $('#ms').textContent = '—'; $('#ms').className = '';
  show(0);
  if (!downSince) downSince = Date.now();
  // force-stop (a time limit, the TV's task killer) leaves no goodbye: a seated pad whose link is still down 1.2 s later says "closed" now
  // and keeps retrying quietly; the hello answer clears the card
  if (mySlot > 0 && !closed && !closeTimer) closeTimer = setTimeout(() => { closeTimer = 0; if (downSince && !gaveUp && !replaced) { closed = true; setAway(false, false); } }, 1200);
  if (downSince && Date.now() - downSince > 15000 && mySlot > 0) say(W.rch);
  fetch('/layouts.json?k=' + encodeURIComponent(K), { cache: 'no-store' }).then((r) => { if (r.status === 403) rescan(); else again(); }).catch(again);
}
function again() { if (gaveUp || replaced) return; if (document.hidden) return;     // a background tab reconnects when it is shown again
  setTimeout(connect, retry); retry = Math.min(retry * 1.6, 3000); }   // also while "closed": back within 3 s of TV Games opening
let opened = 0, closeTimer = 0;
function connect() {
  if (gaveUp || replaced) return;
  if (ws && ws.readyState <= 1) return;                       // shown again while a reconnect was on its way: two sockets for one phone kicked each other
  show(0);
  ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws?k=' + encodeURIComponent(K));
  // a socket can stay CONNECTING for good, no error, no close (an iPhone's Chrome over https, the TV saw no request at all): the check
  // above then refused every new try and the pad said "Connecting…" until the page was reloaded
  const w = ws; setTimeout(() => { if (w === ws && w.readyState === 0) { w.onclose = null; w.close(); ws = null; lost(); } }, 5000);
  ws.onopen = () => { opened = Date.now(); retry = 150; downSince = 0; lastPong = Date.now(); send('hello ' + cid + (myName ? ' ' + myName : '')); held.forEach((b) => send('d ' + b)); };
  ws.onmessage = (e) => {
    const t = String(e.data);
    if (t[0] === 'p') { pong(t); return; }
    try {
      const m = JSON.parse(t);
      if (m.rescan) { rescan(); return; }
      if (m.kicked) { replacedBy(e.target, true); return; }       // sent out from the TV's lobby: back only on a tap
      if (m.replaced) { if (e.target === ws) replacedBy(e.target); else { e.target.onclose = null; e.target.close(); } return; }   // an older socket of this page: just closed
      if ('away' in m && !('slot' in m)) { setAway(m.away, m.open); return; }
      if (m.closed) { closed = true; setAway(false, false); return; }
      if (m.full) { show(-1); return; }
      if ('sys' in m && !('slot' in m)) { sysName = m.sys; layout = null; relayout(); return; }
      if ('game' in m && !('slot' in m)) { setMode(m.game ? 'pad' : 'nav'); $('#add').hidden = !!m.game; if (!m.game) setLobby(false); return; }
      if ('web' in m && !('slot' in m)) { $('#reload').hidden = !m.web; return; }
      if ('lobby' in m && !('slot' in m)) { setLobby(m.lobby, false, m.max); return; }
      if ('count' in m) { showCount(m.count); return; }
      if ('layout' in m) { layout = m.layout; relayout(); return; }
      if ('say' in m) { say(m.say); return; }
      if ('vibrate' in m) { if (haptics && 'vibrate' in navigator) navigator.vibrate(m.vibrate); else buzz(0); return; }
      if (m.name && !myName) { myName = clean(m.name); store.set('tvg-name', myName); }      // the TV remembers a name this phone lost
      else if (myName && m.name !== myName) send('name ' + myName);
      closed = false; setAway(false, false);                       // a hello answer: the TV is back; an "away" message may follow at once
      show(m.slot); setLobby(m.lobby, m.ready, m.max);
      if (haptics && 'vibrate' in navigator) navigator.vibrate([30, 40, 30]); else buzz(0);   // a stronger one: the TV says the slot is yours
      $('#add').hidden = !!m.game; $('#reload').hidden = !m.web;
      if (m.sys) sysName = m.sys; layout = null; say('');
      if (first_hello || (mode === 'nav') === !!m.game) setMode(m.game ? 'pad' : 'nav'); else relayout();
      first_hello = false;
    } catch (x) {}
  };
  ws.onclose = lost;
  ws.onerror = () => { try { ws.close(); } catch (x) {} };
}
// ---- ping: a stamp out, the TV's reply carries it back with the TV's clock; median of the last 5 (pingmath.js), shown small at the top
const trk = new PingMath.Tracker(5);
function pong(t) {
  const [, t0, tv] = t.split(' '), now = Date.now(); lastPong = now;
  if (!(Number(t0) > 0)) return;
  trk.add(Number(t0), now, tv == null ? null : Number(tv));
  const p = trk.ping(), el = $('#ms'); window.__rtt = p;
  if (away || closed) return;                                  // the card says it; the chip stays "—" instead of flashing a number under it
  el.textContent = p + ' ms'; el.className = PingMath.level(p); el.classList.remove('tk'); void el.offsetWidth; el.classList.add('tk');   // tk: a small blink on each answer
  send('r ' + p + ' ' + (trk.offset() == null ? 0 : trk.offset()));
  window.__off = trk.offset();
}
let lastPing = 0;
function pingNow() { lastPing = Date.now(); send('p ' + lastPing); }
function pingIfStale() { if (Date.now() - lastPing > 700) pingNow(); }       // any input, the stick's 60 moves a second included, keeps the chip live
// A page the phone froze (iPhone: screen dimmed or locked, another app in front, Low Power Mode) stops its timers AND cannot read its socket, so
// when it wakes "no answer for 5 s" is true of the page, not of the link: closing then dropped a healthy connection on every wake-up (the
// "often drops" of the iPhone, each one a new hello and a seat re-take). A gap between two ticks says it slept: give the link a fresh window
// and a ping now; only a link that stays silent after that is dead. 8 s, not 5: a Wi-Fi in power save or a busy TV can hold an answer back.
let lastTick = Date.now();
function alive() {                                              // the page is awake again: is the socket still good?
  lastTick = Date.now();
  if (!ws || ws.readyState > 1) { if (!gaveUp && !replaced) { retry = 150; connect(); } return; }
  lastPong = Date.now(); pingNow();                             // silent for 3 s after this one = dead (below)
  clearTimeout(alive.t); alive.t = setTimeout(() => { if (ws && ws.readyState === 1 && Date.now() - lastPong > 2900) dead(); }, 3000);
}
function dead() { const w = ws; if (w) { w.onclose = null; try { w.close(); } catch (e) {} } lost(); }     // a link that died without a close (Wi-Fi gone, TV off)
setInterval(() => {
  const now = Date.now(), slept = now - lastTick > 2500; lastTick = now;
  if (slept) { alive(); return; }
  pingNow();
  if (ws && ws.readyState === 1 && now - lastPong > 8000) dead();
}, 1000);
// the phone woke up: do not wait for the next retry. pageshow: Safari's back-forward cache gives the page back with a dead socket and no
// visibilitychange; online: Wi-Fi came back; focus: an iPhone's tab switch
document.addEventListener('visibilitychange', () => { if (!document.hidden && !gaveUp && !replaced) alive(); });
// the tab closed (not hidden, not kept in the back cache): the seat is free at once instead of kept 30 s for a reconnect
window.addEventListener('pagehide', (e) => { if (!e.persisted && ws && ws.readyState === 1) try { ws.send('bye'); } catch (er) {} });
window.addEventListener('pageshow', () => { if (!gaveUp && !replaced) alive(); });
window.addEventListener('online', () => { if (!gaveUp && !replaced) alive(); });
window.addEventListener('focus', () => { if (!gaveUp && !replaced && !document.hidden) alive(); });

// ---- presses
function press(b, on) {
  if (on === held.has(b)) return;
  if (on) { held.add(b); buzz(12); } else held.delete(b);
  send((on ? 'd ' : 'u ') + b + ' ' + Date.now());               // the stamp lets the TV measure the delay of this press
  document.querySelectorAll('[data-b="' + b + '"]').forEach((el) => el.classList.toggle('on', on));
  const arm = { UP: 'u', DOWN: 'd', LEFT: 'l', RIGHT: 'r' }[b];
  if (arm && !dp.classList.contains('off')) $('.arm.' + arm).classList.toggle('on', on);
}
function releaseAll() { [...navT.keys()].forEach((id) => navUp(id, true)); touches.clear(); stickEnd(); [...held].forEach((b) => press(b, false)); }

// Every finger is followed by its identifier; on each move what it is over is looked up again, so a thumb can slide A -> B or
// across the d-pad's arms without lifting. A d-pad finger near a corner holds two arms.
const touches = new Map();           // identifier -> Set of button names
const dp = $('#dpad');
function under(x, y) {
  const out = new Set();
  const r = dp.getBoundingClientRect();
  if (!dp.classList.contains('off') && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
    const nx = (x - r.left - r.width / 2) / (r.width / 2), ny = (y - r.top - r.height / 2) / (r.height / 2);
    if (Math.hypot(nx, ny) >= 0.2) {
      if (nx < -0.34) out.add('LEFT'); else if (nx > 0.34) out.add('RIGHT');
      if (ny < -0.34) out.add('UP'); else if (ny > 0.34) out.add('DOWN');
    }
    return out;
  }
  const el = document.elementFromPoint(x, y), b = el && el.closest && el.closest('[data-b]');
  if (b && !b.hidden) out.add(b.dataset.b);
  return out;
}
function apply() {
  const all = new Set(); touches.forEach((s) => s.forEach((x) => all.add(x)));
  ['UP', 'DOWN', 'LEFT', 'RIGHT', 'A', 'B', 'C', 'D', 'X', 'Y', 'L', 'R', 'L2', 'R2', 'START', 'SELECT'].forEach((b) => press(b, all.has(b)));
}

// ---- navigate mode: a tap sends "n NAME" (the TV presses that key in TV Games); an arrow held repeats like a remote's
const navT = new Map();      // touch id -> { el, timer }
function navSend(el) { send('n ' + el.dataset.n); }
// OK goes on release: held 500 ms it is "hold OK" instead (a tile's menu: hide, remove…), as on the TV's own remote; a buzz says it took
function navDown(id, el) {
  el.classList.add('on'); buzz(12);
  const rec = { el, timer: null, ok: el.dataset.n === 'OK', held: false };
  if (rec.ok) rec.timer = setTimeout(() => { rec.held = true; buzz(40); send('n HOLD'); }, 500);
  else navSend(el);
  if (['UP', 'DOWN', 'LEFT', 'RIGHT'].includes(el.dataset.n)) {
    rec.timer = setTimeout(() => { rec.timer = setInterval(() => navSend(el), 120); }, 380);
  }
  navT.set(id, rec);
}
function navUp(id, cancel) {
  const r = navT.get(id); if (!r) return; clearTimeout(r.timer); clearInterval(r.timer); r.el.classList.remove('on'); navT.delete(id);
  if (r.ok && !r.held && !cancel) navSend(r.el);   // a cancelled touch (a call, the page hidden) presses nothing
}

// ---- actions (gear, haptics, add, tip) run on touchstart, not on click
const ACT = {
  fs() { toggleFs(); buzz(12); },
  sheetx() { $('#sheet').hidden = true; },
  tiltGo() { location.href = 'https://' + location.hostname + ':' + TLS + '/?k=' + encodeURIComponent(K); },
  tiltNo() { $('#tiltSheet').hidden = true; },
  mode() { setMode(mode === 'nav' ? 'pad' : 'nav'); buzz(12); },
  who() { if (mySlot > 0) editName(true); },
  gear() { const m = $('#menu'); m.hidden = !m.hidden; },
  haptic() { setHaptic(!haptics); buzz(12); },
  add(el) { location.href = el.href; },
  reload() { send('reload'); $('#menu').hidden = true; buzz(12); },   // the TV fetches the page again, past its cache
  stick() { const on = !wantStick(); store.set(stickKey(), on ? '1' : '0'); relayout(); buzz(12); },
  ready() { if (!lobbyOn) return; iReady = !iReady; send('ready ' + (iReady ? 1 : 0)); syncReady(); buzz(iReady ? 20 : 10); },
  reopen() { send('open'); buzz(20); $('#reopen').disabled = true; setTimeout(() => { $('#reopen').disabled = false; }, 2000); },
  take() { if (!replaced) return; replaced = false; quick = []; delete $('#off').dataset.act; $('#off').hidden = true; retry = 150; connect(); },
  recenter() { send('pc'); buzz(15); },
  gyro() { gyroTap(); },
  tipx() { $('#tip').hidden = true; store.set('tvg-tip', '1'); },
};
function act(el) { const a = el.closest('[data-act]'); if (a) { ACT[a.dataset.act](a); return true; } return false; }
document.addEventListener('click', (e) => { const a = e.target.closest && e.target.closest('[data-act]'); if (a) { e.preventDefault(); if (!a.__t) ACT[a.dataset.act](a); a.__t = false; } });

// ---- first tap: fullscreen, landscape, screen kept awake
let first = true, lock = null;
async function wake() { try { if (navigator.wakeLock && document.visibilityState === 'visible') lock = await navigator.wakeLock.request('screen'); } catch (e) {} }
function firstTap() {
  if (!first) return; first = false;
  if (canFs) { try { const p = enterFs(); if (p && p.catch) p.catch(() => {}); } catch (e) {} }
  wake();
}
// ---- fullscreen button: the Fullscreen API (webkit-prefixed on old Safari / iPad); iPhone Safari has none for pages, so there it shows
// how to add the pad to the Home Screen (the manifest + apple-mobile-web-app-capable make that icon open fullscreen)
const de = document.documentElement;
const canFs = !!(de.requestFullscreen || de.webkitRequestFullscreen);
const fsEl = () => document.fullscreenElement || document.webkitFullscreenElement || null;
const enterFs = () => (de.requestFullscreen ? de.requestFullscreen({ navigationUI: 'hide' }) : de.webkitRequestFullscreen());
const exitFs = () => (document.exitFullscreen ? document.exitFullscreen() : document.webkitExitFullscreen && document.webkitExitFullscreen());
const IC = {
  enter: '<svg viewBox="0 0 24 24"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg>',
  exit: '<svg viewBox="0 0 24 24"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/></svg>',
};
function fsSync() {
  const on = !!fsEl();
  $('#fs').innerHTML = on ? IC.exit : IC.enter; $('#fs').dataset.state = on ? 'on' : 'off';
  lockLandscape();
}
// landscape while fullscreen and showing the game's pad; the navigation view keeps whatever the phone is in: unlock() fell back to the
// phone's own default (portrait with auto-rotate off), which threw a landscape player upright when it opened the arrows. Refusal is normal (iOS, desktop)
function lockLandscape() {
  try {
    const o = screen.orientation; if (!o) return;
    const land = /^landscape/.test(o.type || '') || matchMedia('(orientation:landscape)').matches;
    if (fsEl() && (mode === 'pad' || land)) { const p = o.lock && o.lock('landscape'); if (p && p.catch) p.catch(() => {}); }
    else if (o.unlock) o.unlock();
  } catch (e) {}
}
function toggleFs() {
  if (!canFs) { $('#sheet').hidden = false; return; }
  try { const p = fsEl() ? exitFs() : enterFs(); if (p && p.catch) p.catch(() => {}); } catch (e) {}
}
['fullscreenchange', 'webkitfullscreenchange'].forEach((n) => document.addEventListener(n, fsSync));
if (navigator.standalone || matchMedia('(display-mode: fullscreen)').matches || matchMedia('(display-mode: standalone)').matches) $('#fs').hidden = true;   // already opened from the Home Screen
fsSync();
const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
if (ios && !navigator.standalone && !store.get('tvg-tip')) $('#tip').hidden = false;
document.addEventListener('visibilitychange', () => { if (document.hidden) releaseAll(); else wake(); });

// ---- touch: nothing may scroll, zoom or select, with any number of fingers
// ---- cards layout (a card game: Tiến lên ...): the hand is private, so it is drawn here and not on the TV. The game sends
// {cards:{hand:['3s','10h','Qd','2c'], note, sel?, acts:[{id, t, main?, off?}]}} again whenever it changes (a reconnect gets
// the last one); a card may be the game's own {id, t, sub?, bg?, k? corner mark, i? icon, look? 'oval' | 'deed', off? dimmed} (Uno, Boom Cats), an empty hand leaves the acts
// as named buttons (a board game's choices); a tap or a swipe across the cards picks them, an act button answers through the text channel:
// t {"act":id,"cards":[picked]}. Cards are rank (2-10, J, Q, K, A) + suit (s c d h); the game checks what is played.
const SUIT = { s: '♠', c: '♣', d: '♦', h: '♥' };
// a button's icon: the game's own (i), else one for the usual names of acts
const ACT_ICON = { play: '🃏', pass: '⏭️', skip: '⏭️', hint: '💡', draw: '📥', again: '🔄', roll: '🎲', buy: '🛒', build: '🏠', sell: '💸',
  mort: '🏦', end: '🏁', ok: '✅', nope: '🚫', give: '🎁', jailpay: '💰', jailcard: '🎫' };
let cHand = [], cSel = new Set(), cPaint = null, cId = null, cLast = null, cLay = null;
// the cards keep their elements between layouts, so a pick slides (CSS transition on .cd) and a hand that changes moves by FLIP: the cards
// left close up, a card played flies up toward the TV and goes, a new one rises (a drawn one turned over). transform + opacity only.
const cEls = new Map(), CEASE = 'cubic-bezier(.2,.8,.25,1)';
const calm = () => { try { return matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { return false; } };
function cMotion(old, els, ids) {
  if (calm() || !els.length || !els[0].animate) return;
  const first = !old.size, fresh = [];
  els.forEach((e, k) => {                                                             // one read of every place after the DOM was written
    const id = ids[k], r0 = old.get(id), r1 = e.getBoundingClientRect(), tf = getComputedStyle(e).transform, end = tf === 'none' ? 'none' : tf, e0 = end === 'none' ? '' : ' ' + end;
    if (r0 && old.els.get(id) === e) {                                                // stayed: slide from where it was
      const dx = r0.left - r1.left, dy = r0.top - r1.top;
      if (Math.abs(dx) > 1 || Math.abs(dy) > 1) e.animate([{ transform: 'translate(' + dx + 'px,' + dy + 'px)' + e0 }, { transform: end }], { duration: 220, easing: CEASE });
    } else fresh.push([e, e0, end]);
  });
  fresh.forEach((f, i) => {                                                           // new: dealt (all of them, one after another) or drawn (turned over)
    const e = f[0], up = first ? 70 : 120, from = 'translate(0,' + up + 'px) rotate(' + (i % 2 ? 5 : -5) + 'deg) scale(.8)' + f[1];
    if (first) { e.animate([{ transform: from, opacity: 0, easing: CEASE }, { transform: f[2], opacity: 1 }], { duration: 320, delay: i * 30, fill: 'backwards' }); return; }
    const b = document.createElement('cflip'); e.append(b);
    const t = e.animate([{ transform: from, opacity: .6, offset: 0, easing: CEASE }, { transform: f[2], opacity: 1, offset: .62 }, { transform: (f[1] + ' scaleX(.04)').trim(), offset: .8 }, { transform: f[2], offset: 1 }], { duration: 520, delay: i * 90, fill: 'backwards' });
    const u = b.animate([{ opacity: 1, offset: 0 }, { opacity: 1, offset: .79 }, { opacity: 0, offset: .8 }, { opacity: 0, offset: 1 }], { duration: 520, delay: i * 90, fill: 'both' });
    u.onfinish = u.oncancel = () => b.remove(); t.oncancel = () => b.remove();
  });
  let n = 0;
  old.forEach((r, id) => {                                                            // played: up toward the TV and gone
    const e = old.els.get(id); if (cEls.get(id) === e || n >= 8) return;
    e.style.cssText = 'position:fixed;margin:0;left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;--cw:' + r.width + 'px;z-index:30;pointer-events:none;will-change:transform,opacity';
    document.body.append(e);
    const x = e.animate([{ transform: 'none', opacity: 1, easing: CEASE }, { transform: 'translate(0,-' + Math.round(innerHeight * .3) + 'px) rotate(' + (n % 2 ? 8 : -8) + 'deg) scale(.7)', opacity: 0 }], { duration: 300, delay: n++ * 40, fill: 'both' });
    x.onfinish = x.oncancel = () => e.remove(); setTimeout(() => e.remove(), 900);
  });
}
function renderCards(cd, quiet) {
  const same = cHand.join() === cd.hand.join();
  const items = cd.hand.slice(0, 40).map((c) => (c && typeof c === 'object' ? c : { id: String(c) }));   // {id, t, sub, bg}: a card of the game's own (Uno, ...)
  cHand = items.map((c) => String(c.id));
  if (Array.isArray(cd.sel)) cSel = new Set(cd.sel.map(String)); else if (!same) cSel = new Set([...cSel].filter((c) => cHand.includes(c)));
  $('#cnote').textContent = cd.note || ''; $('#cnote').classList.toggle('turn', !!cd.turn);
  const hand = $('#hand'), two = portrait() && cHand.length > 7, cut = two ? Math.ceil(cHand.length / 2) : cHand.length;
  hand.style.setProperty('--n', Math.max(cut, 1)); cLay = cd;          // upright: two rows, so the cards stay big enough to read
  hand.style.setProperty('--ov', items.some((it) => it.t != null) && cut <= 8 && !portrait() ? .04 : .3);   // a few of a game's own cards side by side; more overlap, read by their corners
  const old = new Map(); old.els = new Map();
  if (!quiet) cEls.forEach((e, id) => { if (e.isConnected) { const r = e.getBoundingClientRect(); if (r.width) { old.set(id, r); old.els.set(id, e); } } });
  const els = items.map((it) => {
    const c = String(it.id), sig = JSON.stringify([it.t, it.sub, it.bg, it.k, it.i, it.look]), had = cEls.get(c);
    if (had && had.__sig === sig) {                                       // the same card: keep its element, change its state
      had.classList.toggle('sel', cSel.has(c)); had.classList.toggle('off', !!it.off); return had;
    }
    const e = document.createElement('div'); e.__sig = sig;
    e.dataset.c = c;
    if (it.t != null) {                                                  // the game's own card: big words, a line under, a colour
      e.className = 'cd own' + (cSel.has(c) ? ' sel' : '') + (it.off ? ' off' : '');   // off: cannot be played now (still pickable)
      if (/^#[0-9a-f]{3,8}$/i.test(it.bg || '')) e.style.setProperty('--bg', it.bg);
      const k = String(it.k != null ? it.k : it.i != null && it.look !== 'deed' ? it.i : '').slice(0, 4), t = String(it.t).slice(0, 16), sub = String(it.sub || '').slice(0, 28);
      const add = (tag, x) => { const n = document.createElement(tag); n.textContent = x; e.append(n); return n; };
      if (it.look === 'oval') { e.classList.add('oval'); add('b', ''); add('s', t); }           // Uno's face: the value in a white oval
      else if (it.look === 'deed') { e.classList.add('deed'); add('s', t); if (sub) add('small', sub); if (it.i != null) add('q', String(it.i).slice(0, 10)); }   // a title deed: the name on its colour band
      else { if (it.i != null) add('q', String(it.i).slice(0, 4)); add('s', t); if (sub) add('small', sub); }
      if (k) { add('em', k); add('em', k); }                            // both corners, as on a real card
      return e;
    }
    const su = c.slice(-1), rk = c.slice(0, -1);
    e.className = 'cd' + (su === 'h' || su === 'd' ? ' red' : '') + (cSel.has(c) ? ' sel' : '');
    e.innerHTML = '<b></b><i></i><u></u>'; e.children[0].textContent = rk; e.children[1].textContent = SUIT[su] || su; e.children[2].textContent = SUIT[su] || su;
    return e;
  });
  hand.replaceChildren(...[els.slice(0, cut), els.slice(cut)].filter((r) => r.length).map((r) => { const e = document.createElement('div'); e.className = 'hrow'; e.append(...r); return e; }));
  const ids = items.map((it) => String(it.id));
  cEls.clear(); ids.forEach((id, k) => cEls.set(id, els[k]));
  if (!quiet) cMotion(old, els, ids);
  $('#cacts').replaceChildren(...(Array.isArray(cd.acts) ? cd.acts : []).slice(0, 6).map((a) => {
    const b = document.createElement('button'); b.className = 'ca' + (a.main ? ' main' : ''); b.disabled = !!a.off; b.dataset.ca = a.id;
    const ic = a.i != null ? String(a.i).slice(0, 4) : ACT_ICON[a.id];
    if (ic) { const i = document.createElement('i'); i.textContent = ic; b.append(i); }
    const w = document.createElement('span'); w.textContent = a.t || a.id; b.append(w); return b;
  }));
}
function cardPick(el) {
  const c = el.dataset.c; if (cPaint == null) cPaint = !cSel.has(c);
  if (cPaint === cSel.has(c)) return;
  if (cPaint) cSel.add(c); else cSel.delete(c);
  el.classList.toggle('sel', cPaint); buzz(8);
}
function cardsTouch(e) {
  for (const t of e.changedTouches) {
    const el = document.elementFromPoint(t.clientX, t.clientY);
    if (e.type === 'touchstart') {
      firstTap();
      if (el && el.closest('[data-act]')) return false;                 // the top bar: the usual handling
      const cd = el && el.closest('.cd'), ca = el && el.closest('.ca');
      if (cd && cId == null) { cId = t.identifier; cPaint = null; cLast = cd; cardPick(cd); }
      else if (ca && !ca.disabled) { ca.classList.add('on'); buzz(15); send('t ' + JSON.stringify({ act: ca.dataset.ca, cards: cHand.filter((c) => cSel.has(c)) })); setTimeout(() => ca.classList.remove('on'), 150); }
    } else if (t.identifier === cId) {
      if (e.type === 'touchmove') { const cd = el && el.closest('.cd'); if (cd && cd !== cLast) { cLast = cd; cardPick(cd); } }
      else { cId = null; cPaint = null; cLast = null; }
    }
  }
  return true;
}
document.addEventListener('click', (e) => {                            // a mouse (tests, a tablet with one): the same as a tap
  if (!document.body.classList.contains('cards')) return;
  const cd = e.target.closest && e.target.closest('.cd'), ca = e.target.closest && e.target.closest('.ca');
  if (cd) { cPaint = null; cardPick(cd); cPaint = null; }
  else if (ca && !ca.disabled) send('t ' + JSON.stringify({ act: ca.dataset.ca, cards: cHand.filter((c) => cSel.has(c)) }));
});
// ---- pointer layout (a game that aims: air hockey, shooting gallery ...): the big pad moves a crosshair on the TV by RELATIVE moves, speeded up
// when the finger is fast (like a laptop's touchpad), sent as "m dx dy" at most every 16 ms with the moves added up in between.
// Turning the phone aims too (gyroSync), where the browser gives the gyroscope.
let tpId = null, tpLast = null, tpAcc = { x: 0, y: 0 }, tpTimer = 0;
function tpFlush() {
  tpTimer = 0; const x = Math.round(tpAcc.x), y = Math.round(tpAcc.y); if (!x && !y) return;
  tpAcc.x -= x; tpAcc.y -= y; send('m ' + x + ' ' + y);
}
// ---- tilt: the gyroscope moves the same crosshair, through the same "m dx dy", so a game needs nothing new. Only where the page
// gets it: NOT on this plain http page in Chrome or Brave (no devicemotion at all: a POCO, Chrome 155, 2026-10, while the same page
// over https got readings); Safari asks first (requestPermission, from a tap). Shown once a reading came, Safari can be asked, or the https page opened. Any grip: the turn about the
// vertical (gravity's axis) is left / right, the turn about the screen's horizontal edge is up / down.
const GYRO_GAIN = 900;                                               // "m" units per radian: a wrist's sweep (~40°) crosses the TV
const GYRO_DEAD = 0.02;                                               // rad/s: a hand held still does not crawl
let gyroSeen = false, gyroOn = store.get('tvg-gyro') !== '0', gyroUp = null;
const gyroAsk = typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function';
function gyroLive() { return gyroSeen && gyroOn && document.body.classList.contains('ptr') && !document.hidden; }
// the QR opens plain http (no certificate warning for touch-only players), where no browser gives the gyroscope: Tilt there moves the
// pad to the https port, the warning once (Advanced, Proceed), the seat kept (the TV gives a phone's second page its first one's seat)
const TLS = +(document.querySelector('meta[name="tvg-tls"]') || {}).content || 0;
const gyroMove = !isSecureContext && TLS > 0;
function tiltSheet() {                                              // what the warning page will ask, before it shows up
  const ios = gyroAsk;                                               // requestPermission: WebKit, so iOS's words on its warning page
  $('#tlT').textContent = W.tlT; $('#tlP').textContent = W.tlP; $('#tl1').textContent = W.tl1;
  $('#tl2').textContent = ios ? W.tl2i : W.tl2a; $('#tl3').textContent = ios ? W.tl3i : W.tl3a;
  $('#tlGo').textContent = W.tlGo; $('#tlNo').textContent = W.tlNo; $('#tiltSheet').hidden = false;
}
function gyroSync() {
  const b = $('#pG'), can = gyroSeen || gyroAsk || gyroMove;
  b.hidden = !can; b.textContent = !gyroSeen ? W.gy : gyroOn ? W.gyOn : W.gyOff; b.classList.toggle('on', gyroSeen && gyroOn);
  $('#tpHint').textContent = gyroSeen && gyroOn ? W.phg : W.ph;
}
// iOS (Safari, and Chrome / Edge there: all WebKit) asks only from a real click: from touchstart, or from a touchend whose touchstart
// was preventDefault-ed (every touch of the pad), it never showed its question (an iPhone, iOS 26). So the Tilt chip is left to the
// browser (handle() skips it) and asks in its click; a probe page asking from a button's click got the question and readings
function gyroAskNow() {
  DeviceMotionEvent.requestPermission().then((r) => { if (r === 'granted') { gyroOn = true; store.set('tvg-gyro', '1'); } }).catch(() => {});
  if (typeof DeviceOrientationEvent !== 'undefined' && DeviceOrientationEvent.requestPermission) DeviceOrientationEvent.requestPermission().catch(() => {});
}
function gyroTap() {
  if (!gyroSeen && gyroMove) { tiltSheet(); return; }
  if (!gyroSeen && gyroAsk) { gyroAskNow(); return; }
  gyroOn = !gyroOn; store.set('tvg-gyro', gyroOn ? '1' : '0'); gyroSync(); buzz(12);
}
function screenRight() {                                             // the screen's left-to-right in the phone's own axes
  const a = (screen.orientation && screen.orientation.angle) || +window.orientation || 0;
  return a === 90 ? [0, -1, 0] : a === 270 || a === -90 ? [0, 1, 0] : a === 180 ? [-1, 0, 0] : [1, 0, 0];
}
window.addEventListener('devicemotion', (e) => {
  const r = e.rotationRate, g = e.accelerationIncludingGravity;
  if (!r || r.alpha == null) return;
  if (!gyroSeen) { gyroSeen = true; gyroSync(); }
  if (g && g.x != null) {                                            // up, smoothed: the hand's own jolts are not the vertical
    const n = Math.hypot(g.x, g.y, g.z) || 1, u = [g.x / n, g.y / n, g.z / n];
    gyroUp = gyroUp ? gyroUp.map((v, i) => v * 0.9 + u[i] * 0.1) : u;
  }
  motionNow.r = r; motionNow.g = g; motionOut();
  if (!gyroLive() || !gyroUp) return;
  const D = Math.PI / 180, w = [r.beta * D, r.gamma * D, r.alpha * D];   // rad/s about the phone's x, y, z
  const dt = Math.min(0.1, Math.max(0.005, (e.interval > 1 ? e.interval / 1000 : e.interval) || 0.016));   // ms in Chrome, s in old Safari
  const un = Math.hypot(...gyroUp) || 1, up = gyroUp.map((v) => v / un);
  const sr = screenRight(), k = sr[0] * up[0] + sr[1] * up[1] + sr[2] * up[2];
  let h = sr.map((v, i) => v - k * up[i]); const hn = Math.hypot(...h);
  if (hn < 0.2) return;                                              // the screen's edge points up or down: no horizon to aim by
  h = h.map((v) => v / hn);
  const yaw = w[0] * up[0] + w[1] * up[1] + w[2] * up[2], pitch = w[0] * h[0] + w[1] * h[1] + w[2] * h[2];
  if (Math.hypot(yaw, pitch) < GYRO_DEAD) return;
  tpAcc.x -= yaw * dt * GYRO_GAIN; tpAcc.y -= pitch * dt * GYRO_GAIN;   // turning left (about up) goes left, the far edge up goes up
  if (!tpTimer) tpTimer = setTimeout(tpFlush, 16);
});
// ---- the whole 3D motion, for a game that asked for it ({motion:true} in its layout: tvpad.on('motion')): the phone's attitude
// (deviceorientation alpha / beta / gamma, degrees), its turn rate (deg/s) and gravity + the hand's push (m/s²), in the phone's
// own axes, with the screen's angle so the game can turn them to the way the phone is held. At most 30 a second, as
// "o" + ten integers: orientation x10, rates, acceleration x100, screen angle.
const motionNow = { o: null, r: null, g: null };
let motionAt = 0;
window.addEventListener('deviceorientation', (e) => { if (e.beta != null) { motionNow.o = e; motionOut(); } });
function motionOut() {
  const l = layout || {};
  if (!l.motion || mode !== 'pad' || document.hidden || !motionNow.r) return;
  const now = performance.now(); if (now - motionAt < 33) return; motionAt = now;
  const o = motionNow.o || {}, r = motionNow.r, g = motionNow.g || {};
  const i = (v, k) => Math.round((+v || 0) * k);
  const a = (screen.orientation && screen.orientation.angle) || +window.orientation || 0;
  send('o ' + [i(o.alpha, 10), i(o.beta, 10), i(o.gamma, 10), i(r.alpha, 1), i(r.beta, 1), i(r.gamma, 1), i(g.x, 100), i(g.y, 100), i(g.z, 100), (a + 360) % 360].join(' '));
}
function tpTouch(t, type) {
  if (type === 'touchstart') { tpId = t.identifier; tpLast = { x: t.clientX, y: t.clientY, t: performance.now() }; $('#tpad').classList.add('on'); return; }
  if (type !== 'touchmove') { tpId = null; $('#tpad').classList.remove('on'); tpFlush(); return; }
  const now = performance.now(), dx = t.clientX - tpLast.x, dy = t.clientY - tpLast.y, dt = Math.max(4, now - tpLast.t);
  const gain = 1 + Math.min(2.2, Math.hypot(dx, dy) / dt * 1.1);            // px per ms: slow = precise, fast flicks travel further
  tpAcc.x += dx * gain; tpAcc.y += dy * gain; tpLast = { x: t.clientX, y: t.clientY, t: now };
  if (!tpTimer) tpTimer = setTimeout(tpFlush, 16);
}
function handle(e) {
  if (e.target.closest && e.target.closest('#textrow,#nameform')) return;
  if (gyroAsk && !gyroSeen && e.target.closest && e.target.closest('#pG')) return;   // a real click, the only thing iOS asks from      // the text field needs its own touches (keyboard, caret)
  e.preventDefault();
  if (document.body.classList.contains('cards') && cardsTouch(e)) return;
  if (e.type === 'touchstart') {
    firstTap();
    for (const t of e.changedTouches) {
      const el = document.elementFromPoint(t.clientX, t.clientY);
      if (el && act(el)) { const a = el.closest('[data-act]'); a.__t = true; continue; }
      if (stickOn && sId == null && t.clientY > 60 && (portrait() ? t.clientY < innerHeight * 0.5 : t.clientX < innerWidth * 0.5) && !(el && el.closest('[data-b]'))) stickStart(t);
    }
    if (!$('#menu').hidden && !e.target.closest('#menu,#gear')) $('#menu').hidden = true;
  }
  for (const t of e.changedTouches) {
    if (document.body.classList.contains('ptr')) {
      if (e.type === 'touchstart') { const el = document.elementFromPoint(t.clientX, t.clientY); if (el && el.closest && el.closest('#tpad') && tpId == null) { tpTouch(t, 'touchstart'); continue; } }
      if (t.identifier === tpId) { tpTouch(t, e.type); continue; }
    }
    if (e.type === 'touchstart') { const el = document.elementFromPoint(t.clientX, t.clientY), nb = el && el.closest && el.closest('[data-n]'); if (nb && mode === 'nav') { navDown(t.identifier, nb); continue; } }
    if (navT.has(t.identifier)) { if (e.type === 'touchend' || e.type === 'touchcancel') navUp(t.identifier, e.type === 'touchcancel'); continue; }
    if (t.identifier === sId) { if (e.type === 'touchend' || e.type === 'touchcancel') stickEnd(); else stickMove(t); continue; }
    if (e.type === 'touchend' || e.type === 'touchcancel') touches.delete(t.identifier);
    else touches.set(t.identifier, under(t.clientX, t.clientY));
  }
  apply();
}
['touchstart', 'touchmove', 'touchend', 'touchcancel'].forEach((n) => document.addEventListener(n, handle, { passive: false }));
['gesturestart', 'gesturechange', 'gestureend', 'contextmenu', 'dblclick', 'selectstart', 'dragstart'].forEach((n) => document.addEventListener(n, (e) => e.preventDefault(), { passive: false }));
window.addEventListener('blur', releaseAll);
$('#textrow').addEventListener('submit', (e) => { e.preventDefault(); const v = $('#txt').value.trim().slice(0, 200); if (v) { send('t ' + v); $('#txt').value = ''; buzz(12); } });
setMode('nav');
// the site's overrides of a look (buttons, face, sh, mid per system: docs/games/games.json "layouts"): a pad fix without an app release
fetch('/layouts.json?k=' + encodeURIComponent(K)).then((r) => r.json()).then((o) => {
  for (const k of Object.keys(o || {})) if (SYS[k] && o[k] && typeof o[k] === 'object') { const {face, sh, mid} = o[k]; Object.assign(SYS[k], JSON.parse(JSON.stringify({face, sh, mid}))); }
  SYS.web = SYS.ps; relayout();
}).catch(() => {});
connect();
