#!/usr/bin/env python3
"""Walks TV Home's tile flows through its real UI on a TV or the emulator, and checks the grid after every step.

  dev/test/home_walk.py <serial> [shots dir]

Each step is what a person does with the remote (a long press opens a tile's menu, rows are picked by their words), then
dev/test/home_tiles.sh checks the drawn grid and every menu against Tiles.kt, and a screenshot is kept. The grid settings are
saved first and put back at the end, so a TV in use is left as it was. The tiles walked are picked from what the TV has (an
app, TV Setup's RAM live widget, a shortcut pinned through Android, else the live widget again), by their names in TV Home's
language. Flows: dev/flows/home-tiles.md.
"""
import base64, json, os, re, subprocess, sys, time

TV = sys.argv[1] if len(sys.argv) > 1 else sys.exit(__doc__)
SHOTS = sys.argv[2] if len(sys.argv) > 2 else '/tmp/home_walk'
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.makedirs(SHOTS, exist_ok=True)
fails, n = [], [0]


def adb(*a, out=False):
    r = subprocess.run(['adb', '-s', TV, *a], capture_output=True)
    return r.stdout if out else r.stdout.decode('utf-8', 'replace')


def key(*k):
    for x in k: adb('shell', 'input', 'keyevent', x); time.sleep(0.35)


