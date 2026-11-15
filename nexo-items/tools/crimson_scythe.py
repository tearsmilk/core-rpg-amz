#!/usr/bin/env python3
"""«Багровая коса» — объёмная модель Java (кубы) и пиксельная текстура, собранные кодом.

    python3 tools/crimson_scythe.py            (из папки nexo-items)

Пишет:
  source/Багровая-коса.bbmodel                               — проект Blockbench (java_block), его можно править;
  pack/assets/azerus/models/item/crimson_scythe.json         — модель (через tools/bbjava2json.py из .bbmodel);
  pack/assets/azerus/textures/item/crimson_scythe.png        — текстура;
  pack/assets/azerus/models/item/crimson_scythe_swing_{1,2,3}.json — кадры замаха (та же модель, другой вид в руке);
  pack/assets/azerus/items/crimson_scythe.json               — определение предмета: кадр по custom_model_data.

Правила Java-модели: поворот куба только по одной оси и только −45/−22.5/0/22.5/45, координаты в −16..32.
Лезвие — дуга из повёрнутых на 22.5° отрезков в три слоя (обух 3 px, середина 2 px, режущая кромка 1 px, у кромки
light_emission 15), древко — ломаная из отрезков 2×2 с узлами на изломах. Цвет каждого текселя считается по точке
модели, в которую он попадает (с учётом поворота куба), поэтому перекрытия соседних отрезков совпадают по цвету.
Свет — сверху слева, пять тонов на материал со сдвигом оттенка (texture-shading.md).
"""
import base64, io, json, math, os, subprocess, sys, uuid

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
NS, MID = 'azerus', 'crimson_scythe'
BB = os.path.join(ROOT, 'source', 'Багровая-коса.bbmodel')
ASSETS = os.path.join(ROOT, 'pack', 'assets')

# ------------------------------------------------------------------------------------------- палитра (с референса)
hexc = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
BLADE = [hexc(h) for h in ('#2e0a14', '#4c1220', '#84182a', '#c41a28', '#ee4636', '#f8963a', '#fbe08a')]
# 0 контур/глубина, 1 тень, 2 тёмный, 3 основа, 4 светлый, 5 блик (оранж.), 6 вершина блика (бледно-жёлтый)
WOOD = [hexc(h) for h in ('#1c120c', '#36291e', '#4e4230', '#6e6248', '#8c8260', '#aca274')]
CLOTH = [hexc(h) for h in ('#36081a', '#661424', '#9c1828', '#d01c2a', '#f2343e')]
FIRE = [hexc(h) for h in ('#d42a26', '#ee5a2a', '#f8963a', '#fbd36a', '#fff2b0')]
LIGHT = (-0.55, 0.75, 0.35)  # свет сверху слева, чуть спереди


def norm(v):
    l = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / l for c in v)


LIGHT = norm(LIGHT)

# ------------------------------------------------------------------------------------------- геометрия лезвия
# Контуры лезвия сняты с референса (x = (ix−130)/10+8, y = 31−(iy−70)/10): внешний — режущая кромка, внутренний — обух.
OUTER = [(15.0, 20.5), (14.4, 24.6), (11.8, 28.0), (7.0, 29.6), (2.2, 28.4), (-0.8, 25.4), (-2.2, 21.4), (-2.9, 17.6), (-3.6, 14.0)]
# Ширина лезвия по дуге (угол от центра дуги → ширина): массивное у верха, сужается к острию.
# (после отзыва в игре: лезвие уже и тоньше — было слишком массивным от первого лица)
WIDTH = [(8, 2.5), (40, 3.3), (90, 4.1), (130, 3.9), (165, 3.0), (190, 2.0), (204, 1.0), (214, 0.5)]


def fit_circle(pts):
    # МНК: x²+y² + Dx + Ey + F = 0
    import numpy as np
    a = np.array([[x, y, 1.0] for x, y in pts])
    b = np.array([-(x * x + y * y) for x, y in pts])
    d, e, f = np.linalg.lstsq(a, b, rcond=None)[0]
    cx, cy = -d / 2, -e / 2
    return cx, cy, math.sqrt(cx * cx + cy * cy - f)


CX, CY, _R = fit_circle(OUTER)


def polar(x, y):
    t = math.degrees(math.atan2(y - CY, x - CX)) % 360
    if t > 300:
        t -= 360
    return math.hypot(x - CX, y - CY), t


def profile(pts):
    pp = sorted((polar(x, y)[1], polar(x, y)[0]) for x, y in pts)
    return pp


