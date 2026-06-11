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

    # dark navy base, slightly lighter in the middle
    base = 0.10 + 0.07 * max(0.0, 1.0 - d * 1.2)
    cr, cg, cb = base * 0.55, base * 0.75, base * 1.6

    # ORBIT: a planet bending a dotted comet trajectory into a goal ring
    # planet
    pcx, pcy, pr = -0.05, 0.05, 0.30
    pd = math.hypot(u - pcx, v - pcy)
    if pd < pr:
        cr, cg, cb = 0.23, 0.29, 0.48
        if math.hypot(u - pcx - pr * 0.25, v - pcy - pr * 0.25) < pr * 0.8:
            cr, cg, cb = 0.17, 0.22, 0.38
    elif pd < pr * 1.12:
        cr, cg, cb = 0.35, 0.55, 0.95

    # gravity rings
    for gk in (1.7, 2.3):
        if abs(pd - pr * gk) < 0.015:
            cr += 0.10
            cg += 0.25
            cb += 0.35

    # dotted trajectory: swings from bottom-left around the planet to top-right
    for i in range(16):
        t = i / 15.0
        ang = math.radians(210 - 240 * t)
        rad = pr * (2.6 - 0.75 * math.sin(math.pi * t))
        txp = pcx + math.cos(ang) * rad
        typ = pcy - math.sin(ang) * rad
        if math.hypot(u - txp, v - typ) < 0.030:
            cr, cg, cb = 0.91, 0.98, 1.0

    # comet at trajectory start
    cd = math.hypot(u + 0.62, v - 0.55)
    cglow = math.exp(-((cd / 0.14) ** 2)) * 0.8
    cg += cglow * 0.85
    cb += cglow
    cr += cglow * 0.3
    if cd < 0.06:
        cr, cg, cb = 0.91, 0.98, 1.0

    # goal ring top-right
    gd = math.hypot(u - 0.55, v + 0.55)
    if 0.10 < gd < 0.16:
        cr, cg, cb = 0.41, 0.94, 0.68
    else:
        gglow = math.exp(-((abs(gd - 0.13) / 0.10) ** 2)) * 0.4
        cg += gglow * 0.9
        cb += gglow * 0.5
        cr += gglow * 0.2

    # small golden star pickup
    sd = abs(u - 0.42) + abs(v - 0.18)
    if sd < 0.07:
        cr, cg, cb = 1.0, 0.84, 0.25

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
