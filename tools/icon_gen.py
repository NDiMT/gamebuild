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

    # BEACON: a warm core at center, one beam of light, shadows with eyes
    bd = math.hypot(u, v)

    # beam pointing up-right
    beam_dir = math.radians(-38)
    ang = math.atan2(v, u)
    adiff = abs((ang - beam_dir + math.pi) % (2 * math.pi) - math.pi)
    if bd > 0.04:
        beam = math.exp(-((adiff / 0.30) ** 2)) * max(0.0, 1.0 - bd * 0.35)
        cr += 1.00 * beam * 0.85
        cg += 0.85 * beam * 0.85
        cb += 0.45 * beam * 0.85

    # glowing core
    cglow = math.exp(-((bd / 0.22) ** 2)) * 0.9
    cr += cglow
    cg += cglow * 0.92
    cb += cglow * 0.75
    if bd < 0.10:
        cr, cg, cb = 1.0, 0.96, 0.82

    # shadows lurking in the dark (eyes glinting), one caught in the beam
    shadows = [(-0.52, 0.42, 0.14, False), (-0.30, -0.52, 0.11, False),
               (0.58, -0.40, 0.13, True)]
    for sxp, syp, sr, in_beam in shadows:
        sd = math.hypot(u - sxp, v - syp)
        if sd < sr:
            if in_beam:
                cr, cg, cb = 0.24, 0.21, 0.32
            else:
                cr, cg, cb = 0.07, 0.08, 0.15
        elif in_beam and sd < sr * 1.25:
            cr, cg, cb = 1.0, 0.82, 0.45  # hot rim
        # eyes face the light
        la = math.atan2(-syp, -sxp)
        for side in (-1, 1):
            exp_ = sxp + math.cos(la) * sr * 0.35 + math.cos(la + math.pi / 2) * sr * 0.38 * side
            eyp = syp + math.sin(la) * sr * 0.35 + math.sin(la + math.pi / 2) * sr * 0.38 * side
            if math.hypot(u - exp_, v - eyp) < sr * 0.16:
                if in_beam:
                    cr, cg, cb = 1.0, 0.91, 0.66
                else:
                    cr, cg, cb = 0.56, 0.66, 1.0

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
