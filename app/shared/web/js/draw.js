// ✏️ Drawing on the TV's screen over the picture here: TV Setup draws the same strokes over whatever is on the TV, for the
// person watching (DrawLayer.kt there, Draw.kt for the way strokes go). Points are sent in 0..10000 of the picture's size.
// What is drawn here fades soon after the finger lifts: by then the TV's picture shows it, from the TV itself.
let drawing = false, drawColor = '#ff3b30', drawWidth = 60, drawId = 0, drawQ = [], drawSending = false, drawPen = null;
const drawLocal = [];                                                  // {pts, color, width, upAt} or {pic, x, y, w, upAt}: drawn here until they fade
// 🖼️ a picture being placed: only here until ✓, then it goes to the TV once, where it stays put like a stroke
let drawPic = null;                                                    // {img, b, x, y, w}: centre and width in 0..10000 of the screen

function drawRect() {                                                  // where the picture is inside the box (it letterboxes)
  const box = $('#screenbox').getBoundingClientRect(), [nw, nh] = picSize(screenEl()).map((v, i) => v || [16, 9][i]);
  const k = Math.min(box.width / nw, box.height / nh), w = nw * k, h = nh * k;
  return { box, x: (box.width - w) / 2, y: (box.height - h) / 2, w, h };
}

function drawSize() {
  const c = $('#drawcv'), r = $('#screenbox').getBoundingClientRect(), d = window.devicePixelRatio || 1;
  if (c.width !== Math.round(r.width * d) || c.height !== Math.round(r.height * d)) { c.width = Math.round(r.width * d); c.height = Math.round(r.height * d); }
}

function drawPaint() {
  const c = $('#drawcv'), g = c.getContext('2d'), d = window.devicePixelRatio || 1, r = drawRect(), now = Date.now();
  drawSize();
  g.setTransform(d, 0, 0, d, 0, 0); g.clearRect(0, 0, c.width, c.height);
  g.lineCap = 'round'; g.lineJoin = 'round';
  for (let i = drawLocal.length - 1; i >= 0; i--) if (drawLocal[i].upAt && now - drawLocal[i].upAt > 1600) drawLocal.splice(i, 1);
  const pic = (p, alpha) => {
    const w = p.w / 10000 * r.w, h = w * p.img.naturalHeight / p.img.naturalWidth, x = r.x + p.x / 10000 * r.w - w / 2, y = r.y + p.y / 10000 * r.h - h / 2;
    g.globalAlpha = alpha; g.drawImage(p.img, x, y, w, h); g.globalAlpha = 1;
    return [x, y, w, h];
  };
  for (const s of drawLocal) {
    if (s.img) { pic(s, s.upAt ? Math.max(0, 1 - (now - s.upAt) / 1600) : 1); continue; }
    if (!s.pts.length) continue;
    g.globalAlpha = s.upAt ? Math.max(0, 1 - (now - s.upAt) / 1600) : 1;
    g.strokeStyle = s.color; g.lineWidth = Math.max(2, s.width / 10000 * r.w);
    g.beginPath();
    s.pts.forEach(([x, y], i) => { const px = r.x + x / 10000 * r.w, py = r.y + y / 10000 * r.h; if (i) g.lineTo(px, py); else { g.moveTo(px, py); g.lineTo(px + .1, py); } });
    g.stroke();
  }
  g.globalAlpha = 1;
  if (drawPic) {                                                       // where it will land: a dashed frame around it
    const [x, y, w, h] = pic(drawPic, 1);
    g.setLineDash([6, 4]); g.lineWidth = 2; g.strokeStyle = '#fff'; g.strokeRect(x, y, w, h); g.setLineDash([]);
  }
  if (drawLocal.some((s) => s.upAt)) requestAnimationFrame(drawPaint);
}

