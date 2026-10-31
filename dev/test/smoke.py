#!/usr/bin/env python3
"""Every /api route over a real network path, for the faults a unit test and adb forward hide. See dev/test/AGENTS.md.

    dev/test/smoke.py tv                   TV Setup on the TV emulator (emulator-5554), reached through the emulator's own port
                                      redirect (`emu redir`), not adb forward: adb forward hid a reply cut short by a reset
    dev/test/smoke.py URL                  any core, e.g. the computer app's test server: TVR_PASS=... dev/test/smoke.py http://127.0.0.1:8769

The routes are read from the code (Manage.kt and its parts, RemoteService.kt, the computer app's Server.kt) and from the page's scripts.
Each is asked with GET and with a POST whose body it may not want (64 KB, not JSON), and must:
  - answer in full: the status line, the headers and as many bytes as Content-Length says (a stream: its first bytes);
  - not fail with 500 (an exception the handler did not expect), nor take longer than 15 s;
  - give JSON when it says JSON.
Routes that change the TV or the saved TVs (reboot, install, delete, keys, scenes, ...) are left out: SKIP below.
First, the lock: every route asked without the pass (and on the TV with a made-up pairing cookie) must be refused, 401 on the
TV, 403 on the computer app; only the pairing itself and the hook (its own token, off: 404) answer a stranger.
Exit 1 if any route fails; V=1 prints each route as it is asked. Needs only python3 (and adb for `tv`).
"""
import http.client, json, os, re, socket, subprocess, sys, time, urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SOURCES = ["app/shared/src/main/kotlin/dev/tvremote/core/manage", "app/shared/src/main/kotlin/dev/tvremote/core/web", "app/tvsetup/src/main/kotlin/dev/tvremote/setup/RemoteService.kt",
           "app/desktop/src/main/kotlin/dev/tvremote/desktop"]
# what changes the TV, a file on it, the saved TVs or this computer; or opens something on the TV's screen
SKIP = re.compile(r"/(reboot|power|shutdown|kill|delete|rename|mkdir|install|upload|uninstall|clean|free|backup|perm|launch|open|"
                  r"update|shell|settings|rules|scenes?|run|key|touch|type|text|wake|off|notify|draw|allow|deny|pair|cert|input|volume|"
                  r"clip|share|warm|cancel|tvset|save|select|remove|forget|scope|do|connect|tailscale/start|assist|fetch|setup|app)$"
                  r"|^/api/(fleet|pair/)")   # pair/start is asked by the pairing itself, with a body it does not read
STREAMS = {"/api/video", "/api/audio", "/api/screen"}               # sent until the connection closes: only their start is read
BIG = b"x" * 65536


def routes():
    found = set()
    for s in SOURCES:
        p = os.path.join(ROOT, s)
        files = [os.path.join(p, f) for f in os.listdir(p) if f.endswith(".kt")] if os.path.isdir(p) else [p]
        for f in files: found |= set(re.findall(r'"(/api/[A-Za-z0-9_/.-]+)"', open(f).read()))
    web = os.path.join(ROOT, "app/shared/web/js")
    for f in os.listdir(web): found |= set(re.findall(r"'(/api/[A-Za-z0-9_/.-]*[A-Za-z0-9])", open(os.path.join(web, f)).read()))
    return sorted(r for r in found if not r.endswith("/"))


def ask(base, path, method, headers):
    u = urllib.parse.urlparse(base)
    c = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=15)
    t = time.time()
    try:
        body = BIG if method == "POST" else None
        c.request(method, path, body=body, headers={**headers, **({"Content-Type": "application/json"} if body else {})})
        r = c.getresponse()
        if r.getheader("Content-Length") is None:                     # a stream: its start, within the time
            data = b""
            while len(data) < 65536 and time.time() - t < 15:
                b = r.read1(8192)
                if not b: break
                data += b
            if path in STREAMS or len(data) >= 65536 or time.time() - t >= 15: return None
        else: data = r.read()
        took = time.time() - t
        if r.status >= 500 and r.status != 503: return f"{r.status} {data[:120]!r}"   # 503: no TV / no ADB, said in words
        if took > 15: return f"slow: {took:.1f}s"
        if "json" in (r.getheader("Content-Type") or "") and path not in STREAMS:
            try: json.loads(data or b"null")
            except ValueError: return f"says JSON, is not: {data[:80]!r}"
        return None
    except http.client.IncompleteRead as e: return f"reply cut short: {len(e.partial)} bytes, {e.expected} missing"
    except (ConnectionResetError, BrokenPipeError) as e: return f"connection reset ({e.__class__.__name__}): the reply was lost"
    except socket.timeout:
        return None if path in STREAMS else "no reply in 15 s"
    except Exception as e: return f"{e.__class__.__name__}: {e}"
    finally: c.close()


