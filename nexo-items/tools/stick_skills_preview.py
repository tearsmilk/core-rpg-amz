#!/usr/bin/env python3
"""Превью эффектов палки core_weapons (шейдер moonstaff, Python-копия из moonstaff_fx.py):

    python3 -I tools/stick_skills_preview.py      (из папки nexo-items; спрайты — из pack, сначала moonstaff_fx)

Пишет preview/stick_skills/:
  stick_backstab_slash.png  — «Удар в спину» глазами атакующего: серп + штрих в моменты 0.5/1.5/2.5/4/6/8 тиков
                              (верхний ряд — обычный удар, нижний — со спины, крит);
  stick_embers_orb.png      — сфера «Углей» перед магом при заряде 0.1/0.4/0.7/1.0 (размер растёт, как в игре);
  stick_embers_beam.png     — луч «Углей» сбоку при длине 4/10/16 блоков (вверху) и сцена из-за плеча мага:
                              сфера, луч 12 блоков, вспышка у цели (внизу).
Частицы (FLAME, CRIT, DUST, искры) в игре добавляет сервер — здесь они примерно показаны мягкими точками.
Геометрия дисплеев — как в core_weapons/kouka.py (反転: модель повёрнута на 180° вокруг Y, оси модели = оси дисплея).
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import moonstaff_fx as FX  # noqa: E402

OUT = os.path.join(ROOT, 'preview', 'stick_skills')


def label(img, text, xy=(8, 6)):
    ImageDraw.Draw(img).text(xy, text, fill=(255, 225, 210))
    return img


def to_img(a):
    return Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8))


def quads_M(model, pos, M, R, t):
    """Квады модели с готовой матрицей M (3×3: оси модели в мире, с масштабом), центр модели — в pos."""
    out = []
    for var, pts in FX.bake_quads(model):
        name = model['textures'][var].split('/')[-1]
        w = (M @ (pts / 16.0 - 0.5).T).T + np.asarray(pos, float)
        out.append((FX.KINDS[name], name, w[0], w[3] - w[0], w[1] - w[0], R, t))
    return out


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1.0]])


def billboard(cam, pos, s):
    """Billboard CENTER: грань north смотрит в камеру незеркально (модель +X → влево на экране)."""
    f = np.asarray(pos, float) - cam.pos
    f /= np.linalg.norm(f)
    up = np.array([0.0, 1.0, 0.0])
    r = np.cross(f, up)
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    return np.column_stack([-r, u, f]) * s


def box_silhouette(cam, img, lo, hi, col):
    """Тёмный силуэт цели (зомби) — проекция бокса, залитая цветом."""
    pts = [cam.project((x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
    pts = [p for p in pts if p is not None]
    if not pts:
        return img
    x0, x1 = int(min(p[0] for p in pts)), int(max(p[0] for p in pts))
    y0, y1 = int(min(p[1] for p in pts)), int(max(p[1] for p in pts))
    x0, y0 = max(x0, 0), max(y0, 0)
    img[y0:y1, x0:x1] = np.array(col)
    return img


# ------------------------------------------------------------------------------------------- «Удар в спину»
SLASH = dict(dist=2.0, size=2.6, crit_size=3.2, start=0.7, roll=-28.0, roll_add=14.0, grow=4)


def slash_frame(model, age, crit, w=480, h=320):
    """Глазами атакующего: глаза в (0, 1.62, 0), взгляд +Z; цель — зомби в 2.4 блока; дисплей серпа в точке взгляда
    на расстоянии dist, поворот roll вокруг взгляда (+roll_add за время роста), масштаб start → 1 за grow тиков."""
    cam = FX.Camera((0, 1.62, 0), (0, 1.5, 4), w, h, fov=70)
    img = FX.sky_ground(cam, ground_y=0.0)
    img = box_silhouette(cam, img, (-0.3, 0.0, 2.1), (0.3, 1.95, 2.7), (0.11, 0.2, 0.12))
    G = 1000                                   # тик сервера при появлении (g = G mod 125 = 0)
    R = (G % 125) + 125 * crit
    k = min(1.0, max(0.0, (age - 1.0) / SLASH['grow']))
    S = (SLASH['crit_size'] if crit else SLASH['size']) * (SLASH['start'] + (1 - SLASH['start']) * k)
    roll = math.radians(SLASH['roll'] + SLASH['roll_add'] * k)
    M = rz(roll) * S                          # 反転: оси модели = оси дисплея; дисплей смотрит +Z
    pos = (0.0, 1.62 - 0.15, SLASH['dist'])
    FX.SPRITES_CACHE.update(SPR)
    img = FX.render_quads(cam, quads_M(model, pos, M, R, (G + age) / 20.0), img)
    if 0.5 <= age <= 6:
        rng = np.random.default_rng(int(age * 10) + crit)
        pts = [((rng.uniform(-0.9, 0.9), 1.45 + rng.uniform(-0.7, 0.7), 2.2 + rng.uniform(-0.2, 0.3)), 1.0)
               for _ in range(14 if crit else 9)]
        img = FX.add_glow_points(cam, img, pts, 0.25, (1.0, 0.45, 0.15), 0.5 * (1 - age / 7))
        if crit:
            img = FX.add_glow_points(cam, img, pts[:6], 0.18, (1.0, 0.95, 0.9), 0.6 * (1 - age / 7))
    return to_img(img)


def slash_sheet():
    model = FX.slash_model()
    ages = (0.5, 1.5, 2.5, 4.0, 6.0, 8.0)
    sheet = Image.new('RGB', (480 * len(ages), 320 * 2))
    for row, crit in enumerate((0, 1)):
        for i, age in enumerate(ages):
            fr = slash_frame(model, age, crit)
            label(fr, f'{"BACKSTAB crit" if crit else "normal"}  age {age} ticks')
            sheet.paste(fr, (480 * i, 320 * row))
    sheet.save(os.path.join(OUT, 'stick_backstab_slash.png'))
    # крупно: один кадр обычного удара и один крит
    big = Image.new('RGB', (960, 640))
    big.paste(slash_frame(model, 2.5, 0, 960, 640).resize((960, 640)), (0, 0))
    big.save(os.path.join(OUT, 'stick_backstab_slash_big.png'))
    big = slash_frame(model, 2.5, 1, 960, 640)
    big.save(os.path.join(OUT, 'stick_backstab_slash_big_crit.png'))


# ------------------------------------------------------------------------------------------- «Угли»: сфера
ORB = dict(size=1.15, start=0.35, fwd=1.3)


def orb_front(c, t, size=256, bg=(0.05, 0.05, 0.07)):
    """Сфера анфас (billboard), слои в порядке модели; масштаб растёт с зарядом."""
    s = ORB['start'] + (1 - ORB['start']) * c
    v = ((np.arange(size) + 0.5) / size - 0.5) / s + 0.5
    lx, ly = np.meshgrid(v, v)
    inside = (lx >= 0) & (lx <= 1) & (ly >= 0) & (ly <= 1)
    img = np.ones((size, size, 3)) * np.array(bg)
    for name in ('ember_ring', 'ember_runes', 'ember_hexagram', 'ember_core'):
        col = FX.shade(FX.KINDS[name], SPR[name], np.clip(lx, 0, 1), np.clip(ly, 0, 1), c, t)
        col[..., 3] *= inside
        img = FX.over(img, col)
    return to_img(img)


def orb_sheet():
    im = Image.new('RGB', (256 * 4, 256 * 2))
    for i, c in enumerate((0.1, 0.4, 0.7, 1.0)):
        for j, t in enumerate((0.0, 0.35)):
            fr = orb_front(c, t + c)
            label(fr, f'charge {c} ({c:.1f} s of 1 s)  t+{t}')
            im.paste(fr, (256 * i, 256 * j))
    im.save(os.path.join(OUT, 'stick_embers_orb.png'))


# ------------------------------------------------------------------------------------------- «Угли»: луч
BEAM = dict(width=0.9, start_w=0.25)


def beam_side(R, t, length_px=760, width_px=60):
    L = R / 8.0
    H = width_px * 2
    img = np.ones((H, length_px, 3)) * np.array((0.42, 0.5, 0.62))      # светлый фон — видно чёрный дым
    xs = (np.arange(length_px) + 0.5) / length_px
    ys = (np.arange(H) + 0.5) / H * 2 - 1
    S_, Y_ = np.meshgrid(xs, ys)
    planes = [(30, 0), (30, 22.5), (30, -22.5), (30, 45), (30, -45), (32, 90), (32, 67.5), (32, 112.5)]
    for kind, phi in planes:
        k = abs(math.cos(math.radians(phi)))
        if k < 0.05:
            continue
        w = Y_ / k * 0.5 + 0.5
        inside = (w >= 0) & (w <= 1)
        lx, ly = (w, S_) if kind == 30 else (S_, w)
        name = 'ember_beam_up' if kind == 30 else 'ember_beam_west'
        col = FX.shade(kind, SPR[name], lx, ly, R / 250.0, t)
        col[..., 3] *= inside
        img = FX.over(img, col)
    return to_img(img)


def beam_scene(t=0.3, w=960, h=540, impact_k=0.6):
    """Из-за плеча мага: глаза (0, 1.62, 0), взгляд +Z; сфера в fwd; луч от сферы до цели в 12 блоках; вспышка-круг
    у цели (billboard), искры и угли — точками."""
    cam = FX.Camera((-1.6, 2.35, -2.6), (0.2, 1.4, 8.0), w, h, fov=65)
    tgt = 12.6
    glow = [((0.0, 0.0, tgt), 3.0, 0.35), ((0.0, 0.0, 1.3), 1.5, 0.2)]
    img = FX.sky_ground(cam, ground_y=0.0, glow=glow)
    img = box_silhouette(cam, img, (-0.32, 0.0, tgt - 0.3), (0.32, 1.95, tgt + 0.3), (0.12, 0.2, 0.12))
    img = box_silhouette(cam, img, (-0.3, 0.0, -0.3), (0.3, 1.8, 0.3), (0.16, 0.13, 0.2))   # маг
    eye = np.array([0.0, 1.62, 0.0])
    fwd = ORB['fwd']
    orb_pos = eye + np.array([0, -0.12, fwd])
    D = tgt - 0.3
    Lv = max(0.5, D - fwd)
    R = int(max(1, min(250, round(Lv * 8))))
    quads = []
    # луч: дисплей в точке сферы, модель вдоль +Z от z=8 до 16 px (0.5 блока) → масштаб Z = 2·Lv
    M = np.diag([BEAM['width'], BEAM['width'], 2.0 * Lv])
    beam_m = FX.ember_beam_model()
    quads += quads_M(beam_m, orb_pos, M, R, t)
    orb = FX.ember_orb_model()
    quads += quads_M(orb, orb_pos, billboard(cam, orb_pos, ORB['size'] * 1.15), 250, t)
    hit = np.array([0.0, 1.3, D])
    quads += quads_M(orb, hit, billboard(cam, hit, 0.6 + 1.2 * impact_k), 250, t + 0.5)
    FX.SPRITES_CACHE.update(SPR)
    img = FX.render_quads(cam, quads, img)
    rng = np.random.default_rng(5)
    pts = [((rng.uniform(-0.25, 0.25), 1.5 + rng.uniform(-0.25, 0.25), rng.uniform(fwd, D)), 1.0) for _ in range(30)]
    img = FX.add_glow_points(cam, img, pts, 0.07, (1.0, 0.35, 0.1), 0.9)
    burst = [((rng.uniform(-0.8, 0.8), 1.3 + rng.uniform(-0.8, 0.9), D + rng.uniform(-0.6, 0.4)), 1.0) for _ in range(26)]
    img = FX.add_glow_points(cam, img, burst, 0.12, (1.0, 0.4, 0.12), 0.8)
    return to_img(img)


def beam_sheet():
    side = Image.new('RGB', (760, 120 * 3))
    for i, Lb in enumerate((4, 10, 16)):
        fr = beam_side(Lb * 8, 0.2 * i)
        label(fr, f'side view, length {Lb} blocks (light background)', (8, 4))
        side.paste(fr, (0, 120 * i))
    scene = beam_scene()
    label(scene, 'over the shoulder: orb, 12-block ember beam, impact burst')
    sheet = Image.new('RGB', (960, 360 + 540), (10, 10, 14))
    sheet.paste(side, (100, 0))
    sheet.paste(scene, (0, 360))
    sheet.save(os.path.join(OUT, 'stick_embers_beam.png'))


SPR = {}


def main():
    os.makedirs(OUT, exist_ok=True)
    SPR.update(FX.load_sprites())
    slash_sheet()
    orb_sheet()
    beam_sheet()
    sheet = Image.new('RGBA', (130 * 10, 130), (20, 22, 30, 255))
    names = list(FX.EMBER_CIRCLE) + ['slash_crescent', 'slash_streak'] + list(FX.EMBER_BEAMS)
    for i, n in enumerate(names):
        sheet.alpha_composite(Image.fromarray(SPR[n], 'RGBA'), (130 * i + 1, 1))
    sheet.save(os.path.join(OUT, 'stick_fx_sprites.png'))
    print('превью:', OUT)


if __name__ == '__main__':
    main()
