// Live demo: the made-up TV behind the real web UI. sw.js hands every /api/... call of the page to demoApi(); it keeps the TV
// in memory (so a reload starts again) and answers in the shapes of Manage.kt / Server.kt / Session.kt. A call that would
// change a real TV (install, remove, reboot...) answers the friendly "this is a demo" error, which the page shows as it does any.
(() => {
  const LANG = () => { try { return localStorage.getItem('lang') === 'en' ? 'en' : 'vi'; } catch { return 'vi'; } };
  const say = (vi, en) => (LANG() === 'en' ? en : vi);
  const MIN = 60000, now = () => Date.now();
  const ok = (o) => ({ status: 200, type: 'application/json', body: JSON.stringify(o) });
  const fail = (msg, status = 400) => ({ status, type: 'application/json', body: JSON.stringify({ error: msg }) });
  const demo = () => fail(say('Đây là bản dùng thử: không thay đổi gì trên TV thật. Tải app để dùng với TV của bạn.',
    'This is a demo: nothing on a real TV changes. Download the app to use it with your TV.'));

  // ---------- the apps: coloured squares with a letter, no brand logos ----------
  const APPS = [
    ['com.demo.tube', 'Tube', '#e5484d', 'T'], ['com.demo.stream', 'Streamly', '#3b82f6', 'S'], ['com.demo.music', 'Music Box', '#22c55e', 'M'],
    ['com.demo.news', 'News Daily', '#f59e0b', 'N'], ['com.demo.kids', 'Kids Corner', '#a855f7', 'K'], ['com.demo.games', 'Game Hub', '#14b8a6', 'G'],
    ['com.demo.fitness', 'Fit Together', '#ec4899', 'F'], ['com.demo.photos', 'Photo Frame', '#6366f1', 'P'], ['com.demo.weather', 'Weather', '#0ea5e9', 'W'],
    ['com.demo.browser', 'Web Browser', '#64748b', 'B'], ['com.demo.cast', 'Cast Receiver', '#f97316', 'C'], ['com.demo.radio', 'Radio Town', '#84cc16', 'R'],
  ];
  const SYS = [['com.android.settings', 'Settings', '#475569', 'S'], ['com.demo.launcher', 'TV Home', '#334155', 'H'], ['com.demo.keyboard', 'TV Keyboard', '#334155', 'K'],
    ['dev.tvremote.setup', 'TV Setup', '#2563eb', 'T']];
  const META = Object.fromEntries([...APPS, ...SYS].map(([p, l, c, ch]) => [p, { label: l, color: c, ch }]));
  const iconSvg = (pkg) => {
    const m = META[pkg] || { color: '#475569', ch: (pkg.split('.').pop()[0] || '?').toUpperCase() };
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><rect width="96" height="96" rx="22" fill="${m.color}"/>` +
      `<text x="48" y="64" font-family="Arial,Helvetica,sans-serif" font-size="52" font-weight="700" fill="#fff" text-anchor="middle">${m.ch}</text></svg>`;
  };

  // ---------- the TV ----------
  const S = {
    tvs: [{ host: '192.168.1.20', name: 'Living room TV', lan: '192.168.1.20', mac: 'a4:77:33:12:9c:01' },
      { host: '192.168.1.34', name: 'Bedroom TV', lan: '192.168.1.34', mac: 'a4:77:33:5e:20:4b' }],
    host: '192.168.1.20', on: true, app: '', vol: 12, muted: false, focus: [1, 1], playing: null, ink: [],   // ink: ✏️ strokes and pictures on the screen
    sleepAt: 0, snoozeUntil: 0, awake: false,
    bedtime: { on: true, ranges: [{ from: '23:00', to: '06:30' }] },
    limits: [{ pkg: 'com.demo.kids', minutes: 60, used: 25, extra: 0 }, { pkg: 'com.demo.games', minutes: 90, used: 70, extra: 0 }],
    volume: [{ from: '22:00', to: '07:00', max: 8 }], ram: { on: true, pct: 90 },
    scenes: [
      { id: 's1', icon: '🎬', name: say('Xem phim', 'Movie night'), steps: [{ do: 'wake' }, { do: 'wait', s: 2 }, { do: 'app', pkg: 'com.demo.stream' }, { do: 'volume', level: 14 }] },
      { id: 's2', icon: '😴', name: say('Đi ngủ', 'Good night'), steps: [{ do: 'volume', level: 5 }, { do: 'timer', min: 30 }] },
      { id: 's3', icon: '🎵', name: say('Nghe nhạc', 'Music'), steps: [{ do: 'wake' }, { do: 'app', pkg: 'com.demo.music' }] },
    ],
    open: ['com.demo.stream', 'com.demo.music', 'com.demo.tube'], t0: now(),
    off: new Set(['com.demo.fitness']), boot: ['com.demo.music', 'com.demo.weather'],
    hook: { on: false }, dns: '', ad: 0,
  };
  S.app = '';
  const dev = { name: 'Living room TV', model: 'Streamer 4K', maker: 'Demo' };

  // ---------- files ----------
  const day = 86400000, stamp = (ago) => { const d = new Date(now() - ago); const p = (n) => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()); };
  const D = (name, ago = 5 * day) => ({ name, dir: true, size: 0, mtime: stamp(ago) });
  const F = (name, size, ago = 3 * day) => ({ name, dir: false, size, mtime: stamp(ago) });
  const FS = {
    '/sdcard': [D('Download'), D('Movies'), D('Pictures'), D('Music'), D('Documents', 30 * day), F('notes.txt', 1200, day)],
    '/sdcard/Download': [F('photo-album.zip', 48e6, 2 * day), F('subtitles.srt', 84e3, 9 * day), F('app-update.apk', 36e6, 6 * day)],
    '/sdcard/Movies': [D('Family trips'), F('holiday.mp4', 820e6, 12 * day), F('birthday.mp4', 310e6, 40 * day)],
    '/sdcard/Movies/Family trips': [F('beach.mp4', 540e6, 60 * day), F('mountain.mp4', 480e6, 61 * day)],
    '/sdcard/Pictures': [F('sunset.jpg', 2.4e6, 8 * day), F('garden.jpg', 3.1e6, 20 * day), F('cat.png', 1.6e6, 4 * day)],
    '/sdcard/Music': [F('morning-playlist.m3u', 3e3, 7 * day), F('lofi-mix.mp3', 9.2e6, 15 * day)],
    '/sdcard/Documents': [F('manual.pdf', 5.5e6, 90 * day)],
  };
  const fsPath = (p) => (p || '/sdcard').replace(/\/+$/, '') || '/sdcard';
  const join = (a, b) => a + '/' + b;

  // ---------- the picture: a drawn TV home screen, never a real one ----------
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');
  // ✏️ what TV Setup's DrawLayer would show over every app (Draw.kt's ops), so the screenshot carries it as a real TV's does
  function drawOps(ops) {
    for (const o of ops || []) {
      if (o.o === 's' || o.o === 'i') S.ink.push({ ...o, p: (o.p || []).slice() });
      else if (o.o === 'a') { const s = S.ink.find((x) => x.id === o.id); if (s) s.p.push(...o.p); }
      else if (o.o === 'u') S.ink.pop();
      else if (o.o === 'x' || o.o === 'h') S.ink = [];
    }
  }
  function inkSvg(W, H) {
    return S.ink.map((s) => {
      if (s.o === 'i') { const w = s.w / 10000 * W, x = s.x / 10000 * W, y = s.y / 10000 * H;
        return `<image href="data:image/png;base64,${s.b}" x="${x - w / 2}" y="${y - w}" width="${w}" height="${2 * w}" preserveAspectRatio="xMidYMid meet"/>`; }
      const pts = []; for (let i = 0; i + 1 < s.p.length; i += 2) pts.push((s.p[i] / 10000 * W).toFixed(1) + ',' + (s.p[i + 1] / 10000 * H).toFixed(1));
      if (pts.length === 1) pts.push(pts[0]);
      return `<polyline points="${pts.join(' ')}" fill="none" stroke="${esc(s.c)}" stroke-width="${Math.max(2, s.w / 10000 * W)}" stroke-linecap="round" stroke-linejoin="round"/>`;
    }).join('');
  }
  // loaded once as a data: address, so the svg picture (an <img>, no outside files) carries it
  let HOME_IMG = '';
  fetch(new URL('img/tv-home-screen.jpg?v=1024', location.href)).then((r) => r.blob()).then((b) => { const f = new FileReader(); f.onload = () => { HOME_IMG = f.result; }; f.readAsDataURL(b); }).catch(() => {});
  const screenSvg = () => screenBase().replace(/<\/svg>$/, inkSvg(960, 540) + '</svg>');
  function screenBase() {
    const W = 960, H = 540;
    let g = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" font-family="Arial,Helvetica,sans-serif">` +
      `<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1b2a4a"/><stop offset="1" stop-color="#0b1020"/></linearGradient>` +
      `<linearGradient id="hero" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5b8cff"/><stop offset="1" stop-color="#a855f7"/></linearGradient></defs>` +
      `<rect width="${W}" height="${H}" fill="url(#bg)"/>`;
    if (!S.on) return g + `<rect width="${W}" height="${H}" fill="#000"/></svg>`;
    const clock = new Date(), hh = String(clock.getHours()).padStart(2, '0') + ':' + String(clock.getMinutes()).padStart(2, '0');
    g += `<text x="${W - 40}" y="52" fill="#cbd5e1" font-size="26" text-anchor="end">${hh}</text>`;
    const a = S.app && META[S.app];
    if (a) {                                                        // an app is "open": its name over its colour
      g += `<rect width="${W}" height="${H}" fill="${a.color}" opacity=".9"/><text x="${W / 2}" y="${H / 2 + 20}" fill="#fff" font-size="84" font-weight="700" text-anchor="middle">${esc(a.label)}</text>`;
      if (S.playing) g += `<rect x="120" y="${H - 90}" width="${W - 240}" height="8" rx="4" fill="#fff" opacity=".3"/><rect x="120" y="${H - 90}" width="${(W - 240) * ((now() / 1000 % 120) / 120)}" height="8" rx="4" fill="#fff"/>`;
      return g + '</svg>';
    }
    if (HOME_IMG) {                                                 // the clean TV Home (a real capture of the current build; its clock and focus are fixed
      g += `<image href="${HOME_IMG}" width="${W}" height="${H}" preserveAspectRatio="xMidYMid slice"/>`;
      return g + '</svg>';
    }
    g += `<text x="48" y="64" fill="#fff" font-size="30" font-weight="700">${esc(say('Chào buổi tối', 'Good evening'))}</text>`;
    g += `<rect x="48" y="96" width="${W - 96}" height="190" rx="20" fill="url(#hero)"/><text x="84" y="190" fill="#fff" font-size="42" font-weight="700">${esc(say('Tiếp tục xem', 'Continue watching'))}</text>` +
      `<text x="84" y="232" fill="#e0e7ff" font-size="22">${esc(say('Chuyến đi biển · còn 24 phút', 'Beach trip · 24 min left'))}</text>`;
    const list = APPS.slice(0, 7);
    list.forEach(([p, label, color, ch], i) => {
      const x = 48 + i * 127, y = 330, sel = S.focus[0] === 1 && S.focus[1] === i;
      g += `<g transform="translate(${x} ${y})"><rect width="112" height="112" rx="22" fill="${color}"${sel ? ' stroke="#fff" stroke-width="5"' : ''}/>` +
        `<text x="56" y="76" fill="#fff" font-size="54" font-weight="700" text-anchor="middle">${ch}</text><text x="56" y="140" fill="#cbd5e1" font-size="18" text-anchor="middle">${esc(label)}</text></g>`;
    });
    g += `<text x="48" y="${H - 22}" fill="#64748b" font-size="16">${esc(say('Màn hình TV mô phỏng', 'Made-up TV screen'))}</text>`;
    return g + '</svg>';
  }

  // ---------- Health ----------
  const wob = (base, spread) => Math.round((base + (Math.sin(now() / 4000) + Math.random() - 0.5) * spread) * 10) / 10;
  function health() {
    const up = Math.round((now() - S.t0) / 1000) + 3 * 3600 + 540;
    const gb = 1073741824;
    return {
      ping_ms: 12 + Math.floor(Math.random() * 8), uptime: up, cpu: Math.max(2, Math.round(wob(14, 10))), gpu: Math.round(wob(9, 6)), cores: 4,
      mem_total: 3.6 * gb, mem_free: Math.round(wob(1.2, .15) * gb), disk_total: 28 * gb, disk_free: 17.4 * gb,
      top: [['com.demo.stream', 9, 8], ['dev.tvremote.setup', 3, 2], ['com.demo.launcher', 2, 5], ['system_server', 2, 7]].map(([name, cpu, mem]) => ({ name, cpu, mem })),
      thermal: { status: 0, temps: [{ name: 'cpu', c: wob(46, 3) }, { name: 'gpu', c: wob(43, 3) }] }, net: { rx: Math.round(wob(180e3, 120e3)), tx: Math.round(wob(22e3, 12e3)) },
      bt: { on: true, devices: [{ name: 'Demo remote', connected: true }, { name: 'Soundbar', connected: false }] },
      wifi: { ssid: 'Home-5G', rssi: -54, speed: 433, freq: 5180 }, ips: [{ if: 'wlan0', ip: '192.168.1.20' }],
      maker: dev.maker, model: dev.model, android: '14', sdk: 34, patch: '2026-08-05', chip: 'Demo SoC S905', abi: 'arm64-v8a', screen: '3840x2160', fps: 60, density: 320,
      remote: { connected: true, is_on: S.on, app: S.app, volume: { level: S.vol, max: 30, muted: S.muted } },
    };
  }
  function stats(days) {
    const usage = {}, samples = [], net = [], t = now();
    for (let d = 0; d < days; d++) {
      const k = new Date(t - d * day), key = k.getFullYear() + '-' + String(k.getMonth() + 1).padStart(2, '0') + '-' + String(k.getDate()).padStart(2, '0');
      const r = 0.5 + ((d * 37) % 11) / 10;
      usage[key] = { 'com.demo.stream': Math.round(5400 * r), 'com.demo.tube': Math.round(3000 * r), 'com.demo.music': Math.round(1500 * r), 'com.demo.kids': Math.round(1800 * (r / 2)) };
    }
    // as TV Setup's Stats.kt writes them: one every 5 minutes, memory and storage in MB; on in the evenings
    const STEP = 300, N = Math.round(days * 86400 / STEP), shows = ['com.demo.stream', 'com.demo.tube', 'com.demo.music'];
    const wave = (i, a, b) => Math.sin(i / a) * 0.6 + Math.sin(i / b + 1.7) * 0.4;   // smooth, the same on every load
    for (let i = 0; i < N; i++) {
      const at = Math.round(t / 1000) - (N - i) * STEP, d = new Date(at * 1000), h = d.getHours() + d.getMinutes() / 60;
      const weekend = d.getDay() === 0 || d.getDay() === 6;
      const on = (h >= 18 && h < 23.5) || (weekend && h >= 9 && h < 11.5) || (i > N - 36 && S.on) ? 1 : 0;   // the last 3 hours on: whenever the demo is opened, the newest part shows the TV in use
      const busy = on ? 22 + 10 * wave(i, 5, 13) : 4 + 2 * wave(i, 7, 3);
      const mem = Math.round(on ? 2550 + 260 * wave(i, 9, 4) : 1850 + 90 * wave(i, 11, 5));
      const sig = Math.round(-55 + 4 * wave(i, 23, 7));
      samples.push([at, on, Math.max(1, Math.round(busy)), mem, 3686, Math.round(10850 + i * 0.05), 28672,
        on ? shows[Math.floor(i / 18) % shows.length] : '', sig, 'wifi', sig > -58 ? 433 : 390]);
    }
    const y = Math.round(t / 1000) - 86400 + 3600;                     // the router restarted once, yesterday evening
    net.push([y, 0, 'wifi', 1], [y + 95, 1, 'wifi', 1]);
    return { usage, samples, net, labels: Object.fromEntries(Object.entries(META).map(([p, m]) => [p, m.label])), collector: true, days };
  }
  const CAPS = 'sdk 34 14\ncodec dec hw c2.demo.avc.decoder video/avc 4096x2176 -\ncodec dec hw c2.demo.hevc.decoder video/hevc 4096x2176 secure\n' +
    'codec dec hw c2.demo.vp9.decoder video/x-vnd.on2.vp9 4096x2176 -\ncodec dec hw c2.demo.av1.decoder video/av01 3840x2160 -\ncodec dec hw c2.demo.aac audio/mp4a-latm 0x0 -\n' +
    'codec dec hw c2.demo.eac3 audio/eac3 0x0 -\ncodec dec sw c2.demo.opus audio/opus 0x0 -\nhdr HDR10 HLG Dolby-Vision\nmode 3840x2160@60\nmode 1920x1080@60\n' +
    'audio HDMI pcm ac3 eac3\naudio speaker pcm\nfeature android.software.leanback\nfeature android.hardware.bluetooth\n';
  const SETTINGS = 'global airplane_mode_on=0\nglobal development_settings_enabled=1\nglobal adb_enabled=1\nglobal stay_on_while_plugged_in=0\nglobal wifi_sleep_policy=2\n' +
    'secure screensaver_enabled=1\nsecure long_press_timeout=400\nsecure accessibility_display_magnification_enabled=0\nsystem screen_off_timeout=1800000\nsystem volume_music=12\n';

  // the Sleep view: a week of evenings (on about 18:00 to 23:30), as TV Setup's log would hold it
  function power() {
    const nowS = Math.round(now() / 1000), mid = new Date(); mid.setHours(0, 0, 0, 0);
    const m = Math.round(mid.getTime() / 1000), events = [];
    for (let d = 7; d >= 0; d--) for (const [h, w] of [[18, 'on'], [23.5, 'off']]) { const t = m - d * 86400 + h * 3600; if (t < nowS - 600) events.push([t, w]); }
    if (events.length && events[events.length - 1][1] === 'off' && S.on) events.push([nowS - 600, 'on']);
    const last = (w) => { const e = events.filter((x) => x[1] === w).pop(); return e ? nowS - e[0] : 3600; };
    return { now: nowS, state: S.on ? 'Awake' : 'Asleep', events, logged: true, setup_running: true,
      boot: { up: 3 * 86400, deep: 86400, count: 2 }, timeouts: { sleep: 1800000, attentive: 0 }, locks: [],
      last_sleep: { ago: last('off'), why: 'timeout' }, last_wake: { ago: last('on'), why: 'remote' },
      hdmi_off: 1, wifi_sleep: 2, low_power_standby: 0, tailscale: {} };
  }

  // ---------- the rules (TV Setup's answer) ----------
  function rules() {
    const t = now(), inBed = false;
    return { now: t, sleep_at: S.sleepAt || 0, snooze_until: S.snoozeUntil, in_bedtime: inBed, bedtime: { on: S.bedtime.on, ranges: S.bedtime.ranges, from: S.bedtime.ranges[0]?.from || '23:00', to: S.bedtime.ranges[0]?.to || '06:30' },
      limits: S.limits, volume: S.volume, volume_now: null, volume_fixed: false, ram: S.ram, awake: S.awake, adb: 'ready', version: '0.12.0' };
  }
  function ruleDo(b) {
    const t = now();
    switch (b.do) {
      case 'sleep': S.sleepAt = b.minutes ? t + b.minutes * MIN : 0; break;
      case 'sleep_add': S.sleepAt = (S.sleepAt || t) + b.minutes * MIN; break;
      case 'bedtime': S.bedtime = { on: !!b.on, ranges: b.ranges || S.bedtime.ranges }; break;
      case 'snooze': S.snoozeUntil = b.minutes ? t + b.minutes * MIN : 0; break;
      case 'limit': S.limits = S.limits.filter((l) => l.pkg !== b.pkg); if (b.minutes) S.limits.push({ pkg: b.pkg, minutes: b.minutes, used: 0, extra: 0 }); break;
      case 'extra': S.limits.forEach((l) => { if (l.pkg === b.pkg) l.extra = b.set ? b.minutes : (l.extra || 0) + b.minutes; }); break;
      case 'volume': S.volume = b.ranges || []; break;
      case 'ram': S.ram = { on: !!b.on, pct: b.pct || 90 }; break;
      case 'awake': S.awake = !!b.on; break;
    }
  }

  const KEYS_VOL = { VOL_UP: 1, VOL_DOWN: -1 };
  function key(k) {
    if (k === 'POWER') S.on = !S.on;
    else if (KEYS_VOL[k]) { S.vol = Math.max(0, Math.min(30, S.vol + KEYS_VOL[k])); S.muted = false; }
    else if (k === 'MUTE') S.muted = !S.muted;
    else if (k === 'HOME' || k === 'BACK') { if (S.app) { S.app = ''; S.playing = null; } }
    else if (k === 'LEFT') S.focus[1] = Math.max(0, S.focus[1] - 1);
    else if (k === 'RIGHT') S.focus[1] = Math.min(6, S.focus[1] + 1);
    else if (k === 'OK' && !S.app) openApp(APPS[S.focus[1]][0]);
    else if (k === 'PLAY_PAUSE' && S.playing) S.playing.state = S.playing.state === 'playing' ? 'paused' : 'playing';
    if (S.playing && k === 'STOP') S.playing = null;
  }
  function openApp(pkg) {
    S.on = true; S.app = pkg; S.open = [pkg, ...S.open.filter((p) => p !== pkg)];
    S.playing = ['com.demo.stream', 'com.demo.music', 'com.demo.tube'].includes(pkg) ? { pkg, state: 'playing', at: now(),
      title: pkg === 'com.demo.music' ? 'Morning playlist' : 'Beach trip', sub: pkg === 'com.demo.music' ? 'Lo-fi Demo' : 'Family videos', dur: 1440000, pos: 960000, speed: 1, seek: true } : null;
  }

  const state = () => ({ host: S.host, connected: true, paired: true, error: '', is_on: S.on, app: S.app || null,
    volume: { level: S.vol, max: 30, muted: S.muted }, device: { manufacturer: dev.maker, model: dev.model, sw_version: '14' },
    kind: 'android', caps: ['adb', 'app_list', 'apps', 'inputs', 'keys', 'link', 'pair_code', 'scenes', 'screen_off', 'store', 'text', 'volume_set', 'wol'],
    asking: false, adb: { state: 'ready' }, name: S.tvs.find((t) => t.host === S.host)?.name || '', saved: true, video: 'h264', version: '0.12.0', app_name: 'TV Remote' });

  const appRow = (p) => ({ pkg: p, off: S.off.has(p), keep: SYS.some((s) => s[0] === p) ? true : false, meta: { label: META[p].label, icon: true } });
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // ---------- the router ----------
  async function route(method, path, q, b) {
    const post = method === 'POST';
    switch (path) {
      case '/api/state': return ok(state());
      case '/api/about': return ok({ version: '0.12.0', released: 'demo' });
      case '/api/update': return ok({ packaged: false, current: '0.12.0' });
      case '/api/tvs': return ok({ tvs: S.tvs, current: S.host });
      case '/api/tv/select': S.host = b.host || S.host; return ok({ ok: true });
      case '/api/tv/save': return ok({ ok: true, tv: S.tvs[0] });
      case '/api/tv/remove': S.tvs = S.tvs.filter((t) => t.host !== b.host); if (S.host === b.host && S.tvs[0]) S.host = S.tvs[0].host; return ok({ ok: true });
      case '/api/discover': return ok({ found: [{ ip: '192.168.1.20', name: 'Living room TV', kind: 'android', remote: true, adb: true, via: 'lan' }, { ip: '192.168.1.34', name: 'Bedroom TV', kind: 'android', remote: true, adb: true, via: 'lan' }], done: true });
      case '/api/setup/status': return ok({ host: S.host, model: dev.model, kind: 'android', adb: { state: 'ready' }, tailscale_ip: null, remote: { connected: true, paired: true },
        saved: S.tvs.find((t) => t.host === S.host) || null });
      case '/api/setup/need': return ok({ ok: true });
      case '/api/setup/version': return ok({ version: '0.12.0' });
      case '/api/adb/connect': return ok({ ok: true });
      case '/api/adb/prompt': return ok({ asking: false });
      case '/api/key': key(b.key); return ok({ ok: true });
      case '/api/volume': S.vol = Math.max(0, Math.min(30, +b.level || 0)); return ok({ ok: true });
      case '/api/app': if (b.link && /^[\w.]+$/.test(b.link)) openApp(b.link); return ok({ ok: true, via: 'adb' });
      case '/api/app/launch': if (META[b.pkg]) openApp(b.pkg); return ok({ ok: true });
      case '/api/apps': { const sys = q.get('system') === '1'; return ok({ apps: [...APPS, ...(sys ? SYS : [])].map((a) => appRow(a[0])) }); }
      case '/api/apps/boot': return ok({ text: S.boot.map((p) => p + '/.BootReceiver').join('\n') });
      case '/api/apps/report': return ok({ device: dev.maker + ' ' + dev.model, android: '14', ram_mb: 2048,   // AppsApi.kt appsReport
        user: APPS.map((a) => a[0]), system: SYS.map((a) => a[0]), disabled: [], labels: Object.fromEntries(APPS.map((a) => [a[0], a[1]])),
        memory: APPS.slice(0, 5).map((a, i) => ({ pkg: a[0], mb: [184, 122, 96, 71, 48][i] })) });
      case '/api/app/meta': { const m = META[q.get('pkg')]; return m ? ok({ label: m.label, icon: true }) : fail('not found', 404); }
      case '/api/app/icon': return { status: 200, type: 'image/svg+xml', body: iconSvg(q.get('pkg')) };
      case '/api/app/info': return ok({ text: '' });
      case '/api/recents': if (post) { if (b.all) { const n = S.open.filter((p) => p !== S.app).length; S.open = S.open.filter((p) => p === S.app); return ok({ ok: true, closed: n }); } S.open = S.open.filter((p) => p !== b.pkg); if (S.app === b.pkg) S.app = ''; return ok({ ok: true }); }
        return ok({ apps: S.open.map((p, i) => ({ pkg: p, top: p === S.app, ago: i * 540, keep: SYS.some((s) => s[0] === p) ? 'system' : '' })) });
      case '/api/procs': return ok({ apps: [...S.open.map((p, i) => ({ pkg: p, mb: 310 - i * 70 })), { pkg: 'dev.tvremote.setup', mb: 96, keep: 'us', state: 'bg', label: 'TV Setup' }, { pkg: 'com.demo.launcher', mb: 180, keep: 'home', state: 'home', label: 'TV Home' }, { pkg: 'com.demo.keyboard', mb: 64, keep: 'keyboard', state: 'bg', label: 'TV Keyboard' }, { pkg: 'com.demo.weather', mb: 58, state: 'cached', label: 'Weather' }] });
      case '/api/procs/kill': return ok({ ok: true });
      case '/api/inputs': return ok({ inputs: [{ id: 'hdmi1', label: 'HDMI 1 · Game console', connected: true }, { id: 'hdmi2', label: 'HDMI 2', connected: false }, { id: 'hdmi3', label: 'HDMI 3 · Soundbar', connected: true }] });
      case '/api/input': return ok({ ok: true });
      case '/api/playing': { const p = S.playing; if (!p) return ok({ state: 'none' });
        const pos = p.state === 'playing' ? p.pos + (now() - p.at) : p.pos; return ok({ ...p, pos: Math.min(pos, p.dur) }); }
      case '/api/screen': return { status: 200, type: 'image/svg+xml', body: screenSvg() };
      case '/api/video': case '/api/audio': return fail(say('Bản dùng thử chỉ có ảnh chụp màn hình.', 'The demo only has screenshots.'), 501);
      case '/api/files': { const p = fsPath(q.get('path')), items = FS[p]; if (!items) return fail('No such folder', 404);
        return ok({ path: p, items, volumes: [], space: { free: 17.4 * 1073741824, total: 28 * 1073741824 } }); }
      case '/api/space': return ok({ ok: true, free: 17.4 * 1073741824, total: 28 * 1073741824 });
      case '/api/file/mkdir': { const p = fsPath(b.path), n = b.name || b.dir; if (FS[p] && n) { FS[p].push(D(n, 0)); FS[join(p, n)] = []; } return ok({ ok: true }); }
      case '/api/file/rename': { const f = (FS[fsPath(b.dir || b.path.replace(/\/[^/]*$/, ''))] || []).find((x) => x.name === b.old || b.path?.endsWith('/' + x.name)); if (f && b.name) f.name = b.name; return ok({ ok: true }); }
      case '/api/file/delete': { const full = b.path || '', dir = full.replace(/\/[^/]*$/, ''), n = full.replace(/^.*\//, ''); if (FS[dir]) FS[dir] = FS[dir].filter((x) => x.name !== n); return ok({ ok: true }); }
      case '/api/health': return ok(health());
      case '/api/stats': return ok(stats(+q.get('days') || 7));
      case '/api/power': return ok(power());
      case '/api/caps': return ok({ text: CAPS });
      case '/api/settings': return ok({ ok: true, text: SETTINGS });
      case '/api/crashes': return ok({ text: '' });
      case '/api/log': return ok({ text: 'pid 0\n' });
      case '/api/free': return ok({ ok: true, freed: 412 * 1048576 });
      case '/api/tvset': return ok({ text: 'sdk 34\ndns_mode off\ndns_host null\nsleep 1800000\nsaver 1\nsaver_after 300000\nPhysical density: 320\nOverride density: 320\nhome com.demo.launcher/.Home\nlauncher com.demo.launcher/.Home\n' });
      case '/api/rules': if (post && b) ruleDo(b); return ok(rules());
      case '/api/scenes': if (post && b && b.scenes) S.scenes = b.scenes.map((s, i) => ({ ...s, id: s.id || 's' + now() + i })); return ok({ scenes: S.scenes });
      case '/api/scene/run': { const sc = S.scenes.find((s) => s.id === b.id) || { steps: b.steps || [] };
        for (const st of sc.steps) { if (st.do === 'app') openApp(st.pkg); else if (st.do === 'volume') S.vol = st.level; else if (st.do === 'wake') S.on = true; else if (st.do === 'sleep') S.on = false; else if (st.do === 'timer') S.sleepAt = now() + st.min * MIN; }
        return ok({ ok: true }); }
      case '/api/hook/config': if (post && b) { if (b.do === 'on' || b.do === 'off') S.hook.on = b.do === 'on'; } return ok({ on: S.hook.on, shortcut: 'http://192.168.1.20:8766/api/hook?k=3f9a1c7e5b2d4a60b8c1d9e7f2a4b6c8&key=HOME', yaml: 'rest_command:\n  tv_key:\n    url: "http://192.168.1.20:8766/api/hook"\n    method: post\n    headers:\n      x-tvr-hook: "3f9a1c7e5b2d4a60b8c1d9e7f2a4b6c8"\n    content_type: "application/json"\n    payload: \'{"key": "{{ key }}"}\'' });
      case '/api/tailscale': return ok({ installed: false, ip: '' });
      case '/api/welcome': return ok({ has: false });
      case '/api/draw': drawOps(b.ops); return ok({ ok: true, pic: true });
      case '/api/launchers': {                                        // the site's own signed list, as the apps read it
        const g = await (await fetch(new URL('../launchers/launchers.json', location.href))).json();
        return ok({ updated: g.updated, record: '', tv_setup: true, back: false, home: 'com.demo.launcher', launchers: g.launchers.map((l) => ({
          id: l.id, name: l.name, desc: l.desc, kind: l.kind, pkg: l.package, source: l.source, license: l.license, version: l.apk ? l.apk.version : '',
          thumb: new URL('../launchers/' + l.thumb, location.href).href, notes: l.supports.notes, min_sdk: l.supports.min_sdk, fit: true, why: '', installed: false, in_use: false })) });
      }
      case '/api/fleet': return ok({ tvs: S.tvs.map((t) => ({ host: t.host, name: t.name, state: 'ready', model: dev.model, android: '14', disk_free: 17.4 * 1073741824, disk_total: 28 * 1073741824, ram: 3.6 * 1073741824, setup: '0.12.0' })) });
      case '/api/fleet/status': return ok({ tvs: S.tvs.map((t, i) => ({ host: t.host, state: i ? 'standby' : S.on ? 'on' : 'standby', app: i ? '' : S.app })) });
      case '/api/fleet/shot': return { status: 200, type: 'image/svg+xml', body: screenSvg() };
      case '/api/paired': return ok({ devices: [] });
      case '/api/share': return ok({ url: '', setup: 'missing' });
      case '/api/diagnose': return ok({ ok: true });
      case '/api/job': return ok({ status: 'done', log: '' });
      case '/api/wirelog': return ok({ text: '' });
      case '/api/search': return ok({ ok: true });
      case '/api/clip': return ok({ text: '' });
      case '/api/watchers': return ok({ watchers: [] });
      case '/api/assist': return ok({ status: 'idle', msg: '', front: '', connected: true });
      case '/api/voice': return ok({ ok: true });
      case '/api/setup/plan': return ok({ anim: true, flauncher: false, home: '', suggest: [], apps: [] });
      // a real TV would change (or a file would leave this browser): the demo says so instead
      case '/api/install': case '/api/file/install': case '/api/upload': case '/api/fetch': case '/api/reboot': case '/api/setup': case '/api/setup/update':
      case '/api/clean': case '/api/shell': case '/api/shutdown': case '/api/launcher/install': case '/api/launcher/play': case '/api/launcher/restore':
      case '/api/pair/start': case '/api/pair/finish': case '/api/adb/pair': case '/api/vidaa/cert': case '/api/tailscale/start': case '/api/fleet/install': case '/api/fleet/do':
      case '/api/fleet/welcome': case '/api/app/backup': case '/api/app/perm': case '/api/wake': return path === '/api/wake' ? ok({ ok: true }) : demo();
      case '/api/screen/off': S.on = false; return ok({ ok: true });
    }
    if (path.startsWith('/api/app/')) return demo();                  // uninstall, disable, clear data...: /api/app/<name>
    if (post) return ok({ ok: true });                                // touch, type, text, notify, draw, warm, cancel...
    console.info('demo: no mock for', method, path);
    return fail('not found', 404);
  }

  // sw.js asks; the answer goes back through the channel it brought
  window.demoApi = async ({ method, path, search, body }) => {
    let b = {}; try { b = body ? JSON.parse(body) : {}; } catch {}
    const r = await route(method, path, new URLSearchParams(search), b || {});
    if (typeof r.body !== 'string') r.body = JSON.stringify(r.body);
    return r;
  };
  window.demoReset = () => { location.reload(); };
  void sleep;
})();
