#!/usr/bin/env python3
"""The census worker's crash reports (POST /v1/crash). Needs the admin token: ~/.tv-remote-release/crash-token (or $CRASH_TOKEN).

  crashes.py                    top crashes as a table (most often first)
  crashes.py --v 0.3.1 --app pc only one version / app
  crashes.py SIG                one crash with its stack and device (SIG = the first letters of the sig column)
  crashes.py --delete SIG       remove one (all its versions)
  crashes.py --json             machine output for agents: [{sig, app, v, kind, msg, count, installs, first, last, device, stack}]
  crashes.py --since 2026-10-01 only crashes first seen on/after that day
  crashes.py --new              what has not been looked at: a crash never marked, or one that happened again after its mark (every version)
  crashes.py --mark SIG|all [--note "why"]   marks it (all = what --new lists) looked at (its count now), in checked.json (committed: every session and machine sees it)
  crashes.py SIG --frames       adds repo path:line for each stack frame that maps to a file here (also with --json: "frames")
  crashes.py --url http://127.0.0.1:8787   a local `wrangler dev`
"""
import argparse, json, os, re, sys, urllib.parse, urllib.request

URL = "https://tvr-census.99-hoangtran.workers.dev"


def token():
    t = os.environ.get("CRASH_TOKEN", "").strip()
    if not t:
        try:
            t = open(os.path.expanduser("~/.tv-remote-release/crash-token")).read().strip()
        except OSError:
            sys.exit("no token: ~/.tv-remote-release/crash-token or $CRASH_TOKEN")
    return t


