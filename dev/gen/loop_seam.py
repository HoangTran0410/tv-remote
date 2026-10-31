#!/usr/bin/env python3
"""dev/gen/loop_seam.py clip.mp4...: does a video loop by itself? (a video background's "fade", dev/gen/wallpapers.py LOOPS)

Prints how far the last frame is from the first, in multiples of the usual step between two frames (64x36 grey, ffmpeg).
Up to ~2x the jump at the turn is not seen: the clip loops. More: the turn jumps, the list sets "fade" for it. "best end" is the
frame (past the middle) closest to the first: a clip that loops when cut there. Plain Python, no numpy.
"""
import subprocess, sys, statistics

W, H = 64, 36


def frames(f):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", f, "-vf", f"scale={W}:{H},format=gray", "-f", "rawvideo", "-"], capture_output=True).stdout
    n = W * H
    return [raw[i:i + n] for i in range(0, len(raw), n)]


def d(x, y):
    return sum(abs(p - q) for p, q in zip(x, y)) / len(x)


for f in sys.argv[1:]:
    a = frames(f)
    step = max(statistics.median(d(a[i], a[i + 1]) for i in range(len(a) - 1)), .01)
    seam = d(a[0], a[-1])
    best = min(range(len(a) // 2, len(a) - 1), key=lambda i: d(a[0], a[i]))
    print(f"{f:20s} frames {len(a):4d} step {step:5.2f} seam {seam:6.2f} x{seam / step:5.1f}  best end {best} x{d(a[0], a[best]) / step:5.1f}")