P_OUT = profile(OUTER)
TH0 = P_OUT[0][0]    # пятка (справа)
TH1 = P_OUT[-1][0]   # остриё (слева внизу)


def interp(pp, th):
    if th <= pp[0][0]:
        return pp[0][1]
    for (a, ra), (b, rb) in zip(pp, pp[1:]):
        if a <= th <= b:
            return ra + (rb - ra) * (th - a) / (b - a)
    return pp[-1][1]


def r_out(th):
    return interp(P_OUT, th)


def r_in(th):
    # к острию обух сходится к кромке
    return interp(P_OUT, th) - interp(WIDTH, th)


# ------------------------------------------------------------------------------------------- кубы
ELEMENTS = []


def snap(a):
    return round(a * 2) / 2 if abs(a * 2 - round(a * 2)) < 1e-6 else round(a, 3)


def add(name, part, frm, to, rot=None, light=0, **extra):
    # лезвие и искры — без затенения граней (цвет ровно как в текстуре, объём нарисован тонами граней)
    e = {'name': name, 'part': part, 'from': [round(v, 3) for v in frm], 'to': [round(v, 3) for v in to],
         'rot': rot, 'light': light, 'shade': light < 15 and part not in ('blade', 'fire')}
    e.update(extra)
    for v in e['from'] + e['to']:
        assert -16 <= v <= 32, (name, v)
    ELEMENTS.append(e)
    return e


def bar(name, part, cx, cy, angle, length, width, z0, z1, light=0, **extra):
    """Брусок длиной length вдоль направления angle (кратно 22.5°), шириной width поперёк, в слое z0..z1."""
    a = angle % 180
    assert abs(a / 22.5 - round(a / 22.5)) < 1e-6, angle
    if a <= 45:
        phi, along_x = a, True
    elif a >= 135:
        phi, along_x = a - 180, True
    else:
        phi, along_x = a - 90, False
    lx, ly = (length, width) if along_x else (width, length)
    rot = {'angle': phi, 'axis': 'z', 'origin': [round(cx, 3), round(cy, 3), 8]} if phi else None
    return add(name, part, (cx - lx / 2, cy - ly / 2, z0), (cx + lx / 2, cy + ly / 2, z1), rot, light, **extra)


# Лезвие: отрезки дуги через 22.5°; три слоя по радиусу — обух (толще), середина, кромка (светится).
STEP = 22.5
HALF = STEP / 2
k0 = math.ceil((TH0 + 3) / HALF)
k1 = math.floor((TH1 - 1) / HALF)
for k in range(k0, k1 + 1):
    th = k * HALF
    ro, ri = r_out(th), r_in(th)
    w = ro - ri
    tip = k == k1
    seg_len = 2 * math.pi * ro * HALF / 360 + (1.2 if not tip else 0.6)
    # толщина слоёв по радиусу: обух 45 %, середина 35 %, кромка остальное (не тоньше 0.8)
    edge_w = max(0.6, w * 0.24)
    back_w = (w - edge_w) * 0.55
    mid_w = w - edge_w - back_w
    rad = math.radians(th)
    ux, uy = math.cos(rad), math.sin(rad)
    # направление отрезка — касательная, округлённая до 22.5° (нечётные через один в разные стороны)
    tangent = round((th + 90) / STEP + (0.01 if k % 4 == 1 else -0.01)) * STEP
    eps = 0.01 * (k % 2)  # соседние отрезки чуть разнесены по z — без мерцания на стыках
    def at(r):
        return CX + ux * r, CY + uy * r
    zb = 0.75 if not tip else 0.5  # обух 1.5 px, середина 1 px, кромка 0.5 px
    x, y = at(ri + back_w / 2)
    bar(f'Лезвие {k} / обух', 'blade', x, y, tangent, seg_len * (ri + back_w / 2) / ro, back_w, 8 - zb - eps, 8 + zb + eps,
        th=th, layer='back')
    x, y = at(ri + back_w + mid_w / 2)
    bar(f'Лезвие {k} / середина', 'blade', x, y, tangent, seg_len * (ri + back_w + mid_w / 2) / ro, mid_w,
        7.5 - eps, 8.5 + eps, th=th, layer='mid', light=4)
    x, y = at(ro - edge_w / 2)
    bar(f'Лезвие {k} / кромка', 'blade', x, y, tangent, seg_len, edge_w, 7.75 - eps, 8.25 + eps, light=15, th=th,
        layer='edge')


