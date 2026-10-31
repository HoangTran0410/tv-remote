#!/usr/bin/env python3
"""dev/gen/wallpapers.py: makes TV Home's wallpaper gallery (docs/home/): 1920x1080 JPEGs (about 150-400 KB), 320x180 thumbnails,
and wallpapers.json (SHA-256 of both, credit, source, licence), then signs it like the launcher list (launchers.key, Ed25519).
Pictures: NASA images (public domain; ESA / CSA credits kept) fetched from images-api.nasa.gov, photos from Unsplash and Wikimedia Commons (PHOTOS and VN below; a Commons file's author and licence are read from Commons itself;
the ready-made themes of docs/home/themes.json show them through loop.html), and two renders made here (CC0).
Also "links": the moving backgrounds and pages TV Home suggests (PEXELS, links(); LINKS_ONLY=1 makes only those again).
Run again after changing WALLS; commit docs/home/ whole. Needs Pillow and a JDK."""
import hashlib, io, json, os, subprocess, urllib.request
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "docs", "home")
CACHE = os.path.join(ROOT, ".cache", "wallpapers")
KEY = os.environ.get("LAUNCHERS_KEY", os.path.expanduser("~/.tv-remote-release/launchers.key"))
# id, NASA id (None: a render), words, credit, crop (0 top .. 1 bottom of what is cut off)
WALLS = [
    ("cosmic-cliffs", "carina_nebula", {"vi": "Vách đá vũ trụ", "en": "Cosmic Cliffs"}, "NASA, ESA, CSA, STScI (James Webb)", .5),
    ("carina-hubble", "GSFC_20171208_Archive_e002076", {"vi": "Tinh vân Carina", "en": "Carina Nebula"}, "NASA, ESA (Hubble)", .5),
    ("lagoon", "GSFC_20171208_Archive_e001955", {"vi": "Tinh vân Đầm phá", "en": "Lagoon Nebula"}, "NASA, ESA (Hubble)", .5),
    ("aurora-green", "iss072e188141", {"vi": "Cực quang xanh", "en": "Green aurora"}, "NASA (International Space Station)", .5),
    ("aurora-red", "iss072e451060", {"vi": "Cực quang đỏ", "en": "Red aurora"}, "NASA (International Space Station)", .5),
    ("aurora-glow", "iss072e159172", {"vi": "Ánh cực quang", "en": "Aurora glow"}, "NASA (International Space Station)", .5),
    ("orbit-night", "iss072e709711", {"vi": "Đêm trên quỹ đạo", "en": "Night in orbit"}, "NASA (International Space Station)", .5),
    ("sunrise-orbit", "iss073e0982823", {"vi": "Bình minh từ quỹ đạo", "en": "Sunrise from orbit"}, "NASA (International Space Station)", .5),
    ("city-lights", "iss025e015176", {"vi": "Đèn thành phố", "en": "City lights"}, "NASA (International Space Station)", .5),
    ("snow-ranges", "iss074e0247458", {"vi": "Dãy núi tuyết", "en": "Snowy ranges"}, "NASA (International Space Station)", .5),
    ("soft-violet", None, {"vi": "Tím dịu", "en": "Soft violet"}, "TV Remote", [(0x1a0f3a, 0, 0), (0x6a3fa0, .75, .3), (0x2b6cb0, .2, .85), (0xd9577a, .95, .95)]),
    ("soft-teal", None, {"vi": "Xanh biển đêm", "en": "Deep teal"}, "TV Remote", [(0x04141f, 0, 0), (0x0f6e7a, .3, .35), (0x1d3f7a, .85, .8), (0x2fb39a, .05, .95)]),
]
# Photos of the ready-made themes and the Vietnam slideshow come from Unsplash (Unsplash License: free to use, credit kept anyway). U(photo, photographer, page):
# `photo` is the images.unsplash.com name, `page` the id on unsplash.com/photos/. A plain string is a Commons file name.
# Picked by eye for a home screen: calm, few details, one colour field behind the tiles (the user, 2026-10-26: busy Unsplash shots of
# lanterns and a stadium crowd lost to the older Commons ones).
def U(photo, author, page): return {"photo": photo, "author": author, "page": "https://unsplash.com/photos/" + page}
# id, picture, words, (x, y) = where the 16:9 window sits in what is cut off (0 left/top .. 1 right/bottom)
PHOTOS = [
    ("terraces", U("photo-1733372607228-6aeaa92c5e62", "Vu Thang", "0CaHnK7PVCo"), {"vi": "Ruộng bậc thang", "en": "Rice terraces"}, (.5, .5)),
    ("beach-sunset", U("photo-1670811456186-e73d0ace9454", "Rolando Yera", "ATwi-EmGbSE"), {"vi": "Hoàng hôn biển", "en": "Sunset beach"}, (.5, .5)),
    ("city-night", U("photo-1556379092-dca659792591", "Pang Yuhao", "wCi28eq8TF4"), {"vi": "Đêm thành phố", "en": "City night"}, (.5, .5)),
    ("pitch-night", "The Peninsula Stadium at night - Salford City - Aug 24.jpg", {"vi": "Sân cỏ đêm", "en": "Pitch at night"}, (.5, .5)),
    ("lanterns-red", "Chinese Lanterns at Night in Chinatown (2).jpg", {"vi": "Đèn lồng đỏ", "en": "Red lanterns"}, (.5, .5)),
    ("theatre-red", "Theatre Lionel Groulx.jpg", {"vi": "Rạp hát", "en": "Theatre"}, (.5, .5)),
    ("tulips-sunset", "Tulip field in Holland2.jpg", {"vi": "Cánh đồng tulip", "en": "Tulip field"}, (.5, .6)),
    ("milky-way", U("photo-1649212056819-983d39d12e5d", "Jason Bonnicksen", "R2uhkW7ik_Y"), {"vi": "Dải Ngân Hà", "en": "Milky Way"}, (.5, .5)),
]
# The "Việt Nam" theme's slideshow (docs/home/vn.json, shown by TV Home natively and by loop.html?s=vn): id, picture, vi, en, (x, y) as PHOTOS.
# Not in the gallery list (wallpapers.json): they are only reached through the theme.
VN = [
    ("halong", U("photo-1764645859246-8c6db98330a7", "Vivu Vietnam", "wTJQjCQm90k"), "Vịnh Hạ Long lúc hoàng hôn", "Ha Long Bay at sunset", (.5, .5)),
    ("mucangchai", U("photo-1695289566332-08eb1e223b6e", "Huy Nguyen", "F4qZ8hdbp2k"), "Mù Cang Chải", "Mu Cang Chai terraces", (.5, .5)),
    ("hoian", U("photo-1582379488743-cc42257bd033", "Benjamin Wong", "J4qoCOug9EY"), "Đèn lồng Hội An", "Hoi An lanterns", (.5, .5)),
    ("danang", U("photo-1790134773057-af82e32e68fd", "Vivu Vietnam", "QsQMtWfqGOM"), "Cầu Vàng, Đà Nẵng", "Golden Bridge, Da Nang", (.5, .5)),
    ("ninhbinh", U("photo-1725209276479-59220882e9b0", "Jacob Pretorius", "6Rm7o1eEIgc"), "Ninh Bình", "Ninh Binh", (.5, .5)),
    ("saigon", U("photo-1583417319070-4a69db38a482", "Tron Le", "wUk2U5Wirxg"), "Sài Gòn hoàng hôn", "Saigon at dusk", (.5, .5)),
    ("halongair", U("photo-1748102289451-da1109f5c3bd", "Ryan Le", "ONObTZNkkz0"), "Vịnh Hạ Long từ trên cao", "Ha Long Bay from above", (.5, .5)),
    ("lua", U("photo-1609412058473-c199497c3c5d", "Hoach Le Dinh", "PeRt3uMmjYM"), "Lúa chín bậc thang", "Ripe terraces", (.5, .5)),
    ("dalat", U("photo-1678099006439-dba9e4d3f9f5", "Pete Walls", "Fl3bY0hWXv4"), "Đà Lạt trong sương", "Da Lat pines in mist", (.5, .5)),
    ("ninhbinh2", U("photo-1656692197297-cb1340b4d538", "Hoang Vu Tuyen", "E2jYY1GxAzE"), "Ninh Bình từ trên cao", "Ninh Binh from above", (.5, .5)),
    ("phuquoc", U("photo-1787283868886-f07766e50c85", "Vivu Vietnam", "S_ZcExscv0E"), "Phú Quốc bình minh", "Phu Quoc sunrise", (.5, .5)),
]
UA = {"User-Agent": "tv-remote-wallpapers/1.0 (https://github.com/HoangTran0410/tv-remote)"}


