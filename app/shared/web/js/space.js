// An install did not fit on the TV: one dialog with the numbers, a cache clean that retries the install, and the biggest apps to
// trim (the Apps tab's own actions). files.js calls offerSpace(size, retry) when an install ends for lack of room.
const NO_SPACE = /INSUFFICIENT_STORAGE|No space left|not enough space|storage full:/i   // pm on Android 14 says "Requested internal only, but not enough space";

async function offerSpace(size, retry) {
  put('#spacetitle', 'textContent', T('space.title'));
  $('#spacebody').replaceChildren(el('p', { className: 'muted', textContent: T('health.reading_from_the_tv') }));
  if (!$('#spacedlg').open) $('#spacedlg').showModal();
  await spaceDraw(size, retry, null);
}

// freed: bytes the cache clean just gave back (null: not cleaned now). Room now, or the size unknown after a clean: the install runs again.
async function spaceDraw(size, retry, freed) {
  const r = await api('/api/storage?size=' + size, null, true);
  const dlg = $('#spacedlg');
  if (!dlg.open) return;
  if (!r) { dlg.close(); return; }
  // size unknown (an install from a link) and nothing freed: the same download would only fail again, so the dialog stays
  if (freed !== null && r.ok && (r.need || freed > 0)) { dlg.close(); toast(T('space.cache_done', { size: fmtSize(freed) })); retry(); return; }
  const again = el('button', { type: 'button', className: 'primary', textContent: T('space.try_again'), onclick: () => { dlg.close(); retry(); } });
  const cache = el('button', { type: 'button', textContent: T('space.free_cache'), onclick: async () => {
    cache.disabled = true;
    const c = await api('/api/clean', {});
    if (!c) { cache.disabled = false; return; }
    spaceDraw(size, retry, cleanFreed(c.text) || 0);
  } });
  const parts = [];
  if (r.free != null) parts.push(el('p', { className: 'state', textContent: r.need ? T('space.need', { need: fmtSize(r.need), free: fmtSize(r.free) }) : T('space.have', { free: fmtSize(r.free) }) }));
  if (freed !== null && !r.need) parts.push(el('p', { className: 'note', textContent: T('space.cache_done', { size: fmtSize(freed) }) }));
  if (freed !== null && r.need && r.free != null) parts.push(el('p', { className: 'note', textContent: T('space.still_short', { size: fmtSize(freed), short: fmtSize(r.need - r.free) }) }));
  parts.push(el('div', { className: 'actions' }, cache, again));
  seedMeta(r.apps);
  parts.push(el('h3', { textContent: T('space.biggest') }));
  if (!r.apps.length) parts.push(el('p', { className: 'note', textContent: T('space.no_list') }));
  else parts.push(el('div', { className: 'list' }, ...r.apps.map((a) => spaceRow(a, () => spaceDraw(size, retry, null)))));
  $('#spacebody').replaceChildren(...parts);
}

function spaceRow(a, redraw) {
  const pkg = a.pkg, icon = appIcon(pkg), name = el('span', { textContent: appName(pkg) });
  onMeta(pkg, (m) => { icon.fill(m); put(name, 'textContent', appName(pkg)); });
  const act = (label, path, askText, danger) => el('button', { type: 'button', className: 'small' + (danger ? ' danger' : ''), textContent: label, onclick: async (e) => {
    const b = e.currentTarget;                                        // read before the await: the event's currentTarget is null after it
    if (!await ask(askText, { ok: label, danger: true })) return;
    b.disabled = true;
    const x = await api(path, { pkg });
    if (x) { toast(T('apps.what_name', { what: label, name: appName(pkg) })); appsStale(); }
    redraw();
  } });
  const acts = el('div', { className: 'acts' },
    act(T('apps.clear_data'), '/api/app/clear', T('apps.clear_ask', { name: appName(pkg) })),
    a.user ? act(T('apps.remove'), '/api/app/uninstall', T('apps.uninstall_ask', { name: appName(pkg) }), true)
      : act(T('space.remove_updates'), '/api/app/uninstall', T('space.updates_ask', { name: appName(pkg) }), true));
  return el('div', { className: 'item' }, icon, el('div', { className: 'name' }, name, el('small', { textContent: fmtSize(a.bytes) })), acts);
}
$('#spaceclose').addEventListener('click', () => $('#spacedlg').close());