# Древко: ломаная снизу вверх, направления — кратно 22.5°, на изломах узлы.
SHAFT = [(15.6, -11.5), (14.8, -7.8), (15.2, -4.0), (14.0, 0.0), (11.6, 3.4), (9.2, 7.6), (8.2, 12.6), (8.6, 18.2),
         (10.2, 24.0)]
joints = []
x0, y0 = SHAFT[0]
for i, (x1, y1) in enumerate(SHAFT[1:], 1):
    ang = math.degrees(math.atan2(y1 - y0, x1 - x0))
    q = round(ang / STEP) * STEP
    L = math.hypot(x1 - x0, y1 - y0)
    ex, ey = x0 + math.cos(math.radians(q)) * L, y0 + math.sin(math.radians(q)) * L
    w = 1.5 if i < 7 else 1.3
    bar(f'Древко {i}', 'shaft', (x0 + ex) / 2, (y0 + ey) / 2, q, L + 0.5, w, 8 - w / 2, 8 + w / 2, a=(x0, y0), b=(ex, ey))
    joints.append((ex, ey))
    x0, y0 = ex, ey
for i, (x, y) in enumerate(joints[:-1]):
    s = 2.0 if i in (1, 3, 5) else 1.75
    add(f'Узел {i + 1}', 'knot', (x - s / 2, y - s / 2, 8 - s / 2), (x + s / 2, y + s / 2, 8 + s / 2), c=(x, y))
# комель: развилка корня внизу
add('Комель', 'knot', (14.6, -12.9, 7.1), (16.6, -10.9, 8.9), c=(15.6, -11.9))
bar('Корень', 'shaft', 13.2, -12.6, 157.5, 3.0, 0.9, 7.55, 8.45, a=(14.6, -12.0), b=(11.8, -13.2))

# Перекрещенная ветка у головы (светлая, как на референсе) и сучок на ней.
bar('Ветка', 'twig', 9.7, 18.4, 33.75 - 11.25, 10.5, 1.0, 8.4, 9.2, a=(5.0, 16.0), b=(14.4, 20.8))
bar('Сучок', 'twig', 5.6, 18.3, 112.5, 3.0, 0.8, 8.5, 9.1, a=(5.9, 16.9), b=(5.1, 19.7))

# Красная обмотка: лента вдоль древка, перевязки сверху и снизу, яркая вставка.
bar('Перевязка верх', 'cloth', 9.4, 7.2, 157.5, 2.6, 1.0, 7.1, 8.9, a=(10.8, 7.8), b=(8.0, 6.6), tone=2)
bar('Лента', 'cloth', 9.7, 2.0, 112.5, 8.8, 0.8, 8.7, 9.2, a=(8.0, 6.1), b=(11.4, -2.1), tone=3)
bar('Лента / блик', 'cloth', 9.1, 3.3, 112.5, 3.4, 0.5, 9.15, 9.35, a=(8.5, 4.9), b=(9.8, 1.8), tone=4, light=10)
bar('Перевязка низ', 'cloth', 12.6, -2.6, 22.5, 3.0, 1.0, 7.1, 8.9, a=(11.0, -3.2), b=(14.3, -1.9), tone=1)

# Искры: жёлтый язычок над лезвием и уголёк справа от головы — светятся.
bar('Язычок / низ', 'fire', 1.6, 30.4, 22.5, 3.6, 1.0, 7.6, 8.4, light=15, tone=3)
bar('Язычок / верх', 'fire', 4.4, 31.3, 0, 2.6, 1.0, 7.6, 8.4, light=15, tone=4)
add('Язычок / кончик', 'fire', (5.7, 30.9, 7.6), (6.7, 31.9, 8.4), light=15, tone=1)
add('Уголёк', 'fire', (16.6, 20.6, 7.6), (17.4, 22.8, 8.4), light=15, tone=3)
add('Уголёк / верх', 'fire', (17.0, 23.0, 7.7), (17.7, 23.7, 8.3), light=15, tone=2)
add('Уголёк / низ', 'fire', (17.4, 20.0, 7.7), (18.3, 20.8, 8.3), light=15, tone=1)


# ------------------------------------------------------------------------------------------- цвет текселя
def rot_point(e, p):
    r = e['rot']
    if not r:
        return p
    o = r['origin']
    a = math.radians(r['angle'])
    c, s = math.cos(a), math.sin(a)
    x, y = p[0] - o[0], p[1] - o[1]
    return (o[0] + c * x - s * y, o[1] + s * x + c * y, p[2])