OPEN = re.compile(r"^/api/(pair/(start|finish|invite)|hook|hook/state)$")    # a stranger's way in: the pairing, or the hook's own token


def locked(base, tv):
    """Every route without the pass. A stranger must get nothing done: GET everywhere, POST where nothing changes even if let in."""
    bad = 0
    for path in routes():
        if OPEN.search(path): continue
        for method in ("GET",) if SKIP.search(path) else ("GET", "POST"):
            for headers in ({}, {"Cookie": "tvr=0123456789abcdef"}) if tv else ({},):
                u = urllib.parse.urlparse(base)
                c = http.client.HTTPConnection(u.hostname, u.port, timeout=15)
                try:
                    c.request(method, path, body=b"{}" if method == "POST" else None, headers={**headers, "Content-Type": "application/json"})
                    st = c.getresponse().status
                except Exception as e: st = f"{e.__class__.__name__}"
                finally: c.close()
                if st != (401 if tv else 403): bad += 1; print(f"OPEN {method:4} {path} {headers or ''}: {st}, not refused", flush=True)
    return bad


def adb(*a): return subprocess.run(["adb", "-s", "emulator-5554", *a], capture_output=True, text=True, timeout=60).stdout


def pair_tv(base):
    """A pass of its own: the code TV Setup shows, read off the TV emulator's screen."""
    def post(path, data):
        u = urllib.parse.urlparse(base)
        c = http.client.HTTPConnection(u.hostname, u.port, timeout=20)
        c.request("POST", path, body=json.dumps(data), headers={"Content-Type": "application/json"})
        r = c.getresponse(); r.read(); return r
    r = post("/api/pair/start", {})
    if r.status != 200: sys.exit(f"pair/start: {r.status} (wait a minute: TV Setup slows down repeated codes)")
    code = None
    for _ in range(10):
        time.sleep(1)
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        m = re.search(r'text="(\d{3}) ?(\d{3})"', adb("shell", "cat", "/sdcard/ui.xml"))
        if m: code = m.group(1) + m.group(2); break
    if not code: sys.exit("no code on the TV emulator's screen")
    r = post("/api/pair/finish", {"code": code})
    adb("shell", "input", "keyevent", "4")
    cookie = (r.getheader("Set-Cookie") or "").split(";")[0]
    if r.status != 200 or not cookie: sys.exit(f"pair/finish: {r.status}")
    return {"Cookie": cookie}


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else "tv"
    port = None
    if arg == "tv":
        port = 18766
        adb("emu", "redir", "del", f"tcp:{port}"); adb("emu", "redir", "add", f"tcp:{port}:8765")
        base = f"http://127.0.0.1:{port}"
        bad = locked(base, True)
        headers = pair_tv(base)
    else:
        base = arg.rstrip("/")
        headers = {"x-tvr-pass": os.environ["TVR_PASS"]} if os.environ.get("TVR_PASS") else {}
        bad = locked(base, False)
    try:
        for path in routes():
            if SKIP.search(path): continue
            for method in ("GET", "POST"):
                if os.environ.get("V"): print(method, path, flush=True)
                err = ask(base, path, method, headers)
                if err: bad += 1; print(f"FAIL {method:4} {path}: {err}", flush=True)
        print(f"{'ok' if not bad else str(bad) + ' failed'}: {base}")
    finally:
        if port: adb("emu", "redir", "del", f"tcp:{port}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
