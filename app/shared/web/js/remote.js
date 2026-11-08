// remote keys, keyboard and mouse passthrough
// ---------- the rows of keys the page draws from remote.json (the same file the phone app's remote reads) ----------
// A key is bound below like the hand-made ones (data-k); an action ("do") runs its REMOTE_DO handler (remote_spec.js).
const remoteKey = (raw) => {
  const e = remoteEntry(raw);
  const b = el('button', { className: 'rb lab' + (e.style ? ' ' + e.style : ''), id: 'r-' + e.id, innerHTML: '<svg class="ic" aria-hidden="true"><use href="#i-' + e.icon + '"/></svg><small></small>' });
  b.querySelector('small').textContent = remoteWords(e.t);
  if (e.key) b.dataset.k = e.key; else b.addEventListener('click', () => REMOTE_DO[e.do](e.arg));
  const needs = [...e.needs || [], ...remoteRole(e)];
  if (needs.length) b.dataset.needs = needs.join(' ');                // greyed on a TV that cannot, or for a guest (applyKind in core.js)
  b.dataset.rid = e.id;
  return b;
};
document.querySelectorAll('[data-rows]').forEach((box) => {
  for (const row of REMOTE.rows[box.dataset.rows] || []) box.append(...row.filter(remoteHere).map(remoteKey));
});
// The Simple remote (remote.json "simple"): its rows of big keys, the D-pad a copy of the one above (bound below with the rest)
for (const row of (REMOTE.simple && REMOTE.simple.rows) || []) {
  $('#simplekeys').append(el('div', { className: 'simplerow' }, ...row.filter(remoteHere).map((raw) => raw.expand === 'pad' ? $('.rc .dpad').cloneNode(true) : remoteKey(raw))));
}
// A key the connected TV names differently (remote.json "kinds": VIDAA's SETTINGS is its Menu) is relabelled when the kind
// changes: applyKind (core.js) sets data-kind on <html>, and the rows are drawn before the TV is known.
const paintRows = () => document.querySelectorAll('[data-rows] [data-rid]').forEach((b) => {
  const raw = Object.values(REMOTE.rows).flat(2).find((x) => x.id === b.dataset.rid); if (!raw || !raw.kinds) return;
  const e = remoteEntry(raw);
  b.querySelector('use').setAttribute('href', '#i-' + e.icon); b.querySelector('small').textContent = remoteWords(e.t);
});
paintRows(); new MutationObserver(paintRows).observe(document.documentElement, { attributes: true, attributeFilter: ['data-kind'] });
// ---------- keys ----------
// A tap counts when the pointer lifts on the key (slide off it and nothing is sent). Hold (0.4 s) = the TV sees the key held down until you let go: fast scrolling, long-press menus.
document.querySelectorAll('[data-k]').forEach((b) => {
  const k = b.dataset.k; let t = null, long = false, down = false;
  const power = k === 'POWER';                                     // never a long press, and turning the TV off needs a yes
  const send = async (dir) => {
    if (power && tvOn && !await ask(T('remote.standby_ask'), { ok: T('remote.turn_off') })) return;
    return api('/api/key', dir ? { key: k, dir } : { key: k }).then((r) => { if (r && r.noop) toast(T('remote.key_no_reaction')); return r; });
  };
  const release = (tap) => {
    if (!down) return; down = false; clearTimeout(t);
    b.classList.remove('held');
    if (long) { long = false; send('END_LONG'); } else if (tap) { buzz(); flash(); send(); }
    setTimeout(refresh, 350);
  };
  const inside = (e) => { const r = b.getBoundingClientRect(); return e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom; };
  const flash = () => { b.classList.add('held'); setTimeout(() => { if (!long) b.classList.remove('held'); }, 160); };
  b.addEventListener('pointerdown', (e) => {
    if (e.button || b.disabled) return; down = true; long = false; try { b.setPointerCapture(e.pointerId); } catch {}   // disabled: a key this TV does not have (applyKind)
    if (!power) t = setTimeout(() => { long = true; buzz(); b.classList.add('held'); send('START_LONG'); }, 400);
  });
  b.addEventListener('pointermove', (e) => { if (down && !inside(e)) release(false); });
  b.addEventListener('pointerup', (e) => { release(inside(e)); b.blur(); });           // keep focus off the button so typing goes to the TV
  ['pointercancel', 'lostpointercapture'].forEach((ev) => b.addEventListener(ev, () => release(false)));
  b.addEventListener('click', (e) => { if (e.detail === 0) send(); });                          // keyboard activation
  b.addEventListener('contextmenu', (e) => e.preventDefault());
});
function normalizeLink(v) {
  v = v.trim(); if (!v) return '';
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(v)) return v;
  return 'https://' + v;
}
async function openOnTv(link, label) {
  const r = await api('/api/app', { link });
  if (r) { toast(r.via === 'adb' || NATIVE() ? T('remote.opened_name', { name: label }) : T('remote.sent_no_adb')); setTimeout(refresh, 1500); }
}
// ---------- "Thêm phím", third row: Mở trên TV, Hẹn giờ tắt, Cảnh, Vẽ lên TV; their menus' entries are in remote.json ----------
// The same menus as the phone app's remote (RemoteScreen.kt, PhoneRemote.kt); the entries live in remote.json, what they do here in REMOTE_DO.
const needAdb = () => { if (ADB.state === 'ready') return false; toast(NATIVE() ? kindWhy() : T('remote.this_needs_adb')); return true; };
// a dialog opened from the remote right after the page (the phone's Manage screen) came up: ADB is often still connecting
// ('unknown'), so wait for it (core.js polls fast while connecting) before saying it is off
const needAdbSoon = async () => {
  if (ADB.state === 'unknown' || !ADB.state) {
    toast(T('core.adb_connecting'));
    for (let i = 0; i < 40 && (ADB.state === 'unknown' || !ADB.state); i++) await sleep(200);
  }
  return needAdb();
};
const storePage = (id) => openOnTv('https://play.google.com/store/apps/details?id=' + id, 'CH Play');
// the TV's Play Store takes a search only as market://search (a play.google.com search link ends in a browser error)
const storeSearch = (q) => openOnTv('market://search?c=apps&q=' + encodeURIComponent(q), 'CH Play');
const castPick = (accept) => { if (needAdb()) return; const f = $('#castfile'); f.accept = accept; f.click(); };   // the picker must open within the tap
async function askStoreSearch() { const v = await ask(T('remote.search_play_store'), { value: '', ok: T('remote.search') }); if (v && v.trim()) storeSearch(v.trim()); }
async function askStorePage() { const v = await ask(T('remote.package_id'), { value: 'org.videolan.vlc', ok: T('remote.open') }); if (v && v.trim()) storePage(v.trim()); }
// a link to the media itself (core Channels.isStream: the same list) plays in TV Channels' player, most TVs having nothing that opens
// it; a page opens as before. Without ADB or TV Channels it goes as any link, and the person hears that TV Channels would play it
const isStream = (l) => { try { const u = new URL(l); return /^https?:$/.test(u.protocol) && /\.(m3u8|m3u|mpd|mp4|m4v|mkv|webm|ts|mov|mp3|aac|m4a|flac|ogg)$/i.test(u.pathname); } catch (e) { return false; } };
async function askLink() {
  const v = await ask(T('remote.link'), { value: 'https://youtu.be/', ok: T('remote.open') });
  const l = v && normalizeLink(v);
  if (!l) return;
  if (ytParse(l)) return homePin(l);                                  // YouTube: the pin box (Pin to TV or Open on TV), filled in
  if (isStream(l) && ADB.state === 'ready') {
    const c = await api('/api/channels', null, true);
    if (c && c.installed) return chanPlay(l);
    toast(T('remote.stream_needs_channels'));
  }
  if (ADB.state === 'ready') return homePin(l);                       // any other link: what opens it on this TV, then Try on the TV or Pin to TV Home
  openOnTv(l, 'link');
}
// The picture takes a few seconds on the TV: its key turns until the file is saved, or the tap looks lost. A browser gets it
// here; the computer app's window saves downloads itself (Window.kt tvrSave) and calls shotDone() once the file is written.
async function screenshot() {
  const b = $('#r-screenshot'), name = 'tv-' + Math.floor(Date.now() / 1000) + '.png';
  if (b.classList.contains('busy')) return;                         // one at a time
  b.classList.add('busy'); toast(T('remote_screen.taking_a_screenshot_of'));
  clearTimeout(shotDone.t); shotDone.t = setTimeout(shotDone, 60000);  // the window's word may never come
  if (window.tvrSave) { window.tvrSave('/api/screen', name); return; }
  try {
    const r = await fetch('/api/screen');
    if (!r.ok) { let e = r.statusText; try { e = (await r.json()).error || e; } catch {} toast(T('common.error_msg', { msg: friendly(adbWords(r.status, e)) })); return; }
    await saveFile(name, await r.blob(), 'image/png');                // the Phone app saves only a link it can fetch again
  } catch { toast(T('core.server_unreachable')); } finally { shotDone(); }
}
function shotDone() { clearTimeout(shotDone.t); $('#r-screenshot').classList.remove('busy'); }
async function timerPlus() { const x = await loadRules({ do: 'sleep_add', minutes: 15 }); if (x) toast(T('remote.tv_off_at_toast', { at: clock(x.sleep_at) })); }
async function timerCustom() {
  const v = await ask(T('remote.turn_off_when'), { value: '', ok: T('remote.set_timer') }); if (v === null) return;
  const m = sleepMinutes(v); if (m) setSleep(m); else toast(T('remote.type_minutes_or_time'));
}
const quickOpen = () => menuDlg(T('remote.open_on_tv'), -1, remoteMenu('open'));
async function timerMenu() {
  if (NATIVE()) { menuDlg(T('remote.tv_screen'), -1, remoteMenu('timer')); return; }   // LG: the screen off only; the timer lives in TV Setup
  if (await needAdbSoon() || !await setupFor('rules')) return;                  // TV Setup runs the timer on the TV (SetupNeed "rules")
  const r = await loadRules(null, true); if (!r) return;
  const at = r.sleep_at || 0, hm = (m) => m < 60 ? T('remote.min_n', { n: m }) : m % 60 ? T('remote.h_min', { h: Math.floor(m / 60), m: m % 60 }) : T('remote.h_n', { n: m / 60 });
  const lengths = (ms) => ms.map((m) => [T('remote.off_in', { when: hm(m) }), '', () => setSleep(m)]);
  const items = remoteMenu('timer', { when: { timerOn: !!at }, expand: { lengths: () => lengths([30, 60, 120]), lengthsMore: () => lengths([15, 45, 90]) } });
  menuDlg(at ? T('remote.tv_off_at_menu', { at: clock(at) }) : T('remote.sleep_timer'), at ? 0 : -1, items);
}
// "Ngõ vào": the TV's real inputs by name ("HDMI 1 · PlayStation") and a tap to switch. A TV with none to list (a box, the emulator,
// Samsung) or no ADB: one short line, and the TV's own picker opens, as the key always did.
const pressInput = () => api('/api/key', { key: 'INPUT' });
async function inputsMenu() {
  if (!can('inputs')) return pressInput();
  const r = await api('/api/inputs', null, true), list = (r && r.inputs) || [];
  if (!list.length) { toast(r ? T('remote.no_inputs') : T('remote.inputs_unreadable')); return pressInput(); }
  menuDlg(T('remote.input'), -1, [...list.map((i) => ['@input|' + i.label, i.connected === false ? T('remote.not_plugged') : '', () => switchTo(i)]), ['@menu|' + T('remote.tv_picker'), '', pressInput]]);
}
async function switchTo(i) {
  const r = await api('/api/input', { id: i.id });
  if (r) { toast(T('remote.switched_to', { name: i.label })); setTimeout(refresh, 1500); }
}
function scenesMenu() {
  if (!NATIVE() && needAdb()) return;                                 // LG / Samsung run scenes by their remote link
  menuDlg(T('remote.scenes'), -1, remoteMenu('scenes', { expand: { scenes: () => SCENES.map((sc) => [((sc.icon || '') + '  ' + sc.name).trim(), '', () => runScene(sc)]) } }));
}
// ---------- keyboard: whatever is typed in the box is mirrored on the TV, keystroke by keystroke ----------
const txt = $('#text');
let typed = '', inputQ = Promise.resolve();
const queue = (path, data) => { inputQ = inputQ.then(() => api(path, data)); };   // one at a time, in order
function syncText() {                                                 // send only the difference: backspaces, then new characters
  const v = txt.value; let i = 0;
  while (i < typed.length && i < v.length && typed[i] === v[i]) i++;
  const del = [...typed.slice(i)].length, text = v.slice(i); typed = v;
  if (del || text) queue('/api/type', { del, text });
}
// Two ways to type, as in the phone app. Live (⚡ on): the TV follows every letter. Send: write first, then send it in one go.
let live = true;
function setLive(on) {
  live = on; try { localStorage.setItem('live', on ? '1' : '0'); } catch {}
  $('#live').classList.toggle('on', on);
  txt.placeholder = on ? T('remote.type_on_the_tv') : T('remote.type_then_send'); txt.value = typed = '';
}
$('#live').addEventListener('click', () => setLive(!live));
txt.addEventListener('input', (e) => { if (live && !e.isComposing) syncText(); });
txt.addEventListener('compositionend', () => live && syncText());   // Vietnamese / IME input arrives when the syllable is done
$('#textf').addEventListener('submit', (e) => {
  e.preventDefault();
  if (live) { syncText(); queue('/api/type', { enter: true }); } else if (txt.value) queue('/api/type', { text: txt.value });
  txt.value = typed = '';
});
$('#del').addEventListener('click', () => { if (txt.value) { txt.value = [...txt.value].slice(0, -1).join(''); if (live) syncText(); } else queue('/api/type', { del: 1 }); });
function setMore(on, now) { if (on && $('#kbdbtn').classList.contains('on')) setKbd(false, now); if (now) put('#more', 'hidden', !on); else slide($('#more'), on); $('#morebtn').classList.toggle('on', on); try { localStorage.setItem('more', on ? '1' : ''); } catch {} }
try { setMore(!!localStorage.getItem('more'), true); } catch {}
$('#morebtn').addEventListener('click', () => setMore(!$('#morebtn').classList.contains('on')));
$('#digits').addEventListener('click', () => { const p = $('#numpad'), on = p.hidden || p.classList.contains('shutting'); slide(p, on); try { localStorage.setItem('numpad', on ? '1' : ''); } catch {} });
try { setLive(localStorage.getItem('live') !== '0'); put('#numpad', 'hidden', !localStorage.getItem('numpad')); } catch { setLive(true); }