def rot_vec(e, n):
    r = e['rot']
    if not r:
        return n
    a = math.radians(r['angle'])
    c, s = math.cos(a), math.sin(a)
    return (c * n[0] - s * n[1], s * n[0] + c * n[1], n[2])


def clamp(i, lo, hi):
    return max(lo, min(hi, i))


def h01(*v):
    """Детерминированный «хэш» 0..1 — для узора кластерами, не для шума по пикселю."""
    x = math.sin(sum(c * k for c, k in zip(v, (12.9898, 78.233, 37.719))) ) * 43758.5453
    return x - math.floor(x)


def blade_tone(e, p, face):
    r, th = polar(p[0], p[1])
    ro, ri = r_out(th), r_in(th)
    t = clamp((r - ri) / max(0.1, ro - ri), 0, 1)
    nx, ny = math.cos(math.radians(th)), math.sin(math.radians(th))
    lit = nx * LIGHT[0] + ny * LIGHT[1]  # −1..1: кромка смотрит к свету (верх, лево) или от него
    layer = e['layer']
    along = (th - TH0) / (TH1 - TH0)  # 0 пятка … 1 остриё
    if layer == 'edge':
        # кромка: ярко-алая, на освещённой части оранжевая, бледно-жёлтые блики кластерами
        idx = 4
        if lit > 0.25 and t > 0.3:
            idx = 5
        if lit > 0.55 and t > 0.55 and (int((th + 4) / 9) % 3 == 0):
            idx = 6
        if face == 'down' or (face == 'north' and idx > 4):
            idx -= 1
        return BLADE[idx]
    # обух и середина: поперёк — от тёмного обуха к светлой кромке, ступенями; границы ступеней гуляют по дуге
    wob = 0.08 * math.sin(math.radians(th * 3.0)) + 0.06 * math.sin(math.radians(th * 7.0 + 40))
    if layer == 'drip':
        return BLADE[1]
    if layer == 'back':
        # обух: тёмно-бордовая линия у самого края, дальше основа; на освещённом верху светлее
        idx = 2 if t + wob < 0.3 else 3
        if t < 0.12:
            idx = 1 if along > 0.1 else 2
        if lit > 0.5 and t + wob > 0.55:
            idx = 4
    else:
        # середина: алая основа со светлыми прядями
        idx = 4 if t + wob > 0.45 or lit > 0.35 else 3
        if lit < -0.35 and t + wob < 0.4:
            idx = 2
    # пряди вдоль дуги: светлые оранжевые и тёмные бордовые чередуются (как «перья» на референсе)
    ph = (th / 5.0 + t * 2.3) % 6
    if layer == 'mid' and 0.1 < along < 0.85:
        if ph < 1.0 and lit > 0.45:
            idx = 5
        elif 3.0 < ph < 3.8:
            idx = 3
    if layer == 'back' and 0.1 < along < 0.9 and t > 0.25:
        if 1.5 < ph < 2.4:
            idx = 2
        elif 4.5 < ph < 5.2 and lit > 0.0:
            idx = 4
    return BLADE[clamp(idx, 0, 6)]


def stick_tone(e, p, ramp, base):
    ax, ay = e['a']
    bx, by = e['b']
    dx, dy = bx - ax, by - ay
    L = math.hypot(dx, dy) or 1
    ux, uy = dx / L, dy / L
    s = (p[0] - ax) * ux + (p[1] - ay) * uy        # вдоль
    side = (p[0] - ax) * (-uy) + (p[1] - ay) * ux  # поперёк: + слева (при ходе снизу вверх)
    if uy < 0:
        side = -side
    lit_side = side > 0.0
    # «витая» древесина: светлая прядь переходит со стороны на сторону
    twist = math.sin((s + p[1] * 0.3) * 0.9)
    idx = base + (1 if lit_side else -1) * (1 if twist > -0.35 else 0)
    if not lit_side and twist < -0.6:
        idx = base
    # тёмные зарубки коры кластерами
    if (int(s * 1.0 + p[1]) % 7) == 3 and not lit_side:
        idx = base - 2
    return ramp[clamp(idx, 0, len(ramp) - 1)]


