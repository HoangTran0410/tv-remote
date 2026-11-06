// Kids mode (Rules.kt "lock" mode / allow / methods, "unlock_until", "total", "clock"; Totp.kt): the card in the Automation tab that locks
// every app but the few a child may open, the ways a parent opens the rest (PIN, Google Authenticator, Telegram), a timed unlock, and the
// total time of TV a day. Draws from RULES (auto.js) and sends through loadRules, as the PIN lock does.

// ---- Google Authenticator: a 20-byte secret as base32 (RFC 4648, no padding), and the link its QR carries (tests/kids.test.js) ----
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function base32(bytes) {
  let bits = 0, acc = 0, out = '';
  for (const b of bytes) { acc = (acc << 8) | b; bits += 8; while (bits >= 5) { out += B32[(acc >>> (bits - 5)) & 31]; bits -= 5; } acc &= (1 << bits) - 1; }
  return bits ? out + B32[(acc << (5 - bits)) & 31] : out;
}
const otpauthUrl = (secret, tv) => 'otpauth://totp/' + 'TV%20Remote:' + encodeURIComponent(tv || 'TV') + '?secret=' + secret + '&issuer=' + encodeURIComponent('TV Remote');
const groupSecret = (s) => s.replace(/(.{4})(?=.)/g, '$1 ');

const TOTAL_DAYS_ALL = [1, 2, 3, 4, 5, 6, 7];
const UNLOCKS = [[30, 'auto.kids_open_30'], [60, 'auto.kids_open_60'], [120, 'auto.kids_open_120']];
let tgFull = null, tgFullAt = 0;                                       // does a Telegram chat with the FULL scope exist (the "ask a parent" method needs one)
const kidsOf = (r) => (r.lock && r.lock.methods) ? r.lock : null;      // a TV Setup before kids mode has no methods in its answer

async function kidsTelegram() {                                         // asked at most every 20 s: the card is drawn on every tick of the rules
  if (Date.now() - tgFullAt < 20000) return tgFull;
  tgFullAt = Date.now();
  const j = await api('/api/telegram', null, true);
  tgFull = !!(j && j.on && j.has_token && (j.chats || []).some((c) => c.scope === 'full'));
  return tgFull;
}
async function kidsGate() { return !(RULES && !kidsOf(RULES)) || await setupFor('kids'); }
async function kidsSend(change) { return await kidsGate() ? loadRules(change) : null; }
// mode / allow / methods always travel together: the TV keeps what it is sent
const kidsSave = (k, patch) => kidsSend({ do: 'kids', mode: k.mode || 'list', allow: k.allow || [], methods: { pin: true, totp: !!k.methods.totp, ask: !!k.methods.ask }, ...patch });

function kidsClockWarn(r) {
  const c = r.clock;
  if (!c) return null;                                                  // TV Setup has not looked yet: nothing to say
  const drift = Math.abs(c.drift_s || 0);
  if (c.auto === false) return T('auto.kids_clock_manual');
  return drift > 30 ? T('auto.kids_clock_drift', { n: drift }) : null;
}

function kidsMethodRow(label, on, fn, hint, disabled) {
  const box = el('input', { type: 'checkbox', checked: !!on, disabled: !!disabled });
  box.addEventListener('change', async () => { box.disabled = true; try { await fn(box.checked); } finally { box.disabled = !!disabled; } if (RULES) renderKids(RULES); });
  return el('div', { className: 'group' }, el('label', { className: 'check' }, box, el('span', { textContent: label })), hint ? UI.note(hint) : null);
}

