#!/usr/bin/env python3
"""TV Home + TV Setup smoke test on ONE emulator, driven only by `uiautomator dump` and D-pad keys.

Run through dev/test/home_smoke/home_smoke.sh (it picks the serial, builds/installs on request, runs this with `python3 -I`).

Rules this file keeps (why it is written this way):
  - every move is "dump, look at the focused node, press one key": no blind key chains;
  - DPAD_CENTER is pressed only right after a dump whose FOCUSED node is the one wanted (its bounds and its text / content-desc
    are checked), so a stray focus never opens or toggles something else;
  - nodes are found by text / content-desc (the strings come from app/shared/i18n/apps/{en,vi}/home.json, so the same
    steps run in English and Vietnamese), never by fixed coordinates.
Per screen it records: the focused node on entry (and whether it is the first control of the page), a screenshot, new error /
crash lines of dev.tvremote.* since the step began, frames / janky frames from `dumpsys gfxinfo`, and whether BACK returns to the
previous screen with focus on the row it came from.  Output: <outdir>/results.json + <outdir>/*.png.
"""
import json, os, re, subprocess, sys, time, argparse
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "app", "tvhome", "src", "main", "res")
HOME_PKG, SETUP_PKG = "dev.tvremote.home", "dev.tvremote.setup"
SCREEN = (1920, 1080)
CLIP_MIN = 26                                                         # px at 1080p: one line of 11 sp is ~27; the cut picker names were 20, whole ones 40

ap = argparse.ArgumentParser()
ap.add_argument("outdir")
ap.add_argument("--serial", default="emulator-5610")
ap.add_argument("--lang", default="en", choices=["en", "vi"], help="TV Home's own language setting for this run (restored at the end)")
ap.add_argument("--only", default="", help="home | setup (default both) | doors (the same-job-same-screen check; only when asked)")
ARGS = ap.parse_args()
os.makedirs(ARGS.outdir, exist_ok=True)
SER = ARGS.serial
if SER != "emulator-5610" and not os.environ.get("SMOKE_ANY_SERIAL"):
    sys.exit("refusing to run on %s: this smoke is for the smoke emulator emulator-5610 (SMOKE_ANY_SERIAL=1 to override)" % SER)


