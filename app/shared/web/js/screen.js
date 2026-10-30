// TV screen (live / screenshots) and tabs
// ---------- screen: live MJPEG stream, or one screenshot at a time ----------
let screenOn = false, screenUrl = null, screenGen = 0, streamCtl = null, frames = 0;
if (CORE !== 'pc') document.querySelectorAll('#screenmode [data-for=pc]').forEach((o) => o.remove());   // an <option> cannot be hidden by CSS everywhere
function setScreenPlaceholder(msg) { put('#screenph', 'textContent', msg || T('screen.hint')); put('#screenph', 'hidden', false); put('#screen', 'hidden', true); put('#screenv', 'hidden', true); put('#screenc', 'hidden', true); }
// The element showing the picture now (a screenshot, the <video> of JMuxer, the canvas of WebCodecs), and its own size.
function screenEl() { return ['#screenc', '#screenv', '#screen'].map((s) => $(s)).find((m) => !m.hidden) || null; }
function picSize(m) { return m && m.tagName === 'CANVAS' ? [m.width, m.height] : m ? [m.naturalWidth || m.videoWidth || 0, m.naturalHeight || m.videoHeight || 0] : [0, 0]; }
function setScreenInfo(text, live) { put('#screeninfo', 'textContent', text); $('#screeninfo').classList.toggle('live', !!live); }
function showFrame(blob) {
  const u = URL.createObjectURL(blob); $('#screen').src = u; put('#screen', 'hidden', false); put('#screenph', 'hidden', true); put('#screenv', 'hidden', true); put('#screenc', 'hidden', true);
  if (screenUrl) URL.revokeObjectURL(screenUrl); screenUrl = u; frames++;
}
function stopScreen(msg) {
  snapTimers.forEach(clearTimeout); if (snapCtl) snapCtl.abort(); snapCtl = null;
  if (typeof drawing !== 'undefined' && drawing) drawStop();         // no picture to draw on
  screenOn = false; screenGen++; if (streamCtl) streamCtl.abort(); if (player) { player.destroy(); player = null; }
  $('#screencard').classList.remove('on'); if (msg && matchMedia('(max-width:899px)').matches) toast(msg);   // on a phone the box is folded away: say it where it is seen
  setLabel($('#screenbtn'), '▶', T('remote.watch')); setScreenPlaceholder(msg); setScreenInfo(''); dockScreen(); put('#watchers', 'hidden', true);
}
// Screenshot mode ("Ảnh chụp", level snap): no stream, a still that refreshes by itself after each key (snapKey, called by api() in
// core.js), every 10 s while visible, and on demand (snapNow). One request in flight: a newer shot aborts the older.
const SNAP_AFTER = [250, 700, 1500, 3000, 5000], SNAP_SLOW = 10000;   // ms after the last key; animations and app launches take 0.3-3 s
let snapCtl = null, snapSig = null, snapTimers = [], snapAt = 0;
async function snapOnce(signal) {
  try {
    const r = await fetch('/api/screen', { signal });
    if (r.ok) return await r.blob();
    let e = r.statusText; try { e = (await r.json()).error || e; } catch {} return friendly(adbWords(r.status, e));
  } catch (e) { return e && e.name === 'AbortError' ? null : T('screen.cannot_reach_server'); }
}
// Same length and a cheap hash of every 16th byte: equal means the screen did not change between two shots.
async function snapSign(b) {
  const u = new Uint8Array(await b.arrayBuffer()); let h = 2166136261;
  for (let i = 0; i < u.length; i += 16) h = Math.imul(h ^ u[i], 16777619);
  return u.length + ':' + (h >>> 0);
}
// One shot, shown unless a newer one took over. Returns true when it equals the shot before (the screen has settled).
async function snapShot(gen) {
  if (snapCtl) snapCtl.abort();
  const ctl = snapCtl = new AbortController(); snapAt = Date.now();
  const b = await snapOnce(ctl.signal); if (b === null || gen !== screenGen || snapCtl !== ctl) return false;
  snapCtl = null;
  if (!(b instanceof Blob)) { setScreenPlaceholder(b); return false; }
  const sig = await snapSign(b); if (gen !== screenGen) return false;
  const same = sig === snapSig; snapSig = sig; if (!same || !screenEl()) showFrame(b);
  setScreenInfo(T('screen.snap_info'));
  return same;
}
function snapNow() { if (screenOn && $('#screenmode').value === 'snap') snapShot(screenGen); }
// After the last key: shoot at 250 ms, then at 0.7, 1.5, 3 and 5 s; two shots in a row that are identical mean the screen has
// settled (no point shooting the TV again, a screencap is a full PNG), so the rest are dropped. A new key starts over.
function snapKey() {
  if (!screenOn || $('#screenmode').value !== 'snap') return;
  snapTimers.forEach(clearTimeout); snapSig = null; const gen = screenGen;   // null: the first shot cannot "match" the picture from before the key
  snapTimers = SNAP_AFTER.map((ms) => setTimeout(async () => {
    if (document.hidden || gen !== screenGen) return;
    if (await snapShot(gen)) snapTimers.forEach(clearTimeout);
  }, ms));
}
async function snapLoop(gen) {
  setScreenInfo(T('screen.snap_info')); snapSig = null; snapShot(gen);
  while (screenOn && gen === screenGen) {
    await sleep(1000);
    if (!document.hidden && Date.now() - snapAt >= SNAP_SLOW) snapShot(gen);
  }
}
// Every core sends H.264 as scrcpy makes it. Packets: 1 byte kind (0 size, 1 codec config, 2 key frame, 3 frame, 4 nothing
// new, 5 it fell behind and jumped to now: CatchUp.kt), 4 bytes length, then the data. Shown by WebCodecs where the page
// may use it (a secure page: the computer's 127.0.0.1), each frame drawn the moment it is decoded; elsewhere (the TV web page
// over http) JMuxer makes it playable by a <video>, which buffers a little.
const H264_Q = { 480: 0, 720: 1, 960: 2, 360: 3, 240: 4, hd: 'hd' }, H264_FPS = { 480: 15, 720: 20, 960: 20, 360: 15, 240: 15, hd: 30 };
// ?stats=1: per-stage timestamps (wall ms) of every frame in window.VT, for measuring latency by hand; nothing is recorded otherwise.
const VT = /[?&]stats=1/.test(location.search) ? (window.VT = { rx: [], dec: [], paint: [], jm: [], audio: [], arx: [], seq: 0 }) : null;
const vtOff = VT ? Date.now() - performance.timeOrigin - performance.now() : 0;   // timeOrigin can sit tens of ms off the wall clock the server logs
const vtNow = () => performance.timeOrigin + performance.now() + vtOff;
const H264_END = new Uint8Array([0, 0, 0, 1, 9, 0xf0]);                // access unit delimiter
let player = null, wcBroken = false;                                   // wcBroken: WebCodecs failed once here, JMuxer until reload
const wcUsable = () => !wcBroken && !/[?&]jm=1/.test(location.search) && window.isSecureContext && typeof VideoDecoder === 'function';   // ?jm=1: JMuxer where WebCodecs works too (to measure it)
// "avc1.PPCCLL" from the SPS in scrcpy's codec config (Annex B)
function avcCodec(b) {
  for (let i = 0; i + 7 < b.length; i++) {
    if (b[i] === 0 && b[i + 1] === 0 && b[i + 2] === 1 && (b[i + 3] & 0x1f) === 7) return 'avc1.' + [b[i + 4], b[i + 5], b[i + 6]].map((x) => x.toString(16).padStart(2, '0')).join('');
  }
  return null;
}
function shownIn(m) { if (m.hidden) { ['#screen', '#screenv', '#screenc', '#screenph'].forEach((s) => { $(s).hidden = $(s) !== m; }); } }
// smooth: Deskhub's "frame pacing is not latency": a frame shown the moment it arrives turns Wi-Fi's bunching into a jerky
// picture, so each waits SMOOTH_MS and two that came together are shown a frame apart. Never a backlog: a frame later than
// three waits behind its arrival makes the queue start again from now.
const SMOOTH_MS = 33;
function wcPlayer(broken, smooth) {
  const cv = $('#screenc'), g = cv.getContext('2d');
  let dec = null, codec = null, config = null, keyed = false, latest = null, raf = 0, ts = 0, outs = 0, watch = 0;
  let q = [], every = 0, lastAt = 0, nextDue = 0;                     // smooth: frames waiting with when to show them
  const paint = (f) => {
    if (cv.width !== f.displayWidth || cv.height !== f.displayHeight) { cv.width = f.displayWidth; cv.height = f.displayHeight; }
    g.drawImage(f, 0, 0); if (VT) VT.paint.push([Math.round(f.timestamp / 1000), vtNow()]); f.close(); frames++; shownIn(cv);
  };
  const draw = () => {
    raf = 0;
    if (!smooth) { const f = latest; latest = null; if (f) paint(f); return; }
    const now = performance.now();
    while (q.length > 1 && q[1].due <= now && q[0].due < now - SMOOTH_MS * 2) q.shift().f.close();   // far behind: skip ahead
    if (q.length && q[0].due <= now) paint(q.shift().f);                // one per screen refresh, in order: none thrown away
    if (q.length) raf = requestAnimationFrame(draw);
  };
  const arrived = (f) => {
    const now = performance.now();
    if (lastAt && now - lastAt < 1000) every = every ? every * 0.9 + (now - lastAt) * 0.1 : now - lastAt;
    lastAt = now;
    let due = Math.max(now + SMOOTH_MS, nextDue);
    if (due - now > SMOOTH_MS * 3) due = now + SMOOTH_MS;
    nextDue = due + Math.max(every * 0.95, 17);                       // a little under the TV's pace (the wait drains back), a refresh at least
    q.push({ f, due }); while (q.length > 8) q.shift().f.close();
    if (!raf) raf = requestAnimationFrame(draw);
  };
  const fail = (why) => { clearTimeout(watch); if (dec) { try { dec.close(); } catch {} } dec = null; pageSaid('picture: VideoDecoder ' + codec + ' ' + (why && why.message || why || 'failed') + ', JMuxer instead'); broken(); };
  const open = () => {
    if (dec) { try { dec.close(); } catch {} }
    outs = 0; clearTimeout(watch); watch = 0;
    dec = new VideoDecoder({ output: smooth ? (f) => { outs++; arrived(f); } : (f) => { outs++; if (VT) VT.dec.push([Math.round(f.timestamp / 1000), vtNow(), 1]); if (latest) latest.close(); latest = f; if (!raf) raf = requestAnimationFrame(draw); }, error: fail });   // only the newest waits to be drawn
    try { dec.configure({ codec, optimizeForLatency: true }); } catch (e) { fail(e); }
    keyed = false;
  };
  return {
    feed(kind, data) {
      if (kind === 1) { config = data.slice(); const c = avcCodec(config); if (c && (c !== codec || !dec)) { codec = c; open(); } return; }
      if (!dec || dec.state !== 'configured' || (kind === 3 && !keyed)) return;   // a frame needs the key frame before it
      let buf = data;
      if (kind === 2) { keyed = true; if (config) { buf = new Uint8Array(config.length + data.length); buf.set(config); buf.set(data, config.length); } }
      if (VT) VT.dec.push([ts / 1000 + 1, vtNow(), 0]);
      try { dec.decode(new EncodedVideoChunk({ type: kind === 2 ? 'key' : 'delta', timestamp: ts += 1000, data: buf })); } catch (e) { fail(e); return; }
      // a decoder that takes the key frame and never gives a picture, nor an error (seen in the Mac app's web view): 4 s, then JMuxer
      if (kind === 2 && !outs && !watch) watch = setTimeout(() => { watch = 0; if (dec && !outs) fail('gave no frame in 4 s (queue ' + dec.decodeQueueSize + ')'); }, 4000);
    },
    destroy() { clearTimeout(watch); if (raf) cancelAnimationFrame(raf); if (latest) latest.close(); latest = null; q.forEach((w) => w.f.close()); q = []; if (dec && dec.state !== 'closed') { try { dec.close(); } catch {} } dec = null; },
  };
}
function jmuxPlayer(fps) {
  const video = $('#screenv'), step = Math.round(1000 / fps);
  const mux = new JMuxer({ node: video, mode: 'video', flushingTime: 0, fps, clearBuffer: true, onError: () => {} });
  let config = null;
  if (VT && video.requestVideoFrameCallback) {
    const cb = (now, m) => { const b = video.buffered; VT.jm.push([vtNow(), m.mediaTime, m.presentedFrames, b.length ? b.end(b.length - 1) - video.currentTime : -1, m.expectedDisplayTime - now]); video.requestVideoFrameCallback(cb); };
    video.requestVideoFrameCallback(cb);
  }
  const edge = setInterval(() => {                                    // stay on the newest frame instead of playing a growing backlog
    const b = video.buffered; if (!b.length) return;
    const end = b.end(b.length - 1); if (end - video.currentTime > 0.3) video.currentTime = end - 0.05;
    if (video.paused && end - video.currentTime > 0.1) video.play().catch(() => {});   // at the last frame: WebKit's play() would rewind to 0 (a flicker)
  }, 250);
  const vt = vtHook(video, step);
  return {
    feed(kind, data) {
      if (kind === 1) { config = data.slice(); return; }              // goes in front of the next frame
      if (vt) vt.arrived();
      // JMuxer shows a frame only once it sees the start of the next one, and a still picture has no next one:
      // an empty delimiter after each frame makes it show the frame now.
      const frame = new Uint8Array((config ? config.length : 0) + data.length + H264_END.length);
      if (config) frame.set(config); frame.set(data, config ? config.length : 0); frame.set(H264_END, frame.length - H264_END.length); config = null;
      if (VT) VT.jm.push([vtNow(), -1, VT.seq, video.buffered.length ? video.buffered.end(video.buffered.length - 1) - video.currentTime : -1, 0]);
      mux.feed({ video: frame, duration: step }); frames++; shownIn(video);
    },
    destroy() { clearInterval(edge); try { mux.destroy(); } catch {} },
  };
}
// ?stats=1: window.JT (beside VT's per-stage stamps) to measure the JMuxer path: for each frame shown, ms from its arrival (`lat`), ms since the one before
// (`gaps`; a freeze is 4+ frame intervals while frames kept arriving), and the <video>'s `waiting` events.
function vtHook(video, step) {
  if (!/[?&]stats=1/.test(location.search) || !video.requestVideoFrameCallback) return null;
  const VT = window.JT = { lat: [], gaps: [], waiting: 0, arrivals: [] }; let t0 = null, last = 0, lastMt = -1;
  video.addEventListener('waiting', () => VT.waiting++);
  const on = (now, md) => {
    video.requestVideoFrameCallback(on);
    if (md.mediaTime === lastMt) return; lastMt = md.mediaTime;       // WebKit calls back for a frame it already showed
    if (t0 === null) t0 = md.mediaTime;
    const a = VT.arrivals[Math.round((md.mediaTime - t0) * 1000 / step)];   // JMuxer stamps frame i at i * step
    if (a !== undefined) VT.lat.push(+(now - a).toFixed(1));
    if (last) VT.gaps.push(+(now - last).toFixed(1)); last = now;
  };
  video.requestVideoFrameCallback(on);
  return { arrived() { VT.arrivals.push(performance.now()); } };
}
// A browser that keeps falling behind (CatchUp on the server jumped to now 3 times in half a minute): one level down, for this
// time only, unless the person set their own frames per second or bit rate. After PIC_UP_MS without another catch-up it goes one
// level back up, never past the level the person picked; if that fell behind again within PIC_FLAP_MS the next wait is doubled.
const PIC_STEPS = ['240', '360', '480', '720', '960', 'hd'];
const PIC_UP_MS = 30000, PIC_UP_MAX = 480000, PIC_FLAP_MS = 60000;
function picNext(s, now) {                                           // s: { mode, chosen, calmFrom, wait }: the level to try now, or null
  const i = PIC_STEPS.indexOf(s.mode), c = PIC_STEPS.indexOf(s.chosen);
  return i < 0 || c < 0 || i >= c || now - s.calmFrom < s.wait ? null : PIC_STEPS[i + 1];
}
function picWaitAfter(wait, upAt, now) { return upAt && now - upAt < PIC_FLAP_MS ? Math.min(wait * 2, PIC_UP_MAX) : wait; }   // fell behind again soon after a step up
let behindAt = [], picChosen = null, picWait = PIC_UP_MS, picCalm = 0, picUpAt = 0;
function fellBehind() {
  const now = Date.now(); picWait = picWaitAfter(picWait, picUpAt, now); picUpAt = 0; picCalm = now;
  behindAt = behindAt.filter((t) => now - t < 30000).concat(now);
  if (behindAt.length < 3) return;
  behindAt = [];
  const t = picTune(), i = PIC_STEPS.indexOf($('#screenmode').value);
  if (i <= 0 || t.fps || t.rate) { toast(T('screen.slow_try_lower')); return; }
  $('#screenmode').value = PIC_STEPS[i - 1]; picLevelSay();
  toast(T('screen.slow_stepped_down', { size: $('#screenmode').selectedOptions[0].textContent }));
  startScreen();
}
setInterval(() => {                                                  // quiet for long enough: one level back up
  if (!screenOn || !streamCtl || streamCtl.signal.aborted) return;
  const now = Date.now(), mode = $('#screenmode').value;
  if (mode === picChosen) { if (picWait > PIC_UP_MS && now - picCalm > PIC_UP_MAX) picWait = PIC_UP_MS; return; }
  const up = picNext({ mode, chosen: picChosen, calmFrom: picCalm, wait: picWait }, now);
  if (!up) return;
  $('#screenmode').value = up; picLevelSay(); picUpAt = picCalm = now;
  toast(T('screen.steady_stepped_up', { size: $('#screenmode').selectedOptions[0].textContent }));
  startScreen();
}, 5000);
// ⚙ Advanced: frames per second and bit rate of the person's own, over the level's (0: the level's). Kept in this browser.
const PIC_FPS = [0, 15, 30, 60], PIC_RATE = [0, 1, 2, 4, 8];          // Mbit/s
function picTune() { let t = {}; try { t = JSON.parse(localStorage.getItem('picTune') || '{}'); } catch {} return { fps: +t.fps || 0, rate: +t.rate || 0, enc: ['hw', 'sw'].includes(t.enc) ? t.enc : 0, smooth: t.smooth ? 1 : 0 }; }
function picTuneSay() { const t = picTune(); return (t.fps ? ' · ≤' + t.fps + ' fps' : '') + (t.rate ? ' · ' + t.rate + ' Mbit/s' : '') + (t.enc ? ' · ' + (t.enc === 'hw' ? T('screen.encoding_hw') : T('screen.encoding_sw')) : '') + (t.smooth && wcUsable() ? ' · ' + T('screen.even_pace') : ''); }
const picEnc = (v) => v === 'hw' ? T('screen.hardware') : v === 'sw' ? T('screen.software') : T('screen.auto');   // the TV's video encoder; auto: hardware, software if it sends nothing
// The quality button under the picture: one dialog with the levels, then frames per second, bit rate and encoder.
function picDialog() {
  const chips = (list, on, pick) => {
    const box = el('div', { className: 'fchips' });
    const draw = () => box.replaceChildren(...list.map(([v, words]) => el('button', { className: on() === v ? 'on' : '', textContent: words, onclick: () => { pick(v); draw(); } })));
    draw(); box.redraw = draw; return box;
  };
  const tune = (key, list, words = (v) => v ? String(v) : T('screen.default')) => chips(list.map((v) => [v, words(v)]), () => picTune()[key], (v) => {
    const t = picTune(); t[key] = v; try { localStorage.setItem('picTune', JSON.stringify(t)); } catch {}
    if (screenOn && $('#screenmode').value !== 'snap') startScreen();
  });
  const levels = [...$('#screenmode').options].map((o) => [o.value, o.textContent]), sized = (v) => /^\d+$/.test(v);
  const pickLevel = (v) => { if (v !== 'snap') { try { localStorage.setItem('liveLevel', v); } catch {} } $('#screenmode').value = v; $('#screenmode').dispatchEvent(new Event('change')); d.querySelectorAll('.fchips.level').forEach((c) => c.redraw()); };
  const liveLevel = () => { try { const q = localStorage.getItem('liveLevel'); if (q && levels.some(([v]) => v === q)) return q; } catch {} return '960'; };   // the size Live returns to
  const level = (list, now, pick) => { const c = chips(list, now || (() => $('#screenmode').value), pick || pickLevel); c.classList.add('level'); return c; };   // two rows, one choice: each on one line
  const d = el('dialog', { className: 'ask menu' }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, el('h2', { textContent: T('screen.picture_quality') }), el('button', { className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
    el('h3', { textContent: T('screen.mode') }), level([['live', T('screen.mode_live')], ['snap', T('screen.mode_snap')]], () => $('#screenmode').value === 'snap' ? 'snap' : 'live', (v) => pickLevel(v === 'live' ? liveLevel() : 'snap')),
    el('p', { className: 'note', textContent: T('screen.mode_note') }),
    el('h3', { textContent: T('screen.size') }), level(levels.filter(([v]) => sized(v))),
    el('p', { className: 'note', textContent: T('screen.largest_size_note') }),
    el('h3', { textContent: T('screen.or') }), level(levels.filter(([v]) => !sized(v) && v !== 'snap')),
    el('h3', { textContent: T('screen.frames_per_second') }), tune('fps', PIC_FPS),
    el('h3', { textContent: T('screen.bit_rate') }), tune('rate', PIC_RATE),
    el('h3', { textContent: T('screen.encoder') }), tune('enc', [0, 'hw', 'sw'], picEnc),
    ...(wcUsable() ? [el('h3', { textContent: T('screen.frame_pace') }), tune('smooth', [0, 1], (v) => v ? T('screen.even_more') : T('screen.fastest')),   // JMuxer keeps its own buffer: nothing to choose there
      el('p', { className: 'note', textContent: T('screen.even_note') })] : []),
    el('p', { className: 'note', textContent: T('screen.higher_note') })));
  d.addEventListener('close', () => d.remove());
  document.body.append(d); d.showModal();
}
// the level is the person's own pick (frames and bit rate go as they are): a TV with under 1.5 GB holds it to 720 px / 20 fps / 1 Mbit/s otherwise (Tv.kt)
function picHand() { try { return !!localStorage.getItem('quality'); } catch { return false; } }
function picLevelSay() { put('#screennow', 'hidden', $('#screenmode').value !== 'snap'); const o = $('#screenmode').selectedOptions[0]; $('#screenmodebtn').replaceChildren(el('span', { textContent: o ? o.textContent : '' }), ' ▾'); }
function loadScript(src) { return new Promise((ok, no) => document.head.append(el('script', { src, onload: ok, onerror: no }))); }
async function h264Loop(gen) {
  setScreenInfo(T('screen.opening_live'), true);
  const size = $('#screenmode').value, tune = picTune(), fps = tune.fps || H264_FPS[size] || 10;
  if (wcUsable()) player = wcPlayer(() => { wcBroken = true; if (gen === screenGen) startScreen(); }, tune.smooth);   // this browser cannot after all: again with JMuxer
  else {
    try { if (!window.JMuxer) await loadScript('jmuxer.min.js'); } catch { if (gen === screenGen) stopScreen(T('screen.player_not_loaded')); return; }
    if (gen !== screenGen) return;
    try { player = jmuxPlayer(fps); }
    catch { $('#screenmode').value = 'snap'; snapLoop(gen); return; }   // no MediaSource (an iPhone before iOS 17.1): pictures instead
  }
  const mine = player;
  streamCtl = new AbortController();
  let why = T('screen.tv_stopped_sending');
  try {
    const r = await fetch('/api/video?q=' + (H264_Q[size] ?? 1) + (tune.fps ? '&fps=' + tune.fps : '') + (tune.rate ? '&rate=' + tune.rate * 1000000 : '') + (tune.enc ? '&enc=' + tune.enc : '') + (picHand() ? '&hand=1' : ''), { signal: streamCtl.signal }); if (!r.ok) throw new Error('http');
    const rd = r.body.getReader(); let buf = new Uint8Array(0), off = 0;   // off: where the next packet starts in buf (no copy per packet)
    for (;;) {
      const { done, value } = await rd.read(); if (done || gen !== screenGen) break;
      if (off >= buf.length) buf = value;                              // nothing pending: take the chunk as it is
      else { const nb = new Uint8Array(buf.length - off + value.length); nb.set(buf.subarray(off)); nb.set(value, buf.length - off); buf = nb; }   // only the unfinished packet is copied
      off = 0;
      while (buf.length - off >= 5) {
        const len = new DataView(buf.buffer, buf.byteOffset + off + 1, 4).getUint32(0); if (buf.length - off < 5 + len) break;
        const kind = buf[off], data = buf.subarray(off + 5, off + 5 + len); off += 5 + len;
        if (kind === 0) { const d = new DataView(data.buffer, data.byteOffset, data.length); $('#screenbox').style.aspectRatio = d.getUint32(0) + ' / ' + d.getUint32(4); }
        else if (kind >= 1 && kind <= 3) { if (VT) VT.rx.push([kind === 1 ? -1 : ++VT.seq, vtNow(), len]); if (player === mine) mine.feed(kind, data); }
        else if (kind === 5) fellBehind();
      }
    }
  } catch (e) { why = T('screen.no_picture_from_tv'); }
  if (screenOn && gen === screenGen && afterWake('screen')) { startScreen(); return; }   // this device slept, not the TV
  if (screenOn && gen === screenGen && !frames && !screenEl()) {     // never a frame: a TV with one encoder may be busy with another device
    await sleep(1000);                                                // our own scrcpy-server gone from the TV's list first
    const w = await watchersNow(); if (w.watch > 0 && gen === screenGen) why = T('screen.others_watching');
  }
  if (screenOn && gen === screenGen) stopScreen(why);                 // back to the plain remote: no dead picture left on the page
}
// Keep watching on the other tabs: move the picture card into a floating corner slot, and back on the Remote tab.
const screenHome = $('#screencard').parentElement;
function dockScreen() {
  const float = currentTab !== 'remote' && screenOn, card = $('#screencard');
  if (float && card.parentElement !== $('#pipslot')) $('#pipslot').append(card);
  if (!float && card.parentElement !== screenHome) screenHome.prepend(card);
  put('#pipback', 'hidden', !float); document.body.classList.toggle('pip', float);
}
$('#pipback').addEventListener('click', () => showTab('remote'));
// the Progress card sits above the mini picture (page.css): it needs the picture's height, which changes with the TV's shape
new ResizeObserver(([e]) => document.documentElement.style.setProperty('--pip-h', e.target.offsetHeight + 'px')).observe($('#pipslot'));
function startScreen() {
  screenOn = true; const gen = ++screenGen; if (streamCtl) streamCtl.abort();
  dockScreen();
  if (player) { player.destroy(); player = null; }
  $('#screencard').classList.add('on'); $('#screenbox').style.aspectRatio = '';
  setLabel($('#screenbtn'), '⏹', T('screen.stop')); setScreenPlaceholder(T('screen.getting_picture'));
  ($('#screenmode').value === 'snap' ? snapLoop : h264Loop)(gen);   // every core sends H.264
  setTimeout(() => { if (gen === screenGen) showWatchers(); }, 3000);   // once our own session is on the TV's list
  const enc = picTune().enc;                                          // an encoder picked by hand that this TV does not have sends nothing: Auto would have moved on
  if (enc && $('#screenmode').value !== 'snap') setTimeout(async () => {
    if (gen !== screenGen || !screenOn || screenEl()) return;
    if (!await ask(T(enc === 'hw' ? 'screen.no_picture_hw' : 'screen.no_picture_sw'), { ok: T('screen.back_to_auto') })) return;
    const t = picTune(); t.enc = 0; try { localStorage.setItem('picTune', JSON.stringify(t)); } catch {}
    if (screenOn) startScreen();
  }, 10000);
}
// How many devices watch and listen to this TV (/api/watchers: the TV's scrcpy-servers, every phone, computer and TV page),
// shown on the picture once there are others: nobody wonders then why the TV is slow or the picture went.
async function watchersNow() {
  try { const r = await fetch('/api/watchers'); return r.ok ? await r.json() : {}; } catch { return {}; }
}
async function showWatchers() {
  if (!screenOn) { put('#watchers', 'hidden', true); return; }
  const w = await watchersNow(), mine = typeof listenOn !== 'undefined' && listenOn ? 1 : 0;
  const watch = w.watch || 0, listen = w.listen || 0, more = watch > 1 || listen > mine;
  if (!screenOn) return;
  put('#watchers', 'hidden', !more);
  if (!more) return;
  put('#watchers', 'textContent', '👁 ' + watch + (listen ? ' · 🎧 ' + listen : ''));
  put('#watchers', 'title', T('screen.watchers', { n: watch }) + (listen ? ' · ' + T('screen.listeners', { n: listen }) : ''));
}
setInterval(() => { if (screenOn && !document.hidden) showWatchers(); }, 10000);
setInterval(() => {
  if (screenOn && $('#screenmode').value !== 'snap' && streamCtl && !streamCtl.signal.aborted) {
    streamFps = frames;
    const shown = !!screenEl();           // the TV sends a frame only when the picture changes
    setScreenInfo((frames ? T('screen.live_fps', { n: frames }) : shown ? T('screen.live_still') : T('screen.live_waiting')) + ($('#screenmode').value === 'hd' ? ' · ' + T('screen.sharpest_slows') : ' · ' + $('#screenmode').value + 'p') + picTuneSay(), true);
  }
  frames = 0;
}, 1000);
// Some TVs draw a video on a plane of its own that no capture sees (FPT Play box: YouTube too), and protected films (Netflix)
// are never captured: the picture is black but for subtitles and logos. Said once the TV has a film open (playing or paused) and the picture has stayed
// nearly all black for a few seconds, so nobody takes it for a fault; a dark scene rarely lasts that long that black.
const blackCv = document.createElement('canvas'); blackCv.width = 32; blackCv.height = 18;
let blackFor = 0, blackIdle = 0;
function blackPicture() {
  const src = screenEl();
  if (!src || !picSize(src)[0]) return false;
  try {
    const c = blackCv.getContext('2d', { willReadFrequently: true }); c.drawImage(src, 0, 0, 32, 18);
    const d = c.getImageData(0, 0, 32, 18).data; let dark = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] + d[i + 1] + d[i + 2] < 60) dark++;
    return dark / (d.length / 4) > 0.9;
  } catch { return false; }
}
setInterval(() => {
  const black = screenOn && typeof PL !== 'undefined' && (PL.o.state || 'none') !== 'none' && blackPicture();
  blackFor = black ? blackFor + 1 : 0;
  put($('#screenblack'), 'hidden', blackFor < 4);
  // nothing playing and still all black: some boxes' hardware encoders send black frames (ZTE B860H, an Amlogic chip), the software
  // one shows the screen. Offered, not done: a dark home screen (TV Home's black background) looks the same.
  const dark = screenOn && (typeof PL === 'undefined' || (PL.o.state || 'none') === 'none') && picTune().enc !== 'sw' && blackPicture();
  blackIdle = dark ? blackIdle + 1 : 0;
  put($('#screenblackenc'), 'hidden', blackIdle < 6);
}, 1000);
$('#screenblacksw').addEventListener('click', () => {
  const t = picTune(); t.enc = 'sw'; try { localStorage.setItem('picTune', JSON.stringify(t)); } catch {}
  blackIdle = 0; put($('#screenblackenc'), 'hidden', true);
  if (screenOn) startScreen();
});
$('#screennow').addEventListener('click', snapNow);
$('#screenbtn').addEventListener('click', () => { if (screenOn) stopScreen(); else startScreen(); });
try { const q = localStorage.getItem('quality'); if (q && [...$('#screenmode').options].some((o) => o.value === q)) $('#screenmode').value = q; } catch {}   // the level picked last time
picChosen = $('#screenmode').value;
picLevelSay();
$('#screenmodebtn').addEventListener('click', picDialog);
$('#screenmode').addEventListener('change', () => {
  picLevelSay(); picChosen = $('#screenmode').value; picWait = PIC_UP_MS;   // the person's own pick is the ceiling
  try { localStorage.setItem('quality', $('#screenmode').value); } catch {} if (screenOn) startScreen(); });
