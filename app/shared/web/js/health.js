// health tab: a snapshot of the TV (load, memory, Wi-Fi, hardware), refreshed while the tab is open
let healthBusy = false;
const kv = (pairs) => pairs.filter((p) => p && p[1] != null && p[1] !== '').flatMap(([k, v]) => [el('div', { className: 'k', textContent: k }), el('div', { className: 'v', textContent: String(v) })]);
// The network and hardware cards hardly change: redrawn only when their figures do, so an IP or SSID can be selected and copied.
const drawn = new Map();
function redrawIfChanged(sel, pairs) {
  const sig = JSON.stringify(pairs) + window.LANG; if (drawn.get(sel) === sig) return;
  drawn.set(sel, sig); $(sel).replaceChildren(...kv(pairs));
}
// the thermal service's status: from 3 on, Android slows the TV down to cool it
const heat = () => [T('health.heat_normal'), T('health.heat_warmish'), T('health.heat_warm'), T('health.heat_hot'), T('health.heat_very_hot'), T('health.heat_too_hot'), T('health.heat_shutdown')];
function fmtUptime(s) {
  const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
  return [d ? T('health.days_n', { n: d }) : '', d || h ? T('health.hours_n', { n: h }) : '', T('health.minutes_n', { n: m })].filter(Boolean).join(' ');
}
// One line on what to do about the Wi-Fi, the usual cause of stutter and a slow remote.
function wifiTip(w) {
  if (w.rssi < -75) return T('health.wifi_weak');
  if (w.rssi < -67) return T('health.wifi_fair');
  if (w.freq && w.freq < 4000) return T('health.wifi_24ghz');
  return T('health.wifi_good');
}
function signal(rssi) { return rssi >= -55 ? T('health.signal_excellent') : rssi >= -67 ? T('health.signal_good') : rssi >= -75 ? T('health.signal_fair') : T('health.signal_weak'); }
$('#freeram').addEventListener('click', async () => {
  const b = $('#freeram'); b.disabled = true; const r = await api('/api/free', {}); b.disabled = false;
  if (r) { toast(T('health.ram_freed', { freed: r.freed, after: r.after })); loadHealth(); recentsNow(true); loadRamApps(true); }
});
// The task manager: the apps holding memory, what each is doing, and Stop where stopping cannot hurt the TV (the server
// decides which: Procs.keepWhy). dumpsys meminfo is slow on a TV, so at most every 45 s while the tab is open.

// Now: the live figures. History: what the TV Setup app on the TV has written down (drawn by stats.js).
let hmode = 'now';
async function loadHist(days) {
  $('#hhist').replaceChildren(el('div', { className: 'muted waiting', textContent: T('health.reading_from_the_tv') }));
  const d = await api('/api/stats?days=' + (days || 7)); if (d && hmode === 'hist') renderStats($('#hhist'), d, loadHist);
}
document.querySelectorAll('#hmode button').forEach((b) => b.addEventListener('click', () => {
  hmode = b.dataset.m; document.querySelectorAll('#hmode button').forEach((x) => x.classList.toggle('on', x === b));
  put('#hhist', 'hidden', hmode !== 'hist'); put('#hnow', 'hidden', hmode !== 'now'); put('#herr', 'hidden', hmode !== 'hist'); put('#hcaps', 'hidden', hmode !== 'caps');
  put('#hsleep', 'hidden', hmode !== 'sleep'); put('#hpriv', 'hidden', hmode !== 'priv');
  if (hmode === 'priv') privOpen(); else if (hmode === 'hist') { loadHist(); loadErrors(); } else if (hmode === 'sleep') loadSleep(true); else if (hmode === 'caps') loadCaps(); else loadHealth();
}));

// Sleep: how the TV sleeps and wakes (Power.kt), whether Tailscale and TV Setup outlive it, and the screen's last 7 days from
// TV Setup's power.jsonl. The reasons are Android's own words, said in plain ones.
const sleepWhy = () => ({ power_button: T('health.why_power_key'), timeout: T('health.why_timeout'), inattentive: T('health.why_unused'), hdmi: T('health.why_hdmi'),
  application: T('health.why_app'), sleep_button: T('health.why_sleep_key'), device_admin: T('health.why_admin'), force_suspend: T('health.why_forced'), quiescent: T('health.why_quiet') });
const wakeWhy = () => ({ power_button: T('health.why_power_key'), wake_key: T('health.why_remote_key'), application: T('health.why_app'), hdmi: T('health.why_hdmi'), plugged_in: T('health.why_plugged') });
const WATCHED = { 'com.tailscale.ipn': 'Tailscale', 'dev.tvremote.setup': 'TV Setup' };
function deathWhy(w) {
  if (/^tguard:lowSwap|^tguard:lowMem/i.test(w)) return T('health.death_memory_tguard');
  if (/^tguard/.test(w)) return T('health.death_tguard');
  if (w === 'lmk' || /lmk|low ?mem/i.test(w)) return T('health.death_memory');
  if (/install|update|replace/i.test(w)) return T('health.death_updating');
  if (/^(empty|cached)/.test(w)) return T('health.death_cleared');
  if (w === 'died') return T('health.death_stopped');
  return w;
}
const ms2say = (ms) => ms == null ? '' : ms <= 0 || ms >= 2147483647 ? T('health.never') : fmtUptime(ms / 1000);
const slClock = (t) => new Date(t * 1000).toLocaleTimeString(window.LOCALE, { hour: '2-digit', minute: '2-digit' });
const slWhen = (t) => { const d = new Date(t * 1000), today = new Date().toDateString() === d.toDateString();
  return slClock(t) + (today ? '' : ' ' + d.getDate() + '/' + (d.getMonth() + 1)); };
