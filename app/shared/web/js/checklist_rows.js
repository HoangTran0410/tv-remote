// The pure part of js/checklist.js (the card "Set up your TV"): which rows a TV gets and what each button does (tests/checklist.test.js).

// f: { adb: state, setup: {installed, latest}|null, home: {installed, home, latest, keysOk, keyon}|null, mic: bool|null,
//      chan: {installed, latest}|null, game: {installed, latest}|null }; null = not known yet (its row is left out).
// A row: { id, done, optional, act } where act is what the button does ('' = nothing left to do).
function checklistRows(f) {
  const rows = [{ id: 'adb', done: f.adb === 'ready', act: f.adb === 'ready' ? '' : 'adb' }];
  if (f.adb !== 'ready') return rows;                                   // nothing else can be read or done without ADB
  const s = f.setup, h = f.home, tool = (id, x) => x && { id, optional: true, done: !!x.installed && !x.latest, act: !x.installed ? (x.latest ? 'install' : '') : x.latest ? 'update' : '' };
  if (s) rows.push({ id: 'setup', done: !!s.installed && !s.latest, act: !s.installed ? 'setup_install' : s.latest ? 'setup_update' : '' });
  if (h) rows.push({ id: 'home', done: !!h.installed && !!h.home && !h.latest, act: !h.installed || !h.home ? 'gallery' : h.latest ? 'home_update' : '' });
  if (s && s.installed && f.mic !== null && f.mic !== undefined) rows.push({ id: 'mic', done: !!f.mic, act: f.mic ? '' : 'mic' });
  if (h && h.installed && h.keysOk) rows.push({ id: 'keys', done: !!h.keyon, act: h.keyon ? '' : 'keys' });
  for (const r of [tool('chan', f.chan), tool('game', f.game)]) if (r) rows.push(r);
  return rows;
}
// shown while a required row is open (an optional one never asks for the card by itself)
const checklistOpen = (rows) => rows.some((r) => !r.optional && !r.done);

if (typeof module !== 'undefined') module.exports = { checklistRows, checklistOpen };

