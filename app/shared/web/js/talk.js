// Speaking into this computer where its window cannot turn speech into words (Windows' and Linux's web views have no speech
// service; the Mac's and Chrome's have, remote.js): the microphone goes to the TV's Assistant as a voice remote's would
// (/api/voice, Atv.voiceBegin), and the TV hears it. The computer app only: the phone app has its own screen, and the TV's
// page has no remote link to the TV.
// The Mac's web view has a speech service that can still refuse ("service-not-allowed": Dictation off, or the window not let in):
// then this way, from then on
let noSpeech = false;
try { noSpeech = !!localStorage.getItem('nospeech'); } catch {}
const talkable = () => CORE === 'pc' && window.isSecureContext && !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
  && (noSpeech || !(window.SpeechRecognition || window.webkitSpeechRecognition));
function speechRefused() { noSpeech = true; try { localStorage.setItem('nospeech', '1'); } catch {} return talkable(); }
let talking = null;

// The listening dialog, for both ways of hearing (here and remote.js hear()): what is heard so far, bars that rise and fall
// with the microphone (so a dead or muted one shows at once), Done to send now and Cancel to send nothing. Esc cancels.
let hearing = null;                                                  // { done, cancel } of what listens now
function hearOpen(h, stream) {
  hearing = h;
  const t = $('#heartext'); put(t, 'textContent', T('heardlg.say')); t.classList.remove('got');
  if (!$('#heardlg').open) $('#heardlg').showModal();
  hearMeter(stream);
}
function hearWords(words) { put('#heartext', 'textContent', words); $('#heartext').classList.add('got'); }
function hearClose() { hearing = null; hearMeter(null); if ($('#heardlg').open) $('#heardlg').close(); }
$('#hearok').addEventListener('click', () => hearing && hearing.done());
$('#hearcancel').addEventListener('click', () => hearing && hearing.cancel());
$('#heardlg').addEventListener('cancel', (e) => { e.preventDefault(); if (hearing) hearing.cancel(); });
let meterStop = null;
function hearMeter(stream) {                                         // null: stop drawing
  if (meterStop) { meterStop(); meterStop = null; }
  const c = $('#hearwave'); put(c, 'hidden', !stream); if (!stream) return;
  const ac = new AudioContext(), a = ac.createAnalyser(); a.fftSize = 1024; ac.createMediaStreamSource(stream).connect(a);
  const dpr = devicePixelRatio || 1; c.width = c.clientWidth * dpr; c.height = c.clientHeight * dpr;
  const g = c.getContext('2d'), buf = new Float32Array(a.fftSize), bars = new Array(28).fill(0);
  const ink = getComputedStyle(c).getPropertyValue('--accent').trim() || '#3a6df0';
  let raf = 0, n = 0;
  const frame = () => {
    raf = requestAnimationFrame(frame);
    a.getFloatTimeDomainData(buf); let sum = 0; for (const v of buf) sum += v * v;
    const lvl = Math.min(1, Math.sqrt(Math.sqrt(sum / buf.length)) * 2.2);   // square root again: a quiet voice still shows
    if (++n % 4 === 0) { bars.shift(); bars.push(lvl); } else bars[bars.length - 1] = Math.max(bars[bars.length - 1], lvl);
    const W = c.width, H = c.height, w = W / bars.length; g.clearRect(0, 0, W, H); g.fillStyle = ink;
    bars.forEach((b, i) => { const h = Math.max(4 * dpr, b * H); g.globalAlpha = 0.35 + 0.65 * (i + 1) / bars.length; g.fillRect(i * w + w * 0.2, (H - h) / 2, w * 0.6, h); });
  };
  frame();
  meterStop = () => { cancelAnimationFrame(raf); ac.close(); };
}

async function talk() {
  if (talking) { talking.stop(); return; }
  talking = { stop() {} };                                         // a second click while the microphone is asked for: nothing
  const lit = (on) => $('#voice').classList.toggle('on', on);
  let mic, ac, src, node;
  try { mic = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } }); }
  catch { talking = null; toast(T('remote.error_why', { why: T('remote.mic_not_allowed') })); return; }
  // 8 kHz, what the TV takes; 4096 samples a piece (8 KB, half a second): the TV pads smaller ones with silence
  try { ac = new AudioContext({ sampleRate: 8000 }); src = ac.createMediaStreamSource(mic); node = ac.createScriptProcessor(4096, 1, 1); }
  catch (e) { talking = null; mic.getTracks().forEach((t) => t.stop()); if (ac) ac.close(); toast(T('remote.error_why', { why: e.message || e })); return; }
  const t0 = performance.now();
  let id = null, sent = Promise.resolve(), held = [], heard = 0, quiet = 0, done = false, dropped = false;
  let warned = false;
  const warn = (text) => { if (!warned) { warned = true; toast(text); } };   // one message for a run of failed posts
  const send = (pcm, end) => {                                     // one after another: the TV plays them in the order sent
    sent = sent.then(async () => {
      try {
        const r = await fetch('/api/voice?do=' + (end ? 'end' : 'data') + '&id=' + id,
          { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: pcm });
        if (!r.ok) { let e = r.statusText; try { e = (await r.json()).error || e; } catch {} warn(T('common.error_msg', { msg: friendly(e) })); return; }
        if (end && !warned) toast(T('remote.talk_sent'));          // said only when the TV took the end
      } catch { warn(T('core.server_unreachable')); }
    });
  };
  const stop = (drop) => {
    if (done) return;
    done = true; dropped = !!drop; talking = null; lit(false); hearClose();
    node.disconnect(); src.disconnect(); mic.getTracks().forEach((t) => t.stop()); ac.close();
    if (id === null) return;                                       // not started yet: the start's answer finishes it
    if (drop) api('/api/key', { key: 'BACK' }, true);              // no end: the TV would act on the half heard; Back shuts it
    else send(new Uint8Array(0), true);
  };
  talking = { stop }; lit(true);
  hearOpen({ done: () => stop(), cancel: () => stop(true) }, mic);
  node.onaudioprocess = (e) => {
    if (done) return;
    const f = e.inputBuffer.getChannelData(0), pcm = new Int16Array(f.length);
    let sum = 0;
    for (let i = 0; i < f.length; i++) { const v = Math.max(-1, Math.min(1, f[i])); pcm[i] = v * 32767; sum += v * v; }
    if (id === null) held.push(pcm); else send(pcm);
    const ms = f.length / 8;
    if (Math.sqrt(sum / f.length) > 0.02) { heard += ms; quiet = 0; } else quiet += ms;
    const t = performance.now() - t0;                              // done speaking, never spoke, or too long
    if ((heard && quiet > 1200) || (!heard && t > 7000) || t > 15000) stop();
  };
  src.connect(node); node.connect(ac.destination);
  const r = await api('/api/voice?do=start', {});                  // the TV opens its Assistant, listening to us
  if (!r) { held = []; stop(true); return; }
  if (dropped) { api('/api/key', { key: 'BACK' }, true); return; } // cancelled while the TV was opening its Assistant
  id = r.id; held.forEach((p) => send(p)); held = [];
  if (done) send(new Uint8Array(0), true);
}