let sleepAt = 0, sleepBusy = false, sleepGen = 0, sleepSeen = '', sleepShown = 7;
function sleepReset() { sleepGen++; sleepAt = 0; sleepSeen = ''; sleepShown = 7; $('#hsleep').replaceChildren(); }
onTvChange(sleepReset);
async function loadSleep(now) {
  if (hmode !== 'sleep' || sleepBusy || (!now && Date.now() - sleepAt < 60000)) return;
  const box = $('#hsleep'), gen = sleepGen;
  if (!now && box.contains(document.activeElement) && document.activeElement !== document.body) return;   // not under a D-pad's focus
  if (!box.childElementCount) box.replaceChildren(el('div', { className: 'muted waiting', textContent: T('health.reading_from_the_tv') }));
  sleepBusy = true; sleepAt = Date.now();
  const [p, rules] = await Promise.all([api('/api/power', null, true), api('/api/rules', null, true)]);
  sleepBusy = false;
  if (gen !== sleepGen || hmode !== 'sleep') return;
  if (!p) {
    if (!sleepSeen) failed(box, () => loadSleep(true));
    return;
  }
  const seen = JSON.stringify([p, rules && rules.awake]).replace(/"now":\d+/, '');
  if (seen === sleepSeen) return;                                      // nothing new: keep what is on screen
  sleepSeen = seen;
  const skew = Math.round(Date.now() / 1000 - p.now), at = (t) => Math.abs(skew) > 120 ? t + skew : t;   // the TV's clock to this one's
  p.events = (p.events || []).map(([t, w]) => [at(t), w]);
  const b = p.boot || {}, t = p.timeouts || {}, ts = p.tailscale || {};
  const lastEv = (w) => { for (let i = p.events.length - 1; i >= 0; i--) if (p.events[i][1] === w) return p.events[i][0]; return null; };
  const tNow = Date.now() / 1000;
  const offAt = lastEv('off') ?? (p.last_sleep?.ago != null ? tNow - p.last_sleep.ago : null), onAt = lastEv('on') ?? (p.last_wake?.ago != null ? tNow - p.last_wake.ago : null);
  const deepPct = b.up && b.deep != null ? Math.round(b.deep * 100 / b.up) : null;
  const sum = UI.card(T('health.sleep_title'), {}, UI.state(p.state === 'Awake' ? T('health.tv_on') : p.state ? T('health.tv_screen_off') : T('health.tv_unknown')),
    el('div', { className: 'kv' }, ...kv([
      offAt != null ? [T('health.last_off'), slWhen(offAt) + (sleepWhy()[p.last_sleep?.why] ? ' · ' + sleepWhy()[p.last_sleep.why] : '')] : null,
      onAt != null ? [T('health.last_on'), slWhen(onAt) + (wakeWhy()[p.last_wake?.why] ? ' · ' + wakeWhy()[p.last_wake.why] : '')] : null,
      [T('health.auto_off'), ms2say(t.attentive > 0 ? t.attentive : t.sleep)],
    ])));
  const more = UI.card(T('health.details_title'), {}, el('div', { className: 'kv' }, ...kv([
      [T('health.up_since'), b.up != null ? T('health.ago', { t: fmtUptime(b.up) }) : ''],
      [T('health.deep_chip'), b.deep != null ? fmtUptime(b.deep) + ' (' + deepPct + '%)' : ''],
      p.deep ? [T('health.deep_count', { by: p.deep.by }), p.deep.count] : null,
      [T('health.boots'), b.count],
      p.hdmi_off != null ? [T('health.hdmi_off'), p.hdmi_off ? T('health.yes') : T('health.no')] : null,
      p.wifi_sleep != null ? [T('health.wifi_sleep'), p.wifi_sleep === 2 ? T('health.kept_on') : T('health.may_turn_off')] : null,
      p.low_power_standby != null ? [T('health.low_power_standby'), p.low_power_standby ? T('health.lps_on') : T('health.off')] : null,
      [T('health.holding_awake'), p.locks.length ? p.locks.map((l) => l.tag).join(', ') : T('health.nothing')],
    ])));
  // TCL powers its chip down whatever an app holds (measured): the switch would only cost power there, so it is not offered
  const keepable = !(p.deep && p.deep.by === 'TCL') || (rules && rules.awake);
  const keep = keepable ? el('label', { className: 'check', title: T('health.keep_awake_tip') },
    el('input', { type: 'checkbox', checked: !!(rules && rules.awake), onchange: async (e) => {
      const box = e.currentTarget, on = box.checked; box.disabled = true;
      const ok = await setupFor('awake') && await api('/api/rules', { do: 'awake', on });
      box.disabled = false; if (!ok) box.checked = !on; else { sleepSeen = ''; toast(on ? T('health.stays_reachable') : T('health.sleeps_deep')); }
    } }), ' ' + T('health.keep_reachable')) : null;
  const deaths = (p.deaths || []).slice(-6).reverse().map((d) => UI.item({ name: (WATCHED[d.pkg] || d.pkg) + ' · ' + slWhen(at(d.t)), sub: deathWhy(d.why) }));
  const net = UI.card(T('health.reachable_title'), {}, el('div', { className: 'kv' }, ...kv([
      ['Tailscale', !ts.installed ? T('health.not_installed') : ts.running ? T('health.running') + (ts.ip ? ' · ' + ts.ip : '') : T('health.not_running')],
      ts.installed ? [T('health.starts_with_tv'), ts.always_on ? T('health.yes') : T('health.no')] : null,
      ['TV Setup', p.setup_running ? T('health.running') : T('health.not_running')],
    ])), keep, deaths.length ? UI.group(T('health.recently_stopped'), el('div', { className: 'list' }, ...deaths)) : null);
  box.replaceChildren(sum, net, sleepDays(p), more);
}
// One strip a day, newest first (7, then 7 more while TV Setup's log goes back further): when the screen was on, and when
// Tailscale's VPN went away.
function sleepDays(p) {
  if (!p.logged) return UI.say(T('health.update_setup_log'));
  const ev = p.events, day0 = new Date(); day0.setHours(0, 0, 0, 0);
  const nowS = Date.now() / 1000;
  const rows = [];
  for (let i = 0; i < sleepShown; i++) {
    const from = day0.getTime() / 1000 - i * 86400, to = from + 86400, end = Math.min(to, nowS);
    let on = null, since = from, secs = 0, wakes = 0;
    const bars = [], ticks = [];
    for (const [t, w] of ev) {
      if (w === 'on' || w === 'off') {
        if (t < from) { on = w === 'on'; continue; }
        if (t >= to) break;
        if (on === null) on = w === 'off';                             // the first word of the window says what came before
        if (w === 'on' && !on) { on = true; since = t; wakes++; }
        else if (w === 'off' && on) { bars.push([since, t]); secs += t - since; on = false; }
      } else if (w === 'vpn_down' && t >= from && t < to) ticks.push(t);
    }
    if (on && end > since) { bars.push([Math.max(since, from), end]); secs += end - Math.max(since, from); }
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'sl-strip'); svg.setAttribute('viewBox', '0 0 1440 10'); svg.setAttribute('preserveAspectRatio', 'none');
    const rect = (x, w, cls) => { const r = document.createElementNS(svg.namespaceURI, 'rect');
      r.setAttribute('x', x); r.setAttribute('width', Math.max(w, 2)); r.setAttribute('height', 10); r.setAttribute('class', cls); svg.append(r); };
    rect(0, 1440, 'sl-bg');
    for (const [a, z] of bars) rect((a - from) / 60, (z - a) / 60, 'sl-on');
    for (const t of ticks) rect((t - from) / 60, 6, 'sl-ts');
    const d = new Date(from * 1000);
    const name = i === 0 ? T('health.today') : i === 1 ? T('health.yesterday') : d.toLocaleDateString(window.LOCALE, { weekday: 'short', day: 'numeric', month: 'numeric' });
    rows.push(el('div', { className: 'sl-day' }, el('div', { className: 'sl-top' }, el('b', { textContent: name }),
      el('small', { textContent: [T('health.day_on', { n: wakes, t: fmtUptime(secs) }), ticks.length ? T('health.day_ts_lost', { n: ticks.length }) : ''].filter(Boolean).join(' · ') })), svg));
  }
  const first = ev.find(([, w]) => w === 'on' || w === 'off'), oldest = day0.getTime() / 1000 - (sleepShown - 1) * 86400;
  const more = first && first[0] < oldest ? el('div', { className: 'actions' }, el('button', { textContent: T('health.show_older'),
    onclick: (e) => { sleepShown += 7; e.target.closest('.card').replaceWith(sleepDays(p)); } })) : null;
  return UI.card(T('health.onoff_title'), {}, ...rows, el('div', { className: 'sl-axis' }, ...['0h', '6h', '12h', '18h', '24h'].map((x) => el('span', { textContent: x }))),
    el('div', { className: 'sl-leg' }, el('span', {}, el('i', { className: 'sl-on' }), T('health.legend_screen_on')), el('span', {}, el('i', { className: 'sl-ts' }), T('health.legend_ts_lost'))), more);
}

