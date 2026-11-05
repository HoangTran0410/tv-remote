#!/usr/bin/env python3
"""dev/gen/game_logos.py: draws the 640x360 logo card of every TV Games catalog game into docs/games/img/<id>.v2.jpg
(the web games; modern look) or <id>.v3.jpg (the NES / homebrew games: pixel look, `draw_retro`, RETRO below).

One family look: a two-colour background with a soft glow, the game's name on top (Arial Bold, white, shadowed),
a sticker-style emblem (flat shapes, dark outline) in the middle. The bottom-left 22% x 22% stays empty: TV Home draws
the TV Games badge there when a game is pinned. Everything is drawn from shapes (no symbol glyphs), at 3x and shrunk.

  python3 dev/gen/game_logos.py            # all games   (pass ids to draw only some)
  python3 dev/gen/game_logos.py --sheet X  # also a contact sheet to X

The files are committed (nothing in docs/ is generated at build time). A changed drawing: give the file a new name
(the `.vN` of FILE below), so TVs that cached the old picture fetch it again, and update `img` in docs/games/games.json.
"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "docs", "games", "img")
SUFFIX = ".v2"                                     # bump with a redraw (Thumbs.kt keys its cache by the img path)
W, H, S = 640, 360, 3
OL = (22, 26, 44)                                  # the outline every shape gets
OW = 5
CX, CY = 340, 212                                  # emblem centre: right of the badge corner, below the name
FONTS = ["/System/Library/Fonts/Supplemental/Arial Bold.ttf"]           # the one font here with every Vietnamese diacritic
K, CYT = 1.1, 226                                  # emblems are drawn around (CX, CY), then scaled by K to centre on (CX, CYT)


def hx(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) if isinstance(c, str) else c


class G:
    def __init__(self, img=None):
        self.img = img or Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def _p(self, pts):
        return [(x * S, y * S) for x, y in pts]

    def circ(self, x, y, r, fill=None, ol=OL, w=OW):
        self.ell(x, y, r, r, fill, ol, w)

    def ell(self, x, y, rx, ry, fill=None, ol=OL, w=OW):
        self.d.ellipse([(x - rx) * S, (y - ry) * S, (x + rx) * S, (y + ry) * S], fill=hx(fill) if fill else None,
                       outline=hx(ol) if ol else None, width=w * S)

    def rr(self, x0, y0, x1, y1, r, fill=None, ol=OL, w=OW):
        self.d.rounded_rectangle([x0 * S, y0 * S, x1 * S, y1 * S], r * S, fill=hx(fill) if fill else None,
                                 outline=hx(ol) if ol else None, width=w * S)

    def poly(self, pts, fill=None, ol=OL, w=OW):
        p = self._p(pts)
        self.d.polygon(p, fill=hx(fill) if fill else None)
        if ol:
            self.d.line(p + [p[0], p[1]], fill=hx(ol), width=w * S, joint="curve")
            for q in p:
                self.d.ellipse([q[0] - w * S / 2, q[1] - w * S / 2, q[0] + w * S / 2, q[1] + w * S / 2], fill=hx(ol))

    def line(self, pts, w, fill, caps=True):
        p = self._p(pts)
        self.d.line(p, fill=hx(fill), width=int(w * S), joint="curve")
        if caps:
            for q in (p[0], p[-1]):
                self.d.ellipse([q[0] - w * S / 2, q[1] - w * S / 2, q[0] + w * S / 2, q[1] + w * S / 2], fill=hx(fill))

    def stroke(self, pts, w, fill, ol=OL):                     # a thick outlined line
        self.line(pts, w + 2 * OW, ol)
        self.line(pts, w, fill)

    def arc(self, x, y, r, a0, a1, w, fill):
        self.d.arc([(x - r) * S, (y - r) * S, (x + r) * S, (y + r) * S], a0, a1, fill=hx(fill), width=int(w * S))

    def star(self, x, y, r1, r2, n, fill, rot=-90, ol=OL, w=OW):
        pts = []
        for i in range(n * 2):
            a = math.radians(rot + i * 180 / n)
            r = r1 if i % 2 == 0 else r2
            pts.append((x + r * math.cos(a), y + r * math.sin(a)))
        self.poly(pts, fill, ol, w)

    def rot(self, fn, ang, px, py):                            # draw fn on its own layer, turn it ang degrees (counter-clockwise)
        g = G()
        fn(g)
        self.img.alpha_composite(g.img.rotate(ang, center=(px * S, py * S), resample=Image.BICUBIC))
        self.d = ImageDraw.Draw(self.img)


# ---- small parts -------------------------------------------------------------------------------------------------

def heart(g, x, y, s, fill):
    g.circ(x - s * .5, y - s * .3, s * .56, fill, None)
    g.circ(x + s * .5, y - s * .3, s * .56, fill, None)
    g.poly([(x - s * 1.04, y - .05 * s), (x + s * 1.04, y - .05 * s), (x, y + s * 1.15)], fill, None)


def spade(g, x, y, s, fill):
    g.circ(x - s * .5, y + s * .25, s * .56, fill, None)
    g.circ(x + s * .5, y + s * .25, s * .56, fill, None)
    g.poly([(x - s * 1.04, y + .2 * s), (x + s * 1.04, y + .2 * s), (x, y - s * 1.15)], fill, None)
    g.poly([(x, y + s * .2), (x - s * .4, y + s * 1.1), (x + s * .4, y + s * 1.1)], fill, None)


def card(g, x, y, w, h, ang, fill, face):
    def f(l):
        l.rr(x - w / 2, y - h / 2, x + w / 2, y + h / 2, 12, fill)
        face(l, x, y)
    g.rot(f, ang, x, y + h)


def bomb(g, x, y, r, spark=True):
    g.line([(x + r * .5, y - r * .8), (x + r * .85, y - r * 1.35), (x + r * 1.35, y - r * 1.4)], 7, OL)
    g.line([(x + r * .5, y - r * .8), (x + r * .85, y - r * 1.35), (x + r * 1.35, y - r * 1.4)], 3, "#d8b27a")
    g.circ(x, y, r, "#3a4160")
    g.rot(lambda l: l.rr(x - r * .32, y - r * 1.12, x + r * .32, y - r * .75, 4, "#59618a"), -25, x, y)
    g.ell(x - r * .35, y - r * .35, r * .22, r * .14, "#8d95bf", None)
    if spark:
        g.star(x + r * 1.45, y - r * 1.45, r * .5, r * .2, 5, "#ffd23f", rot=-70)


def paddle(g, x, y, r, ang, fill, handle="#d9a066"):
    def f(l):
        l.rr(x - r * .22, y + r * .7, x + r * .22, y + r * 1.9, 6, handle)
        l.circ(x, y, r, fill)
        l.circ(x, y, r * .62, None, "#ffffff55" if False else _lighter(fill), 3)
    g.rot(f, ang, x, y)


def _lighter(c):
    c = hx(c)
    return tuple(min(255, v + 70) for v in c)


# ---- the emblems: each draws around (CX, CY), about 260 x 170 --------------------------------------------------------

def hockey(g):
    g.rr(CX - 150, CY - 85, CX + 150, CY + 85, 34, "#1d3f78", "#9bd6ff", 5)
    g.line([(CX, CY - 80), (CX, CY + 80)], 5, "#9bd6ff", False)
    g.circ(CX, CY, 34, None, "#9bd6ff", 5)
    for x, c in ((CX - 98, "#2fb4ff"), (CX + 98, "#ff4d5e")):
        g.circ(x, CY, 42, c)
        g.circ(x, CY, 20, _lighter(c))
    g.circ(CX, CY, 22, "#20233a")
    g.circ(CX, CY, 11, "#4a5078", None)


def tanks(g):
    g.rr(CX - 125, CY - 10, CX + 125, CY + 80, 38, "#2a3350")
    for i in range(7):
        g.circ(CX - 90 + i * 30, CY + 35, 11, "#6b769c", OL, 3)
    g.rr(CX - 105, CY - 50, CX + 105, CY + 30, 22, "#58c26a")
    g.rr(CX - 105, CY + 5, CX + 105, CY + 30, 12, "#3f9a50", None)
    g.rr(CX + 20, CY - 78, CX + 165, CY - 52, 8, "#3f9a50")        # the barrel above the turret, pointing right
    g.rr(CX - 55, CY - 92, CX + 55, CY - 40, 24, "#6fd681")
    g.circ(CX, CY - 66, 12, "#3f9a50", OL, 4)
    g.circ(CX + 190, CY - 65, 9, "#ffd23f")


def cycles(g):
    g.stroke([(CX - 140, CY + 70), (CX - 140, CY - 10), (CX - 20, CY - 10)], 20, "#19d3ff")
    g.stroke([(CX + 140, CY - 70), (CX + 140, CY + 20), (CX + 20, CY + 20)], 20, "#ff3fb4")
    g.circ(CX - 5, CY - 10, 22, "#e8fbff")
    g.circ(CX + 5, CY + 20, 22, "#ffe3f5")
    g.circ(CX - 5, CY - 10, 10, "#19d3ff", None)
    g.circ(CX + 5, CY + 20, 10, "#ff3fb4", None)


def sumo(g):
    g.ell(CX, CY + 40, 150, 62, "#e6c58b", OL)
    g.ell(CX, CY + 40, 118, 46, None, "#b8935a", 5)
    for x, c, d in ((CX - 62, "#ff6b5e", 1), (CX + 62, "#4aa8ff", -1)):
        g.ell(x, CY + 10, 52, 60, c)
        g.ell(x, CY - 62, 34, 32, "#ffd9b0")
        g.rr(x - 30 - 0, CY - 90, x + 30, CY - 78, 6, OL, None)           # the topknot band
        g.circ(x + d * 12, CY - 62, 5, OL, None)
        g.ell(x - d * 40, CY + 20, 20, 14, "#ffd9b0")
    g.star(CX, CY + 5, 24, 11, 8, "#ffd23f")


def quiz(g):
    g.rr(CX - 105, CY - 100, CX + 105, CY + 70, 40, "#ffd23f")
    g.poly([(CX - 40, CY + 60), (CX - 70, CY + 112), (CX + 10, CY + 66)], "#ffd23f")
    g.arc(CX, CY - 40, 36, 180, 400, 22, OL)
    g.line([(CX + 22, CY - 10), (CX, CY + 10), (CX, CY + 22)], 22, OL, False)
    g.circ(CX, CY + 44, 12, OL, None)


def balloons(g):
    for x, y, c, r in ((CX - 85, CY - 25, "#ff5d73", 55), (CX + 5, CY - 50, "#ffd23f", 62), (CX + 90, CY - 15, "#35c3ff", 52)):
        g.line([(x, y + r * 1.1), (x - 10, y + r * 1.8), (x + 8, y + r * 2.2)], 4, "#e6e9f5")
        g.poly([(x - 9, y + r * 1.12), (x + 9, y + r * 1.12), (x, y + r * .95)], c)
        g.ell(x, y, r * .85, r, c)
        g.ell(x - r * .3, y - r * .4, r * .16, r * .3, _lighter(c), None)
    g.star(CX + 135, CY - 105, 22, 9, 5, "#ffffff", ol=OL)


def slice_(g):
    def half(l, sh):
        l.circ(CX + sh, CY + sh, 92, "#ff9f2e")
        l.circ(CX + sh, CY + sh, 76, "#ffc04d", None)
        for i in range(8):
            a = math.radians(i * 45 + 22)
            l.line([(CX + sh + 10 * math.cos(a), CY + sh + 10 * math.sin(a)), (CX + sh + 70 * math.cos(a), CY + sh + 70 * math.sin(a))], 5, "#ff9f2e", False)
    g.circ(CX, CY, 96, "#ff8a1f")
    g.circ(CX, CY, 78, "#ffd27a", None)
    for i in range(8):
        a = math.radians(i * 45 + 22)
        g.line([(CX + 12 * math.cos(a), CY + 12 * math.sin(a)), (CX + 72 * math.cos(a), CY + 72 * math.sin(a))], 5, "#ff9f2e", False)
    g.circ(CX, CY, 10, "#fff2cf", None)
    g.line([(CX - 150, CY + 85), (CX + 150, CY - 95)], 12, "#ffffff")
    g.line([(CX - 120, CY + 100), (CX + 100, CY - 98)], 5, "#c9f1ff")
    for x, y in ((CX + 105, CY + 50), (CX - 110, CY - 60), (CX + 130, CY + 80)):
        g.circ(x, y, 8, "#ffc04d")


def moles(g):
    g.ell(CX, CY + 70, 130, 42, "#2a1b14", "#6b4a35", 6)
    g.rr(CX - 70, CY - 55, CX + 70, CY + 66, 60, "#9b6a45")
    g.ell(CX, CY + 70, 130, 42, None, "#6b4a35", 6)                  # the hole's rim, over the body's foot
    g.circ(CX - 30, CY - 15, 11, "#ffffff", None)
    g.circ(CX + 30, CY - 15, 11, "#ffffff", None)
    g.circ(CX - 28, CY - 13, 5, OL, None)
    g.circ(CX + 32, CY - 13, 5, OL, None)
    g.ell(CX, CY + 15, 24, 17, "#ff8aa0")
    g.line([(CX - 12, CY + 42), (CX + 12, CY + 42)], 5, OL)
    def mal(l):
        l.rr(CX + 100, CY - 115, CX + 112, CY + 5, 5, "#d9a066")
        l.rr(CX + 68, CY - 128, CX + 144, CY - 90, 12, "#ff5d73")
    g.rot(mal, -28, CX + 106, CY)


def tienlen(g):
    def a(l, x, y): spade(l, x, y, 22, "#20233a")
    def b(l, x, y): heart(l, x, y, 22, "#e63950")
    def c(l, x, y): heart(l, x, y, 22, "#e63950")
    card(g, CX - 70, CY + 5, 112, 158, 22, "#ffffff", a)
    card(g, CX, CY - 8, 112, 158, 0, "#ffffff", b)
    card(g, CX + 70, CY + 5, 112, 158, -22, "#ffffff", c)


def caro(g):
    g.rr(CX - 105, CY - 105, CX + 105, CY + 105, 22, "#f0cf8a")
    for i in range(1, 4):
        g.line([(CX - 105 + i * 52.5, CY - 100), (CX - 105 + i * 52.5, CY + 100)], 4, "#a8793a", False)
        g.line([(CX - 100, CY - 105 + i * 52.5), (CX + 100, CY - 105 + i * 52.5)], 4, "#a8793a", False)
    for k in range(4):
        x, y = CX - 79 + k * 52.5, CY - 79 + k * 52.5
        if k in (1, 3):
            g.circ(x, y, 18, "#ff4d5e")
        else:
            g.stroke([(x - 15, y - 15), (x + 15, y + 15)], 10, "#2f7bff")
            g.stroke([(x - 15, y + 15), (x + 15, y - 15)], 10, "#2f7bff")
    g.stroke([(CX - 79, CY + 26), (CX + 79 + 0, CY + 26)], 10, "#2f7bff")


def bombers(g):
    bomb(g, CX - 55, CY + 20, 64)
    g.circ(CX + 95, CY + 70, 11, "#ffd23f")
    g.star(CX + 105, CY - 15, 34, 15, 8, "#ff9f2e")
    g.star(CX + 105, CY - 15, 18, 8, 8, "#ffe27a", ol=None)


def colors(g):
    def oval(col):
        def f(l, x, y):
            l.ell(x, y, 26, 40, "#ffffff", None)
            l.ell(x, y, 15, 28, col, None)
        return f
    for dx, col, ang in ((-105, "#ff4d5e", 28), (-35, "#ffcf33", 9), (35, "#2fcf6a", -9), (105, "#3a8bff", -28)):
        card(g, CX + dx, CY + (10 if abs(dx) > 50 else -5), 100, 148, ang, col, oval(col))


def cangua(g):
    g.rr(CX - 110, CY - 110, CX + 110, CY + 110, 26, "#ffffff")
    for x0, y0, c in ((-110, -110, "#ff4d5e"), (10, -110, "#2fcf6a"), (-110, 10, "#3a8bff"), (10, 10, "#ffcf33")):
        g.rr(CX + x0 + 10, CY + y0 + 10, CX + x0 + 100, CY + y0 + 100, 14, c, None)
        g.circ(CX + x0 + 55, CY + y0 + 55, 24, "#ffffff", None)
        g.circ(CX + x0 + 55, CY + y0 + 55, 14, c, None)
    g.rr(CX - 110, CY - 110, CX + 110, CY + 110, 26, None)
    g.rot(lambda l: (l.rr(CX - 38, CY - 38, CX + 38, CY + 38, 14, "#ffffff"),
                     [l.circ(CX + dx, CY + dy, 7, OL, None) for dx, dy in ((-18, -18), (18, -18), (0, 0), (-18, 18), (18, 18))]), 12, CX, CY)


def typhu(g):
    g.poly([(CX - 130, CY - 5), (CX - 40, CY - 85), (CX + 50, CY - 5)], "#ff4d5e")
    g.rr(CX - 110, CY - 5, CX + 30, CY + 85, 6, "#fff1cf")
    g.rr(CX - 62, CY + 22, CX - 18, CY + 85, 5, "#8b5a2b")
    for i, y in enumerate((CY + 85, CY + 62, CY + 39)):
        g.ell(CX + 100, y, 52, 16, "#ffc928")
    g.ell(CX + 100, CY + 33, 52, 16, "#ffe27a")
    g.ell(CX + 100, CY + 33, 30, 8, None, "#e0a010", 3)


def bida(g):
    def cue(l):
        l.rr(CX - 160, CY - 9, CX + 40, CY + 9, 8, "#e8c07a")
        l.rr(CX - 160, CY - 9, CX - 110, CY + 9, 8, "#3b2a1c")
        l.rr(CX + 30, CY - 6, CX + 50, CY + 6, 3, "#5ec8ff")
    g.rot(cue, 24, CX, CY)
    g.circ(CX + 5, CY + 5, 78, "#20233a")
    g.circ(CX + 5, CY + 5, 36, "#ffffff", None)
    f = ImageFont.truetype(FONTS[-1], 52 * S)
    g.d.text(((CX + 5) * S, (CY + 5) * S), "8", font=f, fill=OL, anchor="mm")
    g.ell(CX - 25, CY - 35, 18, 10, "#5a5f86", None)
    g.circ(CX + 120, CY + 60, 28, "#ffcf33")
    g.circ(CX + 120, CY + 60, 12, "#ffffff", None)


def meono(g):
    g.poly([(CX - 100, CY - 30), (CX - 105, CY - 105), (CX - 40, CY - 62)], "#ffa43b")
    g.poly([(CX + 100, CY - 30), (CX + 105, CY - 105), (CX + 40, CY - 62)], "#ffa43b")
    g.poly([(CX - 88, CY - 45), (CX - 90, CY - 85), (CX - 55, CY - 62)], "#ff8aa0", None)
    g.poly([(CX + 88, CY - 45), (CX + 90, CY - 85), (CX + 55, CY - 62)], "#ff8aa0", None)
    g.ell(CX, CY + 5, 108, 90, "#ffa43b")
    for dx in (-38, 38):
        g.circ(CX + dx, CY - 10, 20, "#ffffff")
        g.circ(CX + dx + 3, CY - 8, 10, OL, None)
    g.poly([(CX - 12, CY + 18), (CX + 12, CY + 18), (CX, CY + 32)], "#ff6f8b")
    g.line([(CX - 30, CY + 48), (CX, CY + 38), (CX + 30, CY + 48)], 5, OL)
    for s in (-1, 1):
        g.line([(CX + s * 70, CY + 22), (CX + s * 128, CY + 12)], 4, OL)
        g.line([(CX + s * 70, CY + 38), (CX + s * 128, CY + 46)], 4, OL)
    bomb(g, CX + 120, CY + 70, 36)


def croom(g):
    xs = (CX - 105, CX, CX + 105)
    for r, y in enumerate((CY - 55, CY + 62)):
        for c, x in enumerate(xs):
            flip = (r, c) in ((0, 0), (1, 2))
            g.rr(x - 44, y - 50, x + 44, y + 50, 14, "#ffffff" if flip else "#7a5cff")
            if flip:
                g.star(x, y, 30, 14, 5, "#ffcf33")
            else:
                g.circ(x, y, 18, None, "#b9a8ff", 5)


def rhde(g):
    g.star(CX + 40, CY - 5, 120, 70, 11, "#ffd23f")
    g.star(CX + 40, CY - 5, 85, 48, 11, "#ff8a3d", ol=None)
    g.rr(CX - 70, CY - 100, CX - 40, CY + 60, 6, "#8b5a2b")            # chair back
    g.rr(CX - 70, CY - 100, CX + 20, CY - 70, 8, "#a8713b")
    g.rr(CX - 70, CY + 10, CX + 70, CY + 40, 8, "#c98a4b")
    for x in (CX - 62, CX + 52):
        g.rr(x, CY + 36, x + 14, CY + 105, 5, "#8b5a2b")
    g.circ(CX + 95, CY - 40, 36, "#ffdcb4")                              # a fist
    g.rr(CX + 80, CY - 28, CX + 130, CY + 8, 12, "#ffdcb4")


def coco(g):
    g.circ(CX, CY + 20, 88, "#7a4a2a")
    g.circ(CX, CY + 20, 68, "#a8713b", None)
    for dx, dy in ((-24, 5), (24, 5), (0, 38)):
        g.circ(CX + dx, CY + dy, 11, "#2a1b14", None)
    g.poly([(CX - 62, CY - 66), (CX - 62, CY - 112), (CX - 30, CY - 84), (CX, CY - 124), (CX + 30, CY - 84), (CX + 62, CY - 112), (CX + 62, CY - 66)], "#ffd23f")


def thwaite(g):
    for x, h, c in ((CX - 120, 70, "#6a7bb4"), (CX - 55, 105, "#8a9be0"), (CX + 15, 60, "#6a7bb4"), (CX + 80, 90, "#8a9be0")):
        g.rr(x, CY + 100 - h, x + 50, CY + 100, 5, c)
        for k in range(3):
            g.rr(x + 10, CY + 100 - h + 12 + k * 20, x + 40, CY + 100 - h + 22 + k * 20, 2, "#ffe27a", None)
    for (x0, y0, x1, y1) in ((CX - 90, CY - 110, CX - 30, CY - 15), (CX + 30, CY - 115, CX + 5, CY - 30), (CX + 130, CY - 100, CX + 100, CY - 10)):
        g.line([(x0, y0), (x1, y1)], 6, "#ff6b5e")
        g.circ(x1, y1, 10, "#ffe27a")
    g.star(CX + 5, CY - 35, 40, 20, 10, "#ffd23f")


def runner(g):
    def lim(a, b, c): g.stroke([a, b, c], 24, "#ff8a3d")
    g.line([(CX - 140, CY + 100), (CX + 150, CY + 100)], 8, "#6c78a8")
    for y in (CY - 50, CY - 5, CY + 40):
        g.line([(CX - 150, y), (CX - 85 - (y % 3) * 12, y)], 7, "#ffffff80" if False else "#9fb0e8")
    lim((CX - 10, CY - 10), (CX + 30, CY + 35), (CX + 5, CY + 90))
    lim((CX - 10, CY - 10), (CX - 50, CY + 30), (CX - 95, CY + 55))
    lim((CX + 5, CY - 50), (CX + 60, CY - 20), (CX + 105, CY - 55))
    lim((CX - 5, CY - 50), (CX - 45, CY - 15), (CX - 85, CY - 45))
    g.stroke([(CX - 5, CY - 62), (CX - 5, CY + 5)], 40, "#ff8a3d")
    g.circ(CX + 5, CY - 95, 28, "#ffd9b0")


def ghostsgraves(g):
    g.rr(CX - 140, CY - 40, CX - 20, CY + 100, 60, "#8b93b8")
    g.rr(CX - 140, CY + 20, CX - 20, CY + 100, 6, "#8b93b8")
    g.rr(CX - 100, CY - 5, CX - 60, CY + 5, 3, "#c9d0f0", None)
    g.rr(CX - 85, CY - 22, CX - 75, CY + 40, 3, "#c9d0f0", None)
    pts = [(CX + 20, CY + 90), (CX + 20, CY - 20)]
    g.poly([(CX + 15, CY + 95), (CX + 15, CY - 20), (CX + 40, CY - 90), (CX + 110, CY - 90), (CX + 135, CY - 20), (CX + 135, CY + 95),
            (CX + 115, CY + 70), (CX + 95, CY + 95), (CX + 75, CY + 70), (CX + 55, CY + 95), (CX + 35, CY + 70)], "#ffffff")
    g.ell(CX + 60, CY - 30, 22, 28, "#ffffff", None)
    g.ell(CX + 75, CY - 30, 20, 28, "#ffffff", None)
    for x in (CX + 55, CX + 95):
        g.ell(x, CY - 35, 8, 13, OL, None)
    g.ell(CX + 75, CY - 3, 10, 12, OL, None)


def roborun(g):
    g.line([(CX, CY - 85), (CX, CY - 115)], 7, "#ffffff")
    g.circ(CX, CY - 120, 13, "#ff4d5e")
    g.rr(CX - 105, CY - 85, CX + 105, CY + 75, 30, "#4da3ff")
    g.rr(CX - 125, CY - 25, CX - 105, CY + 25, 6, "#ffcf33")
    g.rr(CX + 105, CY - 25, CX + 125, CY + 25, 6, "#ffcf33")
    for dx in (-48, 48):
        g.circ(CX + dx, CY - 28, 30, "#ffffff")
        g.circ(CX + dx + 6, CY - 24, 14, OL, None)
    g.rr(CX - 55, CY + 22, CX + 55, CY + 58, 10, OL, None)
    for i in range(5):
        g.rr(CX - 46 + i * 20, CY + 28, CX - 36 + i * 20, CY + 52, 2, "#ffffff", None)


def nova(g):
    g.ell(CX + 90, CY - 20, 55, 105, "#2fcf6a")
    g.ell(CX + 85, CY - 10, 28, 70, "#8ff0a8", None)
    g.circ(CX - 15, CY + 25, 85, "#2fcf6a")
    g.ell(CX - 15, CY + 55, 50, 45, "#c9ffd6", None)
    g.circ(CX - 20, CY - 55, 52, "#2fcf6a")
    g.poly([(CX - 62, CY - 90), (CX - 70, CY - 128), (CX - 28, CY - 100)], "#2fcf6a")
    g.poly([(CX + 10, CY - 98), (CX + 25, CY - 130), (CX + 32, CY - 80)], "#2fcf6a")
    g.circ(CX - 5, CY - 60, 10, OL, None)
    g.circ(CX - 62, CY - 50, 8, OL, None)
    g.circ(CX - 60, CY + 85, 30, "#c98a4b")
    g.poly([(CX - 80, CY + 72), (CX - 40, CY + 72), (CX - 60, CY + 62)], "#8b5a2b")


def witchnwiz(g):
    g.poly([(CX - 120, CY + 60), (CX + 120, CY + 60), (CX + 60, CY + 30), (CX + 55, CY - 40), (CX + 30, CY - 120), (CX - 30, CY - 60),
            (CX - 50, CY + 30)], "#7a4dff")
    g.ell(CX, CY + 62, 140, 26, "#5a35cf")
    g.poly([(CX - 52, CY + 20), (CX + 56, CY + 20), (CX + 58, CY + 45), (CX - 56, CY + 45)], "#ffcf33")
    g.rr(CX - 14, CY + 16, CX + 14, CY + 50, 4, "#ffe27a")
    g.rr(CX - 6, CY + 24, CX + 6, CY + 42, 2, "#7a4dff", None)
    g.star(CX + 85, CY - 55, 38, 16, 5, "#ffe27a")
    g.star(CX - 100, CY - 30, 20, 9, 5, "#ffe27a")


def stallar(g):
    g.circ(CX, CY, 78, "#ff8a3d")
    g.circ(CX - 20, CY - 20, 40, "#ffb36b", None)
    g.ell(CX, CY, 160, 36, None, OL, 22)
    g.ell(CX, CY, 160, 36, None, "#9fe3ff", 12)
    g.star(CX + 135, CY - 90, 26, 11, 5, "#ffe27a")
    g.star(CX - 130, CY + 80, 18, 8, 5, "#ffffff")


def filefixers(g):
    g.rr(CX - 120, CY - 70, CX - 20, CY - 35, 12, "#ffcf33")
    g.rr(CX - 125, CY - 55, CX + 125, CY + 90, 16, "#ffe27a")
    g.rr(CX - 125, CY - 30, CX + 125, CY + 90, 16, "#ffcf33")
    def wr(l):
        l.rr(CX - 14, CY - 20, CX + 14, CY + 105, 10, "#e8f1ff")
        l.circ(CX, CY - 28, 36, "#e8f1ff")
    g.rot(wr, -35, CX, CY + 20)


def tabletennis(g):
    paddle(g, CX - 20, CY - 20, 85, 28, "#ff4d5e")
    g.circ(CX + 105, CY + 55, 26, "#ffffff")
    g.line([(CX + 40, CY + 95), (CX + 80, CY + 80)], 6, "#c9d0f0")
    g.line([(CX + 30, CY + 70), (CX + 70, CY + 55)], 6, "#c9d0f0")


def snake(g):
    pts = [(CX - 130, CY + 70), (CX - 40, CY + 70), (CX - 40, CY), (CX + 50, CY), (CX + 50, CY - 70), (CX + 110, CY - 70)]
    g.stroke(pts, 40, "#58d36a")
    g.stroke([pts[-2], pts[-1]], 40, "#58d36a")
    g.circ(CX + 112, CY - 70, 36, "#7fe48d")
    g.circ(CX + 120, CY - 80, 9, "#ffffff", None)
    g.circ(CX + 122, CY - 80, 4, OL, None)
    g.line([(CX + 145, CY - 62), (CX + 175, CY - 62)], 5, "#ff4d5e")
    g.circ(CX - 125, CY - 55, 28, "#ff4d5e")
    g.line([(CX - 125, CY - 83), (CX - 118, CY - 100)], 5, "#8b5a2b")


def breakout(g):
    cols = ["#ff4d5e", "#ff9f2e", "#ffd23f", "#4ddc7a"]
    for r, c in enumerate(cols):
        for i in range(6):
            g.rr(CX - 156 + i * 52, CY - 105 + r * 30, CX - 112 + i * 52, CY - 81 + r * 30, 5, c)
    g.circ(CX + 20, CY + 25, 16, "#ffffff")
    g.rr(CX - 60, CY + 78, CX + 100, CY + 102, 12, "#4da3ff")


def pong(g):
    g.rr(CX - 150, CY - 60, CX - 126, CY + 70, 10, "#ffffff")
    g.rr(CX + 126, CY - 85, CX + 150, CY + 45, 10, "#ffffff")
    for i in range(6):
        g.rr(CX - 5, CY - 115 + i * 40, CX + 5, CY - 91 + i * 40, 3, "#ffffffaa" if False else "#9fb0e8", None)
    g.rr(CX + 25, CY - 12, CX + 63, CY + 26, 6, "#ffd23f")


def planes(g):
    g.arc(CX + 70, CY - 25, 82, 0, 360, 24, OL)                                  # the ring it flies through
    g.arc(CX + 70, CY - 25, 82, 0, 360, 13, "#ffd23f")
    n, ta, tc, tb, k = (CX + 150, CY - 95), (CX - 135, CY - 30), (CX - 70, CY + 40), (CX - 30, CY + 110), (CX - 100, CY + 95)
    g.poly([n, tc, k], "#7f9ad6")                                                # keel
    g.poly([n, tc, tb], "#c3d6ff")                                               # near wing
    g.poly([n, ta, tc], "#ffffff")                                               # far wing, lit


def fight(g):
    g.star(CX, CY - 10, 105, 55, 12, "#ffd23f")                                  # the impact between two fists
    g.star(CX, CY - 10, 52, 28, 12, "#ffffff", ol=None)
    for d, c in ((-1, "#ff5d4a"), (1, "#3b8bff")):
        x = CX + d * 70
        g.rr(x + d * 40 - 34, CY - 44, x + d * 40 + 34, CY + 24, 14, "#f8fafc")  # the wrist wrap
        g.rr(x - 50, CY - 62, x + 50, CY + 42, 34, c)                          # the glove
        g.ell(x - d * 6, CY - 34, 30, 12, _lighter(c), None)                   # its shine
        g.rr(x - 32, CY + 6, x + 32, CY + 34, 14, c)                           # the thumb across the front
        g.line([(x + d * 4, CY - 30), (x + d * 4, CY + 4)], 4, OL)             # a finger crease


# id: (name, bg top, bg bottom, emblem fn)
GAMES = {
    "hockey": ("Air Hockey", "#1e5fd8", "#0b2a6b", hockey),
    "tanks": ("Tank Arena", "#2e8b57", "#0f3d2b", tanks),
    "cycles": ("Light Cycles", "#4a1fa8", "#12083a", cycles),
    "sumo": ("Sumo Bump", "#d8433a", "#6e1620", sumo),
    "quiz": ("Quiz", "#7a3df0", "#2c1272", quiz),
    "planes": ("Paper Planes", "#2f8ae0", "#103a7a", planes),
    "fight": ("Dojo Duel", "#c2410c", "#4a1406", fight),
    "balloons": ("Balloon Pop", "#1fa6c9", "#0c4a72", balloons),
    "slice": ("Fruit Slice", "#e8782a", "#7a2a14", slice_),
    "moles": ("Whack-a-Mole", "#4aa845", "#1b5a2a", moles),
    "tienlen": ("Tiến lên", "#1f8a5a", "#0b3d2e", tienlen),
    "caro": ("Cờ caro", "#2f7bd8", "#14306e", caro),
    "bombers": ("Bombers", "#e0502f", "#5a1a1a", bombers),
    "colors": ("Color Cards", "#c92f8a", "#4a124a", colors),
    "cangua": ("Cờ cá ngựa", "#e8a21f", "#8a3a10", cangua),
    "typhu": ("Cờ tỷ phú", "#1f9a8a", "#0b3d4a", typhu),
    "bida": ("Pool", "#1f8f4f", "#0a3a28", bida),
    "meono": ("Boom Cats", "#e6502f", "#6a1a3a", meono),
    "croom": ("Concentration", "#6a4ae0", "#271460", croom),
    "rhde": ("RHDE", "#e8782a", "#7a2a14", rhde),
    "coco": ("Coco Battle", "#2e9a62", "#0e4030", coco),
    "thwaite": ("Thwaite", "#2a3f9a", "#0f1a4a", thwaite),
    "runner": ("Runner", "#d8432f", "#5a1a24", runner),
    "ghostsgraves": ("Ghosts & Graves", "#4a3f9a", "#171240", ghostsgraves),
    "roborun": ("Robo Run", "#1f8ad8", "#0c2f6e", roborun),
    "nova": ("Nova the Squirrel", "#2f9a5a", "#0f4a3a", nova),
    "witchnwiz": ("Witch n' Wiz", "#6a3ac9", "#2a1160", witchnwiz),
    "stallar": ("Stallar", "#2a2f8a", "#0b0f3a", stallar),
    "filefixers": ("File Fixers", "#1f7ad8", "#12306e", filefixers),
    "tabletennis": ("Table Tennis", "#1f8a8a", "#0b3d4a", tabletennis),
    "opennes-snake": ("Snake", "#2a8f3a", "#0f3d22", snake),
    "opennes-breakout": ("Breakout", "#8a2fc9", "#2a1060", breakout),
    "opennes-pong": ("Pong", "#2f3f9a", "#0b123a", pong),
    "stb": ("Super Tilt Bro", "#1f7ad8", "#0c2f6e", None),
    "tobudx": ("Tobu Tobu Girl", "#2a8f6a", "#0c3a3a", None),
}


def background(top, bot):
    top, bot = hx(top), hx(bot)
    img = Image.new("RGBA", (W * S, H * S))
    d = ImageDraw.Draw(img)
    for y in range(H * S):
        t = y / (H * S - 1)
        d.line([(0, y), (W * S, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)) + (255,))
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for k in range(12):                                          # a soft disc behind the emblem
        r = (175 - k * 9) * S
        gd.ellipse([(CX - 0) * S - r, (CY - 5) * S - r, CX * S + r, (CY - 5) * S + r], fill=(255, 255, 255, 7))
    img.alpha_composite(glow)
    return img


def fit_font(name, maxw=540, maxs=60):
    for size in range(maxs, 20, -2):
        f = ImageFont.truetype(FONTS[0], size * S)
        if f.getlength(name) <= maxw * S:
            return f
    return f


def draw(gid):
    name, top, bot, fn = GAMES[gid]
    img = background(top, bot)
    g = G()
    fn(g)
    big = g.img.resize((int(W * S * K), int(H * S * K)), Image.LANCZOS)
    lay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    lay.paste(big, (int(CX * S * (1 - K)), int((CYT - CY * K) * S)))
    img.alpha_composite(lay)
    d = ImageDraw.Draw(img)
    f = fit_font(name)
    d.text((W / 2 * S, 46 * S + 4 * S), name, font=f, fill=(0, 0, 0, 110), anchor="mm")
    d.text((W / 2 * S, 46 * S), name, font=f, fill=(255, 255, 255), anchor="mm")
    return img.convert("RGB").resize((W, H), Image.LANCZOS)


# ---- the retro look (NES / homebrew games) -----------------------------------------------------------------------------
# These games are 8-bit in play, so their card is too: a 160x90 canvas shown at x4 with nearest-neighbour (no antialias),
# a 5x7 pixel font, a 16x16 sprite from the game's theme in a NES-like palette (outlined automatically), a dark
# background with a few stars, scanlines over the result and a small NES badge bottom-right (bottom-left stays empty).
# Sprites suggest what the game is about; they copy no game's art.
RSUFFIX = ".v3"                                    # the retro cards' version (the web cards keep SUFFIX)
LW, LH, LS = 160, 90, 4

PAL = {"K": "#101018", "W": "#fcfcfc", "E": "#bcbcbc", "D": "#6c6c7c", "R": "#d82800", "O": "#fc7424", "Y": "#fcc000",
       "G": "#009800", "L": "#58d854", "B": "#0058f8", "C": "#3cbcfc", "P": "#6844fc", "M": "#d800cc", "N": "#8c4a14",
       "S": "#fcc0a0", "T": "#00a090", "A": "#a4e4fc"}

FONT = {  # 5x7 pixel font, rows joined by "/"
    "A": "01110/10001/10001/11111/10001/10001/10001", "B": "11110/10001/10001/11110/10001/10001/11110",
    "C": "01110/10001/10000/10000/10000/10001/01110", "D": "11110/10001/10001/10001/10001/10001/11110",
    "E": "11111/10000/10000/11110/10000/10000/11111", "F": "11111/10000/10000/11110/10000/10000/10000",
    "G": "01110/10001/10000/10111/10001/10001/01111", "H": "10001/10001/10001/11111/10001/10001/10001",
    "I": "01110/00100/00100/00100/00100/00100/01110", "J": "00111/00010/00010/00010/00010/10010/01100",
    "K": "10001/10010/10100/11000/10100/10010/10001", "L": "10000/10000/10000/10000/10000/10000/11111",
    "M": "10001/11011/10101/10101/10001/10001/10001", "N": "10001/11001/10101/10011/10001/10001/10001",
    "O": "01110/10001/10001/10001/10001/10001/01110", "P": "11110/10001/10001/11110/10000/10000/10000",
    "Q": "01110/10001/10001/10001/10101/10010/01101", "R": "11110/10001/10001/11110/10100/10010/10001",
    "S": "01111/10000/10000/01110/00001/00001/11110", "T": "11111/00100/00100/00100/00100/00100/00100",
    "U": "10001/10001/10001/10001/10001/10001/01110", "V": "10001/10001/10001/10001/10001/01010/00100",
    "W": "10001/10001/10001/10101/10101/11011/10001", "X": "10001/10001/01010/00100/01010/10001/10001",
    "Y": "10001/10001/01010/00100/00100/00100/00100", "Z": "11111/00001/00010/00100/01000/10000/11111",
    "&": "01100/10010/10100/01000/10101/10010/01101", "'": "00100/00100/01000/00000/00000/00000/00000",
    "-": "00000/00000/00000/11111/00000/00000/00000", " ": "00000/00000/00000/00000/00000/00000/00000",
}

SPRITES = {
    "croom": ["", ".PPPPPPP.WWWWWWW", ".PMPMPMP.WWWRWWW", ".PPMMMPP.WWRRRWW", ".PMPMPMP.WWWRWWW", ".PPPPPPP.WWWWWWW", "",
              ".WWWWWWW.PPPPPPP", ".WYWWWYW.PMPMPMP", ".WWYYYWW.PPMMMPP", ".WYWWWYW.PMPMPMP", ".WWWWWWW.PPPPPPP"],
    "rhde": ["..NN", "..NN", "..NN", "..NN", "..NN", "..NNNNNNNNNNN", "..NRRRRRRRRRN", "..NRRRRRRRRRN", "..NNNNNNNNNNN", "..NN.......NN",
             "..NN.......NN", "..NN.......NN", "..NN.......NN"],
    "coco": [".....RR", "....RRRR", "....WWWW", "...WWKWWOO", "...WWWWWOO", "....WWWW", "....WWWWR", "...WWWWWW", "..WWWWWWWW",
             ".WWWWWWWWWW", ".WWWWWWWWWWW", "WWWWWWWWWWWW", ".WWWWWWWWWW", "..EWWWWWWW", "....YY..YY", "...YYY.YYY"],
    "thwaite": [".......RR", "......RRRR", ".....RRRRRR", ".....EEEEEE", ".....EECCEE", ".....EECCEE", ".....EEEEEE", "....REEEEEER",
                "...RR.EEEE.RR", "..RRR.EEEE.RRR", "......OYYO", ".......YY", "", "", ".DD..DD....DD.DD", "DDDDDDDD..DDDDDD"],
    "runner": ["......SSSS", ".....SSSSSS", ".....SSSKSS", ".....SSSSSS", "......SSSS", "....RRRRRRR", "...RRRRRRRRR", "..SRRRRRRRR",
               ".SS.RRRRRRR", "....BBBBBBB", "...BBB...BBB", "..BBB.....BBB", ".NNN.......NN"],
    "ghostsgraves": [".....WWWWWW", "...WWWWWWWWWW", "..WWWWWWWWWWWW", ".WWWWWWWWWWWWWE", ".WWKKWWWWWKKWWE", ".WWKKWWWWWKKWWE",
                     ".WWWWWWWWWWWWWE", ".WWWWWWKKWWWWWE", ".WWWWWWKKWWWWWE", ".WWWWWWWWWWWWWE", ".WWWWWWWWWWWWWE", ".WWWWWWWWWWWWWE",
                     ".WWW.WWWW.WWWWE", ".WW...WW...WWE"],
    "roborun": [".......R", ".......D", "....EEEEEEEE", "...EEEEEEEEEE", "...ECCEEEECCE", "...ECCEEEECCE", "...EEEEEEEEEE", "...EEKKKKKKEE",
                "....EEEEEEEE", "..BBBBBBBBBBBB", ".BBBBBYBBBBBBBB", ".BBBBBBBBBBBBBB", "EE.BBBBBBBBBB.EE", "EE.BBBBBBBBBB.EE", "...DD......DD",
                "..DDD......DDD"],
    "nova": ["...OOO", "..OOOOO.....N.N", ".OOOOOOO....NNN", ".OOOOOOO...NNNN", ".OOOOOOOO.NNKNN", ".OOOOOOOOONNNNNNK", "..OOOOOONNNNNNWN",
             "...OOOONNNNNNNN", "....OONNNNNNNN", "....NNNNWWWNN", "....NNNNWWWNN", "....NNNNNNNNN", ".....NN..NN", ".....NN..NN"],
    "witchnwiz": [".......PP", "......PPP", "......PPP", ".....PPPPP", ".....PPPPP", "....PPPPPPP", "....PYYYYYP", "...PPYYYYYPP", "..PPPPPPPPPPP",
                  ".PPPPPPPPPPPPP", "PPPPPPPPPPPPPPP"],
    "stallar": [".......WW", "......WWWW", "......WCCW", ".....WWCCWW", ".....WWWWWW", "....EWWWWWWE", "....EWWWWWWE", "...REWWWWWWER",
                "..RRE.WWWW.ERR", ".RRR..EEEE..RRR", ".RR...OYYO...RR", "......OYYO", ".......YY"],
    "filefixers": ["..EEEEEEEEE", "..EWWWWWWWEE", "..EWWWWWWWEWE", "..EWBBBBWWEEEE", "..EWWWWWWWWWWE", "..EWBBBBBBBWWE", "..EWWWWWWWWWWE",
                   "..EWBBBBBWWWWE", "..EWWWWWWWWWWE", "..EWBBBBBBBWWE", "..EWWWWWWWWWWE", "..EEEEEEEEEEEE"],
    "tabletennis": ["....RRRRRR", "...RRRRRRRR", "..RRRRRRRRRR", "..RRRRRRRRRR", "..RRRRRRRRRR", "..RRRRRRRRRR", "...RRRRRRRR", "....RRRRRR",
                    ".....NN", ".....NN.....WW", ".....NN....WWWW", ".....NN....WWWW", ".....NN.....WW"],
    "opennes-snake": [".LLLLLLLLLLL", ".LGGGGGGGGGGL", ".LG", ".LG......RR", ".LGGGGGGGRRRR", ".LLLLLLLGRRRR", "........LG.RR", "...WK...LG",
                      "..LGGGGGGG", "..LLLLLLLL"],
    "opennes-breakout": ["RRR.RRR.RRR.RRR", "RRR.RRR.RRR.RRR", "OOO.OOO.OOO.OOO", "OOO.OOO.OOO.OOO", "YYY.YYY.YYY.YYY", "YYY.YYY.YYY.YYY",
                         "LLL.LLL.LLL.LLL", "LLL.LLL.LLL.LLL", "", "", ".......WW", ".......WW", "", "", "..CCCCCCCCCC"],
    "stb": ["....GGGGG", "...GGGGGGG", "...GGGWKGG", "...GGGGGGG", "....GGGGG", "...LLLLLLLLLSSS", "..LLLLLLLLLLSSSS", "..LLLLLLL...SSS",
            "..LLLLLLL", "...BBBBBB", "...BB..BB", "..BB....BB", ".BBB....BBB"],
    "tobudx": ["...YYYY", "..YYYYYY", "..YSSKSY", "..YSSSSY", "...SSSS", "..WWRRWW", ".EWWRRWWE", "EEWWRRWWEE", "..WWRRWW", "...SSSS", "...S..S", "..SS..SS"],
    "opennes-pong": ["", "", "WW", "WW", "WW", "WW............WW", "WW.....WW.....WW", "WW.....WW.....WW", "WW............WW", "WW............WW", "..............WW", "..............WW", "..............WW", "", "", ""],
}
# id: (background, title colour, frame colour)
RETRO = {
    "croom": ("#1a1040", "Y", "P"), "rhde": ("#401a0c", "O", "N"), "coco": ("#0c3020", "Y", "L"), "thwaite": ("#0c1440", "C", "B"),
    "runner": ("#401018", "Y", "R"), "ghostsgraves": ("#150f38", "W", "P"), "roborun": ("#0c1c48", "C", "B"),
    "nova": ("#0c3022", "Y", "L"), "witchnwiz": ("#1c0c40", "Y", "P"), "stallar": ("#080a30", "W", "C"),
    "filefixers": ("#0c2048", "A", "B"), "tabletennis": ("#0c3030", "W", "T"), "opennes-snake": ("#0c2c14", "L", "G"),
    "opennes-breakout": ("#240c48", "Y", "M"), "opennes-pong": ("#0c1034", "W", "B"),
    "stb": ("#0c2c48", "Y", "C"),
    "tobudx": ("#0c3028", "L", "T"),
}
BADGE = {"tobudx": ("GB", "G")}                                 # the corner badge of a card that is not NES: text, colour
RETRO_NAMES = {"croom": "CONCENTRATION", "ghostsgraves": "GHOSTS & GRAVES"}     # where the pixel title differs from the name


def _px(im, x, y, c):
    if 0 <= x < LW and 0 <= y < LH:
        im.putpixel((x, y), hx(PAL[c]) if isinstance(c, str) and c in PAL else hx(c))


def _text(im, s, cx, y, sc, c, shadow="K"):
    w = len(s) * 6 * sc - sc
    x0 = cx - w // 2
    for ox, oy, col in ((sc, sc, shadow), (0, 0, c)):
        for i, ch in enumerate(s):
            for r, row in enumerate(FONT[ch].split("/")):
                for q, v in enumerate(row):
                    if v == "1":
                        for a in range(sc):
                            for b in range(sc):
                                _px(im, x0 + i * 6 * sc + q * sc + a + ox, y + r * sc + b + oy, col)


def _sprite(im, rows, cx, cy, sc):
    h = [(r.ljust(16, "."))[:16] for r in rows]
    h += ["." * 16] * (16 - len(h))
    cells = {(x, y): ch for y, r in enumerate(h) for x, ch in enumerate(r) if ch != "."}
    xs, ys = [x for x, _ in cells], [y for _, y in cells]
    x0 = cx - (min(xs) + max(xs) + 1) * sc // 2                # centre what is drawn, not the 16x16 box
    y0 = cy - (min(ys) + max(ys) + 1) * sc // 2
    for (x, y) in cells:                                       # the dark outline: every empty cell touching a filled one
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in cells:
                for a in range(sc):
                    for b in range(sc):
                        _px(im, x0 + (x + dx) * sc + a, y0 + (y + dy) * sc + b, "K")
    for (x, y), ch in cells.items():
        for a in range(sc):
            for b in range(sc):
                _px(im, x0 + x * sc + a, y0 + y * sc + b, ch)


def draw_retro(gid):
    bg, tc, fc = RETRO[gid]
    name = RETRO_NAMES.get(gid, GAMES[gid][0].upper())
    im = Image.new("RGB", (LW, LH), hx(bg))
    d = ImageDraw.Draw(im)
    for y in range(LH):                                        # a two-step dither toward the bottom: darker, CRT-like
        for x in range((y % 2), LW, 2):
            if y > LH * 0.55:
                im.putpixel((x, y), tuple(int(v * .82) for v in hx(bg)))
    seed = sum(map(ord, gid))
    for i in range(26):                                        # stars, same every run
        x, y = (seed * 31 + i * 53) % LW, 24 + (seed * 17 + i * 37) % 52
        if not (x < 36 and y > 68):
            _px(im, x, y, "E" if i % 3 else "W")
    d.rectangle([1, 1, LW - 2, LH - 2], outline=hx(PAL[fc]))   # frame, 1 pixel
    sc = 2 if len(name) * 6 * 2 - 2 <= 140 else 1
    _text(im, name, LW // 2, 7 if sc == 2 else 9, sc, tc)
    ty = 7 + 7 * sc + sc + 3
    d.rectangle([20, ty, LW - 21, ty], fill=hx(PAL[fc]))       # a rule under the name
    _sprite(im, SPRITES[gid], LW // 2, 56, 3)
    bt, bc = BADGE.get(gid, ("NES", "R"))
    d.rectangle([128, 76, 155, 85], fill=hx(PAL[bc]), outline=hx(PAL["K"]))      # the console badge
    _text(im, bt, 142, 78, 1, "W", "K")
    big = im.resize((W, H), Image.NEAREST)
    px = big.load()
    for y in range(3, H, LS):                                  # scanlines: the last row of every pixel row, 22% darker
        for x in range(W):
            r, g, b = px[x, y]
            px[x, y] = (int(r * .78), int(g * .78), int(b * .78))
    return big


def main():
    a = sys.argv[1:]
    sheet = None
    if "--sheet" in a:
        i = a.index("--sheet")
        sheet = a[i + 1]
        del a[i:i + 2]
    ids = a or list(GAMES)
    tiles = []
    for gid in ids:
        if gid in RETRO:
            im = draw_retro(gid)
            im.save(os.path.join(OUT, gid + RSUFFIX + ".jpg"), quality=92, subsampling=0, optimize=True)
        else:
            im = draw(gid)
            im.save(os.path.join(OUT, gid + SUFFIX + ".jpg"), quality=88, optimize=True)
        tiles.append(im)
    if sheet:
        cols = 4
        rows = (len(tiles) + cols - 1) // cols
        sh = Image.new("RGB", (cols * 480, rows * 270), (20, 20, 20))
        for i, im in enumerate(tiles):
            sh.paste(im.resize((480, 270), Image.LANCZOS), ((i % cols) * 480, (i // cols) * 270))
        sh.save(sheet)


if __name__ == "__main__":
    main()
