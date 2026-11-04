#!/usr/bin/env python3
"""dev/sign/sign_voice.py: checks docs/voice/phrases.json, signs it (phrases.json.sig, Ed25519, base64) and copies both into
app/shared/web/voice/ (the copy packed in every app and served to the page; tests keep the two equal).

The list holds the WORDS of the spoken commands (Voice.kt, js/voice.js): key phrases, verbs, app aliases, channel names, fillers.
What a key or verb does is code, so a phrase can only be added to an action that exists. Apps read the site's list first (else GitHub,
else the copy kept, else the packed one), so a new phrase reaches them with no update. Signed with the launcher list's key
(LAUNCHERS_KEY, default ~/.tv-remote-release/launchers.key). Run after every edit; commit the files together. Nothing here uploads.
"""
import json, os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIST = os.path.join(ROOT, "docs", "voice", "phrases.json")
PACK = os.path.join(ROOT, "app", "shared", "web", "voice")
KEY = os.environ.get("LAUNCHERS_KEY", os.path.expanduser("~/.tv-remote-release/launchers.key"))
KEYS = {"HOME", "BACK", "MUTE", "PLAY_PAUSE", "NEXT", "PREV", "CH_UP", "CH_DOWN", "SLEEP", "WAKE", "SETTINGS", "OK", "UP", "DOWN", "LEFT", "RIGHT", "MENU"}  # what Voice.kt can press by these names
PARTS = {"fillers": None, "keys": None, "volume": ("up", "down"), "timer": ("set", "cancel", "minutes", "hours"),
         "open": ("verbs", "noise"), "close": ("verbs",), "channel": ("words", "verbs", "tune"),
         "search": ("verbs",)}              # "tìm X" / "search X": looked up in the app the person picked (Voice.Prefs), else the TV's own search


def fail(m):
    print("x " + m)
    sys.exit(1)


def strs(a, what):
    if not isinstance(a, list) or not all(isinstance(x, str) and x.strip() for x in a):
        fail(what + " must be a list of words")


def check():
    d = json.load(open(LIST, encoding="utf-8"))
    if d.get("v") != 1 or not isinstance(d.get("rev"), int):
        fail("v must be 1 and rev a number (raise rev with every edit: apps ignore a list older than the one they hold)")
    for l, b in d["lang"].items():
        for part, sub in PARTS.items():
            if part not in b:
                fail("%s: no %s" % (l, part))
            if part == "keys":
                for k, v in b[part].items():
                    if k not in KEYS:
                        fail("%s: key %s has no code (Voice.kt / Scenes.KEYS) yet" % (l, k))
                    strs(v, "%s.keys.%s" % (l, k))
            elif sub is None:
                strs(b[part], l + "." + part)
            else:
                for s in sub:
                    strs(b[part].get(s, []), "%s.%s.%s" % (l, part, s))
    for l, b in d["lang"].items():
        strs(b.get("connect", []), l + ".connect")             # "on", "trên": X <connect> app = search X in that app (Voice.kt, js/voice.js)
    for a in d["apps"]:
        if not a.get("label") or not a.get("names"):
            fail("app without label / names: %r" % a)
        q = a.get("search")
        if q is not None and not (re.fullmatch(r"https://[^ ]*\{q\}[^ ]*", q) and q.count("{q}") == 1):
            fail("%s: search must be an https link with one {q}" % a["label"])
        for p in a.get("pkgs", []):
            if not re.fullmatch(r"[A-Za-z0-9_.]+", p):
                fail("bad package " + p)
    for r in d["channel_names"]:
        re.compile(r)
    for l, sp in d.get("spoken", {}).items():                # Phrases.spoken: what an offline recognizer listens for
        if l not in d["lang"]:
            fail("spoken.%s: no such language in lang" % l)
        for k, v in sp.get("words", {}).items():
            if k != k.lower() or not all(x.strip() for x in v.split("|")):
                fail("spoken.%s.words.%s: a folded word -> its accented ways, | between them" % (l, k))
        for w in sp.get("spell", []):
            if not all(ch in sp.get("letters", {}) for ch in w):
                fail("spoken.%s.spell: '%s' has a letter not in letters" % (l, w))
        strs(sp.get("numbers", ["x"]), "spoken.%s.numbers" % l)
    acts = d.get("settings", {})                             # Settings pages: id -> the intent actions tried in order (Voice.Command.Setting)
    for i, al in acts.items():
        strs(al, "settings." + i)
        for a in al:                                         # the only actions the TV is ever asked to start: a settings screen or the pairing screen
            if not re.fullmatch(r"android\.settings\.[A-Z_]+|com\.google\.android\.intent\.action\.CONNECT_INPUT", a):
                fail("settings.%s: action %s is not a settings page" % (i, a))
    for l, b in d["lang"].items():
        for i, ps in b.get("settings", {}).items():
            if i not in acts:
                fail("%s.settings.%s: no actions for it in settings" % (l, i))
            strs(ps, "%s.settings.%s" % (l, i))
    taken = {}
    for l, b in d["lang"].items():                           # one phrase, one page; and none that is also a key ("cai dat" is the SETTINGS key)
        for i, ps in b.get("settings", {}).items():
            for w in ps:
                if w != w.lower() or taken.setdefault(w, i) != i:
                    fail("'%s' is both settings.%s and settings.%s (or not lower case)" % (w, i, taken[w]))
                if any(w in b2["keys"].get(k, []) for b2 in d["lang"].values() for k in b2["keys"]):
                    fail("'%s' is a key phrase already" % w)
    seen = {}
    for l, b in d["lang"].items():                           # one phrase, one key
        for k, v in b["keys"].items():
            for w in v:
                if seen.setdefault(w, k) != k:
                    fail("'%s' is both %s and %s" % (w, seen[w], k))
    return d


def main():
    d = check()
    if not os.path.exists(KEY):
        fail("no key at %s (dev/README.md: the launcher list's key)" % KEY)
    subprocess.run(["java", os.path.join(ROOT, "dev", "release", "Sign.java"), "sign", KEY, LIST], check=True)
    os.makedirs(PACK, exist_ok=True)
    for f in ("phrases.json", "phrases.json.sig"):
        shutil.copyfile(os.path.join(os.path.dirname(LIST), f), os.path.join(PACK, f))
    print("signed %s: rev %d, %d apps, %d key phrases; copied to app/shared/web/voice/" % (LIST, d["rev"], len(d["apps"]), sum(len(v) for b in d["lang"].values() for v in b["keys"].values())))


main()