def color(e, p, face, n):
    part = e['part']
    shade = n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]
    step = 0 if face in ('south', 'north') else (1 if shade > 0.45 else (-1 if shade < -0.15 else 0))
    if part == 'blade':
        c = blade_tone(e, p, face)
        ramp = BLADE
    elif part == 'shaft':
        base = 4 if p[1] > 9 else (3 if p[1] > 1 else 2)
        c = stick_tone(e, p, WOOD, base)
        ramp = WOOD
    elif part == 'twig':
        c = stick_tone(e, p, WOOD, 4)
        ramp = WOOD
    elif part == 'knot':
        cx, cy = e['c']
        d = (p[0] - cx) * LIGHT[0] + (p[1] - cy) * LIGHT[1]
        base = 4 if cy > 9 else (3 if cy > 1 else 2)
        idx = base + (1 if d > 0.5 else (-1 if d < -0.4 else 0))
        if face == 'south' and abs(p[0] - cx) < 0.5 and abs(p[1] - cy) < 0.5:
            idx = base - 1  # сердцевина сучка
        c = WOOD[clamp(idx, 0, 5)]
        ramp = WOOD
    elif part == 'cloth':
        idx = e.get('tone', 3)
        if 'a' in e:
            ax, ay = e['a']
            if int(abs(p[0] - ax) + abs(p[1] - ay)) % 3 == 2:
                idx -= 1  # складки
        c = CLOTH[clamp(idx, 0, 4)]
        ramp = CLOTH
    else:  # fire
        c = FIRE[clamp(e.get('tone', 2), 0, 4)]
        ramp = FIRE
    if step:
        i = ramp.index(c) if c in ramp else 2
        c = ramp[clamp(i + step, 0, len(ramp) - 1)]
    return c


# ------------------------------------------------------------------------------------------- развёртка UV и текстура
FACES = ('north', 'east', 'south', 'west', 'up', 'down')


def face_dims(e, f):
    (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
    return {'north': (x2 - x1, y2 - y1), 'south': (x2 - x1, y2 - y1), 'east': (z2 - z1, y2 - y1),
            'west': (z2 - z1, y2 - y1), 'up': (x2 - x1, z2 - z1), 'down': (x2 - x1, z2 - z1)}[f]


def face_point(e, f, u, v):
    """u, v ∈ 0..1 по грани (как развёртка Minecraft: u вправо, v вниз) → точка куба до поворота."""
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
    return (lx(x1, x2, u), y1, lx(z2, z1, v))  # down


NORMALS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0), 'up': (0, 1, 0),
           'down': (0, -1, 0)}


def build_texture():
    rects = []
    for ei, e in enumerate(ELEMENTS):
        for f in FACES:
            du, dv = face_dims(e, f)
            rects.append((ei, f, max(1, round(du)), max(1, round(dv))))
    W = 128
    # полки по высоте
    order = sorted(range(len(rects)), key=lambda i: (-rects[i][3], -rects[i][2]))
    x = y = shelf = 0
    pos = {}
    for i in order:
        _, _, w, h = rects[i]
        if x + w + 2 > W:
            x, y, shelf = 0, y + shelf, 0
        pos[i] = (x + 1, y + 1)
        x += w + 2
        shelf = max(shelf, h + 2)
    H = 1
    while H < y + shelf:
        H *= 2
    H = max(H, W)
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    px = img.load()
    for i, (ei, f, w, h) in enumerate(rects):
        e = ELEMENTS[ei]
        ox, oy = pos[i]
        n = rot_vec(e, NORMALS[f])
        for j in range(-1, h + 1):
            for k in range(-1, w + 1):
                u = (clamp(k, 0, w - 1) + 0.5) / w
                v = (clamp(j, 0, h - 1) + 0.5) / h
                p = rot_point(e, face_point(e, f, u, v))
                c = color(e, p, f, n)
                px[ox + k, oy + j] = c + (255,)
        e.setdefault('uv', {})[f] = [ox, oy, ox + w, oy + h]
    return img, W, H


# ------------------------------------------------------------------------------------------- вид в руке и кадры замаха
# Вид в руке считается, а не подбирается руками: задаём, куда в мире (от третьего лица — относительно игрока,
# от первого — относительно камеры) смотрят «верх» модели (+Y, древко к лезвию) и «лезвие» (−X), и какая точка
# древка лежит в ладони; поворот раскладывается в углы XYZ display, сдвиг — так, чтобы хват попал в ладонь.
sys.path.insert(0, HERE)
import numpy as np  # noqa: E402
from render_model import Rx, Ry, Rz, T, S as Sc, part_matrix  # noqa: E402

E_ENTITY = Ry(180) @ Sc(-1, -1, 1) @ T(0, -1.501, 0)
HAND_TP = E_ENTITY @ part_matrix((-5, 2, 0), (-18, 0, 0)) @ Rx(-90) @ Ry(180) @ T(1 / 16, 0.125, -0.625)
HAND_FP = T(0.56, -0.52, -0.72)
GRIP = (13.0, 1.6, 8.0)   # точка древка в ладони (ниже обмотки)


