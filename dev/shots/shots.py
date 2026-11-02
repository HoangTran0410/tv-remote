#!/usr/bin/env python3
"""The screenshots of the intro site (docs/img), taken again from today's UI in one go. See dev/test/AGENTS.md.

    dev/shots/shots.py                  every picture
    dev/shots/shots.py web ts           only some: web (computer app's remote), ts (Tailscale box), feat [names] (the web cards of the
                                  home page's "And more", on a gradient), tv (TV web page),
                                  phone (phone app's remote), tvapp (TV Setup's screen), og (the link previews, from TV Home's picture)

Needs: python3 with Pillow, Google Chrome, cwebp, the computer app built (./gradlew :desktop:installDist) and, for tv / phone /
tvapp, the two emulators running with the apps installed (dev/test/emu.sh). The picture on the TV is the real TV Home of the TV
emulator, captured before (docs/img/tvhome-widgets-<lang>.webp): no real TV, no one's address in the pictures.
TV_PIN=<the emulator TV Setup PIN> when TV Setup there is locked (tvapp types it on its pad).
The page's pictures are taken in the dark theme, like the site (THEME=light for the old look).
Only the standard library besides Pillow: Chrome is driven through its DevTools protocol over a small WebSocket client below.
"""
import base64, functools, http.server, json, os, re, shutil, socket, struct, subprocess, sys, tempfile, threading, time
import urllib.request
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
IMG = os.path.join(ROOT, "docs", "img")
CACHE = os.path.join(ROOT, ".cache", "shots")                     # its own TV list and keys: real TVs stay untouched
SDK = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
ADB = shutil.which("adb") or os.path.join(SDK, "platform-tools", "adb")
CHROME = os.environ.get("CHROME") or next((p for p in ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
          shutil.which("google-chrome") or "", shutil.which("chromium") or ""] if p and os.path.exists(p)), "")
def free_port():
    with socket.socket() as s: s.bind(("127.0.0.1", 0)); return s.getsockname()[1]
WEB_PORT, PIC_PORT, CDP_PORT, TV_FWD = free_port(), free_port(), free_port(), free_port()   # beside a running app or another run
PASS = "shots-" + os.urandom(8).hex()                                 # the computer app's key (Pass.kt), carried in each link
TV, PHONE = os.environ.get("TV_SERIAL", "emulator-5554"), os.environ.get("PHONE_SERIAL", "emulator-5556")
NAME = {"vi": "TV phòng khách", "en": "Living room TV"}
FONT = os.path.join(ROOT, "dev", "shots", "Roboto.ttf")            # Android's own face, for the words drawn over the emulators' names
TMP = tempfile.mkdtemp(prefix="tvr-shots-")
THEME = os.environ.get("THEME", "dark")                           # the site is dark: its pictures of the page are taken dark (THEME=light for the old look)


def log(*a): print("·", *a, flush=True)


# ---- Chrome over the DevTools protocol ----------------------------------------------------------------------------------

class Ws:
    """Just enough WebSocket for DevTools: masked text frames out, (fragmented) frames in."""
    def __init__(self, url):
        host, port, path = re.match(r"ws://([^:/]+):(\d+)(/.*)", url).groups()
        self.s = socket.create_connection((host, int(port)))
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.sendall(f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode())
        head = b""
        while b"\r\n\r\n" not in head: head += self.s.recv(1)
        if b" 101 " not in head.split(b"\r\n")[0]: raise IOError(head.decode(errors="replace"))

    def _read(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c: raise IOError("closed")
            b += c
        return b

    def send(self, text):
        data, mask = text.encode(), os.urandom(4)
        n = len(data)
        head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536
                                else bytes([0x80 | 127]) + struct.pack(">Q", n))
        self.s.sendall(head + mask + bytes(c ^ mask[i % 4] for i, c in enumerate(data)))

    def recv(self):
        out = b""
        while True:
            b0, b1 = self._read(2)
            n = b1 & 0x7F
            if n == 126: n = struct.unpack(">H", self._read(2))[0]
            elif n == 127: n = struct.unpack(">Q", self._read(8))[0]
            out += self._read(n)
            if b0 & 0x80: return out.decode()


