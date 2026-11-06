// Now playing: what the TV plays, from its media session (/api/playing: tvr-helper's Playing on the TV, NowPlaying.kt), above the
// remote while the Remote tab is open. The same bar is on the phone app's remote (RemoteScreen.kt). And the TV's clipboard
// (/api/clip), the "clip" entry of the Open on the TV menu.
const PL = { o: { state: 'none' }, at: 0, fine: 0, timer: 0, dragging: false };
const mmss = (ms) => { const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = String(s % 60).padStart(2, '0'); return h ? h + ':' + String(m).padStart(2, '0') + ':' + x : m + ':' + x; };
const plPos = () => { const o = PL.o; let p = o.pos || 0; if (o.state === 'playing') p += (performance.now() - PL.at) * (o.speed || 1); return o.dur ? Math.min(p, o.dur) : p; };
function plDraw() {
  const o = PL.o, name = o.title || o.app, on = o.state && o.state !== 'none' && !!name;   // no title (FPT Play): the app's name
  put($('#playing'), 'hidden', !on); if (!on) return;
  const t = $('#pltitle'); if (t.title !== name) { t.title = name; t.textContent = ''; t.append(Object.assign(document.createElement('span'), { className: 'rin', textContent: name })); }
  runFit(t);
  const sub = o.title ? o.sub || '' : ''; if ($('#plsub').textContent !== sub) put('#plsub', 'textContent', sub);   // the same words again would start its slide over            // the app is the picture beside it and "Đang mở" below, as on the phone app
  const icon = '/api/app/icon?pkg=' + encodeURIComponent(o.pkg); if ($('#plicon').dataset.src !== icon) { $('#plicon').dataset.src = icon; $('#plicon').src = icon; }
  put($('#plbar'), 'hidden', !o.dur);
  if (o.dur && !PL.dragging) { put($('#plseek'), 'value', String(Math.round(plPos() / o.dur * 1000))); put($('#plpos'), 'textContent', mmss(plPos())); put($('#pldur'), 'textContent', mmss(o.dur)); }
  put($('#plseek'), 'disabled', !o.seek || !can('control'));        // a guest who may only watch sees where it is, and no more
  const u = $('#plplay use'), href = o.state === 'paused' ? '#i-play' : '#i-pause'; if (u.getAttribute('href') !== href) u.setAttribute('href', href);
}
async function plPoll() {
  clearTimeout(PL.timer);
  const looking = currentTab === 'remote' && !picking && !document.hidden && !stopped && ADB.state === 'ready';
  if (looking || document.hidden) PL.fine = performance.now();       // hidden counts as fine: the 5 s start on return
  if (looking) { const o = await call('/api/playing', null, true); if (o) { PL.o = o; PL.at = performance.now(); } else PL.o = { state: 'none' }; }
  else if (performance.now() - PL.fine > 5000) PL.o = { state: 'none' };   // kept while the page is hidden, so it is there at once on return
  plDraw();
  PL.timer = setTimeout(plPoll, looking && PL.o.state !== 'none' ? 1000 : 3000);   // nothing playing: every 3 s is soon enough
}
// A press is busy until the TV's own answer shows it done (state or track changed, 2.5 s at most): a second press meanwhile
// is ignored, so presses cannot pile up faster than the TV's media session follows. No icon is flipped before the TV says so.
const plSig = (o) => [o.state, o.title, o.pkg].join('|');
async function plDo(what, ms, btn) {
  if (PL.busy) return; PL.busy = true; if (btn) btn.classList.add('busy');
  try {
    const before = plSig(PL.o);
    if (!await call('/api/playing', { do: what, ms })) return;     // not sent: the toast says why, the button is free again
    for (let t = performance.now(); performance.now() - t < (what === 'seek' ? 600 : 2500);) {
      await sleep(400);
      const o = await call('/api/playing', null, true); if (!o) continue;
      PL.o = o; PL.at = performance.now(); plDraw();
      if (what === 'seek' || plSig(o) !== before) break;
    }
  } finally { PL.busy = false; if (btn) btn.classList.remove('busy'); setTimeout(plPoll, 100); }
}
$('#plplay').addEventListener('click', (e) => plDo(PL.o.state === 'playing' ? 'pause' : 'play', 0, e.currentTarget));
$('#plprev').addEventListener('click', (e) => plDo('prev', 0, e.currentTarget));
$('#plnext').addEventListener('click', (e) => plDo('next', 0, e.currentTarget));
$('#plseek').addEventListener('input', (e) => { PL.dragging = true; put('#plpos', 'textContent', mmss(e.target.value / 1000 * PL.o.dur)); });
$('#plseek').addEventListener('change', (e) => {
  const ms = Math.round(e.target.value / 1000 * PL.o.dur); PL.dragging = false;
  if (PL.busy) return;
  PL.o.pos = ms; PL.at = performance.now(); plDo('seek', ms);
});
setInterval(() => { if (!document.hidden && !$('#playing').hidden && PL.o.state === 'playing') plDraw(); }, 250);   // the time moves on between polls
document.addEventListener('visibilitychange', () => { if (!document.hidden) plPoll(); });
plPoll();

// the TV's clipboard: shown in a box anyone can edit, then copied here, set on the TV, or set and pasted where the TV's cursor is
// The screen off while the sound, the picture and the keys go on (ScreenOff.kt); pressed again, or the TV's own remote, turns it on.
async function screenOff() {
  if (NATIVE()) { if (await api('/api/screen/off', {})) toast(T('playing.screen_off_any')); return; }   // LG: by its remote link, any key turns it on
  if (await needAdbSoon()) return;
  const s = await api('/api/screen/state', null, true);
  if (s && s.off) { if (await api('/api/screen/on', {})) toast(T('playing.screen_on')); return; }
  if (await api('/api/screen/off', {})) toast(T('playing.screen_off'));
}

async function clipDialog() {
  if (await needAdbSoon()) return;
  const got = await api('/api/clip'); if (!got) return;
  const box = el('textarea', { value: got.text || '', placeholder: T('playing.clip_empty'), spellcheck: false });
  const send = async (paste) => {
    if (!box.value) { toast(T('playing.no_text')); return; }
    if (await api('/api/clip', { text: box.value, paste })) { toast(T(paste ? 'playing.pasted' : 'playing.copied_to_tv')); d.close(); }
  };
  const copy = async () => {
    if (await clipCopy(box.value)) { toast(T('playing.copied_here')); d.close(); }
    else { box.select(); toast(T('playing.copy_failed')); }
  };
  const d = el('dialog', { className: 'ask' }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, el('h2', { textContent: T('playing.clip_title') }), el('button', { className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
    box,
    el('div', { className: 'acts wrap' },
      el('button', { textContent: T('playing.copy_here'), onclick: copy }),
      el('button', { textContent: T('playing.copy_to_tv'), onclick: () => send(false) }),
      el('button', { className: 'primary', textContent: T('playing.paste_on_tv'), onclick: () => send(true) }))));
  d.addEventListener('close', () => d.remove());
  document.body.append(d); d.showModal(); box.focus(); box.select();
}
