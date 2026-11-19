#!/usr/bin/env python3
"""coreshaders - procedural equirectangular panoramas and the resource pack files.

    python3 -I coreshaders/tools/panoramas.py            # textures, models, item definitions
    python3 -I coreshaders/tools/panoramas.py --only sky_sea

Every panorama is W x H = 1024 x 512, u = atan(d.z, d.x) / 2pi + 0.5, v = acos(d.y) / pi (v = 0 is straight up).
Row 0 is the sentinel row: texel (0,0) = (61,203,147,3), texel (1,0) = (K,147,203,3), K = image kind 1..15,
the rest of row 0 repeats row 1. The shader samples rows 1..H-1 only.
Glow sprites (plane frame) are 16 x 16: texel (0,0) = (61,203,147,3), texel (1,0) = (16+K,147,203,3).
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "pack", "assets")
NS = "coreshaders"
W, H = 1024, 512
SENT = (61, 203, 147, 3)
IMAGES = ["sky_sea", "space", "nether", "aurora", "end"]
KIND = {name: i + 1 for i, name in enumerate(IMAGES)}
GLOW_KIND_BASE = 16


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def mix(a, b, t):
    t = np.asarray(t, dtype=np.float64)
    if t.ndim == 2:
        t = t[..., None]
    return np.asarray(a) * (1.0 - t) + np.asarray(b) * t


def directions(w=W, h=H):
    u = (np.arange(w) + 0.5) / w
    v = (np.arange(h) + 0.5) / h
    uu, vv = np.meshgrid(u, v)
    th = (uu - 0.5) * 2.0 * math.pi
    ph = vv * math.pi
    d = np.stack([np.sin(ph) * np.cos(th), np.cos(ph), np.sin(ph) * np.sin(th)], axis=-1)
    return d, uu, vv


def _hash3(ix, iy, iz, seed):
    x = (ix.astype(np.int64) * 73856093) ^ (iy.astype(np.int64) * 19349663) ^ (iz.astype(np.int64) * 83492791) ^ (seed * 2654435761)
    x = x & 0xFFFFFFFF
    x = (x ^ (x >> 16)) * 0x45D9F3B & 0xFFFFFFFF
    x = (x ^ (x >> 16)) * 0x45D9F3B & 0xFFFFFFFF
    x = x ^ (x >> 16)
    return (x & 0xFFFFFF).astype(np.float64) / float(0xFFFFFF)


def vnoise3(p, seed=0):
    i = np.floor(p)
    f = p - i
    f = f * f * (3.0 - 2.0 * f)
    i = i.astype(np.int64)
    ix, iy, iz = i[..., 0], i[..., 1], i[..., 2]
    fx, fy, fz = f[..., 0], f[..., 1], f[..., 2]
    def c(a, b, cc):
        return _hash3(ix + a, iy + b, iz + cc, seed)
    x00 = c(0, 0, 0) * (1 - fx) + c(1, 0, 0) * fx
    x10 = c(0, 1, 0) * (1 - fx) + c(1, 1, 0) * fx
    x01 = c(0, 0, 1) * (1 - fx) + c(1, 0, 1) * fx
    x11 = c(0, 1, 1) * (1 - fx) + c(1, 1, 1) * fx
    y0 = x00 * (1 - fy) + x10 * fy
    y1 = x01 * (1 - fy) + x11 * fy
    return y0 * (1 - fz) + y1 * fz


_ROT = np.array([[0.00, 0.80, 0.60], [-0.80, 0.36, -0.48], [-0.60, -0.48, 0.64]])


def fbm3(p, octaves=5, seed=0, gain=0.5, lac=2.03):
    s = np.zeros(p.shape[:-1])
    a = 0.5
    norm = 0.0
    q = p.copy()
    for o in range(octaves):
        s += a * vnoise3(q, seed + o * 17)
        norm += a
        a *= gain
        q = (q @ _ROT.T) * lac
    return s / norm


def ridged3(p, octaves=4, seed=0):
    s = np.zeros(p.shape[:-1])
    a = 0.5
    norm = 0.0
    q = p.copy()
    for o in range(octaves):
        n = 1.0 - np.abs(2.0 * vnoise3(q, seed + o * 31) - 1.0)
        s += a * n * n
        norm += a
        a *= 0.5
        q = (q @ _ROT.T) * 2.1
    return s / norm


def cell_stars(d, scale, seed, density=0.5, size=0.18):
    p = d * scale
    i = np.floor(p).astype(np.int64)
    best = np.zeros(d.shape[:-1])
    col = np.zeros(d.shape[:-1])
    for a in (-1, 0, 1):
        for b in (-1, 0, 1):
            for c in (-1, 0, 1):
                cx, cy, cz = i[..., 0] + a, i[..., 1] + b, i[..., 2] + c
                on = _hash3(cx, cy, cz, seed) < density
                jx = _hash3(cx, cy, cz, seed + 1)
                jy = _hash3(cx, cy, cz, seed + 2)
                jz = _hash3(cx, cy, cz, seed + 3)
                br = _hash3(cx, cy, cz, seed + 4)
                sp = np.stack([cx + 0.15 + 0.7 * jx, cy + 0.15 + 0.7 * jy, cz + 0.15 + 0.7 * jz], axis=-1)
                dist = np.linalg.norm(p - sp, axis=-1)
                r = size * (0.35 + 0.65 * br ** 3)
                v = np.exp(-(dist / r) ** 2) * (0.25 + 0.75 * br ** 2) * on
                upd = v > best
                best = np.where(upd, v, best)
                col = np.where(upd, _hash3(cx, cy, cz, seed + 5), col)
    return best, col


def plane_coords(d, height, eps=0.015):
    y = np.maximum(np.abs(d[..., 1]), eps)
    return np.stack([d[..., 0] / y * height, d[..., 2] / y * height], axis=-1)


def p3(xz, y=0.0):
    return np.stack([xz[..., 0], np.full(xz.shape[:-1], y), xz[..., 1]], axis=-1)


def pano_sky_sea():
    d, uu, vv = directions()
    y = d[..., 1]
    up = np.clip(y, 0.0, 1.0)
    sun = np.array([0.55, 0.42, -0.72])
    sun /= np.linalg.norm(sun)
    cs = np.clip(d @ sun, -1, 1)
    zen = np.array([0.10, 0.36, 0.86])
    mid = np.array([0.30, 0.60, 0.97])
    hor = np.array([0.74, 0.88, 0.98])
    t = up ** 0.45
    sky = mix(hor, mid, smoothstep(0.0, 0.55, t))
    sky = mix(sky, zen, smoothstep(0.45, 1.0, t))
    sky = sky + np.array([1.0, 0.95, 0.8]) * (0.35 * np.exp((cs - 1.0) * 60.0) + 0.12 * np.exp((cs - 1.0) * 6.0))[..., None]
    pc = plane_coords(d, 1.0, 0.03)
    def density(off):
        q = p3(pc * 0.9 + off)
        warp = fbm3(q * 0.6 + 3.1, 3, seed=7)
        q2 = q + np.stack([warp, warp * 0.3, -warp], -1) * 0.7
        base = fbm3(q2 * 0.7, 4, seed=11)
        puff = 1.0 - np.abs(2.0 * fbm3(q2 * 1.5, 4, seed=13) - 1.0)
        return base * 0.8 + puff * 0.25
    den = density(0.0)
    cov = 0.625 + 0.03 * np.sin(uu * 2 * math.pi * 3.0)
    cloud = smoothstep(cov, cov + 0.09, den)
    sdir = np.array([sun[0], sun[2]]) / np.hypot(sun[0], sun[2])
    den2 = density(sdir * 0.05)
    lit = np.clip(0.72 + (den - den2) * 7.0, 0.0, 1.0)
    core = smoothstep(cov + 0.06, cov + 0.25, den)
    ccol = mix(np.array([0.60, 0.68, 0.80]), np.array([1.0, 1.0, 1.0]), lit)
    ccol = mix(ccol, ccol * np.array([0.86, 0.9, 0.97]), core * 0.5)
    ccol = ccol + np.array([1.0, 0.9, 0.7]) * (0.25 * np.exp((cs - 1.0) * 8.0))[..., None] * lit[..., None]
    fade = smoothstep(0.015, 0.12, y)
    haze = 1.0 - smoothstep(0.0, 0.25, y)
    ccol = mix(ccol, hor * 1.02, haze * 0.55)
    sky = mix(sky, ccol, cloud * fade)
    dn = np.clip(-y, 0.0, 1.0)
    sea_h = np.array([0.30, 0.80, 0.82])
    sea_m = np.array([0.06, 0.56, 0.66])
    sea_d = np.array([0.02, 0.26, 0.42])
    s = dn ** 0.5
    sea = mix(sea_h, sea_m, smoothstep(0.0, 0.35, s))
    sea = mix(sea, sea_d, smoothstep(0.3, 1.0, s))
    sp = plane_coords(d, 1.0, 0.004)
    wq = p3(sp * np.array([5.0, 3.0]))
    waves = fbm3(wq, 5, seed=23)
    waves2 = fbm3(p3(sp * np.array([16.0, 10.0])) + 1.7, 3, seed=29)
    wamp = smoothstep(0.0, 0.2, dn)
    waves = 0.5 + (waves - 0.5) * (0.35 + 0.65 * wamp)
    waves2 = 0.5 + (waves2 - 0.5) * wamp
    sea = sea * (0.86 + 0.28 * waves[..., None]) + 0.06 * (waves2[..., None] - 0.5)
    fres = (1.0 - smoothstep(0.0, 0.3, dn))
    sea = mix(sea, hor * np.array([0.85, 0.97, 1.0]), fres * 0.35)
    refl = np.array([sun[0], -sun[1], sun[2]])
    cr = np.clip(d @ refl, -1, 1)
    glitter = np.exp((cr - 1.0) * 30.0) * smoothstep(0.62, 0.8, waves2 * 0.6 + waves * 0.5)
    sea = sea + np.array([1.0, 0.97, 0.88]) * (glitter * 1.4 + 0.25 * np.exp((cr - 1.0) * 12.0))[..., None]
    img = np.where((y >= 0.0)[..., None], sky, sea)
    line = np.exp(-(y / 0.006) ** 2)
    img = mix(img, np.array([0.78, 0.92, 0.97]), line * 0.45)
    return img


def pano_space():
    d, uu, vv = directions()
    gal = np.array([0.35, 0.82, -0.45])
    gal /= np.linalg.norm(gal)
    band = np.exp(-((d @ gal) / 0.28) ** 2)
    n1 = fbm3(d * 2.2 + 5.0, 6, seed=41)
    n2 = fbm3(d * 3.5 - 2.0, 5, seed=43)
    n3 = ridged3(d * 2.8 + 1.3, 5, seed=47)
    neb = smoothstep(0.42, 0.78, n1) * (0.35 + 0.65 * band)
    img = np.zeros(d.shape) + np.array([0.008, 0.008, 0.025])
    img = img + np.array([0.05, 0.04, 0.12]) * band[..., None] * (0.5 + n2[..., None])
    c1 = np.array([0.55, 0.18, 0.75])
    c2 = np.array([0.15, 0.35, 0.95])
    c3 = np.array([0.95, 0.30, 0.55])
    nc = mix(c1, c2, smoothstep(0.35, 0.65, n2))
    nc = mix(nc, c3, smoothstep(0.62, 0.85, n3) * 0.7)
    img = img + nc * (neb ** 1.4 * 0.95)[..., None]
    fil = smoothstep(0.55, 0.9, n3) * neb
    img = img + np.array([0.9, 0.75, 1.0]) * (fil * 0.35)[..., None]
    dust = smoothstep(0.5, 0.75, fbm3(d * 6.0, 4, seed=53)) * band
    img = img * (1.0 - 0.55 * dust[..., None])
    for scale, dens, size, gainv, seed in ((60, 0.55, 0.16, 1.0, 61), (140, 0.5, 0.14, 0.75, 67), (25, 0.18, 0.12, 1.6, 71)):
        st, sc = cell_stars(d, scale, seed, dens, size)
        scol = mix(np.array([0.7, 0.8, 1.0]), np.array([1.0, 0.85, 0.65]), sc)
        img = img + scol * (st * gainv)[..., None]
    cen = np.array([-0.6, 0.25, 0.75])
    cen /= np.linalg.norm(cen)
    cc = np.clip(d @ cen, -1, 1)
    img = img + np.array([0.6, 0.45, 1.0]) * (0.5 * np.exp((cc - 1.0) * 300.0) + 0.18 * np.exp((cc - 1.0) * 30.0))[..., None]
    return img


def pano_nether():
    d, uu, vv = directions()
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    dn = np.clip(-y, 0, 1)
    fog = fbm3(d * 3.0 + 2.0, 5, seed=81)
    sky = mix(np.array([0.62, 0.18, 0.08]), np.array([0.22, 0.03, 0.03]), smoothstep(0.0, 0.7, up ** 0.6))
    sky = sky * (0.75 + 0.5 * fog[..., None])
    roof = plane_coords(d, 1.0, 0.03)
    rock = ridged3(p3(roof * 1.6), 5, seed=83)
    roofc = mix(np.array([0.16, 0.03, 0.03]), np.array([0.38, 0.08, 0.06]), rock)
    roof_on = smoothstep(0.25, 0.55, up)
    sky = mix(sky, roofc, roof_on * 0.85)
    gs, gc = cell_stars(d, 14, 91, 0.45, 0.3)
    gcl = fbm3(d * 4.0, 3, seed=93)
    glow = gs * smoothstep(0.4, 0.6, gcl) * smoothstep(0.15, 0.4, up)
    sky = sky + np.array([1.0, 0.6, 0.2]) * (smoothstep(0.4, 0.6, gcl) * smoothstep(0.15, 0.4, up) * 0.12)[..., None]
    sky = sky + np.array([1.0, 0.85, 0.45]) * (glow * 1.6)[..., None]
    sky = sky + np.array([1.0, 0.75, 0.35]) * (0.5 * smoothstep(0.05, 0.0, np.abs(gs - 0.5) * 0) * 0)[..., None]
    ang = np.arctan2(d[..., 2], d[..., 0])
    ridge = 0.06 + 0.07 * fbm3(np.stack([np.cos(ang) * 3, np.sin(ang) * 3, np.zeros_like(ang)], -1), 5, seed=97)
    cliff = (y > 0.0) & (y < ridge)
    cliffc = mix(np.array([0.10, 0.02, 0.025]), np.array([0.30, 0.07, 0.05]), fbm3(d * 18.0, 3, seed=99))
    cliffc = mix(cliffc, np.array([0.7, 0.25, 0.1]), (1.0 - smoothstep(0.0, 0.05, y)) * 0.4)
    sky = np.where(cliff[..., None], cliffc, sky)
    lp = plane_coords(d, 1.0, 0.004)
    q = p3(lp * 1.2)
    cr = ridged3(q * 1.4, 5, seed=101)
    flow = fbm3(q * 2.0 + 4.0, 4, seed=103)
    pool = 0.8 * smoothstep(0.64, 0.8, fbm3(q * 0.5 + 7.0, 4, seed=105))
    far = 1.0 - smoothstep(0.0, 0.25, dn)
    crack = np.maximum(smoothstep(0.62, 0.9, cr) * (1.0 - far * 0.6), pool)
    hot = mix(np.array([1.0, 0.42, 0.04]), np.array([1.0, 0.86, 0.35]), smoothstep(0.35, 0.75, flow))
    crustc = mix(np.array([0.10, 0.02, 0.02]), np.array([0.30, 0.06, 0.03]), fbm3(q * 5.0, 3, seed=107))
    crustc = crustc + np.array([0.6, 0.15, 0.02]) * (smoothstep(0.45, 0.62, cr) * 0.35)[..., None]
    lava = mix(crustc, hot, crack)
    lava = mix(lava, np.array([0.9, 0.32, 0.07]), far * 0.55)
    img = np.where((y >= 0.0)[..., None], sky, lava)
    hz = np.exp(-(y / 0.03) ** 2)
    img = mix(img, np.array([1.0, 0.45, 0.12]), hz * 0.55)
    return img


def pano_aurora():
    d, uu, vv = directions()
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    dn = np.clip(-y, 0, 1)
    sky = mix(np.array([0.05, 0.10, 0.20]), np.array([0.008, 0.015, 0.05]), smoothstep(0.0, 0.8, up ** 0.7))
    st, sc = cell_stars(d, 70, 131, 0.5, 0.15)
    sky = sky + mix(np.array([0.75, 0.85, 1.0]), np.array([1.0, 0.95, 0.8]), sc) * (st * 0.9)[..., None]
    st2, _ = cell_stars(d, 150, 137, 0.5, 0.13)
    sky = sky + np.array([0.7, 0.8, 1.0]) * (st2 * 0.5)[..., None]
    ang = np.arctan2(d[..., 2], d[..., 0])
    elev = np.arcsin(np.clip(y, -1, 1))
    total = np.zeros(y.shape)
    colacc = np.zeros(d.shape)
    for k, (base, amp, freq, ph, seed, gain) in enumerate(((0.38, 0.12, 2.0, 0.3, 141, 1.0), (0.62, 0.10, 3.0, 2.1, 143, 0.7),
                                                          (0.25, 0.08, 1.0, 4.0, 147, 0.6))):
        ring = np.stack([np.cos(ang), np.sin(ang), np.zeros_like(ang)], -1)
        wob = fbm3(ring * 1.5 + k * 3.0, 4, seed=seed)
        center = base + amp * np.sin(ang * freq + ph) + 0.12 * (wob - 0.5)
        rel = (elev - center) / 0.42
        lower = smoothstep(-0.12, 0.0, rel) * (1.0 - smoothstep(0.0, 1.0, rel))
        rays = fbm3(np.stack([np.cos(ang) * 40.0, np.sin(ang) * 40.0, np.full_like(ang, k * 7.0)], -1), 3, seed=seed + 1)
        rays = 0.45 + 0.9 * smoothstep(0.35, 0.8, rays)
        strength = smoothstep(0.25, 0.65, fbm3(ring * 2.5 + 11.0 + k, 4, seed=seed + 2))
        a = lower * rays * strength * gain
        c = mix(np.array([0.15, 1.0, 0.45]), np.array([0.65, 0.25, 0.95]), smoothstep(0.15, 0.85, rel))
        colacc = colacc + c * a[..., None]
        total = total + a
    sky = sky + colacc * 0.95
    sky = sky + np.array([0.1, 0.4, 0.25]) * (np.clip(total, 0, 1) * 0.15 * (1.0 - smoothstep(0.0, 0.5, up)))[..., None]
    ridge = 0.05 + 0.10 * fbm3(np.stack([np.cos(ang) * 2.5, np.sin(ang) * 2.5, np.full_like(ang, 3.0)], -1), 5, seed=151)
    mtn = (y >= 0.0) & (y < ridge)
    capn = fbm3(d * 25.0, 4, seed=153)
    cap = smoothstep(0.55, 0.85, y / np.maximum(ridge, 1e-3) + 0.4 * (capn - 0.5))
    mc = mix(np.array([0.06, 0.08, 0.14]), np.array([0.42, 0.48, 0.62]), cap)
    mc = mc * (0.85 + 0.3 * capn)[..., None]
    sky = np.where(mtn[..., None], mc, sky)
    tree_n = fbm3(np.stack([np.cos(ang) * 60.0, np.sin(ang) * 60.0, np.zeros_like(ang)], -1), 2, seed=157)
    tree_h = 0.012 + 0.03 * smoothstep(0.45, 0.85, tree_n)
    trees = (y >= 0.0) & (y < tree_h)
    sky = np.where(trees[..., None], np.array([0.02, 0.05, 0.06]), sky)
    gp = plane_coords(d, 1.0, 0.004)
    gn = fbm3(p3(gp * 2.0), 5, seed=161)
    snow = mix(np.array([0.26, 0.33, 0.48]), np.array([0.46, 0.54, 0.70]), smoothstep(0.3, 0.75, gn))
    snow = mix(snow, np.array([0.30, 0.40, 0.55]), smoothstep(0.0, 0.12, dn) * 0.0 + (1.0 - smoothstep(0.0, 0.15, dn)) * 0.4)
    glint, _ = cell_stars(d, 120, 167, 0.3, 0.1)
    snow = snow + np.array([0.8, 0.9, 1.0]) * (glint * smoothstep(0.1, 0.4, dn) * 0.6)[..., None]
    snow = snow + np.array([0.1, 0.5, 0.3]) * (0.08 * (1.0 - smoothstep(0.0, 0.5, dn)))[..., None]
    img = np.where((y >= 0.0)[..., None], sky, snow)
    return img


END_COLORS = np.array([
    (0.022087, 0.098399, 0.110818), (0.011892, 0.095924, 0.089485), (0.027636, 0.101689, 0.100326),
    (0.046564, 0.109883, 0.114838), (0.064901, 0.117696, 0.097189), (0.063761, 0.086895, 0.123646),
    (0.084817, 0.111994, 0.166380), (0.097489, 0.154120, 0.091064), (0.106152, 0.131144, 0.195191),
    (0.097721, 0.110188, 0.187229), (0.133516, 0.138278, 0.148582), (0.070006, 0.243332, 0.235792),
    (0.196766, 0.142899, 0.214696), (0.047281, 0.315338, 0.321970), (0.204675, 0.390010, 0.302066),
    (0.080955, 0.314821, 0.661491)])


def pano_end():
    d, uu, vv = directions()
    n1 = fbm3(d * 2.0 + 9.0, 5, seed=181)
    n2 = fbm3(d * 4.5 - 3.0, 4, seed=183)
    img = np.zeros(d.shape) + np.array([0.012, 0.035, 0.045])
    img = img + np.array([0.04, 0.24, 0.25]) * (smoothstep(0.42, 0.8, n1) ** 1.3)[..., None]
    img = img + np.array([0.10, 0.06, 0.18]) * (smoothstep(0.55, 0.85, n2) * 0.7)[..., None]
    for layer, (scale, dens, size, gain) in enumerate(((12, 0.5, 0.10, 2.2), (24, 0.55, 0.11, 2.0), (45, 0.6, 0.12, 1.6),
                                                      (90, 0.6, 0.13, 1.3), (170, 0.5, 0.14, 1.0))):
        st, sc = cell_stars(d, scale, 191 + layer * 7, dens, size)
        idx = np.clip((sc * 16).astype(int), 0, 15)
        col = END_COLORS[idx] * 4.5 + 0.06
        img = img + col * (st * gain)[..., None]
    return img


PANORAMAS = {"sky_sea": pano_sky_sea, "space": pano_space, "nether": pano_nether, "aurora": pano_aurora, "end": pano_end}
GLOW = {"sky_sea": (0.80, 0.95, 1.00), "space": (0.70, 0.45, 1.00), "nether": (1.00, 0.45, 0.12),
        "aurora": (0.35, 1.00, 0.60), "end": (0.20, 0.85, 0.80)}


def to_rgba8(img):
    img = np.clip(img, 0.0, 1.0)
    img = img ** (1.0 / 1.0)
    rgb = np.round(img * 255.0).astype(np.uint8)
    a = np.full(rgb.shape[:2] + (1,), 255, np.uint8)
    return np.concatenate([rgb, a], axis=-1)


def with_sentinel(rgba, kind):
    out = rgba.copy()
    out[0] = out[1]
    out[0, 0] = SENT
    out[0, 1] = (kind, SENT[2], SENT[1], 3)
    return out


def glow_sprite(name):
    g = np.zeros((16, 16, 4), np.uint8)
    r, gg, b = (int(round(c * 255)) for c in GLOW[name])
    g[..., 0], g[..., 1], g[..., 2] = r, gg, b
    g[..., 3] = 0
    g[0, 0] = SENT
    g[0, 1] = (GLOW_KIND_BASE + KIND[name], SENT[2], SENT[1], 3)
    return g


def face(tex):
    return {"uv": [0, 0, 16, 16], "texture": "#" + tex, "tintindex": 0}


def cube_model(name):
    return {"textures": {"p": f"{NS}:item/panorama/{name}", "particle": f"{NS}:item/panorama/{name}"},
            "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "shade": False, "light_emission": 15,
                          "faces": {f: face("p") for f in ("north", "south", "east", "west", "up", "down")}}],
            "display": {}}


def plane_model(name):
    return {"textures": {"p": f"{NS}:item/panorama/{name}", "g": f"{NS}:item/panorama/{name}_glow",
                         "particle": f"{NS}:item/panorama/{name}"},
            "elements": [{"from": [0, 0, 8], "to": [16, 16, 8], "shade": False, "light_emission": 15,
                          "faces": {"north": face("p"), "south": face("p")}},
                         {"from": [-3, -3, 8], "to": [19, 19, 8], "shade": False, "light_emission": 15,
                          "faces": {"north": face("g"), "south": face("g")}}],
            "display": {}}


def item_def(model):
    return {"model": {"type": "minecraft:model", "model": f"{NS}:item/{model}",
                      "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": -1}]}}


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, indent=1)
        fh.write("\n")


def build(only=None):
    tdir = os.path.join(ASSETS, NS, "textures", "item", "panorama")
    os.makedirs(tdir, exist_ok=True)
    out = {}
    for name in IMAGES:
        if only and name not in only:
            continue
        img = PANORAMAS[name]()
        rgba = with_sentinel(to_rgba8(img), KIND[name])
        Image.fromarray(rgba, "RGBA").save(os.path.join(tdir, name + ".png"), optimize=True)
        Image.fromarray(glow_sprite(name), "RGBA").save(os.path.join(tdir, name + "_glow.png"))
        out[name] = rgba
        print("panorama", name, rgba.shape)
    for name in IMAGES:
        dump(os.path.join(ASSETS, NS, "models", "item", f"cube_{name}.json"), cube_model(name))
        dump(os.path.join(ASSETS, NS, "models", "item", f"plane_{name}.json"), plane_model(name))
        dump(os.path.join(ASSETS, NS, "items", f"cube_{name}.json"), item_def(f"cube_{name}"))
        dump(os.path.join(ASSETS, NS, "items", f"plane_{name}.json"), item_def(f"plane_{name}"))
    return out


if __name__ == "__main__":
    only = None
    if "--only" in sys.argv:
        only = sys.argv[sys.argv.index("--only") + 1].split(",")
    build(only)
