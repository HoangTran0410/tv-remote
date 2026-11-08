// 📺 the TV card on the Setup tab: restart, DNS filter, sleep, screensaver, size, home screen, and the screen off or dimmed. Read back from the TV after
// every change, so the choice that is lit is what the TV really has. The commands are in TvShell.kt.

// Each filter is tried in turn until one answers on this network: a DNS that does not leaves the TV with no Internet, so the
// TV checks, and puts the old setting back by itself when names stop resolving.
const DNS_SETS = {
  ads: ['dns.adguard-dns.com', 'adblock.dns.mullvad.net', 'p2.freedns.controld.com'],
  family: ['family.adguard-dns.com', 'family.dns.mullvad.net', 'family.cloudflare-dns.com'],
};
// The well-known ones, offered by name in "Tự nhập...". Android's private DNS takes a host name only (DNS-over-TLS): the
// addresses people know (8.8.8.8) are turned into their names.
const DNS_LIST = [
  { host: 'dns.google', brand: 'Google', ips: ['8.8.8.8', '8.8.4.4'] },
  { host: 'one.one.one.one', brand: 'Cloudflare', ips: ['1.1.1.1', '1.0.0.1'] },
  { host: 'security.cloudflare-dns.com', brand: 'Cloudflare', kind: T('tvset.dns_malware'), ips: ['1.1.1.2', '1.0.0.2'] },
  { host: 'family.cloudflare-dns.com', brand: 'Cloudflare', kind: T('tvset.family'), ips: ['1.1.1.3', '1.0.0.3'] },
  { host: 'dns.quad9.net', brand: 'Quad9', kind: T('tvset.dns_malware'), ips: ['9.9.9.9', '149.112.112.112'] },
  { host: 'dns.adguard-dns.com', brand: 'AdGuard', kind: T('tvset.dns_ads'), ips: ['94.140.14.14', '94.140.15.15'] },
  { host: 'family.adguard-dns.com', brand: 'AdGuard', kind: T('tvset.family'), ips: ['94.140.14.15', '94.140.15.16'] },
  { host: 'adblock.dns.mullvad.net', brand: 'Mullvad', kind: T('tvset.dns_ads'), ips: [] },
  { host: 'family.dns.mullvad.net', brand: 'Mullvad', kind: T('tvset.family'), ips: [] },
  { host: 'p2.freedns.controld.com', brand: 'Control D', kind: T('tvset.dns_ads'), ips: [] },
  { host: 'dns.nextdns.io', brand: 'NextDNS', ips: [] },
];
const DNS_BY_IP = Object.fromEntries(DNS_LIST.flatMap((d) => d.ips.map((ip) => [ip, d.host])));
const dnsBrand = (host) => (DNS_LIST.find((d) => d.host === host) || {}).brand;
const dnsWho = (hosts) => hosts.map(dnsBrand).join(', ');
/** What was typed as the host name to set, or null after saying why it cannot be one. */
function dnsHost(typed) {
  const words = typed.toLowerCase().replace(/^\w+:\/\//, '').split(/[\s,;/]+/).filter(Boolean);   // "8.8.8.8 / 8.8.4.4", a pasted URL
  const ip = words.find((w) => DNS_BY_IP[w]);
  if (ip) return DNS_BY_IP[ip];
  if (words.some((w) => /^[\d.]+$|:/.test(w))) { toast(T('tvset.dns_name_only')); return null; }
  const h = words[0] || '';
  if (!/^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(h)) { toast(T('tvset.dns_bad_name')); return null; }
  return h;
}
const MIN = 60000;
const fmtWait = (ms) => ms % (60 * MIN) === 0 ? T('tvset.hours', { n: ms / 60 / MIN }) : T('tvset.minutes', { n: Math.round(ms / MIN) });
let tvsetBusy = false;

async function loadTvset() {
  if (ADB.state !== 'ready' || tvsetBusy) return;
  const gen = tvGen, r = await api('/api/tvset', null, true);
  if (r) drawTvset(readTvset(r.text));
  else if (gen === tvGen && $('#tvset > .muted')) failed($('#tvset'), loadTvset);   // still "Reading TV settings...": say so, with Try again
}

function drawTvset(t) {
  const choice = (label, on, fn) => el('button', { className: on ? 'on' : '', textContent: label, onclick: fn });
  const row = (title, choices, note) => el('div', { className: 'setrow' }, el('b', { textContent: title }), el('div', { className: 'places' }, ...choices),
    note ? el('p', { className: 'muted', textContent: note }) : null);
  const rows = [];

  const dnsOn = t.dnsMode === 'hostname' && t.dnsHost;
  const kind = !dnsOn ? 'off' : DNS_SETS.ads.includes(t.dnsHost) ? 'ads' : DNS_SETS.family.includes(t.dnsHost) ? 'family' : 'own';
  const dnsNote = el('p', { className: 'muted', textContent: t.sdk && t.sdk < 28 ? T('tvset.dns_old_android')
    : (dnsOn ? T('tvset.dns_using', { host: t.dnsHost + (dnsBrand(t.dnsHost) ? ' (' + dnsBrand(t.dnsHost) + ')' : '') }) + ' ' : '') + T('tvset.dns_applies') });
  const dnsRow = row(T('tvset.dns_filter'), [
    choice(T('tvset.none'), kind === 'off', () => setDns([''])),
    Object.assign(choice(T('tvset.dns_ads'), kind === 'ads', () => setDns(DNS_SETS.ads)), { title: dnsWho(DNS_SETS.ads) }),
    Object.assign(choice(T('tvset.family'), kind === 'family', () => setDns(DNS_SETS.family)), { title: dnsWho(DNS_SETS.family) }),
    choice(kind === 'own' ? t.dnsHost : T('tvset.type_own'), kind === 'own', async () => {
      const typed = await ask(T('tvset.dns_own_ask'), { value: kind === 'own' ? t.dnsHost : '',
        choices: DNS_LIST.map((d) => ({ value: d.host, name: d.brand, tag: d.kind, sub: d.host + (d.ips.length ? ' · ' + d.ips[0] : '') })) });
      const h = typed && typed.trim() && dnsHost(typed);
      if (h) setDns([h]);
    }),
  ]);
  dnsRow.append(dnsNote);
  if (!dnsOn && t.dnsOff && Date.now() - t.dnsOff.at < 7 * 86400000) {   // the moment in the page's language, not dd/mm
    let when; try { when = new Date(t.dnsOff.at).toLocaleString(window.LANG, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }); } catch { when = new Date(t.dnsOff.at).toLocaleString(); }
    dnsRow.append(el('p', { className: 'warnline', textContent: T('tvset.dns_turned_off', { when }) }));
  }
  if (t.sdk && t.sdk < 28) dnsRow.querySelectorAll('button').forEach((b) => { b.disabled = true; });
  rows.push(dnsRow);

  const sleeps = [[-1, T('tvset.sleep_never')], [30 * MIN, fmtWait(30 * MIN)], [60 * MIN, fmtWait(60 * MIN)], [240 * MIN, fmtWait(240 * MIN)], [720 * MIN, fmtWait(720 * MIN)], [1440 * MIN, fmtWait(1440 * MIN)]];
  const ownSleep = t.sleep !== null && !sleeps.some(([v]) => v === t.sleep);
  const sleepBtns = sleeps.map(([v, label]) => [v, choice(label, t.sleep === v, () => setTv('sleep', v, T('tvset.sleep_set', { label })))]);
  const ownBtn = choice(ownSleep ? fmtWait(t.sleep) : T('tvset.type_own'), ownSleep, async () => {
    const v = await askWait(ownSleep ? t.sleep : null);
    if (v) setTv('sleep', v, T('tvset.sleep_set', { label: fmtWait(v) }));
  });
  if (ownSleep) sleepBtns.push([t.sleep, ownBtn]), sleepBtns.sort((a, b) => (a[0] < 0 ? -1 : b[0] < 0 ? 1 : a[0] - b[0]));   // 45 min between 30 and 1 h, as the screensaver row below
  rows.push(row(T('tvset.sleep_title'), [...sleepBtns.map(([, b]) => b), ...(ownSleep ? [] : [ownBtn])]));

  // the screensaver comes on after this long without a key; "never" is how some TVs (Sony) say off
  const saverOn = t.saver && t.saverAfter !== null && t.saverAfter < 86400000;
  const savers = [['off', T('tvset.none')], [5 * MIN, fmtWait(5 * MIN)], [15 * MIN, fmtWait(15 * MIN)], [30 * MIN, fmtWait(30 * MIN)]];
  if (saverOn && !savers.some(([v]) => v === t.saverAfter)) savers.push([t.saverAfter, fmtWait(t.saverAfter)]), savers.sort((a, b) => (a[0] === 'off' ? -1 : b[0] === 'off' ? 1 : a[0] - b[0]));   // 10 min between Off and 15, not after 30
  rows.push(row(T('tvset.saver_title'), savers.map(([v, label]) => choice(label, v === 'off' ? !saverOn : saverOn && t.saverAfter === v, () => setTv('saver', v, T('tvset.saver_set', { label }))))));

  const sizes = densityChoices(t.physical);
  if (sizes.length) rows.push(row(T('tvset.size_title'), sizes.map(([label, v]) => choice(label, v === 'reset' ? !t.override || t.override === t.physical : t.override === v,
    () => setTv('density', v, T('tvset.size_set', { label })))), T('tvset.size_note')));

  if (t.launchers.length) {
    const homes = t.launchers.map((c) => {
      const pkg = c.split('/')[0], b = choice(appName(pkg), c === t.home, () => setTv('home', c, T('tvset.home_set', { name: appName(pkg) })));
      onMeta(pkg, (m) => { if (m.label) b.textContent = m.label; });
      return b;
    });
    rows.push(row(T('tvset.home_title'), homes, t.launchers.length < 2 ? T('tvset.home_one') : T('tvset.home_many')));
  }
  // the remote's Màn hình TV menu (remote.json "screen") again here, where Manage is used without the remote
  rows.push(el('div', { className: 'setrow' }, el('b', { textContent: T('tvset.screen_title') }),
    UI.actions(UI.btn(T('rkey.screen_off'), () => screenOff()), UI.btn(T('rkey.dim'), () => dimOpen()), UI.btn(T('rkey.vbox'), () => videoOpen()))));
  $('#tvset').replaceChildren(...rows);
}

