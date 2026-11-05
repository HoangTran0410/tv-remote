// 🏞️ TV Home's drawn backgrounds, as the page's small previews: the SAME recipe the TV paints (app/tvhome WallArt.kt), written once
// as data (HomeConfig's WallSpec.kt holds a copy: tests/home_walls.test.js fails when the two differ) and drawn by two small
// painters that do the same maths. Every size is a share of the picture: x, r of its width, y of its height.
//   bg     the sky: colour stops [hex, at] along a straight line at `ang` degrees (0 = top to bottom, 90 = left to right)
//   layers drawn in order:
//     glow  a soft light: centre x y, radius r (of the width) squashed to `sy` of itself, colour c fading out from alpha a
//     disc  a sun or a bubble: the same with a soft edge
//     hill  a silhouette across the picture down to the bottom: edge y, swinging up and down by `amp` (of the height) along the waves
//           w = [[turns, phase, weight]...]; `ridge` 1 = sharp peaks; filled top → bot (ta / ba alpha)
//   shade  how much of TV Home's dark top-and-bottom veil goes over it (the words stay readable), 1 = what a photo gets
const HOME_ART = {
  "dawn": {
    "bg": [["#1a2147", 0], ["#51376d", 0.42], ["#b8506a", 0.7], ["#ee9a5c", 1]], "ang": 0, "shade": 0.55,
    "layers": [
      {"k": "glow", "x": 0.64, "y": 0.7, "r": 0.62, "sy": 0.55, "c": "#ffb070", "a": 0.8},
      {"k": "disc", "x": 0.64, "y": 0.68, "r": 0.055, "c": "#ffe0a8", "a": 1},
      {"k": "hill", "y": 0.76, "amp": 0.05, "w": [[1.3, 0.4, 1], [2.7, 2.1, 0.5]], "top": "#6a3f5e", "bot": "#3c2344"},
      {"k": "hill", "y": 0.84, "amp": 0.05, "w": [[1.1, 1.9, 1], [3.1, 0.3, 0.45]], "top": "#40254a", "bot": "#25152f"},
      {"k": "hill", "y": 0.92, "amp": 0.035, "w": [[1.6, 0.9, 1], [3.7, 2.4, 0.4]], "top": "#1f1229", "bot": "#120a1a"}
    ]
  },
  "peaks": {
    "bg": [["#1b2a45", 0], ["#385676", 0.5], ["#7d9bb0", 0.8]], "ang": 0, "shade": 0.6,
    "layers": [
      {"k": "glow", "x": 0.3, "y": 0.22, "r": 0.5, "sy": 0.6, "c": "#cfe3f2", "a": 0.28},
      {"k": "hill", "y": 0.5, "amp": 0.13, "ridge": 1, "w": [[1.7, 0.2, 1], [3.9, 1.1, 0.8], [7.3, 0.6, 0.5]], "top": "#7391aa", "bot": "#4e6b86"},
      {"k": "hill", "y": 0.62, "amp": 0.13, "ridge": 1, "w": [[1.3, 1.4, 1], [3.1, 0.3, 0.8], [6.2, 1.7, 0.5]], "top": "#4c6985", "bot": "#314c66"},
      {"k": "hill", "y": 0.75, "amp": 0.11, "ridge": 1, "w": [[1.9, 0.8, 1], [4.3, 1.9, 0.8], [8.1, 0.2, 0.5]], "top": "#2f4860", "bot": "#1e3347"},
      {"k": "hill", "y": 0.89, "amp": 0.07, "ridge": 1, "w": [[1.4, 0.1, 1], [3.3, 1.2, 0.5]], "top": "#1a2d3e", "bot": "#0e1b27"}
    ]
  },
  "dunes": {
    "bg": [["#2a1d33", 0], ["#7b4a48", 0.5], ["#d38c55", 0.82]], "ang": 0, "shade": 0.65,
    "layers": [
      {"k": "glow", "x": 0.8, "y": 0.5, "r": 0.5, "sy": 0.7, "c": "#ffc084", "a": 0.55},
      {"k": "disc", "x": 0.8, "y": 0.5, "r": 0.04, "c": "#ffe6bd", "a": 0.95},
      {"k": "hill", "y": 0.66, "amp": 0.055, "w": [[1.0, 0.3, 1], [2.3, 1.0, 0.35]], "top": "#a8613c", "bot": "#7e4129"},
      {"k": "hill", "y": 0.76, "amp": 0.06, "w": [[1.2, 2.1, 1], [2.9, 0.4, 0.4]], "top": "#7f4429", "bot": "#58301f"},
      {"k": "hill", "y": 0.86, "amp": 0.05, "w": [[0.9, 0.6, 1], [2.2, 1.5, 0.4]], "top": "#552b1c", "bot": "#35190f"},
      {"k": "hill", "y": 0.94, "amp": 0.03, "w": [[1.5, 1.2, 1], [3.4, 0.2, 0.3]], "top": "#26120b", "bot": "#190b07"}
    ]
  },
  "waves": {
    "bg": [["#06222c", 0], ["#0e5160", 0.55], ["#0a3a49", 1]], "ang": 0, "shade": 0.6,
    "layers": [
      {"k": "glow", "x": 0.75, "y": 0.04, "r": 0.55, "sy": 0.8, "c": "#7fe3d2", "a": 0.38},
      {"k": "hill", "y": 0.5, "amp": 0.03, "w": [[2.6, 0, 1], [5.1, 1.2, 0.5]], "top": "#2c8d97", "bot": "#0f4d5a", "ta": 0.55, "ba": 0.7},
      {"k": "hill", "y": 0.62, "amp": 0.035, "w": [[2.1, 1.7, 1], [4.6, 0.4, 0.5]], "top": "#1f7783", "bot": "#0b4452", "ta": 0.7, "ba": 0.85},
      {"k": "hill", "y": 0.75, "amp": 0.04, "w": [[1.8, 0.9, 1], [4.1, 2.0, 0.45]], "top": "#155f6c", "bot": "#08353f", "ta": 0.85, "ba": 0.95},
      {"k": "hill", "y": 0.88, "amp": 0.04, "w": [[1.5, 2.4, 1], [3.6, 1.1, 0.4]], "top": "#0d4854", "bot": "#052630"}
    ]
  },
  "candy": {
    "bg": [["#2b2152", 0], ["#3b2860", 1]], "ang": 135, "shade": 0.85,
    "layers": [
      {"k": "glow", "x": 0.18, "y": 0.22, "r": 0.6, "sy": 0.85, "c": "#ff7eb3", "a": 0.6},
      {"k": "glow", "x": 0.82, "y": 0.2, "r": 0.55, "sy": 0.85, "c": "#7c83ff", "a": 0.65},
      {"k": "glow", "x": 0.55, "y": 0.9, "r": 0.65, "sy": 0.7, "c": "#ffb38a", "a": 0.5},
      {"k": "glow", "x": 0.92, "y": 0.85, "r": 0.4, "sy": 0.9, "c": "#b07cff", "a": 0.5}
    ]
  },
  "peach": {
    "bg": [["#3a1322", 0], ["#4a1a2a", 1]], "ang": 135, "shade": 0.85,
    "layers": [
      {"k": "glow", "x": 0.14, "y": 0.3, "r": 0.62, "sy": 0.9, "c": "#ff7a59", "a": 0.6},
      {"k": "glow", "x": 0.7, "y": 0.12, "r": 0.5, "sy": 0.8, "c": "#ffb347", "a": 0.45},
      {"k": "glow", "x": 0.86, "y": 0.82, "r": 0.55, "sy": 0.85, "c": "#e8416c", "a": 0.6},
      {"k": "glow", "x": 0.4, "y": 0.95, "r": 0.5, "sy": 0.6, "c": "#ff9a76", "a": 0.4}
    ]
  },
  "meadow": {
    "bg": [["#14303a", 0], ["#3f6b5e", 0.45], ["#c9c985", 0.78]], "ang": 0, "shade": 0.7,
    "layers": [
      {"k": "glow", "x": 0.26, "y": 0.6, "r": 0.5, "sy": 0.6, "c": "#fff0a8", "a": 0.6},
      {"k": "disc", "x": 0.26, "y": 0.6, "r": 0.04, "c": "#fff6cc", "a": 0.95},
      {"k": "hill", "y": 0.66, "amp": 0.06, "w": [[1.2, 0.5, 1], [2.8, 1.6, 0.4]], "top": "#6e9448", "bot": "#43703a"},
      {"k": "hill", "y": 0.76, "amp": 0.06, "w": [[1.0, 2.0, 1], [2.5, 0.3, 0.45]], "top": "#437a3c", "bot": "#295429"},
      {"k": "hill", "y": 0.86, "amp": 0.05, "w": [[1.4, 0.7, 1], [3.2, 1.8, 0.4]], "top": "#2a5a2c", "bot": "#173a1d"},
      {"k": "hill", "y": 0.94, "amp": 0.03, "w": [[1.7, 1.1, 1], [3.9, 0.5, 0.3]], "top": "#153219", "bot": "#0d2212"}
    ]
  },
  "mist": {
    "bg": [["#27393a", 0], ["#47605f", 0.5], ["#95a8a3", 0.8]], "ang": 0, "shade": 0.65,
    "layers": [
      {"k": "hill", "y": 0.52, "amp": 0.1, "ridge": 1, "w": [[5.3, 0.3, 1], [11, 1.0, 0.6], [2.2, 0.5, 0.9]], "top": "#6c8480", "bot": "#4d6764"},
      {"k": "glow", "x": 0.5, "y": 0.64, "r": 0.9, "sy": 0.22, "c": "#cfe0dc", "a": 0.4},
      {"k": "hill", "y": 0.64, "amp": 0.1, "ridge": 1, "w": [[4.7, 1.2, 1], [9.8, 0.4, 0.6], [1.9, 1.5, 0.9]], "top": "#486260", "bot": "#33494a"},
      {"k": "glow", "x": 0.5, "y": 0.76, "r": 0.9, "sy": 0.2, "c": "#b9ccc8", "a": 0.3},
      {"k": "hill", "y": 0.77, "amp": 0.09, "ridge": 1, "w": [[6.1, 0.7, 1], [12.4, 1.9, 0.5], [2.6, 0.2, 0.8]], "top": "#2c4343", "bot": "#1d2f2f"},
      {"k": "hill", "y": 0.89, "amp": 0.07, "ridge": 1, "w": [[5.4, 1.6, 1], [10.3, 0.1, 0.5], [2.1, 1.1, 0.8]], "top": "#1a2a2a", "bot": "#0f1919"}
    ]
  },
  "bokeh": {
    "bg": [["#06222a", 0], ["#0d4650", 0.6], ["#092a39", 1]], "ang": 160, "shade": 0.7,
    "layers": [
      {"k": "disc", "x": 0.12, "y": 0.22, "r": 0.09, "c": "#7fe3d0", "a": 0.16},
      {"k": "disc", "x": 0.3, "y": 0.7, "r": 0.12, "c": "#9ad0ff", "a": 0.12},
      {"k": "disc", "x": 0.46, "y": 0.18, "r": 0.06, "c": "#ffe29a", "a": 0.2},
      {"k": "disc", "x": 0.58, "y": 0.52, "r": 0.1, "c": "#7fe3d0", "a": 0.14},
      {"k": "disc", "x": 0.72, "y": 0.8, "r": 0.075, "c": "#ffe29a", "a": 0.18},
      {"k": "disc", "x": 0.82, "y": 0.28, "r": 0.13, "c": "#9ad0ff", "a": 0.13},
      {"k": "disc", "x": 0.93, "y": 0.62, "r": 0.05, "c": "#7fe3d0", "a": 0.22},
      {"k": "disc", "x": 0.04, "y": 0.86, "r": 0.06, "c": "#ffe29a", "a": 0.15},
      {"k": "disc", "x": 0.66, "y": 0.1, "r": 0.035, "c": "#ffffff", "a": 0.18},
      {"k": "disc", "x": 0.2, "y": 0.5, "r": 0.04, "c": "#ffffff", "a": 0.12}
    ]
  },
  "canyon": {
    "bg": [["#2a1522", 0], ["#86403a", 0.5], ["#de885a", 0.76]], "ang": 0, "shade": 0.65,
    "layers": [
      {"k": "glow", "x": 0.5, "y": 0.62, "r": 0.7, "sy": 0.4, "c": "#ffb080", "a": 0.5},
      {"k": "hill", "y": 0.6, "amp": 0.08, "w": [[1.2, 0.2, 1], [2.9, 1.7, 0.5], [6.3, 0.9, 0.2]], "top": "#b0533b", "bot": "#872f26"},
      {"k": "hill", "y": 0.71, "amp": 0.08, "ridge": 1, "w": [[1.6, 1.1, 1], [3.7, 0.4, 0.45]], "top": "#8c382c", "bot": "#65231f"},
      {"k": "hill", "y": 0.82, "amp": 0.07, "ridge": 1, "w": [[1.1, 0.3, 1], [2.8, 1.5, 0.5]], "top": "#68261f", "bot": "#451815"},
      {"k": "hill", "y": 0.92, "amp": 0.04, "w": [[1.5, 0.8, 1], [3.5, 2.0, 0.35]], "top": "#34130f", "bot": "#200b09"}
    ]
  }
};
const HOME_ART_IDS = Object.keys(HOME_ART);

