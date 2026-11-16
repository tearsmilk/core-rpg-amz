#!/usr/bin/env python3
"""«Посох Лунного Странника» (azerus_moon_staff) — объёмная модель Java (кубы), пиксельная текстура 64×64,
модели и спрайты эффектов (магический круг, луч) и превью. Всё собирается кодом (техника crimson_scythe.py).

    python3 -I tools/moon_staff.py            (из папки nexo-items)

Пишет:
  source/Посох-лунного-странника.bbmodel                 — проект Blockbench (java_block), его можно править;
  pack/assets/azerus/models/item/moon_staff.json         — модель (через tools/bbjava2json.py из .bbmodel);
  pack/assets/azerus/textures/item/moon_staff.png        — текстура 64×64;
  pack/assets/azerus/models/item/moon_staff_cast.json    — поза «колдует» (анимация BOW) и самоцвет ярче (свет 15);
  pack/assets/azerus/items/moon_staff.json               — определение предмета: в руках при использовании — cast;
  pack/assets/azerus/textures/item/moon_staff_fx/*.png   — 10 спрайтов эффектов 128×128 (tools/moonstaff_fx.py);
  pack/assets/azerus/models/item/moon_staff_{circle,beam,barrier}.json, items/moon_staff_{circle,beam,barrier}.json;
  preview/moon_staff/*.png                               — превью (render_model.py + копия шейдера).
Шейдерный хук ставит отдельно tools/moonstaff_hook.py apply, проверка — tools/moonstaff_check.py.

Правила Java-модели: поворот куба только по одной оси и только −45/−22.5/0/22.5/45, координаты в −16..32.
Посох вдоль +Y (x = z = 8), длина 46 px (y −15..31): золотой подток, тёмно-багровое древко 2×2, красная лента под
навершием с двумя хвостами, ступенчатый золотой воротник, золотой полумесяц (открытое кольцо: от воротника вверх по
левой стороне, через верх, остриём вправо-вверх; разрыв справа), круглая оправа-восьмиугольник и 4 лапки, большой
круглый красный самоцвет из 4 скрещённых брусков. Свет сверху слева, пять тонов на материал, без шума.
"""
import base64, io, json, math, os, subprocess, sys, uuid

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import moonstaff_fx as FX  # noqa: E402
from render_model import (Canvas, Rx, Ry, Rz, T, S as Sc, part_matrix, load_model, tex_image, quads, draw,  # noqa: E402
                          display, persp, render_gui, render_turn, render_third, ortho, draw_box, player_parts)

NS, MID = 'azerus', 'moon_staff'
BB = os.path.join(ROOT, 'source', 'Посох-лунного-странника.bbmodel')
ASSETS = os.path.join(ROOT, 'pack', 'assets')
PREVIEW = os.path.join(ROOT, 'preview', 'moon_staff')
TEX_W = TEX_H = 64

# ------------------------------------------------------------------------------------------- палитры (5 тонов)
hexc = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
WOOD = [hexc(h) for h in ('#1e0a0c', '#3a1216', '#5a1a1e', '#7a2a26', '#9a3c30')]
GOLD = [hexc(h) for h in ('#4a2a0c', '#8a5a1a', '#c8902a', '#f0c850', '#fff2a8')]
GEM = [hexc(h) for h in ('#3a0610', '#7a0f1c', '#c41a2a', '#ff4a4a', '#ffd0d0')]   # 4 — блик (1 тексель)
CLOTH = [hexc(h) for h in ('#36081a', '#661424', '#9c1828', '#d01c2a', '#f2343e')]
RAMPS = {'gold': GOLD, 'wood': WOOD, 'gem': GEM, 'cloth': CLOTH}


def norm(v):
    l = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / l for c in v)


LIGHT = norm((-0.55, 0.75, 0.35))   # сверху слева, чуть спереди

# ------------------------------------------------------------------------------------------- кубы
ELEMENTS = []
ANGLES = (-45, -22.5, 22.5, 45)


def add(name, part, frm, to, rot=None, light=0, shade=True, **extra):
    e = {'name': name, 'part': part, 'from': [round(v, 3) for v in frm], 'to': [round(v, 3) for v in to],
         'rot': rot, 'light': light, 'shade': shade}
    e.update(extra)
    for v in e['from'] + e['to']:
        assert -16 <= v <= 32, (name, v)
    if rot:
        assert rot['angle'] in ANGLES and rot['axis'] in 'xyz', (name, rot)
    ELEMENTS.append(e)
    return e


def box(name, part, cx, cy, cz, sx, sy, sz, rot=None, **kw):
    return add(name, part, (cx - sx / 2, cy - sy / 2, cz - sz / 2), (cx + sx / 2, cy + sy / 2, cz + sz / 2), rot, **kw)


def zrot(angle, origin):
    return {'angle': angle, 'axis': 'z', 'origin': [round(v, 3) for v in origin]} if angle else None


