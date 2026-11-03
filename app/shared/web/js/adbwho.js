// 🛡️ TV tab › Who may use ADB: the keys the TV allows (core/ontv/AdbAccess.kt, `dumpsys adb`), who is connected now, and the TV's own
// "revoke all". A TV that will not say shows "can't check", never "nobody". Another device's "Allow USB debugging?" is answered in
// the banner at the top (core.js checkAsk / answerAsk): the person picks, nothing is ever allowed for an unknown key by itself.
let adbWhoGen = 0;
onTvChange(() => { adbWhoGen++; const b = $('#adbwho'); if (b) b.replaceChildren(); });

async function adbWhoLoad() {
  const box = $('#adbwho'); if (!box || ADB.state !== 'ready') return;
  const gen = adbWhoGen;
  const r = await api('/api/adb/access', null, true); if (!r || gen !== adbWhoGen) return;
  checkAsk();                                                           // a question on the TV is shown at the top, at once
  const when = (t) => T('setup.used_ago', { ago: agoText(t) });
  const keys = r.keys && r.keys.map((k) => UI.item({
    name: k.name || T('adbwho.unnamed'),
    sub: [k.fp, k.seen ? when(k.seen) : ''].filter(Boolean).join(' · '),
    extra: k.ours ? [el('span', { className: 'ltag', textContent: T('adbwho.this') })] : [],
  }));
  const byIp = r.clients && [...r.clients.reduce((m, c) => m.set(c.address, [...(m.get(c.address) || []), c]), new Map()).values()];   // one row per address
  const now = byIp && byIp.map((cs) => UI.item({
    name: cs[0].loopback ? T('adbwho.on_tv') : T('adbwho.from', { ip: cs[0].address }),
    sub: cs.map((c) => ':' + c.port).join(' '),
  }));
  const revoke = el('button', { className: 'small danger', textContent: T('adbwho.revoke'), title: T('adbwho.revoke_title'),
    onclick: async () => {
      if (!await ask(T('adbwho.revoke_ask'), { ok: T('adbwho.revoke'), danger: true })) return;
      const x = await api('/api/adb/revoke', {}); if (!x) return;
      toast(T(x.result === 'revoked' ? 'adbwho.revoked' : x.result === 'settings' ? 'adbwho.settings' : 'adbwho.failed'));
      setTimeout(adbWhoLoad, 1500);
    } });
  const refresh = el('button', { className: 'small', textContent: T('adbwho.refresh'), onclick: adbWhoLoad });
  box.replaceChildren(UI.card(T('adbwho.title'), { tools: [refresh, revoke] }, UI.note(T('adbwho.note')),
    el('h3', { textContent: T('adbwho.now', { n: now ? now.length : 0 }) }),
    now ? UI.list(now, T('adbwho.none_now')) : UI.warn(T('adbwho.cant_now')),
    el('h3', { textContent: T('adbwho.keys', { n: keys ? keys.length : 0 }) }),
    keys ? UI.list(keys, T('adbwho.no_keys')) : UI.warn(T('adbwho.cant_keys'))));
}
