// 🎯 What "open this" points at, wherever something can be opened (the quick keys, the app opened at power-on, TV Home's grid).
// One string, the same as Target.kt: a package (an app), "pin:<package>/<id>" (a launcher shortcut) or "tile:<provider>/<id>" (a live
// tile) or "do:controlcenter" (an action of TV Home, for a key). A plain package is how every older config saved it, so it stays valid with no migration. tests/target_cases.json holds the
// table both languages must agree on. The picker that chooses one is in js/quickkeys.js.
const TARGET_PKG = /^[A-Za-z][\w]*(\.[\w]+)+$/;
const TARGET_PIN = /^pin:([A-Za-z]\w*(?:\.\w+)+)\/([A-Za-z0-9][\w.:@-]{0,63})$/;
const TARGET_TILE = /^tile:([A-Za-z]\w*(?:\.\w+)+)\/([a-z][a-z0-9_]{0,15})$/;
const Target = {
  kind(t) {
    t = String(t || '');
    if (!t || t.length > 200) return '';
    if (t === 'do:controlcenter') return 'action';
    if (t.startsWith('pin:')) return TARGET_PIN.test(t) ? 'pin' : '';
    if (t.startsWith('tile:')) return TARGET_TILE.test(t) ? 'tile' : '';
    return TARGET_PKG.test(t) ? 'app' : '';
  },
  valid(t) { return !!Target.kind(t); },
  // the app a target belongs to: itself, the app owning the shortcut, the tile's provider
  pkg(t) { const k = Target.kind(t); return k === 'action' ? 'dev.tvremote.home' : k === 'app' ? t : k === 'pin' || k === 'tile' ? t.slice(t.indexOf(':') + 1, t.indexOf('/')) : ''; },
  id(t) { const k = Target.kind(t); return k === 'pin' || k === 'tile' ? t.slice(t.indexOf('/') + 1) : k === 'action' ? t.slice(3) : ''; },
};
