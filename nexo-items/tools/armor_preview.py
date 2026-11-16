#!/usr/bin/env python3
"""Превью кастомной брони на модели игрока (программный рендер, не скриншот из игры).

    python3 nexo-items/tools/armor_preview.py <steve.png>     # → nexo-items/preview/armor/

Модель — ванильный игрок (голова, тело, руки 4 px, ноги), броня — те же кубы, раздутые на 1.0 (layer_1) и 0.5
(layer_2), левые конечности — зеркально, как в игре. Свечение считается той же формулой, что в
minecraft/shaders/core/entity.fsh (пиксели с альфой 251: без освещения, волна яркости по строкам текстуры).
"""
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(HERE, "..", "pack", "assets", "azerus", "textures", "item", "armor")
OUT = os.path.join(HERE, "..", "preview", "armor")
SETS = ["guren", "tsukikori", "morikashi"]
GLOW_ALPHA = 251


def glow_factor(ticks, row):
    """Как в entity.fsh: волна бежит вниз по строкам текстуры, период ~2 с."""
    wave = 0.5 + 0.5 * math.sin(ticks * 0.16 - row * 0.55)
    return 0.80 + 0.55 * wave


def box(x0, y0, z0, x1, y1, z1, u, v, w, h, d, tex, inflate=0.0, mirror=False):
    x0 -= inflate; y0 -= inflate; z0 -= inflate
    x1 += inflate; y1 += inflate; z1 += inflate
    rect = {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h),
    }
    # Углы: (точка при u=0,v=0), (направление по u), (направление по v) — для каждой грани.
    faces = {
        "front": ((x0, y1, z1), (x1 - x0, 0, 0), (0, y0 - y1, 0), (0, 0, 1)),
        "back": ((x1, y1, z0), (x0 - x1, 0, 0), (0, y0 - y1, 0), (0, 0, -1)),
        "right": ((x0, y1, z0), (0, 0, z1 - z0), (0, y0 - y1, 0), (-1, 0, 0)),
        "left": ((x1, y1, z1), (0, 0, z0 - z1), (0, y0 - y1, 0), (1, 0, 0)),
        "top": ((x0, y1, z0), (x1 - x0, 0, 0), (0, 0, z1 - z0), (0, 1, 0)),
        "bottom": ((x0, y0, z1), (x1 - x0, 0, 0), (0, 0, z0 - z1), (0, -1, 0)),
    }
    out = []
    for name, (o, du, dv, n) in faces.items():
        tex_face = name
        flip = False
        if mirror:
            # Зеркальный куб: правая/левая грани меняются местами, каждая грань отражена по u.
            tex_face = {"right": "left", "left": "right"}.get(name, name)
            flip = True
        out.append((np.array(o, float), np.array(du, float), np.array(dv, float), np.array(n, float), rect[tex_face], flip, tex, name))
    return out


def player(skin, l1, l2, slim=False):
    q = []
    q += box(-4, 24, -4, 4, 32, 4, 0, 0, 8, 8, 8, skin)
    q += box(-4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4, skin)
    q += box(-8, 12, -2, -4, 24, 2, 40, 16, 4, 12, 4, skin)
    q += box(4, 12, -2, 8, 24, 2, 32, 48, 4, 12, 4, skin)
    q += box(-4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4, skin)
    q += box(0, 0, -2, 4, 12, 2, 16, 48, 4, 12, 4, skin)
    if l2 is not None:
        q += box(-4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4, l2, 0.5)
        q += box(-4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4, l2, 0.5)
        q += box(0, 0, -2, 4, 12, 2, 0, 16, 4, 12, 4, l2, 0.5, mirror=True)
    if l1 is not None:
        q += box(-4, 24, -4, 4, 32, 4, 0, 0, 8, 8, 8, l1, 1.0)
        q += box(-4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4, l1, 1.0)
        q += box(-8, 12, -2, -4, 24, 2, 40, 16, 4, 12, 4, l1, 1.0)
        q += box(4, 12, -2, 8, 24, 2, 40, 16, 4, 12, 4, l1, 1.0, mirror=True)
        q += box(-4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4, l1, 1.0)
        q += box(0, 0, -2, 4, 12, 2, 0, 16, 4, 12, 4, l1, 1.0, mirror=True)
    return q