def call(base, path, params, method="GET"):
    q = urllib.parse.urlencode({k: v for k, v in params.items() if v})
    req = urllib.request.Request(f"{base}{path}?{q}", method=method, headers={"Authorization": "Bearer " + token(), "User-Agent": "tvr-crashes"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"{e.code} {e.read().decode()[:200]}")


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


ADB_KIND = "TV Setup ADB "   # AdbReport's kind: expected states of a TV that cannot be reached; --new skips them unless --adb
CHECKED = os.path.join(os.path.dirname(os.path.abspath(__file__)), "checked.json")


def checked():
    """{"<sig>|<v>": {at, n, note}}: when a crash (one version of it) was last looked at, and how many times it had happened then.
    By count, not by date: the worker keeps days, and a crash seen again on the day it was marked must still show as new."""
    try:
        return json.load(open(CHECKED))
    except (OSError, ValueError):
        return {}


def unseen(r, marks):
    """How many times it happened since its mark (all of them when never marked); 0 = nothing new."""
    m = marks.get(r["sig"] + "|" + r["v"])
    return r["n"] - (m["n"] if m else 0)


def device(r):
    return " ".join(str(r[k]) for k in ("brand", "model", "android", "os", "arch", "java") if r.get(k))


def normal(r, marks):
    """The stable shape of --json (the worker's own field names may change). seen: the last mark or null; unseen: times since it."""
    return {"sig": r["sig"], "app": r["app"], "v": r["v"], "kind": r["kind"], "msg": r["msg"], "count": r["n"], "installs": r["installs"],
            "first": r["first"], "last": r["last"], "device": device(r), "stack": r.get("stack") or "",
            "seen": marks.get(r["sig"] + "|" + r["v"]), "unseen": unseen(r, marks)}


_files = {}


def files():
    """Source files of the repo by base name (build folders skipped)."""
    if not _files:
        for d, ds, fs in os.walk(os.path.join(ROOT, "app")):
            ds[:] = [x for x in ds if x not in ("build", ".gradle", "node_modules", ".cache")]
            for f in fs:
                if f.endswith((".kt", ".java", ".js")):
                    _files.setdefault(f, []).append(os.path.join(d, f))
    return _files


def frames(stack):
    """[{frame, path, line}] for the stack's frames that map to a file here. Kotlin / Java: `pkg.Class.fn(File.kt:12)`, the package must
    match the folders; JS: `file.js:12[:col]`, a file under app/shared/web/js (any other copy of the name is skipped)."""
    out = []
    for ln in (stack or "").splitlines():
        ln = ln.strip()
        m = re.search(r"([\w.$]+)\.[\w$<>]+\((\w+\.(?:kt|java)):(\d+)\)", ln)
        path = None
        if m:
            pkg = m.group(1).rsplit(".", 1)[0].replace(".", "/") if "." in m.group(1) else ""
            c = files().get(m.group(2), [])
            hit = [p for p in c if pkg and pkg in p.replace(os.sep, "/")] or (c if len(c) == 1 else [])
            path, line = (hit[0], m.group(3)) if hit else (None, 0)
        else:
            m = re.search(r"([\w.-]+\.js):(\d+)", ln)
            if m:
                hit = [p for p in files().get(m.group(1), []) if "/web/js/" in p.replace(os.sep, "/")]
                path, line = (hit[0], m.group(2)) if hit else (None, 0)
        if path:
            out.append({"frame": ln[:160], "path": os.path.relpath(path, ROOT), "line": int(line)})
    return out


def table(rows):
    if not rows:
        print("(no crashes)")
        return
    print(f"{'sig':<10} {'n':>4} {'inst':>4} {'app':<5} {'v':<8} {'last':<10} {'where':<6} kind / msg")
    for r in rows:
        print(f"{r['sig'][:8]:<10} {r['n']:>4} {r['installs']:>4} {r['app']:<5} {r['v']:<8} {r['last']:<10} {r['loc']:<6} {r['kind']}: {(r['msg'] or '')[:70]}")


def one(rows, with_frames=False):
    for r in rows:
        dev = device(r)
        print(f"== {r['sig']}  {r['app']} {r['v']}  x{r['n']} ({r['installs']} installs)  {r['first']} .. {r['last']}  [{r['loc']}]\n   device: {dev}\n   {r['kind']}: {r['msg']}\n")
        print(r["stack"] or "(no stack)", "\n")
        if with_frames:
            for f in frames(r["stack"]):
                print(f"   -> {f['path']}:{f['line']}   {f['frame']}")


def main():
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("sig", nargs="?")
    a.add_argument("--v", default="")
    a.add_argument("--app", default="")
    a.add_argument("--url", default=URL)
    a.add_argument("--delete", metavar="SIG")
    a.add_argument("--json", action="store_true")
    a.add_argument("--since", default="", metavar="YYYY-MM-DD")
    a.add_argument("--new", action="store_true")
    a.add_argument("--adb", action="store_true", help="--new also lists 'TV Setup ADB ...' (a TV whose own ADB is off: a state, not a crash)")
    a.add_argument("--frames", action="store_true")
    a.add_argument("--mark", metavar="SIG|all")
    a.add_argument("--note", default="")
    o = a.parse_args()
    marks = checked()
    if o.mark:
        rows = call(o.url, "/v1/crashes", {"sig": "" if o.mark == "all" else full(o, o.mark), "v": o.v, "app": o.app})["crashes"]
        if o.mark == "all": rows = [r for r in rows if unseen(r, marks) > 0]   # only what --new lists: the others keep their own notes
        at = __import__("datetime").datetime.now().astimezone().isoformat(timespec="seconds")
        for r in rows:
            k = r["sig"] + "|" + r["v"]
            marks[k] = {"at": at, "n": r["n"], "note": o.note or (marks.get(k) or {}).get("note", "")}
        with open(CHECKED, "w") as f:
            json.dump(dict(sorted(marks.items())), f, ensure_ascii=False, indent=1)
            f.write("\n")
        print(f"marked {len(rows)} (sig|version) as looked at -> {os.path.relpath(CHECKED, ROOT)}: commit it")
        return
    if o.json or o.since or o.new:
        rows = call(o.url, "/v1/crashes", {"sig": full(o, o.sig) if o.sig else "", "v": o.v, "app": o.app})["crashes"]
        rows = [r for r in rows if r["first"] >= o.since and (not o.new or (unseen(r, marks) > 0 and (o.adb or not r["kind"].startswith(ADB_KIND))))]
        if o.json:
            out = [normal(r, marks) for r in rows]
            if o.frames:
                for x in out:
                    x["frames"] = frames(x["stack"])
            print(json.dumps(out, ensure_ascii=False, indent=1))
        elif o.sig:
            one(rows, o.frames)
        else:
            table(rows)
        return
    if o.delete:
        sig = full(o, o.delete)
        print(call(o.url, "/v1/crashes", {"sig": sig}, "DELETE"))
    elif o.sig:
        one(call(o.url, "/v1/crashes", {"sig": full(o, o.sig)})["crashes"], o.frames)
    else:
        table(call(o.url, "/v1/crashes", {"v": o.v, "app": o.app})["crashes"])


def full(o, prefix):
    """The sig column shows 8 characters: find the whole one."""
    hits = {r["sig"] for r in call(o.url, "/v1/crashes", {})["crashes"] if r["sig"].startswith(prefix)}
    if len(hits) != 1:
        sys.exit(f"{len(hits)} crashes start with {prefix!r}")
    return hits.pop()


if __name__ == "__main__":
    main()