def euler_xyz(R):
    b = math.asin(max(-1.0, min(1.0, R[0, 2])))
    c = math.atan2(-R[0, 1], R[0, 0])
    a = math.atan2(-R[1, 2], R[2, 2])
    return [round(math.degrees(v), 2) for v in (a, b, c)]


def aim(hand, up, blade, scale, grip=GRIP, offset=(0, 0, 0), spin=0.0):
    """up, blade — направления в мире; spin — доп. поворот вокруг оси X мира (замах), градусы."""
    up = np.array(up, float)
    up /= np.linalg.norm(up)
    bl = np.array(blade, float)
    bl -= up * (bl @ up)
    bl /= np.linalg.norm(bl)
    side = np.cross(up, bl)  # куда смотрит +Z модели... не важно: базис правый
    # модельные оси: +Y → up, −X → bl, +Z → −X × Y … собираем R_world так, что R·ey = up, R·(−ex) = bl
    ex = -bl
    ez = np.cross(ex, up)
    Rw = np.column_stack([ex, up, ez])
    if spin:
        Rw = Rx(spin)[:3, :3] @ Rw
    Rd = np.linalg.inv(hand[:3, :3]) @ Rw
    # сдвиг: T + Rd·S·(g/16 − 0.5) = offset (в кадре руки)
    g = np.array(grip) / 16 - 0.5
    off = np.linalg.inv(hand[:3, :3]) @ np.array(offset, float)
    t = (off - Rd @ (scale * g)) * 16
    ang = euler_xyz(Rd)
    chk = (Rx(ang[0]) @ Ry(ang[1]) @ Rz(ang[2]))[:3, :3]
    assert np.abs(chk - Rd).max() < 0.01, (ang, Rd)
    return {'rotation': euler_xyz(Rd), 'translation': [round(float(v), 2) for v in t], 'scale': [scale] * 3}


TP_UP, TP_BLADE = (0.06, 1, 0.22), (-0.12, -0.2, 1)       # стоит почти вертикально, лезвие вперёд
FP_UP, FP_BLADE = (-0.35, 1, 0.15), (-0.5, 0.05, -1)  # от первого лица: вверх-влево, лезвие от себя
TP_SCALE, FP_SCALE = 0.66, 0.36  # от первого лица заметно мельче: коса не закрывает экран
FP_OFFSET = (0.16, -0.14, 0.0)  # правее и ниже

DISPLAY = {
    'thirdperson_righthand': aim(HAND_TP, TP_UP, TP_BLADE, TP_SCALE),
    'firstperson_righthand': aim(HAND_FP, FP_UP, FP_BLADE, FP_SCALE, offset=FP_OFFSET),
    'gui': None,  # подгоняется под слот ниже (fit)
    'ground': {'translation': [0, 2, 0], 'scale': [0.3, 0.3, 0.3]},
    'fixed': None,
    'head': {'rotation': [0, 0, 0], 'translation': [0, 6, 0], 'scale': [0.6, 0.6, 0.6]},
}



def fit(rotation, box=15.4):
    """Вписать модель в квадрат слота (16 px): масштаб по большей стороне после поворота, центр — в центр."""
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


DISPLAY['gui'] = fit([0, 14, 10])
DISPLAY['fixed'] = fit([0, 0, 10], 15.0)

# Кадры замаха (по custom_model_data, их ставит core-weapons): поворот всей косы вокруг оси «плечо — плечо».
# 1 — взмах назад (лезвие за плечом), 2 — удар (коса летит вперёд-вниз), 3 — проводка (лезвие у земли впереди).
SWING_SPIN = {1: -40, 2: 55, 3: 105}
SWING_SPIN_FP = {1: 16, 2: -40, 3: -75}  # от первого лица вперёд — это −Z камеры
SWING = {i: {'thirdperson_righthand': aim(HAND_TP, TP_UP, TP_BLADE, TP_SCALE, spin=a),
             'firstperson_righthand': aim(HAND_FP, FP_UP, FP_BLADE, FP_SCALE, offset=FP_OFFSET, spin=SWING_SPIN_FP[i])}
         for i, a in SWING_SPIN.items()}


