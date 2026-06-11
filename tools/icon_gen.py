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

    # ECHO: a white dot pursued by colored ghosts of itself along a loop
    def loop_pos(t):
        return (0.36 * math.cos(t), -0.36 * math.sin(t * 1.4) - 0.05)

    ghosts = [
        (2.4, (0.25, 0.95, 1.0)),   # cyan
        (3.3, (1.0, 0.35, 0.85)),   # magenta
        (4.2, (1.0, 0.78, 0.25)),   # amber
    ]
    # ghost breadcrumb trails
    for t0, (gr_, gg_, gb_) in ghosts:
        for s in range(6):
            tx, ty = loop_pos(t0 - s * 0.18)
            td = math.hypot(u - tx, v - ty)
            rr = 0.045 * (1.0 - s * 0.13)
            if td < rr:
                fade = 0.8 - s * 0.12
                cr += gr_ * fade
                cg += gg_ * fade
                cb += gb_ * fade
        gx, gy = loop_pos(t0)
        gd = math.hypot(u - gx, v - gy)
        glow = math.exp(-((gd / 0.13) ** 2)) * 0.5
        cr += gr_ * glow
        cg += gg_ * glow
        cb += gb_ * glow
        if gd < 0.07:
            cr, cg, cb = gr_, gg_, gb_

    # the player: bright white, one step ahead
    pxp, pyp = loop_pos(1.5)
    pd = math.hypot(u - pxp, v - pyp)
    pglow = math.exp(-((pd / 0.18) ** 2)) * 0.85
    cr += pglow
    cg += pglow
    cb += pglow
    if pd < 0.085:
        cr, cg, cb = 1.0, 1.0, 1.0

    # golden orb top-right
    od = math.hypot(u - 0.52, v + 0.52)
    oglow = math.exp(-((od / 0.14) ** 2)) * 0.55
    cr += 1.0 * oglow
    cg += 0.85 * oglow
    cb += 0.3 * oglow
    if od < 0.05:
        cr, cg, cb = 1.0, 0.88, 0.5

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
