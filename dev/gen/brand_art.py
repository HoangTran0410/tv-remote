#!/usr/bin/env python3
"""The project's TV apps drawn as one family: the same tile, glyph size, stroke and type, each in its own colours, so a row of
them on a TV home reads as one brand (the user, 2026-10-25: four banners, four styles).

  python3 dev/gen/brand_art.py

Writes app/<x>/src/main/res/drawable/banner.png (320x180) for TV Setup, TV Home, TV Channels, TV Games and TV Apps, icon.png (192) for
the last four (TV Setup's icon is the project's logo: dev/gen/round_icons.py), and the copies: TV Setup's More TV apps row
(app/tvsetup/.../eco_*.png), TV Home's TV Setup suggestion (suggest_setup.png) and the site's demo (docs/demo/img/banner-*.png). Drawn at 4x and scaled down. Needs Pillow."""
import pathlib
from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[2]
K = 4
APPS = {                                                             # name, the tile's top-left and bottom-right colours
    "tv": ("Setup", (59, 120, 246), (29, 78, 216)),
    "home": ("Home", (22, 40, 83), (41, 83, 154)),
    "channels": ("Channels", (20, 184, 166), (37, 99, 235)),
    "games": ("Games", (124, 58, 237), (219, 39, 119)),
    "tvapps": ("Apps", (245, 158, 11), (234, 88, 12)),
    "voice": ("Voice", (239, 68, 68), (190, 24, 93)),               # TV Setup Voice (app/tvvoice): its tile in TV Setup's More TV apps row
    "keys": ("Keys", (14, 165, 233), (29, 78, 216)),                # TV Setup Keys (app/tvkeys): TV Setup's blue, lighter at the corner
}
BOLT = (255, 200, 61)                                                # TV Setup's bolt keeps the logo's yellow


def tile(w, h, r, a, b):
    g = Image.new("RGB", (w, h))
    px = g.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(g, (0, 0), m)
    return out


def dot(d, x, y, lw):
    d.ellipse((x - lw / 2, y - lw / 2, x + lw / 2, y + lw / 2), fill="white")


def line(d, pts, lw):                                                # round joins and ends, as the line icons have
    d.line(pts, fill="white", width=lw)
    for x, y in pts:
        dot(d, x, y, lw)


def glyph(kind, d, cx, cy, s, mid):
    """[kind]'s mark centred at (cx, cy), s = its width; [mid] the tile's middle hue (the holes in a filled mark)."""
    lw = max(2, int(s * 0.075))
    if kind == "tv":                                                 # a bolt, filled: the logo's shape
        p = [(0.10, -0.50), (-0.30, 0.06), (-0.02, 0.06), (-0.12, 0.50), (0.30, -0.08), (0.02, -0.08)]
        d.polygon([(cx + x * s, cy + y * s) for x, y in p], fill=BOLT)
    elif kind == "home":                                             # a house: roof, walls, a door
        w, h = s * 0.80, s * 0.48
        x0, x1, base = cx - w / 2, cx + w / 2, cy + s * 0.36
        line(d, [(cx - s * 0.5, cy - s * 0.02), (cx, cy - s * 0.44), (cx + s * 0.5, cy - s * 0.02)], lw)
        line(d, [(x0 + s * 0.06, cy - s * 0.06), (x0 + s * 0.06, base), (x1 - s * 0.06, base), (x1 - s * 0.06, cy - s * 0.06)], lw)
        dw = s * 0.18
        line(d, [(cx - dw / 2, base), (cx - dw / 2, base - h * 0.5), (cx + dw / 2, base - h * 0.5), (cx + dw / 2, base)], lw)
    elif kind == "channels":                                         # a screen with two antenna ears and a play mark
        w, h = s, s * 0.66
        x0, y0 = cx - w / 2, cy - h / 2 + s * 0.08
        for sx in (-1, 1):
            line(d, [(cx + sx * s * 0.2, y0 - s * 0.22), (cx, y0 + lw / 2)], lw)
        d.rounded_rectangle((x0, y0, x0 + w, y0 + h), s * 0.12, outline="white", width=lw)
        t = s * 0.16
        d.polygon([(cx - t * 0.7, cy - t + s * 0.08), (cx - t * 0.7, cy + t + s * 0.08), (cx + t, cy + s * 0.08)], fill="white")
    elif kind == "tvapps":                                           # a box, as the page's 📦 line icon: three faces meeting in the middle
        r, top, mid_y = s * 0.46, cy - s * 0.48, cy - s * 0.2
        rim = [(cx, top), (cx + r, mid_y), (cx + r, cy + s * 0.26), (cx, cy + s * 0.52), (cx - r, cy + s * 0.26), (cx - r, mid_y), (cx, top)]
        line(d, rim, lw)
        line(d, [(cx - r, mid_y), (cx, cy + s * 0.06), (cx + r, mid_y)], lw)
        line(d, [(cx, cy + s * 0.06), (cx, cy + s * 0.52)], lw)
    elif kind == "voice":                                            # a mic: a capsule, the holder's arc, a stem and a foot
        cw, ch = s * 0.34, s * 0.56
        top = cy - s * 0.50
        d.rounded_rectangle((cx - cw / 2, top, cx + cw / 2, top + ch), cw / 2, fill="white")
        r = s * 0.30
        d.arc((cx - r, top + ch * 0.62 - r, cx + r, top + ch * 0.62 + r), 0, 180, fill="white", width=lw)
        line(d, [(cx, top + ch * 0.62 + r), (cx, cy + s * 0.46)], lw)
        line(d, [(cx - s * 0.18, cy + s * 0.46), (cx + s * 0.18, cy + s * 0.46)], lw)
    elif kind == "keys":                                             # a key: a ring, a shaft and two teeth
        r = s * 0.20
        ox, oy = cx - s * 0.26, cy - s * 0.26
        d.ellipse((ox - r, oy - r, ox + r, oy + r), outline="white", width=lw * 2)
        a, b = (ox + r * 0.72, oy + r * 0.72), (cx + s * 0.44, cy + s * 0.44)
        line(d, [a, b], lw * 2)
        line(d, [(cx + s * 0.16, cy + s * 0.16), (cx + s * 0.34, cy - s * 0.02)], lw * 2)
        line(d, [(cx + s * 0.30, cy + s * 0.30), (cx + s * 0.44, cy + s * 0.16)], lw * 2)
    else:                                                            # a pad: white body, a cross, two buttons
        w, h = s, s * 0.56
        x0, y0 = cx - w / 2, cy - h / 2
        d.rounded_rectangle((x0, y0, x0 + w, y0 + h), h * 0.5, fill="white")
        a, t = s * 0.22, s * 0.042
        px = x0 + w * 0.29
        d.rectangle((px - a / 2, cy - t, px + a / 2, cy + t), fill=mid)
        d.rectangle((px - t, cy - a / 2, px + t, cy + a / 2), fill=mid)
        r = s * 0.06
        for bx, by in ((x0 + w * 0.66, cy + s * 0.06), (x0 + w * 0.78, cy - s * 0.06)):
            d.ellipse((bx - r, by - r, bx + r, by + r), fill=mid)