def get(u):
    import time
    for k in range(6):
        try: return urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=120).read()
        except urllib.error.HTTPError as e:
            if e.code != 429: raise
            time.sleep(8 * (k + 1))
    raise SystemExit("429 from " + u)


def unsplash(u):
    """(picture, credit, page, licence) of an Unsplash photo: 2560 px wide copy from the image CDN (no key needed)."""
    f = os.path.join(CACHE, "unsplash-" + u["photo"] + ".jpg")
    os.makedirs(CACHE, exist_ok=True)
    if not os.path.exists(f):
        open(f, "wb").write(get("https://images.unsplash.com/" + u["photo"] + "?w=2560&q=85&fm=jpg"))
    return Image.open(f).convert("RGB"), u["author"] + " / Unsplash", u["page"], "Unsplash License"


def commons(name):
    """(picture, credit, page, licence) of a Commons file: 2560 px wide copy, author and licence from its own page."""
    import re, time, urllib.parse
    f = os.path.join(CACHE, "commons-" + re.sub(r"[^A-Za-z0-9.]+", "_", name))
    q = urllib.parse.urlencode({"action": "query", "format": "json", "titles": "File:" + name, "prop": "imageinfo", "iiprop": "extmetadata",
                                "iiextmetadatafilter": "LicenseShortName|Artist"})
    time.sleep(1.5)
    m = json.loads(get("https://commons.wikimedia.org/w/api.php?" + q))["query"]["pages"]
    m = next(iter(m.values()))["imageinfo"][0]["extmetadata"]
    lic = m["LicenseShortName"]["value"]
    author = re.sub(r"\s+", " ", re.sub("<[^>]+>", "", m.get("Artist", {}).get("value", ""))).strip()
    os.makedirs(CACHE, exist_ok=True)
    if not os.path.exists(f):
        open(f, "wb").write(get("https://commons.wikimedia.org/wiki/Special:FilePath/" + urllib.parse.quote(name) + "?width=2560"))
    return Image.open(f).convert("RGB"), author, "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(name.replace(" ", "_")), lic


