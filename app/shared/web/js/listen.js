// 🎧 the TV's sound on this device. scrcpy captures it on the TV (Android 11+). Two ways, the last one used at once (the chip next to the button changes it):
//   only here: the TV's own speakers are silent (headphones on, the rest of the house asleep); everything the TV plays
//   both: the TV keeps playing (playback capture with audio_dup, Android 13+; older TVs answer "noshare" and go the first way).
//         This device lags the TV a little, and apps that refuse playback capture (some with DRM) are silent here.
// It is light next to the picture:
//   Opus, 64 kbit/s (8 KB/s): the computer's page, where the browser decodes Opus (WebCodecs needs a secure page, and
//         127.0.0.1 is one); the TV encodes it at a few percent of one core
//   PCM 24 kHz mono, 384 kbit/s (48 KB/s): the TV's own page (plain HTTP, no WebCodecs); the TV encodes nothing
// ✨ high quality: Opus at 160 kbit/s, PCM as captured (48 kHz stereo, 1.5 Mbit/s: fine on the home network).
// For comparison the 960p picture is up to 2 Mbit/s and most of a CPU core on the TV.
// Stream framing, as the picture's: 1 byte kind (0 format, 1 codec header, 2 sound, 3 notice word, 4 nothing new, 5 error word),
// 4 bytes length, data.
let listenShared = false;
let listenTvLoud = false;                                              // the private way could not silence the TV (Android 13+: its volume is fixed, or the app blocks capture)
const listenHq = () => { try { return localStorage.getItem('listenHq') === '1'; } catch { return false; } };
const listenEven = () => { try { return localStorage.getItem('listenEven') === '1'; } catch { return false; } };
// What playPcm learnt about each TV (seconds kept ahead), so a TV that sends in bursts starts with enough, not after 3 stalls
const leadOf = () => { try { return (JSON.parse(localStorage.getItem('listenLead') || '{}'))[curHost] || 0; } catch { return 0; } };
const leadKeep = (s) => { try { const m = JSON.parse(localStorage.getItem('listenLead') || '{}'); m[curHost] = Math.round(s * 1000) / 1000; localStorage.setItem('listenLead', JSON.stringify(m)); } catch {} };
const leadBase = () => listenEven() ? 0.5 : 0.08;                       // Đều: half a second from the start, never back down
// While listening, a bar under the header: how it plays (menu) and HQ, as the phone app's chip and HQ under the picture (Listen.kt).
function showWay() {
  put('#listenway', 'textContent', (listenShared ? T('listen.both') : T('listen.this_device')) + ' ▾');
  $('#listenhq').classList.toggle('on', listenHq());
  $('#listeneven').classList.toggle('on', listenEven());
  put('#listenboost', 'textContent', '🔊 ×' + listenBoost()); $('#listenboost').classList.toggle('on', listenBoost() !== 1);
}
// 🔊 ×0.5 … ×10: louder (or softer) here than the TV sends it (a TV turned down sends it quiet), a limiter after it so loud parts do not crack
const BOOST_MIN = 0.5, BOOST_MAX = 10;
const listenBoost = () => { let b = 1; try { b = +localStorage.getItem('listenBoost') || 1; } catch {} return Math.min(BOOST_MAX, Math.max(BOOST_MIN, Math.round(b * 2) / 2)); };
let aout = null;
function soundOut() {
  if (!aout || aout.context !== actx) {
    const gain = actx.createGain(), lim = actx.createDynamicsCompressor();
    lim.threshold.value = -3; lim.knee.value = 0; lim.ratio.value = 20; lim.attack.value = 0.003; lim.release.value = 0.1;
    gain.connect(lim); lim.connect(actx.destination); aout = gain;
  }
  aout.gain.value = listenBoost();
  return aout;
}
let listenOn = false, listenCtl = null, actx = null, adec = null, anext = 0, abytes = 0, listenGen = 0, catchUp = false, alead = 0.08, acalm = 0, aquiet = false;

async function opusHere() {
  if (CORE !== 'pc' || !window.AudioDecoder || !window.isSecureContext) return false;
  try { return !!(await AudioDecoder.isConfigSupported({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2 })).supported; } catch { return false; }
}