def font(weight, size):
    for f in ("/System/Library/Fonts/SFNSRounded.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        try:
            ft = ImageFont.truetype(f, size)
            try:
                ft.set_variation_by_name(weight)
            except Exception:
                pass
            return ft
        except OSError:
            pass
    return ImageFont.load_default()


small, big = font("Semibold", 26 * K), font("Bold", 36 * K)
DIR = {"tv": "tvsetup", "home": "tvhome", "channels": "tvchannels", "games": "tvgames", "voice": "tvvoice", "keys": "tvkeys"}   # glyph and eco_*.png names -> app folder
for app, (name, a, b) in APPS.items():
    mid = tuple((a[i] + b[i]) // 2 for i in range(3))
    out = ROOT / f"app/{DIR.get(app, app)}/src/main/res/drawable"
    out.mkdir(parents=True, exist_ok=True)
    ban = tile(320 * K, 180 * K, 0, a, b).convert("RGB")
    d = ImageDraw.Draw(ban)
    glyph(app, d, 72 * K, 90 * K, 80 * K, mid)
    d.text((128 * K, 74 * K), "TV", fill=(255, 255, 255), font=small, anchor="lm")   # "TV" over the name: one shape for all four
    d.text((126 * K, 108 * K), name, fill="white", font=big, anchor="lm")
    ban = ban.resize((320, 180), Image.LANCZOS)
    ban.save(out / "banner.png")
    if app == "tv":                                                  # TV Home's suggestion tile for it, while it is not installed
        ban.save(ROOT / "app/tvhome/src/main/res/drawable/suggest_setup.png")
    if app not in ("tv", "keys"):                                    # keys: an add-on TV Setup installs by itself, no tile in its row
        ban.save(ROOT / f"app/tvsetup/src/main/res/drawable/eco_{app}.png")
    if app != "tv":
        icon = tile(192 * K, 192 * K, 44 * K, a, b)
        glyph(app, ImageDraw.Draw(icon), 96 * K, 96 * K, 120 * K, mid)
        icon.resize((192, 192), Image.LANCZOS).save(out / "icon.png")
    demo = ROOT / f"docs/demo/img/banner-{'setup' if app == 'tv' else app}.png"
    if demo.exists():
        Image.open(out / "banner.png").save(demo)
print("banners and icons written")