def photo(src):
    """An Unsplash U(...) or a Commons file name."""
    return unsplash(src) if isinstance(src, dict) else commons(src)


def fetch(nid):
    os.makedirs(CACHE, exist_ok=True)
    f = os.path.join(CACHE, nid + ".jpg")
    if not os.path.exists(f):
        def j(u): return json.load(urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "tv-remote"}), timeout=30))
        jp = [x["href"] for x in j("https://images-api.nasa.gov/asset/" + nid)["collection"]["items"] if x["href"].lower().endswith((".jpg", ".jpeg"))]
        pick = next((u for u in jp if "~large" in u), None) or next(u for u in jp if "~orig" in u)
        open(f, "wb").write(urllib.request.urlopen(urllib.request.Request(pick.replace(" ", "%20"), headers={"User-Agent": "tv-remote"}), timeout=90).read())
    return Image.open(f).convert("RGB")


def render(blobs):
    w, h = 192, 108
    im = Image.new("RGB", (w, h), "#%06x" % blobs[0][0])
    for c, x, y in blobs[1:]:
        m = Image.new("L", (w, h), 0)
        r = int(h * .9)
        ImageDraw.Draw(m).ellipse((x * w - r, y * h - r, x * w + r, y * h + r), fill=255)
        im.paste(Image.new("RGB", (w, h), "#%06x" % c), mask=m.filter(ImageFilter.GaussianBlur(h * .45)))
    return Image.eval(im, lambda v: int(v * .5)).resize((1920, 1080), Image.BICUBIC)   # dark: the clock and the names stay readable


