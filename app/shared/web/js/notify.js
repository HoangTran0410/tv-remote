// A message on the TV: a small card in a corner over whatever is playing (TV Setup draws it: Notify.kt, NotifyLayer.kt). The
// corner and how long it stays are remembered on this page. The numbers are clamped the way Notify.kt clamps them, so the
// chips and a scene's step say the same thing as the TV does (tests/notify.test.js keeps the two in step).
const NOTIFY_CORNERS = [['top-left'], ['top-right'], ['bottom-left'], ['bottom-right']];
// the words of a corner, looked up when they are shown (the language is known by then; tests/notify.test.js runs this file without T)
const notifyCornerName = (v) => ({ 'top-left': T('notify.top_left'), 'top-right': T('notify.top_right'), 'bottom-left': T('notify.bottom_left'), 'bottom-right': T('notify.bottom_right') })[v];
const NOTIFY_SECONDS = [3, 5, 10, 30];
const NOTIFY_CORNER = 'top-right', NOTIFY_SECOND = 5;                // the defaults

const notifyCorner = (v) => NOTIFY_CORNERS.some((c) => c[0] === v) ? v : NOTIFY_CORNER;
const notifySeconds = (v) => {
  const n = typeof v === 'number' || (typeof v === 'string' && v.trim() !== '') ? Number(v) : NaN;   // as Notify.seconds: anything else is the default
  return Number.isFinite(n) ? Math.min(60, Math.max(1, Math.round(n))) : NOTIFY_SECOND; };

function notifyPrefs() {
  let o = null;
  try { o = JSON.parse(localStorage.getItem('notify')); } catch {}
  const s = notifySeconds(o && o.seconds);
  return { corner: notifyCorner(o && o.corner), seconds: NOTIFY_SECONDS.includes(s) ? s : NOTIFY_SECOND, speak: !!(o && o.speak === true) };   // only the chips' lengths come back; read aloud is off until chosen
}
function notifySave(p) { try { localStorage.setItem('notify', JSON.stringify({ corner: p.corner, seconds: p.seconds, speak: !!p.speak })); } catch {} }
// why the TV showed the message but did not read it (TV Setup's answer: "noengine", or "nolang" with the language); '' when it did
const notifySpeakWhy = (r) => !r || !r.speak ? '' : r.speak === 'nolang' ? T('notify.speak_nolang', { lang: r.lang || '' }) : T('notify.speak_noengine');

async function notifyDialog() {
  if (await needAdbSoon()) return;
  if (!await setupFor('notify')) return;                              // TV Setup draws the card (SetupNeed "notify"): asked to update first
  const p = notifyPrefs();
  const title = el('input', { placeholder: T('notify.title_optional'), maxLength: 60, spellcheck: false, 'aria-label': T('notify.title') });
  const text = el('textarea', { placeholder: T('notify.short_message'), rows: 3, maxLength: 300, 'aria-label': T('notify.text') });
  const corners = el('div'), secs = el('div'), aloud = el('div');
  const draw = () => {
    corners.replaceChildren(UI.chips(NOTIFY_CORNERS.map(([v]) => ({ label: notifyCornerName(v), on: p.corner === v, fn: () => { p.corner = v; draw(); } }))));
    corners.firstChild.classList.add('two');                        // 2 x 2 like the screen's corners: four in a row wrap one alone on a phone
    secs.replaceChildren(UI.chips(NOTIFY_SECONDS.map((n) => ({ label: T('notify.seconds_n', { n }), on: p.seconds === n, fn: () => { p.seconds = n; draw(); } }))));
    aloud.replaceChildren(UI.chips([[false, 'notify.speak_no'], [true, 'notify.speak_yes']].map(([v, id]) => ({ label: T(id), on: p.speak === v, fn: () => { p.speak = v; draw(); } }))));
  };
  draw();
  const send = async () => {
    if (!title.value.trim() && !text.value.trim()) { toast(T('notify.type_something')); text.focus(); return; }
    if (p.speak && !await setupFor('speak')) return;                // an older TV Setup would only show it: asked to update first
    notifySave(p);
    const r = await api('/api/notify', { title: title.value.trim(), text: text.value.trim(), corner: p.corner, seconds: p.seconds, ...(p.speak ? { speak: true } : {}) });
    if (r) { toast(notifySpeakWhy(r) || T('notify.sent')); d.close(); }
  };
  title.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); text.focus(); } });
  text.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); } });
  const d = el('dialog', { className: 'ask' }, el('div', { className: 'card' },
    UI.head(T('notify.dialog_title'), el('button', { className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
    title, text,
    UI.group(T('notify.screen_corner'), corners), UI.group(T('notify.stays_for'), secs), UI.group(T('notify.read_aloud'), aloud),
    UI.actions(el('button', { textContent: T('common.cancel'), onclick: () => d.close() }), UI.btn('📤 ' + T('notify.send'), send, 'primary'))));
  d.addEventListener('close', () => d.remove());
  document.body.append(d); d.showModal(); text.focus();
}