def render(quads, yaw, pitch, scale=12, size=(420, 520), light=1.0, ticks=None, bg=(46, 48, 58)):
    W, H = size
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(p):
        x, y, z = p[..., 0], p[..., 1] - 16, p[..., 2]
        x2 = cy * x + sy * z
        z2 = -sy * x + cy * z
        y2 = cp * y - sp * z2
        z3 = sp * y + cp * z2
        return np.stack([x2, y2, z3], -1)

    color = np.zeros((H, W, 3), float)
    color[:] = bg
    depth = np.full((H, W), -1e9)
    ys, xs = np.mgrid[0:H, 0:W]
    sx = (xs - W / 2 + 0.5) / scale
    sy_ = -(ys - H / 2 + 0.5) / scale
    ldir = np.array([-0.45, 0.75, 0.5]); ldir /= np.linalg.norm(ldir)
    for o, du, dv, n, (tu, tv, tw, th), flip, tex, fname in quads:
        if tw == 0 or th == 0:
            continue
        P = view(o[None])[0]; U = view(du[None])[0] - view(np.zeros((1, 3)))[0]; V = view(dv[None])[0] - view(np.zeros((1, 3)))[0]
        nv = view(n[None])[0] - view(np.zeros((1, 3)))[0]
        if nv[2] <= 1e-6:
            continue  # грань от нас (двусторонние слои брони тоже видны снаружи — этого хватает)
        det = U[0] * V[1] - U[1] * V[0]
        if abs(det) < 1e-9:
            continue
        rx, ry = sx - P[0], sy_ - P[1]
        s = (rx * V[1] - ry * V[0]) / det
        t = (U[0] * ry - U[1] * rx) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        if not inside.any():
            continue
        z = P[2] + s * U[2] + t * V[2]
        su = np.clip((s * tw).astype(int), 0, tw - 1)
        if flip:
            su = tw - 1 - su
        tvv = np.clip((t * th).astype(int), 0, th - 1)
        px = tex[tv + tvv, tu + su]
        a = px[..., 3]
        m = inside & (a >= 26) & (z > depth)
        if not m.any():
            continue
        lam = 0.62 + 0.38 * max(0.0, float(np.dot(n, ldir)))
        rgb = px[..., :3].astype(float)
        lit = rgb * lam * light
        if ticks is not None:
            g = (a == GLOW_ALPHA)
            row = (tv + tvv).astype(float)
            f = 0.80 + 0.55 * (0.5 + 0.5 * np.sin(ticks * 0.16 - row * 0.55))
            emis = np.clip(rgb * f[..., None], 0, 255)
            lit = np.where(g[..., None], emis, lit)
        color[m] = lit[m]
        depth[m] = z[m]
    return Image.fromarray(np.clip(color, 0, 255).astype(np.uint8), "RGB")


def load(p):
    return np.array(Image.open(p).convert("RGBA"))


def main(steve):
    os.makedirs(OUT, exist_ok=True)
    skin = load(steve)
    views = [("front", 0, 8), ("back", 180, 8), ("three_quarter", -35, 18)]
    row = []
    for name in SETS:
        l1 = load(os.path.join(TEX, name + "_armor_layer_1.png"))
        l2 = load(os.path.join(TEX, name + "_armor_layer_2.png"))
        q = player(skin, l1, l2)
        tiles = [render(q, yaw, pitch) for _, yaw, pitch in views]
        night = render(q, -35, 18, light=0.28, ticks=40.0, bg=(12, 13, 20))
        tiles.append(night)
        sheet = Image.new("RGB", (420 * len(tiles), 520), (0, 0, 0))
        for i, t in enumerate(tiles):
            sheet.paste(t, (420 * i, 0))
        sheet.save(os.path.join(OUT, name + "_views.png"))
        frames = [render(q, -35, 18, scale=10, size=(340, 430), light=0.28, ticks=float(i) * 3.3, bg=(12, 13, 20)) for i in range(12)]
        frames[0].save(os.path.join(OUT, name + "_glow.gif"), save_all=True, append_images=frames[1:], duration=165, loop=0)
        row.append(render(q, -35, 18))
        print("ok", name)
    over = Image.new("RGB", (420 * 3, 520), (46, 48, 58))
    for i, t in enumerate(row):
        over.paste(t, (420 * i, 0))
    over.save(os.path.join(OUT, "all_sets.png"))
    # Иконки 16x16 крупно (x8) на сером фоне слота.
    parts = ["helmet", "chestplate", "leggings", "boots"]
    ic = Image.new("RGBA", (len(parts) * 140 + 12, len(SETS) * 140 + 12), (139, 139, 139, 255))
    for i, name in enumerate(SETS):
        for j, p in enumerate(parts):
            im = Image.open(os.path.join(TEX, name + "_" + p + ".png")).convert("RGBA").resize((128, 128), Image.NEAREST)
            ic.alpha_composite(im, (12 + j * 140, 12 + i * 140))
    ic.save(os.path.join(OUT, "icons.png"))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/claude-0/vanilla/assets/minecraft/textures/entity/player/wide/steve.png")