function renderKids(r) {
  const card = $('#kidscard'); if (!card) return;
  const k = kidsOf(r) || { mode: 'list', allow: [], methods: { pin: true, totp: false, ask: false } };
  const kids = k.mode === 'kids', pin = !!(r.lock && r.lock.pin), wait = k.wait_until > r.now;
  foldSum('kidscard', kids ? T('fold.kids', { n: k.allow.length }) : T('fold.off'), kids);
  const part = (...kids) => el('div', { className: 'part' }, ...kids.filter(Boolean));   // each block in its own box: the card reads at a glance
  const body = [UI.note(T('auto.kids_note')), el('div', { id: 'familybox', className: 'part' })];   // Quick setup (family.js) first: a parent's one tap
  const lockPart = [UI.group(T('auto.kids_lock_title'), UI.chips([
    { label: T('auto.kids_mode_list'), on: !kids, fn: () => kids && kidsSave(k, { mode: 'list' }) },
    { label: T('auto.kids_mode_kids'), on: kids, fn: () => !kids && kidsSave(k, { mode: 'kids' }) }]))];
  if (kids) {
    const names = k.allow.map((p) => {
      const icon = appIcon(p); onMeta(p, (m) => icon.fill(m));
      return el('div', { className: 'item' }, icon, el('div', { className: 'name' }, el('span', { textContent: appName(p) })),
        el('div', { className: 'acts' }, act('🗑', () => kidsSave(k, { allow: k.allow.filter((x) => x !== p) }), 'danger', T('auto.kids_allow_remove'))));
    });
    lockPart.push(UI.group(T('auto.kids_allow_title'), UI.list(names, T('auto.kids_allow_none')),
      UI.actions(Object.assign(act(T('auto.kids_allow_add'), async () => {
        if (!await kidsGate()) return;
        if (!pickApps.length) await loadPickApps();
        const pkg = await chooseApp(T('auto.kids_allow_pick'), choosable().filter((p) => !k.allow.includes(p))); if (!pkg) return;
        await kidsSave(k, { allow: [...k.allow, pkg] });
      }), { id: 'kidsadd' }))));   // TV Home's kids tile lands here (?card=kidsadd)
  }
  body.push(part(...lockPart));
  // how a parent opens what is locked
  const unlockPart = [];
  const canAsk = tgFull, canOffPin = !!k.methods.totp && !(r.lock && r.lock.recovery_left === 0);   // off only with Authenticator on and its backup codes shown
  unlockPart.push(UI.group(T('auto.kids_methods_title'), UI.note(T('auto.kids_methods_why')),
    kidsMethodRow(T('auto.kids_m_pin'), k.methods.pin, async (on) => {
      if (on && !pin && !await askPin()) return;
      await kidsSave(k, { methods: { ...k.methods, pin: on } });
    }, k.methods.pin && !canOffPin ? T('auto.kids_m_pin_keep') : '', k.methods.pin && !canOffPin),
    el('details', { className: 'adv', open: !!(k.methods.totp || k.methods.ask) }, el('summary', { textContent: T('auto.kids_methods_more') }),   // a parent's PIN first; the rest folded
    kidsMethodRow(T('auto.kids_m_totp'), k.methods.totp, async (on) => {
      if (on) await kidsTotp(); else if (await ask_(T('auto.kids_totp_off_ask'), T('auto.kids_totp_off'))) await kidsSend({ do: 'totp', off: true });
    }, T('auto.kids_m_totp_note')),
    kidsMethodRow(T('auto.kids_m_ask'), k.methods.ask && !!canAsk, async (on) => { await kidsSave(k, { methods: { ...k.methods, ask: on } }); },
      canAsk ? T('auto.kids_m_ask_note') : T('auto.kids_m_ask_none'), !canAsk),
    canAsk ? null : UI.actions(act(T('auto.kids_open_telegram'), () => { $('#aboutbtn').click(); setTimeout(() => { const g = $('#tggroup'); if (g) g.open = true; if (g) g.scrollIntoView({ block: 'center' }); }, 300); })))))
  const left = r.lock && r.lock.recovery_left;
  if (k.methods.totp && left !== undefined) unlockPart.push(UI.group(null, UI.state(T('auto.kids_recovery_left', { n: left })),
    UI.actions(act(T('auto.kids_recovery_new'), async () => { if (!await kidsGate()) return; const res = await kidsRaw({ do: 'recovery_new' }); if (res.recovery) { await showRecovery(res.recovery); loadRules(null, true); } else toast(friendly(res.error)); }))));
  unlockPart.push(UI.actions(act(T(pin ? 'auto.pin_change' : 'auto.pin_first'), () => askPin(), pin ? '' : 'primary')), UI.note(T('auto.kids_lost_phone')));
  if (wait) unlockPart.push(UI.warn(T('auto.kids_waiting', { at: clock(k.wait_until) })));
  const warn = k.methods.totp ? kidsClockWarn(r) : null;
  if (warn) unlockPart.push(UI.warn(warn));
  body.push(part(...unlockPart));
  // open for a while, or lock again at once
  const open = r.unlock_until > r.now;
  body.push(part(UI.group(T('auto.kids_unlock_title'),
    open ? UI.state(T('auto.kids_open_until', { at: clock(r.unlock_until) })) : null,
    open ? null : UI.note(T('auto.kids_unlock_note')),
    UI.chips([...UNLOCKS.map(([m, id]) => ({ label: T(id), fn: () => kidsSend({ do: 'unlock', minutes: m }) })),
      { label: T('auto.kids_open_today'), fn: () => kidsSend({ do: 'unlock', today: true }) }]),
    open ? UI.actions(act(T('auto.kids_lock_now'), () => kidsSend({ do: 'unlock', minutes: 0 }), 'danger')) : null)));
  card.replaceChildren(UI.head(T('auto.kids_title')), ...body.filter(Boolean));
  if (typeof renderFamily === 'function') renderFamily(r);
  renderTotal(r);
  kidsTelegram().then((v) => { if (v !== canAsk && RULES === r) renderKids(r); });
}
const ask_ = (text, ok) => ask(text, { ok, danger: true });