// One request at a time; what comes meanwhile goes in the next, so a slow link never falls behind the finger.
function drawSend(op) {
  const last = drawQ[drawQ.length - 1];
  if (op.o === 'a' && last && last.o !== 'u' && last.o !== 'x' && last.id === op.id) last.p.push(...op.p); else drawQ.push(op);
  if (!drawSending) drawFlush();
}
async function drawFlush() {
  drawSending = true;
  while (drawQ.length && drawing) {
    const ops = drawQ; drawQ = [];
    const r = await fetch('/api/draw', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ops }) }).catch(() => null);
    if (!r || !r.ok) {
      drawStop(); drawFail(await drawWhy(r));
      break;
    }
  }
  drawSending = false;
}

// the server's {"error": ...} in words, not the reply's raw text
async function drawWhy(r) {
  if (!r) return '';
  let e = r.statusText; try { e = (await r.json()).error || e; } catch {}
  if (r.status === 401 && CORE === 'tv' || r.status === 403 && e === 'scope') refresh();
  return adbWords(r.status, e);
}
// An old or missing TV Setup comes as needSetup's question (update or install it), before anything is drawn when it can.
function drawFail(why) {
  if (!needSetup(why)) toast(/draw:nooverlay/.test(why) ? T('draw.no_overlay') : T('draw.cannot_draw', { why: friendly(why || T('draw.connection_lost')) }));
}

let drawPics = true;                                                   // this TV Setup takes pictures: known once drawing starts
async function drawStart() {
  if (!screenOn) startScreen();                                        // the strokes go where the picture shows
  drawing = true; drawQ = [];
  put('#drawcv', 'hidden', false); put('#drawbar', 'hidden', false); $('#r-draw').classList.add('on');
  drawSize();
  const r = await fetch('/api/draw', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"ops":[]}' }).catch(() => null);
  if (!drawing) return;
  if (!r || !r.ok) { const why = await drawWhy(r); drawStop(); drawFail(why); return; }
  drawPics = (await r.json().catch(() => ({}))).pic !== false;
}
function drawStop() {
  drawing = false; drawPen = null; drawLocal.length = 0; picEnd();
  put('#drawcv', 'hidden', true); put('#drawbar', 'hidden', true); $('#r-draw').classList.remove('on');
}
$('#drawdone').addEventListener('click', drawStop);
$('#drawvid').addEventListener('click', () => { drawStop(); videoOpen(); });   // the same Video on TV box as the TV card's (video.js), not a second one
$('#drawundo').addEventListener('click', () => drawSend({ o: 'u' }));
$('#drawclear').addEventListener('click', () => { drawLocal.length = 0; drawPaint(); drawSend({ o: 'x' }); });
// one swatch for the colour in use; the colours and the line's size open under it, and close once one is picked (or on a tap elsewhere)
$('#drawcolor').addEventListener('click', (e) => { e.stopPropagation(); put('#drawcolors', 'hidden', !$('#drawcolors').hidden); });
document.addEventListener('click', (e) => { if (!e.target.closest('#drawcolors')) put('#drawcolors', 'hidden', true); });
document.querySelectorAll('#drawbar [data-color]').forEach((b) => b.addEventListener('click', () => {
  drawColor = b.dataset.color; $('#drawcolor').style.setProperty('--c', drawColor); drawDot(); put('#drawcolors', 'hidden', true);
  document.querySelectorAll('#drawbar [data-color]').forEach((x) => x.classList.toggle('on', x === b));
}));
// the dot beside the slider: the line's size and colour (a fifth of the TV-width units, as on a phone's picture)
const drawDot = () => { const d = Math.max(4, drawWidth / 5) + 'px'; Object.assign($('#drawdot').style, { width: d, height: d, background: drawColor }); };
$('#drawwidth').addEventListener('input', (e) => { drawWidth = +e.target.value; drawDot(); });

