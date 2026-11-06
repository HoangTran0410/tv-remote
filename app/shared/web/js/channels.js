// 📺 TV Channels (app/tvchannels), the iptv-org player, from here (Channels.kt): put on the TV or updated from the latest release,
// and a channel opened by its name: the app finds it itself (tvchannels://find/<name>), or shows its search when none fits.
let chanNow = null;
onTvChange(() => { chanNow = null; chanCard(); });

async function chanCard() {
  const line = $('#chanstate'), form = $('#chanf'), inst = $('#chaninst');
  if (!line) return;
  const gen = tvGen, r = ADB.state === 'ready' ? await api('/api/channels', null, true) : null;
  if (gen !== tvGen) return;                                          // another TV was picked meanwhile: its own chanCard() draws it
  chanNow = r;
  checkSet('chan', r && { installed: !!r.installed, latest: r.latest });
  const man = $('#chanmanage');
  line.hidden = form.hidden = man.hidden = !(r && r.installed);
  if (r && r.installed) put(line, 'textContent', T('channels.state', { v: r.version }));
  inst.hidden = !r || (r.installed && !r.latest) || (!r.installed && !r.latest);   // nothing to install: no release carries it yet
  if (!inst.hidden) put(inst, 'textContent', r.installed ? T('channels.update', { v: r.latest }) : T('channels.install'));
  lookOffline(inst, !!(r && r.offline), chanCard);
}

async function chanInstall() {
  const n = await ownAppWait(await api('/api/channels/install', {}), 'TV Channels');
  if (n && n.installed && (!chanNow || n.version !== chanNow.version)) toast(T('channels.installed', { v: n.version }));   // the same version: nothing went on
  chanCard();
}

// also what a spoken "mở kênh VTV1" does (auto.js); without ADB the link goes through the remote protocol, as any link
async function chanOpen(name) {
  if (ADB.state !== 'ready') return openOnTv(channelLink(name), name);
  if (await api('/api/channels/open', { q: name })) { toast(T('channels.opening', { name })); setTimeout(refresh, 1500); }
}

// a stream address (.m3u8, .mp4 ...) played by TV Channels (tvchannels://url?u=...), from "Open on TV" (remote.js askLink); the core checks it is a plain http(s) link
async function chanPlay(url) {
  if (!url) return;
  if (await api('/api/channels/play', { url })) { toast(T('channels.playing')); setTimeout(refresh, 1500); }
}

// TV Channels is on the TV but from before the stream link (core says channels:old): one question with the update, then it plays again
async function chanOld(url) {
  if (await ask(T('core.fr_channels_old'), { ok: T('page.update') })) { await chanInstall(); if (url) chanPlay(url); }
}

$('#chaninst').addEventListener('click', chanInstall);
$('#chanf').addEventListener('submit', (e) => { e.preventDefault(); chanOpen($('#chanq').value.trim()); });
