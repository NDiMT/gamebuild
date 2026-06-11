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

    # TETHER: two orbs joined by a glowing cord that slices a shadow
    ax, ay = -0.42, 0.38   # white player orb
    bx2, by2 = 0.45, -0.42 # cyan partner orb

    # cord: quadratic bezier with slight sag
    mx, my = (ax + bx2) / 2 + 0.10, (ay + by2) / 2 + 0.10
    best_d = 9.9
    for i in range(33):
        t = i / 32.0
        qx_ = (1 - t) ** 2 * ax + 2 * (1 - t) * t * mx + t * t * bx2
        qy_ = (1 - t) ** 2 * ay + 2 * (1 - t) * t * my + t * t * by2
        d = math.hypot(u - qx_, v - qy_)
        if d < best_d:
            best_d = d
    cglow = math.exp(-((best_d / 0.10) ** 2)) * 0.7
    cg += 0.85 * cglow
    cb += 1.0 * cglow
    cr += 0.3 * cglow
    if best_d < 0.025:
        cr, cg, cb = 0.91, 0.98, 1.0

    # shadow being cut (split halves either side of the cord)
    for off in (-0.10, 0.10):
        sxp, syp = 0.10 + off * 0.7, 0.02 - off * 0.7
        sd = math.hypot(u - sxp, v - syp)
        if sd < 0.115 and best_d > 0.035:
            cr, cg, cb = 0.10, 0.11, 0.20
            la = math.atan2(ay - syp, ax - sxp)
            for side in (-1, 1):
                exp_ = sxp + math.cos(la) * 0.04 + math.cos(la + math.pi / 2) * 0.045 * side
                eyp = syp + math.sin(la) * 0.04 + math.sin(la + math.pi / 2) * 0.045 * side
                if math.hypot(u - exp_, v - eyp) < 0.018:
                    cr, cg, cb = 0.56, 0.66, 1.0

    # player orb (white)
    pd = math.hypot(u - ax, v - ay)
    pglow = math.exp(-((pd / 0.16) ** 2)) * 0.85
    cr += pglow
    cg += pglow
    cb += pglow
    if pd < 0.085:
        cr, cg, cb = 1.0, 1.0, 1.0

    # partner orb (cyan, with motion trail)
    qd = math.hypot(u - bx2, v - by2)
    qglow = math.exp(-((qd / 0.18) ** 2)) * 0.8
    cg += 0.85 * qglow
    cb += 1.0 * qglow
    cr += 0.25 * qglow
    if qd < 0.095:
        cr, cg, cb = 0.25, 0.90, 1.0
    for i in range(1, 4):
        td = math.hypot(u - (bx2 + 0.10 * i), v - (by2 + 0.13 * i))
        if td < 0.09 * (1.0 - i * 0.22):
            fade = 0.55 / i
            cg += fade * 0.85
            cb += fade
            cr += fade * 0.2

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
