#!/usr/bin/env python3
"""dev/sign/sign_feeds.py: checks docs/home/feeds.json and signs it (feeds.json.sig, Ed25519, base64).

The list tells TV Home where its Market and News cards read from: the bank's exchange-rate XML, the gold shops' price lists, a crypto
ticker, the fuel sellers' price pages, and the default RSS feeds per language. Each "kind" is code in app/tvhome (widgets/Market.kt, News.kt); the addresses are here,
so a source that moves or blocks the app is fixed by editing this file, with no new APK. Only https. It is signed with the launcher
list's key (LAUNCHERS_KEY, default ~/.tv-remote-release/launchers.key), as providers.json is.

Run it after every edit, then commit feeds.json and feeds.json.sig together. :home packs both as the copy used until the site's
is read. Nothing here uploads: the site is deployed on push.
"""
import json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIST = os.path.join(ROOT, "docs", "home", "feeds.json")
KEY = os.environ.get("LAUNCHERS_KEY", os.path.expanduser("~/.tv-remote-release/launchers.key"))
KINDS = {"rates": ["vcb"], "gold": ["btmc", "pnj"], "crypto": ["binance"], "fuel": ["saigonpetro", "webgia"]}   # what Market.kt can read


def fail(m):
    print("x " + m)
    sys.exit(1)


def https(o, what):
    if not isinstance(o.get("url"), str) or not o["url"].startswith("https://"):
        fail("%s: url is not https" % what)


def check():
    d = json.load(open(LIST, encoding="utf-8"))
    if d.get("v") != 1:
        fail("v must be 1")
    if not isinstance(d.get("rev"), int):
        fail("rev must be a number (raise it on each edit)")
    for k, kinds in KINDS.items():
        for o in (d[k] if isinstance(d.get(k), list) else [d.get(k) or {}]):
            if o.get("kind") not in kinds:
                fail("%s: unknown kind %r (old apps skip it; add its code first)" % (k, o.get("kind")))
            https(o, k)
    ids = set()
    for n in d.get("news", []):
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,15}", n.get("id", "")) or n["id"] in ids:
            fail("bad or repeated news id: %r" % n.get("id"))
        ids.add(n["id"])
        if not n.get("name") or n.get("lang") not in ("vi", "en"):
            fail("%s: needs a name and lang vi|en" % n["id"])
        https(n, n["id"])
    return d


def main():
    d = check()
    if not os.path.exists(KEY):
        fail("no key at %s (dev/README.md: the launcher list's key)" % KEY)
    subprocess.run(["java", os.path.join(ROOT, "dev", "release", "Sign.java"), "sign", KEY, LIST], check=True)
    print("signed %s: rev %d, %d feeds" % (LIST, d["rev"], len(d["news"])))


main()