// ---------- mouse: click, drag and scroll on the picture act as a finger on the TV screen ----------
// The picture is an <img> (frames from the computer) or a <video> (H.264 straight from the TV): both take the same gestures.
let touching = false, lastMove = 0, lastWheel = 0, lastPos = { x: .5, y: .5 }, tapDot = null;
// Where the finger landed: a dot that follows a drag and fades out in a ring when let go, so a click on a still picture shows.
function tapMark(e, what) {
  const box = $('#screenbox').getBoundingClientRect(), at = (m) => { m.style.left = (e.clientX - box.left) + 'px'; m.style.top = (e.clientY - box.top) + 'px'; };
  if (what === 'move') { if (tapDot) at(tapDot); return; }
  if (what === 'down') { if (tapDot) tapDot.remove(); tapDot = el('div', { className: 'tap' }); at(tapDot); $('#screenbox').append(tapDot); return; }
  const m = tapDot; if (!m) return; tapDot = null;
  m.classList.add('gone'); m.addEventListener('animationend', () => m.remove());
}
// Moves are only worth their latest position: a slow link keeps one waiting move and updates it, so the TV never replays a drag
// seconds after the finger lifted. Scrolls past a few waiting ones are dropped for the same reason.
let moveNext = null, waiting = 0;
function touch(a, p, extra) {
  const m = { a, x: p.x, y: p.y, ...extra };
  if (a === 'move') { const had = moveNext; moveNext = m; if (!had) inputQ = inputQ.then(() => { const n = moveNext; moveNext = null; return api('/api/touch', n); }); return; }
  if (a === 'scroll' && waiting > 2) return;
  waiting++; inputQ = inputQ.then(() => api('/api/touch', m)).finally(() => { waiting--; });
}
[$('#screen'), $('#screenv'), $('#screenc')].forEach((scr) => {
  scr.draggable = false;
  const screenPos = (e) => {                                          // pointer -> 0..1 inside the picture (the box letterboxes it)
    const r = scr.getBoundingClientRect(), [nw, nh] = picSize(scr).map((v, i) => v || [16, 9][i]);
    const k = Math.min(r.width / nw, r.height / nh), w = nw * k, h = nh * k;
    const x = (e.clientX - r.left - (r.width - w) / 2) / w, y = (e.clientY - r.top - (r.height - h) / 2) / h;
    return x < 0 || x > 1 || y < 0 || y > 1 ? null : { x, y };
  };
  scr.addEventListener('pointerdown', (e) => {
    const p = screenPos(e); if (e.button || !p || !can('control')) return;   // a guest who may only watch: the picture is not a touchpad
    e.preventDefault(); touching = true; lastPos = p; try { scr.setPointerCapture(e.pointerId); } catch {} touch('down', p); tapMark(e, 'down');
  });
  scr.addEventListener('pointermove', (e) => {
    if (touching && screenPos(e)) tapMark(e, 'move');
    const p = screenPos(e); if (!touching || !p || Date.now() - lastMove < 40) return;
    lastMove = Date.now(); lastPos = p; touch('move', p);
  });
  ['pointerup', 'pointercancel'].forEach((ev) => scr.addEventListener(ev, (e) => { if (touching) { touching = false; touch('up', screenPos(e) || lastPos); tapMark(e, 'up'); } }));
  scr.addEventListener('wheel', (e) => {
    const p = screenPos(e); if (!p || !can('control')) return;
    e.preventDefault(); if (Date.now() - lastWheel < 90) return;
    lastWheel = Date.now(); touch('scroll', p, { dy: e.deltaY > 0 ? -1 : 1 });
  }, { passive: false });
  scr.addEventListener('contextmenu', (e) => { e.preventDefault(); api('/api/key', { key: 'BACK' }); });   // right click = Back
});
$('#kbdbtn').addEventListener('click', () => { if ($('#kbdbtn').classList.contains('on')) { kbdFollow.closed(); setKbd(false); } else kbdGo(); });
// The computer keyboard is the TV's keyboard on the Remote tab, no need to click into the box first:
// letters go to the TV's text field, Backspace/Delete erase, arrows move, Enter confirms, Esc = Back.
const keymap = { ArrowUp:'UP', ArrowDown:'DOWN', ArrowLeft:'LEFT', ArrowRight:'RIGHT', Escape:'BACK', Home:'HOME' };
const typingHere = () => ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName);
document.addEventListener('keydown', (e) => {
  if (window.ONTV || currentTab !== 'remote' || picking || typingHere() || e.metaKey || e.ctrlKey || e.altKey || e.isComposing || document.querySelector('dialog[open]')) return;   // any dialog open: its keys are its own
  const onButton = ['BUTTON', 'SUMMARY', 'A'].includes(document.activeElement.tagName);
  if (document.activeElement.closest('nav.tabs') && /^(Arrow(Left|Right)|Home|End)$/.test(e.key)) return;   // the tab bar's own arrows
  if (onButton && (e.key === 'Enter' || e.key === ' ' || e.key === 'Tab')) return;   // keyboard users still operate the page
  if (e.key === 'Escape' && document.fullscreenElement) return;       // let Esc leave fullscreen
  const adb = ADB.state === 'ready' || NATIVE() && can('text');      // letters: ADB, or LG's / Samsung's keyboard link
  if (keymap[e.key] || e.key === 'Enter') { e.preventDefault(); if (!e.repeat) keyDown(e.key); }   // sent on keyup: held, it is a long press
  else if (e.key === 'Backspace') { e.preventDefault(); if (adb) queue('/api/type', { del: 1 }); else api('/api/key', { key: 'DEL' }); }
  else if (!adb) return;                                              // letters need ADB
  else if (e.key === 'Delete') { e.preventDefault(); queue('/api/type', { fdel: 1 }); }
  else if (e.key.length === 1) { e.preventDefault(); queue('/api/type', { text: e.key }); }
});
// A key held 0.4 s = held on the TV until it is let go (fast scrolling, long-press menus), as the on-screen keys; a short one is one press
// (Enter: OK, or Enter in a field)
const held = {};                                                      // key -> { t: timer, long }
const keyDown = (key) => {
  if (held[key]) return; const h = held[key] = { long: false };
  h.t = setTimeout(() => { h.long = true; api('/api/key', { key: keymap[key] || 'OK', dir: 'START_LONG' }); }, 400);
};
const keyUp = (key, tap) => {
  const h = held[key]; if (!h) return; clearTimeout(h.t); delete held[key];
  if (h.long) api('/api/key', { key: keymap[key] || 'OK', dir: 'END_LONG' });
  else if (!tap) return;
  else if (keymap[key]) api('/api/key', { key: keymap[key] });
  else if (ADB.state === 'ready' || NATIVE() && can('text')) queue('/api/type', { enter: true }); else api('/api/key', { key: 'OK' });
};
document.addEventListener('keyup', (e) => keyUp(e.key, true));
window.addEventListener('blur', () => Object.keys(held).forEach((k) => keyUp(k, false)));   // let go elsewhere: no keyup here, the TV must not stay held
document.addEventListener('paste', (e) => {                           // Cmd+V anywhere on the Remote tab pastes onto the TV
  if (window.ONTV || currentTab !== 'remote' || typingHere() || document.querySelector('dialog[open]') || !(ADB.state === 'ready' || NATIVE() && can('text'))) return;
  const t = (e.clipboardData || window.clipboardData).getData('text'); if (t) { e.preventDefault(); queue('/api/type', { text: t.slice(0, 500) }); }
});