def bar(name, part, cx, cy, angle, length, width, z0, z1, **kw):
    """Брусок длиной length вдоль направления angle (кратно 22.5°), шириной width, в слое z0..z1 (как у косы)."""
    a = angle % 180
    assert abs(a / 22.5 - round(a / 22.5)) < 1e-6, angle
    if a <= 45:
        phi, along_x = a, True
    elif a >= 135:
        phi, along_x = a - 180, True
    else:
        phi, along_x = a - 90, False
    lx, ly = (length, width) if along_x else (width, length)
    return add(name, part, (cx - lx / 2, cy - ly / 2, z0), (cx + lx / 2, cy + ly / 2, z1),
               zrot(phi, (cx, cy, 8)), **kw)


X0 = Z0 = 8.0
# подток (низ): острый кончик, конус, обойма с ободком
box('Подток / кончик', 'gold', X0, -14.6, Z0, 1.2, 0.8, 1.2)
box('Подток / конус', 'gold', X0, -13.65, Z0, 2.0, 1.1, 2.0)
box('Подток / обойма', 'gold', X0, -12.2, Z0, 3.0, 1.8, 3.0)
box('Подток / ободок', 'gold', X0, -12.3, Z0, 3.4, 0.6, 3.4)
box('Подток / переход', 'gold', X0, -11.05, Z0, 2.4, 0.5, 2.4)
# древко
box('Древко', 'wood', X0, 4.0, Z0, 2.0, 30.0, 2.0)
box('Хват / низ', 'gold', X0, -1.3, Z0, 2.4, 0.6, 2.4)
box('Хват / верх', 'gold', X0, 1.3, Z0, 2.4, 0.6, 2.4)
# лента: четыре витка (через один повёрнуты на ±22.5° вокруг Y) и два хвоста спереди
for i, (y, ang) in enumerate(((13.5, 0), (14.5, 22.5), (15.5, 0), (16.5, -22.5))):
    box(f'Лента / виток {i + 1}', 'cloth', X0, y, Z0, 2.6, 1.1, 2.6,
        {'angle': ang, 'axis': 'y', 'origin': [X0, y, Z0]} if ang else None, tone=3 - (i % 2))
add('Лента / хвост 1', 'cloth', (8.6, 10.6, 9.3), (10.1, 15.2, 9.8), zrot(22.5, (9.35, 15.2, 9.55)), tone=3, tail=1)
add('Лента / хвост 2', 'cloth', (10.36, 7.6, 9.32), (11.86, 11.15, 9.82), zrot(-22.5, (11.11, 11.15, 9.57)), tone=2,
    tail=2)
add('Лента / хвост 3', 'cloth', (6.5, 11.8, 9.28), (7.7, 15.2, 9.72), zrot(-22.5, (7.1, 15.2, 9.5)), tone=2, tail=3)
# воротник под навершием
box('Воротник / низ', 'gold', X0, 17.5, Z0, 3.0, 1.0, 3.0)
box('Воротник / середина', 'gold', X0, 18.5, Z0, 3.5, 1.0, 3.5)
box('Воротник / верх', 'gold', X0, 19.5, Z0, 2.5, 1.0, 2.5)

# полумесяц: отрезки хорд через 22.5°, центры на углах 270 → 45 (по левой стороне через верх); внутренний край
# на радиусе R_IN, ширина полосы по углу — толще слева, остриём к правому верху
GX, GY = 8.0, 25.5
R_IN = 4.15
WIDTH = {270: 1.3, 247.5: 1.5, 225: 1.7, 202.5: 1.85, 180: 1.85, 157.5: 1.7, 135: 1.5, 112.5: 1.25, 90: 1.05,
         67.5: 0.9, 45: 0.6}
for k, th in enumerate(sorted(WIDTH, reverse=True)):
    w = WIDTH[th]
    rc = R_IN + w / 2
    seg = 2 * rc * math.sin(math.radians(11.25)) + (0.55 if th > 45 else 0.2)
    cx, cy = GX + rc * math.cos(math.radians(th)), GY + rc * math.sin(math.radians(th))
    eps = 0.02 * (k % 2)
    bar(f'Полумесяц {k + 1}', 'gold', cx, cy, th + 90, seg, w, 7.25 - eps, 8.75 + eps, th=th, w=w, crescent=True)
# острие
_tc = (GX + (R_IN + 0.2) * math.cos(math.radians(36)), GY + (R_IN + 0.2) * math.sin(math.radians(36)))
bar('Полумесяц / остриё', 'gold', _tc[0], _tc[1], 135, 0.9, 0.4, 7.6, 8.4, th=36, w=0.4, crescent=True)

# оправа: правильный восьмиугольник (вписанный радиус A) из 4 брусков
A = 2.95
SIDE = 2 * A * math.tan(math.radians(22.5))
box('Оправа / гориз.', 'gold', GX, GY, Z0, 2 * A, SIDE, 0.9, bezel=True)
box('Оправа / верт.', 'gold', GX, GY, Z0, SIDE, 2 * A, 0.9, bezel=True)
box('Оправа / диаг. 1', 'gold', GX, GY, Z0, 2 * A, SIDE, 0.9, zrot(45, (GX, GY, Z0)), bezel=True)
box('Оправа / диаг. 2', 'gold', GX, GY, Z0, 2 * A, SIDE, 0.9, zrot(-45, (GX, GY, Z0)), bezel=True)
# лапки от оправы к полумесяцу
for th in (45, 135, 225, 270):
    rc = (A - 0.3 + R_IN + 0.25) / 2
    bar(f'Лапка {th}', 'gold', GX + rc * math.cos(math.radians(th)), GY + rc * math.sin(math.radians(th)), th,
        R_IN + 0.25 - (A - 0.3), 0.7, 7.65, 8.35, prong=True)
