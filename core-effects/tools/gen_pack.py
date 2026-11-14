#!/usr/bin/env python3
"""Генератор текстур и шрифтов пакета core-effects (накладки на экран, иконки, бинт).

    python3 tools/gen_pack.py        # пишет pack/assets/coreeffects/** и pack/mythichud/**

Накладка — картинка 512×256, режется на два ряда по 512×128 (клетки 256×128: в шрифт больше 256 не влезает).
Нормированные координаты u, v ∈ [−1, 1]; типичный экран (GUI 270×480 при масштабе 4 на 1080p) — |u| ≈ 0.42..0.55,
|v| ≈ 0.47, поэтому «рамка» эффекта считается от этого прямоугольника, а дальше до краёв картинки — полная сила
(на мелком масштабе GUI видно больше картинки, но жёсткого края нет до 1152×576 единиц GUI).
"""
import json
import os
import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'pack')
A = os.path.join(ROOT, 'assets', 'coreeffects')
rng = np.random.default_rng(20261114)

W, H = 512, 256


def grid():
    x = (np.arange(W) + 0.5) / W * 2 - 1
    y = (np.arange(H) + 0.5) / H * 2 - 1
    return np.meshgrid(x, y)


def smooth_noise(scale, octaves=4, seed=0):
    r = np.random.default_rng(seed)
    out = np.zeros((H, W))
    amp = 1.0
    tot = 0
    for o in range(octaves):
        gh = max(2, int(H / scale * 2 ** o))
        gw = max(2, int(W / scale * 2 ** o))
        g = r.random((gh, gw))
        img = Image.fromarray((g * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
        out += np.asarray(img, dtype=float) / 255 * amp
        tot += amp
        amp *= 0.5
    return out / tot


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def frame_r(u, v, p=3.0, hu=0.46, hv=0.47):
    return (np.abs(u / hu) ** p + np.abs(v / hv) ** p) ** (1 / p)


def finish(rgb, alpha):
    """alpha 0..1 → RGBA; ниже 0.1 шейдер отбрасывает фрагмент — у порога сглаживаем шумом."""
    a = np.clip(alpha, 0, 1)
    dither = rng.random(a.shape) * 0.06
    a = np.where(a < 0.1, np.where(a + dither >= 0.1, 0.1, 0.0), a)
    out = np.zeros((H, W, 4), dtype=np.uint8)
    out[..., 0] = np.clip(rgb[0], 0, 255)
    out[..., 1] = np.clip(rgb[1], 0, 255)
    out[..., 2] = np.clip(rgb[2], 0, 255)
    out[..., 3] = (a * 255).round().astype(np.uint8)
    # Последний столбец каждой клетки 256×128 не должен быть полностью прозрачным: иначе клиент урежет ширину глифа.
    for cx in (255, 511):
        for cy in (0, 128):
            if out[cy:cy + 128, cx, 3].max() == 0:
                out[cy + 64, cx, 3] = 1
    return out


def vignette():
    u, v = grid()
    r = frame_r(u, v)
    a = smoothstep(0.45, 1.05, r) * 0.62
    return finish((np.zeros_like(u),) * 3, a)


def panic():
    u, v = grid()
    r = frame_r(u, v)
    n = smooth_noise(18, 5, 7)
    veins = np.abs(smooth_noise(10, 4, 8) - 0.5)
    vein = smoothstep(0.05, 0.0, veins) * smoothstep(0.35, 0.9, r)
    a = smoothstep(0.25, 0.95, r + (n - 0.5) * 0.25) * 0.72 + vein * 0.25
    col = (14 + 30 * vein, 0 * u, 22 + 40 * vein)
    return finish(col, a)


def blood():
    u, v = grid()
    r = frame_r(u, v)
    n = smooth_noise(14, 5, 3)
    edge = smoothstep(0.62, 1.08, r + (n - 0.5) * 0.45)
    # капли/потёки сверху: столбики разной длины от верхнего края экрана (v ≈ −0.47) вниз
    drips = np.zeros_like(u)
    cols = rng.choice(W, 34, replace=False)
    for c in cols:
        length = rng.uniform(0.05, 0.32)
        width = rng.uniform(1.5, 4.5) / W * 2
        x0 = (c + 0.5) / W * 2 - 1
        top = -0.47 - 0.1
        bottom = -0.47 + length
        d = np.exp(-((u - x0) / width) ** 2)
        body = d * smoothstep(top - 0.02, top, v) * smoothstep(bottom + 0.02, bottom - 0.01, v)
        bulb = np.exp(-(((u - x0) / (width * 1.6)) ** 2 + ((v - bottom) / (width * 2.2)) ** 2))
        drips = np.maximum(drips, np.maximum(body, bulb))
    drips *= smoothstep(-0.2, -0.45, v) * 0 + 1
    a = np.maximum(edge * 0.78, drips * 0.85)
    shade = 0.75 + 0.5 * n
    col = (110 * shade + 40 * drips, 0 * u, 6 * shade)
    return finish(col, a)


def heat():
    u, v = grid()
    r = frame_r(u, v)
    n = smooth_noise(12, 4, 11)
    a = smoothstep(0.62, 1.1, r + (n - 0.5) * 0.35) * 0.5
    col = (255 * np.ones_like(u), 110 + 60 * n, 20 + 0 * u)
    return finish(col, a)


def curse():
    u, v = grid()
    r = frame_r(u, v)
    n = smooth_noise(9, 5, 21)
    wisps = smoothstep(0.55, 0.75, n) * smoothstep(0.6, 1.0, r)
    a = np.maximum(smoothstep(0.7, 1.15, r) * 0.45, wisps * 0.45)
    col = (80 + 60 * wisps, 0 * u, 130 + 60 * wisps)
    return finish(col, a)


LAYERS = [  # (имя, первый символ) — как Overlay.Layer в Java
    ('vignette', 0xE011, vignette),
    ('blood', 0xE015, blood),
    ('heat', 0xE019, heat),
    ('curse', 0xE01D, curse),
    ('panic', 0xE021, panic),
]


def write_png(path, arr):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(arr, 'RGBA').save(path, optimize=True)


def overlays():
    tex = os.path.join(A, 'textures', 'font', 'overlay')
    providers = [{'type': 'space', 'advances': {'': -1, '': -289}}]
    dark = np.zeros((8, 16, 4), dtype=np.uint8)
    dark[..., 3] = 64
    write_png(os.path.join(tex, 'dark.png'), dark)
    providers.append({'type': 'bitmap', 'file': 'coreeffects:font/overlay/dark.png', 'height': 144, 'ascent': 69,
                      'chars': ['']})
    for name, first, fn in LAYERS:
        img = fn()
        write_png(os.path.join(tex, name + '_top.png'), img[:128])
        write_png(os.path.join(tex, name + '_bottom.png'), img[128:])
        providers.append({'type': 'bitmap', 'file': f'coreeffects:font/overlay/{name}_top.png', 'height': 72,
                          'ascent': 69, 'chars': [chr(first) + chr(first + 1)]})
        providers.append({'type': 'bitmap', 'file': f'coreeffects:font/overlay/{name}_bottom.png', 'height': 72,
                          'ascent': -3, 'chars': [chr(first + 2) + chr(first + 3)]})
        # превью (tools/preview, не в git) — проверить глазами
        prev = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'preview')
        os.makedirs(prev, exist_ok=True)
        bg = Image.new('RGBA', (W, H), (110, 150, 90, 255))
        bg.alpha_composite(Image.fromarray(img, 'RGBA'))
        bg.save(os.path.join(prev, name + '.png'))
    font = os.path.join(A, 'font', 'overlay.json')
    os.makedirs(os.path.dirname(font), exist_ok=True)
    with open(font, 'w', encoding='utf-8') as f:
        json.dump({'providers': providers}, f, ensure_ascii=True, indent=2)


# --------------------------------------------------------------------------------------------- иконки 16×16

def px(img, pts, c):
    for x, y in pts:
        img[y, x] = c


def icon_blood():
    i = np.zeros((16, 16, 4), dtype=np.uint8)
    dark, mid, light = (90, 0, 8, 255), (190, 16, 30, 255), (255, 110, 110, 255)
    # капля
    rows = {2: (7, 8), 3: (7, 8), 4: (6, 9), 5: (6, 9), 6: (5, 10), 7: (4, 11), 8: (4, 11), 9: (3, 12), 10: (3, 12),
            11: (3, 12), 12: (4, 11), 13: (5, 10), 14: (6, 9)}
    for y, (a, b) in rows.items():
        for x in range(a, b + 1):
            i[y, x] = mid
        i[y, a] = dark
        i[y, b] = dark
    for x in range(6, 10):
        i[14, x] = dark
    px(i, [(6, 8), (5, 9), (5, 10), (6, 10)], light)
    return i


def icon_panic():
    i = np.zeros((16, 16, 4), dtype=np.uint8)
    o, f, e = (30, 10, 50, 255), (150, 120, 255, 255), (255, 255, 255, 255)
    for y in range(2, 14):
        for x in range(3, 13):
            if (x - 7.5) ** 2 / 25 + (y - 7.5) ** 2 / 36 <= 1:
                i[y, x] = f
    for y in range(16):
        for x in range(16):
            if i[y, x, 3] and any(not i[min(15, max(0, y + dy)), min(15, max(0, x + dx)), 3]
                                  for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                i[y, x] = o
    px(i, [(5, 6), (6, 6), (5, 7), (6, 7), (9, 6), (10, 6), (9, 7), (10, 7)], o)   # глаза
    px(i, [(6, 6), (10, 6)], e)
    px(i, [(7, 10), (8, 10), (6, 11), (9, 11), (7, 12), (8, 12)], o)                # рот «о»
    return i


def icon_warmth():
    i = np.zeros((16, 16, 4), dtype=np.uint8)
    r, o, y = (200, 40, 10, 255), (255, 140, 30, 255), (255, 230, 120, 255)
    flame = {3: (7, 8), 4: (6, 8), 5: (6, 9), 6: (5, 9), 7: (5, 10), 8: (4, 11), 9: (4, 11), 10: (4, 11), 11: (4, 11),
             12: (5, 10), 13: (6, 9)}
    for yy, (a, b) in flame.items():
        for x in range(a, b + 1):
            i[yy, x] = o
        i[yy, a] = r
        i[yy, b] = r
    for yy, (a, b) in {8: (7, 8), 9: (6, 9), 10: (6, 9), 11: (6, 9), 12: (7, 8)}.items():
        for x in range(a, b + 1):
            i[yy, x] = y
    px(i, [(9, 2), (10, 4)], o)
    return i


def icon_curse():
    i = np.zeros((16, 16, 4), dtype=np.uint8)
    d, p, l = (40, 0, 60, 255), (140, 40, 220, 255), (230, 180, 255, 255)
    # череп
    for y in range(2, 11):
        for x in range(3, 13):
            if (x - 7.5) ** 2 / 25 + (y - 6.5) ** 2 / 20 <= 1:
                i[y, x] = p
    for y in range(10, 14):
        for x in range(5, 11):
            i[y, x] = p
    px(i, [(5, 6), (6, 6), (5, 7), (6, 7), (9, 6), (10, 6), (9, 7), (10, 7), (7, 9), (8, 9)], d)
    px(i, [(6, 13), (8, 13), (10, 13), (6, 12), (8, 12)], d)
    px(i, [(5, 3), (6, 3), (4, 4)], l)
    return i


def icons():
    sheet = np.zeros((16, 64, 4), dtype=np.uint8)
    out = {'bleeding': icon_blood(), 'panic': icon_panic(), 'warmth': icon_warmth(), 'curse': icon_curse()}
    for k, (name, img) in enumerate(out.items()):
        sheet[:, k * 16:(k + 1) * 16] = img
        write_png(os.path.join(ROOT, 'mythichud', 'source-pack', 'assets', 'mythichud', 'textures', 'assets',
                               'coreeffects', name + '.png'), img)
    write_png(os.path.join(A, 'textures', 'font', 'icons.png'), sheet)
    font = os.path.join(A, 'font', 'icons.json')
    with open(font, 'w', encoding='utf-8') as f:
        json.dump({'providers': [{'type': 'bitmap', 'file': 'coreeffects:font/icons.png', 'height': 9, 'ascent': 8,
                                  'chars': ['']}]}, f, ensure_ascii=True, indent=2)


def bandage():
    i = np.zeros((16, 16, 4), dtype=np.uint8)
    w, s, e, red = (240, 236, 222, 255), (196, 188, 170, 255), (130, 120, 105, 255), (190, 20, 30, 255)
    # свёрнутый бинт: рулон и хвост
    for y in range(4, 12):
        for x in range(2, 10):
            if (x - 5.5) ** 2 + (y - 7.5) ** 2 <= 14:
                i[y, x] = w
    for y in range(4, 12):
        for x in range(2, 10):
            d = (x - 5.5) ** 2 + (y - 7.5) ** 2
            if 9 < d <= 14:
                i[y, x] = s
    px(i, [(5, 7), (6, 7), (5, 8), (6, 8)], e)
    for x in range(8, 15):
        for y in range(8, 11):
            i[y, x] = w
        i[11, x] = s
    px(i, [(11, 9), (12, 9), (12, 8)], red)
    write_png(os.path.join(A, 'textures', 'item', 'bandage.png'), i)
    os.makedirs(os.path.join(A, 'items'), exist_ok=True)
    os.makedirs(os.path.join(A, 'models', 'item'), exist_ok=True)
    with open(os.path.join(A, 'items', 'bandage.json'), 'w') as f:
        json.dump({'model': {'type': 'minecraft:model', 'model': 'coreeffects:item/bandage'}}, f, indent=2)
    with open(os.path.join(A, 'models', 'item', 'bandage.json'), 'w') as f:
        json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': 'coreeffects:item/bandage'}}, f, indent=2)


if __name__ == '__main__':
    overlays()
    icons()
    bandage()
    print('ok')
