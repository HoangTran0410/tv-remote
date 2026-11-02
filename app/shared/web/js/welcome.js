// The welcome screen (Automation tab): a video or picture TV Setup shows over the whole TV when it is turned on (Welcome.kt in the
// shared code, WelcomeActivity.kt on the TV). The file goes up like any upload (files.js upload(), kind "welcome": the Jobs card
// shows its progress and its Stop); the choices are saved at once, as the switches next to it are. The limits are Welcome.kt's.
const WELCOME_MAX = 200 * 1048576;
const WELCOME_FILE = /\.(mp4|m4v|webm|mkv|jpe?g|png|webp)$/i;
const WELCOME_SECONDS = [5, 10, 20, 30];
let WELCOME = null;

onTvChange(() => { WELCOME = null; welcomeShow(); });

async function welcomeLoad() {
  const r = await api('/api/welcome', null, true);
  if (!r) return null;
  WELCOME = r; welcomeShow(); return r;
}

async function welcomeDo(change) {
  const r = await api('/api/welcome', change);
  if (r) { WELCOME = r; welcomeShow(); }
  return r;
}

function welcomePick(file, src) {
  if (!file) return;
  if (!WELCOME_FILE.test(file.name)) { toast(T('core.fr_welcome_kind')); return; }
  if (file.size > WELCOME_MAX) { toast(T('core.fr_welcome_toobig', { max: fmtSize(WELCOME_MAX) })); return; }
  upload(file, 'welcome', '/sdcard', () => welcomeLoad(), src);
}

// the minutes and seconds of a video, "0:23"
const welcomeLength = (s) => Math.floor(s / 60) + ':' + String(Math.round(s % 60)).padStart(2, '0');

function welcomeShow() {
  const box = $('#welcomebody'); if (!box) return;
  const w = WELCOME;
  if (!w) { box.replaceChildren(UI.empty(T('welcome.reading'))); return; }
  if (w.setup === 'busy') { box.replaceChildren(UI.empty(T('auto.restarting'))); return; }
  if (w.setup) {                                                       // no TV Setup on the TV, or one from before the welcome screen
    box.replaceChildren(UI.note(T(w.setup === 'old' ? 'welcome.setup_old' : 'welcome.setup_missing', { min: w.min || '0.12.0' })),
      UI.actions(act(T(w.setup === 'old' ? 'auto.update_setup' : 'auto.install_setup'), w.setup === 'old' ? updateSetup : installSetupApk, 'primary')));
    return;
  }
  const pick = (label, cls) => {
    const input = el('input', { type: 'file', accept: 'video/mp4,video/webm,video/x-matroska,image/jpeg,image/png,image/webp,.mp4,.m4v,.webm,.mkv,.jpg,.jpeg,.png,.webp' });
    const l = el('label', { className: 'linkbtn filebtn ' + cls }, input, el('span', { textContent: label }));
    input.addEventListener('change', () => { const f = input.files[0]; input.value = ''; welcomePick(f, l); });
    return l;
  };
  foldSum('welcomecard', w.has && w.on ? T('fold.on') : T('fold.off'), !!(w.has && w.on));
  if (!w.has) {
    box.replaceChildren(UI.empty(T('welcome.none')), UI.note(T('welcome.limits', { max: fmtSize(WELCOME_MAX) })), UI.actions(pick(T('welcome.choose'), 'primary')));
    return;
  }
  const video = w.kind === 'video';
  const thumb = el('img', { className: 'wthumb', alt: T('welcome.preview'), src: '/api/welcome/thumb?v=' + (w.at || 0) });
  thumb.addEventListener('error', () => thumb.hidden = true);
  const on = el('input', { type: 'checkbox', checked: !!w.on, onchange: () => welcomeDo({ do: 'config', on: on.checked }) });
  const whens = UI.chips([['every', T('welcome.every'), T('welcome.every_tip')], ['boot', T('welcome.boot'), T('welcome.boot_tip')]].map(([v, label, title]) =>
    ({ label, title, on: w.when === v, fn: () => welcomeDo({ do: 'config', when: v }) })));
  const secs = [...new Set([...WELCOME_SECONDS, w.seconds])].sort((a, b) => a - b);
  box.replaceChildren(...[
    UI.item({ icon: thumb, name: w.name || '', sub: (video ? T('welcome.sub_video', { size: fmtSize(w.size || 0), time: welcomeLength(w.duration || 0) }) : T('welcome.sub_image', { size: fmtSize(w.size || 0) })) }),
    el('label', { className: 'check' }, on, el('span', { textContent: T('welcome.on') })),
    UI.group(T('welcome.when'), whens),
    video ? null : UI.group(T('welcome.seconds'), UI.chips(secs.map((n) => ({ label: T('welcome.seconds_n', { n }), on: w.seconds === n, fn: () => welcomeDo({ do: 'config', seconds: n }) })))),
    w.overlay === false ? UI.warn(T('welcome.no_overlay')) : null,
    UI.actions(
      UI.btn(T('welcome.show_now'), async () => { if (await welcomeDo({ do: 'show' })) toast(T('welcome.shown')); }, 'primary'),
      pick(T('welcome.change'), ''),
      UI.btn(T('welcome.remove'), async () => { if (await ask(T('welcome.remove_ask'), { ok: T('welcome.remove'), danger: true })) { if (await welcomeDo({ do: 'remove' })) toast(T('welcome.removed')); } }))].filter(Boolean));
}