// ---------- ask the TV: typed or spoken here; what is not a command is looked up on YouTube (else the TV's search) ----------
async function askTv(q) {
  q = q.trim(); if (!q) return;
  await loadVoicePrefs();                                              // the apps picked for "tìm X" and "YouTube" (TV Setup's rules; none: today's behaviour)
  if (voiceWantsApp(q, null, VOICE_PREFS)) await loadVoicePins();      // "mở <a tile on TV Home>": its pinned tiles are named first
  const c = voiceCommand(q, voiceApps(), SCENES, null, voicePins(), VOICE_PREFS);                   // "mở YouTube", "tắt tiếng", a scene's name...: done here, at once (voice.js)
  if (c) { doCommand(c); return; }
  const r = await api('/api/search', { query: q });
  if (r) { toast(T(r.to === 'youtube' ? 'remote.searching_youtube' : 'remote.sent_to_tv', { q })); setTimeout(refresh, 2500); }
}
$('#askf').addEventListener('submit', (e) => { e.preventDefault(); askTv($('#ask').value); $('#ask').value = ''; });
// speech is turned into text by the browser (Chrome, Safari), not by the TV. Browsers allow the microphone only on secure pages:
// the TV's own page is plain HTTP, so there the phone keyboard's own microphone key does it.
const Speech = window.SpeechRecognition || window.webkitSpeechRecognition;
if (!(Speech && window.isSecureContext) && matchMedia('(pointer:coarse)').matches) $('#ask').placeholder = T('remote.ask_keyboard_mic');
let rec = null;
function hear() {                                                     // this device's speech as words, then to the TV
  if (rec) { rec.stop(); return; }
  const r0 = rec = new Speech(); let dropped = false, handOver = false, mic = null;
  rec.lang = window.SPEECH_LANG || LANG; rec.interimResults = true;
  rec.onresult = (e) => { const r = e.results[e.results.length - 1]; hearWords(r[0].transcript); if (r.isFinal && !dropped) askTv(r[0].transcript); };
  rec.onerror = (e) => {
    if (e.error === 'aborted' || e.error === 'no-speech') return;
    if (e.error === 'service-not-allowed' && speechRefused()) { dropped = handOver = true; return; }   // onend hands over to the TV's Assistant
    toast(T('remote.error_why', { why: e.error === 'not-allowed' ? T('remote.mic_not_allowed') : e.error }));
  };
  rec.onend = () => {
    rec = null; $('#voice').classList.remove('on'); if (mic) mic.getTracks().forEach((t) => t.stop());
    if (hearing && hearing.r === r0) hearClose();
    if (handOver) talk();
  };
  try { rec.start(); } catch (e) { rec = null; toast(T('remote.error_why', { why: e.message || e })); return; }
  $('#voice').classList.add('on');
  hearOpen({ r: r0, done: () => r0.stop(), cancel: () => { dropped = true; r0.abort(); } }, null);
  // the bars want the microphone too: a second listener on it, given back when this ends
  if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) navigator.mediaDevices.getUserMedia({ audio: true })
    .then((s) => { if (rec === r0) { mic = s; hearMeter(s); } else s.getTracks().forEach((t) => t.stop()); }).catch(() => {});
}
// ⌨️ opens the typing box and the "Hỏi TV" box right under the top row, as More opens its keys: the picture stays in sight.
// Opened by Speak (nothing to hear with) or More's entry: open, the keyboard up in the box that works (typing needs ADB)
function setKbd(on, now) {                                           // one fold at a time: the D-pad stays close
  if (on && $('#morebtn').classList.contains('on')) setMore(false, now);
  if (now) put('#kbdpane', 'hidden', !on); else slide($('#kbdpane'), on);
  $('#kbdbtn').classList.toggle('on', on);
}
try { localStorage.removeItem('kbd'); } catch {}                     // it starts shut: open again by itself it looked like a keyboard nobody asked for
function kbdGo(at) {
  if (!$('#kbdbtn').classList.contains('on')) setKbd(true);
  $(at || ($('#kbd').disabled ? '#ask' : '#text')).focus({ preventScroll: true });
}
// Speak, as on the phone app: this device turns speech into text (the browser), or its microphone goes to the TV's Assistant
// (talk.js, where the window cannot), else the "Hỏi TV" box takes it typed (on a phone its keyboard's 🎤); held, the TV's
// own Assistant.
{
  const v = $('#voice'); let held = 0;
  const assist = () => { api('/api/key', { key: 'ASSIST' }); toast(T('remote.assistant_opened')); };
  v.addEventListener('pointerdown', () => { held = setTimeout(() => { held = -1; assist(); }, 600); });
  ['pointerup', 'pointerleave', 'pointercancel'].forEach((e) => v.addEventListener(e, () => { if (held > 0) clearTimeout(held); }));
  v.addEventListener('contextmenu', (e) => e.preventDefault());
  v.addEventListener('click', () => {
    if (held === -1) { held = 0; return; }
    if (talkable()) talk();
    else if (Speech && window.isSecureContext) hear();
    else kbdGo('#ask');
  });
}

