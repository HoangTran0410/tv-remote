// ---------- Telegram bot (Telegram.kt): TV Setup's switch, token, paired chats and history, in Settings, shown only to the owner ----------
// The bot lives in TV Setup on the TV. The TV's own page asks /api/telegram there; the Phone and Computer apps ask the same thing through
// Manage, which sends it to TV Setup over ADB (the owner's way, as the rules). The token goes to the TV once and is never sent back, only masked.
let tgNow = null, tgAt = 0, tgWant = false, tgPoll = 0, tgChat = null, tgItems = [], tgTotal = 0;
let tgGen = 0, tgReads = 0;                                           // the history: a fresh read (another chat, the poll, another TV) drops older answers; More waits for none in flight
const tgMin = (s) => Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
const TG_KINDS = { on: 'telegram_ui.k_on', off: 'telegram_ui.k_off', limit: 'telegram_ui.k_limit', bed: 'telegram_ui.k_bed', sleep: 'telegram_ui.k_sleep', pair: 'telegram_ui.k_pair' };   // Telegram.ALERTS, in the order /alerts lists them
const tgWhen = (t) => new Date(t).toLocaleString(LOCALE, { dateStyle: 'short', timeStyle: 'short' });
const tgAgo = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return s < 90 ? T('telegram_ui.ago_s', { n: s }) : s < 5400 ? T('telegram_ui.ago_m', { n: Math.round(s / 60) }) : T('telegram_ui.ago_h', { n: Math.round(s / 3600) }); };

function tgCodeLine() {
  const j = tgNow, line = $('#tgcodeline'); if (!line) return;
  const left = j && j.code ? Math.max(0, Math.round(j.code_left - (Date.now() - tgAt) / 1000)) : 0;
  const locked = j && j.locked ? Math.max(0, Math.round(j.locked - (Date.now() - tgAt) / 1000)) : 0;
  const say = locked ? T('telegram_ui.locked', { left: tgMin(locked) }) : left ? T('telegram_ui.code_line', { code: j.code, left: tgMin(left) }) : '';
  put(line, 'textContent', say); put(line, 'hidden', !say);
  if (!left) $('#tgqr').replaceChildren();
}

// Running, token refused, Telegram not reachable, locked: one line, then when it last answered and the last error
function tgStatus(j) {
  const locked = j.locked > 0;
  const st = locked ? T('telegram_ui.st_locked', { left: tgMin(j.locked) })
    : { ok: T('telegram_ui.st_ok'), connecting: T('telegram_ui.st_connecting'), badtoken: T('telegram_ui.st_badtoken'), offline: T('telegram_ui.st_offline'),
      error: T('telegram_ui.st_error', { err: friendly(j.error || '') }) }[j.status] || T('telegram_ui.st_connecting');
  const bad = locked || j.status === 'badtoken' || j.status === 'offline' || j.status === 'error';
  put('#tgstate', 'textContent', [j.bot, j.masked, st].filter(Boolean).join(' · '));
  put('#tgstate', 'className', 'state' + (bad ? ' bad' : ''));
  const last = j.last_ok ? T('telegram_ui.last_ok', { ago: tgAgo(j.now - j.last_ok + (Date.now() - tgAt)) }) : T('telegram_ui.never_ok');
  put('#tgsub', 'textContent', [last, j.error && j.status !== 'ok' ? T('telegram_ui.last_err', { err: friendly(j.error) }) : ''].filter(Boolean).join(' · '));
  const a = $('#tgbotlink'); put(a, 'hidden', !j.bot_link); if (j.bot_link) { a.href = j.bot_link; put(a, 'textContent', j.bot_link.replace('https://', '')); }
}