// What the TV can play and do (Caps.kt asks the TV's own Android): first in plain words, then the details, and a copy as text
// for a forum or a shop ("will this TV play Dolby Vision?").
let capsFor = '', capsText = '';
async function loadCaps() {
  const box = $('#hcaps');
  if (capsFor === (STATE.host || '') && capsText) return;
  box.replaceChildren(el('div', { className: 'muted waiting', textContent: T('health.asking_tv') }));
  const r = await api('/api/caps'); if (hmode !== 'caps') return;
  if (!r) { box.replaceChildren(); return; }
  if (!r.text.trim()) {
    box.replaceChildren(UI.say(CORE === 'pc'
      ? T('health.caps_old_app') : T('health.caps_no_answer')));
    return;
  }
  capsFor = (STATE.host || ''); capsText = r.text; drawCaps(capsRead(r.text));
}
function drawCaps(c) {
  const vid = (mime) => c.video.find((v) => v.mime === mime), feat = (f) => c.features.includes(f);
  const biggest = c.modes.map((m) => m.split('@')[0].split('x').map(Number)).reduce((a, b) => (b[0] * b[1] > a[0] * a[1] ? b : a), [0, 0]);
  const fourK = (v) => v && v.hw && Math.max(v.w, v.h) >= 3840;
  const hdrs = { dv: 'Dolby Vision', hdr10: 'HDR10', 'hdr10+': 'HDR10+', hlg: 'HLG' };
  const pass = [...new Set(c.outputs.filter((o) => /hdmi|arc|earc|optical/.test(o.dev)).flatMap((o) => o.enc).filter((e) => e !== 'PCM' && e !== 'bitstream'))];
  const yes = (ok, text) => el('li', { className: ok ? 'yes' : 'no', textContent: (ok ? '✓ ' : '✗ ') + text });
  const plain = el('ul', { className: 'capsplain' },
    biggest[0] ? yes(true, T('health.caps_screen_up_to', { size: sizeName(biggest[0], biggest[1]) || biggest.join('x') })) : null,
    yes(fourK(vid('video/x-vnd.on2.vp9')) || fourK(vid('video/av01')), T('health.caps_youtube_4k')),
    yes(fourK(vid('video/hevc')), T('health.caps_film_4k')),
    yes(!!(vid('video/av01') || {}).hw, T('health.caps_av1')),
    yes(c.hdr.length > 0, c.hdr.length ? 'HDR: ' + c.hdr.map((h) => hdrs[h] || h).join(', ') : 'HDR'),
    yes(c.video.some((v) => v.hw && v.secure), T('health.caps_secure')),
    yes(pass.length > 0, pass.length ? T('health.caps_passthrough', { list: pass.join(', ') }) : T('health.caps_passthrough_any')),
    c.sdk ? yes(c.sdk >= 30, T('health.caps_listen')) : null,
    c.sdk ? yes(c.sdk >= 28, T('health.caps_dns')) : null,
    yes(feat('android.software.picture_in_picture'), T('health.caps_pip')),
    yes(feat('android.hardware.hdmi.cec'), T('health.caps_cec')),
    yes(feat('android.hardware.usb.host'), T('health.caps_usb')),
    yes(feat('android.hardware.bluetooth'), T('health.caps_bluetooth')));
  const vrow = (v) => [v.name, [v.hw ? T('health.decode_hw') : T('health.decode_sw'), sizeName(v.w, v.h), ...v.flags.filter((f) => f !== 'soft' && f !== 'tunnel').map((f) => ({ '10bit': '10-bit', hdr10: 'HDR10', 'hdr10+': 'HDR10+', secure: T('health.decode_secure') }[f] || f))].filter(Boolean).join(' · ')];
  const card = (title, ...kids) => UI.card(title, {}, ...kids);
  $('#hcaps').replaceChildren(
    UI.card(T('health.caps_title'), { tools: [el('button', { className: 'small', onclick: () => copyText(capsSummary(c)) }, el('span', { className: 'ico', textContent: '📋' }), el('span', { className: 'lbl', textContent: ' ' + T('tv.copy') }))] }, plain),
    card(T('health.caps_video_title'), el('div', { className: 'kv' }, ...kv(c.video.map(vrow)))),
    card(T('health.caps_audio_title'), el('div', { className: 'kv' }, ...kv([
      [T('health.caps_decodes'), c.audio.map((a) => a.name).join(', ')],
      ...c.outputs.filter((o) => o.enc.length).map((o) => [{ hdmi: 'HDMI', arc: 'HDMI ARC', earc: 'HDMI eARC', optical: T('health.out_optical'), speaker: T('health.out_speaker'), bluetooth: 'Bluetooth' }[o.dev] || o.dev, o.enc.join(', ') || '—'])]))),
    c.modes.length ? card(T('health.caps_modes_title'), el('p', { className: 'note', textContent: c.modes.map((m) => m.replace(/\.0$/, '') + ' Hz').join(' · ') })) : null);
}
// the copy: the same plain lines, then the details, so it reads well pasted anywhere
function capsSummary(c) {
  const lines = [...$('#hcaps .capsplain').children].map((li) => li.textContent);
  return [T('health.caps_summary_title') + (c.release ? ' · Android ' + c.release : ''), ...lines, '',
    ...c.video.map((v) => v.name + ': ' + (v.hw ? 'hw' : 'sw') + ' ' + v.w + 'x' + v.h + (v.flags.length ? ' ' + v.flags.join(',') : '')),
    'Audio: ' + c.audio.map((a) => a.name).join(', '), ...c.outputs.map((o) => 'Out ' + o.dev + ': ' + o.enc.join(', ')),
    'HDR: ' + (c.hdr.join(', ') || '-'), 'Modes: ' + c.modes.join(', ')].join('\n');
}

