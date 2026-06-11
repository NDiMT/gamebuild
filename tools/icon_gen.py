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

    ground = 0.42

    # neon ground line + glow
    gd = abs(v - ground)
    glow = math.exp(-(gd / 0.14) ** 2) * 0.5
    cg += 0.85 * glow
    cb += 1.00 * glow
    if gd < 0.035 :
        cr, cg, cb = 0.25, 0.95, 1.0

    # perspective grid under the ground line
    if v > ground + 0.03:
        depth = (v - ground) / (1.0 - ground)
        gx = u / (0.35 + 0.65 * depth)
        if abs((gx * 3.0 + 0.5) % 1.0 - 0.5) < 0.045:
            cg += 0.35
            cb += 0.4

    # the running cube (slightly tilted, mid-jump) with trail
    cxp, cyp = 0.10, ground - 0.34
    ca, sa = math.cos(math.radians(14)), math.sin(math.radians(14))
    ru = (u - cxp) * ca - (v - cyp) * sa
    rv = (u - cxp) * sa + (v - cyp) * ca
    half = 0.21
    bd = max(abs(ru), abs(rv))
    bglow = math.exp(-(max(0.0, bd - half) / 0.12) ** 2) * 0.8
    cr += 1.0 * bglow
    cg += 0.85 * bglow
    cb += 0.95 * bglow
    if bd < half:
        cr, cg, cb = 1.0, 1.0, 1.0
        # eyes
        if (math.hypot(ru - 0.08, rv + 0.05) < 0.035
                or math.hypot(ru + 0.01, rv + 0.05) < 0.035):
            cr, cg, cb = 0.06, 0.07, 0.11
    # speed trail to the left of the cube
    for i in range(1, 4):
        tx = cxp - i * 0.16
        td = max(abs((u - tx) * ca - (v - cyp) * sa), abs((u - tx) * sa + (v - cyp) * ca))
        if td < half * (1.0 - i * 0.18):
            fade = 0.5 / i
            cr += fade
            cg += fade * 0.9
            cb += fade

    # red spike on the ground, right side
    spx = 0.52
    if v <= ground and v > ground - 0.30:
        hwid = 0.16 * (1.0 - (ground - v) / 0.30)
        if abs(u - spx) < hwid:
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
