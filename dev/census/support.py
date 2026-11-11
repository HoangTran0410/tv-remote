#!/usr/bin/env python3
"""Support codes from TV Setup, the Phone app and the Computer app (src/support.js; the list's "app" column: setup / phone / pc). Needs the admin token: ~/.tv-remote-release/crash-token (or $CRASH_TOKEN).

  support.py                         the last 50 codes: when sent, which TV, whether it lets you look now
  support.py CODE                    what the TV sent (its facts and log)
  support.py CODE /api/state         while the TV lets support look: GET that route on the TV, print the answer
  support.py CODE --all              everything at once: about, state, diagnose, setup/version, wirelog, rules, apps, the prefs,
                                     health, stats, playing, caps, paired, tailscale, TV Setup's log, and the TV's state over ADB
                                     (home screens, what is on screen, memory, settings, network, remotes, getprop, the whole logcat)
                                     a phone code: its log, logcat, settings, every network, the saved TVs, the self-check of each;
                                     a computer code: its log, system, networks, and its page's state, TVs, about, fleet, setup
  support.py CODE --all --out DIR    the same, one file per answer in DIR, and one line each here (agents: read the files, not the screen)
  support.py CODE sh "dumpsys package dev.tvremote.setup.keys"   any read-only command (TV Setup newer than 0.26.2: settings, dumpsys,
                                     getprop, pm list/dump, appops get, logcat -d); /api/support/prefs, /api/support/logcat
  support.py CODE sh "getevent -pl"            a remote's keys (TV Setup newer than 0.26.3): what each remote declares; also
                                     "timeout 15 getevent -lt -c 40" (the next presses), cat/ls /vendor|system/usr/keylayout/...,
                                     and logcat -s KeyFix shows "key <code>" for each press while support looks
  support.py CODE --reply "text"     answer the person: TV Setup shows it on the TV (it asks every 6 h), and the person answers
                                     from the page the TV's QR opens (tvremote.pages.dev/support#code.key); one thread per TV, so
                                     any of its codes reaches it. What they write: `support.py CODE` (the thread under the facts)
  support.py --said                  what people wrote in the last 7 days (crashwatch tells the session when someone does)
  support.py --delete CODE           forget it (and its thread, once no other code of that TV is left)
  support.py --url http://127.0.0.1:8798   a local `wrangler dev`
  --app kidguard                     KidGuard's codes (it shares this worker, 2026-11-11). Without it every list leaves KidGuard
                                     out and a KidGuard code is not opened: a child's phone, read only from the KidGuard repo
"""
import argparse, datetime, json, os, re, sys, time, urllib.error, urllib.parse, urllib.request

URL = "https://tvr-census.99-hoangtran.workers.dev"
SH = ["cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME",   # the home screens
      "dumpsys activity activities", "dumpsys meminfo", "dumpsys deviceidle", "settings list global", "settings list secure",
      "dumpsys connectivity",                                # which network is the way out, Wi-Fi validated or not, VPN
      "dumpsys input",                                       # the remotes and keyboards plugged in, what has focus
      "getprop", "logcat -d -v time"]                       # TV Setup cuts each to what the worker takes, a log keeps its end
ALL = ["/api/about", "/api/state", "/api/diagnose", "/api/setup/version", "/api/wirelog", "/api/rules", "/api/apps",
       "/api/health", "/api/stats", "/api/playing", "/api/caps", "/api/paired", "/api/tailscale",
       "/api/support/prefs", "/api/support/logcat"] + ["/api/support/sh?c=" + urllib.parse.quote(c, safe="") for c in SH]

PHONE = ["/api/support/log", "/api/support/net", "/api/support/tvs", "/api/support/prefs", "/api/support/logcat"]   # + /api/diagnose?ip= of each saved TV
PC = ["/api/support/sys", "/api/support/net", "/api/support/log", "/api/state", "/api/about", "/api/tvs", "/api/update", "/api/setup/need",
      "/api/setup/status", "/api/fleet", "/api/diagnose", "/api/health", "/api/caps", "/api/playing", "/api/rules", "/api/setup/version"]


def token():
    t = os.environ.get("CRASH_TOKEN", "").strip()
    if not t:
        try:
            t = open(os.path.expanduser("~/.tv-remote-release/crash-token")).read().strip()
        except OSError:
            sys.exit("no token: ~/.tv-remote-release/crash-token or $CRASH_TOKEN")
    return t


def call(base, path, params=None, body=None, method="GET"):
    q = urllib.parse.urlencode(params or {})
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{base}{path}" + (f"?{q}" if q else ""), data=data, method=method,
                                 headers={"Authorization": "Bearer " + token(), "User-Agent": "tvr-support", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"{e.code} {e.read().decode()[:300]}")


def when(ms, now):
    if not ms:
        return "-"
    s = (now - ms) / 1000
    return f"{int(s)} s ago" if abs(s) < 120 else f"{int(s / 60)} min ago" if abs(s) < 7200 else datetime.datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M")