// ---- the total of TV a day ----
function renderTotal(r) {
  const card = $('#totalcard'); if (!card) return;
  const t = r.total || {}, min = t.min || 0, extra = t.extra || 0, used = t.used || 0;
  const days = t.days && t.days.length ? t.days : TOTAL_DAYS_ALL;
  foldSum('totalcard', min ? T('fold.per_day', { time: minsShort(min) }) : T('fold.off'), min > 0);
  const body = [UI.note(T('auto.kids_total_note'))];
  if (min) {
    const all = min + extra, left = Math.max(0, all - used), pct = Math.min(100, Math.round(used * 100 / Math.max(1, all)));
    const meter = el('div', { className: 'bar' + (pct >= 100 ? ' full' : '') }, el('i')); meter.firstChild.style.width = pct + '%';
    body.push(UI.item({ name: T('auto.kids_total_per_day', { time: mins(min) }), sub: T(extra ? 'auto.kids_total_used_extra' : 'auto.kids_total_used', { used: minsShort(used), left: minsShort(left), extra: minsShort(extra) }),
      extra: [meter], acts: [act(T('auto.plus_30_today'), async () => { await kidsSend({ do: 'total_extra', minutes: 30 }); toast(T('auto.kids_plus_done')); }),
        act('✏️', () => totalAsk(days), '', T('auto.edit')),
        act('🗑', async () => { if (await ask_(T('auto.kids_total_drop_ask'), T('auto.drop_limit'))) await kidsSend({ do: 'total', min: 0 }); }, 'danger', T('auto.drop_limit'))] }));
    const dc = el('div', { className: 'fchips schedays' }, ...TOTAL_DAYS_ALL.map((d) => el('button', { type: 'button', className: days.includes(d) ? 'on' : '', textContent: dayName(d), 'aria-pressed': days.includes(d),
      onclick: () => { const next = days.includes(d) ? days.filter((x) => x !== d) : [...days, d].sort(); if (!next.length) return toast(T('auto.kids_total_days_one')); kidsSend({ do: 'total', min, days: next }); } })));
    dc.setAttribute('role', 'group'); dc.setAttribute('aria-label', T('scheddlg.days'));
    body.push(dc);
  } else body.push(UI.actions(act(T('auto.kids_total_set'), () => totalAsk(days), 'primary')));
  card.replaceChildren(UI.head(T('auto.kids_total_title')), ...body);
}
async function totalAsk(days) {
  const v = await askTime(T('auto.kids_total_how_long'), (RULES && RULES.total && RULES.total.min) || 120, 1440);
  if (v) await kidsSend({ do: 'total', min: v, days });
}