// 🖼️ Pick, place (drag; size with two fingers or the wheel), then ✓ sends it. At most 1280 pixels: plenty for a TV
// layer, and a phone photo stays a few hundred KB on its way.
$('#drawpic').addEventListener('click', () => { if (drawPics) $('#drawfile').click(); else setupFor('pic'); });   // the file picker must open within the tap: known beforehand
$('#drawfile').addEventListener('change', async (e) => {
  const f = e.target.files[0]; e.target.value = ''; if (!f) return;
  const img = new Image(), url = URL.createObjectURL(f);
  try { img.src = url; await img.decode(); } catch { toast(T('draw.cannot_open_picture')); return; } finally { URL.revokeObjectURL(url); }
  const k = Math.min(1, 1280 / Math.max(img.naturalWidth, img.naturalHeight)), c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(img.naturalWidth * k)); c.height = Math.max(1, Math.round(img.naturalHeight * k));
  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
  let data = /png|webp|gif/.test(f.type) ? c.toDataURL('image/png') : '';             // these may be see-through: keep that
  if (!data || data.length > 4e6) data = c.toDataURL('image/jpeg', 0.85);
  picPlace(data, 4000);
});
async function picPlace(data, w) {
  const shown = new Image(); shown.src = data; await shown.decode().catch(() => {});
  drawPic = { img: shown, b: data.slice(data.indexOf(',') + 1), x: 5000, y: 5000, w, base: w };   // base: 1x
  put('#drawbar', 'hidden', true); put('#picbar', 'hidden', false); picWidth(drawPic.w);
}

// 💬 A message, as big words on the TV ("Xuống ăn cơm!"): drawn here into a picture, then placed and sent like one, so it
// moves, sizes and goes away with the strokes, and any TV Setup that takes pictures shows it. The same look as the phone
// app's (messagePic in Drawing.kt): the pen's colour on a dark box (a light one for black), lines kept under 1100 pixels.
$('#drawtext').addEventListener('click', async () => {
  if (!drawPics) { setupFor('pic'); return; }
  const text = (await ask(T('draw.message_for_tv'), { value: '', ok: T('draw.next') }) || '').trim();
  if (!text) return;
  const c = document.createElement('canvas'), g = c.getContext('2d'), font = '700 72px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
  g.font = font;
  const lines = [];
  for (const para of text.split(/\n/)) {
    let line = '';
    for (const word of para.split(/\s+/)) {
      const next = line ? line + ' ' + word : word;
      if (line && g.measureText(next).width > 1100) { lines.push(line); line = word; } else line = next;
    }
    lines.push(line);
  }
  const pad = 40, lh = 92, w = Math.ceil(Math.max(...lines.map((l) => g.measureText(l).width))) + pad * 2, h = lines.length * lh + pad * 2 - 20;
  c.width = w; c.height = h;
  const dark = drawColor === '#000000';
  g.fillStyle = dark ? 'rgba(255,255,255,.9)' : 'rgba(0,0,0,.85)';
  g.beginPath(); g.roundRect(0, 0, w, h, 32); g.fill();
  g.font = font; g.fillStyle = drawColor; g.textBaseline = 'top'; g.textAlign = 'center';
  lines.forEach((l, i) => g.fillText(l, w / 2, pad + i * lh));
  if (!drawing) await drawStart();
  picPlace(c.toDataURL('image/png'), Math.min(9000, Math.round(w / 1920 * 10000)));   // 72px words as on a 1080p TV
});
function picEnd() { drawPic = null; picTouch.clear(); put('#picbar', 'hidden', true); if (drawing) put('#drawbar', 'hidden', false); drawPaint(); }
// 1x is the size it starts at (a picture: 4000 = 40% of the screen's width); down to 0.1x, up to the screen's width
function picWidth(w) { drawPic.w = Math.round(Math.min(10000, Math.max(400, w))); put('#picscale', 'textContent', (drawPic.w / drawPic.base).toFixed(1) + 'x'); drawPaint(); }
// − and +: a tap is one step, holding keeps going (faster the longer), like dragging a slider that springs back
[['#picsmaller', 1 / 1.06], ['#picbigger', 1.06]].forEach(([id, k]) => {
  let timer = null;
  const stop = () => { clearTimeout(timer); timer = null; };
  $(id).addEventListener('pointerdown', (e) => {
    if (!drawPic || e.button) return;
    e.preventDefault(); stop();
    let n = 0;
    const step = () => { if (!drawPic) return stop(); picWidth(drawPic.w * k); timer = setTimeout(step, n++ ? Math.max(30, 120 - n * 6) : 350); };
    step();
  });
  ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => $(id).addEventListener(ev, stop));
});
$('#piccancel').addEventListener('click', picEnd);
$('#picok').addEventListener('click', () => {
  const p = drawPic; if (!p) return;
  drawSend({ o: 'i', id: ++drawId, b: p.b, x: Math.round(p.x), y: Math.round(p.y), w: p.w });
  drawLocal.push({ img: p.img, x: p.x, y: p.y, w: p.w, upAt: Date.now() });
  picEnd();
});
const picTouch = new Map();                                            // pointerId -> [x, y] in 0..10000, while placing
$('#drawcv').addEventListener('wheel', (e) => { if (!drawPic) return; e.preventDefault(); picWidth(drawPic.w * (e.deltaY < 0 ? 1.08 : 1 / 1.08)); }, { passive: false });