# ------------------------------------------------------------------------------------------- запись
def write_bbmodel(img, W, H):
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    groups, outliner = [], []
    by_group = {}
    elements = []
    for e in ELEMENTS:
        uid = str(uuid.uuid5(uuid.NAMESPACE_URL, 'crimson_scythe/' + e['name']))
        rot = [0, 0, 0]
        origin = [8, 8, 8]
        if e['rot']:
            rot['xyz'.index(e['rot']['axis'])] = e['rot']['angle']
            origin = e['rot']['origin']
        else:
            origin = [round((a + b) / 2, 3) for a, b in zip(e['from'], e['to'])]
        faces = {f: {'uv': e['uv'][f], 'texture': 0} for f in FACES}
        elements.append({'name': e['name'], 'box_uv': False, 'render_order': 'default', 'rescale': False,
                         'locked': False, 'shade_direction_override': '', 'light_emission': e['light'], 'export': True,
                         'shade': e['shade'], 'scope': 0, 'allow_mirror_modeling': True, 'from': e['from'], 'to': e['to'], 'autouv': 0,
                         'color': {'blade': 0, 'shaft': 3, 'knot': 3, 'twig': 2, 'cloth': 1, 'fire': 5}[e['part']],
                         'rotation': rot, 'origin': origin, 'faces': faces, 'type': 'cube', 'uuid': uid})
        gname = {'blade': 'Лезвие', 'shaft': 'Древко', 'knot': 'Древко', 'twig': 'Ветка', 'cloth': 'Обмотка',
                 'fire': 'Искры'}[e['part']]
        by_group.setdefault(gname, []).append(uid)
    for gname, kids in by_group.items():
        gid = str(uuid.uuid5(uuid.NAMESPACE_URL, 'crimson_scythe/group/' + gname))
        groups.append({'name': gname, 'uuid': gid, 'export': True, 'locked': False, 'scope': 0, 'selected': False,
                       'visibility': True, 'origin': [8, 8, 8], 'rotation': [0, 0, 0], 'color': 0, 'children': [],
                       'reset': False, 'shade': True, 'mirror_uv': False, 'autouv': 0, 'isOpen': False})
        outliner.append({'uuid': gid, 'isOpen': False, 'children': kids})
    tex = {'name': MID + '.png', 'path': '', 'folder': 'item', 'namespace': NS, 'id': '0', 'group': '', 'scope': 0,
           'width': W, 'height': H, 'uv_width': W, 'uv_height': H, 'particle': True, 'use_as_default': False,
           'layers_enabled': False, 'sync_to_project': '', 'file_format': 'png', 'render_mode': 'default',
           'render_sides': 'auto', 'wrap_mode': 'limited', 'pbr_channel': 'color', 'fps': 7, 'force_translucent': False,
           'frame_time': 1, 'frame_order_type': 'loop', 'frame_order': '', 'frame_interpolate': False, 'visible': True,
           'internal': True, 'saved': False, 'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, 'crimson_scythe/texture')),
           'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}
    bb = {'meta': {'format_version': '5.0', 'model_format': 'java_block', 'box_uv': False}, 'name': MID, 'parent': '',
          'ambientocclusion': False, 'front_gui_light': False, 'visible_box': [1, 1, 0], 'variable_placeholders': '',
          'variable_placeholder_buttons': [], 'unhandled_root_fields': {}, 'resolution': {'width': W, 'height': H},
          'elements': elements, 'groups': groups, 'outliner': outliner, 'textures': [tex], 'display': DISPLAY}
    os.makedirs(os.path.dirname(BB), exist_ok=True)
    with open(BB, 'w', encoding='utf-8') as fh:
        json.dump(bb, fh, ensure_ascii=False, indent=1)


def write_frames():
    mdir = os.path.join(ASSETS, NS, 'models', 'item')
    for i, d in SWING.items():
        disp = dict(d)  # левая рука не задана — Minecraft берёт правую и сам отражает её
        with open(os.path.join(mdir, f'{MID}_swing_{i}.json'), 'w', encoding='utf-8') as fh:
            json.dump({'parent': f'{NS}:item/{MID}_held', 'display': disp}, fh, indent=1)
    base = {'type': 'minecraft:model', 'model': f'{NS}:item/{MID}'}       # слот, земля, рамка — без угольков
    held = {'type': 'minecraft:model', 'model': f'{NS}:item/{MID}_held'}  # в руках — с кольцом угольков
    swing = {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': 0,
             'cases': [{'when': f'cw_swing_{i}', 'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{MID}_swing_{i}'}}
                       for i in SWING],
             'fallback': held}
    item = {'hand_animation_on_swap': False,
            'model': {'type': 'minecraft:select', 'property': 'minecraft:display_context',
                      'cases': [{'when': ['gui', 'ground', 'fixed', 'head', 'none'], 'model': base}],
                      'fallback': swing}}
    idir = os.path.join(ASSETS, NS, 'items')
    os.makedirs(idir, exist_ok=True)
    with open(os.path.join(idir, MID + '.json'), 'w', encoding='utf-8') as fh:
        json.dump(item, fh, indent=1)