// ---------- the Simple remote: only remote.json's "simple" keys, big, for a parent; the rest of the page hidden (css/remote.css
// html[data-simple]). Turned on from Tools or by a shared link with ?simple=1; left only by holding its small button for 2 s,
// so a stray tap never brings the full page back. Remembered on this browser. The Phone app has its own native one (Simple.kt).
function setSimple(on, quiet) {
  if (CORE === 'phone' || window.ONTV) on = false;                    // the Manage screen and the TV itself have no remote here
  document.documentElement.toggleAttribute('data-simple', on);
  put('#simple', 'hidden', !on);
  try { localStorage.setItem('simple', on ? '1' : ''); } catch {}
  if (on && typeof showTab === 'function') showTab('remote');
  if (on && !quiet) toast(T('simple.on'));
}
{
  const q = new URLSearchParams(location.search);
  let on = false; try { on = localStorage.getItem('simple') === '1'; } catch {}
  if (q.has('simple')) {                                              // the share link: once, then this browser remembers
    on = q.get('simple') !== '0'; q.delete('simple');
    history.replaceState(null, '', location.pathname + (String(q) ? '?' + q : '') + location.hash);   // a reload must not undo leaving it
  }
  setSimple(on, true);
  const x = $('#simpleexit'); let t = 0;
  const stop = () => { clearTimeout(t); t = 0; x.classList.remove('holding'); put('#simpleexit', 'textContent', T('simple.exit')); };
  x.addEventListener('pointerdown', (e) => {
    if (e.button) return;
    x.classList.add('holding'); put('#simpleexit', 'textContent', T('simple.keep'));
    t = setTimeout(() => { stop(); buzz(); setSimple(false); }, 2000);
  });
  ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => x.addEventListener(ev, () => { if (t) stop(); }));
  x.addEventListener('click', () => { if (document.documentElement.hasAttribute('data-simple')) toast(T('simple.hold')); });   // a tap: say how
  x.addEventListener('contextmenu', (e) => e.preventDefault());
  // Speak only where this device can hear (remote.json "when": "voice"); talk.js is read after this file
  document.addEventListener('DOMContentLoaded', () => document.querySelectorAll('#simplekeys [data-rid]').forEach((b) => {
    const raw = REMOTE.simple.rows.flat().find((e) => e.id === b.dataset.rid);
    if (raw && raw.when === 'voice') b.hidden = !(talkable() || (Speech && window.isSecureContext));
  }));
  new MutationObserver(() => { const b = $('#r-simple-voice'); if (b) b.classList.toggle('on', $('#voice').classList.contains('on')); })
    .observe($('#voice'), { attributes: true, attributeFilter: ['class'] });   // lit while it listens, as the top row's
}