// 🗂️ all settings, under the Setup tab: read the first time it is opened (again after a failed read), searched as typed
let setRows = [], setBusy = false, setGen = 0;                         // setGen: a read from the old TV is dropped
onTvChange(() => { setGen++; setBusy = false; setRows = []; $('#setlistbody').replaceChildren(); });
let setFilter = 'all';                                               // all · edit (has a control) · view (locked or nothing to set)
const setOpen = new Set();                                            // groups opened by hand: kept open as the list redraws
async function loadSettings() {
  if (setRows.length || setBusy || ADB.state !== 'ready') return;
  setBusy = true;
  $('#setlistbody').replaceChildren(el('div', { className: 'muted', textContent: T('health.reading_from_the_tv') }));
  const gen = setGen, r = await api('/api/settings');
  if (gen !== setGen) return;
  setBusy = false;
  if (!r) { $('#setlistbody').replaceChildren(); return; }
  setRows = settingsRead(r.text); drawSettings();
}
$('#setlist').addEventListener('toggle', (e) => { if (e.target.open) loadSettings(); });
function drawSettings() {
  const q = $('#setlistq').value.trim().toLowerCase();
  const all = setRows.map((s) => { const e = settingEdit(s); return { s, i: settingInfo(s), edit: !e.locked && !!e.kind, locked: !!e.locked }; })
    .filter(({ s, i }) => !q || (i.title + ' ' + s.name + '=' + s.value + ' ' + i.value).toLowerCase().includes(q));
  const counts = { all: all.length, edit: all.filter((r) => r.edit).length }; counts.view = counts.all - counts.edit;
  document.querySelectorAll('#setfilter button').forEach((b) => { const f = b.dataset.f; b.classList.toggle('on', f === setFilter); b.dataset.n = counts[f]; });
  const rows = all.filter((r) => setFilter === 'all' || (setFilter === 'edit') === r.edit);
  const row = ({ s, i, locked }) => el('div', { className: 'setrow', title: s.table + ' ' + s.name + '=' + s.value, tabIndex: 0,   // tabIndex: keyboard and D-pad reach a cut-off value
    onkeydown: (e) => { if (e.key === 'Enter' && e.target === e.currentTarget) e.currentTarget.click(); },
    onclick: (e) => { e.currentTarget.classList.toggle('full'); if (locked) toast(T('health.locked_toast')); } },   // a long value opens in full on a tap
    el('div', { className: 'setname' }, el('span', { textContent: i.title }), el('small', { className: 'mono', textContent: s.name })),
    setControl(s, i));
  const named = (div, pkgs) => pkgs.forEach((p) => onMeta(p, (m) => {   // "com.google.android.apps.tv.dreamx" -> the app's own name
    if (m && m.label && m.label !== p.slice(p.lastIndexOf('.') + 1)) { div.textContent = div.textContent.split(', ').map((x) => x === p ? m.label : x).join(', '); div.classList.toggle('long', div.textContent.length > 24); }
  }));
  const parts = [];
  for (const g of Object.keys(SET_GROUPS)) {
    const mine = rows.filter((r) => r.i.group === g); if (!mine.length) continue;
    // every group folded, so the list fits and the search box stays in reach; a search opens the groups it finds something in
    const made = g !== 'other' ? mine.sort((a, b) => a.i.order - b.i.order).map((r) => { const d = row(r); if (r.i.pkgs) named(d.lastChild, r.i.pkgs); return d; })
      : mine.slice(0, 400).map(row);                                 // Android's hundreds of internal keys
    const box = el('details', { className: 'setgroupbox', open: !!q || setOpen.has(g) }, el('summary', {}, el('span', { textContent: mine[0].i.groupName }), el('span', { className: 'n', textContent: String(mine.length) })), ...made);
    box.addEventListener('toggle', () => { if (!q) { if (box.open) setOpen.add(g); else setOpen.delete(g); } });
    parts.push(box);
  }
  $('#setlistbody').replaceChildren(...parts);
}
// The value, or what changes it: a toggle, a choice, ✎ for a name or a number; 🔒 where a change could cut the TV off
function setControl(s, i) {
  const e = settingEdit(s), shown = el('div', { className: 'setval' + (i.known ? '' : ' mono') + (i.value.length > 24 ? ' long' : ''), textContent: i.value });
  const stop = (n) => { n.addEventListener('click', (ev) => ev.stopPropagation()); return n; };
  const back = !e.locked && e.kind !== 'turnon' && s.was !== undefined && s.was !== s.value && s.was !== 'null'
    ? stop(el('button', { type: 'button', className: 'small', title: T('health.put_back') + ': ' + s.was, textContent: '↺', onclick: () => setSetting(s, s.was) })) : null;
  let ctl = null;
  if (e.locked) { shown.textContent = '🔒 ' + i.value; shown.title = T('health.locked_tip'); }
  else if (e.kind === 'turnon') {                                    // on only: off could cut the remotes off
    shown.textContent = '🔒 ' + i.value; shown.title = T('health.turnon_tip');
    ctl = el('button', { type: 'button', className: 'small primary', textContent: T('health.turn_on'), onclick: async () => {
      if (await ask(T('health.turnon_ask'), { ok: T('health.turn_on') })) setSetting(s, e.on);
    } });
  } else if (e.kind === 'step') {                                    // a volume: type the level, or a step each way; the TV says its range
    const n = Number(s.value) || 0, go = (v) => { v = Math.min(e.max, Math.max(e.min, Math.round(v))); if (v !== n) setSetting(s, String(v)); else box.value = n; };
    const box = el('input', { type: 'number', className: 'setnum', inputMode: 'numeric', min: e.min, max: e.max, value: n, ariaLabel: i.title + ' ' + e.min + '–' + e.max,
      onchange: (ev) => go(Number(ev.target.value)), onkeydown: (ev) => { if (ev.key === 'Enter') ev.target.blur(); } });
    ctl = el('div', { className: 'setstep' }, el('button', { type: 'button', className: 'small', title: T('health.lower'), textContent: '−', disabled: n <= e.min, onclick: () => go(n - 1) }),
      box, el('span', { className: 'setmax', title: e.min + '–' + e.max, textContent: '/ ' + e.max }),
      el('button', { type: 'button', className: 'small', title: T('health.higher'), textContent: '+', disabled: n >= e.max, onclick: () => go(n + 1) }));
  } else if (e.kind === 'range') {                                   // brightness: a slider, set once it is let go
    const out = el('span', { className: 'setval', textContent: settingWord(s, s.value) });
    const r = el('input', { type: 'range', className: 'setrange', min: e.min, max: e.max, value: Number(s.value) || 0, ariaLabel: i.title,
      oninput: (ev) => { out.textContent = settingWord(s, ev.target.value); }, onchange: (ev) => setSetting(s, ev.target.value) });
    ctl = el('div', { className: 'setstep' }, r, out);
  } else if (e.kind === 'bool') {
    const on = s.value !== e.off && s.value !== 'null';
    ctl = el('button', { type: 'button', className: 'small' + (on ? ' on' : ''), textContent: settingWord(s, on ? e.on : e.off), onclick: () => setSetting(s, on ? e.off : e.on) });
  } else if (e.kind === 'pick') {
    const vals = e.values.some((v) => Number(v) === Number(s.value)) ? e.values : [s.value, ...e.values];
    ctl = el('select', { className: 'setpick', onchange: (ev) => setSetting(s, ev.target.value) },
      ...vals.map((v) => el('option', { value: v, textContent: settingWord(s, v), selected: Number(v) === Number(s.value) })));
  } else if (e.kind === 'text') {
    ctl = el('button', { type: 'button', className: 'small', title: T('health.change'), textContent: (s.value === 'null' ? '—' : i.value) + ' ✎', onclick: async () => {
      const v = await ask(e.tech ? T('health.new_value_tech') : T('health.new_value'), { value: s.value === 'null' ? '' : s.value, ok: T('health.change') });
      if (v !== null && v.trim() !== s.value) setSetting(s, v.trim());
    } });
  }
  if (!ctl) return back ? el('div', { className: 'setctl' }, back, shown) : shown;
  if (e.kind === 'turnon') return el('div', { className: 'setctl' }, shown, stop(ctl));
  return el('div', { className: 'setctl' }, ...(back ? [back] : []), stop(ctl));
}
async function setSetting(s, value) {
  if (!/^[A-Za-z0-9 ._:+-]{0,100}$/.test(value)) { toast(T('health.plain_chars')); return drawSettings(); }
  const r = await api('/api/settings', { table: s.table, name: s.name, value });
  if (!r || !/\bok\b/.test(r.text || '')) { if (r) toast(T('common.error_msg', { msg: friendly((r.text || '').trim()) })); return drawSettings(); }
  if (s.was === undefined) s.was = s.value;
  s.value = value; drawSettings(); toast(T('health.changed'));
}
$('#setlistq').addEventListener('input', drawSettings);
$('#setfilter').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) { setFilter = b.dataset.f; drawSettings(); } });
$('#setlistf').addEventListener('submit', (e) => e.preventDefault());
$('#setlistcopy').addEventListener('click', () => {
  const q = $('#setlistq').value.trim().toLowerCase();
  copyText(setRows.filter((s) => !q || (s.name + '=' + s.value).toLowerCase().includes(q)).map((s) => s.table + ' ' + s.name + '=' + s.value).join('\n'));
});