# ------------------------------------------------------------------------------------------- кольцо угольков (модель)
# Кольцо маленьких светящихся кубиков вокруг головы косы — только в руках (модель crimson_scythe_held). Каждый
# уголёк берёт свою клетку 2×2 анимированной текстуры crimson_scythe_fx.png; по кадрам яркая «голова» с хвостом
# переходит от клетки к клетке, поэтому кажется, что две искры бегут по кругу. Частиц сервера для этого не нужно.
FX_N = 12               # угольков на кольце
FX_CENTER = (6.0, 21.0)  # центр кольца (x, y) — голова косы
FX_R = 10.5             # радиус кольца
FX_TILT = 18            # наклон кольца к игроку, градусы
FX_SIZE = 1.1
FX_FRAMETIME = 2
FX_TAIL = [(255, 242, 176), (248, 150, 58), (238, 70, 54), (156, 24, 40)]  # голова → хвост


def fx_elements():
    out = []
    t = math.radians(FX_TILT)
    for i in range(FX_N):
        a = 2 * math.pi * i / FX_N
        x = FX_CENTER[0] + FX_R * math.cos(a)
        z = 8 + FX_R * math.sin(a) * math.cos(t)
        y = FX_CENTER[1] + FX_R * math.sin(a) * math.sin(t)
        h = FX_SIZE / 2 if i % 2 == 0 else FX_SIZE * 0.35
        cx, cy = (i % 8) * 2, (i // 8) * 2
        faces = {f: {'uv': [cx, cy, cx + 2, cy + 2], 'texture': '#1'} for f in FACES}
        out.append({'from': [round(x - h, 3), round(y - h, 3), round(z - h, 3)],
                    'to': [round(x + h, 3), round(y + h, 3), round(z + h, 3)],
                    'light_emission': 15, 'shade': False, 'faces': faces})
    return out


def fx_texture():
    frames = FX_N
    img = Image.new('RGBA', (16, 16 * frames), (0, 0, 0, 0))
    px = img.load()
    for f in range(frames):
        for i in range(FX_N):
            d = min((f - i) % FX_N, (f + FX_N // 2 - i) % FX_N)  # две искры напротив друг друга
            if d >= len(FX_TAIL):
                continue
            c = FX_TAIL[d]
            lit = FX_TAIL[max(0, d - 1)]
            cx, cy = (i % 8) * 2, (i // 8) * 2
            for j in range(2):
                for k in range(2):
                    px[cx + k, f * 16 + cy + j] = (lit if (j, k) == (0, 0) else c) + (255,)
    return img


def write_fx():
    tdir = os.path.join(ASSETS, NS, 'textures', 'item')
    fx_texture().save(os.path.join(tdir, MID + '_fx.png'))
    with open(os.path.join(tdir, MID + '_fx.png.mcmeta'), 'w', encoding='utf-8') as fh:
        json.dump({'animation': {'frametime': FX_FRAMETIME, 'interpolate': True}}, fh, indent=1)
    mdir = os.path.join(ASSETS, NS, 'models', 'item')
    base = json.load(open(os.path.join(mdir, MID + '.json'), encoding='utf-8'))
    held = dict(base)
    held['textures'] = dict(base['textures'])
    held['textures']['1'] = f'{NS}:item/{MID}_fx'
    held['elements'] = base['elements'] + fx_elements()
    with open(os.path.join(mdir, MID + '_held.json'), 'w', encoding='utf-8') as fh:
        json.dump(held, fh, indent=1, ensure_ascii=False)


def main():
    img, W, H = build_texture()
    write_bbmodel(img, W, H)
    subprocess.run([sys.executable, os.path.join(HERE, 'bbjava2json.py'), BB, ASSETS, NS, MID], check=True)
    # конвертер пишет display как есть; модель предмета не наследует ничего
    write_fx()
    write_frames()
    print('лезвие: центр дуги', round(CX, 2), round(CY, 2), 'углы', round(TH0, 1), '…', round(TH1, 1),
          '| кубов', len(ELEMENTS), '| текстура', W, 'x', H)


if __name__ == '__main__':
    main()
