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

    # a swarm of fireflies sweeping in a comet curve
    fireflies = [
        (-0.42, 0.30, 0.050), (-0.30, 0.12, 0.060), (-0.16, -0.02, 0.065),
        (-0.02, -0.14, 0.075), (0.14, -0.22, 0.085), (0.32, -0.26, 0.100),
        (-0.34, 0.34, 0.040), (-0.20, 0.20, 0.045), (-0.06, 0.06, 0.050),
        (0.10, -0.04, 0.055), (0.26, -0.10, 0.050), (0.05, -0.30, 0.045),
        (0.42, -0.40, 0.060), (-0.10, -0.26, 0.040),
    ]
    for fx, fy, fr in fireflies:
        d2 = math.hypot(u - fx, v - fy)
        glow = math.exp(-((d2 / (fr * 3.2)) ** 2)) * 0.55
        cr += 1.00 * glow
        cg += 0.92 * glow
        cb += 0.45 * glow
        if d2 < fr:
            cr, cg, cb = 1.0, 0.97, 0.82

    # red obstacle slab on the right edge
    if 0.62 < u < 0.84 and -0.9 < v < 0.45:
        cr, cg, cb = 0.16, 0.10, 0.20
        if u < 0.66 or u > 0.80 or v < -0.86 or v > 0.41:
            cr, cg, cb = 1.0, 0.24, 0.35
    else:
        sd = max(0.62 - u, u - 0.84, -0.9 - v, v - 0.45)
        if sd < 0.10:
            k = math.exp(-((sd / 0.07) ** 2)) * 0.35
            cr += k
            cg += k * 0.2
            cb += k * 0.25

    # cyan orb bottom-left
    od = math.hypot(u + 0.45, v + 0.52)
    oglow = math.exp(-((od / 0.16) ** 2)) * 0.6
    cg += 0.8 * oglow
    cb += 1.0 * oglow
    if 0.055 < od < 0.085:
        cr, cg, cb = 0.25, 0.90, 1.0
    elif od < 0.035:
        cr, cg, cb = 0.25, 0.90, 1.0

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
