#!/usr/bin/env python3
"""coreshaders - procedural equirectangular panoramas and the resource pack files.

    python3 -I coreshaders/tools/panoramas.py            # textures, models, item definitions
    python3 -I coreshaders/tools/panoramas.py --only sky_sea

Every panorama is W x H = 1024 x 512, u = atan(d.z, d.x) / 2pi + 0.5, v = acos(d.y) / pi (v = 0 is straight up).
Row 0 is the sentinel row: texel (0,0) = (61,203,147,3), texel (1,0) = (K,147,203,3), texel (2,0) = (glow rgb, 3), K = kind 1..12 (IMAGES order),
the rest of row 0 repeats row 1. The shader samples rows 1..H-1 only.
Glow sprites (plane frame) are 16 x 16: texel (0,0) = (61,203,147,3), texel (1,0) = (16+K,147,203,3), texel (2,0) = (glow, 3).
Custom images made by the plugin (/cs upload, /cs import) use kinds 33..200 and glow sprites with K = 255.
Models: cube faces north, south, east, west, up, down get tintindex 0..5, plane sides north/south 0/1 (the frame quads share
them). The plugin puts per-face rim flags into the low 2 bits of green and blue of each tint (see Faces.java).
    python3 -I coreshaders/tools/panoramas.py --models   # only models, item definitions and sentinel texels
The shader shows the horizon (v = 0.5) at CS_HORIZON = -0.45 rad below eye level, so the newer panoramas are drawn in
View angles: a = degrees above that shown horizon, and shapes are laid out in world degrees around it.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "pack", "assets")
NS = "coreshaders"
W, H = 1024, 512
SENT = (61, 203, 147, 3)
IMAGES = ["sky_sea", "space", "nether", "aurora", "end", "sunset", "volcano", "hell_end", "heaven", "walk", "rain", "sea_sand"]
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


def cell_dots(xz, seed, density=0.5, size=0.2):
    """2D version of cell_stars for ground planes: xz in cell units, returns brightness and a per-dot hash."""
    i = np.floor(xz).astype(np.int64)
    zero = np.zeros(xz.shape[:-1], np.int64)
    best = np.zeros(xz.shape[:-1])
    col = np.zeros(xz.shape[:-1])
    for a in (-1, 0, 1):
        for b in (-1, 0, 1):
            cx, cz = i[..., 0] + a, i[..., 1] + b
            on = _hash3(cx, cz, zero, seed) < density
            px = cx + 0.2 + 0.6 * _hash3(cx, cz, zero, seed + 1)
            pz = cz + 0.2 + 0.6 * _hash3(cx, cz, zero, seed + 2)
            br = _hash3(cx, cz, zero, seed + 3)
            dist = np.hypot(xz[..., 0] - px, xz[..., 1] - pz)
            v = np.exp(-(dist / (size * (0.6 + 0.4 * br))) ** 4) * on
            upd = v > best
            best = np.where(upd, v, best)
            col = np.where(upd, _hash3(cx, cz, zero, seed + 4), col)
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
    sun_et, sun_az = math.radians(26.26), math.radians(-100.0)
    sun = np.array([math.cos(sun_et) * math.cos(sun_az), math.sin(sun_et), math.cos(sun_et) * math.sin(sun_az)])
    zen = np.array([0.10, 0.36, 0.86])
    mid = np.array([0.30, 0.60, 0.97])
    hor = np.array([0.74, 0.88, 0.98])
    t = up ** 0.45
    sky = mix(hor, mid, smoothstep(0.0, 0.55, t))
    sky = mix(sky, zen, smoothstep(0.45, 1.0, t))
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


HORIZON = -0.45
HALF = math.pi / 2
DEG = 180.0 / math.pi


class View:
    """Texel directions seen through the shader mapping: az (radians, same as u), el = world elevation (radians),
    a = degrees above the shown horizon (world elevation - CS_HORIZON), w = world view direction."""

    def __init__(self, d=None):
        if d is None:
            d, _, _ = directions()
        self.d = d
        self.az = np.arctan2(d[..., 2], d[..., 0])
        et = np.arcsin(np.clip(d[..., 1], -1.0, 1.0))
        self.el = np.where(et >= 0.0, HORIZON + et * (HALF - HORIZON) / HALF, HORIZON + et * (HALF + HORIZON) / HALF)
        self.a = (self.el - HORIZON) * DEG
        self.w = np.stack([np.cos(self.el) * np.cos(self.az), np.sin(self.el), np.cos(self.el) * np.sin(self.az)], -1)

    def sub(self, sl):
        v = View.__new__(View)
        v.d, v.az, v.el, v.a, v.w = self.d[sl], self.az[sl], self.el[sl], self.a[sl], self.w[sl]
        return v

    def local(self, az0, a0):
        return wrap(self.az - az0 / DEG) * DEG * np.cos(self.el), self.a - a0


def wrap(x):
    return (x + math.pi) % (2.0 * math.pi) - math.pi


def world_dir(az_deg, a_deg):
    el = a_deg / DEG + HORIZON
    az = az_deg / DEG
    return np.array([math.cos(el) * math.cos(az), math.sin(el), math.cos(el) * math.sin(az)])


def a_row(a):
    k = HALF / (HALF - HORIZON) if a >= 0 else HALF / (HALF + HORIZON)
    return (0.5 - a / DEG * k / math.pi) * H


def crop(az0, xhalf, a_lo, a_hi):
    el_max = max(abs(a_lo / DEG + HORIZON), abs(a_hi / DEG + HORIZON))
    span = min(179.0, xhalf / max(math.cos(min(el_max, 1.45)), 0.1))
    c0 = max(0, int(math.floor(((az0 - span) / 360.0 + 0.5) * W)) - 1)
    c1 = min(W, int(math.ceil(((az0 + span) / 360.0 + 0.5) * W)) + 1)
    r0 = max(1, int(math.floor(a_row(a_hi))) - 1)
    r1 = min(H, int(math.ceil(a_row(a_lo))) + 1)
    return (slice(r0, r1), slice(c0, c1))


def ramp(x, stops):
    xs = [s[0] for s in stops]
    return np.stack([np.interp(x, xs, [s[1][c] for s in stops]) for c in range(3)], -1)


def cover(sdf, aa=0.28):
    return 1.0 - smoothstep(-aa, aa, sdf)


def paint(img, sl, col, alpha):
    img[sl] = mix(img[sl], col, np.clip(alpha, 0.0, 1.0))


def ground(v, height=1.0, bmin=0.2):
    b = np.maximum(-v.a, bmin) / DEG
    dist = height / np.tan(b)
    return dist * np.cos(v.az), dist * np.sin(v.az), dist


def dome(v, height=1.0, amin=0.5):
    b = np.maximum(v.a, amin) / DEG
    dist = height / np.tan(b)
    return dist * np.cos(v.az), dist * np.sin(v.az), dist


def seg(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    t = np.clip(((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy + 1e-12), 0.0, 1.0)
    return np.hypot(px - ax - t * vx, py - ay - t * vy), t


def stroke(px, py, pts, width):
    """SDF of a polyline with a width profile width(s), s = 0..1 along the line; returns sdf and s."""
    n = len(pts) - 1
    best = np.full(px.shape, 1e9)
    bs = np.zeros(px.shape)
    for i in range(n):
        dd, t = seg(px, py, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
        s = (i + t) / n
        sd = dd - width(s)
        upd = sd < best
        best = np.where(upd, sd, best)
        bs = np.where(upd, s, bs)
    return best, bs


def ring_noise(v, scale, seed, z=0.0, octaves=4):
    return fbm3(np.stack([np.cos(v.az) * scale, np.sin(v.az) * scale, np.full(v.az.shape, z)], -1), octaves, seed=seed)


def sky_sun_glow(v, sun, tight, wide, col_t, col_w):
    cs = np.clip(v.w @ sun, -1.0, 1.0)
    ang = np.arccos(cs) * DEG
    return ang, col_t * np.exp(-ang / tight)[..., None] + col_w * np.exp(-ang / wide)[..., None]


def blob_crown(px, py, cx, cy, r, seed, lobes=7):
    rng = np.random.RandomState(seed)
    sd = np.full(px.shape, 1e9)
    for k in range(lobes):
        ang = rng.uniform(0, 2 * math.pi)
        rr = r * rng.uniform(0.45, 0.7)
        off = r * rng.uniform(0.15, 0.55)
        ox, oy = cx + math.cos(ang) * off, cy + math.sin(ang) * off * 0.8
        sd = np.minimum(sd, np.hypot(px - ox, py - oy) - rr)
    sd = np.minimum(sd, np.hypot(px - cx, py - cy) - r * 0.62)
    return sd


def island(v, az0, a_top, w, depth, seed, bump=0.5, spikes=0.3, floor=None):
    """Floating island: x, y (deg from the anchor), coverage, depth fraction 0 top .. 1 bottom, top and bottom lines."""
    x, y = v.local(az0, a_top)
    xn = np.clip(x / w, -1.2, 1.2)
    n1 = fbm3(np.stack([x * 0.35, np.full(x.shape, seed * 1.7), np.zeros(x.shape)], -1), 4, seed=seed)
    n2 = fbm3(np.stack([x * 1.6, np.full(x.shape, seed * 0.3), np.ones(x.shape)], -1), 3, seed=seed + 1)
    shape = np.clip(1.0 - xn * xn, 0.0, 1.0)
    top = bump * shape ** 0.5 * (0.6 + 0.8 * n1) + 0.25 * (n2 - 0.5)
    sp = vnoise3(np.stack([x * 0.9, np.full(x.shape, seed * 0.7), np.full(x.shape, 2.0)], -1), seed + 2)
    bot = -depth * shape ** 0.75 * (0.75 + 0.5 * n1) - depth * spikes * shape ** 0.5 * smoothstep(0.35, 0.95, sp)
    if floor is not None:
        bot = np.maximum(bot, floor - a_top)
    inside = np.minimum(top - y, y - bot)
    cov = cover(-inside, 0.25) * smoothstep(1.0, 0.97, np.abs(xn))
    frac = np.clip((top - y) / np.maximum(top - bot, 1e-3), 0.0, 1.0)
    return x, y, cov, frac, top, bot


def chorus(px, py, x0, y0, unit, seed, height=5):
    rng = np.random.RandomState(seed)
    segs = []
    tips = []
    stack = [(x0, y0, 0, height)]
    while stack:
        x, y, depth, h = stack.pop()
        h = max(1, h)
        y1 = y + h * unit
        segs.append((x, y, x, y1))
        if depth < 2 and rng.rand() < 0.95:
            nb = rng.randint(1, 3)
            for b in range(nb):
                side = -1 if (b == 0) == (rng.rand() < 0.5) else 1
                ln = rng.randint(1, 3) * unit
                hy = y + rng.randint(max(1, h - 2), h + 1) * unit
                segs.append((x, hy, x + side * ln, hy))
                stack.append((x + side * ln, hy, depth + 1, rng.randint(1, max(2, h - 1))))
        else:
            tips.append((x, y1))
        if depth < 2 and rng.rand() < 0.5:
            tips.append((x, y1))
    sd = np.full(px.shape, 1e9)
    half = unit * 0.32
    for (ax, ay, bx, by) in segs:
        cx, cy = (ax + bx) / 2, (ay + by) / 2
        hx, hy = abs(bx - ax) / 2 + half, abs(by - ay) / 2 + half
        qx, qy = np.abs(px - cx) - hx, np.abs(py - cy) - hy
        sd = np.minimum(sd, np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0))
    fl = np.full(px.shape, 1e9)
    for (tx, ty) in tips:
        qx, qy = np.abs(px - tx) - unit * 0.55, np.abs(py - ty - unit * 0.2) - unit * 0.55
        fl = np.minimum(fl, np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0))
    return sd, fl


def palm(px, py, base_x, base_y, s, seed, lean=0.35, height=4.5):
    rng = np.random.RandomState(seed)
    n = 12
    trunk = [(base_x + s * lean * height * (i / n) ** 2, base_y + s * height * (i / n)) for i in range(n + 1)]
    tw = lambda t: s * (0.16 - 0.06 * t)
    tsd, ts = stroke(px, py, trunk, tw)
    cx, cy = trunk[-1]
    fsd = np.full(px.shape, 1e9)
    fs = np.zeros(px.shape)
    count = 11
    for k in range(count):
        th = math.pi * (k + 0.5) / count + rng.uniform(-0.12, 0.12)
        th = th if k % 2 == 0 else th + 0.05
        ln = s * rng.uniform(1.8, 2.5)
        droop = rng.uniform(0.7, 1.1) * (0.6 + 0.6 * abs(math.cos(th)))
        pts = [(cx + math.cos(th) * ln * t, cy + math.sin(th) * ln * t * 0.55 - droop * ln * t * t * 0.9) for t in np.linspace(0, 1, 10)]
        width = lambda t, ln=ln: ln * (0.11 * np.sin(math.pi * np.minimum(t, 1.0)) ** 0.7 + 0.01)
        sd, sp = stroke(px, py, pts, width)
        saw = 0.5 + 0.5 * np.sin(sp * 55.0 + k)
        sd = sd + ln * 0.03 * saw * smoothstep(0.1, 0.3, sp)
        upd = sd < fsd
        fsd = np.where(upd, sd, fsd)
        fs = np.where(upd, sp, fs)
    nut = np.full(px.shape, 1e9)
    for k in range(3):
        nut = np.minimum(nut, np.hypot(px - cx - s * (k - 1) * 0.16, py - cy + s * 0.14) - s * 0.11)
    return tsd, ts, fsd, fs, nut


def pano_sunset():
    v = View()
    a = v.a
    sun_az, sun_a = -90.0, 3.2
    sun = world_dir(sun_az, sun_a)
    daz = wrap(v.az - sun_az / DEG) * DEG
    toward = 0.5 + 0.5 * np.cos(daz / DEG)
    front = [(0, (1.00, 0.50, 0.14)), (4, (1.00, 0.62, 0.24)), (12, (0.97, 0.62, 0.36)), (24, (0.82, 0.48, 0.44)),
             (40, (0.52, 0.32, 0.46)), (62, (0.22, 0.17, 0.36)), (116, (0.07, 0.07, 0.20))]
    back = [(0, (0.78, 0.46, 0.44)), (6, (0.66, 0.44, 0.52)), (18, (0.46, 0.38, 0.58)), (36, (0.27, 0.25, 0.47)),
            (62, (0.13, 0.13, 0.32)), (116, (0.07, 0.07, 0.20))]

    def skycol(aa):
        return mix(ramp(aa, back), ramp(aa, front), toward ** 1.6)

    sky = skycol(np.maximum(a, 0.0))
    ang, glow = sky_sun_glow(v, sun, 7.0, 28.0, np.array([1.0, 0.62, 0.22]) * 0.55, np.array([1.0, 0.55, 0.25]) * 0.3)
    sky = sky + glow
    st, _ = cell_stars(v.d, 80, 211, 0.4, 0.13)
    sky = sky + np.array([0.9, 0.85, 1.0]) * (st * 0.7 * smoothstep(45, 85, a) * (1.0 - toward))[..., None]
    rsun = 5.0
    limb = np.clip(ang / rsun, 0.0, 1.0)
    scol = mix(np.array([1.0, 0.97, 0.80]), np.array([1.0, 0.66, 0.26]), limb ** 2.5)
    sky = mix(sky, scol, cover(ang - rsun, 0.25))
    q = np.stack([np.cos(v.az) * 3.2, np.sin(v.az) * 3.2, a * 0.55], -1)
    warp = fbm3(q * 0.7 + 3.0, 3, seed=213)
    n = fbm3(q + np.stack([warp * 1.5, warp * 0.8, np.zeros_like(warp)], -1), 5, seed=217)
    streak = fbm3(np.stack([np.cos(v.az) * 9.0, np.sin(v.az) * 9.0, a * 1.6], -1), 3, seed=218)
    n = n * 0.75 + streak * 0.35
    band = smoothstep(1.0, 4.0, a) * (1.0 - smoothstep(18.0, 32.0, a))
    cl = smoothstep(0.55, 0.68, n) * band
    thick = smoothstep(0.62, 0.8, n)
    lit = np.exp(-ang / 14.0)
    ccol = mix(np.array([0.30, 0.17, 0.26]), np.array([0.95, 0.46, 0.30]), toward ** 2)
    ccol = mix(ccol, np.array([1.0, 0.80, 0.50]), lit * 0.9)
    ccol = ccol * (1.0 - 0.35 * thick)[..., None]
    rim = np.exp(-ang / 6.0) * (1.0 - thick)
    ccol = ccol + np.array([1.0, 0.75, 0.4]) * (rim * 0.6)[..., None]
    sky = mix(sky, ccol, cl * 0.9)
    hp = np.stack(dome(v, 1.0, 0.6)[:2], -1)
    hq = p3(np.stack([hp[..., 0] * 0.9, hp[..., 1] * 2.4], -1) * 1.3)
    hw = fbm3(hq * 0.6 + 7.0, 3, seed=241)
    hn = fbm3(hq + np.stack([hw, hw * 0.5, -hw], -1) * 0.8, 5, seed=243)
    hr = 1.0 - np.abs(2.0 * fbm3(hq * 2.6 + 2.0, 4, seed=245) - 1.0)
    hd = hn * 0.8 + hr * 0.3
    hband = smoothstep(9.0, 16.0, a) * (1.0 - smoothstep(48.0, 64.0, a))
    hc = smoothstep(0.56, 0.7, hd) * hband
    hl = np.exp(-ang / 45.0)
    hcol = mix(np.array([0.88, 0.50, 0.46]), np.array([1.0, 0.72, 0.42]), hl)
    hcol = mix(hcol, np.array([0.50, 0.30, 0.44]), smoothstep(22.0, 50.0, a)[..., None] * (1.0 - hl[..., None]) * 0.7)
    hcol = hcol * (0.8 + 0.35 * smoothstep(0.6, 0.8, hd))[..., None]
    sky = mix(sky, hcol, hc * 0.85)
    hx, _ = v.local(sun_az - 38.0, 0.0)
    head = 2.3 * np.exp(-((hx + 4.0) / 9.0) ** 2) + 1.1 * np.exp(-((hx - 9.0) / 6.0) ** 2)
    head = head * (0.8 + 0.4 * ring_noise(v, 40.0, 219))
    land = cover(a - head, 0.2) * smoothstep(-0.3, 0.0, a)
    sky = mix(sky, mix(np.array([0.16, 0.07, 0.12]), sky, 0.35), land)
    b = np.maximum(-a, 0.0)
    gx, gz, dist = ground(v)
    refl = skycol(b * 1.15)
    fres = 0.18 + 0.7 * np.exp(-b / 7.0)
    wq = p3(np.stack([gx * 0.9, gz * 2.6], -1))
    waves = fbm3(wq, 5, seed=223)
    wfade = smoothstep(0.0, 6.0, b) * (1.0 - smoothstep(25.0, 50.0, b))
    waves = 0.5 + (waves - 0.5) * (0.25 + 0.75 * wfade)
    sea = mix(np.array([0.10, 0.05, 0.08]), np.array([0.025, 0.02, 0.045]), smoothstep(0.0, 30.0, b))
    sea = sea + refl * (fres * (0.65 + 0.7 * waves))[..., None]
    wpath = 2.6 + 0.2 * b
    path = np.exp(-(daz / wpath) ** 2)
    sx = daz / (0.3 + 0.05 * b)
    sy = np.log(b + 0.4) * 30.0
    dash = fbm3(np.stack([sx, sy, np.zeros_like(sx)], -1), 3, seed=227)
    dash2 = fbm3(np.stack([sx * 2.1, sy * 1.7, np.ones_like(sx)], -1), 2, seed=229)
    spark = smoothstep(0.52, 0.72, dash * 0.7 + dash2 * 0.4)
    gl = path * (spark * 1.5 + 0.2) * np.exp(-b / 30.0)
    gcol = mix(np.array([1.0, 0.52, 0.16]), np.array([1.0, 0.92, 0.65]), np.exp(-np.abs(daz) / (1.0 + 0.1 * b)) * spark)
    sea = sea + gcol * gl[..., None]
    sea = sea + np.array([1.0, 0.5, 0.2]) * (np.exp(-ang / 10.0) * 0.25)[..., None] * (b < 3.0)[..., None]
    img = np.where((a >= 0.0)[..., None], sky, sea)
    line = np.exp(-(a / 0.35) ** 2) * (0.25 + 0.75 * np.exp(-np.abs(daz) / 30.0)) * (1.0 - land)
    img = img + np.array([1.0, 0.62, 0.3]) * (line * 0.45)[..., None]
    return img


def pano_volcano():
    v = View()
    a = v.a
    vaz = -90.0
    top_a = 9.0
    x, _ = v.local(vaz, 0.0)
    sky = ramp(np.maximum(a, 0.0), [(0, (0.66, 0.22, 0.07)), (4, (0.50, 0.15, 0.06)), (12, (0.30, 0.09, 0.06)),
                                    (30, (0.13, 0.055, 0.05)), (116, (0.05, 0.03, 0.035))])
    dc = np.hypot(x, a - top_a)
    sky = sky + np.array([1.0, 0.32, 0.06]) * (0.4 * np.exp(-dc / 16.0) + 0.14 * np.exp(-dc / 50.0))[..., None]
    q = v.w * 2.2
    warp = fbm3(q * 0.7 + 4.0, 3, seed=301)
    n = fbm3(q + warp[..., None] * 0.9, 5, seed=303)
    det = fbm3(q * 3.0 + 1.0, 4, seed=305)
    ash = smoothstep(0.38, 0.62, n) * smoothstep(2.0, 16.0, a)
    under = np.clip(0.5 * np.exp(-dc / 24.0) + 0.3 * np.exp(-np.maximum(a, 0) / 10.0), 0, 1)
    acol = mix(np.array([0.10, 0.07, 0.065]), np.array([0.22, 0.17, 0.15]), smoothstep(0.4, 0.7, det))
    acol = acol + np.array([0.75, 0.26, 0.07]) * (under * (1.1 - det))[..., None]
    sky = mix(sky, acol, ash * 0.9)
    sl = crop(vaz - 10.0, 75.0, top_a - 2.0, 85.0)
    sv = v.sub(sl)
    px, _ = sv.local(vaz, 0.0)
    pa = sv.a
    psky = sky[sl]
    rng = np.random.RandomState(307)
    puffs = []
    hc = top_a + 1.4
    while hc < 52.0:
        ya = hc - top_a
        r = 2.4 + 0.33 * ya + 0.012 * ya * ya
        xc = -ya * 0.3 - 1.6 * math.sin(ya * 0.12) + rng.uniform(-0.5, 0.5) * r
        puffs.append((xc, hc, r * rng.uniform(0.85, 1.1)))
        for side in (-1, 1):
            if rng.rand() < 0.85:
                puffs.append((xc + side * r * rng.uniform(0.5, 1.05), hc + r * rng.uniform(-0.5, 0.35),
                              r * rng.uniform(0.45, 0.8)))
        hc += r * rng.uniform(0.4, 0.55)
    bill = fbm3(np.stack([px * 0.16, pa * 0.16, np.zeros_like(pa)], -1), 4, seed=309)
    fine = fbm3(np.stack([px * 0.5, pa * 0.5, np.ones_like(pa)], -1), 3, seed=310)
    for k, (xc, hc, r) in enumerate(sorted(puffs, key=lambda p: -p[1])):
        dx, dy = (px - xc) / r, (pa - hc) / (r * 0.9)
        dd = np.hypot(dx, dy)
        edge = dd - 1.0 - 0.28 * (bill - 0.5) - 0.12 * (fine - 0.5)
        cov = 1.0 - smoothstep(-0.18, 0.06, edge)
        cov = cov * (1.0 - smoothstep(44.0, 58.0, hc))
        nz = np.sqrt(np.clip(1.0 - dd * dd, 0.0, 1.0))
        shade = np.clip(0.3 + 0.4 * dy - 0.2 * dx + 0.4 * nz + 0.5 * (bill - 0.5), 0.0, 1.0)
        ya = max(hc - top_a, 0.0)
        pc = mix(np.array([0.08, 0.055, 0.05]), np.array([0.42, 0.36, 0.33]), shade ** 1.3)
        under = np.clip(0.6 - 0.6 * dy, 0.0, 1.0) * (0.3 + 0.7 * math.exp(-ya / 18.0))
        pc = pc + np.array([1.0, 0.36, 0.07]) * (under * (0.6 + 0.4 * (1.0 - fine)))[..., None]
        pc = pc + np.array([0.45, 0.13, 0.03]) * (math.exp(-ya / 26.0) * (1.0 - shade) * 0.35)[..., None]
        psky = mix(psky, pc, cov * (0.97 - 0.12 * min(ya / 40.0, 1.0)))
    bolt = np.full(pa.shape, 1e9)
    for seed, x0, a0, a1, lean in ((361, -3.0, 30.0, 17.0, 0.25), (363, -10.5, 36.0, 26.0, -0.35)):
        r2 = np.random.RandomState(seed)
        pts = []
        n = 9
        for i in range(n + 1):
            t = i / n
            pts.append((x0 + lean * (a0 - a1) * t + (r2.uniform(-1.0, 1.0) * 1.1 if 0 < i < n else 0.0), a0 + (a1 - a0) * t))
        sd, _ = stroke(px, pa, pts, lambda t: 0.08 + 0.1 * (1.0 - t))
        bolt = np.minimum(bolt, sd)
        br = pts[4]
        sd2, _ = stroke(px, pa, [br, (br[0] + 2.2 * (1 if lean > 0 else -1), br[1] - 2.5), (br[0] + 3.0 * (1 if lean > 0 else -1), br[1] - 4.5)], lambda t: 0.06)
        bolt = np.minimum(bolt, sd2)
    psky = psky + np.array([0.85, 0.80, 1.0]) * (np.exp(-np.maximum(bolt, 0.0) / 0.12) * 1.2 + np.exp(-np.maximum(bolt, 0.0) / 2.2) * 0.22)[..., None]
    sky[sl] = psky
    fy = a - (top_a - 0.6)
    fw = 0.9 + np.maximum(fy, 0.0) * 0.5
    jets = fbm3(np.stack([x / (np.maximum(fy, 0.0) + 1.5) * 5.0, a * 0.08, np.zeros_like(a)], -1), 3, seed=311)
    fount = (1.0 - smoothstep(0.0, fw, np.abs(x))) * smoothstep(-0.5, 0.5, fy) * (1.0 - smoothstep(2.5, 7.0, fy))
    fount = fount * smoothstep(0.32, 0.65, jets)
    sky = sky + np.array([1.0, 0.62, 0.15]) * (fount * 1.8)[..., None]
    for scale, dens, size, gain, seed in ((55, 0.45, 0.2, 1.3, 313), (120, 0.4, 0.17, 0.9, 317)):
        st, sc = cell_stars(v.d, scale, seed, dens, size)
        wgt = 0.25 + 1.8 * np.exp(-dc / 12.0)
        ecol = mix(np.array([1.0, 0.35, 0.05]), np.array([1.0, 0.75, 0.3]), sc)
        sky = sky + ecol * (st * gain * wgt * smoothstep(0.0, 2.0, a))[..., None]
    xn = np.abs(x) / 31.0
    prof = (top_a + 1.6) * np.clip(1.0 - xn, 0.0, 1.0) ** 1.8
    prof = np.minimum(prof, top_a + 0.25 * np.clip(1.0 - np.abs(x) / 4.0, 0, 1))
    prof = prof - 0.7 * np.clip(1.0 - (x / 2.4) ** 2, 0.0, 1.0)
    sil = fbm3(np.stack([x * 0.5, np.zeros_like(x), np.zeros_like(x)], -1), 4, seed=319)
    prof = prof + (sil - 0.5) * 0.9 * smoothstep(0.0, 3.0, prof)
    hz = 0.8 + 2.6 * ring_noise(v, 5.0, 321)
    hz = hz + 0.6 * ring_noise(v, 30.0, 322)
    vaz2 = 95.0
    x2, _ = v.local(vaz2, 0.0)
    prof2 = 6.5 * np.clip(1.0 - np.abs(x2) / 20.0, 0.0, 1.0) ** 1.7
    prof2 = np.minimum(prof2, 5.6) - 0.4 * np.clip(1.0 - (x2 / 2.0) ** 2, 0, 1)
    s2 = (1.0 - smoothstep(0.0, 1.5 + np.maximum(a - 5.0, 0) * 0.5, np.abs(x2 - (a - 5.0) * 0.4))) * smoothstep(4.8, 6.5, a)
    s2 = s2 * smoothstep(0.35, 0.65, fbm3(np.stack([x2 * 0.3, a * 0.25, np.zeros_like(a)], -1), 4, seed=323)) * (1.0 - smoothstep(14, 32, a))
    sky = mix(sky, np.array([0.20, 0.15, 0.14]), s2 * 0.8)
    ridge = np.maximum(np.maximum(prof, hz), prof2)
    mtn = cover(a - ridge, 0.2) * smoothstep(-0.8, -0.2, a)
    gully = ridged3(np.stack([x * 0.7, a * 0.12, np.zeros_like(a)], -1), 4, seed=325)
    rock = mix(np.array([0.05, 0.035, 0.035]), np.array([0.17, 0.10, 0.08]), gully * 0.8)
    rock = rock * (0.85 + 0.25 * smoothstep(-6.0, 6.0, -x))[..., None]
    rim_l = np.exp(-(ridge - a) / 0.6) * (prof >= hz)
    rock = rock + np.array([0.8, 0.25, 0.05]) * (rim_l * 0.4 * np.exp(-np.abs(x) / 16.0))[..., None]
    rock = rock + np.array([0.6, 0.18, 0.04]) * (np.exp(-np.maximum(a, 0.0) / 2.0) * 0.25)[..., None]
    lava = np.zeros(a.shape)
    halo = np.zeros(a.shape)
    for x0, drift, wig, a_end, seed in ((-0.9, -1.5, 0.7, 0.0, 331), (0.8, 1.0, 0.9, 1.5, 333), (0.3, 2.3, 0.6, 3.8, 335),
                                        (-0.5, -2.7, 0.8, 2.6, 337), (0.1, 0.25, 0.6, -0.5, 339)):
        dy = np.clip(top_a - 0.5 - a, 0.0, None)
        wn = fbm3(np.stack([a * 0.4, np.full(a.shape, seed * 0.1), np.zeros_like(a)], -1), 3, seed=seed)
        xc = x0 + drift * dy + wig * np.sin(dy * 0.6 + seed) + (wn - 0.5) * 2.0
        w = 0.28 + 0.07 * dy
        on = smoothstep(a_end - 0.8, a_end + 0.8, a) * (a < prof - 0.05)
        core = (1.0 - smoothstep(0.35, 1.0, np.abs(x - xc) / w)) * on
        lava = np.maximum(lava, core)
        halo = np.maximum(halo, np.exp(-np.abs(x - xc) / (w * 3.5)) * on)
    crust = fbm3(np.stack([x * 1.6, a * 1.6, np.zeros_like(a)], -1), 3, seed=341)
    hot = mix(np.array([0.95, 0.30, 0.04]), np.array([1.0, 0.82, 0.35]), smoothstep(0.45, 0.75, crust))
    rock = rock + np.array([0.9, 0.25, 0.04]) * (halo * 0.4)[..., None]
    rock = mix(rock, hot, lava * (0.6 + 0.4 * smoothstep(0.3, 0.55, crust)))
    cg = np.exp(-np.hypot(x, (a - top_a + 0.5) * 1.6) / 1.5)
    rock = rock + np.array([1.0, 0.6, 0.2]) * (cg * 1.3)[..., None]
    sky = mix(sky, rock, mtn)
    gx, gz, dist = ground(v)
    b = np.maximum(-a, 0.0)
    gq = p3(np.stack([gx, gz], -1) * 0.9)
    cr = ridged3(gq, 5, seed=343)
    far = 1.0 - smoothstep(0.0, 8.0, b)
    cr = 0.5 + (cr - 0.5) * (1.0 - far * 0.85)
    tex = fbm3(gq * 4.0, 3, seed=345)
    gcol = mix(np.array([0.045, 0.03, 0.03]), np.array([0.13, 0.08, 0.06]), tex)
    crack = smoothstep(0.80, 0.93, cr) * (1.0 - far * 0.8)
    gcol = gcol + np.array([0.9, 0.3, 0.05]) * (smoothstep(0.62, 0.85, cr) * 0.12 * (1 - far))[..., None]
    gcol = mix(gcol, np.array([1.0, 0.45, 0.08]), crack * 0.9)
    zf = -gz
    xr = 2.2 + 1.6 * np.sin(zf * 0.11 + 0.8) + 0.9 * np.sin(zf * 0.29 + 2.0)
    rw = 0.75 + 0.25 * fbm3(gq * 0.6 + 9.0, 2, seed=347)
    dr = np.abs(gx - xr) / rw
    flow = fbm3(np.stack([gx * 2.5, gz * 0.8, np.zeros_like(gx)], -1), 4, seed=349)
    river = 1.0 - smoothstep(0.75, 1.0, dr)
    rcol = mix(np.array([0.95, 0.28, 0.03]), np.array([1.0, 0.85, 0.4]), smoothstep(0.45, 0.75, flow))
    rcol = mix(rcol, np.array([0.25, 0.07, 0.03]), smoothstep(0.68, 0.8, flow) * 0.7)
    gcol = gcol + np.array([0.8, 0.22, 0.03]) * (np.exp(-np.maximum(dr - 1.0, 0.0) * 1.2) * 0.4)[..., None]
    gcol = mix(gcol, rcol, river)
    gcol = mix(gcol, np.array([0.55, 0.16, 0.05]), far * 0.55)
    img = np.where((a >= 0.0)[..., None], sky, gcol)
    img = mix(img, rock, mtn * (a < 0.0))
    img = img + np.array([1.0, 0.4, 0.1]) * (np.exp(-(a / 0.6) ** 2) * 0.35 * (1.0 - mtn))[..., None]
    return img


def pano_hell_end():
    v = View()
    a = v.a
    b = np.maximum(-a, 0.0)
    up = ramp(np.maximum(a, 0.0), [(0, (0.74, 0.12, 0.07)), (6, (0.46, 0.06, 0.09)), (16, (0.24, 0.04, 0.13)),
                                   (32, (0.08, 0.03, 0.10)), (60, (0.03, 0.02, 0.06)), (116, (0.015, 0.01, 0.035))])
    n1 = fbm3(v.w * 2.0 + 9.0, 5, seed=401)
    n2 = fbm3(v.w * 4.5 - 3.0, 4, seed=403)
    endw = smoothstep(14.0, 40.0, a)
    up = up + np.array([0.05, 0.20, 0.22]) * (smoothstep(0.45, 0.8, n1) ** 1.3 * endw)[..., None]
    up = up + np.array([0.20, 0.08, 0.26]) * (smoothstep(0.5, 0.85, n2) * endw * 0.8)[..., None]
    for layer, (scale, dens, size, gain) in enumerate(((20, 0.5, 0.11, 2.0), (45, 0.6, 0.12, 1.5), (100, 0.55, 0.13, 1.1))):
        st, sc = cell_stars(v.d, scale, 405 + layer * 7, dens, size)
        idx = np.clip((sc * 16).astype(int), 0, 15)
        col = END_COLORS[idx] * 4.5 + 0.06
        up = up + col * (st * gain * smoothstep(10.0, 30.0, a))[..., None]
    lo = ramp(b, [(0, (0.74, 0.12, 0.07)), (5, (0.48, 0.05, 0.05)), (18, (0.26, 0.02, 0.04)), (64, (0.08, 0.0, 0.02))])
    fog = fbm3(v.w * 3.0 + 2.0, 5, seed=411)
    wisp = ridged3(v.w * 2.2 + 5.0, 4, seed=413)
    lo = lo * (0.6 + 0.8 * fog[..., None])
    lo = lo + np.array([1.0, 0.35, 0.08]) * (smoothstep(0.6, 0.9, wisp) * 0.25 * (1.0 - smoothstep(10, 50, b)))[..., None]
    img = np.where((a >= 0.0)[..., None], up, lo)
    img = img + np.array([1.0, 0.3, 0.1]) * (np.exp(-np.abs(a) / 2.0) * 0.2)[..., None]
    es, ec = cell_stars(v.d, 60, 415, 0.35, 0.16)
    img = img + mix(np.array([1.0, 0.3, 0.05]), np.array([1.0, 0.7, 0.3]), ec) * (es * 1.3 * (1.0 - smoothstep(5, 30, a)))[..., None]
    nether = ((-90.0, 1.5, 15.0, 15.0, 1, True), (-50.0, -15.0, 7.0, 9.0, 2, False), (-128.0, -6.0, 10.0, 11.0, 3, True),
              (-16.0, 4.0, 7.0, 8.0, 4, True), (22.0, -22.0, 12.0, 12.0, 5, False), (62.0, -1.0, 11.0, 13.0, 6, True),
              (108.0, -16.0, 8.0, 10.0, 7, False), (150.0, 5.0, 11.0, 12.0, 8, True), (-155.0, -30.0, 12.0, 11.0, 9, False),
              (-96.0, -38.0, 8.0, 8.0, 10, False))
    ends = ((-100.0, 29.0, 8.5, 6.0, 21), (40.0, 24.0, 9.0, 6.0, 22), (162.0, 34.0, 9.0, 6.5, 23), (-140.0, 20.0, 7.0, 5.0, 24))

    def draw_chorus(sl, x, y, top, w, seed, unit, count, base_dark):
        rng = np.random.RandomState(seed + 500)
        row = x.shape[0] // 2
        for c in range(count):
            cx = (-0.6 + 1.2 * (c + rng.uniform(0.2, 0.8)) / count) * w
            ty = float(np.interp(cx, x[row], top[row]))
            sd, fl = chorus(x, y, cx, ty - unit * 0.4, unit, seed * 13 + c, height=rng.randint(3, 6))
            paint(img, sl, base_dark, cover(sd, 0.18))
            img[sl] = img[sl] + np.array([0.6, 0.35, 0.7]) * (np.exp(-np.maximum(fl, 0) / (unit * 1.5)) * 0.25)[..., None]
            paint(img, sl, np.array([0.86, 0.70, 0.96]), cover(fl, 0.18))

    for az0, at, w, dep, seed, has_ch in nether:
        sl = crop(az0, w * 1.2, at - dep * 1.6 - 20.0, at + 16.0)
        sv = v.sub(sl)
        x, y, cov, frac, top, bot = island(sv, az0, at, w, dep, 430 + seed, bump=0.9, spikes=0.45)
        tex = fbm3(sv.w * 60.0, 3, seed=440 + seed)
        spots = smoothstep(0.62, 0.8, fbm3(sv.w * 25.0, 3, seed=441 + seed))
        col = mix(np.array([0.52, 0.13, 0.11]), np.array([0.20, 0.035, 0.045]), frac[..., None] ** 0.8)
        col = col * (0.65 + 0.7 * tex)[..., None]
        col = mix(col, np.array([0.62, 0.18, 0.13]), spots * 0.4)
        nyl = 1.0 - smoothstep(0.0, 0.7, top - y)
        col = mix(col, np.array([0.66, 0.06, 0.10]), nyl * 0.9)
        col = col + np.array([0.7, 0.14, 0.03]) * (frac ** 2 * 0.4)[..., None]
        gs, _ = cell_stars(sv.w, 45, 450 + seed, 0.5, 0.25)
        col = col + np.array([1.0, 0.85, 0.4]) * (gs * smoothstep(0.4, 0.8, frac) * 1.5)[..., None]
        paint(img, sl, col, cov)
        rng = np.random.RandomState(seed + 77)
        if seed % 3 != 1:
            fx = rng.uniform(-0.35, 0.35) * w
            row = x.shape[0] // 2
            fb = float(np.interp(fx, x[row], bot[row])) + at
            ln = 16.0
            alive = (sv.a < fb + 0.3) * np.exp(-np.maximum(fb - sv.a, 0.0) / ln) * (1.0 - smoothstep(ln, ln * 1.6, fb - sv.a))
            xs = wrap(sv.az - az0 / DEG) * DEG * math.cos(fb / DEG + HORIZON)
            fall = (1.0 - smoothstep(0.15, 0.45, np.abs(xs - fx))) * alive
            fl = fbm3(np.stack([xs * 3.0, sv.a * 0.4, np.zeros_like(x)], -1), 3, seed=seed + 460)
            fcol = mix(np.array([1.0, 0.38, 0.05]), np.array([1.0, 0.8, 0.3]), fl)
            paint(img, sl, fcol, fall * (1.0 - cov))
            img[sl] = img[sl] + np.array([1.0, 0.35, 0.05]) * (np.exp(-np.abs(xs - fx) / 1.2) * alive * 0.25 * (1.0 - cov))[..., None]
        if has_ch:
            draw_chorus(sl, x, y, top, w, seed, 0.85 + w * 0.04, 3 + int(w > 10), np.array([0.17, 0.06, 0.16]))
    for az0, at, w, dep, seed in ends:
        sl = crop(az0, w * 1.2, at - dep * 1.6, at + 14.0)
        sv = v.sub(sl)
        x, y, cov, frac, top, bot = island(sv, az0, at, w, dep, 470 + seed, bump=0.6, spikes=0.35)
        tex = fbm3(sv.w * 70.0, 3, seed=480 + seed)
        col = mix(np.array([0.88, 0.87, 0.64]), np.array([0.30, 0.27, 0.24]), frac[..., None] ** 0.6)
        col = col * (0.75 + 0.45 * tex)[..., None]
        col = mix(col, np.array([0.50, 0.40, 0.55]), (1.0 - smoothstep(0.0, 0.5, top - y)) * 0.3)
        paint(img, sl, col, cov)
        draw_chorus(sl, x, y, top, w, seed, 0.75 + w * 0.04, 4, np.array([0.32, 0.16, 0.36]))
    return img


def cumulus_ring(v, img, seed, count, a_base, r_lo, r_hi, sun, lit_col, shade_col, haze_col, haze):
    rng = np.random.RandomState(seed)
    sl = crop(0.0, 179.0, a_base - 1.0, a_base + r_hi * 2.4)
    sv = v.sub(sl)
    cov = np.zeros(sv.a.shape)
    ny = np.zeros(sv.a.shape)
    nx = np.zeros(sv.a.shape)
    for k in range(count):
        az0 = rng.uniform(-180, 180)
        r = rng.uniform(r_lo, r_hi)
        cy = a_base + rng.uniform(-0.3, 0.7) * r
        x, y = sv.local(az0, cy)
        dd = np.hypot(x, y * 1.15)
        inside = cover(dd - r, 0.3)
        upd = inside > cov
        cov = np.maximum(cov, inside)
        ny = np.where(upd, y / r, ny)
        nx = np.where(upd, x / r, nx)
    cov = cov * (sv.a > a_base - 0.5)
    puff = fbm3(sv.w * 30.0, 3, seed=seed + 1)
    lit = np.clip(0.55 + 0.45 * ny + 0.12 * nx * sun + 0.25 * (puff - 0.5), 0.0, 1.0)
    col = mix(shade_col, lit_col, lit)
    col = mix(col, haze_col, haze * (1.0 - smoothstep(0.0, 6.0, sv.a - a_base))[..., None] ** 1.0)
    paint(img, sl, col, cov)


def pano_heaven():
    v = View()
    a = v.a
    sun_az, sun_a = -90.0, 33.0
    sun = world_dir(sun_az, sun_a)
    sky = ramp(np.maximum(a, 0.0), [(0, (0.97, 0.92, 0.82)), (8, (0.90, 0.90, 0.88)), (24, (0.74, 0.84, 0.96)),
                                    (60, (0.56, 0.72, 0.95)), (116, (0.45, 0.63, 0.93))])
    ang, glow = sky_sun_glow(v, sun, 4.0, 18.0, np.array([1.0, 0.95, 0.75]) * 0.5, np.array([1.0, 0.93, 0.75]) * 0.16)
    sx, sy = v.local(sun_az, sun_a)
    phi = np.arctan2(sy, sx)
    rays = fbm3(np.stack([np.cos(phi) * 9.0, np.sin(phi) * 9.0, np.zeros_like(phi)], -1), 4, seed=501)
    rays = smoothstep(0.42, 0.72, rays) * np.exp(-ang / 55.0) * (0.55 - 0.45 * np.sin(phi)) * smoothstep(2.5, 8.0, ang)
    rays = rays * smoothstep(-2.0, 1.0, a)
    sky = sky + glow
    sky = mix(sky, np.array([1.0, 1.0, 0.96]), cover(ang - 2.6, 0.3))
    gx, gz, dist = ground(v, 1.0)
    b = np.maximum(-a, 0.0)
    q = p3(np.stack([gx, gz], -1) * 0.55)
    warp = fbm3(q * 0.5 + 3.0, 3, seed=503)
    q2 = q + np.stack([warp, warp * 0.3, -warp], -1) * 0.9
    den = fbm3(q2, 5, seed=505)
    puff = 1.0 - np.abs(2.0 * fbm3(q2 * 2.2, 4, seed=507) - 1.0)
    h = den * 0.75 + puff * 0.35
    sdir = np.array([sun[0], sun[2]]) / math.hypot(sun[0], sun[2])
    q3 = p3(np.stack([gx, gz], -1) * 0.55 + sdir * 0.08) + np.stack([warp, warp * 0.3, -warp], -1) * 0.9
    h2 = fbm3(q3, 5, seed=505) * 0.75 + (1.0 - np.abs(2.0 * fbm3(q3 * 2.2, 4, seed=507) - 1.0)) * 0.35
    lit = np.clip(0.6 + (h2 - h) * 10.0 + (h - 0.55) * 1.8, 0.0, 1.0)
    fade = smoothstep(0.0, 10.0, b)
    lit = 0.75 + (lit - 0.75) * fade
    ccol = mix(np.array([0.56, 0.54, 0.74]), np.array([0.93, 0.89, 0.81]), lit ** 1.3)
    ccol = ccol + np.array([0.06, 0.03, -0.04]) * (lit * (1.0 - smoothstep(0.0, 30.0, b)))[..., None]
    ccol = mix(ccol, np.array([0.94, 0.90, 0.82]), (1.0 - smoothstep(0.0, 5.0, b)) * 0.45)
    img = np.where((a >= 0.0)[..., None], sky, ccol)
    cumulus_ring(v, img, 509, 40, 0.0, 3.0, 9.0, -1.0, np.array([0.99, 0.96, 0.90]), np.array([0.76, 0.74, 0.86]),
                 np.array([0.96, 0.92, 0.84]), 0.35)
    isl = ((-100.0, 14.0, 18.0, 13.0, 1, 2), (-52.0, 6.0, 7.0, 6.0, 2, 1), (-22.0, 21.0, 8.0, 6.0, 3, 0),
           (32.0, 11.0, 11.0, 9.0, 4, 2), (98.0, 16.0, 10.0, 8.0, 5, 1), (155.0, 8.0, 8.0, 7.0, 6, 1),
           (-165.0, 24.0, 6.0, 4.5, 7, 0))
    for az0, at, w, dep, seed, falls in isl:
        sl = crop(az0, w * 1.3, -3.0, at + w * 1.0 + 3.0)
        sv = v.sub(sl)
        x, y, cov, frac, top, bot = island(sv, az0, at, w, dep, 520 + seed, bump=0.8, spikes=0.25, floor=-1.5)
        tex = fbm3(sv.w * 55.0, 3, seed=530 + seed)
        col = mix(np.array([0.95, 0.88, 0.72]), np.array([0.58, 0.54, 0.56]), frac[..., None] ** 0.8)
        col = col * (0.82 + 0.3 * tex)[..., None]
        col = col + np.array([0.15, 0.10, 0.0]) * (np.clip(-x / w, 0, 1) * (1.0 - frac))[..., None]
        grass = 1.0 - smoothstep(0.25, 1.0, top - y + 0.5 * (tex - 0.5))
        col = mix(col, mix(np.array([0.30, 0.66, 0.26]), np.array([0.60, 0.90, 0.40]), tex), grass)
        rng = np.random.RandomState(seed + 9)
        row = x.shape[0] // 2
        paint(img, sl, col, cov)
        for k in range(falls):
            fx = (rng.uniform(-0.55, -0.15) if k == 0 else rng.uniform(0.15, 0.55)) * w
            ft = float(np.interp(fx, x[row], top[row])) + at - 0.3
            wf = 0.45 + 0.03 * w + np.maximum(ft - sv.a, 0.0) * 0.04
            streak = fbm3(np.stack([x * 3.0, sv.a * 0.3, np.zeros_like(x)], -1), 3, seed=seed + 540 + k)
            down = np.clip(ft - sv.a, 0.0, None)
            fall_m = (1.0 - smoothstep(0.55, 1.0, np.abs(x - fx) / wf)) * (sv.a < ft) * smoothstep(-1.0, 1.5, sv.a)
            fcol = mix(np.array([0.48, 0.70, 0.95]), np.array([0.92, 0.97, 1.0]), smoothstep(0.4, 0.7, streak))
            img[sl] = mix(img[sl], fcol, fall_m * (0.75 + 0.25 * streak))
            mist = np.exp(-np.hypot((x - fx) * 0.8, sv.a - 0.2) / 2.2) * 0.7 * (down > 0)
            img[sl] = mix(img[sl], np.array([1.0, 1.0, 1.0]), mist)
        rng = np.random.RandomState(seed + 31)
        for t in range(2 + int(w) // 3):
            tx = rng.uniform(-0.7, 0.7) * w
            ty = float(np.interp(tx, x[row], top[row]))
            r = rng.uniform(0.7, 1.2) * (0.55 + w * 0.07)
            trunk = cover(np.maximum(np.abs(x - tx) - r * 0.12, np.abs(y - ty - r * 0.6) - r * 0.6), 0.15)
            paint(img, sl, np.array([0.45, 0.33, 0.22]), trunk)
            sd = blob_crown(x, y, tx, ty + r * 1.4, r, seed * 7 + t)
            tc = mix(np.array([0.22, 0.50, 0.20]), np.array([0.58, 0.88, 0.42]), np.clip(0.5 + (y - ty - r * 1.4) / (r * 2.0) + 0.3 * (tex - 0.5), 0, 1))
            paint(img, sl, tc, cover(sd, 0.2))
    img = img + np.array([1.0, 0.95, 0.75]) * (rays * 0.22)[..., None]
    return img


def pano_walk():
    v = View()
    a = v.a
    above = smoothstep(-0.3, 0.05, a)
    sky = ramp(np.maximum(a, 0.0), [(0, (0.82, 0.89, 0.94)), (8, (0.70, 0.82, 0.95)), (30, (0.48, 0.67, 0.93)),
                                    (70, (0.34, 0.56, 0.90)), (116, (0.28, 0.50, 0.88))])
    pc = np.stack(dome(v, 1.0, 0.5)[:2], -1)
    q = p3(pc * 3.2)
    warp = fbm3(q * 0.6 + 3.1, 3, seed=601)
    wv = np.stack([warp, warp * 0.3, -warp], -1) * 0.35

    def dens(qq):
        return fbm3(qq * 0.7, 4, seed=603) * 0.8 + (1.0 - np.abs(2.0 * fbm3(qq * 1.6, 4, seed=605) - 1.0)) * 0.25

    den = dens(q + wv)
    den2 = dens(q + np.array([0.08, 0.0, 0.08]) + wv)
    cloud = smoothstep(0.545, 0.63, den) * smoothstep(23.0, 30.0, a)
    lit = np.clip(0.72 + (den - den2) * 8.0, 0.0, 1.0)
    ccol = mix(np.array([0.64, 0.70, 0.83]), np.array([1.0, 1.0, 1.0]), lit)
    sky = mix(sky, ccol, cloud)
    rng = np.random.RandomState(671)
    flock = [(-97.0, 28.0, 2.6)]
    for k in range(5):
        flock.append((-97.0 + rng.uniform(-8.0, 8.0), 28.0 + rng.uniform(-4.0, 7.0), rng.uniform(1.4, 2.2)))
    for k in range(7):
        flock.append((rng.uniform(-180.0, 180.0), rng.uniform(26.0, 40.0), rng.uniform(1.6, 2.4)))
    for bz, ba, bs in flock:
        sl = crop(bz, 4.0 * bs, ba - 3.0 * bs, ba + 3.0 * bs)
        sv = v.sub(sl)
        x, y = sv.local(bz, ba)
        flap = rng.uniform(-0.25, 0.35)
        pts = [(-1.2 * bs, (0.15 + flap) * bs), (-0.6 * bs, (0.45 + flap * 0.5) * bs), (0.0, 0.0),
               (0.6 * bs, (0.45 + flap * 0.5) * bs), (1.2 * bs, (0.15 + flap) * bs)]
        sd, t = stroke(x, y, pts, lambda t: (0.05 + 0.1 * np.sin(math.pi * t)) * bs)
        paint(sky, sl, np.array([0.18, 0.21, 0.27]), cover(sd, 0.08 * bs) * 0.9)
    gx, gz, dist = ground(v, 1.0)
    b = np.maximum(-a, 0.0)
    gq = p3(np.stack([gx, gz], -1))
    patch = fbm3(gq * 0.12, 4, seed=607)
    det = fbm3(gq * 1.5, 3, seed=609)
    fine = fbm3(gq * 7.0, 2, seed=611)
    nearw = 1.0 - smoothstep(4.0, 30.0, dist)
    grass = mix(np.array([0.30, 0.55, 0.18]), np.array([0.56, 0.72, 0.26]), smoothstep(0.4, 0.68, patch))
    grass = mix(grass, np.array([0.22, 0.45, 0.15]), smoothstep(0.55, 0.75, det) * 0.5)
    grass = grass * (0.85 + 0.3 * fine * nearw + 0.15 * (1 - nearw))[..., None]
    for seed, scale, cols in ((613, 1.6, ((1.0, 1.0, 0.95), (1.0, 0.88, 0.25))), (617, 2.3, ((0.95, 0.45, 0.75), (0.65, 0.5, 1.0)))):
        fs, fc = cell_dots(np.stack([gx, gz], -1) * scale * 2.0, seed, 0.45, 0.12)
        fcol = mix(np.array(cols[0]), np.array(cols[1]), (fc > 0.5).astype(float))
        grass = mix(grass, fcol, (fs * (1.0 - smoothstep(4.0, 12.0, dist)) * smoothstep(0.4, 0.55, patch + 0.1))[..., None])
    xc = 1.1 + 1.4 * np.sin(gz * 0.16 + 0.6) + 0.7 * np.sin(gz * 0.061 + 2.0)
    pw = 0.55 + 0.12 * fbm3(gq * 0.8 + 5.0, 2, seed=619)
    edge = np.abs(gx - xc) / pw + 0.25 * (fbm3(gq * 3.0, 2, seed=621) - 0.5)
    path = 1.0 - smoothstep(0.85, 1.0, edge)
    dirt = mix(np.array([0.70, 0.57, 0.38]), np.array([0.52, 0.41, 0.27]), smoothstep(0.4, 0.7, fbm3(gq * 2.5, 3, seed=623)))
    rut = np.exp(-((np.abs(gx - xc) / pw - 0.5) / 0.12) ** 2) * nearw
    dirt = dirt * (1.0 - 0.18 * rut)[..., None]
    peb, _ = cell_dots(np.stack([gx, gz], -1) * 6.0, 627, 0.4, 0.15)
    dirt = dirt + np.array([0.2, 0.2, 0.18]) * (peb * nearw)[..., None]
    gcol = mix(grass, dirt, path)
    trees = ((-128.0, 14.0, 1), (-58.0, 15.0, 2), (-100.0, 30.0, 3), (24.0, 13.0, 4), (68.0, 20.0, 5), (140.0, 14.0, 6),
             (-20.0, 40.0, 7), (105.0, 36.0, 8), (-160.0, 24.0, 9), (-78.0, 60.0, 10))
    shade = np.zeros(a.shape)
    for az0, d0, seed in trees:
        tx, tz = d0 * math.cos(az0 / DEG), d0 * math.sin(az0 / DEG)
        shade = np.maximum(shade, np.exp(-(((gx - tx - 0.9) / 2.4) ** 2 + ((gz - tz - 0.6) / 1.8) ** 2)))
    gcol = gcol * (1.0 - 0.35 * shade)[..., None]
    haze = np.array([0.72, 0.82, 0.86])
    gcol = mix(gcol, haze, (1.0 - smoothstep(0.0, 6.0, b)) * 0.5)
    img = np.where((a >= 0.0)[..., None], sky, gcol)
    h1 = 1.5 + 9.0 * smoothstep(0.32, 0.7, ring_noise(v, 3.0, 631)) ** 1.3 + 0.5 * ring_noise(v, 25.0, 632)
    far = cover(a - h1, 0.2) * above
    fc = mix(np.array([0.50, 0.66, 0.62]), np.array([0.60, 0.74, 0.76]), smoothstep(0.0, 6.0, h1 - a))
    img = mix(img, fc, far)
    h2 = 0.3 + 6.0 * smoothstep(0.32, 0.72, ring_noise(v, 2.5, 633, 2.0)) ** 1.4
    tl = smoothstep(0.5, 0.8, ring_noise(v, 55.0, 635)) * 1.0 + smoothstep(0.55, 0.85, ring_noise(v, 110.0, 637)) * 0.5
    tl = tl * smoothstep(0.42, 0.58, ring_noise(v, 6.0, 639))
    mid = cover(a - h2, 0.2) * above
    trl = cover(a - (h2 + tl), 0.2) * (tl > 0.05) * above
    mc = mix(np.array([0.40, 0.62, 0.34]), np.array([0.30, 0.52, 0.28]), smoothstep(0.0, 4.0, h2 - a))
    mc = mix(mc, haze, 0.2)
    img = mix(img, mix(np.array([0.22, 0.40, 0.24]), haze, 0.2), trl)
    img = mix(img, mc, mid)
    for az0, d0, seed in trees:
        s = math.atan(1.0 / d0) * DEG
        base = -s
        rng = np.random.RandomState(seed + 640)
        r = s * rng.uniform(1.9, 2.4)
        top = min(base + s * rng.uniform(4.6, 5.6), 18.5)
        sl = crop(az0, r * 1.8, base - 1.0, top + 1.0)
        sv = v.sub(sl)
        x, y = sv.local(az0, 0.0)
        cy = top - r * 0.95
        trunk = cover(np.maximum(np.abs(x - 0.03 * (y - base)) - s * 0.22 * (1.2 - 0.4 * (y - base) / (top - base)), np.abs(y - (base + cy) * 0.5) - (cy - base) * 0.5), 0.15)
        bark = mix(np.array([0.28, 0.20, 0.14]), np.array([0.42, 0.32, 0.22]), fbm3(sv.w * 120.0, 2, seed=seed))
        paint(img, sl, bark, trunk)
        sd = blob_crown(x, y, 0.0, cy, r, seed * 5 + 3, lobes=10)
        lf = fbm3(sv.w * (2600.0 / d0), 3, seed=seed + 650)
        sd = sd + (lf - 0.5) * r * 0.3
        light = np.clip(0.45 + 0.45 * (y - cy) / r - 0.2 * x / r + 0.45 * (lf - 0.5), 0.0, 1.0)
        tc = mix(np.array([0.12, 0.28, 0.11]), np.array([0.44, 0.70, 0.27]), light)
        tc = mix(tc, haze, min(0.45, d0 / 90.0))
        paint(img, sl, tc, cover(sd, 0.2))
    img = img + np.array([1.0, 0.98, 0.9]) * (np.exp(-(a / 0.8) ** 2) * 0.05)[..., None]
    return img


def pano_rain():
    v = View()
    a = v.a
    b = np.maximum(-a, 0.0)
    above = smoothstep(-0.3, 0.05, a)
    sky = ramp(np.maximum(a, 0.0), [(0, (0.52, 0.55, 0.58)), (6, (0.42, 0.45, 0.49)), (20, (0.31, 0.33, 0.37)),
                                    (50, (0.23, 0.24, 0.28)), (116, (0.17, 0.18, 0.22))])
    pc = np.stack(dome(v, 1.0, 0.8)[:2], -1)
    q = p3(pc * 0.45)
    warp = fbm3(q * 0.5 + 1.0, 3, seed=701)
    n = fbm3(q + np.stack([warp, warp * 0.4, -warp], -1) * 1.4, 6, seed=703)
    n2 = fbm3(q * 2.2 + 4.0, 5, seed=705)
    hf = smoothstep(0.0, 8.0, a)
    lump = smoothstep(0.3, 0.75, n)
    sky = sky * (0.8 + 0.5 * lump * hf + (1 - hf) * 0.2)[..., None]
    sky = sky * (1.0 - 0.2 * smoothstep(0.5, 0.75, n2) * (0.3 + 0.7 * lump) * hf)[..., None]
    sky = sky + np.array([0.08, 0.08, 0.09]) * (smoothstep(0.62, 0.85, n2) * smoothstep(0.5, 0.8, n) * hf)[..., None]
    cur = fbm3(np.stack([np.cos(v.az) * 28.0, np.sin(v.az) * 28.0, a * 0.05], -1), 4, seed=707)
    curtain = smoothstep(0.45, 0.7, ring_noise(v, 3.0, 709)) * (1.0 - smoothstep(3.0, 22.0, a)) * above
    sky = sky * (1.0 - curtain * (0.15 + 0.2 * cur))[..., None]
    hill = 0.8 + 3.0 * ring_noise(v, 4.0, 711) * smoothstep(0.35, 0.55, ring_noise(v, 1.5, 713))
    land = cover(a - hill, 0.2) * above
    sky = mix(sky, np.array([0.27, 0.30, 0.32]), land * 0.8)
    lh_x, lh_y = v.local(-80.0, 0.0)
    lh_x, lh_y = lh_x / 2.3, lh_y / 2.3
    lh_on = (np.abs(lh_x) < 8)
    tw = 0.75 - lh_y * 0.04
    tower = cover(np.maximum(np.abs(lh_x) - tw, np.abs(lh_y - 4.0) - 4.0), 0.12) * lh_on
    stripe = (np.sin(lh_y * 2.2) > 0.3) * (lh_y < 7.2)
    tcol = mix(np.array([0.30, 0.31, 0.33]), np.array([0.42, 0.24, 0.24]), stripe * 0.8)
    roof = cover(np.maximum(np.abs(lh_x) - (0.9 - (lh_y - 8.0) * 0.8), np.abs(lh_y - 8.5) - 0.5), 0.1) * lh_on
    rock = cover(lh_y - 1.0 * np.exp(-(lh_x / 3.0) ** 2) - 0.2, 0.1) * lh_on * above
    sky = mix(sky, np.array([0.20, 0.22, 0.23]), rock)
    sky = mix(sky, tcol, tower * 0.9)
    sky = mix(sky, np.array([0.22, 0.22, 0.24]), roof * 0.9)
    lamp = np.exp(-np.hypot(lh_x, lh_y - 7.5) / 0.4) + 0.35 * np.exp(-np.hypot(lh_x, lh_y - 7.5) / 3.0)
    beam = np.exp(-np.abs(lh_y - 7.5 - lh_x * 0.03) / (0.4 + np.abs(lh_x) * 0.05)) * np.exp(-np.maximum(lh_x, 0.0) / 30.0) * (lh_x > 0) * 0.3
    gx, gz, dist = ground(v, 1.0)
    gq = p3(np.stack([gx, gz], -1))
    waves = fbm3(p3(np.stack([gx * 0.9, gz * 2.2], -1)), 5, seed=715)
    chop = fbm3(gq * 3.0, 3, seed=717)
    wf = smoothstep(0.0, 6.0, b) * (1.0 - smoothstep(30.0, 55.0, b))
    sea = ramp(b, [(0, (0.44, 0.47, 0.50)), (4, (0.31, 0.35, 0.37)), (14, (0.19, 0.23, 0.25)), (64, (0.11, 0.14, 0.16))])
    sea = sea * (0.85 + (0.5 * (waves - 0.5) + 0.3 * (chop - 0.5)) * (0.3 + 0.7 * wf))[..., None]
    crest = smoothstep(0.62, 0.75, waves) * wf
    sea = sea + np.array([0.05, 0.055, 0.06]) * (crest * (1.0 - smoothstep(12.0, 30.0, b)))[..., None]
    refl = ramp(b, [(0, (0.46, 0.49, 0.52)), (10, (0.32, 0.35, 0.38)), (64, (0.22, 0.24, 0.27))])
    sea = mix(sea, refl, (np.exp(-b / 6.0) * 0.45)[..., None])
    rq = np.stack([gx, gz], -1) * 3.0
    ci = np.floor(rq).astype(np.int64)
    rings = np.zeros(a.shape)
    zero = np.zeros_like(ci[..., 0])
    for ox in (0, 1):
        for oz in (0, 1):
            cx, cz = ci[..., 0] + ox, ci[..., 1] + oz
            on = _hash3(cx, cz, zero, 722) < 0.35
            jx = _hash3(cx, cz, zero, 719)
            jz = _hash3(cx, cz, zero, 720)
            rr = 0.08 + 0.2 * _hash3(cx, cz, zero, 721)
            dd = np.hypot(rq[..., 0] - cx - jx * 0.6 + 0.3, rq[..., 1] - cz - jz * 0.6 + 0.3)
            rings = np.maximum(rings, on * np.exp(-((dd - rr) / 0.035) ** 2) * (1.0 - rr * 2.5))
    sea = sea + np.array([0.55, 0.58, 0.62]) * (rings * 0.14 * (1.0 - smoothstep(2.0, 6.0, dist)))[..., None]
    img = np.where((a >= 0.0)[..., None], sky, sea)
    mistn = fbm3(v.w * 5.0 + 3.0, 4, seed=723)
    mist = np.exp(-np.abs(a) / 3.0) * (0.35 + 0.4 * mistn)
    img = mix(img, np.array([0.50, 0.53, 0.56]), np.clip(mist, 0, 0.8) * (1.0 - 0.6 * np.clip(tower + roof, 0, 1)))
    img = img + np.array([1.0, 0.85, 0.55]) * ((lamp * 0.7 + beam) * above)[..., None]
    return img


def pano_sea_sand():
    v = View()
    a = v.a
    b = np.maximum(-a, 0.0)
    above = smoothstep(-0.3, 0.05, a)
    sky = ramp(np.maximum(a, 0.0), [(0, (0.80, 0.93, 1.0)), (6, (0.62, 0.84, 0.99)), (25, (0.36, 0.66, 0.97)),
                                    (70, (0.18, 0.48, 0.92)), (116, (0.14, 0.42, 0.88))])
    sun = world_dir(90.0, 62.0)
    ang, glow = sky_sun_glow(v, sun, 5.0, 25.0, np.array([1.0, 1.0, 0.9]) * 0.5, np.array([1.0, 0.98, 0.85]) * 0.25)
    sky = sky + glow
    sky = mix(sky, np.array([1.0, 1.0, 0.97]), cover(ang - 2.4, 0.3))
    cumulus_ring(v, sky, 801, 26, 1.5, 0.8, 3.2, 1.0, np.array([1.0, 1.0, 1.0]), np.array([0.74, 0.82, 0.92]),
                 np.array([0.86, 0.94, 1.0]), 0.5)
    pc = np.stack(dome(v, 1.0, 0.5)[:2], -1)
    cq = p3(pc * 0.9)
    cn = fbm3(cq, 5, seed=803)
    cn2 = fbm3(cq + np.array([0.06, 0.0, 0.06]), 5, seed=803)
    clit = np.clip(0.75 + (cn - cn2) * 8.0, 0, 1)
    sky = mix(sky, mix(np.array([0.78, 0.86, 0.95]), np.array([1.0, 1.0, 1.0]), clit), smoothstep(0.62, 0.72, cn) * smoothstep(8.0, 20.0, a) * 0.95)
    gx, gz, dist = ground(v, 1.0)
    gq = p3(np.stack([gx, gz], -1))
    f = -gz
    shore = 2.3 + 0.7 * np.sin(gx * 0.22 + 0.4) + 0.5 * np.sin(gx * 0.09 + 2.0) + 0.25 * fbm3(gq * 0.5, 3, seed=805)
    shore = shore + np.maximum(np.abs(gx) - 12.0, 0.0) * 0.12
    dw = f - shore
    depth = np.clip(dw, 0.0, None)
    wcol = ramp(depth, [(0, (0.55, 0.95, 0.88)), (1.2, (0.30, 0.88, 0.85)), (4, (0.10, 0.74, 0.80)), (12, (0.05, 0.55, 0.74)),
                        (40, (0.04, 0.42, 0.68)), (400, (0.10, 0.48, 0.72))])
    caust = ridged3(gq * 1.6 + np.array([0.0, 3.0, 0.0]), 3, seed=807)
    cw = (1.0 - smoothstep(1.0, 6.0, depth)) * (1.0 - smoothstep(6.0, 18.0, dist))
    wcol = wcol + np.array([0.35, 0.4, 0.35]) * (smoothstep(0.75, 0.95, caust) * cw * 0.6)[..., None]
    waves = fbm3(p3(np.stack([gx * 0.7, gz * 2.0], -1)), 4, seed=809)
    wcol = wcol * (0.92 + 0.25 * (waves - 0.5) * smoothstep(0.0, 6.0, b) + 0.06)[..., None]
    gs, _ = cell_dots(np.stack([gx, gz * 2.5], -1) * 2.0, 811, 0.3, 0.1)
    wcol = wcol + np.array([1.0, 1.0, 0.95]) * (gs * smoothstep(1.0, 4.0, depth) * (1.0 - smoothstep(10.0, 30.0, dist)) * 0.8)[..., None]
    fn = fbm3(gq * 2.5, 3, seed=813)
    foam = (1.0 - smoothstep(0.0, 0.18 + 0.12 * fn, np.abs(dw - 0.05))) * smoothstep(0.3, 0.6, fn + 0.2)
    foam2 = np.exp(-((dw - 0.9 - 0.2 * np.sin(gx * 0.7)) / 0.08) ** 2) * smoothstep(0.45, 0.7, fn) * 0.6
    grain = fbm3(gq * 8.0, 2, seed=815)
    ripple = 0.5 + 0.5 * np.sin(gx * 6.0 + gz * 2.0 + 3.0 * fbm3(gq * 0.7, 2, seed=817))
    near = 1.0 - smoothstep(3.0, 14.0, dist)
    sand = mix(np.array([0.92, 0.86, 0.72]), np.array([1.0, 0.96, 0.86]), grain)
    sand = sand * (0.95 + 0.08 * ripple * near)[..., None]
    sand = mix(sand, np.array([0.80, 0.74, 0.60]), smoothstep(-0.9, 0.0, dw) * 0.55)
    shells, sc = cell_dots(np.stack([gx, gz], -1) * 1.6, 819, 0.12, 0.1)
    sand = mix(sand, mix(np.array([1.0, 0.8, 0.75]), np.array([0.95, 0.95, 1.0]), sc), (np.clip(shells * 2, 0, 1) * near * (dw < -0.3))[..., None])
    gcol = np.where((dw > 0.0)[..., None], wcol, sand)
    gcol = mix(gcol, np.array([1.0, 1.0, 1.0]), np.clip(foam + foam2 * (dw > 0), 0, 1) * (1.0 - smoothstep(10.0, 40.0, dist)))
    hz = np.array([0.70, 0.88, 0.95])
    gcol = mix(gcol, hz, (1.0 - smoothstep(0.0, 4.0, b)) * 0.3)
    img = np.where((a >= 0.0)[..., None], sky, gcol)
    dune = 0.6 + 2.6 * ring_noise(v, 3.0, 821) + 2.0 * smoothstep(0.5, 0.75, ring_noise(v, 40.0, 823))
    landm = cover(a - dune, 0.2) * above * smoothstep(0.0, 0.25, np.sin(v.az))
    jung = mix(np.array([0.22, 0.48, 0.24]), np.array([0.36, 0.62, 0.32]), smoothstep(0.0, 2.0, dune - a))
    img = mix(img, mix(jung, hz, 0.25), landm)
    ix, iy = v.local(-58.0, 0.0)
    ih = 1.4 * np.exp(-(ix / 3.0) ** 2) + 0.5 * np.exp(-((ix + 3.5) / 2.0) ** 2)
    isl = cover(iy - ih, 0.15) * (np.abs(ix) < 9) * above
    img = mix(img, np.array([0.40, 0.62, 0.50]), isl)
    sl = crop(-58.0, 6.0, -0.5, 6.0)
    sv = v.sub(sl)
    x, y = sv.local(-58.0, 0.0)
    for k, px0 in enumerate((-0.6, 0.9)):
        tsd, ts, fsd, fs, nut = palm(x, y, px0, 0.8, 0.55, 830 + k, lean=0.3 * (1 if k else -1), height=4.0)
        paint(img, sl, np.array([0.38, 0.55, 0.48]), cover(np.minimum(tsd, fsd), 0.15))
    palms = ((-35.0, 7.0, 0.45, 841), (-140.0, 9.0, -0.4, 842), (150.0, 6.0, 0.35, 843), (95.0, 11.0, -0.3, 844),
             (-118.0, 5.5, 0.38, 845), (40.0, 16.0, -0.35, 846), (-170.0, 30.0, 0.2, 847), (-72.0, 26.0, -0.3, 848))
    shade = np.zeros(a.shape)
    for az0, d0, lean, seed in palms:
        tx, tz = d0 * math.cos(az0 / DEG), d0 * math.sin(az0 / DEG)
        shade = np.maximum(shade, np.exp(-(((gx - tx - 1.5) / 2.0) ** 2 + ((gz - tz - 1.0) / 1.3) ** 2)) * (dw < 0))
    img = img * (1.0 - 0.18 * shade * (a < 0))[..., None]
    for az0, d0, lean, seed in palms:
        s = math.atan(1.0 / d0) * DEG
        sl = crop(az0, s * 4.5, -s - 1.0, s * 6.5)
        sv = v.sub(sl)
        x, y = sv.local(az0, 0.0)
        tsd, ts, fsd, fs, nut = palm(x, y, 0.0, -s, s, seed, lean=lean, height=4.5)
        ring = 0.5 + 0.5 * np.sin(ts * 70.0)
        tcol = mix(np.array([0.48, 0.36, 0.24]), np.array([0.66, 0.52, 0.36]), ring * 0.6 + 0.2)
        paint(img, sl, tcol, cover(tsd, 0.15))
        fcol = mix(np.array([0.12, 0.42, 0.14]), np.array([0.45, 0.75, 0.25]), np.clip(0.25 + fs * 0.55 + 0.1 * np.sin(fs * 40.0), 0, 1))
        paint(img, sl, fcol, cover(fsd, 0.15))
        paint(img, sl, np.array([0.40, 0.30, 0.15]), cover(nut, 0.12))
    return img


PANORAMAS = {"sky_sea": pano_sky_sea, "space": pano_space, "nether": pano_nether, "aurora": pano_aurora, "end": pano_end,
             "sunset": pano_sunset, "volcano": pano_volcano, "hell_end": pano_hell_end, "heaven": pano_heaven,
             "walk": pano_walk, "rain": pano_rain, "sea_sand": pano_sea_sand}
GLOW = {"sky_sea": (0.80, 0.95, 1.00), "space": (0.70, 0.45, 1.00), "nether": (1.00, 0.45, 0.12),
        "aurora": (0.35, 1.00, 0.60), "end": (0.20, 0.85, 0.80), "sunset": (1.00, 0.62, 0.25),
        "volcano": (1.00, 0.32, 0.08), "hell_end": (0.95, 0.22, 0.45), "heaven": (1.00, 0.92, 0.65),
        "walk": (0.55, 0.95, 0.45), "rain": (0.62, 0.72, 0.86), "sea_sand": (0.35, 0.95, 0.90)}


def to_rgba8(img):
    img = np.clip(img, 0.0, 1.0)
    img = img ** (1.0 / 1.0)
    rgb = np.round(img * 255.0).astype(np.uint8)
    a = np.full(rgb.shape[:2] + (1,), 255, np.uint8)
    return np.concatenate([rgb, a], axis=-1)


def glow8(name):
    return tuple(int(round(c * 255)) for c in GLOW[name])


def with_sentinel(rgba, kind, glow):
    out = rgba.copy()
    out[0] = out[1]
    out[0, 0] = SENT
    out[0, 1] = (kind, SENT[2], SENT[1], 3)
    out[0, 2] = (glow[0], glow[1], glow[2], 3)
    return out


def glow_sprite(name):
    g = np.zeros((16, 16, 4), np.uint8)
    r, gg, b = glow8(name)
    g[..., 0], g[..., 1], g[..., 2] = r, gg, b
    g[..., 3] = 0
    g[0, 0] = SENT
    g[0, 1] = (GLOW_KIND_BASE + KIND[name], SENT[2], SENT[1], 3)
    g[0, 2] = (r, gg, b, 3)
    return g


CUBE_FACES = ("north", "south", "east", "west", "up", "down")
PLANE_PAD = 0.02


def face(tex, tint):
    return {"uv": [0, 0, 16, 16], "texture": "#" + tex, "tintindex": tint}


def cube_model(name):
    return {"textures": {"p": f"{NS}:item/panorama/{name}", "particle": f"{NS}:item/panorama/{name}"},
            "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "shade": False, "light_emission": 15,
                          "faces": {f: face("p", i) for i, f in enumerate(CUBE_FACES)}}],
            "display": {}}


def plane_model(name):
    lo, hi = -PLANE_PAD, 16 + PLANE_PAD
    return {"textures": {"p": f"{NS}:item/panorama/{name}", "g": f"{NS}:item/panorama/{name}_glow",
                         "particle": f"{NS}:item/panorama/{name}"},
            "elements": [{"from": [lo, lo, 8], "to": [hi, hi, 8], "shade": False, "light_emission": 15,
                          "faces": {"north": face("p", 0), "south": face("p", 1)}},
                         {"from": [-3, -3, 8], "to": [19, 19, 8], "shade": False, "light_emission": 15,
                          "faces": {"north": face("g", 0), "south": face("g", 1)}}],
            "display": {}}


def item_def(model, tints):
    return {"model": {"type": "minecraft:model", "model": f"{NS}:item/{model}",
                      "tints": [{"type": "minecraft:custom_model_data", "index": i, "default": -1} for i in range(tints)]}}


def patch_sentinels():
    tdir = os.path.join(ASSETS, NS, "textures", "item", "panorama")
    for name in IMAGES:
        glow = glow8(name)
        pano = np.array(Image.open(os.path.join(tdir, name + ".png")).convert("RGBA"))
        pano[0, 2] = (glow[0], glow[1], glow[2], 3)
        Image.fromarray(pano, "RGBA").save(os.path.join(tdir, name + ".png"), optimize=True)
        Image.fromarray(glow_sprite(name), "RGBA").save(os.path.join(tdir, name + "_glow.png"))
        print("sentinels", name)


def write_models():
    for name in IMAGES:
        dump(os.path.join(ASSETS, NS, "models", "item", f"cube_{name}.json"), cube_model(name))
        dump(os.path.join(ASSETS, NS, "models", "item", f"plane_{name}.json"), plane_model(name))
        dump(os.path.join(ASSETS, NS, "items", f"cube_{name}.json"), item_def(f"cube_{name}", 6))
        dump(os.path.join(ASSETS, NS, "items", f"plane_{name}.json"), item_def(f"plane_{name}", 2))


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
        rgba = with_sentinel(to_rgba8(img), KIND[name], glow8(name))
        Image.fromarray(rgba, "RGBA").save(os.path.join(tdir, name + ".png"), optimize=True)
        Image.fromarray(glow_sprite(name), "RGBA").save(os.path.join(tdir, name + "_glow.png"))
        out[name] = rgba
        print("panorama", name, rgba.shape)
    write_models()
    return out


if __name__ == "__main__":
    only = None
    if "--models" in sys.argv:
        patch_sentinels()
        write_models()
        sys.exit(0)
    if "--only" in sys.argv:
        only = sys.argv[sys.argv.index("--only") + 1].split(",")
    build(only)
