// 📁 Folders on TV Home's app grid, as the page edits them: the twin of app/shared/src/home/kotlin/.../Folders.kt (tests/home_folders_cases.json
// holds the cases both are tested with). Each operation takes the TV's config and returns the change to send (dock, hidden, order,
// tiles, folders: one write, one redraw on the TV), or null when it does not apply. A folder is a grid tile "folder:<id>" in "tiles"
// and "order" holding installed apps and the live widgets and shortcuts on the grid; its apps are off the grid; an emptied folder is deleted.

const FOLDER_MAX = 12, FOLDER_APPS_MAX = 40, FOLDER_TILES_MAX = 24, FOLDER_NAME_MAX = 30;
const homeFolderKey = (id) => 'folder:' + id;
const homeIsFolder = (k) => /^folder:[a-z0-9]{1,8}$/.test(String(k || ''));
const homeFolderMember = (k) => ['app', 'tile', 'pin'].includes(Target.kind(k));   // an app, or a live widget / shortcut already in "tiles" (it stays there)
const homeFolderName = (s) => String(s || '').replace(/\s+/g, ' ').trim().slice(0, FOLDER_NAME_MAX).trim();

function homeFolderState(c) {
  const s = { dock: [...(c.dock || [])], hidden: [...(c.hidden || [])], order: [...(c.order || [])], tiles: [...(c.tiles || [])], folders: {} };
  for (const id of Object.keys(c.folders || {}).sort()) s.folders[id] = { name: c.folders[id].name || '', apps: [...(c.folders[id].apps || [])] };
  return s;
}
function homeFolderPrune(s) {
  for (const id of Object.keys(s.folders)) if (!s.folders[id].apps.length) {
    delete s.folders[id];
    s.tiles = s.tiles.filter((t) => t !== homeFolderKey(id)); s.order = s.order.filter((t) => t !== homeFolderKey(id));
  }
}
function homeFolderTake(s, pkg) {
  const not = (l) => l.filter((x) => x !== pkg);
  s.dock = not(s.dock); s.hidden = not(s.hidden); s.order = not(s.order);
  for (const f of Object.values(s.folders)) f.apps = not(f.apps);
  homeFolderPrune(s);
}
function homeFolderChange(s) { return { dock: s.dock, hidden: s.hidden, order: s.order, tiles: s.tiles, folders: s.folders }; }
const homeFolderClamp = (to, n) => Math.max(0, Math.min(to, n));

const HomeFolders = {
  /** every package that is in a folder */
  members(c) { return new Set(Object.values(c.folders || {}).flatMap((f) => f.apps || [])); },
  folderOf(c, pkg) { return Object.keys(c.folders || {}).sort().find((id) => (c.folders[id].apps || []).includes(pkg)) || null; },
  newId(c) { const used = c.folders || {}; for (;;) { let id = ''; for (let i = 0; i < 6; i++) id += 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(Math.random() * 36)]; if (!used[id]) return id; } },
  canCreate(c) { return Object.keys(c.folders || {}).length < FOLDER_MAX && (c.tiles || []).length < FOLDER_TILES_MAX; },
  create(c, id, name, app) {
    const s = homeFolderState(c);
    if (Object.keys(s.folders).length >= FOLDER_MAX || s.tiles.length >= FOLDER_TILES_MAX || s.folders[id] || !homeIsFolder(homeFolderKey(id))) return null;
    if (!homeFolderMember(app)) return null;
    const at = s.order.indexOf(app);
    homeFolderTake(s, app);
    s.folders[id] = { name: homeFolderName(name), apps: [app] };
    s.tiles.push(homeFolderKey(id));
    s.order.splice(at >= 0 && at <= s.order.length ? at : s.order.length, 0, homeFolderKey(id));
    return homeFolderChange(s);
  },
  rename(c, id, name) {
    const s = homeFolderState(c); if (!s.folders[id]) return null;
    s.folders[id].name = homeFolderName(name); return homeFolderChange(s);
  },
  delete(c, id) {
    const s = homeFolderState(c); const f = s.folders[id]; if (!f) return null;
    const key = homeFolderKey(id);
    let at = s.order.indexOf(key); if (at < 0) at = s.order.length;
    s.order = s.order.filter((k) => k !== key); s.tiles = s.tiles.filter((k) => k !== key); delete s.folders[id];
    s.order.splice(Math.min(at, s.order.length), 0, ...f.apps.filter((a) => !s.order.includes(a)));
    return homeFolderChange(s);
  },
  add(c, id, app, to = Infinity) {
    const s = homeFolderState(c); const f = s.folders[id];
    if (!f || !homeFolderMember(app)) return null;
    if (f.apps.includes(app)) return to === Infinity ? homeFolderChange(s) : HomeFolders.reorder(c, id, app, to);
    if (f.apps.length >= FOLDER_APPS_MAX) return null;
    homeFolderTake(s, app);
    const g = s.folders[id]; if (!g) return null;
    g.apps.splice(homeFolderClamp(to, g.apps.length), 0, app);
    return homeFolderChange(s);
  },
  remove(c, id, app) {
    const s = homeFolderState(c); const f = s.folders[id];
    if (!f || !f.apps.includes(app)) return null;
    f.apps = f.apps.filter((a) => a !== app);
    const at = s.order.indexOf(homeFolderKey(id));
    if (!s.order.includes(app)) s.order.splice(at < 0 ? s.order.length : at + 1, 0, app);
    homeFolderPrune(s);
    return homeFolderChange(s);
  },
  reorder(c, id, app, to) {
    const s = homeFolderState(c); const f = s.folders[id];
    if (!f || !f.apps.includes(app)) return null;
    f.apps = f.apps.filter((a) => a !== app);
    f.apps.splice(homeFolderClamp(to, f.apps.length), 0, app);
    return homeFolderChange(s);
  },
};

if (typeof module !== 'undefined') module.exports = { HomeFolders, homeIsFolder, homeFolderKey };
