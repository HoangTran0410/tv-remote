// 🎮 TV Games (app/tvgames) from here (Games.kt): put on the TV or updated from the latest release, and opened. Playing needs no ADB
// (the app serves its own pad page, a QR on the TV); ADB is only for installing it from here. A release without tv-games.apk yet
// answers `soon`, said plainly instead of failing.
let gameNow = null;
onTvChange(() => { gameNow = null; gameCard(); });

async function gameCard() {
  const line = $('#gamestate'), soon = $('#gamesoon'), open = $('#gameopen'), inst = $('#gameinst');
  if (!line) return;
  const gen = tvGen, r = ADB.state === 'ready' ? await api('/api/games', null, true) : null;
  if (gen !== tvGen) return;                                          // another TV was picked meanwhile: its own gameCard() draws it
  gameNow = r;
  checkSet('game', r && { installed: !!r.installed, latest: r.latest });
  line.hidden = open.hidden = !(r && r.installed);
  soon.hidden = !(r && r.soon);
  if (r && r.installed) put(line, 'textContent', T('games.state', { v: r.version }));
  inst.hidden = !r || !r.latest;                                       // nothing to install: no release carries it yet, or it is up to date
  if (!inst.hidden) put(inst, 'textContent', r.installed ? T('games.update', { v: r.latest }) : T('games.install'));
  lookOffline(inst, !!(r && r.offline), gameCard);
}

async function gameInstall() {
  const n = await ownAppWait(await api('/api/games/install', {}), 'TV Games');
  if (n && n.installed && (!gameNow || n.version !== gameNow.version)) toast(T('games.installed', { v: n.version }));   // the same version: nothing went on
  gameCard();
}

// The phone as a pad: TV Games' own page for it, opened here (the window first, a click's permission lasts only until the first await).
async function gamePad() {
  const w = window.open('', '_blank');
  const r = await api('/api/games/pad', null, true);
  if (r && r.url && w) w.location = r.url; else { if (w) w.close(); if (r) toast(T('gpad.off')); }
}

async function gameOpen() {
  if (await api('/api/games/open', {})) { toast(T('games.opening')); setTimeout(refresh, 1500); }
}

$('#gameinst').addEventListener('click', gameInstall);
$('#gameopen').addEventListener('click', gameOpen);