// ---------- the TV's own keyboard came up (someone focused a search box there): the typing box opens here, once a second ----------
// Same rule as the phone app's (TvKeyboard.Follow in Kotlin): open on the edge, shut when it goes if nothing was typed, never
// reopen in one keyboard session once the person shut the box. Needs ADB (the TV's input method is read through it).
const kbdFollow = (() => {
  let was = false, shut = false, ours = false, typedIn = false;
  return {
    read(shown) {                                                     // 'open' | 'close' | ''
      const edge = shown !== was; was = shown;
      if (!edge) return '';
      if (shown) { shut = false; typedIn = false; ours = true; return 'open'; }
      const close = ours && !typedIn && !shut; ours = false;
      return close ? 'close' : '';
    },
    closed() { shut = true; ours = false; },
    typed() { typedIn = true; },
  };
})();
txt.addEventListener('input', () => kbdFollow.typed());
let kbdAsking = false;
setInterval(async () => {
  if (kbdAsking || window.ONTV || document.hidden || currentTab !== 'remote' || picking || ADB.state !== 'ready' || !kbdAutoOn()) return;
  kbdAsking = true;
  const r = await fetch('/api/tvkbd').then((x) => x.ok ? x.json() : null).catch(() => null);
  kbdAsking = false;
  if (!r) return;
  const d = kbdFollow.read(!!r.shown);
  if (d === 'open') { if ($('#kbd').disabled) return; setKbd(true); $('#text').focus({ preventScroll: true }); }
  else if (d === 'close' && $('#kbdbtn').classList.contains('on')) { $('#text').blur(); setKbd(false); }
}, 1000);