// Plays one piece of sound right after the previous one. After a gap playback starts again a little ahead ([alead]: 80 ms,
// twice as much after each stall up to 0.6 s, as SoundQueue.kt does on the phone, back down after two calm minutes: a TCL
// sends its sound in bursts, a fixed 80 ms broke every few seconds; kept per TV for next time). Too far ahead, it skips back
// to live in one go.
function playPcm(channels, rate) {
  if (!actx || !channels.length || !channels[0].length) return;
  const buf = actx.createBuffer(channels.length, channels[0].length, rate);
  channels.forEach((c, i) => buf.copyToChannel(c, i));
  const now = actx.currentTime;
  if (anext < now + 0.02) {                                          // ran dry (a stall): start again a little ahead
    if (anext) { alead = Math.min(0.6, alead * 2); if (!listenEven()) leadKeep(alead); }
    anext = now + alead; acalm = now;
  } else if (alead > leadBase() && now - acalm > 120) { alead = Math.max(leadBase(), alead * 0.8); acalm = now; if (!listenEven()) leadKeep(alead); }
  // After a stall the network hands over the late sound in one burst; played in turn, it would keep the whole stall as delay
  // for good. Too far ahead: drop pieces until close to live again (one short skip instead of a lasting lag).
  const ahead = anext - now;
  if (VT) VT.audio.push([vtNow(), ahead * 1000, now, anext, actx.baseLatency, actx.outputLatency]);   // ?stats=1
  if (ahead > alead + 0.2) catchUp = true; else if (ahead < alead + 0.02) catchUp = false;
  if (catchUp) return;
  const src = actx.createBufferSource(); src.buffer = buf; src.connect(soundOut()); src.start(anext);
  anext += buf.duration;
}

function setListenUi(on, text) {
  const b = $('#listen'); setLabel(b, '🎧', on ? T('listen.listening') : T('remote.listen')); b.classList.toggle('on', on);
  put('#listenbar', 'hidden', !on); if (on) showWay();
  put('#listeninfo', 'hidden', !text); put('#listeninfo', 'textContent', text || '');
}

async function startListen(share) {
  const gen = ++listenGen;
  listenShared = !!share; try { localStorage.setItem('listenShare', listenShared ? '1' : '0'); } catch {}
  listenOn = true; setListenUi(true, T('listen.opening'));
  try {
    actx = actx || new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
    if (navigator.audioSession) navigator.audioSession.type = 'playback';   // iPhone: play even with the silent switch on
    await actx.resume();                                              // inside the tap: browsers start sound only then
  } catch { stopListen(T('listen.no_browser_sound')); return; }
  const opus = await opusHere(); if (gen !== listenGen) return;
  listenTvLoud = false; listenCtl = new AbortController(); anext = 0; abytes = 0; aquiet = false; alead = Math.min(0.6, Math.max(leadBase(), leadOf()));
  let fmt = { codec: 2, rate: 24000, ch: 1 }, ts = 0, why = T('listen.tv_stopped_sending');
  try {
    const r = await fetch('/api/audio?codec=' + (opus ? 'opus' : 'pcm') + (listenShared ? '&share=1' : '') + (listenHq() ? '&hq=1' : ''), { signal: listenCtl.signal });
    if (!r.ok) { let e = r.statusText; try { e = (await r.json()).error || e; } catch {} throw new Error(r.status === 404 && CORE === 'pc' ? STALE : e); }
    toast(listenShared ? T('listen.playing_both') : T('listen.playing_here'));
    const rd = r.body.getReader(); let buf = new Uint8Array(0);
    for (;;) {
      const { done, value } = await rd.read(); if (done || gen !== listenGen) break;
      abytes += value.length;
      const nb = new Uint8Array(buf.length + value.length); nb.set(buf); nb.set(value, buf.length); buf = nb;
      while (buf.length >= 5) {
        const len = new DataView(buf.buffer, buf.byteOffset + 1, 4).getUint32(0); if (buf.length < 5 + len) break;
        const kind = buf[0], data = buf.slice(5, 5 + len); buf = buf.slice(5 + len);
        if (kind === 3 && new TextDecoder().decode(data) === 'noshare') { listenShared = false; toast(T('listen.private_only')); }
        else if (kind === 3 && new TextDecoder().decode(data) === 'tvloud') { listenTvLoud = true; toast(T('listen.tv_still_loud')); }
        else if (kind === 3 && new TextDecoder().decode(data) === 'tvsilent') listenTvLoud = false;
        else if (kind === 0) { const d = new DataView(data.buffer); fmt = { codec: data[0], rate: d.getUint32(1), ch: data[5] }; }
        else if (kind === 1 && fmt.codec === 1) {
          adec = new AudioDecoder({ output: (a) => {
            const chs = []; for (let i = 0; i < a.numberOfChannels; i++) { const c = new Float32Array(a.numberOfFrames); a.copyTo(c, { planeIndex: i, format: 'f32-planar' }); chs.push(c); }
            playPcm(chs, a.sampleRate); a.close();
          }, error: () => {} });
          adec.configure({ codec: 'opus', sampleRate: fmt.rate, numberOfChannels: fmt.ch, description: data });
        } else if (kind === 2) {
          aquiet = false;
          if (VT) VT.arx.push([vtNow(), data.length]);
          if (fmt.codec === 1) { if (adec && adec.state === 'configured') adec.decode(new EncodedAudioChunk({ type: 'key', timestamp: ts += 20000, data })); }
          else {                                                       // 16-bit little-endian PCM, channels interleaved
            const v = new DataView(data.buffer), n = Math.floor(data.length / 2 / fmt.ch), chs = Array.from({ length: fmt.ch }, () => new Float32Array(n));
            for (let i = 0; i < n; i++) for (let c = 0; c < fmt.ch; c++) chs[c][i] = v.getInt16((i * fmt.ch + c) * 2, true) / 32768;
            playPcm(chs, fmt.rate);
          }
        } else if (kind === 6) { anext = 0; aquiet = true; }           // the TV went silent and nothing comes until it is not: no stall, no longer lead
        else if (kind === 5) { why = new TextDecoder().decode(data) === 'unsupported' ? T('listen.needs_android_11') : friendly(new TextDecoder().decode(data)); }
      }
    }
  } catch (e) { if (gen !== listenGen) return; why = e.name === 'AbortError' ? '' : T('listen.no_sound', { why: friendly(e.message) }); }
  if (gen === listenGen && why && afterWake('listen')) { stopListen(); startListen(listenShared); return; }   // this device slept, not the TV
  if (gen === listenGen) stopListen(why);
}

