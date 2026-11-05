#!/usr/bin/env python3
"""dev/sign/sign_games.py: checks docs/games/games.json and signs it (games.json.sig, Ed25519, base64).

The list tells TV Games what it can get without a new APK: libretro "cores" per system (downloaded on first use, sha256 checked
before they are loaded), featured "web" games, free "homebrew" ROMs, and optional pad "layouts" overrides. Same key and way of
working as dev/sign/sign_providers.py (LAUNCHERS_KEY, default ~/.tv-remote-release/launchers.key; the key is never printed).
Run it after every edit, then commit games.json and games.json.sig together; the APK packs both as the copy used until the
site's is read. Cores are staged by dev/release/cores.sh (the buildbot's nightlies are not pinned: they are kept as assets of the release
tag cores-1). Nothing here uploads: the site is deployed on push.
"""
import json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIST = os.path.join(ROOT, "docs", "games", "games.json")
KEY = os.environ.get("LAUNCHERS_KEY", os.path.expanduser("~/.tv-remote-release/launchers.key"))
LAYOUTS = {"nes", "snes", "gba", "ps", "arcade", "pce", "sms", "md", "web"}                         # the pad's looks (assets/pad/pad.js SYS)
SYSTEMS = {"nes", "snes", "gba", "psx", "arcade", "gb", "gbc", "md", "sms", "gg", "pce"}                               # Systems.kt
ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}
HEX = re.compile(r"^[0-9a-f]{64}$")


GENRES = ("action", "sports", "quiz", "racing", "shooter", "puzzle", "platformer", "party", "arcade", "rpg")   # the library filters; words are the app's strings

def fail(m):
    print("x " + m)
    sys.exit(1)


def https(where, u):
    if not isinstance(u, str) or not u.startswith("https://"):
        fail("%s: %r is not https" % (where, u))


def check():
    d = json.load(open(LIST, encoding="utf-8"))
    if d.get("v") != 1:
        fail("v must be 1")
    for sid, c in d.get("cores", {}).items():
        if sid not in SYSTEMS:
            fail("cores.%s: unknown system (Systems.kt knows %s)" % (sid, sorted(SYSTEMS)))
        if c.get("layout") not in LAYOUTS - {"web"}:
            fail("cores.%s: layout %r unknown" % (sid, c.get("layout")))
        if not c.get("exts") or not all(isinstance(e, str) and e.isalnum() for e in c["exts"]):
            fail("cores.%s: exts" % sid)
        if not c.get("license") or not c.get("name"):
            fail("cores.%s: name and license are needed" % sid)
        if not c.get("abi"):
            fail("cores.%s: no abi" % sid)
        for abi, a in c["abi"].items():
            if abi not in ABIS:
                fail("cores.%s: abi %s unknown" % (sid, abi))
            https("cores.%s.%s" % (sid, abi), a.get("url"))
            if not HEX.match(a.get("sha256", "")):
                fail("cores.%s.%s: sha256 is not 64 hex" % (sid, abi))
            if not isinstance(a.get("size"), int) or not 0 < a["size"] <= 64 << 20:
                fail("cores.%s.%s: size (bytes, up to 64 MB)" % (sid, abi))
    ids = set()
    for w in d.get("web", []):
        if not w.get("id") or w["id"] in ids:
            fail("web: id missing or twice: %r" % w.get("id"))
        ids.add(w["id"])
        if not isinstance(w.get("name"), dict) or not w["name"].get("en"):
            fail("web.%s: name needs at least en" % w["id"])
        u = w.get("url", "")
        if not (u.startswith("https://") or u.startswith("http://")):
            fail("web.%s: url must be http(s)" % w["id"])
        if w.get("pad", "web") not in LAYOUTS:
            fail("web.%s: pad %r unknown" % (w["id"], w.get("pad")))
    ids = set()
    for h in d.get("homebrew", []):
        if not h.get("id") or h["id"] in ids:
            fail("homebrew: id missing or twice: %r" % h.get("id"))
        ids.add(h["id"])
        if h.get("system") not in SYSTEMS:
            fail("homebrew.%s: system %r unknown" % (h["id"], h.get("system")))
        https("homebrew." + h["id"], h.get("url"))
        if not HEX.match(h.get("sha256", "")):
            fail("homebrew.%s: sha256 is not 64 hex" % h["id"])
        for k in ("name", "license", "source"):
            if not h.get(k):
                fail("homebrew.%s: no %s (only clearly redistributable ROMs: GPL / CC / public domain / permission)" % (h["id"], k))
    for kind in ("web", "homebrew"):                                  # "players" (most that can play, 1-4), "min" (needed to start, default 1), web "lobby": false
        for e in d.get(kind, []):
            mx, mn = e.get("players", 4), e.get("min", 1)
            if not (isinstance(mx, int) and isinstance(mn, int) and 1 <= mn <= mx <= 4):
                fail("%s.%s: players / min must be integers with 1 <= min <= players <= 4" % (kind, e.get("id")))
            if e.get("genre", "arcade") not in GENRES:
                fail("%s.%s: genre must be one of %s" % (kind, e.get("id"), ", ".join(GENRES)))
            if "lobby" in e and not isinstance(e["lobby"], bool):
                fail("%s.%s: lobby must be true or false" % (kind, e.get("id")))
    for l, o in d.get("layouts", {}).items():
        if l not in LAYOUTS:
            fail("layouts.%s unknown" % l)
        if not isinstance(o, dict):
            fail("layouts.%s must be an object (buttons, face, sh, mid)" % l)
    return d


def main():
    d = check()
    if not os.path.exists(KEY):
        fail("no key at %s (dev/README.md: the launcher list's key); games.json.sig is left for the maintainer" % KEY)
    subprocess.run(["java", os.path.join(ROOT, "dev", "release", "Sign.java"), "sign", KEY, LIST], check=True)
    print("signed %s: %d cores, %d web, %d homebrew" % (LIST, len(d.get("cores", {})), len(d.get("web", [])), len(d.get("homebrew", []))))


main()