// LG, Samsung, Roku, VIDAA let no app switch their tracking off: the paths in their own menus (the guide's #tracking-other), each a
// tap that opens Settings on the TV and this list again, so the path stays in front of the person while they walk it
const TRACK_PATHS = { lg: ['track.lg_acr', 'track.lg_ads', 'track.lg_mic'], samsung: ['track.samsung_acr', 'track.samsung_ads', 'track.samsung_mic'],
  roku: ['track.roku_acr', 'track.roku_ads', 'track.roku_mic'], vidaa: ['track.vidaa_acr', 'track.vidaa_ads', 'track.vidaa_mic'] };
function trackingMenu() {
  const k = TV_KIND, [acr, ads, mic] = TRACK_PATHS[k] || []; if (!acr) return;
  const open = () => { REMOTE_DO.key(k === 'roku' ? 'HOME' : 'SETTINGS'); setTimeout(trackingMenu, 300); };
  menuDlg(T('track.title', { tv: KIND_NAME[k] }), 0, [
    ['@settings|' + T('track.open'), T('track.open_sub'), open],
    ['@eye|' + T('track.acr'), T(acr), open],
    ['@tag|' + T('track.ads'), T(ads), open],
    ['@mic|' + T('track.mic'), T(mic), open],
    ['@guide|' + T('track.guide'), T('track.guide_sub'), () => window.open('https://tvremote.pages.dev/guide#tracking-other', '_blank')]], -1, true);
}