function stopListen(msg) {
  listenGen++; listenOn = false;
  if (listenCtl) { listenCtl.abort(); listenCtl = null; }
  if (adec) { try { adec.close(); } catch {} adec = null; }
  setListenUi(false, ''); if (msg) toast(msg);
}

setInterval(() => {                                                    // what it costs, next to the button
  if (listenOn && (abytes || aquiet)) setListenUi(true, T('listen.listening_here') + ' · ' + (listenShared || listenTvLoud ? T('listen.tv_has_sound') : T('listen.tv_quiet')) + (listenHq() ? ' · ✨ ' + T('listen.high_quality') : '') + ' · ' + Math.round(abytes / 1024) + ' KB/s');
  abytes = 0;
}, 1000);
// 🎧: on or off at once, the way used last (picked with the chip under the header, once it plays); the session restarts either way
$('#listen').addEventListener('click', () => {
  if (listenOn) { stopListen(); return; }
  let last = '0'; try { last = localStorage.getItem('listenShare') || '0'; } catch {}
  startListen(last === '1');
});
const relisten = (share) => { if (listenOn) { stopListen(); startListen(share); } else showWay(); };
$('#listenway').addEventListener('click', () => {
  menuDlg(T('remote.how_to_hear_the'), -1, [
    ['@phone|' + T('listen.only_this_device'), T('listen.only_this_note'), () => { if (listenShared) relisten(false); }],
    ['@tv|' + T('listen.tv_and_this_device'), T('listen.tv_and_this_note'), () => { if (!listenShared) relisten(true); }]], listenShared ? 1 : 0);
});
$('#listenboost').addEventListener('click', () => {                   // a slider that applies as it moves, nothing restarts
  const set = (b) => { try { localStorage.setItem('listenBoost', String(b)); } catch {} if (actx) soundOut(); showWay(); out.textContent = '×' + listenBoost(); r.value = listenBoost(); };
  const out = el('output', { textContent: '×' + listenBoost() });
  const r = el('input', { type: 'range', min: BOOST_MIN, max: BOOST_MAX, step: 0.5, value: listenBoost(), ariaLabel: T('listen.loudness'), oninput: (e) => set(+e.target.value) });
  const d = el('dialog', { className: 'ask menu' }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, el('h2', { textContent: T('listen.loudness_here') }), el('button', { className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
    el('div', { className: 'vol boost' }, r, out),
    el('p', { className: 'note', textContent: T('listen.boost_note') }),
    el('div', { className: 'actions' }, el('button', { textContent: '↩ ' + T('listen.back_to_x1'), onclick: () => set(1) }))));
  d.addEventListener('close', () => d.remove());
  document.body.append(d); d.showModal();
});
$('#listeneven').addEventListener('click', () => {                    // later behind the TV, never a stall; the session restarts
  try { localStorage.setItem('listenEven', listenEven() ? '0' : '1'); } catch {}
  relisten(listenShared);
});
$('#listenhq').addEventListener('click', () => {                      // 160 kbit/s instead of 64; applies at once
  try { localStorage.setItem('listenHq', listenHq() ? '0' : '1'); } catch {}
  relisten(listenShared);
});