def nodes():
    """(words, x, y) of every node on screen with text or a description."""
    adb('shell', 'uiautomator', 'dump', '/sdcard/walk.xml')
    x = adb('shell', 'cat', '/sdcard/walk.xml')
    out = []
    for m in re.finditer(r'<node [^>]*>', x):
        t = re.search(r' text="([^"]*)"', m.group(0)).group(1) or re.search(r'content-desc="([^"]*)"', m.group(0)).group(1)
        b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', m.group(0)).group(1))))
        if t: out.append((t.replace('&amp;', '&'), (b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    return out


def find(words, scroll=True):
    """The middle of the node saying [words]; on Home, the focus walks down the grid until it shows."""
    for i in range(8):
        hit = [(x, y) for t, x, y in nodes() if t == words and 60 < y < 1060]
        if hit: return hit[0]
        if not scroll: break
        key('DPAD_DOWN')
    return None


def tap(words, hold=False, scroll=None):
    p = find(words, scroll=hold if scroll is None else scroll)
    if p is None: raise RuntimeError('not on screen: ' + words)
    adb('shell', 'input', 'swipe', str(p[0]), str(p[1]), str(p[0]), str(p[1]), '1200' if hold else '60')
    time.sleep(1.5)


def home():
    key('BACK', 'BACK', 'HOME'); time.sleep(1.5)


def has(words): return any(t == words for t, _, _ in nodes())


def check(name, ok=True, why=''):
    n[0] += 1
    shot = os.path.join(SHOTS, '%02d-%s.png' % (n[0], name))
    open(shot, 'wb').write(adb('exec-out', 'screencap', '-p', out=True))
    r = subprocess.run([os.path.join(ROOT, 'dev/test/home_tiles.sh'), TV], capture_output=True, text=True, env=dict(os.environ, KEEP='1'))
    bad = [l for l in r.stdout.splitlines() if l.startswith('  - ')]
    state = 'ok' if ok and r.returncode == 0 else 'WRONG'
    print('%-5s %-40s %s %s' % (state, name, why, ' '.join(bad)))
    if state != 'ok': fails.append(name + ': ' + why + ' ' + ' '.join(bad))


def receiver(*extra):
    out = adb('shell', 'am', 'broadcast', '-n', 'dev.tvremote.home/.HomeReceiver', *extra)
    return json.loads(base64.b64decode(re.search(r'data="([^"]*)"', out).group(1)))


GRID = ['dock', 'hidden', 'order', 'tiles', 'folders']
conf = receiver()['config']
saved = {k: conf[k] for k in GRID}
APP_LOCALE = re.search(r'\[(\w\w)', adb('shell', 'cmd', 'locale', 'get-app-locales', 'dev.tvremote.home'))   # Android 13+'s language per app
LANG = conf.get('lang') or (APP_LOCALE and APP_LOCALE.group(1)) or adb('shell', 'getprop', 'persist.sys.locale').strip()[:2] or 'en'
W = json.load(open(os.path.join(ROOT, 'app/shared/i18n/apps/%s/home.json' % LANG), encoding='utf-8'))
LIVE = 'tile:dev.tvremote.setup/ram'                                  # when the grid has no live widget: in + Add on every TV with TV Setup


def tiles():
    out = subprocess.run([os.path.join(ROOT, 'dev/test/home_tiles.sh'), TV, '--json'], capture_output=True, text=True, env=dict(os.environ, KEEP='1')).stdout
    d = json.loads(out); return [t for t in d['tiles'] if t['key'] in d['grid']]


print('saved the grid settings:', ', '.join('%s %d' % (k, len(saved[k])) for k in GRID))


def step(name, fn):
    try: fn()
    except Exception as e:
        fails.append(name + ': ' + str(e)); print('FAIL  %-40s %s' % (name, e))
    home()


def labels(): return {t for t, _, _ in nodes()}


try:
    home()
    grid = tiles()
    if not any(t['kind'] == 'LIVE' for t in grid) and LIVE not in saved['tiles']:   # put back with the rest at the end
        receiver('--es', 'set', base64.b64encode(json.dumps({'tiles': saved['tiles'] + [LIVE], 'order': saved['order'] + [LIVE]}).encode()).decode())
        home(); grid = tiles()
    names = [t['label'] for t in grid]
    grid = [t for t in grid if names.count(t['label']) == 1]          # tiles are found by name: two alike (an app and its shortcut) would mix up
    apps = [t for t in grid if t['kind'] == 'APP' and 'HIDE' in t['acts']]
    APP = ([t for t in apps if t['key'] not in saved['dock']] or apps)[0]['label']   # off the dock first: hiding a docked one empties a slot
    LIVE_NAME = next(t['label'] for t in grid if t['kind'] == 'LIVE')
    PIN = next((t['label'] for t in grid if t['kind'] == 'PIN' and 'FOLDER_PUT' in t['acts']), LIVE_NAME)
    print('lang %s; walking: app "%s", live widget "%s", into a folder "%s"' % (LANG, APP, LIVE_NAME, PIN))
    check('start')
except Exception as e:
    fails.append('getting ready: %r' % e); print('FAIL  getting ready:', repr(e))
try:
    if fails: raise SystemExit

    def hide_app():                                                    # an app: Hide, then + Add > Choose apps brings it back
        tap(APP, hold=True); tap(W['am_g_home'])
        menu = labels()
        check('app menu', W['hide'] in menu and W['remove_from_grid'] not in menu, 'Hide yes, Remove no')
        tap(W['hide']); home()
        check('app hidden', find(APP) is None, APP + ' off the grid')
        tap(W['add'], scroll=True)
        hint = [t for t in labels() if t.endswith(W['add_choose_hint'].split('%1$d ')[1])]
        check('+ Add count', bool(hint), ' '.join(hint))
        tap(W['add_choose']); tap(APP, scroll=True)
        done = [t for t in labels() if t.startswith(W['pk_choose_done'].split(' (')[0])]
        tap(done[0]); home()
        check('app back', find(APP) is not None, APP + ' on the grid again')
    step('hide an app', hide_app)

    def live_widget():                                                 # a live widget: no Hide; Remove; + Add puts it back
        tap(LIVE_NAME, hold=True); tap(W['am_g_home'])
        menu = labels()
        check('live widget menu', W['hide'] not in menu and W['remove_from_grid'] in menu, 'Hide no, Remove yes')
        tap(W['remove_from_grid']); home()
        check('live widget removed', find(LIVE_NAME) is None)
        tap(W['add'], scroll=True); tap(W['add_g_live']); tap(LIVE_NAME); home()
        check('live widget back', find(LIVE_NAME) is not None)
    step('live widget', live_widget)

    def pin_folder():                                                  # an Android pin into a folder and out again
        tap(PIN, hold=True); tap(W['am_g_home'])
        check('pin menu', W['remove_from_grid'] in labels() and W['folder_put'] in labels(), 'Remove + Put in a folder')
        tap(W['folder_put'])
        if W['folder_default'] in labels(): tap(W['folder_default'])
        else: tap(W['folder_new']); tap(W['ok'])                       # the name as it comes: "Folder"
        home(); check('pin in folder', find(PIN) is None, PIN + ' inside "%s"' % W['folder_default'])
        tap(W['folder_default'], scroll=True); tap(PIN, hold=True); tap(W['am_g_home'])
        check('in-folder menu', W['folder_out'] in labels() and W['hide'] not in labels() and W['remove_from_grid'] not in labels(), 'Out yes, Hide / Remove no')
        tap(W['folder_out']); home()
        check('pin out', find(PIN) is not None)
    step('pin and folder', pin_folder)
finally:
    receiver('--es', 'set', base64.b64encode(json.dumps(saved).encode()).decode())
    home()
    now = {k: receiver()['config'][k] for k in GRID}
    print('grid settings put back:', 'same' if now == saved else 'DIFFERENT: ' + json.dumps({k: now[k] for k in GRID if now[k] != saved[k]}, ensure_ascii=False))
    print('screenshots:', SHOTS)
    print('OK: every step' if not fails else 'WRONG (%d):\n  - ' % len(fails) + '\n  - '.join(fails))
    sys.exit(1 if fails else 0)