$('#screenfull').addEventListener('click', () => { const b = $('#screenbox'); if (document.fullscreenElement) document.exitFullscreen(); else if (b.requestFullscreen) b.requestFullscreen(); });
// Away (another app in front, a window over this one): the picture goes on for a while, so a glance elsewhere does not cut
// it; away longer it stops, sparing the TV its encoder, and comes back by itself on return.
let picAwayT = 0, picBack = false;
document.addEventListener('visibilitychange', () => {
  clearTimeout(picAwayT);
  if (document.hidden) { if (screenOn) picAwayT = setTimeout(() => { if (document.hidden && screenOn) { picBack = true; stopScreen(); } }, 15000); }
  else if (picBack) { picBack = false; if (!screenOn && !picking) startScreen(); }
  else snapNow();                                                     // back in view: one fresh shot
});
setScreenPlaceholder();

// ---------- tabs ----------
let currentTab = window.ONTV ? (location.search.match(/[?&]tab=(\w+)/) || [])[1] || 'apps' : 'remote', streamFps = 0;   // on the TV itself there is no Remote tab; TV Setup may name one
function showTab(t) {
  if (document.readyState === 'loading') return document.addEventListener('DOMContentLoaded', () => showTab(t), { once: true });   // a jump from the phone app can come before tvset.js etc. are read (crash 5b47665e)
  if ((CORE === 'phone' || window.ONTV) && (t === 'remote' || !document.querySelector('[data-tab="' + t + '"]'))) t = 'apps';   // the phone app has its own remote; the TV itself needs none
  if (SCOPE !== 'full') t = 'remote';                                  // a guest (a shared QR): the other tabs are the owner's
  currentTab = t; document.querySelector('main').classList.toggle('wide', t === 'remote' && !picking);
  document.querySelectorAll('[data-tab]').forEach((b) => { b.setAttribute('aria-selected', b.dataset.tab === t); b.tabIndex = b.dataset.tab === t ? 0 : -1; });   // roving tabindex: one stop in the bar, arrows move along it
  const off = document.querySelector('[data-tab="' + t + '"]')?.hasAttribute('data-kind-tab');   // this TV cannot: what it would do
  document.querySelectorAll('[data-panel]').forEach((p) => { p.hidden = p.dataset.panel !== (off ? 'kind' : t); });
  dockScreen();
  if (off) { $('#kindtab').replaceChildren(kindIntro(t)); return; }
  if (ADB.state === 'ready') document.querySelectorAll('[data-panel="' + t + '"]').forEach((p) => { p.dataset.seen = curHost || '-'; });   // kept on screen if ADB drops (applyAdb)
  if (ADB.state === 'ready') { if (t === 'apps' && !appsLoaded) loadApps(); if (t === 'files' && !filesLoaded) loadFiles(cwd); }
  if (t === 'tv' && CORE === 'pc') loadSteps();
  if (t === 'health') loadHealth();
  if (t === 'auto') autoTab();
  if (t === 'tv') { tsState(); restoreState(); loadTvset(); loadHold(); loadPaired(); homeCard(); chanCard(); gameCard(); }
}
// A jump from somewhere else (a menu of the remote, the phone app, a link): the tab, then the card [id] it is about, brought
// into view and lit for a moment, so nobody has to look for it. The tab's own loading may move it: placed a little later.
function goTo(t, id) {
  showTab(t);
  const c = id && document.getElementById(id);
  if (!c) return;
  setTimeout(() => {
    if (c.offsetParent === null) return;                             // not on this core / for this TV
    c.scrollIntoView({ block: 'center', behavior: 'smooth' });
    c.classList.remove('flash'); void c.offsetWidth; c.classList.add('flash');   // again from the start when asked twice
    c.addEventListener('animationend', () => c.classList.remove('flash'), { once: true });
  }, 250);
}
document.querySelectorAll('[data-tab]').forEach((b) => { b.id = 'tab-' + b.dataset.tab; b.addEventListener('click', () => showTab(b.dataset.tab)); });
document.querySelectorAll('[data-panel]').forEach((p) => { p.setAttribute('role', 'tabpanel'); p.setAttribute('aria-labelledby', 'tab-' + p.dataset.panel); });
document.querySelector('nav.tabs').addEventListener('keydown', (e) => {   // ← → Home End between the tabs that are shown
  const k = { ArrowRight: 1, ArrowLeft: -1, Home: 0, End: 0 }[e.key]; if (k === undefined) return;
  if (e.defaultPrevented) return;                                   // on the TV js/ontv.js has moved the focus: OK opens the tab, so passing one loads nothing
  const all = [...document.querySelectorAll('nav.tabs [data-tab]')].filter((b) => b.offsetParent !== null), i = all.indexOf(document.activeElement); if (i < 0) return;
  e.preventDefault(); const n = e.key === 'Home' ? all[0] : e.key === 'End' ? all[all.length - 1] : all[(i + k + all.length) % all.length];
  n.focus(); showTab(n.dataset.tab);
});