def adb(*a, binary=False, timeout=60):
    r = subprocess.run(["adb", "-s", SER, *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


# ---------------------------------------------------------------- strings (en / vi) from the app's own resources
def load_strings(lang):
    p = os.path.join(ROOT, "app", "shared", "i18n", "apps", lang, "home.json")   # TV Home's words, one file per language
    return {k: v for k, v in json.load(open(p, encoding="utf-8")).items() if isinstance(v, str)}


EN, VI = load_strings("en"), load_strings("vi")
STR = EN if ARGS.lang == "en" else {**EN, **VI}


def S(key):
    return STR[key]


# ---------------------------------------------------------------- screen model
class Node:
    def __init__(self, el, parent):
        a = el.attrib
        self.el, self.parent = el, parent
        self.text, self.desc, self.cls = a.get("text", ""), a.get("content-desc", ""), a.get("class", "").split(".")[-1]
        self.focused, self.focusable = a.get("focused") == "true", a.get("focusable") == "true"
        self.enabled = a.get("enabled") != "false"
        self.scrollable = a.get("scrollable") == "true"
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", a.get("bounds", "[0,0][0,0]"))
        self.b = tuple(int(x) for x in m.groups())
        self.children = []

    def center(self):
        return ((self.b[0] + self.b[2]) // 2, (self.b[1] + self.b[3]) // 2)

    def texts(self):
        out = []
        for n in self.walk():
            if n.text:
                out.append(n.text)
            if n.desc:
                out.append(n.desc)
        return out

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()

    def label(self):
        return " | ".join(self.texts()) or self.cls

    def info(self):
        return {"label": self.label()[:90], "class": self.cls, "bounds": list(self.b)}


class Screen:
    def __init__(self, xml):
        self.root = None
        self.nodes = []

        def build(el, parent):
            n = Node(el, parent)
            self.nodes.append(n)
            if parent:
                parent.children.append(n)
            for c in el.findall("node"):
                build(c, n)
            return n
        h = ET.fromstring(xml)
        for el in h.findall("node"):
            self.root = build(el, None)

    def focused(self):
        f = [n for n in self.nodes if n.focused]
        return f[-1] if f else None

    def find(self, labels):
        """text / desc equal to one of the labels (exact), else None"""
        labels = [labels] if isinstance(labels, str) else labels
        hits = [n for n in self.nodes if (n.text and n.text.strip() in labels) or (n.desc and n.desc in labels)]
        # a row's title before another row's second line with the same words ("Same as the TV" / "Tiếng Việt" over the
        # "Tiếng Việt" row when the TV is in Vietnamese): the first text inside its focusable row
        for n in hits:
            row = n
            while row and not row.focusable:
                row = row.parent
            if row is None or next((m for m in self.nodes if m.text and m.text.strip() and self._under(m, row)), None) is n:
                return n
        return hits[0] if hits else None

    @staticmethod
    def _under(m, row):
        while m is not None:
            if m is row:
                return True
            m = m.parent
        return False

    def focus_target(self, labels):
        n = self.find(labels)
        while n and not n.focusable:
            n = n.parent
        return n

    def has(self, labels):
        return self.find(labels) is not None

    def focusables(self):
        return [n for n in self.nodes if n.focusable and n.cls not in ("ScrollView", "FrameLayout") or (n.focusable and n.text)]

    def all_text(self):
        return [n.text for n in self.nodes if n.text.strip()]


def dump():
    for i in range(5):
        adb("shell", "rm", "-f", "/sdcard/smoke.xml")
        adb("shell", "uiautomator", "dump", "/sdcard/smoke.xml")
        x = adb("shell", "cat", "/sdcard/smoke.xml")
        if x.startswith("<?xml"):
            try:
                return Screen(x)
            except ET.ParseError:
                pass
        time.sleep(0.7)
    raise RuntimeError("uiautomator dump failed 5 times")


def key(k, wait=0.55):
    adb("shell", "input", "keyevent", k)
    time.sleep(wait)


class Fail(Exception):
    pass


def inside(inner, outer):
    return inner[0] >= outer[0] - 2 and inner[1] >= outer[1] - 2 and inner[2] <= outer[2] + 2 and inner[3] <= outer[3] + 2


def go_to(labels, scr=None, limit=45):
    """D-pad to the control labelled `labels`; returns a Screen whose FOCUSED node is that control (checked by bounds + text)."""
    scr = scr or dump()
    last = None
    tried_alt = 0
    for _ in range(limit):
        tgt, f = scr.focus_target(labels), scr.focused()
        if tgt is not None and f is not None and f.b == tgt.b:
            return scr
        # focus may sit on an inner node of the target (e.g. the row's text): accept only if its own bounds are within the target
        if tgt is not None and f is not None and inside(f.b, tgt.b) and f.focusable and (scr.find(labels) is not None) and f is tgt:
            return scr
        if f is None:
            k = "DPAD_DOWN"
        elif tgt is None:
            k = "DPAD_DOWN"                                   # off screen: scroll by moving down (focus-checked every step)
            if last == f.b and tried_alt >= 1:
                k = "DPAD_UP"
        else:
            (fx, fy), (tx, ty) = f.center(), tgt.center()
            dy, dx = ty - fy, tx - fx
            vertical = abs(dy) >= abs(dx) if tried_alt == 0 else abs(dy) < abs(dx)
            k = ("DPAD_DOWN" if dy > 0 else "DPAD_UP") if vertical else ("DPAD_RIGHT" if dx > 0 else "DPAD_LEFT")
        before = f.b if f else None
        if os.environ.get("SMOKE_DEBUG"):
            print("         go_to %r: focus=%s target=%s -> %s" % (labels, f.label()[:30] if f else None, tgt.b if tgt else None, k))
        key(k)
        scr = dump()
        nf = scr.focused()
        if (nf.b if nf else None) == before:
            tried_alt += 1
            if tried_alt > 3:
                raise Fail("focus stuck at %s while looking for %r" % (f.label() if f else None, labels))
        else:
            tried_alt = 0
        last = before
    raise Fail("could not focus %r in %d keys" % (labels, limit))


def press_ok(scr, labels):
    """DPAD_CENTER, only if the focused node of the dump we hold is the one wanted."""
    f, tgt = scr.focused(), scr.focus_target(labels)
    if f is None or tgt is None or f.b != tgt.b:
        raise Fail("refusing OK: focus is %s, wanted %r" % (f.label() if f else None, labels))
    key("DPAD_CENTER", 1.1)


# ---------------------------------------------------------------- per-step evidence
RESULTS, SHOT_N = [], [0]


def dev_time():
    return adb("shell", "date", "+%m-%d %H:%M:%S.000").strip()


def app_pids():
    pids = set()
    for p in (HOME_PKG, SETUP_PKG, SETUP_PKG + ":web"):
        for x in adb("shell", "pidof", p).split():
            pids.add(x)
    return pids


def logs_since(ts):
    pids = app_pids()
    crash = [l for l in adb("logcat", "-d", "-b", "crash", "-v", "threadtime", "-T", ts).splitlines() if l.strip() and not l.startswith("-----")]
    err = []
    for l in adb("logcat", "-d", "-b", "main,system", "-v", "threadtime", "-T", ts, "*:E").splitlines():
        m = re.match(r"\S+\s+\S+\s+(\d+)\s+\d+\s+E", l)
        if "dev.tvremote" in l or (m and m.group(1) in pids):
            err.append(l[:220])
    return {"crash": [l[:220] for l in crash if "dev.tvremote" in l or "FATAL" in l], "crash_other": len(crash), "errors": err[:12], "errors_n": len(err)}


def gfx(pkg):
    t = adb("shell", "dumpsys", "gfxinfo", pkg)
    g = lambda pat: (re.search(pat, t) or [None, None])[1]
    return {"frames": int(g(r"Total frames rendered: (\d+)") or 0), "janky": int(g(r"Janky frames: (\d+)") or 0),
            "p90_ms": int(g(r"90th percentile: (\d+)ms") or 0), "p99_ms": int(g(r"99th percentile: (\d+)ms") or 0)}


def shot(name):
    SHOT_N[0] += 1
    fn = "%02d_%s_%s.png" % (SHOT_N[0], ARGS.lang, re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_"))
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(os.path.join(ARGS.outdir, fn), "wb") as fh:
        fh.write(data)
    return fn if len(data) > 5000 else fn + " (BLANK?)"


def static_checks(scr):
    """cheap automatic UI checks on one dump: inverted/off-screen bounds, missing focus, untranslated strings"""
    w = []
    f = scr.focused()
    if f is None:
        w.append("no focused node")
    else:
        if not inside(f.b, (0, 0) + SCREEN) and not (f.b[1] < SCREEN[1] and f.b[3] > 0):
            w.append("focused node off screen %s" % (f.b,))
        if f.b[2] - f.b[0] < 8 or f.b[3] - f.b[1] < 8:
            w.append("focused node has no size %s" % (f.b,))
    for n in scr.nodes:
        if n.text.strip() and (n.b[2] > SCREEN[0] + 2 or n.b[3] > SCREEN[1] + 2):
            w.append("text clipped by the screen %r %s" % (n.text[:30], n.b))
        # cut by its own box: a node's bounds are what is visible of it, so a label squeezed under a picture (the picker's tile
        # names, 2026-11-03) is a sliver. The edges of a
        # scrolling list cut rows on purpose and are skipped
        if n.text.strip() and n.cls in ("TextView", "Button") and 0 < n.b[3] - n.b[1] < CLIP_MIN:
            s = n.parent
            while s and not s.scrollable:
                s = s.parent
            if not (s and (abs(n.b[1] - s.b[1]) <= 2 or abs(n.b[3] - s.b[3]) <= 2)) and 0 < n.b[3] <= SCREEN[1] and n.b[1] >= 0:
                w.append("text cut by its box %r %s (%d px high)" % (n.text[:30], n.b, n.b[3] - n.b[1]))
    # untranslated: in vi, a text equal to an English string whose Vietnamese differs; in en, Vietnamese letters we did not ship
    en_val = {}
    for k, v in EN.items():
        if k in VI and VI[k] != v and len(v) > 3:
            en_val[v.strip()] = k
    vi_marks = re.compile(r"[ăâđêôơưĂÂĐÊÔƠƯàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ]")
    for t in scr.all_text():
        if ARGS.lang == "vi" and t.strip() in en_val:
            w.append("untranslated in vi: %r (string %s)" % (t[:40], en_val[t.strip()]))
        if ARGS.lang == "en" and vi_marks.search(t) and t not in ("Tiếng Việt",) and "Tết" not in t and not re.search(r"https?://", t):
            w.append("Vietnamese text in en: %r" % t[:40])
    return w


class Step:
    """one screen: begin() before the keys that open it, end() once it is open and has been dumped"""
    def __init__(self, name, pkg, expect_first=True):
        self.name, self.pkg, self.expect_first = name, pkg, expect_first
        self.ts = dev_time()
        adb("shell", "dumpsys", "gfxinfo", pkg, "reset")
        self.t0 = time.time()
        self.rec = {"step": name, "pkg": pkg, "ok": True, "problems": []}

    def problem(self, msg, hard=True):
        self.rec["problems"].append(msg)
        if hard:
            self.rec["ok"] = False

    def end(self, scr, first_ignore=("Back", "Quay lại")):
        r = self.rec
        r["open_s"] = round(time.time() - self.t0, 1)
        f = scr.focused()
        r["entry_focus"] = f.info() if f else None
        ctl = [n for n in scr.nodes if n.focusable and n.cls not in ("ScrollView",) and n.desc not in first_ignore and n.b[2] > n.b[0] and n.b[3] > n.b[1]]
        r["first_control"] = ctl[0].info() if ctl else None
        if self.expect_first and f is not None and ctl:
            # "first control of the page": the focused node is the first focusable one in reading order (the Back arrow aside)
            if f.b != ctl[0].b and not inside(f.b, ctl[0].b) and not inside(ctl[0].b, f.b):
                self.problem("entry focus is not the first control: focused %r, first %r" % (f.label()[:50], ctl[0].label()[:50]), hard=False)
        r["shot"] = shot(self.name)
        r["gfx"] = gfx(self.pkg)
        r["logs"] = logs_since(self.ts)
        for w in static_checks(scr):
            self.problem(w, hard=w.startswith("no focused") or w.startswith("untranslated"))
        if r["logs"]["crash"]:
            self.problem("CRASH in log: " + r["logs"]["crash"][0][:150])
        if r["logs"]["errors_n"]:
            self.problem("%d error line(s) from dev.tvremote in logcat: %s" % (r["logs"]["errors_n"], r["logs"]["errors"][0][:150]), hard=False)
        r["visible_text"] = scr.all_text()[:40]
        RESULTS.append(r)
        flag = "ok " if r["ok"] and not r["problems"] else ("WARN" if r["ok"] else "FAIL")
        print("[%s] %-34s focus=%-38r frames=%s janky=%s" % (flag, self.name, (f.label()[:36] if f else None), r["gfx"]["frames"], r["gfx"]["janky"]))
        for p in r["problems"]:
            print("       - " + p)
        return r


def back_check(step_rec, labels_prev, row_labels=None, expect_focus_row=True):
    """BACK once; the previous screen must show (labels_prev) and focus must be back on the row we left"""
    key("BACK", 0.9)
    scr = dump()
    # the Customize list scrolls: its title may be off screen, so "the list" means: no Back arrow, and the row we left is shown
    on_list = (labels_prev is None) or (scr.has(labels_prev) if labels_prev != "LIST" else (not scr.has(S("g_back")) and bool(row_labels) and scr.has(row_labels)))
    rec = {"back_ok": on_list}
    if not rec["back_ok"]:  # noqa
        step_rec["ok"] = False
        step_rec["problems"].append("BACK did not return to the previous screen (%r not seen); now: %s" % (labels_prev, scr.all_text()[:6]))
    elif row_labels and expect_focus_row:
        f, t = scr.focused(), scr.focus_target(row_labels)
        rec["focus_restored"] = bool(f and t and f.b == t.b)
        if not rec["focus_restored"]:
            step_rec["problems"].append("after BACK focus is on %r, not on the row %r we came from" % (f.label()[:40] if f else None, row_labels))
    step_rec.update(rec)
    print("       back -> %s" % ("ok" if rec["back_ok"] else "WRONG SCREEN"))
    return scr


# ---------------------------------------------------------------- TV Home
def home_to_top():
    """BACK until the clock screen shows (never more than 6 keys, each one looked at)"""
    for _ in range(6):
        scr = dump()
        if scr.has(S("settings")) and scr.has(S("inputs")) and scr.find(S("settings")).focusable:
            return scr
        key("BACK", 0.8)
    raise Fail("could not get back to the Home screen")


def open_home():
    adb("shell", "am", "start", "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN")
    time.sleep(2.0)
    return home_to_top()


CUSTOMIZE_PAGES = [("background", ["bg"]), ("look", ["g_look"]), ("layout", ["g_layout"]), ("widgets", ["w_widgets"]),
                   ("system", ["g_system", "w_system"]), ("keys_and_startup", ["g_keys"]), ("language", ["c_lang"])]


def labels(*keys):
    return [S(k) for k in keys]


def run_home():
    print("== TV Home (%s) ==" % ARGS.lang)
    st = Step("home", HOME_PKG, expect_first=False)
    scr = open_home()
    st.end(scr)

    # Settings button -> menu
    st = Step("settings_menu", HOME_PKG)
    scr = go_to(S("settings"), scr)
    press_ok(scr, S("settings"))
    scr = dump()
    if not scr.has(S("customize")):
        st.problem("Settings menu does not list %r" % S("customize"))
    st.end(scr)
    menu_f = S("settings")

    # Customize TV Home -> landing
    st = Step("customize_landing", HOME_PKG)
    scr = go_to(S("customize"), scr)
    press_ok(scr, S("customize"))
    scr = dump()
    rec = st.end(scr)
    landing = labels("customize")
    if not scr.has(landing):
        st.problem("Customize title missing")

    # pages
    for name, keys in CUSTOMIZE_PAGES:
        row = labels(*keys)
        st = Step("customize_" + name, HOME_PKG)
        scr = go_to(row, dump())
        press_ok(scr, row)
        scr = dump()
        if not (scr.has(S("g_back")) or scr.find(S("g_back"))):
            st.problem("no Back control on the page")
        if not scr.has(row):
            st.problem("page title %r not shown" % row)
        r = st.end(scr)
        # Keys & startup has two sub-pages of its own
        if name == "keys_and_startup":
            for sub, skey in (("add_to_home_screen_picker", "c_add_home"), ("quick_keys", "c_quickkeys")):
                srow = labels(skey)
                st2 = Step("customize_keys_" + sub, HOME_PKG, expect_first=(sub != "add_to_home_screen_picker"))   # the picker opens on its first tile, the Search box above it is optional
                s2 = go_to(srow, dump())
                press_ok(s2, srow)
                s2 = dump()
                if s2.has(srow) and not s2.find(srow).focusable and False:
                    pass
                r2 = st2.end(s2, first_ignore=("Back", S("g_back")))
                s2 = back_check(r2, row, srow)
                if not s2.has(S("g_back")):
                    pass
        s = back_check(r, "LIST", row)
    # Manage from phone (QR dialog)
    mrow = labels("mg_row")
    st = Step("customize_manage_from_phone_qr", HOME_PKG)
    scr = go_to(mrow, dump())
    press_ok(scr, mrow)
    scr = dump()
    close = [S("pq_close"), S("err_close")]
    if not scr.has(close):
        st.problem("QR dialog has no Close button")
    elif not scr.focus_target(close) or scr.focused() is None or scr.focused().b != scr.focus_target(close).b:
        st.problem("QR dialog: focus is not on Close", hard=False)
    r = st.end(scr)
    # a QR must be drawn: the dialog shows a URL with a key
    if not any(re.search(r"https?://[\d.]+:\d+", t) for t in scr.all_text()):
        st.problem("QR dialog shows no address")
    back_check(r, "LIST", mrow)
    # leave: landing -> menu -> home
    key("BACK", 0.9)
    scr = dump()
    st_rec = {"step": "leave_to_home", "ok": True, "problems": []}
    if scr.has(S("customize")) and scr.has(S("tv_settings")):
        key("BACK", 0.9)
        scr = dump()
    if not (scr.has(S("settings")) and scr.has(S("inputs"))):
        st_rec["ok"] = False
        st_rec["problems"].append("BACK chain did not end on Home: %s" % scr.all_text()[:6])
    else:
        f = scr.focused()
        st_rec["focus_after_close"] = f.info() if f else None
    RESULTS.append(st_rec)
    print("[%s] leave_to_home" % ("ok " if st_rec["ok"] else "FAIL"))


# ---------------------------------------------------------------- doors: one job, one screen
TIME_RE = re.compile(r"^\d{1,2}:\d{2}$")                             # the clock changes between two dumps; not part of a screen's identity


def signature(scr):
    """(title, labels): the top-most text and the set of every text / content-desc shown (order-insensitive)"""
    items = []
    for n in scr.nodes:
        if n.b[2] <= n.b[0] or n.b[3] <= n.b[1]:
            continue                                                   # inverted / empty bounds: not drawn
        for t in (n.text, n.desc):
            t = t.strip()
            if t and not TIME_RE.match(t):
                items.append((max(n.b[1], 0), n.b[0], t))
    return (min(items)[2] if items else "", frozenset(t for _, _, t in items))


def front():
    t = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ u\d+ ([\w.]+)/([\w.$]+)", t)
    return (m.group(1), m.group(2).lstrip(".")) if m else ("?", "?")


def doors_home():
    """HOME, then BACK until the Home screen shows (its gear, named Quick settings, and the top bar; the old home_to_top looks for the
    Settings / Inputs buttons that this build no longer has)"""
    adb("shell", "am", "start", "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN")
    time.sleep(2.0)
    for _ in range(6):
        scr = dump()
        g = scr.find(S("cc_title"))
        if g is not None and g.focusable and not scr.has(S("el_edit_hint")):   # edit mode shows the same bar: BACK is "done"
            return scr
        key("BACK", 0.8)
    raise Fail("could not get back to the Home screen")


def home_customize():
    """Home -> the gear (Quick settings = the Control Center) -> its Customize tile: the in-Home door"""
    scr = go_to(S("cc_title"), doors_home()); press_ok(scr, S("cc_title"))
    time.sleep(1.0)
    scr = go_to(S("cc_customize"), dump()); press_ok(scr, S("cc_customize"))
    time.sleep(1.2)
    return dump()


def menu_key_customize():
    """Menu key on a tile -> the tile's menu -> Customize TV Home (the first tile of the grid)"""
    scr = doors_home()
    f = scr.focused()
    if f is None or f.b[1] < 500:
        scr = go_to(S("add"), scr)                                      # any grid tile; the Add tile has no menu, so go one up
        key("DPAD_UP")
        scr = dump()
    key("KEYCODE_MENU", 1.2)
    scr = go_to(S("customize"), dump()); press_ok(scr, S("customize"))
    time.sleep(1.2)
    return dump()


def keys_page_door(scr, label):
    scr = go_to(S("g_keys"), scr); press_ok(scr, S("g_keys"))
    scr = go_to(label, dump()); press_ok(scr, label)
    time.sleep(0.8)
    return dump()


def dream_settings():
    adb("shell", "am", "start", "-n", HOME_PKG + "/.DreamSettings")
    time.sleep(2.5)
    return dump()


def am_home_action(action, over=None):
    """TV Home's own action, from Home (or over another app: the Control Center's case)"""
    doors_home()
    if over:
        adb("shell", "am", "start", "-a", over)
        time.sleep(2.0)
    adb("shell", "am", "start", "-n", HOME_PKG + "/.HomeActivity", "-a", HOME_PKG + "." + action)
    time.sleep(2.5)
    return dump()


def control_center_customize():
    adb("shell", "am", "start", "-a", "android.settings.SETTINGS")     # any app in front of it
    time.sleep(2.0)
    adb("shell", "am", "start", "-n", HOME_PKG + "/.ControlCenterActivity")
    time.sleep(2.5)
    scr = go_to(S("cc_customize"), dump()); press_ok(scr, S("cc_customize"))
    time.sleep(1.2)
    return dump()


def grid_add_tile():
    scr = go_to(S("add"), doors_home()); press_ok(scr, S("add"))
    return dump()


def run_doors():
    print("== doors: one job, one screen (%s) ==" % ARGS.lang)
    jobs = [
        ("add", [("grid + Add tile", grid_add_tile),
                 ("Customize > Keys & startup > Add to home screen", lambda: keys_page_door(home_customize(), S("c_add_home"))),
                 ("DreamSettings > Keys & startup > Add to home screen", lambda: keys_page_door(dream_settings(), S("c_add_home"))),
                 ("am start -a ADD", lambda: am_home_action("ADD"))]),
        ("customize", [("Home > gear (Quick settings) > Customize", home_customize),
                       ("Menu key on a tile > Customize TV Home", menu_key_customize),
                       ("Control Center over Settings > Customize", control_center_customize),
                       ("am start -a CUSTOMIZE", lambda: am_home_action("CUSTOMIZE")),
                       ("am start -a CUSTOMIZE over Settings", lambda: am_home_action("CUSTOMIZE", over="android.settings.SETTINGS"))]),
        ("edit", [("Customize > Keys & startup > Edit home screen", lambda: keys_page_door(home_customize(), S("el_edit"))),
                  ("DreamSettings > Keys & startup > Edit home screen", lambda: keys_page_door(dream_settings(), S("el_edit"))),
                  ("am start -a EDIT", lambda: am_home_action("EDIT"))]),
        ("quick_keys", [("Customize > Keys & startup > Quick keys", lambda: keys_page_door(home_customize(), S("c_quickkeys"))),
                        ("DreamSettings > Keys & startup > Quick keys", lambda: keys_page_door(dream_settings(), S("c_quickkeys"))),
                        ("am start -a QUICK_KEYS", lambda: am_home_action("QUICK_KEYS"))]),
    ]
    for job, doors in jobs:
        sigs = []
        for door, fn in doors:
            st = Step("doors_%s_%s" % (job, re.sub(r"[^a-z0-9]+", "_", door.lower()).strip("_")[:40]), HOME_PKG, expect_first=False)
            try:
                scr = fn()
            except Fail as e:
                st.problem("door not reached: %s" % e)
                st.rec["visible_text"] = []
                RESULTS.append(st.rec)
                print("[FAIL] %s: %s" % (st.name, e))
                sigs.append((door, None))
                continue
            pkg, act = front()
            sg = signature(scr)
            st.rec.update({"door": door, "front": pkg + "/" + act, "title": sg[0], "labels": sorted(sg[1])})
            if pkg != HOME_PKG:
                st.problem("in front: %s/%s, not TV Home" % (pkg, act))
            elif act.endswith("DreamSettings") and not door.startswith("DreamSettings"):
                st.problem("left in DreamSettings", hard=False)
            elif act.endswith("DreamSettings"):
                st.problem("still in DreamSettings after the door (the job's screen is TV Home's)", hard=False)
            st.end(scr)
            print("       front=%s/%s title=%r labels=%d" % (pkg, act, sg[0], len(sg[1])))
            sigs.append((door, sg))
        base = next(((d, s) for d, s in sigs if s), None)
        rec = {"step": "doors_%s_same_screen" % job, "ok": True, "problems": [], "doors": [d for d, _ in sigs]}
        for d, s in sigs:
            if s is None:
                rec["ok"] = False
                rec["problems"].append("%s: not reached" % d)
            elif base and s != base[1]:
                rec["ok"] = False
                rec["problems"].append("%s differs from %s: title %r vs %r; only in %s: %s; only in %s: %s" % (
                    d, base[0], s[0], base[1][0], d, sorted(s[1] - base[1][1]), base[0], sorted(base[1][1] - s[1])))
        RESULTS.append(rec)
        print("[%s] %s: %d doors, one screen?" % ("ok " if rec["ok"] else "FAIL", rec["step"], len(sigs)))
        for p in rec["problems"]:
            print("       - " + p)
    doors_home()


def set_language_by_customize(row, target, leave_lang):
    """--only doors: TV Home's language through the Customize action (the Settings button the old setter presses is gone); row and
    target are the labels in the language TV Home shows now. Focus-checked like every step."""
    adb("shell", "am", "start", "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN")
    time.sleep(2.0)
    adb("shell", "am", "start", "-n", HOME_PKG + "/.HomeActivity", "-a", HOME_PKG + ".CUSTOMIZE")
    time.sleep(2.5)
    scr = go_to(row, dump()); press_ok(scr, row)
    scr = go_to(target, dump()); press_ok(scr, target)
    time.sleep(0.8)
    adb("shell", "am", "start", "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN")
    time.sleep(1.5)


def set_home_language(target_native):
    """set TV Home's own language through its UI (focus-checked); target_native is the row text, e.g. 'Tiếng Việt'"""
    adb("shell", "am", "start", "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN")   # not open_home(): that waits for the labels of --lang, which is not set yet
    time.sleep(2.0)
    scr = home_to_top_any()
    for lab_settings, lab_customize, lab_lang in ((EN["settings"], EN["customize"], EN["c_lang"]), (VI.get("settings", ""), VI.get("customize", ""), VI.get("c_lang", ""))):
        if scr.has(lab_settings):
            break
    scr = go_to(lab_settings, scr); press_ok(scr, lab_settings)
    scr = go_to(lab_customize, dump()); press_ok(scr, lab_customize)
    scr = go_to(lab_lang, dump()); press_ok(scr, lab_lang)
    scr = go_to(target_native, dump()); press_ok(scr, target_native)
    time.sleep(0.8)
    home_to_top_any()


def home_to_top_any():
    for _ in range(6):
        scr = dump()
        if scr.has([EN["settings"], VI.get("settings", "?")]) and scr.has([EN["inputs"], VI.get("inputs", "?")]):
            return scr
        key("BACK", 0.8)
    return dump()


# ---------------------------------------------------------------- TV Setup
def allow_usb_prompt(scr):
    """the system "Allow USB debugging?" for the emulator's own ADB: allow it (this is the test emulator), focus-checked"""
    if not scr.has("Allow USB debugging?"):
        return scr
    # AGENTS.md: Allow is only ever pressed for a key we know. On the smoke emulator the only asker is TV Setup's own ADB link, but that is
    # an assumption, so it is opt-in (SMOKE_ALLOW_USB=1); without it the prompt is left standing and the ADB screens are tested greyed out.
    if not os.environ.get("SMOKE_ALLOW_USB"):
        return scr
    scr = go_to("Allow", scr)
    press_ok(scr, "Allow")
    time.sleep(4)
    return dump()


def run_setup():
    print("== TV Setup ==")
    adb("shell", "am", "force-stop", SETUP_PKG)       # a known start: no Manage page left on top of the main screen
    time.sleep(1)
    adb("shell", "am", "start", "-n", SETUP_PKG + "/.MainActivity")
    time.sleep(0.3)
    st = Step("setup_main", SETUP_PKG, expect_first=False)
    adb("shell", "am", "start", "-n", SETUP_PKG + "/.MainActivity")
    time.sleep(2.5)
    scr = dump()
    scr = allow_usb_prompt(scr)
    scr = dump()
    r = st.end(scr)
    f = scr.focused()
    cards = [n for n in scr.nodes if n.focusable and n.cls == "LinearLayout" and n.b[1] > 300 and n.b[1] < 560]
    r["cards"] = [c.label()[:60] for c in cards]
    if cards and f and f.b != cards[0].b:
        st.problem("main screen opens with focus on %r, not on the first action card %r" % (f.label()[:40], cards[0].label()[:40]), hard=False)
    r["problems"] = st.rec["problems"]
    skip = ("Update",)            # Update asks GitHub and can install a release: not pressed in a smoke
    def main_screen(scr):
        return scr.has("TV Setup") and scr.has("ACTIONS") and not scr.has("Reconnect") and not scr.has("Close")   # a dialog still open over it is not "back on the main screen"

    def back_to_main(rec, limit=3):
        """BACK until TV Setup's own screen shows; the count goes in the record (one is expected, more is a warning)"""
        n = 0
        scr = dump()
        while not main_screen(scr) and n < limit:
            key("BACK", 1.8)                      # the main screen is slow to come back over the Manage page: a dump too early saw the old one
            n += 1
            scr = dump()
            if not main_screen(scr):
                time.sleep(1.5)
                scr = dump()
            print("       back #%d shows: %s | top: %s" % (n, scr.all_text()[:4], [l.strip()[:90] for l in adb("shell", "dumpsys", "activity", "activities").splitlines() if "topResumedActivity" in l or "Hist  #" in l][:4]))
        rec["backs_to_main"] = n
        if not main_screen(scr):
            # soft: seen once with the Manage page opened over the "Look at the TV" prompt (BACK, BACK landed on Home): say so, relaunch, go on
            rec["problems"].append("BACK x%d left TV Setup (now: %s); relaunched it" % (n, scr.all_text()[:3]))
            adb("shell", "am", "start", "-n", SETUP_PKG + "/.MainActivity")
            time.sleep(2.5)
            scr = dump()
            if not main_screen(scr):
                raise Fail("TV Setup's main screen does not come back")
        elif n > 1:
            rec["problems"].append("needed %d BACK presses to return to the main screen" % n)
        time.sleep(2.0)                                # seen: the dump said "main" while the activity was still closing, and TV Setup then ended on Home
        scr = dump()
        if not main_screen(scr):
            rec["problems"].append("TV Setup's main screen vanished right after BACK (Home showed); relaunched it")
            adb("shell", "am", "start", "-n", SETUP_PKG + "/.MainActivity")
            time.sleep(2.5)
            scr = dump()
        print("       back x%d -> %s" % (n, "ok" if main_screen(scr) else "WRONG SCREEN"))
        return scr

    for c in cards:
        t = c.texts()[0] if c.texts() else ""
        if not t:
            continue
        if any(t.startswith(x) for x in skip):
            RESULTS.append({"step": "setup_" + t, "ok": True, "skipped": "not pressed (can install an update)", "problems": []})
            print("[skip] setup_%s" % t)
            continue
        if not c.enabled:
            RESULTS.append({"step": "setup_" + t, "ok": True, "skipped": "disabled card (needs ADB)", "problems": []})
            print("[skip] setup_%s (disabled: needs ADB)" % t)
            continue
        st = Step("setup_" + t, SETUP_PKG, expect_first=False)
        scr = go_to(t, dump())
        press_ok(scr, t)
        time.sleep(1.5)
        scr = allow_usb_prompt(dump())
        r = st.end(scr)
        r["focusables"] = [n.label()[:50] for n in scr.nodes if n.focusable and n.cls != "ScrollView"][:20]
        back_to_main(r)
    # right column: web remote toggle (pressed twice: restored), paired devices
    for lab_re in (r"Web remote: (On|Off)", r"Paired devices"):
        scr = dump()
        node = next((n for n in scr.nodes if re.match(lab_re, n.text)), None)
        if node is None:
            RESULTS.append({"step": "setup_" + lab_re, "ok": False, "problems": ["control not found"]})
            continue
        lab = node.text
        st = Step("setup_" + re.sub(r"\W+", "_", lab_re.split(":")[0]).strip("_").lower(), SETUP_PKG, expect_first=False)
        scr = go_to(lab, scr)
        press_ok(scr, lab)
        scr = dump()
        r = st.end(scr)
        if lab_re.startswith("Web"):
            new = next((n.text for n in scr.nodes if n.text.startswith("Web remote")), None)
            r["toggle"] = [lab, new]
            if new == lab:
                st.problem("toggle did not change: %r" % lab)
            scr = go_to(new or lab, dump())
            press_ok(scr, new or lab)                    # restore
            scr = dump()
            back = next((n.text for n in scr.nodes if n.text.startswith("Web remote")), None)
            if back != lab:
                st.problem("toggle not restored: %r != %r" % (back, lab))
        else:
            back_check(r, "TV Setup", None, expect_focus_row=False)


def main():
    try:
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        if ARGS.only in ("", "home", "doors"):
            if ARGS.lang == "vi":
                if ARGS.only == "doors":
                    set_language_by_customize(EN["c_lang"], "Tiếng Việt", "vi")
                else:
                    set_home_language("Tiếng Việt")
            try:
                run_doors() if ARGS.only == "doors" else run_home()
            finally:
                if ARGS.lang == "vi":
                    try:
                        if ARGS.only == "doors":
                            set_language_by_customize(VI["c_lang"], VI["c_lang_tv"], "en")
                        else:
                            set_home_language(VI["c_lang_tv"])
                    except Exception as e:
                        print("!! could not restore the language to 'Same as the TV':", e)
        if ARGS.only in ("", "setup") and ARGS.lang == "en":       # TV Setup follows the TV's locale, not TV Home's own setting: its labels here are English
            run_setup()
    except Fail as e:
        print("!! STOPPED:", e)
        RESULTS.append({"step": "aborted", "ok": False, "problems": [str(e)]})
        try:
            shot("aborted")
        except Exception:
            pass
    bad = [r for r in RESULTS if not r.get("ok", True)]
    warn = [r for r in RESULTS if r.get("ok", True) and r.get("problems")]
    out = {"serial": SER, "lang": ARGS.lang, "steps": RESULTS, "failed": len(bad), "warned": len(warn),
           "janky_total": sum(r.get("gfx", {}).get("janky", 0) for r in RESULTS), "frames_total": sum(r.get("gfx", {}).get("frames", 0) for r in RESULTS)}
    with open(os.path.join(ARGS.outdir, "results-%s.json" % ARGS.lang), "w") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
    print("\n%d steps, %d failed, %d with warnings -> %s" % (len(RESULTS), len(bad), len(warn), os.path.join(ARGS.outdir, "results-%s.json" % ARGS.lang)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