// Errors: Android notes every app that stops or stops answering in its "dropbox" (kept about 3 days); the core sends those notes
// cut to the lines that matter (CRASHES in HealthApi.kt), and this groups them per app, newest first.
function parseCrashes(text) {
  const out = []; let e = null, sep = false;
  for (const line of text.split('\n')) {
    if (line.startsWith('=====')) { sep = true; continue; }
    const h = sep && line.match(/^(\d{4}-\d\d-\d\d \d\d:\d\d):\d\d (\S+)/); sep = false;
    if (h) { e = { at: h[1], kind: /anr/.test(h[2]) ? 'anr' : /native/.test(h[2]) ? 'native' : 'crash', sys: h[2].startsWith('system_server'), proc: '', why: '', lines: [] }; out.push(e); continue; }
    if (!e) continue;
    if (line.startsWith('Process: ')) { e.proc = line.slice(9).trim(); continue; }
    if (line.startsWith('Subject: ') || (!line.startsWith('\tat ') && !e.why) || line.startsWith('Abort message')) e.why = line.replace(/^Subject: /, '').trim();
    if (e.lines.length < 20) e.lines.push(line.replace(/^\t/, '    '));
  }
  return out;
}
const crashAt = (at) => at.slice(8, 10) + '/' + at.slice(5, 7) + ' ' + at.slice(11);
const kindWord = () => ({ crash: T('health.kind_stopped'), anr: T('health.kind_froze'), native: T('health.kind_stopped') });
const PLAIN = () => [                                                 // the usual reasons in words; the exact note stays below
  [/^Input dispatching timed out/, T('health.why_input')], [/executing service/, T('health.why_service')],
  [/^Broadcast of Intent/, T('health.why_broadcast')], [/ContentProvider not responding/, T('health.why_provider')],
  [/^signal 11 /, T('health.why_sigsegv')], [/^signal 6 /, T('health.why_sigabrt')], [/^(java\.lang\.)?OutOfMemoryError/, T('health.why_oom')],
];
const plain = (why) => (PLAIN().find(([re]) => re.test(why)) || [])[1] || why;
async function loadErrors() {
  const box = $('#herr');
  box.replaceChildren(el('div', { className: 'muted waiting', textContent: T('health.reading_from_the_tv') }));
  const r = await api('/api/crashes'); if (!r || hmode !== 'hist') return;   // under History's charts: what went wrong is history too
  const groups = new Map();
  for (const e of parseCrashes(r.text)) {
    const key = e.sys ? 'android' : (e.proc || '?').split(':')[0];
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(e);
  }
  const list = [...groups].map(([pkg, es]) => ({ pkg, es: es.sort((a, b) => b.at.localeCompare(a.at)) })).sort((a, b) => b.es[0].at.localeCompare(a.es[0].at));
  const reload = el('button', { type: 'button', className: 'small', textContent: T('health.reload_btn'), onclick: loadErrors });
  const card = UI.card(T('health.errors_title'), { tools: [reload] });
  if (!list.length) card.append(el('p', { className: 'note', textContent: T('health.errors_none') }));
  for (const { pkg, es } of list) {
    const n = { crash: 0, anr: 0 }; es.forEach((e) => { n[e.kind === 'anr' ? 'anr' : 'crash']++; });
    const name = el('b', { textContent: pkg === 'android' ? T('health.android_system') : appName(pkg) });
    if (pkg !== 'android') onMeta(pkg, (m) => { if (m.label) name.textContent = m.label; });
    const icon = pkg === 'android' ? el('div', { className: 'appicon placeholder', textContent: '⚙️' }) : appIcon(pkg);
    if (pkg !== 'android') onMeta(pkg, (m) => icon.fill(m));
    const counts = [n.crash ? T('health.stops_n', { n: n.crash }) : '', n.anr ? T('health.freezes_n', { n: n.anr }) : ''].filter(Boolean).join(' · ');
    const d = el('details', { className: 'crash' }, el('summary', {}, icon,
      el('div', { className: 't' }, name, el('small', { textContent: T('health.counts_last', { counts, at: crashAt(es[0].at) }) })),
      el('span', { className: 'n', textContent: String(es.length) })));
    const whys = new Map();                                            // the same failure again and again: shown once, with how often
    for (const e of es) { const k = e.kind + e.why; if (!whys.has(k)) whys.set(k, { e, n: 0 }); whys.get(k).n++; }
    for (const { e, n: times } of [...whys.values()].slice(0, 5)) {
      d.append(el('div', { className: 'why' }, el('span', { textContent: kindWord()[e.kind] }), ' ', el('b', { textContent: plain(e.why) || '?' }),
        el('small', {}, ' · ', el('span', { textContent: T('health.times_last', { n: times, at: crashAt(e.at) }) }))));
      const text = (e.proc ? 'Process: ' + e.proc + '\n' : '') + e.lines.join('\n');
      d.append(el('pre', { className: 'term', textContent: text }));
    }
    d.append(el('button', { type: 'button', className: 'small crashcopy', textContent: T('health.copy_to_ask'),
      onclick: () => copyText([...whys.values()].map(({ e, n: t }) => `[${e.at}] ${pkg} ${e.kind} ×${t}\n${e.lines.join('\n')}`).join('\n\n')) }));
    card.append(d);
  }
  box.replaceChildren(card);
}
// Now: four views (This TV, CPU, RAM, Network). /api/health every 10 s (the facts, temperature, apps by CPU in that view);
// /api/health/live every 2 s for the gauges and the 60 s charts, only while Now is on screen: nothing runs once it is left.
let hsub = 'tv', hlast = null, hliveT = 0, hliveBusy = false, hramAt = 0;
const HLIVE_MS = 2000, HKEEP = 31;                                    // 31 readings: the last 60 s
let hhist = [];                                                       // [{t, cpu, mem, rx, tx}], oldest first
onTvChange(() => { hhist = []; hlast = null; hramAt = 0; $('#hramapps').replaceChildren(); $('#hcpuapps').replaceChildren(); $('#hnetapps').replaceChildren(); });
document.querySelectorAll('#hsub button').forEach((b) => b.addEventListener('click', () => {
  hsub = b.dataset.v;
  document.querySelectorAll('#hsub button').forEach((x) => { x.classList.toggle('on', x === b); x.setAttribute('aria-selected', x === b); });
  document.querySelectorAll('#hnow .hpane').forEach((p) => { p.hidden = p.dataset.v !== hsub; });
  if (hlast) drawLive(hlast);
  if (hsub === 'ram') loadRamApps(true);
  if (hsub === 'cpu' || hsub === 'net') loadHealth();   // apps by CPU / the Wi-Fi card at once
  hliveGo(true);
}));
const hliveOn = () => currentTab === 'health' && hmode === 'now' && !document.hidden && !stopped && ADB.state === 'ready';
// one reading, then the next 2 s after it came back (never two at once on a slow link); stops by itself when Now is left
function hliveGo(now) {
  clearTimeout(hliveT);
  if (!hliveOn()) { hliveT = 0; return; }                          // 0: loadHealth() starts it again
  hliveT = setTimeout(async () => {
    if (hliveBusy) return;                                            // the reading in flight goes on from here
    if (!hliveOn()) { hliveT = 0; return; }
    hliveBusy = true;
    const r = await api('/api/health/live' + (hsub === 'net' ? '?net=1' : ''), null, true);
    hliveBusy = false;
    if (r && hliveOn()) {
      const m = r.mem || {};
      hhist.push({ t: Date.now(), cpu: r.cpu, mem: m.total ? memUsed(m) * 100 / m.total : null, rx: r.net && r.net.rx, tx: r.net && r.net.tx });
      while (hhist.length > HKEEP || (hhist.length && Date.now() - hhist[0].t > 62000)) hhist.shift();
      hlast = r; drawLive(r);
    }
    hliveGo();
  }, now ? 0 : HLIVE_MS);
}
document.addEventListener('visibilitychange', () => hliveGo(true));

