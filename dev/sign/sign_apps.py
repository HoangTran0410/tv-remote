#!/usr/bin/env python3
"""dev/sign/sign_apps.py: checks docs/apps/apps.json (TV Apps' list) and signs it (apps.json.sig, Ed25519, base64).

  1. the list's shape (what app/shared/src/tvapps/.../AppList.kt reads: an entry it cannot use is dropped there silently, so here it fails);
  2. every APK source resolves now: a fixed link answers, a GitHub repository's latest release has one file the pattern matches;
  3. one APK per app (DEEP=1: every one) is downloaded into its own empty folder and its signers are compared with "certs"
     (apksigner); a missing icon is taken from that APK (its highest-density PNG, 128 px) into docs/apps/icons/, and every
     icon's SHA-256 is written into the list ("icon_sha": the apps refuse an icon that differs);
  4. apps.json is signed with LAUNCHERS_KEY (default ~/.tv-remote-release/launchers.key; the launcher list's key, never printed).

A new release of an app needs nothing here (the apps fetch its latest, pinned to its author's certificate); a fixed link with a
version in it (Kodi, VLC: not on GitHub) is moved by hand when its author releases. Run after every edit, then commit apps.json,
apps.json.sig and the icons together. SKIP_DOWNLOAD=1: shape, icons on disk and the signature only.
"""
import hashlib, io, json, os, re, shutil, subprocess, sys, tempfile, urllib.request, zipfile
from PIL import Image                                                 # pip install pillow

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DIR = os.path.join(ROOT, "docs", "apps")
LIST = os.path.join(DIR, "apps.json")
KEY = os.environ.get("LAUNCHERS_KEY", os.path.expanduser("~/.tv-remote-release/launchers.key"))
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal"}
LANGS = {"vi", "en"}                                                  # each app's words: these at least (the rest fall back to en)
GH = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")


def fail(m):
    print("x " + m)
    sys.exit(1)


def get(url, cap=1 << 20):
    h = {"User-Agent": "tv-remote"}
    if GH and url.startswith("https://api.github.com/"):
        h["Authorization"] = "Bearer " + GH
    with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=180) as r:
        return r.read(cap)


def resolve(s):
    """The link the apps would download now (AppList.resolve)."""
    if isinstance(s, str):
        get(s, 1)                                                     # answers (redirects followed)
        return s
    rel = json.loads(get("https://api.github.com/repos/%s/releases/latest" % s["repo"]))
    hits = [a["browser_download_url"] for a in rel.get("assets", []) if re.fullmatch(s["match"], a["name"])]
    if len(hits) != 1:
        raise ValueError("%s: %d files match %r in %s" % (s["repo"], len(hits), s["match"], rel.get("tag_name")))
    return hits[0]


def tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    bt = os.path.join(sdk, "build-tools")
    for v in sorted(os.listdir(bt), reverse=True) if os.path.isdir(bt) else []:
        p = os.path.join(bt, v, name)
        if os.path.exists(p):
            return p
    return shutil.which(name) or fail("no %s (Android SDK build-tools)" % name)


def certs(apk):
    out = subprocess.run([tool("apksigner"), "verify", "--print-certs", apk], capture_output=True, text=True)
    if out.returncode != 0:
        raise ValueError("apksigner: " + (out.stderr or out.stdout).strip()[:200])
    return {m.lower() for m in re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", out.stdout)}


def icon_from(apk, dest):
    """The launcher icon: aapt2's highest-density PNG (an adaptive XML icon: the same name's biggest PNG in the zip)."""
    out = subprocess.run([tool("aapt2"), "dump", "badging", apk], capture_output=True, text=True).stdout
    icons = re.findall(r"application-icon-(\d+):'([^']+)'", out)
    z = zipfile.ZipFile(apk)
    names = z.namelist()

    def picture(n):                                                   # a PNG / WebP (some builds drop the extension), not an XML icon
        try:
            return Image.open(io.BytesIO(z.read(n))).convert("RGBA")
        except Exception:
            return None
    im = None
    for _, p in sorted(icons, key=lambda x: -int(x[0])):
        im = picture(p) if p in names else None
        if im is None:
            stem = os.path.splitext(os.path.basename(p))[0]
            pngs = [n for n in names if re.search(r"/%s(\.(png|webp))?$" % re.escape(stem), n)]
            for n in sorted(pngs, key=lambda n: -z.getinfo(n).file_size):
                im = picture(n)
                if im:
                    break
        if im:
            break
    if im is None:
        raise ValueError("no PNG icon in the APK")
    save_icon(im, dest)


def save_icon(im, dest):
    im.thumbnail((128, 128), Image.LANCZOS)
    im.save(dest, "PNG", optimize=True)


def icon_png(b, dest):
    save_icon(Image.open(io.BytesIO(b)).convert("RGBA"), dest)


def download(url, to):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "tv-remote"}), timeout=300) as r, open(to, "wb") as o:
        shutil.copyfileobj(r, o, 1 << 16)