# самоцвет: пиксельная сфера из четырёх скрещённых брусков
GEM_LIGHT = 10
GEM_PARTS = [('Самоцвет / широкий', 4.8, 3.0, 2.8), ('Самоцвет / высокий', 3.0, 4.8, 2.8),
             ('Самоцвет / тело', 3.6, 3.6, 3.6), ('Самоцвет / грань', 2.4, 2.4, 4.8)]
for name, sx, sy, sz in GEM_PARTS:
    box(name, 'gem', GX, GY, Z0, sx, sy, sz, light=GEM_LIGHT, shade=False)


# ------------------------------------------------------------------------------------------- геометрия точек
def _rot3(axis, a, v):
    c, s = math.cos(a), math.sin(a)
    x, y, z = v
    if axis == 'x':
        return (x, c * y - s * z, s * y + c * z)
    if axis == 'y':
        return (c * x + s * z, y, -s * x + c * z)
    return (c * x - s * y, s * x + c * y, z)


def rot_point(e, p):
    r = e['rot']
    if not r:
        return p
    o = r['origin']
    d = _rot3(r['axis'], math.radians(r['angle']), (p[0] - o[0], p[1] - o[1], p[2] - o[2]))
    return (o[0] + d[0], o[1] + d[1], o[2] + d[2])


def rot_vec(e, n):
    r = e['rot']
    return _rot3(r['axis'], math.radians(r['angle']), n) if r else n


def clamp(i, lo, hi):
    return max(lo, min(hi, i))


# ------------------------------------------------------------------------------------------- цвет текселя
def lit_step(n):
    s = n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]
    return 1 if s > 0.45 else (-1 if s < -0.2 else 0)


def color(e, p, face, n, u, v):
    """p — точка модели (после поворота куба), n — нормаль грани, u/v — 0..1 по грани."""
    part = e['part']
    step = lit_step(n)
    if part == 'wood':
        # древко: столбец слева светлее (цилиндр), волокна — тёмные/светлые штрихи кластерами вдоль
        idx = 2 + step
        if face in ('south', 'north', 'east', 'west'):
            idx += 1 if u < 0.5 else 0
            if face in ('east', 'north'):
                idx -= 1
            yy = int(math.floor(p[1]))
            if yy % 9 == 4 and u >= 0.5:
                idx -= 1            # тёмная прожилка
            if yy % 13 == 7 and u < 0.5 and face in ('south', 'west'):
                idx += 1            # светлая прожилка на освещённой стороне
        if p[1] < -8 or p[1] > 11:
            idx += 0 if p[1] > 11 else -0      # ровный тон по всей длине (без градиента по высоте)
        return WOOD[clamp(idx, 0, 4)]
    if part == 'cloth':
        idx = e.get('tone', 3) + step
        if 'tail' in e:
            if v > 0.8:
                idx -= 1            # нижний край хвоста в тени
            if face in ('south', 'north') and u < 0.34:
                idx += 1            # кромка
            if e['tail'] == 2 and v > 0.66:
                idx -= 1
        else:
            if face in ('south', 'north', 'east', 'west'):
                if v < 0.34:
                    idx += 1        # верх витка — складка на свету
                elif v > 0.67:
                    idx -= 1
        return CLOTH[clamp(idx, 0, 4)]
    if part == 'gem':
        # псевдосфера: нормаль = направление от центра камня, свет спереди-слева-сверху
        d = (p[0] - GX, p[1] - GY, abs(p[2] - Z0))   # сзади камень выглядит так же, как спереди
        r = math.sqrt(d[0] ** 2 + d[1] ** 2 + d[2] ** 2) or 1
        nn = (d[0] / r, d[1] / r, d[2] / r)
        L2 = norm((-0.5, 0.55, 0.67))
        s = nn[0] * L2[0] + nn[1] * L2[1] + nn[2] * L2[2]
        idx = 3 if s > 0.72 else (2 if s > 0.25 else (1 if s > -0.35 else 0))
        if face in ('south', 'north') and e['name'] == 'Самоцвет / грань':
            # блик: один тексель слева сверху на передней грани, отсвет снизу справа
            # (на задней грани u идёт справа налево — блик тоже слева сверху)
            ul = u if face == 'south' else 1 - u
            if ul < 0.34 and v < 0.34:
                return GEM[4]
            if ul > 0.66 and v > 0.66:
                return GEM[2]
            return GEM[3]
        return GEM[idx]
    # gold
    if e.get('crescent') and face in ('south', 'north'):
        th = e['th']
        r = math.hypot(p[0] - GX, p[1] - GY)
        t = clamp((r - R_IN) / max(0.3, e['w']), 0, 1)          # 0 внутренний край … 1 внешний
        lit = math.cos(math.radians(th)) * -0.6 + math.sin(math.radians(th)) * 0.8   # к свету — лево-верх
        idx = 2
        if t > 0.5:
            idx = 3
        if t > 0.72 and lit > 0.55:
            idx = 4                                             # внешняя кромка на свету
        if t < 0.3:
            idx = 1 if lit > -0.2 else 2                        # внутренняя кромка — тень (у освещённой стороны)
        if lit < -0.5 and t > 0.3:
            idx -= 1
        if face == 'north':
            idx -= 1
        return GOLD[clamp(idx, 0, 4)]
    if e.get('bezel') and face in ('south', 'north'):
        d = (p[0] - GX, p[1] - GY)
        lit = (-0.6 * d[0] + 0.8 * d[1]) / (math.hypot(*d) or 1)
        idx = 3 if lit > 0.3 else (2 if lit > -0.4 else 1)
        if math.hypot(*d) > A - 0.25 and lit > 0.3:
            idx = 4
        return GOLD[clamp(idx - (face == 'north'), 0, 4)]
    idx = 2 + step
    if face in ('south', 'north', 'east', 'west') and not e.get('prong') and not e.get('crescent'):
        # кольца и обоймы: левая часть светлее, верхний ряд — блик
        idx = 2 + (1 if u < 0.4 else (0 if u < 0.75 else -1)) + (step if step < 0 else 0)
        if v < 0.3:
            idx += 1
        if face in ('east', 'north'):
            idx -= 1
    return GOLD[clamp(idx, 0, 4)]