function tgChatRow(c) {
  const kinds = c.alerts || [];
  const type = c.type === 'private' ? T('telegram_ui.t_private') : c.type ? T('telegram_ui.t_group') : '';
  const sub = [type, T('telegram_ui.chat_sub', { when: tgWhen(c.at) }), c.seen ? T('telegram_ui.chat_seen', { when: tgWhen(c.seen) }) : T('telegram_ui.chat_unseen')].filter(Boolean).join(' · ');
  return UI.item({
    name: c.name, sub,
    extra: [scopeChips(c.scope, (k) => k !== c.scope && tgDo({ do: 'scope', id: c.id, scope: k })),      // what it may do: Scope.kt, as for a browser
      el('p', { className: 'note', textContent: kinds.length ? T('telegram_ui.alerts_on', { list: kinds.map((k) => T(TG_KINDS[k])).join(', ') }) : T('telegram_ui.alerts_none') }),
      UI.chips(Object.keys(TG_KINDS).map((k) => ({ label: T(TG_KINDS[k]), on: kinds.includes(k), fn: () => tgDo({ do: 'alerts', id: c.id, kind: k, on: !kinds.includes(k) }) })))],
    acts: [el('button', { className: 'small', textContent: kinds.length ? T('telegram_ui.mute') : T('telegram_ui.unmute'), onclick: () => tgDo({ do: 'alerts', id: c.id, kind: '', on: !kinds.length }) }),
      el('button', { className: 'small danger', textContent: T('telegram_ui.chat_remove'),
        onclick: async () => { if (await ask(T('telegram_ui.chat_remove_ask', { name: c.name }), { ok: T('telegram_ui.chat_remove'), danger: true })) tgDo({ do: 'forget', id: c.id }); } })],
  });
}

function tgShow(j) {
  tgNow = j; tgAt = Date.now();
  const on = !!(j && j.on), has = !!(j && j.has_token), want = on || tgWant;
  $('#tgpick').querySelectorAll('button').forEach((b) => b.classList.toggle('on', (b.dataset.v === 'on') === want));
  put('#tgbody', 'hidden', !want);
  put('#tgsetup', 'hidden', !(want && !has));
  put('#tgready', 'hidden', !(on && has));
  put('#tgkept', 'hidden', !has);
  if (!(on && has)) return;
  tgStatus(j);
  tgCodeLine();
  $('#tglang').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === (j.lang || '')));
  $('#tgchats').replaceChildren(UI.list(j.chats.map(tgChatRow), T('telegram_ui.chats_none')));
  if (tgChat !== null && !j.chats.some((c) => c.id === tgChat)) tgChat = null;
  tgFilter(j.chats);
}

// a fresh pairing code as a QR: t.me/<bot>?start=<code> opens the bot and /start <code> pairs, like /pair
function tgQr(j) {
  const box = $('#tgqr');
  if (!j.code || !j.link) { box.replaceChildren(); return; }
  box.replaceChildren(qrBlock(T('telegram_ui.qr_title'), T('telegram_ui.qr_note'), j.link));
}

async function tgDo(body) {
  const j = await api('/api/telegram', body); if (!j) return null;
  tgShow(j); if (body.do === 'code') tgQr(j);
  if (body.do === 'clear_history' || body.do === 'forget') tgHist(true);
  return j;
}

// ---- history: newest first, 20 at a time, one chat or all
function tgFilter(chats) {
  const all = [{ label: T('telegram_ui.h_all'), on: tgChat === null, fn: () => { tgChat = null; tgHist(true); tgFilter(chats); } }]
    .concat(chats.map((c) => ({ label: c.name, on: tgChat === c.id, fn: () => { tgChat = c.id; tgHist(true); tgFilter(chats); } })));
  $('#tgfilter').replaceChildren(UI.chips(all));
}

function tgHistRow(h) {
  const res = { ok: T('telegram_ui.r_ok'), scope: T('telegram_ui.r_scope'), error: T('telegram_ui.r_error') }[h.res] || h.res;
  const who = h.cmd.startsWith('alert ') ? T('telegram_ui.h_alert', { kind: T(TG_KINDS[h.cmd.slice(6)]), to: (h.to || []).join(', ') }) : (h.name || '');
  return UI.item({ name: h.cmd.startsWith('alert ') ? who : h.cmd, sub: [tgWhen(h.t), h.cmd.startsWith('alert ') ? '' : who, res].filter(Boolean).join(' · ') });
}