const homeHex = (h, a) => { const n = parseInt(h.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})`; };
// the edge of a hill at `u` (0..1 across): the same sum WallArt.kt takes
const homeHillY = (l, u) => {
  let s = 0, t = 0;
  for (const [f, p, wt] of l.w) { s += wt * (l.ridge ? Math.abs(Math.sin(Math.PI * f * u + p)) : Math.sin(2 * Math.PI * f * u + p)); t += wt; }
  return l.y + l.amp * (l.ridge ? 2 * s / t - 1 : s / t);
};
// Draws wall `id` on a canvas context of w x h px (the page's small previews call it; resolution does not matter)
// `user`: the person's veil strength (HomeConfig "shade" / 50, 1 the usual one), Backdrop.shade's rule: under 1 lighter, over 1 each stop goes towards the darkest
function homeArtPaint(ctx, w, h, id, user = 1) {
  const a = HOME_ART[id];
  if (!a) return;
  const rad = a.ang * Math.PI / 180, dx = Math.sin(rad), dy = Math.cos(rad);
  const half = (Math.abs(dx) * w + Math.abs(dy) * h) / 2, cx = w / 2, cy = h / 2;      // the line runs corner to corner
  const g = ctx.createLinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half);
  for (const [c, at] of a.bg) g.addColorStop(at, c);
  ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
  for (const l of a.layers) {
    if (l.k === 'hill') {
      const top = (l.y - l.amp) * h, ga = ctx.createLinearGradient(0, top, 0, h);
      ga.addColorStop(0, homeHex(l.top, l.ta === undefined ? 1 : l.ta)); ga.addColorStop(1, homeHex(l.bot, l.ba === undefined ? 1 : l.ba));
      ctx.beginPath(); ctx.moveTo(0, h);
      for (let i = 0; i <= 96; i++) ctx.lineTo(w * i / 96, homeHillY(l, i / 96) * h);
      ctx.lineTo(w, h); ctx.closePath(); ctx.fillStyle = ga; ctx.fill();
    } else {
      const r = l.r * w, sy = l.sy === undefined ? 1 : l.sy;
      ctx.save(); ctx.translate(l.x * w, l.y * h); ctx.scale(1, sy);
      const rg = ctx.createRadialGradient(0, 0, 0, 0, 0, r);
      if (l.k === 'disc') { rg.addColorStop(0, homeHex(l.c, l.a)); rg.addColorStop(0.82, homeHex(l.c, l.a)); rg.addColorStop(1, homeHex(l.c, 0)); }
      else { rg.addColorStop(0, homeHex(l.c, l.a)); rg.addColorStop(0.5, homeHex(l.c, l.a * 0.35)); rg.addColorStop(1, homeHex(l.c, 0)); }
      ctx.fillStyle = rg; ctx.beginPath(); ctx.arc(0, 0, r, 0, 2 * Math.PI); ctx.fill();
      ctx.restore();
    }
  }
  // the veil TV Home puts over it (Backdrop.SHADE: black at 69 % / 25 % / 56 % from top to bottom), `shade` of its strength
  homeVeil(ctx, w, h, a.shade, user);
}
// Backdrop.shade: the three stops of the veil (176 / 64 / 144 of 255) at `k` of their strength, then the person's `user` one
const homeShade = (k, user) => [176, 64, 144].map((a) => { const v = a * k, u = user <= 1 ? v * user : v + Math.max(0, 224 - v) * Math.min(user - 1, 1); return Math.min(255, Math.max(0, u)) / 255; });
function homeVeil(ctx, w, h, k, user) {
  const s = ctx.createLinearGradient(0, 0, 0, h), a = homeShade(k, user);
  s.addColorStop(0, `rgba(0,0,0,${a[0]})`); s.addColorStop(0.5, `rgba(0,0,0,${a[1]})`); s.addColorStop(1, `rgba(0,0,0,${a[2]})`);
  ctx.fillStyle = s; ctx.fillRect(0, 0, w, h);
}

// ---- a theme's picture in the Themes list: its home screen in miniature, from its config (ThemePreview.kt does the same on the TV).
// The wall under the veil at its real strength, the clock, the widgets as homePack lays them, the grid's columns and rows with
// the live widgets first and the focus on the first one. Rounded shapes only; `img` (a loaded Image) is the background of a photo / page /
// video, else the drawn look or the colours.
/** HomeConfig.ACCENTS (a test keeps them equal) and TSTYLES: the page's twins of the TV's look choices. */
const HOME_ACCENTS = { blue: '#2F72F0', teal: '#0E9F92', green: '#2E9E55', amber: '#C57400', orange: '#E8590C', red: '#E5383B', pink: '#D6336C', violet: '#7B55EE' };
const HOME_TSTYLES = ['banner', 'icon', 'glass'];
const HOME_PREV = { ink: '255,255,255', live: ['#4fc3a1', '#ffc857', '#8ab4f8', '#f28b82'], none: '#2a2f3a' };
// the wall moves (a video, or a web page that is one video): the picture alone cannot say so (ThemePreview.moving is the twin)
function homeThemeMoving(conf) {
  if (/^vid:/.test(conf.wall || '')) return true;
  if (conf.wall !== 'web') return false;
  const u = String(conf.wurl || '').toLowerCase();
  return u.includes('/home/loop.html?v=') || /\.(mp4|webm|m3u8|mkv|mov)(\?|#|$)/.test(u);
}
/** HomeConfig.barClock / barDate: which of the top bar's time and date TV Home draws (a clock widget is the clock then; with a calendar widget the date too, while that line says the date). */
function homeBar(conf) {
  const ws = homePlaced(conf), clock = ws.includes('clock');
  return { clock: conf.showClock !== false && !clock,
    date: conf.showDate !== false && !((conf.dstyle || 'date') === 'date' && (clock || ws.includes('calendar'))) };
}

/** HomeConfig.placed: the chosen widgets that get a place (a clock widget waiting for room leaves the bar's clock on). */
function homePlaced(conf) {
  const keys = (conf.widgets || []).filter((k) => k !== 'timer' && (!/^qr\d$/.test(k) || (conf.qrs && conf.qrs[k])));   // as HomeConfig.placed: a timer counts as not set
  if (!keys.length || typeof homePack !== 'function') return keys;
  const big = keys.filter((k) => homeSize(conf, k) === 'big'), note = !!conf.note;
  const p = conf.wplace === 'left' || conf.wplace === 'right' ? homePackColumn(keys, big, note, 378) : homePack(keys, big, note);
  return keys.filter((k) => !(p.waiting || []).includes(k));
}

function homeThemePaint(cv, conf, tiles, img, look, now) {   // now: a Date, for the live preview of the config being edited (its real time)
  const ctx = cv.getContext('2d'), w = cv.width, h = cv.height, wall = conf.wall, user = (conf.shade ?? 50) / 50;
  const bar = (x, y, bw, bh, a) => { ctx.fillStyle = `rgba(${HOME_PREV.ink},${a})`; ctx.beginPath(); ctx.roundRect(x, y, bw, bh, bh / 2); ctx.fill(); };
  const box = (x, y, bw, bh, r) => { ctx.beginPath(); ctx.roundRect(x, y, bw, bh, r); ctx.fill(); };
  const art = HOME_ART[wall] ? wall : HOME_ART[look] ? look : '';
  if (HOME_ART[wall] || (!img && art)) homeArtPaint(ctx, w, h, art, user);
  else {
    if (img) { const k = Math.max(w / img.width, h / img.height); ctx.drawImage(img, (w - img.width * k) / 2, (h - img.height * k) / 2, img.width * k, img.height * k); }
    else { const c = (HOME_WALLS[wall] || HOME_PREV.none).split(','), g = ctx.createLinearGradient(0, 0, w, h); g.addColorStop(0, c[0]); g.addColorStop(1, c[c.length - 1]); ctx.fillStyle = g; ctx.fillRect(0, 0, w, h); }
    homeVeil(ctx, w, h, 1, user);
  }
  const m = w * 0.06, head = homeBar(conf);                             // a clock widget takes the bar's time (and date): one clock on the screen, as on the TV
  let hx = m;
  if (head.clock) {
    if (now) {                                                          // the clock as the TV writes it
      const hh = conf.clock24 === false ? (now.getHours() % 12 || 12) : String(now.getHours()).padStart(2, '0'), t = hh + ':' + String(now.getMinutes()).padStart(2, '0');
      ctx.font = `300 ${Math.round(h * 0.11)}px system-ui, sans-serif`; ctx.textBaseline = 'middle'; ctx.fillStyle = `rgba(${HOME_PREV.ink},0.95)`;
      ctx.fillText(t, m, h * 0.11); hx = m + ctx.measureText(t).width + w * 0.025;
    } else { bar(m, h * 0.075, w * 0.12, h * 0.07, 0.8); hx = m + w * 0.145; }
  }
  if (conf.greet !== false) bar(hx, h * 0.08, w * 0.16, h * 0.026, 0.45);
  if (head.date) bar(hx, h * 0.122, w * 0.22, h * 0.034, 0.75);
  bar(w - m - w * 0.16, h * 0.085, w * 0.16, h * 0.05, 0.25);           // the buttons as one bar: where things are, not each one
  // where the widgets sit (HomeConfig "wplace"): the row under the top bar (top, today), a column beside the grid (left / right)
  const place = HOME_PLACES.includes(conf.wplace) ? conf.wplace : 'top', side0 = place === 'left' || place === 'right';
  let y = h * 0.21;
  const big = HOME_WIDGETS.concat(['qr1', 'qr2', 'qr3', 'qr4']).filter((k) => homeSize(conf, k) === 'big'), keys = (conf.widgets || []).filter((k) => HOME_WIDGETS.includes(k));
  const packed = side0 ? homePackColumn(keys, big, false, 378) : homePack(keys, big);   // 378 dp: what is left of a 1080p screen under the top bar
  const side = side0 && packed.cols.length > 0;
  const card = (k, x, cy, cw, ch, n) => {
    const look = ((conf.wopt || {})[k] || {}).look || (conf.wstyle || {})[k], pad = h * 0.03;
    ctx.fillStyle = look === 'solid' ? 'rgba(24,27,34,0.94)' : `rgba(${HOME_PREV.ink},${look === 'tonal' ? 0.29 : 0.18})`;   // Frost's solid card; none: no card
    if (look !== 'none') box(x, cy, cw, ch, h * 0.035);
    if (k === 'clock' && (n === 1 || ch > h * 0.12)) { ctx.strokeStyle = `rgba(${HOME_PREV.ink},0.7)`; ctx.lineWidth = Math.max(1, h * 0.008); ctx.beginPath(); ctx.arc(x + cw / 2, cy + ch / 2, Math.min(cw, ch) * 0.28, 0, 2 * Math.PI); ctx.stroke(); }
    else { bar(x + pad, cy + pad, cw * 0.42, h * 0.018, 0.4); if (ch > h * 0.12) bar(x + pad, cy + ch * 0.45, cw * 0.62, h * 0.045, 0.7); else bar(x + pad, cy + ch * 0.55, cw * 0.5, h * 0.03, 0.7); }
  };
  const rowH = h * 0.30, wgap = w * 0.02, sideW = (w - 2 * m) / 3, sideGap = w * 0.033;
  const drawRow = (ry) => {
    const room = w - 2 * m - wgap * (packed.cols.length - 1), sum = Math.max(HOME_W.floor, packed.cols.reduce((n, c) => n + c.weight, 0));
    const used = room * packed.cols.reduce((n, c) => n + c.weight, 0) / sum + wgap * (packed.cols.length - 1);
    let x = conf.center ? (w - used) / 2 : m;                           // centered over a centered grid, as TV Home lays it
    for (const col of packed.cols) {
      const cw = room * col.weight / sum, n = col.keys.length, ch = (rowH - (n - 1) * wgap * 0.6) / n;
      col.keys.forEach((k, j) => card(k, x, ry + j * (ch + wgap * 0.6), cw, ch, n));
      x += cw + wgap;
    }
  };
  if (side) {                                                           // full-width blocks stacked, a block as high as its dp says (540 dp screen)
    const x = place === 'left' ? m : w - m - sideW, sc = h / 540;
    let cy = y;
    for (const col of packed.cols) {
      const n = col.keys.length, ch = col.weight * sc, g = HOME_H.gap * sc * 0.5;
      col.keys.forEach((k, j) => card(k, x, cy + j * ((ch - g * (n - 1)) / n + g), sideW, (ch - g * (n - 1)) / n, n));
      cy += ch + HOME_H.gap * sc;
    }
  } else if (packed.cols.length && place === 'top') { drawRow(y); y += rowH + h * 0.04; }
  const cols0 = Math.min(8, Math.max(4, conf.cols || 5)), gap = w * 0.014, base = (w - 2 * m - (cols0 - 1) * gap) / cols0;
  const gx = side && place === 'left' ? m + sideW + sideGap : m, gw = side ? w - 2 * m - sideW - sideGap : w - 2 * m;
  // beside a column the tiles keep about their size and the row holds fewer (HomeActivity.gridCols)
  const cols = side ? Math.min(cols0, Math.max(Math.min(3, cols0), Math.floor((gw + gap) / (base * 0.88 + gap)))) : cols0;
  const tw = (gw - (cols - 1) * gap) / cols, th = tw * 0.56;
  const fit = (h - y + gap) / (th + gap), shown = conf.rows >= 1 && !side ? Math.min(conf.rows, Math.max(fit, 1)) : Math.min(fit, 3), ring = conf.focus === 'ring';
  const tstyle = HOME_TSTYLES.includes(conf.tstyle) ? conf.tstyle : 'banner', accent = HOME_ACCENTS[conf.accent] || '';
  // TV Home keeps the grid at the bottom of the screen: whole rows above a margin, a part of a row cut by the screen's edge
  const whole = Math.abs(shown - Math.round(shown)) < 0.0001;
  if (!side) y = Math.max(y, (whole ? h * 0.94 : h) - (shown * (th + gap) - (whole ? gap : 0)));
  for (let i = 0; i < Math.ceil(shown - 0.0001) * cols; i++) {
    const rx = gx + (i % cols) * (tw + gap), ry = y + Math.floor(i / cols) * (th + gap), row = Math.floor(i / cols);
    const vis = row + 1 <= shown + 0.0001 ? 1 : Math.min(1, Math.max(0.05, shown - row)), live = i < tiles.length;
    if (live || tstyle === 'banner') {
      ctx.fillStyle = live ? HOME_PREV.live[i % 4] : `rgba(${HOME_PREV.ink},0.15)`;
      if (live) ctx.globalAlpha = 0.8;
      box(rx, ry, tw, th * vis, h * 0.03); ctx.globalAlpha = 1;
    } else {                                                        // icon / glass: a rounded square for the icon (a frosted card behind it for glass) and a bar for the name
      if (tstyle === 'glass') { ctx.fillStyle = `rgba(${HOME_PREV.ink},0.19)`; box(rx, ry, tw, th * vis, h * 0.03); }
      const s = th * (tstyle === 'icon' ? 0.56 : 0.46), ix = rx + (tw - s) / 2, iy = ry + th * (tstyle === 'icon' ? 0.04 : 0.1), ny = iy + s + th * 0.08;
      if (iy + s <= ry + th * vis) { ctx.fillStyle = HOME_PREV.live[i % 4]; ctx.globalAlpha = 0.8; box(ix, iy, s, s, s * 0.26); ctx.globalAlpha = 1; }
      if (ny + th * 0.05 <= ry + th * vis) { ctx.fillStyle = `rgba(${HOME_PREV.ink},0.6)`; box(rx + tw * 0.28, ny, tw * 0.44, th * 0.05, th * 0.025); }
    }
    if (i === 0) {
      if (ring) { ctx.strokeStyle = accent || '#fff'; ctx.lineWidth = Math.max(1.5, h * 0.012); ctx.beginPath(); ctx.roundRect(rx - 1, ry - 1, tw + 2, th * vis + 2, h * 0.03); ctx.stroke(); }
      else {
        ctx.fillStyle = `rgba(${HOME_PREV.ink},0.25)`; box(rx - tw * 0.04, ry - th * 0.06, tw * 1.08, th * vis + th * 0.12, h * 0.036);
        if (accent) { ctx.strokeStyle = accent; ctx.lineWidth = Math.max(1.5, h * 0.01); ctx.beginPath(); ctx.roundRect(rx - tw * 0.04, ry - th * 0.06, tw * 1.08, th * vis + th * 0.12, h * 0.036); ctx.stroke(); }
      }
    }
  }
  if (homeThemeMoving(conf)) {                                    // a dark pill with a play triangle, top right
    const bh = h * 0.17, bw = bh * 1.6, x = w - w * 0.04 - bw, y = h * 0.05, t = bh * 0.42, cx = x + bw / 2 + t * 0.12, cy = y + bh / 2;
    ctx.fillStyle = 'rgba(0,0,0,0.7)'; box(x, y, bw, bh, bh / 2);
    ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.moveTo(cx - t * 0.45, cy - t * 0.55); ctx.lineTo(cx + t * 0.6, cy); ctx.lineTo(cx - t * 0.45, cy + t * 0.55); ctx.closePath(); ctx.fill();
  }
}