async function askWait(now) {
  const m = await askTime(T('tvset.sleep_after_ask'), now ? now / MIN : 0);
  return m ? m * MIN : null;
}

async function setTv(key, value, done) {
  const r = await api('/api/tvset', { key, value });
  if (!r) return;
  const out = r.text.trim();
  if (key === 'home' && !out.endsWith(value)) toast(T('tvset.home_unchanged', { why: out.replace(/^home\s*/, '') || T('tvset.no_reason') }));
  else if (/^ok$/m.test(out) || key === 'home') toast(done);
  else toast(T('tvset.error_msg', { msg: friendly(out || T('core.cannot_change')) }));
  loadTvset();
}

// One host after another until the TV can look up names through it; the buttons wait meanwhile (up to 30 s a host).
async function setDns(hosts) {
  const btns = [...$('#tvset').querySelectorAll('.setrow:first-child button')];
  tvsetBusy = true; btns.forEach((b) => { b.disabled = true; });
  const note = $('#tvset').querySelector('.setrow:first-child .muted');
  let ok = '';
  try {
    for (const h of hosts) {
      if (h) note.textContent = T('tvset.dns_trying', { host: h });
      const r = await call('/api/tvset', { key: 'dns', value: h });
      if (!r) break;
      if (/^ok$/m.test(r.text)) { ok = h || 'off'; break; }
    }
  } finally { tvsetBusy = false; }
  toast(ok === 'off' ? T('tvset.dns_off') : ok ? T('tvset.dns_on', { host: ok }) : T('tvset.dns_failed'));
  loadTvset();
}