async function tgHist(fresh) {
  if (fresh) tgGen++; else if (tgReads) return;                      // a double-tapped More would ask the same offset twice
  const gen = tgGen, from = fresh ? 0 : tgItems.length;
  const q = '/api/telegram?history=1&offset=' + from + (tgChat !== null ? '&chat=' + tgChat : '');
  tgReads++;
  const j = await api(q, null, true).finally(() => tgReads--);
  if (!j || gen !== tgGen) return;                                    // another chat or TV picked meanwhile: its rows are not these
  tgItems = fresh ? j.items : tgItems.concat(j.items); tgTotal = j.total;
  $('#tghist').replaceChildren(UI.list(tgItems.map(tgHistRow), T('telegram_ui.h_none')));
  put('#tgmore', 'hidden', tgItems.length >= tgTotal);
  put('#tgclear', 'hidden', !tgTotal && !tgItems.length);
}

// While Settings is open the page asks every few seconds: a chat that just paired, or Telegram answering, shows up by itself
function tgLoop() {
  clearTimeout(tgPoll);
  if (!$('#about').open || SCOPE !== 'full') return;
  tgPoll = setTimeout(async () => { const j = await api('/api/telegram', null, true); if (j) { tgShow(j); if (!tgItems.length || tgItems.length <= 20) tgHist(true); } tgLoop(); }, CORE === 'tv' ? 3000 : 5000);
}

async function tgLoad() {
  put('#tggroup', 'hidden', SCOPE !== 'full'); put('#tghint', 'hidden', true);
  if (SCOPE !== 'full') return;
  tgWant = false;
  const j = await api('/api/telegram', null, true);
  // the Phone and Computer apps: TV Setup on the TV missing, too old, or not answering yet -> the hint with its Install / Update button instead of the card
  put('#tggroup', 'hidden', !j); put('#tghint', 'hidden', !!j || CORE === 'tv');
  if (!j) return;
  tgShow(j); tgHist(true);
  tgLoop();
}

$('#aboutbtn').addEventListener('click', tgLoad);
onTvChange(() => { tgNow = null; tgChat = null; tgItems = []; tgGen++; tgTotal = 0; $('#tgqr').replaceChildren(); clearTimeout(tgPoll); if ($('#about').open) tgLoad(); });
setInterval(() => { if ($('#about').open && tgNow) { tgCodeLine(); if (tgNow.on && tgNow.has_token) tgStatus(tgNow); } }, 1000);
$('#tgpick').addEventListener('click', async (e) => {
  const b = e.target.closest('button'); if (!b) return;
  const on = b.dataset.v === 'on';
  if (on === !!(tgNow && tgNow.on) && !tgWant) return;
  if (on && !(tgNow && tgNow.has_token)) { tgWant = true; tgShow(tgNow); $('#tgtoken').focus(); return; }   // no token yet: the steps first
  tgWant = false;
  await tgDo({ do: on ? 'on' : 'off' });
});
$('#tgform').addEventListener('submit', async (e) => {
  e.preventDefault();
  const t = $('#tgtoken').value.trim(); if (!t) return;
  const j = await tgDo({ do: 'token', token: t });
  if (j) { tgWant = false; $('#tgtoken').value = ''; toast(T('telegram_ui.saved')); }
});
$('#tgcode').addEventListener('click', () => tgDo({ do: 'code' }));
$('#tglang').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b && b.dataset.v !== (tgNow.lang || '')) tgDo({ do: 'lang', lang: b.dataset.v }); });
$('#tgmore').addEventListener('click', () => tgHist(false));
$('#tgclear').addEventListener('click', async () => {
  if (await ask(T('telegram_ui.h_clear_ask'), { ok: T('telegram_ui.h_clear'), danger: true })) tgDo({ do: 'clear_history' });
});
$('#tgremove').addEventListener('click', async () => {
  if (!await ask(T('telegram_ui.remove_ask'), { ok: T('telegram_ui.remove_ok'), danger: true })) return;
  tgWant = false; await tgDo({ do: 'remove' });
});
$('#about').addEventListener('close', () => clearTimeout(tgPoll));
// the hint of the Phone and Computer apps: TV Setup there is missing or too old, so the Install / Update question; then the card
$('#tgcheck').addEventListener('click', async () => { if (await setupFor('telegram')) { toast(T('telegram_ui.setup_ok')); tgLoad(); } });