def fit(im, at, ax=.5):
    w, h = im.size
    t = w * 9 // 16
    if h > t:
        top = int((h - t) * at)
        im = im.crop((0, top, w, top + t))
    elif h < t:
        left = int((w - h * 16 // 9) * ax)
        im = im.crop((left, 0, left + h * 16 // 9, h))
    return im.resize((1920, 1080), Image.LANCZOS)


def save(im, path, **k):
    b = io.BytesIO()
    im.save(b, "JPEG", optimize=True, progressive=True, **k)
    open(path, "wb").write(b.getvalue())
    return hashlib.sha256(b.getvalue()).hexdigest(), len(b.getvalue())


def vn():
    """docs/home/vn-*.jpg (1920x1080), vn-t.jpg (the theme's collage thumb) and vn.json (the list TV Home and loop.html read)."""
    items = []
    for i, name, vi, en, (ax, ay) in VN:
        im, author, page, lic = photo(name)
        im = fit(im, ay, ax)
        _, n = save(im, os.path.join(OUT, "vn-" + i + ".jpg"), quality=82)
        save(im.resize((320, 180), Image.LANCZOS), os.path.join(OUT, "vn-" + i + "-t.jpg"), quality=72)   # the themes page's slideshow card cycles these
        items.append({"file": "vn-" + i + ".jpg", "vi": vi, "en": en, "credit": author, "source": page})
        print("vn", i, n // 1024, "KB", author, lic)
    _, author, page, _ = photo(PHOTOS[0][1])   # the Rice terraces theme's photo rides along
    items.append({"file": "terraces.jpg", "vi": PHOTOS[0][2]["vi"], "en": PHOTOS[0][2]["en"], "credit": author, "source": page})
    col = Image.new("RGB", (320, 180))
    for k, f in enumerate(["vn-halong", "vn-hoian", "vn-danang", "vn-mucangchai"]):
        col.paste(Image.open(os.path.join(OUT, f + ".jpg")).resize((160, 90), Image.LANCZOS), ((k % 2) * 160, (k // 2) * 90))
    save(col, os.path.join(OUT, "vn-t.jpg"), quality=72)
    json.dump({"v": 1, "slides": items}, open(os.path.join(OUT, "vn.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    open(os.path.join(OUT, "vn.json"), "a").write("\n")

# Moving backgrounds and pages TV Home suggests beside the pictures (wallpapers.json "links": id, kind video | slides | page, names,
# url = the "wurl" it sets, thumb on the site pinned by SHA-256). Videos: Pexels License, 1920x1080, calm and small (a weak box
# plays them; the rain clip at 35 MB was left out); loop.html shows them with the thumb blurred until the first frame.
# Pages: only ones that need no login, show no ads and stay silent (earth.nullschool.net; NASA Eyes, Stellarium, lofi.co were heavy and full of menus).
PEXELS = [
    ("fireplace", 855381, 30, "free-video-855381.jpg", {"vi": "Lò sưởi", "en": "Fireplace", "ru": "Камин"}),
    # made to loop (found 2026-10-31 among ~40 calm clips by dev/gen/loop_seam.py); newer Pexels files carry their own name, not "<id>-hd_..."
    ("pastel", 7898649, 15, None, {"vi": "Chuyển màu pastel", "en": "Pastel glow", "ru": "Пастельное сияние"}),
    ("colour-flow", 11387730, 30, None, {"vi": "Sắc màu trôi", "en": "Colour flow", "ru": "Цветной поток"}),
    ("pink-waves", 34396899, "14572011_1920_1080_30fps.mp4", None, {"vi": "Sóng hồng", "en": "Pink waves", "ru": "Розовые волны"}),
    ("glow-dots", 34567729, "14646022_1920_1080_30fps.mp4", None, {"vi": "Hạt sáng", "en": "Glowing dots", "ru": "Светящиеся точки"}),
    ("stardust", 12920706, 30, None, {"vi": "Bụi sao", "en": "Stardust", "ru": "Звёздная пыль"}),
    ("aurora-harbor", 5600929, 25, "pexels-photo-5600929.jpeg", {"vi": "Cực quang cảng", "en": "Harbour aurora", "ru": "Сияние над гаванью"}),
    ("aurora-peaks", 852435, 30, "free-video-852435.jpg", {"vi": "Cực quang núi", "en": "Aurora peaks", "ru": "Сияние над горами"}),
    ("aquarium", 2556513, 25, "free-video-2556513.jpg", {"vi": "Bể cá", "en": "Aquarium", "ru": "Аквариум"}),
    ("milky-way-live", 1926295, 25, "free-video-1926295.jpg", {"vi": "Dải Ngân Hà", "en": "Milky Way", "ru": "Млечный Путь"}),
    ("night-sky", 1877846, 30, "free-video-1877846.jpg", {"vi": "Bầu trời đêm", "en": "Night sky", "ru": "Ночное небо"}),
    ("cloud-sea", 5721853, 30, "pexels-photo-5721853.jpeg", {"vi": "Biển mây", "en": "Sea of clouds", "ru": "Море облаков"}),
    ("mountain-mist", 2888383, 24, "free-video-2888383.jpg", {"vi": "Mây phủ núi", "en": "Mountain mist", "ru": "Туман в горах"}),
    ("city-timelapse", 1654216, 30, "free-video-1654216.jpg", {"vi": "Phố đêm", "en": "City night", "ru": "Ночной город"}),
    ("beach-live", 18914444, 30, None, {"vi": "Hoàng hôn biển", "en": "Sunset beach", "ru": "Закат на пляже"}),
    ("halong-live", 3783927, 24, "pexels-photo-3783927.jpeg", {"vi": "Vịnh Hạ Long", "en": "Ha Long Bay", "ru": "Бухта Халонг"}),
]
# The clips whose last frame is their first (dev/gen/loop_seam.py: the jump at the turn within ~2 frame steps); every other one gets
# "fade": TV Home dissolves its turn (HomeConfig "wfade"). Measured 2026-10-31: fireplace 1.6x, the rest 4.5x-130x.
LOOPS = {"fireplace", "pastel", "colour-flow", "pink-waves", "glow-dots", "stardust"}
BEACH_POSTER = "https://images.pexels.com/videos/26310139/beach-background-beach-waves-beautiful-sunset-crashing-waves-26310139.jpeg?auto=compress&cs=tinysrgb&w=640"
SITE = "https://tvremote.pages.dev/home/"


def links():
    from urllib.parse import quote
    out = []
    for i, vid, fps, poster, names in PEXELS:
        mp4 = "https://videos.pexels.com/video-files/%d/" % vid + (fps if isinstance(fps, str) else "%d-hd_1920_1080_%dfps.mp4" % (vid, fps))
        if poster is None and i != "beach-live":                 # no poster known: a frame of the clip itself
            pic = subprocess.run(["ffmpeg", "-v", "error", "-user_agent", "Mozilla/5.0", "-ss", "3", "-i", mp4, "-frames:v", "1", "-f", "image2pipe", "-c:v", "png", "-"], capture_output=True, check=True).stdout
        else:
            pic = get(BEACH_POSTER if poster is None else "https://images.pexels.com/videos/%d/%s?auto=compress&cs=tinysrgb&w=640" % (vid, poster))
        t = "v-" + i + "-t.jpg"
        tsha, _ = save(fit(Image.open(io.BytesIO(pic)).convert("RGB"), .5).resize((320, 180), Image.LANCZOS), os.path.join(OUT, t), quality=72)
        out.append({"id": i, "kind": "video", "name": names, "url": SITE + "loop.html?v=" + quote(mp4, safe="") + "&p=" + quote(SITE + t, safe=""),
                    "thumb": t, "thumb_sha256": tsha, "fade": i not in LOOPS, "credit": "Pexels", "source": "https://www.pexels.com/video/%d/" % vid, "license": "Pexels License"})
        print("link", i)
    vt = open(os.path.join(OUT, "vn-t.jpg"), "rb").read()
    out.insert(0, {"id": "vn", "kind": "slides", "name": {"vi": "Việt Nam", "en": "Việt Nam", "ru": "Вьетнам"},
                   "url": SITE + "loop.html?s=vn", "thumb": "vn-t.jpg", "thumb_sha256": hashlib.sha256(vt).hexdigest(), "credit": "Unsplash, Wikimedia Commons",
                   "source": SITE + "vn.json", "license": "Unsplash License, CC BY-SA"})
    out.append({"id": "earth-wind", "kind": "page", "name": {"vi": "Gió toàn cầu", "en": "Earth winds", "ru": "Ветра Земли"},
                "url": "https://earth.nullschool.net/#current/wind/surface/level/orthographic=107,16,600", "credit": "Cameron Beccario",
                "source": "https://earth.nullschool.net/about.html", "license": "", "heavy": True})
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    if os.environ.get("VN_ONLY"): return vn()          # only the Vietnam slideshow; the gallery list stays as signed
    lst = os.path.join(OUT, "wallpapers.json")
    if os.environ.get("LINKS_ONLY"):                   # only the suggested videos and pages: the pictures stay as they are
        d = json.load(open(lst, encoding="utf-8")); d["links"] = links()
        return write(lst, d)
    items = []
    for i, nid, name, credit, a in WALLS:
        im = render(a) if nid is None else fit(fetch(nid), a)
        sha, n = save(im, os.path.join(OUT, i + ".jpg"), quality=82 if nid else 90)
        tsha, _ = save(im.resize((320, 180), Image.LANCZOS), os.path.join(OUT, i + "-t.jpg"), quality=72)
        e = {"id": i, "name": name, "file": i + ".jpg", "sha256": sha, "bytes": n, "thumb": i + "-t.jpg", "thumb_sha256": tsha, "credit": credit,
             "source": "https://images.nasa.gov/details/" + nid if nid else "https://github.com/HoangTran0410/tv-remote",
             "license": "Public domain (NASA)" if nid else "CC0"}
        items.append(e)
        print(i, n // 1024, "KB")
    for i, name, words, (ax, ay) in PHOTOS:
        im, author, page, lic = photo(name)
        im = fit(im, ay, ax)
        sha, n = save(im, os.path.join(OUT, i + ".jpg"), quality=84)
        tsha, _ = save(im.resize((320, 180), Image.LANCZOS), os.path.join(OUT, i + "-t.jpg"), quality=72)
        items.append({"id": i, "name": words, "file": i + ".jpg", "sha256": sha, "bytes": n, "thumb": i + "-t.jpg", "thumb_sha256": tsha,
                      "credit": author, "source": page, "license": lic})
        print(i, n // 1024, "KB", author, lic)
    write(lst, {"v": 1, "walls": items, "links": links()})
    vn()


def write(lst, d):
    json.dump(d, open(lst, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    open(lst, "a").write("\n")
    subprocess.run(["java", os.path.join(ROOT, "dev", "release", "Sign.java"), "sign", KEY, lst], check=True)
    print("signed", lst)


main()