def ask(base, code, path, wait=None):
    wait = wait or (100 if "/api/support/" in path else 40)  # a dump: up to 25 s on the TV's ADB, then 200 KB up from a slow box
    q = call(base, "/v1/support/ask", body={"code": code, "path": path}, method="POST")
    end = time.time() + wait
    while time.time() < end:
        time.sleep(1.5)
        a = call(base, "/v1/support/answer", {"code": code, "id": q["id"]})
        if not a.get("pending"):
            return a
    return {"status": 0, "body": f"no answer in {wait} s: the TV stopped looking, or is asleep"}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("code", nargs="?")
    ap.add_argument("path", nargs="?")
    ap.add_argument("cmd", nargs="?")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--out")
    ap.add_argument("--delete", action="store_true")
    ap.add_argument("--reply")
    ap.add_argument("--said", action="store_true")
    ap.add_argument("--url", default=URL)
    ap.add_argument("--app", default="")
    a = ap.parse_args()
    base = a.url.rstrip("/")
    only = {"app": a.app} if a.app else {"app": "!kidguard"}
    if a.code and not a.app and a.code.isdigit():           # a KidGuard code (a child's phone) is opened only with --app kidguard
        if call(base, "/v1/support", {"code": a.code}).get("app") == "kidguard": sys.exit("a KidGuard code: read it from the KidGuard repo with --app kidguard")
    if a.said:
        r = call(base, "/v1/support/said", {"since": int(time.time() * 1000) - 7 * 86_400_000, **only})
        for m in r["said"]: print(f'{m["code"]}  {when(m["at"], r["now"]):>16}  {m["text"]}')
        return
    if a.reply is not None:
        if not a.code: sys.exit("support.py CODE --reply \"text\"")
        print(call(base, "/v1/support/reply", body={"code": a.code, "text": a.reply}, method="POST"))
        return
    if not a.code:
        r = call(base, "/v1/support", only)
        for s in r["support"]:
            live = "LIVE " + str(int((s["live"] - r["now"]) / 60000) + 1) + " min" if s["live"] > r["now"] else ""
            print(f'{s["code"]}  {when(s["made"], r["now"]):>16}  {s["app"]} {s["v"]:<8} {s["model"]:<28} seen {when(s["seen"], r["now"])}  {live}')
        return
    if a.delete:
        print(call(base, "/v1/support", {"code": a.code}, method="DELETE"))
        return
    if a.path == "sh":                                       # a read-only command on the TV, over TV Setup's own ADB
        a.path = "/api/support/sh?c=" + urllib.parse.quote(a.cmd or "", safe="").replace("~", "%7E")
    if a.path or a.all:
        app = call(base, "/v1/support", {"code": a.code}).get("app", "setup") if not a.path else ""
        paths = [a.path] if a.path else PHONE if app == "phone" else PC if app == "pc" else ALL
        if app == "phone":                                   # the self-check against every saved TV, as its picker would run it
            try: paths += ["/api/diagnose?ip=" + t["ip"] for t in json.loads(ask(base, a.code, "/api/support/tvs").get("body", "{}")).get("tvs", [])]
            except ValueError: pass
        for p in paths:
            r = ask(base, a.code, p)
            if a.out:
                os.makedirs(a.out, exist_ok=True)
                f = os.path.join(a.out, re.sub(r"[^\w.-]+", "_", urllib.parse.unquote(p).replace("/api/support/sh?c=", "sh_").replace("/api/support/", "").replace("/api/", ""))[:80] + ".txt")
                open(f, "w").write(r.get("body", ""))
                print(f"{r.get('status')}  {len(r.get('body', '')):>7} chars  {f}")
                continue
            print(f"== {p}  ({r.get('status')})")
            print(r.get("body", ""))
        return
    r = call(base, "/v1/support", {"code": a.code})
    live = f"lets support look for {int((r['live'] - r['now']) / 60000) + 1} more min" if r["live"] > r["now"] else "not open now"
    print(f'{r["code"]}  {r["app"]} {r["v"]}  {r["model"]}  sent {when(r["made"], r["now"])}, seen {when(r["seen"], r["now"])}, {live}\n')
    said = [l for l in r["report"].split("\n")[:2] if l.startswith("[said] ")]   # what the sender chose / wrote (the first lines of the report)
    print(("SENDER SAID: " + " | ".join(l[7:] for l in said) + "\n\n") if said else "(the sender said nothing)\n")
    print(r["report"])
    if r.get("msgs"):                                         # the thread, last: what to answer
        print("\n== THREAD (codes of this TV: " + ", ".join(c["code"] for c in r.get("codes", [])) + ")")
        for m in r["msgs"]: print(f'{"US " if m["who"] == "us" else "THEM"}  {when(m["at"], r["now"]):>16}  {m["text"]}')


if __name__ == "__main__":
    main()