class Chrome:
    def __init__(self, w, h, dpr=1, cookie=None, url=None):
        self.prof = tempfile.mkdtemp(prefix="cdp-", dir=TMP)
        self.p = subprocess.Popen([CHROME, "--headless=new", f"--remote-debugging-port={CDP_PORT}", f"--user-data-dir={self.prof}",
                                   f"--window-size={w},{h}", "--hide-scrollbars", "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(900):                                          # Chrome took 21 s to start beside three emulators
            try:
                tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{CDP_PORT}/json")); break
            except Exception: time.sleep(0.1)
        else: sys.exit(f"Chrome did not open its DevTools port {CDP_PORT} in 90 s")
        self.ws, self.n = Ws([t for t in tabs if t["type"] == "page"][0]["webSocketDebuggerUrl"]), 0
        self.call("Emulation.setDeviceMetricsOverride", width=w, height=h, deviceScaleFactor=dpr, mobile=w < 700)
        self.call("Emulation.setEmulatedMedia", features=[{"name": "prefers-color-scheme", "value": THEME}])   # the page's theme is "auto" = this
        if cookie: self.call("Network.setCookie", name=cookie[0], value=cookie[1], url=url)

    def call(self, method, **params):
        self.n += 1
        self.ws.send(json.dumps({"id": self.n, "method": method, "params": params}))
        while True:
            r = json.loads(self.ws.recv())
            if r.get("id") == self.n:
                if "error" in r: raise RuntimeError(f"{method}: {r['error']}")
                return r.get("result", {})

    def open(self, url, wait=3):
        self.call("Page.navigate", url=url)
        for _ in range(120):                                          # a fixed wait lost the page's scripts on a busy computer
            time.sleep(0.25)
            if self.call("Runtime.evaluate", expression="document.readyState", returnByValue=True)["result"].get("value") == "complete": break
        time.sleep(wait)

    def js(self, code):
        r = self.call("Runtime.evaluate", expression=code, awaitPromise=True, returnByValue=True)
        if "exceptionDetails" in r: raise RuntimeError(r["exceptionDetails"].get("exception", {}).get("description", r["exceptionDetails"]))
        return r["result"].get("value")

    def shot(self, path):
        open(path, "wb").write(base64.b64decode(self.call("Page.captureScreenshot")["data"])); return Image.open(path)

    def close(self):
        self.p.kill(); self.p.wait()


def chrome_shot(url, js, w, h, dpr=1, cookie=None, wait=3):
    c = Chrome(w, h, dpr, cookie, url)
    try:
        c.open(url, wait)
        c.js("window.W = ms => new Promise(r => setTimeout(r, ms))")  # the scripts below wait with it
        got = c.js(js) if js else None
        time.sleep(0.5)
        return c.shot(os.path.join(TMP, f"s{time.time_ns()}.png")), got
    finally: c.close()


SMALL = {"tvapp": (640, 960), "phone-remote": (240, 360)}   # widths of the hero's srcset (docs/index.html)


def save_webp(img, name):
    png = os.path.join(TMP, name + ".png")
    img.convert("RGB").save(png)
    subprocess.run(["cwebp", "-quiet", "-q", "80", png, "-o", os.path.join(IMG, name + ".webp")], check=True)
    log("docs/img/" + name + ".webp", img.size)
    for w in SMALL.get(name.rsplit("-", 1)[0] if name.startswith("tvapp") else name, ()):   # the hero's srcset: -<w> copies, never upscaled
        small = img.resize((w, round(img.height * w / img.width)), Image.LANCZOS)
        small.convert("RGB").save(png)
        subprocess.run(["cwebp", "-quiet", "-q", "80", png, "-o", os.path.join(IMG, f"{name}-{w}.webp")], check=True)
        log(f"docs/img/{name}-{w}.webp", small.size)


# ---- the TV picture ----------------------------------------------------------------------------------------------------

def serve_pictures():
    """<lang>.png on PIC_PORT, the picture the pages show as the TV's screen: TV Home as captured on the TV emulator."""
    for lang in ("vi", "en"):
        Image.open(os.path.join(IMG, f"tvhome-widgets-{lang}.webp")).convert("RGB").save(os.path.join(TMP, f"{lang}.png"))
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a): pass
    h = functools.partial(Quiet, directory=TMP)
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", PIC_PORT), h)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


SHOW_PICTURE = """
  for (let i = 0; i < 60 && $('#screenbtn').disabled; i++) await W(250);
  const m = $('#screenmode'); m.value = '720'; m.dispatchEvent(new Event('change'));
  $('#screenbtn').click(); await W(5000);
  const img = $('#screen'), v = $('#screenv');
  img.src = 'http://127.0.0.1:%d/' + LANG + '.png'; await W(800);
  v.hidden = true; v.style.display = 'none'; img.hidden = false; img.style.display = 'block'; $('#screenph').hidden = true;
  $('#setupupd')?.setAttribute('hidden', '');
""" % PIC_PORT

# the page's own frames come back some seconds later: put the picture again just before the shot
PUT_PICTURE = """
  { const img = $('#screen'); let me = $('#shotpic');
    if (!me) { me = img.cloneNode(false); me.id = 'shotpic'; img.after(me);
      const st = document.createElement('style'); st.textContent = '#screen, #screenv, #screenc { display: none !important }'; document.head.append(st); }
    me.hidden = false; me.style.display = 'block'; me.src = 'http://127.0.0.1:%d/' + LANG + '.png'; await W(1500); }
""" % PIC_PORT


# ---- the computer app ---------------------------------------------------------------------------------------------------

def desktop():
    """The computer app on its own port, TV list and keys, with the TV emulator (127.0.0.1:5555) as its one TV."""
    exe = os.path.join(ROOT, "app", "desktop", "build", "install", "desktop", "bin", "desktop")
    if not os.path.exists(exe): sys.exit("Build the computer app first: cd app/gradle && ./gradlew :desktop:installDist")
    os.makedirs(os.path.join(CACHE, "keys"), exist_ok=True)
    tvs = os.path.join(CACHE, "tvs.json")
    old = json.load(open(tvs)) if os.path.exists(tvs) else {}
    t = (old.get("tvs") or [{}])[0]
    t.update(host="127.0.0.1", name=NAME["vi"])                       # keeps setup_pass, so TV Setup is not asked again
    json.dump({"tvs": [t], "current": "127.0.0.1"}, open(tvs, "w"), ensure_ascii=False, indent=1)
    env = dict(os.environ, TVS_FILE=tvs, TVR_KEYS=os.path.join(CACHE, "keys"), TVR_PASS=PASS)
    p = subprocess.Popen([exe, "--port", str(WEB_PORT)], env=env, stdout=open(os.path.join(TMP, "desktop.log"), "w"), stderr=subprocess.STDOUT)
    for _ in range(100):
        try: urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{WEB_PORT}/api/about", headers={"x-tvr-pass": PASS})); return p
        except Exception: time.sleep(0.2)
    p.kill(); sys.exit("The computer app did not start:\n" + open(os.path.join(TMP, "desktop.log")).read()[-2000:])


def shot_web():
    """web-remote-<lang>: the computer app's remote with the picture on, 1200 wide, from the tab row down."""
    for lang in ("vi", "en"):
        js = """(async () => {
          wantPick = false; await W(3000); $('[data-tab=remote]')?.click(); await W(4000);
          %s
          await W(6000);                                              // the app logos come in
          %s
          [...document.querySelectorAll('#quick > *, .appgrid > *')].forEach(e => { if (/com\\./.test(e.textContent)) e.remove() });
          setChip('#chipTv', 'ok', T('core.tv_on')); $('#info').textContent = T('core.connected');
          scrollTo(0, 0);
          return (document.querySelector('[role=tablist]') || $('[data-tab]').parentElement).getBoundingClientRect().top;
        })()""" % (SHOW_PICTURE, PUT_PICTURE)
        img, top = chrome_shot(f"http://127.0.0.1:{WEB_PORT}/?lang={lang}&k={PASS}", js, 1200, 1000)
        full = img.crop((0, int(top), 1200, int(top) + 852))
        save_webp(full, f"web-remote-{lang}")
        save_webp(full.crop((0, 0, 1200, 640)).resize((600, 320), Image.LANCZOS), f"web-remote-{lang}-600")   # the hero's small copy


def shot_ts():
    """tailscale-<lang>: the Tailscale box waiting for the sign-in, its QR code blurred (a made-up link, but a real-looking code)."""
    for lang in ("vi", "en"):
        js = """(async () => {
          await W(1500);
          const real = api;
          api = async (u, b, q) => u.startsWith('/api/tailscale/start') ? { status: 'done', url: 'https://login.tailscale.com/a/1f2e3d4c5b6a' }
            : u.startsWith('/api/tailscale') ? {} : real(u, b, q);
          tailscaleGo(); await W(1200);
          const box = e => { const r = e.getBoundingClientRect(); return [r.x, r.y, r.right, r.bottom] };
          return [box($('#ts .card') || $('#ts')), box($('#ts .tsqr svg'))];
        })()"""
        img, (card, qr) = chrome_shot(f"http://127.0.0.1:{WEB_PORT}/?lang={lang}&k={PASS}", js, 400, 900, dpr=2)
        q = tuple(int(v * 2) for v in qr)
        img.paste(img.crop(q).filter(ImageFilter.GaussianBlur(10)), q[:2])
        c = img.crop(tuple(int(v * 2) for v in card))
        save_webp(c.resize((600, round(c.height * 600 / c.width)), Image.LANCZOS), f"tailscale-{lang}")


# ---- the "And more" cards of the home page (docs/img/feat-<name>-<lang>.webp), the web UI on a gradient ---------------------
# The phone-app cards (air, notify, ...) are the emulator's screen on a vertical gradient; these are the page's cards the same way:
# a rounded cut of the page, a soft shadow, one of the same gradients (top colour -> bottom colour), 720 x 480. Baked into the image:
# the home page has no CSS for it (docs/css/home.css .fc img).

GRADIENTS = [((36, 63, 150), (14, 23, 52)), ((121, 48, 159), (25, 15, 48)), ((21, 120, 142), (10, 30, 46)),
             ((187, 109, 40), (40, 26, 15)), ((151, 60, 111), (37, 16, 35)), ((58, 59, 168), (14, 16, 45)), ((49, 91, 200), (13, 20, 47))]


def frame(cut, css_w, n):
    """`cut`: the card as the browser drew it (2 px per CSS px, `css_w` wide). Returns it on gradient number n, 720 x 480."""
    top, bot = GRADIENTS[n % len(GRADIENTS)]
    bg = Image.new("RGB", (720, 480))
    d = ImageDraw.Draw(bg)
    for y in range(480):
        d.line([(0, y), (720, y)], fill=tuple(round(a + (b - a) * y / 479) for a, b in zip(top, bot)))
    cut = cut.convert("RGB")
    while cut.height > 40 and max(cut.crop((cut.width // 4, cut.height - 3, cut.width * 3 // 4, cut.height - 2)).getextrema()[i][1] for i in range(3)) < 6:
        cut = cut.crop((0, 0, cut.width, cut.height - 3))               # the dialog's backdrop under a card that is shorter than its box
    w = min(648, round(css_w * 1.4))
    cut = cut.convert("RGB").resize((w, round(cut.height * w / cut.width)), Image.LANCZOS)
    bleed = cut.height > 450                                          # a tall card runs off the bottom edge, like the phone's screen
    cut = cut.crop((0, 0, w, 450)) if bleed else cut
    x, y = (720 - w) // 2, 30 if bleed else max(15, (480 - cut.height) // 2)
    r = 20
    mask = Image.new("L", cut.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, cut.width - 1, cut.height - 1 + (r * 2 if bleed else 0)), radius=r, fill=255)
    shadow = Image.new("L", bg.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x, y + 12, x + cut.width, y + cut.height + 12), radius=r, fill=140)
    bg.paste((0, 0, 0), (0, 0), shadow.filter(ImageFilter.GaussianBlur(14)))
    bg.paste(cut, (x, y), mask)
    return bg


FEAT_NAMES = {"vi": "TV phòng khách", "en": "Living room TV"}
# name: (page width in CSS px, tab or "", js that makes the card appear, js giving the card to cut: the element, gradient number)
CLICK = "{ const b = [...document.querySelectorAll(%s)].find(e => e.offsetParent && new RegExp(%s, 'i').test(e.innerText)); if (!b) throw new Error('no button ' + %s); b.click(); await W(1800); }"
def click(sel, rx): return CLICK % (json.dumps(sel), json.dumps(rx), json.dumps(sel + rx))
DLG = "document.querySelector('dialog[open] .card')"
MORE = "if (!$('#more') || $('#more').getBoundingClientRect().height < 10) { $('#morebtn').click(); await W(900); }"
FEATS = {
    "apps":     (840, "apps", "await W(2500);", "$('#appsf').closest('section')", 0),
    "files":    (480, "files", "await W(2500); $('#places button').click(); await W(3000); [...$('#places').closest('section').querySelectorAll('.item')].slice(4).forEach((n) => n.remove());", "$('#places').closest('section')", 1),
    "health":   (480, "health", "await W(4000);", "[...document.querySelectorAll('.card')].find(c => /CPU/.test(c.textContent) && /RAM/.test(c.textContent) && c.getBoundingClientRect().height < 500)", 2),
    "tvset":    (480, "tv", "await W(2500); const t = [...document.querySelectorAll('.card')].find(c => /DNS/.test(c.textContent) && c.getBoundingClientRect().height < 900).closest('section, details'); t.style.cssText = 'padding:16px;background:var(--bg2);border-radius:14px';", "t", 3),
    "welcome":  (480, "auto", "await W(2500);", "$('#welcomecard')", 4),
    "lock":     (480, "auto", "await W(2500);", "$('#lockcard')", 5),
    "schedule": (480, "auto", "await W(2500);" + click("#schedcard button", "Thêm|Add"), DLG, 6),
    "limits":   (480, "auto", "await W(2500);" + click("#limitcard button", "Thêm|Add"), DLG, 0),
    "optimize": (480, "tv", "await W(2500); $('#setupapply').click(); await W(2500);", DLG, 1),
    "launchers": (860, "tv", "await W(2500); $('#launchopen').click(); await W(3500);", DLG, 2),
    "dim":      (460, "", MORE + "$('#r-timer').click(); await W(1500);" + click("dialog[open] button", "Màn hình TV|TV screen") +
                 click("dialog[open] button", "Giảm độ sáng|Dim the screen"), DLG, 3),
    "share":    (460, "", MORE + "$('#r-tools').click(); await W(1500);" + click("dialog[open] button", "Chia sẻ|Share") + "await W(1500);" +
                 "document.querySelectorAll('dialog[open] svg:not(.ic), dialog[open] canvas, dialog[open] img').forEach(e => { if (e.getBoundingClientRect().width > 100) e.style.filter = 'blur(10px)' });", DLG, 4),
    "brands":   (480, "pick", "await W(1500); $('#savedcard').hidden = true; $('#scanlist').replaceChildren(...[['LG webOS TV', '192.168.1.31', 'lg'], ['Samsung 55 Q60', '192.168.1.32', 'samsung'],"
                 " ['Roku Ultra', '192.168.1.33', 'roku'], [LANG === 'vi' ? 'TV phòng khách' : 'Living room TV', '192.168.1.20', 'android']]"
                 ".map(([name, ip, kind]) => scanRow({ name, ip, kind, remote: kind === 'android', adb: true, via: 'lan' })));", "$('#scan').closest('.card, section')", 1),
    "many":     (480, "pick", "await W(1500); const real = api; api = async (u, b, q) => u === '/api/fleet' ? { blocked: [], tvs: [{ host: STATE.host, state: 'ready', name: LANG === 'vi' ? 'TV phòng khách' : 'Living room TV',"
                 " android: '14', disk_free: 18.7e9, disk_total: 30.1e9, ram: 3.9e9, setup: '0.21.0', model: 'Streamer 4K' }, { host: '192.168.1.34', state: 'ready', name: LANG === 'vi' ? 'TV phòng ngủ' : 'Bedroom TV',"
                 " android: '14', disk_free: 18.7e9, disk_total: 30.1e9, ram: 3.9e9, setup: '0.21.0', model: 'Streamer 4K' }] } : real(u, b, q);"
                 " $('#fleet').hidden = false; $('#fleet').open = true; await W(2500);", "$('#fleet')", 2),
    "photo":    (600, "remote", "await W(500); $('#screenbtn').click(); await W(7000); await drawStart(); await W(1500); const cv = document.createElement('canvas'); cv.width = 1280; cv.height = 720; const g = cv.getContext('2d');"
                 " g.fillStyle = '#2d7d5b'; g.fillRect(0, 0, 1280, 720); g.fillStyle = '#ffd54a'; g.beginPath(); g.arc(900, 190, 70, 0, 7); g.fill(); g.fillStyle = '#1f5f46'; g.beginPath(); g.moveTo(0, 720); g.lineTo(380, 260); g.lineTo(760, 720); g.fill();"
                 " await picPlace(cv.toDataURL('image/png'), 3000); await W(1500);", "$('#screenbox').closest('.card')", 0),
    "gamepad":  (480, "remote", "await W(500); const tt = () => { toast(T('gamepad.connected')); $('#toast').style.cssText = 'inset:auto auto auto 50%;top:270px'; }; tt(); setInterval(tt, 400); await W(300);",
                 "{ scrollIntoView() {}, getBoundingClientRect() { const a = document.querySelector('[role=tablist]').getBoundingClientRect(); return new DOMRect(a.x, a.y, a.width, 640); } }", 4),
    "keymap":   (480, "tv", "await W(500); const real = api; api = async (u, b, q) => { const x = await real(u, b, q); if (x && x.config && /^\\/api\\/home/.test(u)) { x.config.keyon = true; x.config.keyapps = { 8: 'com.google.android.youtube.tv', 9: 'dev.tvremote.channels', 10: 'dev.tvremote.games', 183: 'com.google.android.youtube.tv' }; } return x; }; $('#homeopen').click(); await W(2500); [...document.querySelectorAll('dialog[open] button, dialog[open] summary, dialog[open] .item')].find((b) => /Phím|keys/i.test(b.innerText)).click(); await W(2000);",
                 DLG, 6),
    "hook":     (480, "", "$('#aboutbtn').click(); await W(1500); $('#hookpick [data-v=on]').click(); await W(1500); $('#hookgroup').style.cssText = 'padding:16px;background:var(--bg2);border-radius:14px'; await W(300);", "$('#hookgroup')", 5),
}
OPEN = """const ob = [...document.querySelectorAll('#tvlist button, #savedcard button')].find(b => /^\\s*(Mở|Connect|Open)\\s*$/.test(b.textContent)); if (ob) ob.click(); await W(8000);"""
RENAME = """(() => { const N = %s, w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n;
  while (n = w.nextNode()) n.nodeValue = n.nodeValue.replace(/sdk_google_atv64_amati_arm64/g, N).replace(/127\\.0\\.0\\.1/g, '192.168.1.50').replace(/YouTube Kids/g, 'YouTube'); })()"""


def shot_feat(only=()):
    """feat-<name>-<lang>: see FEATS. `shots.py feat apps dim` makes only those."""
    c = Chrome(480, 1400, 2)
    try:
        for lang in ("vi", "en"):
            for name, (vw, tab, setup, card, g) in FEATS.items():
                if only and name not in only: continue
                c.call("Emulation.setDeviceMetricsOverride", width=vw, height=1400, deviceScaleFactor=2, mobile=False)
                c.open(f"http://127.0.0.1:{WEB_PORT}/?lang={lang}&k={PASS}", 4)
                c.js("window.W = ms => new Promise(r => setTimeout(r, ms))")
                tabjs = f"$('[data-tab={tab}]').click(); await W(3500);" if tab not in ("", "pick") else ""
                opened = "" if tab == "pick" else OPEN
                try:
                    r = c.js("(async () => { wantPick = %s; await W(3000); %s %s %s scrollTo(0, 0); await W(500); const e = %s; if (!e) throw new Error('no card'); e.scrollIntoView({ block: 'center' }); await W(1500);"
                             " window.__e = e; return 1 })()"
                             % ("true" if tab == "pick" else "false", opened, tabjs, setup, card))
                    c.js(RENAME % json.dumps(FEAT_NAMES[lang])); time.sleep(0.5)
                    r = c.js("(() => { const r = window.__e.getBoundingClientRect(); return [r.x + scrollX, r.y + scrollY, r.width, Math.min(r.height, 600)] })()")
                    shot = c.call("Page.captureScreenshot", clip=dict(x=r[0] + 1, y=r[1] + 1, width=r[2] - 2, height=r[3] - 2, scale=2))
                    png = os.path.join(TMP, f"f{time.time_ns()}.png")
                    open(png, "wb").write(base64.b64decode(shot["data"]))
                    save_webp(frame(Image.open(png), r[2], g), f"feat-{name}-{lang}")
                except Exception as e: log("feat", name, lang, "FAILED:", str(e)[:200])
    finally: c.close()


# ---- the TV web page ----------------------------------------------------------------------------------------------------

def adb(serial, *a, text=True):
    return subprocess.run([ADB, "-s", serial, *a], capture_output=True, text=text, check=False).stdout


def screen_texts(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/u.xml")
    return adb(serial, "shell", "cat", "/sdcard/u.xml")


def tv_cookie(base):
    """The TV web page's cookie, paired once with the code read off the TV emulator's screen and kept in .cache/shots."""
    path = os.path.join(CACHE, "tvcookie")
    if os.path.exists(path):
        tok = open(path).read().strip()
        try:
            urllib.request.urlopen(urllib.request.Request(base + "/api/state", headers={"Cookie": "tvr=" + tok})); return tok
        except Exception: pass
    post = lambda p, body: urllib.request.urlopen(urllib.request.Request(base + p, data=json.dumps(body).encode(), method="POST",
                                                                         headers={"Content-Type": "application/json"}))
    post("/api/pair/start", {})
    time.sleep(2)
    code = re.search(r'text="(\d{3} ?\d{3})"', screen_texts(TV))           # shown as "863 801"
    if not code: sys.exit("No pairing code on the TV emulator's screen")
    r = post("/api/pair/finish", {"code": code.group(1).replace(" ", "")})
    tok = re.search(r"tvr=([^;]+)", r.headers["Set-Cookie"]).group(1)
    adb(TV, "shell", "input", "keyevent", "KEYCODE_BACK")             # the code's screen off the TV
    open(path, "w").write(tok)
    return tok


def shot_tv():
    """tvpage-remote-<lang>: the TV web page on a phone-sized browser (400 wide), from the TV emulator's TV Setup."""
    adb(TV, "forward", f"tcp:{TV_FWD}", "tcp:8765")
    base = f"http://127.0.0.1:{TV_FWD}"
    tok = tv_cookie(base)
    for lang in ("vi", "en"):
        js = """(async () => { %s $('#name').textContent = %s; scrollTo(0, 0); await W(3000); %s $('#name').textContent = %s; return 1 })()""" % (SHOW_PICTURE, json.dumps(NAME[lang]), PUT_PICTURE, json.dumps(NAME[lang]))
        img, _ = chrome_shot(f"{base}/?lang={lang}", js, 400, 880, dpr=2, cookie=("tvr", tok))
        c = img.crop((0, 0, 800, 1733))
        save_webp(c.resize((400, 867), Image.LANCZOS), f"tvpage-remote-{lang}")


# ---- the emulators' own screens -----------------------------------------------------------------------------------------

def screencap(serial):
    path = os.path.join(TMP, serial + ".png")
    open(path, "wb").write(adb(serial, "exec-out", "screencap", "-p", text=False))
    img = Image.open(path)
    if img.getextrema() in [((0, 0),) * 3, ((0, 0),) * 4]:          # some phone images give blank screencaps
        adb(serial, "emu", "screenrecord", "screenshot", path); img = Image.open(path)
    return img.convert("RGB")


def tap(serial, text, exact=False):
    for n in re.findall(r"<node [^>]*>", screen_texts(serial)):
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        if (g("text") == text) if exact else (text in g("text") + " " + g("content-desc")):
            a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
            adb(serial, "shell", "input", "tap", str((a + c) // 2), str((b + d) // 2)); return True
    return False


def words(img, box, bg, xy, text, size, weight, color):
    """The emulator's model name over-written by a room's name, in the same face, size and colour."""
    d = ImageDraw.Draw(img)
    d.rectangle(box, fill=bg)
    f = ImageFont.truetype(FONT, size)
    try: f.set_variation_by_axes([weight, 100, 0])
    except Exception: pass
    d.text(xy, text, font=f, fill=color)


def shot_phone():
    """phone-remote: the phone app's remote on the phone emulator, the TV emulator's picture swapped for the captured TV Home."""
    adb(PHONE, "shell", "am", "start", "-n", "dev.tvremote.phone/.MainActivity"); time.sleep(3)
    if tap(PHONE, "10.0.2.2"): time.sleep(6)
    if tap(PHONE, "480p"): time.sleep(1); tap(PHONE, "720p"); time.sleep(5)
    img = screencap(PHONE)
    if img.size != (1080, 2340): sys.exit(f"The phone emulator's screen is {img.size}, the crop below wants 1080×2340")
    img.paste(Image.open(os.path.join(TMP, "vi.png")).convert("RGB").resize((1080, 606), Image.LANCZOS), (0, 308))
    words(img, (160, 168, 760, 229), (17, 20, 24), (167, 173), NAME["vi"], 41, 700, (232, 234, 237))
    save_webp(img.resize((480, 1040), Image.LANCZOS), "phone-remote")


def shot_tvapp():
    """tvapp-<lang>: TV Setup's screen on the TV emulator in each language."""
    app = "dev.tvremote.setup"
    try:
        for lang, loc in (("vi", "vi-VN"), ("en", "en-US")):
            adb(TV, "shell", "cmd", "locale", "set-app-locales", app, "--locales", loc)
            adb(TV, "shell", "am", "force-stop", app)
            adb(TV, "shell", "am", "start", "-n", app + "/.MainActivity"); time.sleep(4)
            if os.environ.get("TV_PIN") and 'text="○' in screen_texts(TV):   # the emulator's TV Setup behind its PIN (a shuffled pad)
                for d in os.environ["TV_PIN"]: tap(TV, d, exact=True)
                time.sleep(2)
            img = screencap(TV)
            if img.size != (1920, 1080): sys.exit(f"The TV emulator's screen is {img.size}, the edit below wants 1920×1080")
            n = re.search(r'text="[^"]*sdk_[^"]*?(&#10;[^"]*)?" [^>]*bounds="\[(\d+),(\d+)\]\[\d+,(\d+)\]"', screen_texts(TV))
            if not n: sys.exit("The emulator's model line is not on TV Setup's screen")
            x, y, b = int(n.group(2)), int(n.group(3)), int(n.group(4))
            h = (b - y) // (2 if n.group(1) else 1)                   # its first line only: the network line under it stays
            words(img, (x - 4, y, x + 760, y + h), img.getpixel((x - 6, y + 2)), (x, y + 1), NAME[lang] + ", Android 14", 26, 400, (140, 150, 163))
            img = img.resize((1280, 720), Image.LANCZOS)
            save_webp(img, f"tvapp-{lang}")
            voice_card(lang)
    finally:
        adb(TV, "shell", "cmd", "locale", "set-app-locales", app, "--locales", "")


VOICE_TITLE = {"vi": "Giọng nói TV Setup", "en": "TV Setup Voice"}   # tvvoice.title


def voice_card(lang):
    """feat-voice-<lang>, the home page's TV Setup Voice card: its screen (VoiceActivity) cut to its panel, on a gradient. The screen
    is not exported, so it is reached as a person does: OK on TV Voice, 4th in the More TV apps row (MainActivity, after Channels)."""
    key = lambda k, n=1: [adb(TV, "shell", "input", "keyevent", k) for _ in range(n)]
    key("KEYCODE_DPAD_DOWN"); time.sleep(0.6)
    key("KEYCODE_DPAD_LEFT", 6); time.sleep(1)
    key("KEYCODE_DPAD_RIGHT", 3); time.sleep(1)
    key("KEYCODE_DPAD_CENTER"); time.sleep(3)
    xml = screen_texts(TV)
    try:
        if VOICE_TITLE[lang] not in xml: sys.exit("TV Setup Voice's screen did not open from the More TV apps row")
        boxes = [tuple(map(int, b)) for b in re.findall(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]
        panel = max((b for b in boxes if b[2] - b[0] < 1800), key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))   # the card: the largest box short of the screen
        cut = screencap(TV).crop(panel)
        save_webp(frame(cut, cut.width / 2, 5), f"feat-voice-{lang}")   # 2 px a CSS px, like the page's cards
    finally:
        key("KEYCODE_BACK"); time.sleep(1)


def shot_og():
    """og-<lang>.jpg, the link previews (X, Facebook, chats): TV Home with its cards, the 1200x630 middle of tvhome-widgets-<lang>.webp,
    which ends under the first row of apps. TV Setup's screen was there before: a settings panel said nothing of what the apps do."""
    for lang in ("vi", "en"):
        Image.open(os.path.join(IMG, f"tvhome-widgets-{lang}.webp")).convert("RGB").crop((40, 0, 1240, 630)) \
            .save(os.path.join(IMG, f"og-{lang}.jpg"), quality=88)
        log(f"docs/img/og-{lang}.jpg")


# ---- all of it ----------------------------------------------------------------------------------------------------------

def main():
    MODES = ("web", "ts", "feat", "tv", "phone", "tvapp", "og")
    args = sys.argv[1:]
    want = [a for a in args if a in MODES] or ["web", "ts", "feat", "tv", "phone", "tvapp", "og"]
    only = [a for a in args if a not in MODES]                        # `feat apps dim`: just those cards
    if only and "feat" not in want: sys.exit(__doc__)
    if not CHROME: sys.exit("Google Chrome not found (set CHROME=…)")
    if not shutil.which("cwebp"): sys.exit("cwebp not found (brew install webp)")
    os.makedirs(CACHE, exist_ok=True)
    pics = srv = None
    try:
        pics = serve_pictures() if {"web", "tv", "phone"} & set(want) else None
        srv = desktop() if {"web", "ts", "feat"} & set(want) else None
        for w in want:
            log("…", w); (shot_feat(only) if w == "feat" else globals()["shot_" + w]())
    finally:
        if srv: srv.kill()
        if pics: pics.shutdown()
        shutil.rmtree(TMP, ignore_errors=True)
    log("done: check the sizes in docs/index.html (width/height of each <img>)")


if __name__ == "__main__":
    main()
