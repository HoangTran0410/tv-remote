// file explorer, uploads and jobs
// ---------- files ----------
let cwd = '/sdcard/Download', filesLoaded = false, fileItems = [], volumes = [];
let filesGen = 0;                                                      // the newest call wins: a slow folder must not replace the one opened after it
onTvChange(() => { filesGen++; cwd = '/sdcard/Download'; filesLoaded = false; $('#fileslist').replaceChildren(); });
const KINDS = [
  ['🎬', 'video', /\.(mp4|mkv|avi|mov|webm|ts|m4v|wmv|flv)$/i], ['🎵', 'audio', /\.(mp3|flac|wav|m4a|ogg|aac|opus)$/i],
  ['🖼️', 'image', /\.(jpe?g|png|gif|webp)$/i], ['📦', 'apk', /\.(apk|apks|xapk|apkm)$/i], ['🗜️', 'zip', /\.(zip|rar|7z|tar|gz|xz)$/i],
];
const kindOf = (i) => i.dir ? ['📁', 'dir'] : (KINDS.find((k) => k[2].test(i.name)) || ['📄', 'file']);
const join = (name) => cwd.replace(/\/$/, '') + '/' + name;
async function fileAct(path, data, okMsg) { const r = await api(path, data); if (r) { if (okMsg) toast(okMsg); loadFiles(cwd); } return r; }

