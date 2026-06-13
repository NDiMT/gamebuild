#!/usr/bin/env python3
"""Generates the PULSE launcher icons (pure stdlib, no Pillow needed)."""
import math
import os
import struct
import sys
import zlib


def write_png(path, w, h, pixels):
    raw = b"".join(
        b"\x00" + bytes(pixels[y * w * 4:(y + 1) * w * 4]) for y in range(h)
    )

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        return out + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def sample(u, v):
    """Scene in [-1, 1] coords. Returns (r, g, b, a) floats 0..1."""
    d = math.hypot(u, v)

    # rounded-square plate
    k = 0.86
    qx, qy = max(abs(u) - k + 0.30, 0.0), max(abs(v) - k + 0.30, 0.0)
    plate = math.sqrt(qx * qx + qy * qy) - 0.30
    if plate > 0:
        return (0, 0, 0, 0)

    # SAVE THE DOGE: the smug doge face on a sky, a bee buzzing in,
    # a black ink shield drawn over its head
    cr, cg, cb = 0.55, 0.80, 0.94  # sky

    # ground at the bottom
    if v > 0.60:
        cr, cg, cb = 0.54, 0.35, 0.20
        if v < 0.66:
            cr, cg, cb = 0.49, 0.65, 0.26  # grass strip

    def tri(px, py, x1, y1, x2, y2, x3, y3):
        b1 = (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2) < 0
        b2 = (px - x3) * (y2 - y3) - (x2 - x3) * (py - y3) < 0
        b3 = (px - x1) * (y3 - y1) - (x3 - x1) * (py - y1) < 0
        return b1 == b2 and b2 == b3

    face = (0.97, 0.80, 0.28)
    cx0, cy0, R = 0.0, 0.10, 0.52

    # ears (behind head)
    if tri(u, v, -0.42, -0.05, -0.30, -0.72, 0.00, -0.28) \
            or tri(u, v, 0.42, -0.05, 0.30, -0.72, 0.00, -0.28):
        cr, cg, cb = 0.90, 0.70, 0.20

    hd = math.hypot(u - cx0, v - cy0)
    if hd < R:
        cr, cg, cb = face
        if v - cy0 > 0.05:               # slightly darker lower muzzle
            cr, cg, cb = 0.95, 0.74, 0.22
        # pink cheeks
        if math.hypot(u + 0.28, v - 0.26) < 0.13 \
                or math.hypot(u - 0.28, v - 0.26) < 0.13:
            cr, cg, cb = 0.98, 0.60, 0.66
        # eyes (smug, half-lidded)
        for ex in (-0.21, 0.21):
            if math.hypot(u - ex, v - 0.02) < 0.135:
                if v < -0.02:            # upper lid = face color
                    cr, cg, cb = face
                else:
                    cr, cg, cb = 1.0, 1.0, 1.0
                    if math.hypot(u - ex, v - 0.05) < 0.075:
                        cr, cg, cb = 0.12, 0.09, 0.05
        # nose
        if math.hypot(u, v - 0.20) < 0.06:
            cr, cg, cb = 0.12, 0.09, 0.05

    # a bee top-right, with translucent wings
    bd = math.hypot(u - 0.58, v + 0.55)
    if bd < 0.20 and bd >= 0.12:
        cr, cg, cb = 0.92, 0.96, 1.0     # wings
    if bd < 0.12:
        if int(((u - 0.58) + 0.12) / 0.06) % 2 == 0:
            cr, cg, cb = 0.97, 0.76, 0.0
        else:
            cr, cg, cb = 0.13, 0.13, 0.13

    # a thick black ink shield arc drawn over the doge
    ad = math.hypot(u - cx0, v - cy0)
    ang = math.atan2(v - cy0, u - cx0)
    if abs(ad - R * 1.5) < 0.045 and ang < 0.15:
        cr, cg, cb = 0.10, 0.10, 0.10

    aa = min(1.0, -plate / 0.02)  # soft edge on the plate
    return (min(cr, 1.0), min(cg, 1.0), min(cb, 1.0), aa)


def render(size):
    ss = 3  # supersampling
    px = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    u = ((x + (sx + 0.5) / ss) / size) * 2 - 1
                    v = ((y + (sy + 0.5) / ss) / size) * 2 - 1
                    pr, pg, pb, pa = sample(u, v)
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
            n = ss * ss
            i = (y * size + x) * 4
            if a > 0:
                px[i] = int(r / a * 255)
                px[i + 1] = int(g / a * 255)
                px[i + 2] = int(b / a * 255)
            px[i + 3] = int(a / n * 255)
    return px


def main():
    res_dir = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res"
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, size in sizes.items():
        out_dir = os.path.join(res_dir, "mipmap-" + dpi)
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, "ic_launcher.png")
        write_png(out, size, size, render(size))
        print("wrote", out)


if __name__ == "__main__":
    main()