const drawAt = (e) => {
  const r = drawRect(), x = (e.clientX - r.box.left - r.x) / r.w, y = (e.clientY - r.box.top - r.y) / r.h;
  return x < 0 || x > 1 || y < 0 || y > 1 ? null : [Math.round(x * 10000), Math.round(y * 10000)];
};
$('#drawcv').addEventListener('pointerdown', (e) => {
  const p = drawAt(e); if (e.button || !p) return;
  if (drawPic) { e.preventDefault(); try { $('#drawcv').setPointerCapture(e.pointerId); } catch {} picTouch.set(e.pointerId, p); return; }
  e.preventDefault(); try { $('#drawcv').setPointerCapture(e.pointerId); } catch {}
  drawPen = { id: ++drawId, pts: [p], color: drawColor, width: drawWidth, upAt: 0 };
  drawLocal.push(drawPen); drawPaint();
  drawSend({ o: 's', id: drawPen.id, c: drawColor, w: drawWidth, p: [...p] });
});
$('#drawcv').addEventListener('pointermove', (e) => {
  if (drawPic && picTouch.has(e.pointerId)) {
    const p = drawAt(e); if (!p) return;
    const was = [...picTouch.values()], old = picTouch.get(e.pointerId); picTouch.set(e.pointerId, p);
    if (picTouch.size === 1) { drawPic.x = Math.min(10000, Math.max(0, drawPic.x + p[0] - old[0])); drawPic.y = Math.min(10000, Math.max(0, drawPic.y + p[1] - old[1])); drawPaint(); }
    else {                                                             // two fingers: apart is bigger, together smaller
      const now = [...picTouch.values()], d = (a) => Math.hypot(a[0][0] - a[1][0], a[0][1] - a[1][1]) || 1;
      picWidth(drawPic.w * d(now) / d(was));
    }
    return;
  }
  if (!drawPen) return;
  const p = drawAt(e); if (!p) return;
  const [lx, ly] = drawPen.pts[drawPen.pts.length - 1];
  if (Math.abs(p[0] - lx) + Math.abs(p[1] - ly) < 25) return;            // a few pixels on the TV: not worth a point
  drawPen.pts.push(p); drawPaint();
  drawSend({ o: 'a', id: drawPen.id, p: [...p] });
});
['pointerup', 'pointercancel'].forEach((ev) => $('#drawcv').addEventListener(ev, (e) => { picTouch.delete(e.pointerId); if (drawPen) { drawPen.upAt = Date.now(); drawPen = null; drawPaint(); } }));
window.addEventListener('resize', () => { if (drawing) drawPaint(); });