async function loadFiles(path) {
  const gen = ++filesGen, r = await api('/api/files?path=' + encodeURIComponent(path));
  if (gen !== filesGen) return;
  if (!r) { if (!filesLoaded && path !== '/sdcard') loadFiles('/sdcard'); return; }
  filesLoaded = true; cwd = r.path; fileItems = r.items; volumes = r.volumes || [];
  if (path !== cwd || $('#filesq').dataset.dir !== cwd) { $('#filesq').value = ''; $('#filesq').dataset.dir = cwd; }
  put('#space', 'textContent', r.space && r.items.length ? T('files.free_of_items', { free: fmtSize(r.space.free), total: fmtSize(r.space.total), n: r.items.length }) : r.items.length ? T('files.items_n', { n: r.items.length }) : '');
  renderPlaces(); renderFiles();
}
function renderPlaces() {
  const places = [[T('files.tv_storage'), '/sdcard'], ['Download', '/sdcard/Download'], [T('files.movies'), '/sdcard/Movies'], [T('files.pictures'), '/sdcard/Pictures'], [T('files.music'), '/sdcard/Music'],
    ...volumes.map((v) => ['USB ' + v, '/storage/' + v])];
  const best = places.filter((p) => cwd === p[1] || cwd.startsWith(p[1] + '/')).sort((a, b) => b[1].length - a[1].length)[0];
  $('#places').replaceChildren(...places.map(([label, p]) => el('button', { textContent: label, className: best && best[1] === p ? 'on' : '', onclick: () => loadFiles(p) })));
  const parts = cwd.split('/').filter(Boolean), kids = [];
  parts.forEach((part, i) => {
    if (i) kids.push(el('i', { textContent: '›' }));
    const p = '/' + parts.slice(0, i + 1).join('/');
    kids.push(el('button', { textContent: i === 0 && part === 'sdcard' ? T('files.tv_storage') : part, onclick: () => loadFiles(p) }));
  });
  $('#crumbs').replaceChildren(...kids);
}
function renderFiles() {
  const q = foldText($('#filesq').value.trim()), sort = $('#filessort').value;
  const cmp = sort === 'new' ? (a, b) => b.mtime.localeCompare(a.mtime) : sort === 'big' ? (a, b) => b.size - a.size : (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true });
  const rows = fileItems.filter((i) => foldHas(q, i.name)).sort((a, b) => (b.dir - a.dir) || cmp(a, b)).map((i) => {
    const full = join(i.name), [icon, kind] = kindOf(i);
    const btn = (text, title, fn, cls = '') => el('button', { textContent: text, title, ariaLabel: title, className: 'small ' + cls, onclick: (e) => { e.stopPropagation(); fn(e); } });
    const acts = el('div', { className: 'acts' });
    if (kind === 'apk') acts.append(setLabel(btn('', T('files.install_this_apk'), async (e) => { const b = e.currentTarget; if (await ask(T('files.install_name_ask', { name: i.name }), { ok: T('files.install_button') })) startJob('/api/file/install', { path: full }, T('files.install_name', { name: i.name }), b); }, 'primary'), '📦', T('files.install_button'), true));
    if (['video', 'audio', 'image'].includes(kind)) acts.append(btn('▶ TV', T('files.open_on_tv'), () => api('/api/file/open', { path: full }).then((r) => r && toast(T('files.opened_on_tv', { name: i.name })))));
    acts.append(btn('⋯', T('page.more_actions'), (e) => popover(e.currentTarget, [   // one row on a phone too: the rest behind ⋯, as on the Apps tab
      i.dir ? null : ['⬇', T('files.download_here'), () => el('a', { href: '/api/file?path=' + encodeURIComponent(full), download: i.name }).click()],
      ['✎', T('files.rename'), async () => { const n = await ask(T('files.new_name'), { value: i.name }); if (n && n !== i.name) fileAct('/api/file/rename', { path: full, name: n }, T('files.renamed')); }],
      ['🗑', T('files.delete'), async () => { if (await ask(T(i.dir ? 'files.delete_folder_ask' : 'files.delete_ask', { path: full }), { ok: T('files.delete'), danger: true })) fileAct('/api/file/delete', { path: full }, T('files.deleted')); }, 'danger'],
    ]), 'iconbtn'));
    const open = i.dir ? () => loadFiles(full) : kind === 'image' ? () => { $('#preview img').src = '/api/file?inline=1&path=' + encodeURIComponent(full); $('#preview').showModal(); } : null;
    const row = el('div', { className: 'item' + (open ? ' click' : ''), onclick: open },
      el('div', { className: 'ficon' + (i.dir ? ' dir' : ''), textContent: icon }),
      el('div', { className: 'name' }, el('span', { textContent: i.name }), el('small', { textContent: (i.dir ? T('files.folder_kind') : fmtSize(i.size)) + ' · ' + i.mtime })), acts);
    if (open) { row.tabIndex = 0; row.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target === row) open(); }); }
    return row;
  });
  $('#fileslist').replaceChildren(...(rows.length ? rows : [el('p', { className: 'empty pad', textContent: q ? T('files.nothing_matches') : T('files.empty_folder') })]));
}
$('#filesq').addEventListener('input', renderFiles);
$('#filessort').addEventListener('change', renderFiles);
$('#filesreload').addEventListener('click', () => loadFiles(cwd));
$('#mkdir').addEventListener('click', async () => { const n = await ask(T('files.new_folder_name'), { value: '' }); if (n) fileAct('/api/file/mkdir', { path: join(n.trim()) }, T('files.folder_created')); });
$('#upfile').addEventListener('change', (e) => { [...e.target.files].forEach((f) => upload(f, 'file', cwd)); e.target.value = ''; });
$('#preview').addEventListener('click', () => { $('#preview').close(); $('#preview img').removeAttribute('src'); });
const fc = $('#filescard');
['dragenter', 'dragover'].forEach((ev) => fc.addEventListener(ev, (e) => { if (e.dataTransfer && [...e.dataTransfer.types].includes('Files')) { e.preventDefault(); fc.classList.add('dragging'); } }));
fc.addEventListener('dragleave', (e) => { if (!fc.contains(e.relatedTarget)) fc.classList.remove('dragging'); });
fc.addEventListener('drop', (e) => { e.preventDefault(); fc.classList.remove('dragging'); [...e.dataTransfer.files].forEach((f) => upload(f, 'file', cwd)); });