// 🔁 Restart: the page waits for the TV to go down and come back, then reconnects. The TV's ADB comes back by itself on the
// TVs this works with (the vendor's "ADB debugging" switch survives a restart); after 3 minutes the page says what to check.
let rebootT0 = 0;
$('#tvreboot').addEventListener('click', async () => {
  if (!await ask(T('tvset.restart_ask'), { ok: T('tvset.restart_ok') })) return;
  if (!await api('/api/reboot', {})) return;
  rebootT0 = Date.now();
  put('#reboottitle', 'textContent', T('rebootdlg.the_tv_is_restarting')); put('#rebootspin', 'hidden', false); $('#rebootdlg').showModal();
  watchReboot(rebootT0);
});
$('#rebootclose').addEventListener('click', () => $('#rebootdlg').close());
async function watchReboot(t0) {
  let down = false;
  while (rebootT0 === t0 && Date.now() - t0 < 10 * 60000) {             // 10 minutes: by then the note says what to do
    await sleep(3000);
    const s = await fetch('/api/state').then((r) => r.ok ? r.json() : null).catch(() => null);
    const up = !!(s && s.adb && s.adb.state === 'ready'), secs = Math.round((Date.now() - t0) / 1000);
    if (!up) down = true;
    // ADB answers early in the boot: done once the TV's settings answer too (or it never went down: nothing to wait for)
    const booted = up && (down || secs > 60) && /^sleep -?\d/m.test((await fetch('/api/tvset').then((r) => r.ok ? r.json() : {}).catch(() => ({}))).text || '');
    if (booted) {
      rebootT0 = 0; $('#rebootdlg').close(); toast(T('tvset.restarted')); bannerAgain(); refresh(); loadTvset(); return;
    }
    const clock = Math.floor(secs / 60) + ':' + String(secs % 60).padStart(2, '0');
    put('#rebootstep', 'textContent', secs < 180 ? (down ? T('tvset.booting', { clock }) : T('tvset.shutting_down', { clock }))
      : (CORE === 'tv' ? T('tvset.slow_tv', { clock }) : T('tvset.slow_adb', { clock })));
  }
}

