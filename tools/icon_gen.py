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

    # KEPLER: a pastel planet, its shimmering capture band,
    # a golden moon mid-orbit and a dotted comet approach
    pxp, pyp, pr = -0.04, 0.04, 0.26
    pd = math.hypot(u - pxp, v - pyp)

    # nebula tints
    for nx, ny, nr, (tr, tg, tb) in ((-0.5, -0.5, 0.9, (0.30, 0.22, 0.55)),
                                     (0.6, 0.55, 0.8, (0.10, 0.40, 0.45))):
        nd = math.hypot(u - nx, v - ny)
        k = math.exp(-((nd / nr) ** 2)) * 0.35
        cr += tr * k
        cg += tg * k
        cb += tb * k

    # capture band annulus
    band_mid, band_w = pr * 1.95, pr * 0.55
    bd = abs(pd - band_mid)
    if bd < band_w / 2:
        cr += 0.10
        cg += 0.12
        cb += 0.18
    if abs(pd - (band_mid - band_w / 2)) < 0.012 or abs(pd - (band_mid + band_w / 2)) < 0.012:
        cr += 0.18
        cg += 0.22
        cb += 0.30

    # planet body (pastel indigo)
    if pd < pr:
        cr, cg, cb = 0.26, 0.32, 0.55
        if math.hypot(u - pxp - 0.06, v - pyp - 0.05) > pr * 0.85:
            cr, cg, cb = 0.19, 0.24, 0.44
    elif pd < pr * 1.10:
        cr, cg, cb = 0.45, 0.55, 0.85

    # golden progress arc along the band (about 270 degrees)
    ang = math.atan2(v - pyp, u - pxp)
    if bd < 0.02 and not (-0.6 < ang < 0.2):
        cr, cg, cb = 1.0, 0.88, 0.55
    # golden moon at the arc's head
    ma = 0.2
    mx0 = pxp + math.cos(ma) * band_mid
    my0 = pyp + math.sin(ma) * band_mid
    md = math.hypot(u - mx0, v - my0)
    mglow = math.exp(-((md / 0.15) ** 2)) * 0.8
    cr += mglow
    cg += mglow * 0.88
    cb += mglow * 0.55
    if md < 0.07:
        cr, cg, cb = 1.0, 0.92, 0.66

    # dotted comet approach from bottom-left
    for i in range(7):
        t = i / 6.0
        tx = -0.85 + t * 0.55
        ty = 0.85 - t * 0.45
        if math.hypot(u - tx, v - ty) < 0.022:
            cr, cg, cb = 0.93, 0.97, 1.0
    sd0 = math.hypot(u + 0.85, v - 0.85)
    sg = math.exp(-((sd0 / 0.12) ** 2)) * 0.7
    cr += sg * 0.9
    cg += sg * 0.95
    cb += sg

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