// ---------- jobs / upload ----------
const jobEls = {};
// The control that started a job shows its progress too: the jobs card sits in a corner, far from where the eyes are.
function mark(src, j) {
  if (!src) return;
  if (j.status === 'running') src.dataset.busy = '⏳ ' + (j.pct || 0) + '%'; else delete src.dataset.busy;
}
// `stop`: what ■ Dừng does for this job (abort the upload, tell the server to end the install or download); without it no button.
// Gives back its row, so a job can put its own buttons there (a launcher's Keep / Go back).
// A job's title and result in the viewer's language from the parts the server sends (kind, name, dest); an id T does not have
// yet, or an older server, keeps the title / msg as sent.
const JOB_T = { download: 'files.job_download', install: 'files.install_name', upload: 'files.job_upload' };
function jobTitle(j) { const k = JOB_T[j.kind]; return k && j.name ? T(k, { name: j.name }) : j.title; }
function jobMsg(j) {
  const how = j.status === 'done' && /^installed (new|update|same|older)$/.exec(j.msg || '');   // Installer.pmInstall: what the install was
  if (how) return { new: T('files.installed_new'), update: T('files.installed_update'), same: T('files.installed_same'), older: T('files.installed_older') }[how[1]];
  const w = j.status === 'done' && j.dest ? T('files.saved_to', { dest: j.dest }) : 'files.saved_to'; return w !== 'files.saved_to' ? w : j.msg; }
