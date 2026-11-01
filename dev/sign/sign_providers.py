#!/usr/bin/env python3
"""dev/sign/sign_providers.py: checks docs/channels/providers.json and signs it (providers.json.sig, Ed25519, base64).

The list tells TV Channels where else to find channels besides iptv-org (VieON, ...): each entry's "kind" is code in
app/tvchannels (Providers.kt); its addresses, headers and the "on" switch are here, so a provider that moves or blocks the app is
fixed or turned off by editing this file, with no new APK. It is signed with the launcher list's key (LAUNCHERS_KEY, default
~/.tv-remote-release/launchers.key): both are files of the site that tell the TVs what to fetch.

Run it after every edit, then commit providers.json and providers.json.sig together. The APK packs both as the copy it uses
until the site's is read, so a release also carries the latest list. Nothing here uploads: the site is deployed on push.
"""
import json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIST = os.path.join(ROOT, "docs", "channels", "providers.json")
KEY = os.environ.get("LAUNCHERS_KEY", os.path.expanduser("~/.tv-remote-release/launchers.key"))
KINDS = {"vieon": ["list", "play", "form"], "tv360": ["guide", "channels"], "vtvgo": ["list", "ids", "play", "body"], "streams": ["streams"]} # what each kind's code reads (Providers.kt)


def fail(m):
    print("x " + m)
    sys.exit(1)


def check():
    d = json.load(open(LIST, encoding="utf-8"))
    if d.get("v") != 1:
        fail("v must be 1")
    ids = set()
    for p in d.get("providers", []):
        for k in ("id", "kind", "name", "country"):
            if not isinstance(p.get(k), str) or not p[k]:
                fail("provider without %s: %r" % (k, p.get("id")))
        if p["id"] in ids:
            fail("two providers named " + p["id"])
        ids.add(p["id"])
        if p["kind"] not in KINDS:
            fail("%s: unknown kind %s (old apps skip it; add its code to Providers.kt first)" % (p["id"], p["kind"]))
        for k in KINDS[p["kind"]]:
            if not p.get(k):
                fail("%s: no %s" % (p["id"], k))
        for st in p.get("streams", []):                                       # written here, played as is: the stations' own addresses
            if not st.get("name") or not re.match(r"^https?://\S+$", st.get("url", "")):
                fail("%s: a stream without a name or an http(s) url: %r" % (p["id"], st))
        for k in ("list", "play", "guide"):
            if k in p and not p[k].startswith("https://"):
                fail("%s: %s is not https" % (p["id"], k))
    return d


def main():
    d = check()
    if not os.path.exists(KEY):
        fail("no key at %s (dev/README.md: the launcher list's key)" % KEY)
    subprocess.run(["java", os.path.join(ROOT, "dev", "release", "Sign.java"), "sign", KEY, LIST], check=True)
    print("signed %s: %d providers (%s)" % (LIST, len(d["providers"]), ", ".join(p["id"] + ("" if p.get("on", True) else " off") for p in d["providers"])))


main()