// a ring: the share used, its words under it; amber from 75 %, red from 90 % (and the words say it too: the number)
function ring(label, pct, sub) {
  const ns = 'http://www.w3.org/2000/svg', svg = document.createElementNS(ns, 'svg'), c = (cls) => {
    const k = document.createElementNS(ns, 'circle'); k.setAttribute('cx', 18); k.setAttribute('cy', 18); k.setAttribute('r', 15.9); k.setAttribute('class', cls); svg.append(k); return k; };
  svg.setAttribute('viewBox', '0 0 36 36'); svg.setAttribute('aria-hidden', 'true');
  c('rg-bg'); const v = c('rg-v'), p = pct == null ? 0 : Math.max(0, Math.min(100, pct));
  v.setAttribute('stroke-dasharray', p.toFixed(1) + ' 100');
  return el('div', { className: 'ring' + (p >= 90 ? ' bad' : p >= 75 ? ' warn' : '') },
    el('div', { className: 'rg' }, svg, el('b', { textContent: pct == null ? '—' : Math.round(pct) + '%' })),
    el('b', { className: 'rg-l', textContent: label }), el('small', { textContent: sub || '' }));
}
// the last 60 s as lines (2 at most, one scale, no second axis); hovering a point shows its values in the row above
function liveChart(series, fmt, max0) {
  const W = 300, H = 60, n = HKEEP - 1, t1 = Date.now(), at = (t) => W - (t1 - t) / 2000 * (W / n);
  const max = Math.max(max0, ...hhist.flatMap((h) => series.map((s) => h[s.k] || 0))) * 1.1;
  const y = (v) => (H - 2 - (v / max) * (H - 4)).toFixed(1);
  const paths = series.map((s) => { const pts = hhist.filter((h) => h[s.k] != null);
    return pts.length < 2 ? '' : '<path class="' + s.cls + '" d="' + pts.map((h, i) => (i ? 'L' : 'M') + at(h.t).toFixed(1) + ' ' + y(h[s.k])).join('') + '"/>'; }).join('');
  const box = el('div', { className: 'lc', innerHTML: '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none" aria-hidden="true"><line class="lc-base" x1="0" x2="' + W + '" y1="' + (H - 1) + '" y2="' + (H - 1) + '"/>' + paths + '<line class="lc-cur" x1="0" x2="0" y1="0" y2="' + H + '" hidden/></svg>' });
  const head = el('div', { className: 'lc-top' }), now = hhist[hhist.length - 1] || {};
  const say = (h, ago) => head.replaceChildren(...series.map((s) => el('span', { className: 'lc-v' }, el('i', { className: s.cls }), s.label + ' ', el('b', { textContent: h[s.k] == null ? '—' : fmt(h[s.k]) }))),
    ...(ago ? [el('small', { textContent: T('health.secs_ago', { n: ago }) })] : []));
  say(now);
  const svg = box.firstChild, cur = svg.querySelector('.lc-cur');
  const show = (e) => { if (!hhist.length) return; const b = svg.getBoundingClientRect(), x = (e.clientX - b.left) * W / b.width;
    const h = hhist.reduce((a, q) => Math.abs(at(q.t) - x) < Math.abs(at(a.t) - x) ? q : a);
    cur.setAttribute('x1', at(h.t)); cur.setAttribute('x2', at(h.t)); cur.hidden = false; say(h, Math.round((t1 - h.t) / 1000)); };
  svg.addEventListener('pointermove', show); svg.addEventListener('pointerdown', show);
  svg.addEventListener('pointerleave', () => { cur.hidden = true; say(now); });
  return el('div', { className: 'lchart' }, head, box, el('div', { className: 'sl-axis' }, el('span', { textContent: T('health.secs_ago', { n: 60 }) }), el('span', { textContent: T('health.now_word') })));
}
const rate = (v) => fmtSize(v || 0) + '/s';
// RAM in use: total - MemAvailable, the figure the full-memory notice and auto free RAM go by (LiveLoad.mem)
const memUsed = (m) => (m.total || 0) - (m.avail || 0);
// a bar split in parts [[share, cls, words]], then its legend
function split(parts) {
  const bar = el('div', { className: 'sbar' }, ...parts.filter((p) => p[0] > 0).map(([v, cls]) => { const i = el('i', { className: cls }); i.style.width = v + '%'; return i; }));
  return [bar, el('div', { className: 'sl-leg' }, ...parts.map(([, cls, words]) => el('span', {}, el('i', { className: cls }), words)))];
}
const big = (v, unit, words) => el('div', { className: 'bignum' }, el('b', { textContent: v == null ? '—' : v }), unit ? el('span', { textContent: unit }) : null, words ? el('small', { textContent: words }) : null);
// a row of a list of apps: icon, name, what it uses, and a bar of its share
function urow(pkg, name, value, sub, share, acts) {
  const icon = pkg === '-' || pkg === 'android' ? el('div', { className: 'appicon placeholder', textContent: '⚙️' }) : appIcon(pkg), title = el('span', { textContent: name });
  if (pkg !== '-' && pkg !== 'android') onMeta(pkg, (m) => { icon.fill(m); if (m.label) put(title, 'textContent', m.label); });
  const bar = el('div', { className: 'bar ubar' }, el('i')); bar.firstChild.style.width = Math.max(1, Math.min(100, share)) + '%';
  return el('div', { className: 'item urow' }, icon, el('div', { className: 'name' }, el('div', { className: 'nameline' }, title, el('b', { textContent: value })), sub ? el('small', { textContent: sub }) : null, bar), acts || null);
}
function drawLive(r) {
  const m = r.mem || {};
  const memPct = m.total ? memUsed(m) * 100 / m.total : null;
  if (hsub === 'tv' && hfull) {
    const f = hfull, disk = f.disk_total ? (f.disk_total - f.disk_free) * 100 / f.disk_total : null;
    $('#hrings').replaceChildren(
      ring('CPU', r.cpu, [f.chip, f.cores ? T('health.cores_n', { n: f.cores }) : ''].filter(Boolean).join(' · ')),
      ring('RAM', memPct, m.total ? fmtSize(memUsed(m)) + ' / ' + fmtSize(m.total) : ''),
      ring(T('health.ring_storage'), disk, f.disk_total ? fmtSize(f.disk_total - f.disk_free) + ' / ' + fmtSize(f.disk_total) : ''));
    $('#hnetchart').replaceChildren(liveChart([{ k: 'rx', cls: 'lc-a', label: '↓' }, { k: 'tx', cls: 'lc-b', label: '↑' }], rate, 10240));
  } else if (hsub === 'cpu') {
    const cores = (r.cores || []).map((v, i) => el('div', { className: 'core' + (v == null ? ' off' : '') }, el('span', { textContent: T('health.core_n', { n: i + 1 }) }),
      el('div', { className: 'bar' }, (() => { const b = el('i'); b.style.width = (v || 0) + '%'; return b; })()), el('b', { textContent: v == null ? '—' : v + '%' })));
    const mhz = r.mhz || [], lo = Math.min(...mhz), hi = Math.max(...mhz), ghz = (v) => (v / 1000).toFixed(1);
    $('#hcpu').replaceChildren(big(r.cpu, '%', hfull && hfull.cores ? T('health.cores_n', { n: hfull.cores }) : ''),
      ...split([[r.apps || 0, 'p-a', T('health.cpu_part_apps') + ' ' + (r.apps ?? '—') + '%'], [r.system || 0, 'p-b', T('health.cpu_part_system') + ' ' + (r.system ?? '—') + '%'], [r.io || 0, 'p-c', T('health.cpu_part_io') + ' ' + (r.io ?? '—') + '%']]),
      liveChart([{ k: 'cpu', cls: 'lc-a', label: 'CPU' }], (v) => Math.round(v) + '%', 100),
      cores.length ? el('div', { className: 'cores' }, ...cores) : null,
      el('div', { className: 'kv' }, ...kv([mhz.length ? [T('health.clock'), (lo === hi ? ghz(hi) : ghz(lo) + '–' + ghz(hi)) + ' GHz'] : null, hfull && hfull.gpu != null ? ['GPU', hfull.gpu + '%'] : null, hfull ? ['Chip', hfull.chip] : null])));
  } else if (hsub === 'ram') {
    const free = m.free || 0, used = memUsed(m), cache = Math.max(0, (m.avail || 0) - free), pc = (v) => m.total ? v * 100 / m.total : 0;
    $('#hram').replaceChildren(big(memPct == null ? null : Math.round(memPct), '%', m.total ? fmtSize(used) + ' / ' + fmtSize(m.total) : ''),
      ...split([[pc(used), 'p-a', T('health.ram_in_use') + ' ' + fmtSize(used)], [pc(cache), 'p-b', T('health.ram_cache') + ' ' + fmtSize(cache)], [pc(free), 'p-f', T('health.ram_free') + ' ' + fmtSize(free)]]),
      liveChart([{ k: 'mem', cls: 'lc-a', label: 'RAM' }], (v) => Math.round(v) + '%', 100),
      el('div', { className: 'kv' }, ...kv([m.swap_total ? [T('health.swap'), fmtSize(m.swap_total - m.swap_free) + ' / ' + fmtSize(m.swap_total)] : null])));
  } else if (hsub === 'net') {
    const n = r.net || {};
    $('#hnetlive').replaceChildren(
      el('div', { className: 'updown' }, big(n.rx == null ? null : fmtSize(n.rx), '/s', '↓ ' + T('health.k_down')), big(n.tx == null ? null : fmtSize(n.tx), '/s', '↑ ' + T('health.k_up'))),
      liveChart([{ k: 'rx', cls: 'lc-a', label: '↓' }, { k: 'tx', cls: 'lc-b', label: '↑' }], rate, 10240),
      el('p', { className: 'note', textContent: [n.rx_total != null ? T('health.since_boot', { rx: fmtSize(n.rx_total), tx: fmtSize(n.tx_total) }) : '', r.conns != null ? T('health.conns_n', { n: r.conns }) : ''].filter(Boolean).join(' · ') }));
    if (Array.isArray(r.apps)) {                                     // a reading from another view says only how many (FPT: TypeError on switching to Network)
      const top = Math.max(1, ...r.apps.map((a) => a.rx + a.tx));
      $('#hnetapps').replaceChildren(UI.list(r.apps.map((a) => urow(a.pkg, a.pkg === 'android' ? T('health.android_system') : a.label || a.pkg, fmtSize(a.rx + a.tx),
        '↓ ' + fmtSize(a.rx) + ' ↑ ' + fmtSize(a.tx) + (a.conns ? ' · ' + T('health.conns_n', { n: a.conns }) : ''), (a.rx + a.tx) * 100 / top)), T('health.net_apps_none')),
        UI.note(T('health.net_apps_note')));
    }
  }
}
// RAM → the apps holding memory, with Stop where it cannot hurt the TV (procRow, the Apps tab's): dumpsys meminfo is slow on a
// TV, so at most every 45 s while this view is open, and again after a Stop
async function loadRamApps(now) {
  if (!now && Date.now() - hramAt < 45000) return;
  hramAt = Date.now();
  const box = $('#hramapps');
  if (!box.childElementCount) box.replaceChildren(el('p', { className: 'muted', textContent: T('health.reading_from_the_tv') }));
  const gen = tvGen, r = await api('/api/procs', null, true);
  if (!r && gen === tvGen && hsub === 'ram' && box.querySelector(':scope > p.muted')) failed(box, () => loadRamApps(true));   // nothing shown yet
  if (!r || hsub !== 'ram') return;
  const top = Math.max(1, ...r.apps.map((a) => a.mb));
  box.replaceChildren(UI.list(r.apps.slice(0, 15).map((a) => {
    const row = urow(a.pkg, a.pkg === 'android' ? T('health.android_system') : a.label, a.mb + ' MB', procState()[a.state] || a.state, a.mb * 100 / top,
      a.keep ? null : el('div', { className: 'acts' }, procStop(a)));
    if (a.keep) row.querySelector('.nameline b').prepend(el('span', { className: 'keep', title: T('health.must_keep') + ': ' + (procKeep()[a.keep] || ''), 'aria-label': T('health.must_keep') }, UI.icon('lock')));
    return row;
  }), T('health.cpu_idle')));
}
$('#hramapps').addEventListener('click', (e) => { if (e.target.closest('.acts button')) setTimeout(() => loadRamApps(true), 1500); });