// ---- Google Authenticator: the QR to scan, the secret as text, and the first code to prove the phone has it ----
function kidsTotp() {
  return new Promise(async (done) => {
    if (!await kidsGate()) return done(false);
    const secret = base32(crypto.getRandomValues(new Uint8Array(20)));
    const url = otpauthUrl(secret, ($('#name') || {}).textContent);
    const svg = qrSvg(url, 190);
    const code = el('input', { inputMode: 'numeric', maxLength: 6, autocomplete: 'one-time-code', spellcheck: false, 'aria-label': T('kidsdlg.code_label'), placeholder: '123456' });
    const err = el('p', { className: 'warnline', hidden: true });
    let ok = false;
    const send = UI.btn(T('kidsdlg.totp_confirm'), async () => {
      const c = code.value.replace(/\D/g, '');
      if (c.length !== 6) { put(err, 'textContent', T('kidsdlg.err_digits')); put(err, 'hidden', false); return; }
      const res = await kidsRaw({ do: 'totp', secret, code: c, client_now: Date.now() });
      if (res.ok) { ok = true; d.close(); toast(T('kidsdlg.totp_done')); if (res.recovery) showRecovery(res.recovery); return; }
      const why = String(res.error || ''), drift = res.clock ? Math.abs(res.clock.drift_s || 0) : 0;
      put(err, 'textContent', /code_wrong|wrong/.test(why) ? T('kidsdlg.err_wrong') + (drift > 30 ? ' ' + T('auto.kids_clock_drift', { n: drift }) : '') : friendly(why) || T('kidsdlg.err_wrong'));
      put(err, 'hidden', false);
    }, 'primary');
    const d = el('dialog', { className: 'ask' }, el('div', { className: 'card' },
      UI.head(T('kidsdlg.totp_title'), el('button', { type: 'button', className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
      UI.note(T('kidsdlg.totp_step1')),
      el('div', { className: 'qrs' }, svg ? el('div', { className: 'qr' }, svg) : null),
      UI.group(T('kidsdlg.totp_key'), el('code', { className: 'secret', textContent: groupSecret(secret) })),
      UI.note(T('kidsdlg.totp_step2')), UI.line(code, send), err));
    d.addEventListener('close', () => { d.remove(); done(ok); if (RULES) loadRules(null, true); });
    document.body.append(d); d.showModal(); code.focus();
  });
}
// not api(): a wrong code comes back as an error and the dialog explains it in its own place
async function kidsRaw(body) {
  try {
    const r = await fetch('/api/rules', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    let j = {}; try { j = await r.json(); } catch {}
    return { ok: r.ok && !j.error, error: adbWords(r.status, j.error || (r.ok ? '' : r.statusText)), clock: j.clock, recovery: j.recovery };
  } catch { return { ok: false, error: T('core.server_unreachable') }; }
}

// the 8 backup codes, said once: the TV keeps only their hashes
function showRecovery(codes) {
  return new Promise((done) => {
    const text = codes.map((c) => String(c).replace(/(\d{4})(?=\d)/g, '$1 ')).join('\n');
    const copy = UI.btn(T('kidsdlg.rec_copy'), async () => toast(T(await clipCopy(text) ? 'kidsdlg.rec_copied' : 'kidsdlg.rec_copy_fail')));
    const print = UI.btn(T('kidsdlg.rec_print'), () => {
      const w = window.open('', '_blank'); if (!w) return toast(T('kidsdlg.rec_copy_fail'));
      w.document.title = T('kidsdlg.rec_title'); w.document.body.append(el('pre', { textContent: T('kidsdlg.rec_title') + '\n\n' + text })); w.print();
    });
    const d = el('dialog', { className: 'ask' }, el('div', { className: 'card' },
      UI.head(T('kidsdlg.rec_title')), UI.note(T('kidsdlg.rec_note')),
      el('pre', { className: 'secret', textContent: text }),
      UI.actions(copy, print, UI.btn(T('kidsdlg.rec_done'), () => d.close(), 'primary'))));
    d.addEventListener('close', () => { d.remove(); done(); });
    document.body.append(d); d.showModal();
  });
}

if (typeof module !== 'undefined') module.exports = { base32, otpauthUrl, groupSecret };