// Roku's hidden screens (remote.json "roku", the guide's #brands): Home and arrows pressed one by one over ECP, a pause between. The press times
// are laid out from the start, not from each answer, so a slow answer does not stretch the gaps. Long enough to stop: a dialog with Cancel.
const rokuKeys = (s) => s.split(' ').flatMap((t) => { const [k, n] = t.split('*'); return Array(+n || 1).fill(k); });
async function rokuScreens() {
  const R = REMOTE.roku, tv = !!TV_KEYS && TV_KEYS.has('VOL_UP');   // a Roku TV, not a stick
  menuDlg(T('roku.title'), -1, R.screens.filter((x) => !x.tvOnly || tv).map((x) => ['@tv|' + T(x.t), '', async () => {
    if (x.ask && !await ask(T(x.ask), { ok: T(x.askOk), danger: true })) return;
    const keys = rokuKeys(x.keys); let stop = false, shown = 0;
    const line = el('p', { textContent: T('roku.sending', { n: 1, total: keys.length }) });
    const d = el('dialog', { className: 'ask' }, el('div', { className: 'card' }, line,
      el('div', { className: 'acts' }, el('button', { type: 'button', textContent: T('common.cancel'), onclick: () => { stop = true; d.close(); } }))));
    d.addEventListener('close', () => { stop = true; d.remove(); });
    document.body.append(d); d.showModal();
    let at = performance.now();
    for (let i = 0; i < keys.length && !stop; i++) {
      line.textContent = T('roku.sending', { n: i + 1, total: keys.length });
      const r = await api('/api/key', { key: keys[i] }); if (!r) { stop = true; break; }
      const gap = keys[i] === 'HOME' && keys[i + 1] === 'HOME' ? R.homeGap : R.gap;
      shown++; at = Math.max(at + gap, performance.now() + gap / 2);   // a stalled answer (Wi-Fi): at least half a gap, not the rest at once
      if (i + 1 < keys.length) await sleep(Math.max(0, at - performance.now()));
    }
    if (d.open) d.close();
    if (shown === keys.length) toast(T('roku.done'));
  }]), -1, true);
}
