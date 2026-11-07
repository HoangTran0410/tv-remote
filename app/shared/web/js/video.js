// 📹 A video or a camera in a box over the TV (Video.kt, VideoLayer in TV Setup): moved and resized while it plays.
// The stage is the TV's screen in small: the box is dragged, its corner resizes it, the arrows move it; x, y = the box's centre and
// s = its width, 0..10000 of the screen (VideoLayer keeps it whole on the screen, the page does the same so both look alike).
// rot turns the picture clockwise (0, 90, 180, 270): a phone's upright video lies across the TV; the box takes the turned shape.
const VB = { x: 7300, y: 7000, s: 4000, aspect: 16 / 9, rot: 0 };
let vbCam = null;                                                     // the camera being sent: { stop() }

const vbPlace = () => ({ x: Math.round(VB.x), y: Math.round(VB.y), s: Math.round(VB.s), rot: VB.rot });   // rot: an older TV Setup ignores it
const vbShape = () => VB.rot % 180 ? 1 / VB.aspect : VB.aspect;      // width / height of the box
const vbCanCam = () => !!(window.isSecureContext && window.VideoEncoder && window.VideoFrame && navigator.mediaDevices && navigator.mediaDevices.getUserMedia);

function vbFit() {                                                    // the box inside the stage (16:9), its height from the video's shape
  VB.s = Math.max(800, Math.min(10000, VB.s));
  let h = VB.s * (16 / 9) / vbShape();
  if (h > 10000) { h = 10000; VB.s = h * vbShape() / (16 / 9); }
  VB.x = Math.max(VB.s / 2, Math.min(10000 - VB.s / 2, VB.x));
  VB.y = Math.max(h / 2, Math.min(10000 - h / 2, VB.y));
  return h;
}

