// "Why can't I reach the TV?" When the TV stays out of reach for a few polls, one quick self-check (Diagnose.kt, /api/diagnose:
// this device's network, the TV's ports, a VPN, the app's ADB link, the TV under a new address) answers with ONE conclusion and
// what to do, in the banner at the top, instead of leaving the person with a red chip and a raw error. The same check backs
// the phone's native screens. Cancel stops waiting; Retry connects again, and a TV that is still out of reach is checked again.
const DIAG_TONE = { up: 'ok', wireless: 'warn', adb_off: 'warn', remote_off: 'warn', allow: 'warn', closed: 'warn', moved: 'warn' };   // the others are 'bad'
let diagSetupKey = '';
let diagKey = '', diagBad = 0, diagCtl = null;
const diagAsked = new Set();                                         // TVs whose pairing window was opened for the person already: once, not at every check

// The TV is not answering as a remote and ADB is not up either (a TV that is asking, or not paired yet, has its own words).
function diagNeeded(s) {
  if (CORE === 'tv' || picking || !s.host || s.connected || s.asking || s.paired === false) return false;
  return !(s.adb && s.adb.state === 'ready');
}

// A Fire TV whose first setup never finished (user_setup_complete = 0) answers every key but Home and Alexa. ADB is up here, so the
// self-check above does not run; this one asks once per TV (Diagnose.SETUP_OFF) and offers the one `settings put` only on Fix.
async function diagSetup(s) {
  if (CORE === 'tv' || picking || !s.host || !s.connected || !(s.adb && s.adb.state === 'ready') || (s.kind || 'android') !== 'android') return;
  const key = s.host; if (diagSetupKey === key) return; diagSetupKey = key;
  let r = null;
  try { const x = await fetch('/api/diagnose/setup?lang=' + LANG); if (x.ok) r = await x.json(); } catch {}
  if (!r || r.code !== 'setup_off' || diagSetupKey !== key) return;
  const box = $('#diag');
  box.className = 'banner warn';
  box.replaceChildren(el('b', { textContent: r.title }), el('p', { textContent: r.fix }),
    el('div', { className: 'actions' }, el('button', { className: 'small primary', textContent: T('diagnose.fix_setup'), onclick: async () => {
      await api('/api/diagnose/setup', {}, true); box.hidden = true; box.replaceChildren(); } })));
  box.hidden = false;
}

function diagHide() {
  if (diagCtl) { diagCtl.abort(); diagCtl = null; }
  put('#diag', 'hidden', true); $('#diag').replaceChildren();
}

function diagWatch(s) {
  diagSetup(s);
  if (!diagNeeded(s)) { diagBad = 0; if (diagKey) { diagKey = ''; diagHide(); } return; }
  const key = s.host + '|' + (s.kind || 'android');
  if (diagKey === key || ++diagBad < 2) return;                      // one miss is often the TV or this device waking
  diagKey = key; diagRun(s);
}

async function diagRun(s) {
  if (diagCtl) diagCtl.abort();
  const ctl = diagCtl = new AbortController(), box = $('#diag');
  box.hidden = false; box.className = 'banner wait';
  box.replaceChildren(el('span', { className: 'minispin' }), el('span', { textContent: T('diagnose.checking') }),
    el('button', { className: 'small', textContent: T('common.cancel'), onclick: () => { diagCtl = null; ctl.abort(); box.hidden = true; box.replaceChildren(); } }));
  let r = null;
  try {
    const q = new URLSearchParams({ ip: s.host, kind: s.kind || 'android', name: s.name || '', lang: LANG, err: s.error || '' });
    const x = await fetch('/api/diagnose?' + q, { signal: ctl.signal });
    if (x.ok) r = await x.json();
  } catch {}
  if (ctl.signal.aborted) return;
  diagCtl = null;
  if (!r) { box.hidden = true; box.replaceChildren(); return; }       // the check itself failed: the chips and banners say what they can
  diagShow(r, s);
}

function diagShow(r, s) {
  const box = $('#diag'), pc = CORE === 'pc';
  const again = async () => { diagKey = ''; diagBad = 0; box.hidden = true; box.replaceChildren(); };
  const ACT = {
    retry: () => el('button', { className: 'small primary', textContent: T('diagnose.retry'), onclick: async () => {
      await again();
      if (pc) selectTv(s.host, s.name, s.kind); else { await api('/api/adb/connect', {}, true); refresh(); } } }),
    scan: () => pc ? el('button', { className: 'small', textContent: T('page.scan'), onclick: () => { again(); pickOpen(); refresh(); $('#scan').click(); } }) : null,
    wireless: () => CORE !== 'tv' ? el('button', { className: 'small primary', textContent: T('setup.wireless_title'), onclick: () => { again(); wirelessPair(); } }) : null,
    adbhelp: () => pc ? el('button', { className: 'small', textContent: T('setup.turn_on_adb_help'), onclick: () => adbHelp() }) : null,
    use: () => pc && r.found ? el('button', { className: 'small primary', textContent: T('diagnose.use_address', { ip: r.found.ip }), onclick: () => { again(); selectTv(r.found.ip, r.found.name || s.name, r.kind); } }) : null,
  };
  const acts = r.actions.map((a) => ACT[a] && ACT[a]()).filter(Boolean);
  // a cause the check cannot fix (or Retry keeps failing): the log and the page to ask for help, here, not only in Settings → About
  if (r.code !== 'up') acts.push(el('button', { className: 'small', textContent: T('about.send_the_log'), onclick: () => sendWireLog() }));
  box.className = 'banner ' + (DIAG_TONE[r.code] || 'bad');
  box.replaceChildren(el('b', { textContent: r.title }), el('p', { textContent: r.fix }),
    el('details', { className: 'checks' }, el('summary', { textContent: T('diagnose.checked') }),
      el('ul', {}, ...r.checks.map((c) => el('li', { className: c.ok === true ? 'ok' : c.ok === false ? 'bad' : '', textContent: (c.ok === true ? '✓ ' : c.ok === false ? '✗ ' : '· ') + c.note })))),
    el('div', { className: 'actions' }, ...acts));
  box.hidden = false;
  if (r.code === 'wireless' && CORE !== 'tv' && !diagAsked.has(s.host)) { diagAsked.add(s.host); wirelessPair(); }   // straight to pairing, not left to find the button
}