# ------------------------------------------------------------------------------------------- развёртка UV и текстура
FACES = ('north', 'east', 'south', 'west', 'up', 'down')
NORMALS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0), 'up': (0, 1, 0),
           'down': (0, -1, 0)}


def face_dims(e, f):
    (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
    return {'north': (x2 - x1, y2 - y1), 'south': (x2 - x1, y2 - y1), 'east': (z2 - z1, y2 - y1),
            'west': (z2 - z1, y2 - y1), 'up': (x2 - x1, z2 - z1), 'down': (x2 - x1, z2 - z1)}[f]


def face_point(e, f, u, v):
    (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
    lx = lambda a, b, s: a + (b - a) * s
    if f == 'north':
        return (lx(x2, x1, u), lx(y2, y1, v), z1)
    if f == 'south':
        return (lx(x1, x2, u), lx(y2, y1, v), z2)
    if f == 'east':
        return (x2, lx(y2, y1, v), lx(z2, z1, u))
    if f == 'west':
        return (x1, lx(y2, y1, v), lx(z1, z2, u))
    if f == 'up':
        return (lx(x1, x2, u), y2, lx(z1, z2, v))
    return (lx(x1, x2, u), y1, lx(z2, z1, v))


def hidden(e, f):
    """Грани, которые всегда закрыты соседями (не тратим на них место в текстуре 64×64)."""
    n = e['name']
    if n == 'Древко' and f in ('up', 'down'):
        return True
    if n.startswith('Самоцвет') and f in ('up', 'down', 'east', 'west') and n != 'Самоцвет / тело':
        return False
    return False


def face_pixels(e, f, w, h):
    """Цвета текселей грани w×h с рамкой 1 тексель (рамка = край грани, чтобы мипы не тянули чужой цвет)."""
    n = rot_vec(e, NORMALS[f])
    out = []
    for j in range(-1, h + 1):
        row = []
        for k in range(-1, w + 1):
            u = (clamp(k, 0, w - 1) + 0.5) / w
            v = (clamp(j, 0, h - 1) + 0.5) / h
            row.append(color(e, rot_point(e, face_point(e, f, u, v)), f, n, u, v))
        out.append(tuple(row))
    return tuple(out)


def build_texture():
    """Развёртка в 64×64: одноцветные грани берут общий «образец» цвета (1 тексель в окружении того же цвета),
    одинаковые грани — один и тот же прямоугольник; остальное — укладка «по линии горизонта» с рамкой 1 тексель."""
    blocks = {}            # содержимое блока → [(ei, f)]
    swatches = {}          # цвет → [(ei, f)]
    for ei, e in enumerate(ELEMENTS):
        for f in FACES:
            if hidden(e, f):
                continue
            du, dv = face_dims(e, f)
            w, h = max(1, round(du)), max(1, round(dv))
            px = face_pixels(e, f, w, h)
            cols = {c for row in px for c in row}
            if len(cols) == 1:
                swatches.setdefault(cols.pop(), []).append((ei, f))
            else:
                blocks.setdefault(px, []).append((ei, f))
    items = [(px, len(px[0]), len(px), users, None) for px, users in blocks.items()]
    items += [(((c,) * 3,) * 3, 3, 3, users, c) for c, users in swatches.items()]
    items.sort(key=lambda it: (-it[2], -it[1]))
    sky = [0] * TEX_W      # высота занятого по каждому столбцу
    img = Image.new('RGBA', (TEX_W, TEX_H), (0, 0, 0, 0))
    pix = img.load()
    used = 0
    for px, W, H, users, swatch in items:
        best = None
        for x in range(0, TEX_W - W + 1):
            y = max(sky[x:x + W])
            if y + H <= TEX_H and (best is None or y < best[1]):
                best = (x, y)
        assert best is not None, f'текстура не влезает в {TEX_W}x{TEX_H}'
        x, y = best
        for k in range(W):
            sky[x + k] = y + H
        used = max(used, y + H)
        for j in range(H):
            for k in range(W):
                pix[x + k, y + j] = px[j][k] + (255,)
        for ei, f in users:
            e = ELEMENTS[ei]
            if swatch is not None:
                e.setdefault('uv', {})[f] = [x + 1, y + 1, x + 2, y + 2]
            else:
                e.setdefault('uv', {})[f] = [x + 1, y + 1, x + W - 1, y + H - 1]
    return img, used


# ------------------------------------------------------------------------------------------- вид в руке
# Как у косы: задаём, куда в мире смотрят «верх» модели (+Y, к самоцвету) и «лицо» навершия (+Z — к нему
# обращены самоцвет и полумесяц), и какая точка древка лежит в ладони. Поворот раскладывается в углы XYZ display.
E_ENTITY = Ry(180) @ Sc(-1, -1, 1) @ T(0, -1.501, 0)


def hand_tp(arm):
    return E_ENTITY @ part_matrix((-5, 2, 0), arm) @ Rx(-90) @ Ry(180) @ T(1 / 16, 0.125, -0.625)


HAND_TP = hand_tp((-18, 0, 0))                 # обычная поза (рука чуть вперёд при ходьбе — как у косы)
HAND_TP_CAST = hand_tp((-90, -5.7, 0))         # BOW_AND_ARROW: рука вперёд (взгляд прямо)
HAND_FP = T(0.56, -0.52, -0.72)


def hand_fp_cast(f=0.0):
    """Ванильная цепочка от первого лица для анимации BOW (ItemInHandRenderer), f — натяжение 0..1."""
    return (T(0.56, -0.52, -0.72) @ T(-0.2785682, 0.18344387, 0.15731531) @ Rx(-13.935) @ Ry(35.3) @ Rz(-9.785)
            @ T(0, 0, f * 0.04) @ Sc(1, 1, 1 + 0.2 * f) @ Ry(-45))


HAND_FP_CAST = hand_fp_cast(0.0)


def euler_xyz(R):
    b = math.asin(max(-1.0, min(1.0, R[0, 2])))
    c = math.atan2(-R[0, 1], R[0, 0])
    a = math.atan2(-R[1, 2], R[2, 2])
    return [round(math.degrees(v), 2) for v in (a, b, c)]


def aim(hand, up, face, scale, grip=(8, 0, 8), offset=(0, 0, 0)):
    """up — куда смотрит +Y модели, face — куда +Z (лицо навершия); grip — точка модели в ладони (px)."""
    up = np.array(up, float)
    up /= np.linalg.norm(up)
    fz = np.array(face, float)
    fz -= up * (fz @ up)
    fz /= np.linalg.norm(fz)
    ex = np.cross(up, fz)
    Rw = np.column_stack([ex, up, fz])
    Rd = np.linalg.inv(hand[:3, :3]) @ Rw
    g = np.array(grip) / 16 - 0.5
    off = np.linalg.inv(hand[:3, :3]) @ np.array(offset, float)
    t = (off - Rd @ (scale * g)) * 16
    ang = euler_xyz(Rd)
    chk = (Rx(ang[0]) @ Ry(ang[1]) @ Rz(ang[2]))[:3, :3]
    assert np.abs(chk - Rd).max() < 0.01, (ang, Rd)
    return {'rotation': ang, 'translation': [round(float(v), 2) for v in t], 'scale': [scale] * 3}


def fit(rotation, box=15.4):
    R = (Rx(rotation[0]) @ Ry(rotation[1]) @ Rz(rotation[2]))[:3, :3]
    pts = []
    for e in ELEMENTS:
        for x in (e['from'][0], e['to'][0]):
            for y in (e['from'][1], e['to'][1]):
                for z in (e['from'][2], e['to'][2]):
                    pts.append(R @ (np.array(rot_point(e, (x, y, z))) - 8))
    pts = np.array(pts)
    mn, mx = pts.min(0), pts.max(0)
    sc = round(box / max(mx[0] - mn[0], mx[1] - mn[1]), 4)
    c = (mn + mx) / 2 * sc
    return {'rotation': rotation, 'translation': [round(-c[0], 2), round(-c[1], 2), 0], 'scale': [sc] * 3}


TP_SCALE, FP_SCALE = 0.66, 0.40
DISPLAY = {
    # от третьего лица: стоит по диагонали, верх вперёд, лицо навершия — вперёд
    'thirdperson_righthand': aim(HAND_TP, (0.10, 1, 0.28), (-0.1, -0.1, 1), TP_SCALE),
    # от первого лица: навершие справа вверху, не закрывает прицел; лицо — к игроку и влево
    'firstperson_righthand': aim(HAND_FP, (-0.30, 1, 0.10), (-0.55, 0.0, 1), FP_SCALE, offset=(0.15, -0.12, 0)),
    'gui': fit([0, 0, -45]),
    'ground': {'translation': [0, 2, 0], 'scale': [0.3, 0.3, 0.3]},
    'fixed': fit([0, 0, -45], 15),
    'head': {'rotation': [0, 0, 0], 'translation': [0, 6, 0], 'scale': [0.6, 0.6, 0.6]},
}
# «Колдует» (анимация BOW, предмет используется): от третьего лица — почти вертикально с наклоном вперёд ~20°,
# самоцвет над головой впереди лица; от первого лица — вперёд-вверх, самоцвет правее и выше центра экрана.
CAST_GRIP = (8, 4, 8)
CAST_DISPLAY = {
    'thirdperson_righthand': aim(HAND_TP_CAST, (0.0, 1, 0.36), (0.0, -0.36, 1), TP_SCALE, grip=CAST_GRIP),
    'firstperson_righthand': aim(HAND_FP_CAST, (-0.18, 1, -0.55), (-0.3, 0.55, 1), FP_SCALE, grip=CAST_GRIP,
                                 offset=(0.02, -0.02, 0.02)),
}


# ------------------------------------------------------------------------------------------- запись
def write_bbmodel(img):
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    groups, outliner, by_group, elements = [], [], {}, []
    gnames = {'gold': 'Золото', 'wood': 'Древко', 'cloth': 'Лента', 'gem': 'Самоцвет'}
    for e in ELEMENTS:
        uid = str(uuid.uuid5(uuid.NAMESPACE_URL, 'moon_staff/' + e['name']))
        rot = [0, 0, 0]
        if e['rot']:
            rot['xyz'.index(e['rot']['axis'])] = e['rot']['angle']
            origin = e['rot']['origin']
        else:
            origin = [round((a + b) / 2, 3) for a, b in zip(e['from'], e['to'])]
        faces = {f: ({'uv': e['uv'][f], 'texture': 0} if f in e['uv'] else {'uv': [0, 0, 0, 0], 'texture': None})
                 for f in FACES}
        elements.append({'name': e['name'], 'box_uv': False, 'render_order': 'default', 'rescale': False,
                         'locked': False, 'shade_direction_override': '', 'light_emission': e['light'], 'export': True,
                         'shade': e['shade'], 'scope': 0, 'allow_mirror_modeling': True, 'from': e['from'],
                         'to': e['to'], 'autouv': 0,
                         'color': {'gold': 1, 'wood': 3, 'cloth': 0, 'gem': 5}[e['part']],
                         'rotation': rot, 'origin': origin, 'faces': faces, 'type': 'cube', 'uuid': uid})
        by_group.setdefault(gnames[e['part']], []).append(uid)
    for gname, kids in by_group.items():
        gid = str(uuid.uuid5(uuid.NAMESPACE_URL, 'moon_staff/group/' + gname))
        groups.append({'name': gname, 'uuid': gid, 'export': True, 'locked': False, 'scope': 0, 'selected': False,
                       'visibility': True, 'origin': [8, 8, 8], 'rotation': [0, 0, 0], 'color': 0, 'children': [],
                       'reset': False, 'shade': True, 'mirror_uv': False, 'autouv': 0, 'isOpen': False})
        outliner.append({'uuid': gid, 'isOpen': False, 'children': kids})
    W, H = img.size
    tex = {'name': MID + '.png', 'path': '', 'folder': 'item', 'namespace': NS, 'id': '0', 'group': '', 'scope': 0,
           'width': W, 'height': H, 'uv_width': W, 'uv_height': H, 'particle': True, 'use_as_default': False,
           'layers_enabled': False, 'sync_to_project': '', 'file_format': 'png', 'render_mode': 'default',
           'render_sides': 'auto', 'wrap_mode': 'limited', 'pbr_channel': 'color', 'fps': 7, 'force_translucent': False,
           'frame_time': 1, 'frame_order_type': 'loop', 'frame_order': '', 'frame_interpolate': False, 'visible': True,
           'internal': True, 'saved': False, 'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, 'moon_staff/texture')),
           'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}
    bb = {'meta': {'format_version': '5.0', 'model_format': 'java_block', 'box_uv': False}, 'name': MID, 'parent': '',
          'ambientocclusion': False, 'front_gui_light': False, 'visible_box': [1, 1, 0], 'variable_placeholders': '',
          'variable_placeholder_buttons': [], 'unhandled_root_fields': {}, 'resolution': {'width': W, 'height': H},
          'elements': elements, 'groups': groups, 'outliner': outliner, 'textures': [tex], 'display': DISPLAY}
    os.makedirs(os.path.dirname(BB), exist_ok=True)
    with open(BB, 'w', encoding='utf-8') as fh:
        json.dump(bb, fh, ensure_ascii=False, indent=1)


def write_cast_and_item():
    mdir = os.path.join(ASSETS, NS, 'models', 'item')
    base = json.load(open(os.path.join(mdir, MID + '.json'), encoding='utf-8'))
    # своя копия кубов: у самоцвета свет 15 вместо 10 (как *_held у косы); вид в руке — поза «колдует»
    els = json.loads(json.dumps(base['elements']))
    for el in els:
        if el.get('light_emission') == GEM_LIGHT:
            el['light_emission'] = 15
    cast = {'parent': f'{NS}:item/{MID}', 'elements': els, 'display': CAST_DISPLAY}
    with open(os.path.join(mdir, MID + '_cast.json'), 'w', encoding='utf-8') as fh:
        json.dump(cast, fh, indent=1, ensure_ascii=False)
    item = {'hand_animation_on_swap': False,
            'model': {'type': 'minecraft:select', 'property': 'minecraft:display_context',
                      'cases': [{'when': ['gui', 'ground', 'fixed', 'head', 'none'],
                                 'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{MID}'}}],
                      'fallback': {'type': 'minecraft:condition', 'property': 'minecraft:using_item',
                                   'on_true': {'type': 'minecraft:model', 'model': f'{NS}:item/{MID}_cast'},
                                   'on_false': {'type': 'minecraft:model', 'model': f'{NS}:item/{MID}'}}}}
    idir = os.path.join(ASSETS, NS, 'items')
    os.makedirs(idir, exist_ok=True)
    with open(os.path.join(idir, MID + '.json'), 'w', encoding='utf-8') as fh:
        json.dump(item, fh, indent=1)


# ------------------------------------------------------------------------------------------- превью
def _model(ref):
    m = load_model(ASSETS, ref)
    texs = {v: tex_image(ASSETS, v) for v in m['textures'].values() if not v.startswith('#')}
    return m, texs, quads(m, texs)


def render_first_custom(model, qs, texs, hand, ctx='firstperson_righthand', w=640, h=360, under=None):
    c = Canvas(w, h, (120, 160, 210))
    if under is not None:
        c.rgb[:] = np.asarray(under, np.float32)[..., :3]
    draw(c, qs, hand @ display(model, ctx), texs, persp(w, h))
    # прицел
    c.rgb[h // 2, w // 2 - 6:w // 2 + 7] = (255, 255, 255)
    c.rgb[h // 2 - 6:h // 2 + 7, w // 2] = (255, 255, 255)
    return c.image()


def render_third_cast(model, qs, texs, cam_yaw, size=640):
    """Как render_third, но обе руки подняты (BOW_AND_ARROW) и вид в руке — cast."""
    c = Canvas(size, size, (40, 38, 48))
    V = Rx(10) @ Ry(cam_yaw)
    pr = ortho(size, size, (0, 1.4, 0), size / 3.6, V)
    for i, (pivot, rot, fr, to, col) in enumerate(player_parts(-90, -5.7, 0)):
        if i == 3:   # левая рука тоже поднята при натяжении лука
            rot = (-90, 28.6, 0)
        draw_box(c, [v / 16 for v in fr], [v / 16 for v in to], E_ENTITY @ part_matrix(pivot, rot), pr, col)
    draw(c, qs, HAND_TP_CAST @ display(model, 'thirdperson_righthand'), texs, pr)
    return c.image()


def circle_backdrop(sprites, c, t, w=640, h=360, forward=2.4, diameter=3.0):
    """Фон первого лица (небо) с кругом впереди: круг перпендикулярен взгляду, по центру экрана."""
    f = 1 / math.tan(math.radians(35))
    rpx = f * (diameter / 2) / forward * h / 2
    bg = np.ones((h, w, 3)) * np.array([120, 160, 210]) / 255.0
    ys, xs = np.mgrid[0:h, 0:w]
    lx = (xs + 0.5 - w / 2) / (2 * rpx) + 0.5
    ly = (ys + 0.5 - h / 2) / (2 * rpx) + 0.5
    inside = (lx >= 0) & (lx <= 1) & (ly >= 0) & (ly <= 1)
    for name in ('ring', 'runes', 'hexagram', 'core'):
        col = FX.shade(FX.KINDS[name], sprites[name], np.clip(lx, 0, 1), np.clip(ly, 0, 1), c, t)
        col[..., 3] *= inside
        bg = FX.over(bg, col)
    return (np.clip(bg, 0, 1) * 255).astype(np.float32)


def _label(img, text):
    from PIL import ImageDraw
    ImageDraw.Draw(img).text((8, 6), text, fill=(200, 230, 255))
    return img


def barrier_previews(sprites):
    """Барьер (K10 hex_cell) спереди, как видит маг: собран (два момента мерцания + ячейка ×4), сборка по тикам
    удержания, уровни L одной ячейки."""
    ppm = 128.0
    im = Image.new('RGB', (512 * 3, 640))
    im.paste(_label(FX.render_barrier(sprites, t=0.0, ppm=ppm), 'formed L=8, t=0.0 s'), (0, 0))
    im.paste(_label(FX.render_barrier(sprites, t=1.3, ppm=ppm), 'formed L=8, t=1.3 s'), (512, 0))
    one = FX.render_cells(sprites, [(0.0, 0.0, FX.display_scale(0.5, 0.04), FX.barrier_R(12, 8))], 0.6,
                          512, 640, ppm=ppm * 4)
    im.paste(_label(one, 'one cell x4, t=0.6 s'), (1024, 0))
    im.save(os.path.join(PREVIEW, 'moon_staff_barrier.png'))
    im = Image.new('RGB', (512 * 4, 640))
    for i, held in enumerate((4, 10, 16, 20)):
        fr = FX.render_barrier_assembly(sprites, held, t=held / 20.0, ppm=ppm)
        im.paste(_label(fr, f'held {held} ticks' + (' - formed (L=9 flash)' if held >= 20 else '')), (512 * i, 0))
    im.save(os.path.join(PREVIEW, 'moon_staff_barrier_assembly.png'))
    im = Image.new('RGB', (256 * 5, 288))
    Q = FX.display_scale(0.5, 0.04)
    for i, L in enumerate((2, 4, 6, 8, 9)):
        fr = FX.render_cells(sprites, [(0.0, 0.0, Q, FX.barrier_R(6, L))], 0.6, 256, 288, ppm=240.0)
        im.paste(_label(fr, f'L={L}'), (256 * i, 0))
    im.save(os.path.join(PREVIEW, 'moon_staff_barrier_states.png'))


def previews(sprites):
    os.makedirs(PREVIEW, exist_ok=True)
    m, texs, qs = _model(f'{NS}:item/{MID}')
    render_gui(m, qs, texs).save(os.path.join(PREVIEW, 'moon_staff_gui.png'))
    sheet = Image.new('RGB', (512 * 4, 512))
    for i, yaw in enumerate((0, 35, 90, 180)):
        sheet.paste(render_turn(m, qs, texs, yaw), (512 * i, 0))
    sheet.save(os.path.join(PREVIEW, 'moon_staff_angles.png'))
    render_turn(m, qs, texs, 0, 0, size=768).save(os.path.join(PREVIEW, 'moon_staff_front.png'))
    render_turn(m, qs, texs, 20, 8, size=768, night=True).save(os.path.join(PREVIEW, 'moon_staff_night.png'))
    tp = Image.new('RGB', (640 * 3, 640))
    for i, yaw in enumerate((-35, 90, 160)):
        tp.paste(render_third(m, qs, texs, yaw), (640 * i, 0))
    tp.save(os.path.join(PREVIEW, 'moon_staff_thirdperson.png'))
    render_first_custom(m, qs, texs, HAND_FP).save(os.path.join(PREVIEW, 'moon_staff_firstperson.png'))
    # поза «колдует»
    mc, texc, qc = _model(f'{NS}:item/{MID}_cast')
    tp = Image.new('RGB', (640 * 3, 640))
    for i, yaw in enumerate((-35, 90, 160)):
        tp.paste(render_third_cast(mc, qc, texc, yaw), (640 * i, 0))
    tp.save(os.path.join(PREVIEW, 'moon_staff_cast_thirdperson.png'))
    fp = Image.new('RGB', (640 * 2, 360 * 2))
    fp.paste(render_first_custom(mc, qc, texc, hand_fp_cast(0.0)), (0, 0))
    fp.paste(render_first_custom(mc, qc, texc, hand_fp_cast(1.0)), (640, 0))
    fp.paste(render_first_custom(mc, qc, texc, hand_fp_cast(1.0), under=circle_backdrop(sprites, 0.5, 0.3)), (0, 360))
    fp.paste(render_first_custom(mc, qc, texc, hand_fp_cast(1.0), under=circle_backdrop(sprites, 1.0, 0.7)), (640, 360))
    fp.save(os.path.join(PREVIEW, 'moon_staff_cast_firstperson.png'))
    render_turn(mc, qc, texc, 20, 8, size=512, night=True).save(os.path.join(PREVIEW, 'moon_staff_cast_night.png'))
    # эффекты: круг при заряде 0.15 / 0.5 / 1.0 в моменты 0 и 0.4 с; луч разной длины; вспышка
    row = Image.new('RGB', (256 * 6, 256))
    for i, (c, t) in enumerate([(c, t) for c in (0.15, 0.5, 1.0) for t in (0.0, 0.4)]):
        row.paste(FX.render_circle(sprites, c, t), (256 * i, 0))
    row.save(os.path.join(PREVIEW, 'moon_staff_circle_charge.png'))
    beams = Image.new('RGB', (640, 128 * 3))
    for i, R in enumerate((16, 120, 230)):
        beams.paste(FX.render_beam_side(sprites, R, 0.25 * i), (0, 128 * i))
    beams.save(os.path.join(PREVIEW, 'moon_staff_beam.png'))
    fl = Image.new('RGB', (512, 256))
    fl.paste(FX.render_flare(sprites, 0.0), (0, 0))
    fl.paste(FX.render_flare(sprites, 0.1), (256, 0))
    fl.save(os.path.join(PREVIEW, 'moon_staff_flare.png'))
    barrier_previews(sprites)
    sheet = Image.new('RGBA', (130 * len(FX.KINDS), 130), (20, 22, 30, 255))
    for i, n in enumerate(FX.KINDS):
        sheet.alpha_composite(Image.fromarray(sprites[n], 'RGBA'), (130 * i + 1, 1))
    sheet.save(os.path.join(PREVIEW, 'moon_staff_fx_sprites.png'))


def main():
    img, used = build_texture()
    write_bbmodel(img)
    subprocess.run([sys.executable, '-I', os.path.join(HERE, 'bbjava2json.py'), BB, ASSETS, NS, MID], check=True)
    write_cast_and_item()
    sprites = FX.write_all()
    if '--no-preview' not in sys.argv:
        previews(sprites)
    ys = [rot_point(e, (x, y, z))[1] for e in ELEMENTS for x in (e['from'][0], e['to'][0])
          for y in (e['from'][1], e['to'][1]) for z in (e['from'][2], e['to'][2])]
    print(f'кубов {len(ELEMENTS)} | текстура {TEX_W}x{TEX_H} (занято строк {used}) | y {min(ys):.2f}..{max(ys):.2f}')


if __name__ == '__main__':
    main()