d = json.load(open(LIST, encoding="utf-8"))
if d.get("v") != 1:
    fail("v must be 1")
cats = {c["id"] for c in d["cats"]}
for c in d["cats"]:
    if not LANGS <= set(c["name"]):
        fail("cat %s: name needs %s" % (c["id"], sorted(LANGS)))
ids = set()
for a in d["apps"]:
    i = a.get("id", "")
    if not re.fullmatch(r"[a-z0-9-]+", i) or i in ids:
        fail("id %r: lowercase, unique" % i)
    ids.add(i)
    # "setup": installed by TV Setup on that screen of its own, never by the list, so no "apk" (AppList.kt; clients before it drop the entry)
    if a.get("setup") not in (None, "voice"):
        fail("%s: setup %r (only \"voice\")" % (i, a.get("setup")))
    if a.get("setup") and "apk" in a:
        fail("%s: an app TV Setup installs has no apk (an old client would install it beside TV Setup's rule)" % i)
    if not all(isinstance(p, str) and re.fullmatch(r"[\w.]+", p) for p in a.get("also", [])):
        fail("%s: also is a list of package names" % i)
    for k in ("pkg", "name", "cat", "desc", "icon", "source", "license", "certs") + (() if a.get("setup") else ("apk",)):
        if not a.get(k):
            fail("%s: no %s" % (i, k))
    if a["cat"] not in cats:
        fail("%s: cat %r is not in cats" % (i, a["cat"]))
    for k in ("desc", "notes"):
        if k in a and not LANGS <= set(a[k]):
            fail("%s: %s needs %s" % (i, k, sorted(LANGS)))
    if a["icon"] != "icons/%s.png" % i:
        fail("%s: icon is icons/%s.png" % (i, i))
    if not all(re.fullmatch(r"[0-9a-f]{64}", c) for c in a["certs"]):
        fail("%s: certs are SHA-256 hex, lowercase" % i)
    for abi, s in a.get("apk", {}).items():
        if abi not in ABIS:
            fail("%s: ABI %r (one of %s)" % (i, abi, sorted(ABIS)))
        ok = s.startswith("https://") if isinstance(s, str) else (re.fullmatch(r"[\w.-]+/[\w.-]+", s.get("repo", "")) and s.get("match"))
        if not ok:
            fail("%s %s: a https:// link or {repo, match}" % (i, abi))

for a in d["apps"]:
    path = os.path.join(DIR, a["icon"])
    if os.environ.get("SKIP_DOWNLOAD") != "1" and "apk" in a:
        try:
            urls = {abi: resolve(s) for abi, s in a["apk"].items()}
        except Exception as e:
            fail("%s: %s" % (a["id"], e))
        check = list(urls.items()) if os.environ.get("DEEP") == "1" else list(urls.items())[:1]
        for abi, url in check:
            tmp = tempfile.mkdtemp(prefix="tvapps-")                   # a downloaded APK: its own empty folder, never run
            try:
                apk = os.path.join(tmp, "app.apk")
                download(url, apk)
                got = certs(apk)
                if not got or not got <= set(a["certs"]):
                    fail("%s %s: signed by %s, the list pins %s" % (a["id"], abi, sorted(got), a["certs"]))
                if not os.path.exists(path):
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    try:
                        icon_from(apk, path)
                    except ValueError:                                # a vector icon only: the app's own F-Droid page has a PNG
                        icon_png(get("https://f-droid.org/repo/%s/en-US/icon.png" % a["pkg"], 2 << 20), path)
                    print("  icon %s" % a["icon"])
            finally:
                shutil.rmtree(tmp, ignore_errors=True)
        print("ok %s: %s" % (a["id"], ", ".join(sorted(urls))))
    if not os.path.exists(path):
        fail("%s: no %s" % (a["id"], a["icon"]))
    a["icon_sha"] = hashlib.sha256(open(path, "rb").read()).hexdigest()

with open(LIST, "w", encoding="utf-8") as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write("\n")
if not os.path.exists(KEY):
    fail("no key at %s; apps.json.sig is left for the maintainer" % KEY)
subprocess.run(["java", os.path.join(ROOT, "dev", "release", "Sign.java"), "sign", KEY, LIST], check=True)
print("signed %s: %d apps" % (LIST, len(d["apps"])))