let hfull = null;
// Apps another package locked ("App isn't available ... managed by Google TV"): one filtered dumpsys on the TV, at most once
// a minute while Health → Now is open; the card exists only while some app is locked.
let suspAt = 0, suspBusy = false;
const GOOGLE_TV = 'com.google.android.apps.tv.launcherx';
let suspBy = new Map();                                              // locked app → its suspenders, from the last read (the Apps tab's ⋯)
onTvChange(() => { suspAt = 0; suspBy = new Map(); for (const c of ['#hsusp', '#appssusp']) put(c, 'hidden', true); $('#hsuspl').replaceChildren(); $('#appssuspl').replaceChildren(); });
// Lifts what [b] locked: asks first unless it is the shell's own (nothing is reset then), then draws both cards again
async function suspUnlock(b, list, btn) {
  const by = b.pkg === GOOGLE_TV ? 'Google TV' : b.name || b.pkg;
  if (!b.shell && !await ask(T('health.susp_ask', { apps: list, by }), { ok: T('health.susp_fix'), danger: true })) return;
  if (btn) btn.disabled = true;
  const r2 = await api('/api/health/suspended', { by: b.pkg });
  if (!r2) { if (btn) btn.disabled = false; return; }
  drawSusp(r2);
  toast(r2.apps.some((a) => a.by.some((x) => x.pkg === b.pkg)) ? T('health.susp_still', { by }) : T('health.susp_ok'));
}
// Health's card and the Apps tab's, the same list
function drawSusp(r) {
  suspBy = new Map(r.apps.map((a) => [a.pkg, a.by]));
  const groups = new Map();                                           // suspender → its apps
  for (const a of r.apps) for (const b of (a.by.length ? a.by : [{ pkg: '', name: '', shell: true }])) {
    if (!groups.has(b.pkg)) groups.set(b.pkg, { b, apps: [] });
    groups.get(b.pkg).apps.push(a.name);
  }
  for (const [card, box] of [['#hsusp', '#hsuspl'], ['#appssusp', '#appssuspl']]) {
    put(card, 'hidden', !groups.size);
    $(box).replaceChildren(...[...groups.values()].map(({ b, apps }) => {
      const by = b.pkg === GOOGLE_TV ? 'Google TV' : b.name || b.pkg, list = apps.join(', ');
      const fix = el('button', { type: 'button', className: 'small primary', textContent: T('health.susp_fix'), disabled: !b.pkg });
      fix.onclick = () => suspUnlock(b, list, fix);
      return el('div', { className: 'item' }, el('div', { className: 'name' }, el('div', { className: 'nameline' }, el('b', { textContent: T('health.susp_say', { by, apps: list }) })), el('small', { textContent: T('health.susp_note') })), fix);
    }));
  }
}
async function suspCheck() {
  if (suspBusy || Date.now() - suspAt < 60000) return;
  suspBusy = true; suspAt = Date.now();
  const r = await api('/api/health/suspended', null, true);
  suspBusy = false;
  if (r && r.apps && currentTab === 'health') drawSusp(r);
}
async function loadHealth() {
  if (hmode !== 'now') return;
  if (healthBusy || ADB.state !== 'ready') return;
  healthBusy = true; const gen = tvGen;
  if (!hliveT) hliveGo(true);                                         // not again while it runs: one reading every 2 s
  // a picture shown outside this page (the phone app's own remote screen, its PiP, another device): the TV's own count
  const [h, seen] = await Promise.all([api('/api/health' + (hsub === 'cpu' ? '?apps=1' : ''), null, true), screenOn ? {} : watchersNow()]);
  healthBusy = false;
  if (!h && gen === tvGen && currentTab === 'health' && !$('.health').classList.contains('ready')) failed($('#hwait'), loadHealth);   // never read yet
  if (!h || currentTab !== 'health') return;
  if (!$('.health').classList.contains('ready')) $('.health').classList.add('ready');
  speedLoad();   // add() writes the attribute even when it is there
  hfull = h;
  suspCheck();
  if (hsub === 'ram') loadRamApps();
  const r = h.remote || {}, vol = r.volume || {}, w = h.wifi;
  put($('#hwhen'), 'textContent', T('health.updates_2s'));
  const patch = /^\d{4}-\d\d/.test(h.patch || '') ? new Date(h.patch.slice(0, 7) + '-15').toLocaleDateString(window.LOCALE, { month: 'short', year: 'numeric' }) : h.patch;
  const fact = (icon, k, v, tool) => v == null || v === '' ? null : el('div', { className: 'fact' }, UI.icon(icon), el('div', {}, el('small', { textContent: k }), el('b', { textContent: String(v) })), tool || null);
  const mac = w && w.mac ? el('button', { type: 'button', className: 'small iconbtn', textContent: '📋', title: T('tv.copy'), 'aria-label': T('tv.copy'), onclick: () => copyText(w.mac) }) : null;
  const facts = [
    fact('clock', T('health.f_on_for'), h.uptime != null ? fmtUptime(h.uptime) : ''),
    fact('power', T('health.k_screen'), !r.connected ? T('health.power_unknown') : r.is_on ? T('health.state_on') : T('health.state_off')),
    fact('apps', T('health.k_app'), r.app ? ((typeof appName === 'function' && appName(r.app)) || r.app) : ''),
    fact('vol_up', T('health.k_volume'), vol.max ? vol.level + ' / ' + vol.max + (vol.muted ? ' · ' + T('health.muted') : '') : ''),
    fact('bot', 'Android', h.android), fact('shield_ok', T('health.k_patch'), patch),
    fact('wifi', 'Wi-Fi', w ? w.ssid : T('health.wifi_off')),
    fact('gauge', T('health.k_signal'), w && w.rssi != null ? signal(w.rssi) + (w.speed ? ' · ' + w.speed + ' Mbps' : '') : ''),
    fact('link', 'MAC', w && w.mac, mac),
    fact('zap', T('health.f_latency'), h.ping_ms + ' ms'),
    fact('camera', T('health.k_stream'), screenOn ? ($('#screenmode').value === 'snap' ? T('health.stream_snap') : T('health.stream_live', { fps: streamFps }) + ($('#screenmode').value === 'hd' ? ' · ' + T('health.stream_sharpest') : ' · ' + $('#screenmode').value + 'p')) : seen.watch > 0 ? T('health.stream_other', { n: seen.watch }) : T('health.stream_none')),
  ];
  const fsig = JSON.stringify(facts.map((f) => f && f.textContent)) + window.LANG;   // redrawn only when a figure changed: a MAC can be selected
  if (drawn.get('#hfacts') !== fsig) { drawn.set('#hfacts', fsig); $('#hfacts').replaceChildren(...facts.filter(Boolean)); }
  const nt = h.nightly, nd = nt && new Date(nt.day + 'T12:00:00'), ago = nd && Math.round((Date.now() - nd) / 864e5);
  put('#hnight', 'hidden', !nt || nt.freed == null);
  if (nt && nt.freed != null) {                                       // only what it did: "freed 0 B" said nothing
    const did = [nt.freed > 0 && T('health.night_freed', { freed: fmtSize(nt.freed) }), nt.compiled > 0 && T('health.night_opt', { n: nt.compiled })].filter(Boolean);
    put('#hnight', 'textContent', T(ago <= 1 ? 'health.night_last' : 'health.night_on', { date: nt.day }) + ': ' + (did.length ? did.join(', ') : T('health.night_none')));
  }
  if (h.apps) {                                                       // CPU → apps: top's figure is of one core; shown of the whole TV
    const per = h.cores || 1, apps = h.apps.filter((a) => a.pkg !== '-'), sys = h.apps.find((a) => a.pkg === '-'), top = Math.max(1, ...h.apps.map((a) => a.cpu));
    const row = (a) => urow(a.pkg, a.pkg === '-' ? T('health.android_system') : a.name || a.pkg, Math.round(a.cpu / per) + '%', T('health.procs_n', { n: a.procs }), a.cpu * 100 / top);
    $('#hcpuapps').replaceChildren(UI.state(T('health.apps_running', { n: apps.length })),
      UI.list([...apps.slice(0, 10), ...(sys ? [sys] : [])].sort((a, b) => b.cpu - a.cpu).map(row), T('health.cpu_idle')));
  }
  const t = h.thermal;
  put('#htempc', 'hidden', !t);
  if (t) redrawIfChanged('#htemp', [
    t.status != null ? [T('health.k_heat'), heat()[t.status] || String(t.status)] : null,
    ...t.temps.map((s) => [s.name, Math.round(s.c) + ' °C']),
  ]);
  const bt = h.bt;
  put('#hbtc', 'hidden', !bt);
  if (bt) redrawIfChanged('#hbt', [
    ['Bluetooth', bt.on ? T('health.state_on') : T('health.state_off')],
    ...(bt.devices.length ? bt.devices.map((d) => [d.name, d.connected ? T('health.bt_connected') : T('health.bt_paired')]) : [[T('health.k_devices'), T('health.bt_none')]]),
  ]);
  redrawIfChanged('#hnet', [
    w ? ['Wi-Fi', w.ssid] : ['Wi-Fi', T('health.wifi_off')],
    w && w.rssi != null ? [T('health.k_signal'), w.rssi + ' dBm · ' + signal(w.rssi)] : null,
    w && w.speed ? [T('health.k_link'), w.speed + ' Mbps'] : null,
    w && w.freq ? [T('health.k_band'), (w.freq > 4000 ? '5 GHz' : '2.4 GHz') + ' · ' + w.freq + ' MHz'] : null,
    w && w.mac ? ['MAC', w.mac] : null,
    ...h.ips.map((i) => [i.if.startsWith('tun') ? T('health.ip_tailscale') : i.if.startsWith('eth') ? T('health.ip_ethernet') : T('health.ip_home'), i.ip]),
    w && w.rssi != null ? [T('health.k_advice'), wifiTip(w)] : null,
  ]);
  redrawIfChanged('#hhw', [
    [T('health.k_device'), (h.maker + ' ' + h.model).trim()], ['Android', h.android + (h.sdk ? ' (SDK ' + h.sdk + ')' : '')], [T('health.k_patch'), h.patch],
    ['Chip', h.chip], [T('health.k_arch'), h.abi], [T('health.k_cores'), h.cores], ['RAM', h.mem_total ? fmtSize(h.mem_total) : ''],
    [T('health.k_screen'), h.screen + (h.fps ? ' · ' + h.fps + ' Hz' : '')], [T('health.k_density'), h.density ? h.density + ' dpi' : ''],
  ]);
  if (!hlast) drawLive({ cpu: h.cpu, mem: h.mem_total ? { total: h.mem_total, avail: h.mem_free } : {} });   // before the first live reading
}
setInterval(() => { if (currentTab === 'health' && !document.hidden && !stopped) { loadHealth(); loadSleep(); } }, 10000);   // each is top + 3 dumpsys on the TV