async function videoOpen() {
  if (await needAdbSoon() || !await setupFor('video')) return;
  let st = await api('/api/videobox', { o: 'q' }); if (!st) return;
  if (st.shown) { VB.x = st.x; VB.y = st.y; VB.s = st.s; if (st.aspect > 0) VB.aspect = st.aspect; if (st.rot >= 0) VB.rot = st.rot; }

  const prev = el('video', { muted: true, playsInline: true, loop: true, autoplay: true });
  prev.muted = true;
  prev.poster = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';   // no ▶ of the WebView's own on an empty box
  const grip = el('span', { className: 'vgrip', ariaHidden: 'true' });
  const box = el('div', { className: 'vbox', tabIndex: 0, role: 'group', ariaLabel: T('vbox.box'), title: T('vbox.box_tip') }, prev, grip);
  const stage = el('div', { className: 'vstage' }, box);
  const say = el('p', { className: 'state' });
  const input = el('input', { type: 'file', accept: 'video/*', hidden: true });
  const pick = el('label', { className: 'linkbtn filebtn' }, input, el('span', { textContent: '🎞️ ' + T('vbox.file') }));
  const cam = el('button', { type: 'button', textContent: '📷 ' + T('vbox.camera'), onclick: () => vbCam ? camStop() : camStart() });
  const turn = el('button', { type: 'button', className: 'vturn', title: T('vbox.rotate_tip'), ariaLabel: T('vbox.rotate'), onclick: async () => {
    if (st.rot === undefined && !await setupFor('videorot')) return;   // an older TV Setup would leave the picture as it is
    VB.rot = (VB.rot + 90) % 360; draw(); move();
  } }, '↷ ', el('span', { className: 'lbl', textContent: T('vbox.rotate') }));   // the word goes on a narrow phone (remote.css)
  const flip = el('button', { type: 'button', className: 'small', textContent: '🔄 ' + T('vbox.flip'), hidden: true, onclick: () => { facing = facing === 'user' ? 'environment' : 'user'; camStart(); } });
  const playb = el('button', { type: 'button', onclick: () => op({ o: st.playing ? 'pause' : 'play' }) });
  const muteb = el('button', { type: 'button', onclick: () => op({ o: 'mute', on: !st.muted }) });
  const stopb = el('button', { type: 'button', className: 'danger', textContent: '⏹ ' + T('vbox.stop'), onclick: async () => { camStop(); await op({ o: 'close' }); prev.removeAttribute('src'); prev.srcObject = null; } });
  let facing = 'user', url = '';
  if (!vbCanCam()) { cam.disabled = true; cam.title = T('vbox.cam_no'); }

  function draw() {
    const h = vbFit();
    box.style.left = (VB.x - VB.s / 2) / 100 + '%'; box.style.top = (VB.y - h / 2) / 100 + '%';
    box.style.width = VB.s / 100 + '%'; box.style.height = h / 100 + '%';
    const side = VB.rot % 180 !== 0, k = vbShape();                   // the small copy turned like the TV's: laid out unturned, then turned about its centre
    prev.style.width = side ? 100 / k + '%' : ''; prev.style.height = side ? 100 * k + '%' : '';
    prev.style.transform = VB.rot ? 'translate(-50%, -50%) rotate(' + VB.rot + 'deg)' : '';
    prev.classList.toggle('turned', !!VB.rot);
    box.classList.toggle('on', !!st.shown);
    put(prev, 'hidden', !prev.src && !prev.srcObject);
    const file = st.shown && !st.live;
    put(playb, 'hidden', !file); put(muteb, 'hidden', !file); stopb.disabled = !st.shown && !vbCam;
    playb.textContent = st.playing ? '⏸ ' + T('vbox.pause') : '▶ ' + T('vbox.play');
    muteb.textContent = st.muted ? '🔇 ' + T('vbox.sound_on') : '🔊 ' + T('vbox.sound_off');
    cam.textContent = vbCam ? '⏹ ' + T('vbox.camera_stop') : '📷 ' + T('vbox.camera');
    put(flip, 'hidden', !vbCam);
    say.textContent = st.err ? friendly(st.err) : vbCam ? T('vbox.cam_on') : !st.shown ? '' : st.playing ? T('vbox.on_tv') : T('vbox.paused');
    if (file && prev.src) { if (st.playing) prev.play().catch(() => {}); else prev.pause(); }   // the small copy follows the TV
    say.classList.toggle('bad', !!st.err);
  }
  async function op(o) {
    const r = await api('/api/videobox', o); if (!r) return null;
    st = r; if (r.aspect > 0 && !prev.videoWidth) VB.aspect = r.aspect; if (r.rot >= 0) VB.rot = r.rot; draw(); return r;   // the TV answers a new file with the last one's shape until it has read it: the copy here knows
  }
  let moving = 0, sending = false, again = false;                     // the box's place: its newest one, one request at a time
  function move() {
    if (!st.shown) return;
    clearTimeout(moving);
    moving = setTimeout(async () => {
      if (sending) { again = true; return; }
      sending = true;
      try { const r = await api('/api/videobox', { o: 'move', ...vbPlace() }, true); if (r) st = r; } finally { sending = false; }
      if (again) { again = false; move(); }
    }, 60);
  }

  // drag the box to move it, its corner to resize it (the top left corner stays)
  box.addEventListener('pointerdown', (e) => {
    e.preventDefault(); box.focus(); box.setPointerCapture(e.pointerId);
    const r = stage.getBoundingClientRect(), sx = e.clientX, sy = e.clientY, x0 = VB.x, y0 = VB.y, s0 = VB.s, h0 = vbFit();
    const left = x0 - s0 / 2, top = y0 - h0 / 2, sizing = e.target === grip;
    const onMove = (m) => {
      const dx = (m.clientX - sx) / r.width * 10000, dy = (m.clientY - sy) / r.height * 10000;
      if (sizing) { VB.s = s0 + Math.max(dx, dy * vbShape() / (16 / 9)); const h = VB.s * (16 / 9) / vbShape(); VB.x = left + VB.s / 2; VB.y = top + h / 2; }
      else { VB.x = x0 + dx; VB.y = y0 + dy; }
      draw(); move();
    };
    const up = () => { box.removeEventListener('pointermove', onMove); box.removeEventListener('pointerup', up); box.removeEventListener('pointercancel', up); };
    box.addEventListener('pointermove', onMove); box.addEventListener('pointerup', up); box.addEventListener('pointercancel', up);
  });
  box.addEventListener('keydown', (e) => {                            // arrows move it, + and - resize it (a TV's D-pad, a keyboard)
    const step = 300, k = e.key;
    if (k === 'ArrowLeft') VB.x -= step; else if (k === 'ArrowRight') VB.x += step;
    else if (k === 'ArrowUp') VB.y -= step; else if (k === 'ArrowDown') VB.y += step;
    else if (k === '+' || k === '=') VB.s += 500; else if (k === '-') VB.s -= 500;
    else return;
    e.preventDefault(); draw(); move();
  });

  input.addEventListener('change', () => {
    const f = input.files[0]; input.value = ''; if (!f) return;
    camStop();
    if (url) URL.revokeObjectURL(url);
    url = URL.createObjectURL(f); prev.srcObject = null; prev.src = url;
    prev.onloadedmetadata = () => { if (prev.videoWidth && prev.videoHeight) { VB.aspect = prev.videoWidth / prev.videoHeight; draw(); } };
    say.textContent = T('vbox.sending'); say.classList.remove('bad');
    upload(f, 'file', '/sdcard/Download', async (j) => {
      const p = /^saved (.+)$/.exec(j.msg || '');
      say.textContent = T('vbox.starting');
      await op({ o: 'file', path: j.dest || (p ? p[1] : '/sdcard/Download/' + f.name), sent: true, ...vbPlace() });
    });
  });

  function camStop() { if (vbCam) { vbCam.stop(); vbCam = null; } draw(); }
  async function camStart() {
    if (vbCam) { vbCam.stop(); vbCam = null; }
    prev.removeAttribute('src');
    say.textContent = T('vbox.cam_asking'); say.classList.remove('bad');
    try {
      vbCam = await vbCamera(prev, facing, (aspect) => { VB.aspect = aspect; draw(); }, (why) => {
        vbCam = null; st = { ...st, shown: false, live: false, err: why === 'gone' ? '' : why }; draw();
      });
      st = { ...st, shown: true, live: true, playing: true, err: '' };
    } catch (e) {
      vbCam = null; st = { ...st, err: e && e.name === 'NotAllowedError' ? 'video:camdenied' : 'video:cam ' + (e && e.message || e) };
    }
    draw();
  }

  const d = el('dialog', { className: 'ask menu vboxdlg' }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, el('h2', { textContent: '📹 ' + T('vbox.title') }),
      el('button', { className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
    el('div', { className: 'actions' }, pick, cam, flip, turn),
    stage, say,
    el('div', { className: 'actions' }, playb, muteb, stopb),
    el('p', { className: 'note', textContent: T('vbox.note') })));
  d.addEventListener('close', () => { camStop(); if (url) URL.revokeObjectURL(url); d.remove(); });   // a camera needs this page; a file plays on
  draw();
  document.body.append(d); d.showModal();
}

// The camera, encoded here (WebCodecs, H.264) and sent frame by frame in order; frames are skipped, never queued, when the
// link is slower than the camera, so the TV shows now, not a growing delay.
async function vbCamera(prev, facing, onAspect, onEnd) {
  const ms = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: facing, width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30, max: 30 } } });
  let ended = false, enc = null, chain = Promise.resolve(), waiting = 0, n = 0, timer = 0;
  const stop = (why) => {
    if (ended) return; ended = true;
    clearTimeout(timer); ms.getTracks().forEach((t) => t.stop()); prev.srcObject = null;
    try { if (enc && enc.state !== 'closed') enc.close(); } catch {}
    chain.then(() => fetch('/api/videobox/stop', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).catch(() => {});
    if (why) onEnd(why);
  };
  try {
    prev.srcObject = ms; await prev.play();
    const w = prev.videoWidth & ~1, h = prev.videoHeight & ~1;      // H.264 wants even sizes
    if (!w || !h) throw new Error('no picture');
    onAspect(w / h);
    const cfg = { codec: w * h > 921600 ? 'avc1.42E028' : 'avc1.42E01F', width: w, height: h, bitrate: w * h > 921600 ? 4e6 : 2.5e6, framerate: 30,
      latencyMode: 'realtime', avc: { format: 'annexb' } };
    const ok = await VideoEncoder.isConfigSupported(cfg).catch(() => ({ supported: false }));
    if (!ok.supported) throw new Error('video:codec');
    const r = await fetch('/api/videobox/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ w, h, ...vbPlace() }) });
    if (!r.ok) { let e = r.statusText; try { e = (await r.json()).error || e; } catch {} throw new Error(isStale(r.status, e) ? STALE : adbWords(r.status, e)); }
    enc = new VideoEncoder({
      output: (chunk) => {
        const b = new Uint8Array(chunk.byteLength); chunk.copyTo(b);
        const q = '/api/videobox/frame?k=' + (chunk.type === 'key' ? 1 : 0) + '&t=' + Math.max(0, Math.round(chunk.timestamp));
        waiting++;
        chain = chain.then(() => ended ? null : fetch(q, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: b })
          .then((x) => x.json()).then((x) => { if (!x.ok) stop('gone'); }))
          .catch(() => stop('video:link')).finally(() => waiting--);
      },
      error: (e) => stop('video:cam ' + (e && e.message || e)),
    });
    enc.configure(cfg);
    const t0 = performance.now();
    const tick = () => {
      if (ended) return;
      if (prev.readyState >= 2 && waiting < 4 && enc.encodeQueueSize < 2) {   // behind: this frame is skipped, the next goes
        const f = new VideoFrame(prev, { timestamp: Math.round((performance.now() - t0) * 1000) });
        enc.encode(f, { keyFrame: n % 60 === 0 }); f.close(); n++;
      }
      timer = setTimeout(tick, 33);
    };
    tick();
  } catch (e) { stop(); throw e; }
  return { stop: () => stop() };
}
