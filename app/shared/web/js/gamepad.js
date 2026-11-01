// A game controller plugged into this computer / phone / browser drives the TV (Gamepad API, "standard" layout).
// padStep() is pure (the state in, the keys to send out) so tests can press, hold and release a fake pad.
const PAD_DEAD = 0.5, PAD_FIRST = 400, PAD_EVERY = 130;              // stick deadzone; ms before a held key repeats; ms between repeats
// Standard mapping index -> TV key. `rep`: repeats while held (moving, volume, channel); the rest fire once per press.
// In picture mode the face buttons go as real gamepad keys (PAD_*, Android's BUTTON_A..): a game on the TV sees a gamepad, and
// Android TV itself treats BUTTON_A / BUTTON_B as OK / Back, so the menus still work.
const PAD_MAP = {
  0: { key: 'OK', pic: 'PAD_A' }, 1: { key: 'BACK', pic: 'PAD_B' }, 2: { key: 'PLAY_PAUSE', pic: 'PAD_X' }, 3: { key: 'MUTE', pic: 'PAD_Y' },
  4: { key: 'VOL_DOWN', pic: 'PAD_L1', rep: 1 }, 5: { key: 'VOL_UP', pic: 'PAD_R1', rep: 1 }, 6: { key: 'CH_DOWN', rep: 1 }, 7: { key: 'CH_UP', rep: 1 },
  8: { key: 'MENU', pic: 'PAD_SELECT' }, 9: { key: 'HOME', pic: 'PAD_START' }, 16: { key: 'HOME' },
  12: { key: 'UP', rep: 1 }, 13: { key: 'DOWN', rep: 1 }, 14: { key: 'LEFT', rep: 1 }, 15: { key: 'RIGHT', rep: 1 },
};
// The set of TV keys a pad state holds down: buttons, plus the left stick as one arrow (its stronger axis, past the deadzone).
function padHeld(pad, picture) {
  const held = new Set(), b = pad.buttons || [], ax = pad.axes || [];
  const on = (i) => b[i] && (b[i].pressed || b[i].value > 0.5);
  for (const i of Object.keys(PAD_MAP)) if (on(i)) { const m = PAD_MAP[i]; held.add(((picture && m.pic) || m.key) + (m.rep ? '+' : '')); }
  const x = ax[0] || 0, y = ax[1] || 0;
  if (Math.max(Math.abs(x), Math.abs(y)) > PAD_DEAD) held.add((Math.abs(x) > Math.abs(y) ? (x > 0 ? 'RIGHT' : 'LEFT') : (y > 0 ? 'DOWN' : 'UP')) + '+');
  return held;
}
// st: { down: {tag: pressedAt}, last: {tag: lastSentAt} } kept between calls. Returns the keys to send now.
function padStep(st, pad, now, picture) {
  const held = padHeld(pad, picture), out = [];
  for (const tag of Object.keys(st.down)) if (!held.has(tag)) { delete st.down[tag]; delete st.last[tag]; }
  for (const tag of held) {
    const key = tag.replace('+', '');
    if (!(tag in st.down)) { st.down[tag] = now; st.last[tag] = now; out.push(key); }
    else if (tag.endsWith('+') && now - st.down[tag] >= PAD_FIRST && now - st.last[tag] >= PAD_EVERY) { st.last[tag] = now; out.push(key); }
  }
  return out;
}

const padSt = { down: {}, last: {} }; let padTimer = null, padSeen = false;
const padFree = () => !(window.ONTV || currentTab !== 'remote' || picking || typingHere() || document.querySelector('dialog[open]') || !can('control'));
function padPoll() {
  const pads = (navigator.getGamepads ? [...navigator.getGamepads()] : []).filter(Boolean);
  if (!pads.length) { padStop(); return; }
  const now = Date.now(), picture = typeof screenOn !== 'undefined' && screenOn;
  const free = padFree();                                             // a field focused or a dialog open: the pad is not ours, but state still follows so a held button does not fire when it closes
  for (const p of pads) for (const k of padStep(padSt, p, now, picture)) if (free) api('/api/key', { key: k });
}
function padStart() {
  if (!padSeen) { padSeen = true; toast(T('gamepad.connected')); }
  if (!padTimer) padTimer = setInterval(padPoll, 40);
}
function padStop() { if (padTimer) clearInterval(padTimer); padTimer = null; padSeen = false; padSt.down = {}; padSt.last = {}; }
// Not on the TV itself (Manage on the TV): a pad there already drives the TV, and once a page reads the Gamepad API the WebView keeps
// every key of a device that calls itself a gamepad for it; an FPT box's IR remote does (KEYBOARD | DPAD | GAMEPAD): the remote went dead.
if (!window.ONTV) {
  window.addEventListener('gamepadconnected', padStart);
  window.addEventListener('gamepaddisconnected', () => { if (!(navigator.getGamepads ? [...navigator.getGamepads()] : []).some(Boolean)) padStop(); });
  if (navigator.getGamepads && [...navigator.getGamepads()].some(Boolean)) padStart();   // a pad already seen before this page opened
}