function showJob(j, label, stop) {
  let row = jobEls[j.id];
  if (!row) {
    put('#jobscard', 'hidden', false); $('#jobscard').open = true;
    row = jobEls[j.id] = { root: el('div', { className: 'job' }), msg: el('div', { className: 'msg' }), bar: el('i'), stop: null };
    if (stop) row.stop = el('button', { className: 'small', type: 'button', textContent: T('page.stop'), title: T('files.stop_this'), onclick: (e) => { e.currentTarget.disabled = true; row.msg.textContent = T('files.stopping'); stop(); } });
    row.root.append(el('div', { className: 'jobhead' }, el('div', { textContent: label || jobTitle(j) }), row.stop), row.msg, el('div', { className: 'bar' }, row.bar)); $('#jobs').prepend(row.root);
  }
  if (row.stop) row.stop.hidden = j.status !== 'running';
  row.root.className = 'job ' + j.status; row.msg.textContent = j.status === 'cancelled' && !j.said ? T('files.stopped') : jobMsg(j); row.bar.style.width = j.pct + '%';   // said: the job's own words for how it ended
  row.bar.parentElement.classList.toggle('busy', j.status === 'running' && !(j.pct >= 0));   // size unknown: moving, not stuck at 0
  row.status = j.status; row.pct = j.pct;
  // folded away, the title still tells how far things are
  const run = Object.values(jobEls).filter((r) => r.status === 'running' && r.pct >= 0);   // a step without a size (installing) has no %
  put('#jobsum', 'textContent', run.length ? '· ' + Math.round(run.reduce((s, r) => s + (r.pct > 0 ? r.pct : 0), 0) / run.length) + '%' : '');
  return row;
}
// The phone app saves a TV file into its own Download folder itself (Managing.kt) and tells its progress here, in the same list.
window.nativeJob = (j) => {
  showJob(j, j.label);
  if (j.status !== 'running') toast(j.msg);
};
async function trackJob(j, label, after, src, again) {
  const id = j.id, stop = () => api('/api/cancel', { id }, true);    // the server ends the download / install and removes what it had put on the TV
  showJob(j, label, stop); mark(src, j);
  let misses = 0;                                                     // a blip in the network is not the end of the job; ten in a row are
  while (j.status === 'running') {
    await sleep(1000);
    if (stopped) { j = { ...j, status: 'error', msg: T('files.stopped_watching') }; showJob(j, label); break; }
    const r = await api('/api/job?id=' + j.id, null, true);
    if (r) { misses = 0; j = r; showJob(j, label); mark(src, j); continue; }
    if (++misses >= 10) { j = { ...j, status: 'error', msg: T('files.connection_lost') }; showJob(j, label); break; }
  }
  mark(src, j);
  const noSpace = j.status === 'error' && again && /install/.test(j.title || '') && NO_SPACE.test(j.msg);   // asked below, once the row says why
  if (j.status === 'error') { j.msg = friendly(j.msg); showJob(j, label); }
  toast(j.status === 'done' ? T('files.done_msg', { msg: jobMsg(j) }) : j.status === 'cancelled' ? T('files.stopped') : T('files.error_msg', { msg: j.msg }));
  if (j.status === 'done' && after) after(j);
  if (j.status === 'done' && filesLoaded) loadFiles(cwd);
  if (j.status === 'done') { appsStale(); if (/install/.test(j.title)) loadQuick(); }
  if (noSpace) offerSpace(0, again);                                  // the size is unknown here (a link): the numbers, the cache clean, the big apps
}
// TV Home / Channels / Games from the latest release (HomeApi.ownJob), the route's answer [j]: a job in the Progress card, download % then install,
// with Stop. Resolves to the app's new state, or null when it did not end well (the card and a toast said why).
async function ownAppWait(j, name) {
  if (!j) return null;
  if (!j.id) return j;                                                // a core from before the job answered the state at once
  const label = T('tvapps.job', { name }), id = j.id, stop = () => api('/api/cancel', { id }, true);
  const step = (x) => x.status !== 'running' ? x : { ...x, msg: x.msg === 'install' ? T('launcher.step_install') : T('launcher.step_download', { pct: x.pct || 0 }) };
  const show = (x) => { const row = showJob(step(x), label, stop); if (row.stop && x.msg === 'install') row.stop.hidden = true; };   // installing: too late to stop
  show(j);
  for (let misses = 0; j.status === 'running' && misses < 10;) {     // ten failed polls in a row: give up waiting, not the TV
    await sleep(1000); const r = await api('/api/job?id=' + id, null, true);
    if (!r) { misses++; continue; }
    misses = 0; j = r; show(j);
  }
  if (j.status === 'running') { showJob({ ...j, status: 'error', msg: T('tvapps.lost') }, label); return null; }
  if (j.status === 'done') { showJob({ ...j, msg: T('tvapps.done', { name }) }, label); appsStale(); return j.state || null; }
  const msg = j.status === 'cancelled' ? T('files.stopped') : friendly(j.msg);
  showJob({ ...j, msg, said: true, pct: 0 }, label); toast(msg); return null;
}
// What the TV must have free for this file, asked of it before a byte is sent (Manage.space); '' when there is room or the TV did not say.
async function roomError(size, kind, dest) {
  const r = await api('/api/space?size=' + size + (kind === 'file' || kind === 'welcome' ? '&path=' + encodeURIComponent(dest || '') : '&apk=1'), null, true);
  return r && r.ok === false ? r.error : '';                          // the raw code: friendly() words it, offerSpace() reads it
}
async function upload(file, kind, dest, after, src) {
  const uid = 'up' + Math.random().toString(36).slice(2);
  const row = { id: uid, title: T('files.send_name', { name: file.name }), status: 'running', msg: T('files.checking_room'), pct: 0 };
  const xhr = new XMLHttpRequest();
  let stopped = false;
  const stop = () => {                                                // abort the upload; the server drops what arrived, and ends an install under way
    stopped = true; xhr.abort(); api('/api/cancel', { id: uid }, true);
    row.status = 'cancelled'; showJob(row); mark(src, row); toast(T('files.stopped'));
  };
  showJob(row, undefined, stop); mark(src, row);
  const isApk = kind === 'install' || kind === 'installcopy';
  const full = await roomError(file.size, kind === 'file' || kind === 'welcome' ? kind : 'apk', dest);
  if (stopped) return;
  if (full) {
    row.status = 'error'; row.msg = friendly(full); showJob(row); mark(src, row);
    if (isApk) offerSpace(file.size, () => upload(file, kind, dest, after, src));
    return;
  }   // refused before a byte leaves
  row.msg = T('files.sending_to_server'); showJob(row);
  xhr.open('POST', '/api/upload?kind=' + kind + '&uid=' + uid + '&name=' + encodeURIComponent(file.name) + '&dest=' + encodeURIComponent(dest || ''));
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');
  xhr.upload.onprogress = (e) => { if (e.lengthComputable && !stopped) { row.pct = Math.round(e.loaded * (CORE === 'pc' ? 45 : 95) / e.total); row.msg = T('files.sending_of', { done: fmtSize(e.loaded), total: fmtSize(e.total) }); showJob(row); mark(src, row); } };
  xhr.onerror = () => { if (stopped) return; row.status = 'error'; row.msg = T('files.cannot_reach_server'); showJob(row); mark(src, row); };
  xhr.onload = () => {
    if (stopped) return;
    let r = {}; try { r = JSON.parse(xhr.responseText); } catch {}
    if (xhr.status === 410) { row.status = 'cancelled'; showJob(row); mark(src, row); return; }
    if (xhr.status !== 200) {
      const why = r.error || xhr.statusText;
      row.status = 'error'; row.msg = errWords(xhr.status, why); const jr = showJob(row); mark(src, row); needSetup(why);
      if (isApk && NO_SPACE.test(why)) offerSpace(file.size, () => upload(file, kind, dest, after, src));   // pm said no room after the check passed
      const builtin = /builtin app: (\S+)/.exec(why), closed = /copy closed: (\S+)/.exec(why);
      if (builtin && kind === 'install') {                          // a system app of the TV: it cannot be replaced, but it can go beside as another app
        const go = el('button', { type: 'button', className: 'small primary', textContent: T('files.copy_install'), onclick: () => { go.disabled = true; upload(file, 'installcopy', dest, after, src); } });
        jr.root.append(el('div', { className: 'actions' }, go), el('p', { className: 'note', textContent: T('files.copy_note') }));
      } else if (closed) {                                          // the copy closed itself at once: offer to take it away
        const rm = el('button', { type: 'button', className: 'small', textContent: T('files.copy_remove'), onclick: async () => { rm.disabled = true; if (await api('/api/app/uninstall', { pkg: closed[1] })) { rm.remove(); appsStale(); } } });
        jr.root.append(el('div', { className: 'actions' }, rm));
      }
      return;   // a TV Setup that is missing or old becomes the Install / Update question
    }
    jobEls[row.id].root.remove(); delete jobEls[row.id]; trackJob(r, file.name, after, src);
  };
  xhr.send(file);
}
// "Cast" a file: copy it to the TV's Download folder, then have the TV open it with its own viewer or player.
$('#castfile').addEventListener('change', (e) => {
  const f = e.target.files[0]; e.target.value = ''; if (!f) return;
  upload(f, 'file', '/sdcard/Download', async (j) => { const p = /^saved (.+)$/.exec(j.msg || ''); if (await api('/api/file/open', { path: j.dest || (p ? p[1] : '/sdcard/Download/' + f.name) })) toast(T('files.opened_on_tv', { name: f.name })); });
});
async function startJob(path, data, label, src) { if (src) src.dataset.busy = '⏳'; const r = await api(path, data); if (r) trackJob(r, label, undefined, src, /^\/api\/(file\/)?install$/.test(path) ?() => startJob(path, data, label, src) : undefined); else mark(src, {}); }
$('#fetchf').addEventListener('submit', (e) => { e.preventDefault(); const u = $('#fetchurl').value.trim(); if (u) { startJob('/api/fetch', { url: u, dest: '/sdcard/Download' }); $('#fetchurl').value = ''; } });
$('#instf').addEventListener('submit', async (e) => { e.preventDefault(); const u = $('#insturl').value.trim(); if (u && await ask(T('files.install_link_ask', { url: u }), { ok: T('files.install_button') })) { startJob('/api/install', { url: u }, undefined, $('#instf button')); $('#insturl').value = ''; } });
$('#apkfile').addEventListener('change', async (e) => { const f = e.target.files[0]; e.target.value = ''; if (f && await ask(T('files.install_name_ask', { name: f.name }), { ok: T('files.install_button') })) upload(f, 'install', '', undefined, e.target.closest('label')); });


$('#jobsclose').addEventListener('click', (e) => { e.preventDefault(); put('#jobscard', 'hidden', true); });   // the next upload or install brings it back
