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
    r2 = u * u + v * v
    d = math.sqrt(r2)

    # rounded-square plate
    k = 0.86
    qx, qy = max(abs(u) - k + 0.30, 0.0), max(abs(v) - k + 0.30, 0.0)
    plate = math.sqrt(qx * qx + qy * qy) - 0.30
    if plate > 0:
        return (0, 0, 0, 0)

    # dark navy base, slightly lighter in the middle
    base = 0.10 + 0.07 * max(0.0, 1.0 - d * 1.2)
    cr, cg, cb = base * 0.55, base * 0.75, base * 1.6

    ring_r = 0.58
    rd = abs(d - ring_r)

    # cyan glow + crisp ring
    glow = math.exp(-(rd / 0.16) ** 2) * 0.55
    cr += 0.00 * glow
    cg += 0.85 * glow
    cb += 1.00 * glow
    if rd < 0.045:
        cr, cg, cb = 0.25, 0.95, 1.0

    # ball at 40 degrees
    ba = math.radians(40)
    bx, by = math.cos(ba) * ring_r, -math.sin(ba) * ring_r
    bd = math.hypot(u - bx, v - by)
    bglow = math.exp(-(bd / 0.16) ** 2) * 0.9
    cr += 1.0 * bglow
    cg += 0.85 * bglow
    cb += 0.95 * bglow
    if bd < 0.085:
        cr, cg, cb = 1.0, 1.0, 1.0

    # gem (diamond) at 215 degrees
    ga = math.radians(215)
    gx, gy = math.cos(ga) * ring_r, -math.sin(ga) * ring_r
    if abs(u - gx) + abs(v - gy) < 0.10:
        cr, cg, cb = 1.0, 0.84, 0.25

    # spike (red wedge) at 330 degrees
    sa = math.radians(330)
    sx, sy = math.cos(sa) * ring_r, -math.sin(sa) * ring_r
    if math.hypot(u - sx, v - sy) < 0.085:
        cr, cg, cb = 1.0, 0.24, 0.35

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