// "Hold to use Input / Settings" (HoldKeys.kt in TV Setup): the remote's own keys need a 2 s hold; keys sent from here are not filtered.
// Read with the rules, at most every 20 s while the TV tab is open (card "Remote buttons"); an older TV Setup answers without "holdkeys" and is asked to update when the switch is used.
let holdAt = 0, holdBusy = false, holdSeen = '', holdGen = 0;
function holdReset() { holdGen++; holdAt = 0; holdSeen = ''; $('#hhold').replaceChildren(); if (typeof currentTab !== 'undefined' && currentTab === 'tv') loadHold(true); }
onTvChange(holdReset);
async function loadHold(now) {
  if (holdBusy || (!now && Date.now() - holdAt < 20000)) return;
  const box = $('#hhold'), gen = holdGen;
  if (!now && box.contains(document.activeElement) && document.activeElement !== document.body) return;   // not under a D-pad's focus
  holdBusy = true; holdAt = Date.now();
  const r = await api('/api/rules', null, true);
  holdBusy = false;
  if (gen !== holdGen || !r) return;
  checkSet('mic', !!r.mic);
  const seen = JSON.stringify([r.holdkeys, r.mic, r.mic_offline, r.voice, r.mic_lang, r.mic_tv_lang, r.mic_langs, r.mic_engine, r.mic_freespeech, r.mic_secure, r.mic_block, r.mic_block_app, r.adb, r.voice_search, r.voice_youtube, r.voice_tubes]) + window.LANG;
  if (seen === holdSeen) return;                                       // nothing new: keep what is on screen
  holdSeen = seen;
  const holdOld = r.holdkeys === undefined;
  const hold = el('label', { className: 'check' }, el('input', { type: 'checkbox', checked: !!r.holdkeys, onchange: async (e) => {
    const cb = e.currentTarget, on = cb.checked; cb.disabled = true;
    const ok = (!holdOld || await setupFor('holdkeys')) && await api('/api/rules', { do: 'holdkeys', on });
    cb.disabled = false; if (!ok) cb.checked = !on; else { holdSeen = ''; toast(on ? T('hold.enabled') : T('hold.disabled')); }
  } }), ' ' + T('hold.switch'));
  // The remote's mic button opens TV Remote's voice on the TV (MicVoice.kt in TV Setup): off by default, a rule like the one above.
  const micOld = r.mic === undefined;
  const mic = el('label', { className: 'check' }, el('input', { type: 'checkbox', checked: !!r.mic, onchange: async (e) => {
    const cb = e.currentTarget, on = cb.checked; cb.disabled = true;
    const ok = (!micOld || await setupFor('mic')) && await api('/api/rules', { do: 'mic', on });
    cb.disabled = false; if (!ok) cb.checked = !on; else { holdSeen = ''; toast(on ? T('mic.enabled') : T('mic.disabled')); }
  } }), ' ' + T('mic.switch'));
  // TV Setup Voice (app/tvvoice): speech heard on the TV, for a TV without Google's; TV Setup installs it while on, removes it when off.
  const voice = String(r.voice || 'none');
  if (voice === 'busy') setTimeout(() => { if (gen === holdGen) loadHold(true); }, 3000);   // being installed: the state soon
  const offline = el('label', { className: 'check' }, el('input', { type: 'checkbox', checked: !!r.mic_offline, disabled: voice === 'busy', onchange: async (e) => {
    const cb = e.currentTarget, on = cb.checked; cb.disabled = true;
    const ok = (r.mic_offline !== undefined || await setupFor('mic_offline')) && await api('/api/rules', { do: 'mic_offline', on });
    cb.disabled = false; if (!ok) cb.checked = !on; else { holdSeen = ''; toast(on ? T('mic.offline_installing') : T('mic.offline_removed')); loadHold(true); }
  } }), ' ' + T('mic.offline'));
  // Who listens first once TV Setup Voice is on the TV (Rules "mic_engine"; MicActivity): Google's, or TV Setup Voice (faster, no Internet)
  const pickEngine = async (engine) => {
    if ((r.mic_engine !== undefined || await setupFor('mic_engine')) && await api('/api/rules', { do: 'mic_engine', engine })) { holdSeen = ''; loadHold(true); }
  };
  const tvFirst = r.mic_engine === 'tv';
  const engine = voice === 'ready' ? [el('p', { className: 'note', textContent: T('mic.engine') }), UI.chips([
    { label: T('mic.engine_google'), on: !tvFirst, fn: () => pickEngine('auto') },
    { label: T('mic.engine_tv'), on: tvFirst, fn: () => pickEngine('tv') }]), UI.note(T('mic.engine_sub'))] : [];
  // What TV Setup Voice hears (Rules "mic_freespeech"): commands only, or also free speech (searches, song names; slower). Off is today's behaviour.
  const pickFree = async (on) => {
    if ((r.mic_freespeech !== undefined || await setupFor('mic_freespeech')) && await api('/api/rules', { do: 'mic_freespeech', on })) { holdSeen = ''; loadHold(true); }
  };
  const free = voice === 'ready' ? [UI.chips([
    { label: T('mic.free_cmd'), on: !r.mic_freespeech, fn: () => pickFree(false) },
    { label: T('mic.free_on'), on: !!r.mic_freespeech, fn: () => pickFree(true) }]), UI.note(T('mic.free_sub'))] : [];
  // Which app "search …" and "YouTube" mean (Rules voice_search / voice_youtube; Voice.Prefs): only the YouTube clients on the TV (r.voice_tubes) are
  // offered; the first chip is today's behaviour. A TV Setup older than this reports no voice_tubes and shows nothing.
  const tubes = r.voice_tubes || [];
  const pickVoice = async (rule, pkg) => { if (await api('/api/rules', { do: rule, pkg })) { forgetVoicePrefs(); holdSeen = ''; loadHold(true); } };
  const mainTube = tubes.find((t) => t.main), others = tubes.filter((t) => !t.main);
  const pickedSearch = tubes.find((t) => t.pkg === r.voice_search);
  const voicePick = tubes.length ? [UI.group(T('voice.title'),
    el('p', { className: 'note', textContent: T('voice.search') }),
    UI.chips([{ label: T('voice.search_tv'), on: !pickedSearch, fn: () => pickVoice('voice_search', '') }, ...tubes.map((t) => ({ label: t.name, on: t === pickedSearch, fn: () => pickVoice('voice_search', t.pkg) }))]),
    ...(pickedSearch && !pickedSearch.query ? [UI.note(T('voice.open_only'))] : []),
    ...(mainTube && others.length ? [el('p', { className: 'note', textContent: T('voice.youtube') }),
      UI.chips([mainTube, ...others].map((t) => ({ label: t.name, on: t.pkg === r.voice_youtube || (t === mainTube && !tubes.some((x) => x.pkg === r.voice_youtube)), fn: () => pickVoice('voice_youtube', t === mainTube ? '' : t.pkg) })))] : []),
    UI.note(T('voice.note')))] : [];
  // Its language (Rules "mic_lang"): each one its own download, added beside the others (the user, 2026-11-03); a TV Setup that does not
  // report mic_langs is older than languages, and shows none
  const VLANGS = { vi: [LANGS.vi, '39 MB'], en: [LANGS.en, '47 MB'] }, has = r.mic_langs || [];   // each by its own name (langs.json)
  const pickLang = async (lang) => { if (await api('/api/rules', { do: 'mic_lang', lang })) { holdSeen = ''; loadHold(true); } };
  const dropLang = async (lang) => { if (await api('/api/rules', { do: 'mic_lang_remove', lang })) { holdSeen = ''; toast(T('mic.offline_removed')); loadHold(true); } };
  const langs = r.mic_offline && r.mic_langs && voice !== 'busy' ? [el('p', { className: 'note', textContent: T('tvvoice.lang') }),
    UI.chips(Object.entries(VLANGS).map(([l, [name, size]]) => ({ label: has.includes(l) ? name : name + ' · ' + size, on: l === (r.mic_active || r.mic_lang), fn: () => pickLang(l) }))),
    ...(has.length > 1 ? [el('div', { className: 'actions' }, el('button', { type: 'button', className: 'danger', textContent: T('tvvoice.remove_lang', { lang: VLANGS[r.mic_active || r.mic_lang]?.[0] }), onclick: () => dropLang(r.mic_active || r.mic_lang) }))] : []),
    UI.note(T('tvvoice.size'))] : [];
  // The language the TV's own recognizer listens in (Rules "mic_tv_lang"; MicActivity): "" = the TV's language. Not shown for TV Setup Voice
  // (its chips above) or a TV Setup that does not report it. Each language is named in its own language by the browser.
  const TVLANGS = 'ar-SA en-US vi-VN fr-FR de-DE es-ES pt-BR ru-RU hi-IN id-ID th-TH tr-TR it-IT ja-JP ko-KR zh-CN zh-TW pl-PL nl-NL uk-UA fa-IR he-IL ms-MY fil-PH bn-IN ur-PK sv-SE el-GR ro-RO cs-CZ hu-HU'.split(' ');
  const tvLangName = (tag) => { try { const n = new Intl.DisplayNames([tag], { type: 'language' }).of(tag); return n.charAt(0).toLocaleUpperCase(tag) + n.slice(1); } catch { return tag; } };
  const tvLangNow = r.mic_tv_lang || '';
  const tvLang = r.mic && !r.mic_offline && r.mic_tv_lang !== undefined ? [el('p', { className: 'note', textContent: T('mic.tv_lang_note') }),
    el('div', { className: 'actions' }, el('button', { type: 'button', className: 'appchoice', textContent: tvLangNow ? tvLangName(tvLangNow) : T('mic.tv_lang_same'), onclick: () => {
      const list = ['', ...(tvLangNow && !TVLANGS.includes(tvLangNow) ? [tvLangNow] : []), ...TVLANGS];   // a list too long for chips: one menu (DESIGN.md)
      menuDlg(T('mic.tv_lang'), -1, list.map((t) => [t ? tvLangName(t) : T('mic.tv_lang_same'), t, async () => { if (await api('/api/rules', { do: 'mic_tv_lang', lang: t })) { holdSeen = ''; loadHold(true); } }]), list.indexOf(tvLangNow));
    } }))] : [];
  const vstate = voice === 'busy' ? T('mic.offline_busy') : voice === 'ready' ? T('mic.offline_ready')
    : voice.startsWith('failed:') && r.mic_offline ? T('mic.offline_failed', { why: voice === 'failed:adb' ? T('mic.needs_adb') : friendly(voice.slice(7)) }) : '';
  // The mic key cannot reach TV Setup (MicVoice.block): say who holds it, and offer the way out (Rules "mic_free": the Google app off over ADB, else its settings page)
  const blocked = r.mic && r.mic_block ? [el('p', { className: 'note warn', textContent: r.mic_block === 'google_holds_mic' ? T('mic.block_google') : T('mic.block_other', { app: r.mic_block_app }) }),
    el('div', { className: 'actions' }, el('button', { type: 'button', textContent: r.mic_block === 'google_holds_mic' && r.adb === 'ready' ? T('mic.block_off_google') : T('mic.block_open', { app: r.mic_block_app || 'Google' }), onclick: async (e) => {
      const b = e.currentTarget; b.disabled = true;
      const ok = await api('/api/rules', { do: 'mic_free' });
      b.disabled = false; if (ok) { toast(r.mic_block === 'google_holds_mic' && r.adb === 'ready' ? T('mic.block_done') : T('mic.block_opened')); holdSeen = ''; setTimeout(() => loadHold(true), 2500); }
    } })), ...(r.mic_block === 'google_holds_mic' ? [UI.note(T('mic.block_note'))] : [])] : [];
  box.replaceChildren(UI.group(T('hold.tile'), hold, UI.note(T('hold.sub')), UI.note(T('hold.sub_off'))), UI.group(T('mic.tile'), mic, UI.note(T('mic.sub')), UI.note(T('mic.sub_off')), UI.note(T('mic.eg')), UI.note(T('mic.need')), ...(r.mic && r.mic_secure === false ? [UI.note(T('mic.first_press'))] : []), ...blocked, offline, UI.note(T('mic.offline_sub')), ...(vstate ? [UI.note(vstate)] : []), ...langs, ...tvLang, ...engine, ...free), ...voicePick,
    // The key tester (KeyTestActivity in TV Setup): opened on the TV by `am start`; an older TV Setup is asked to update first.
    UI.group(T('keytest.tile'), el('div', { className: 'actions' }, el('button', { type: 'button', textContent: T('keytest.open'), onclick: async () => { if (await api('/api/keytest/open', {})) toast(T('keytest.opened')); } })), UI.note(T('keytest.note'))));
}
