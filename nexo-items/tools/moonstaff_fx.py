#!/usr/bin/env python3
"""«Посох Лунного Странника»: спрайты эффектов (магический круг и луч), модели эффектов, определения предметов
эффектов и Python-копия шейдерного хука (azerus:moonstaff_fragment.glsl) — для превью и для проверки.

Модуль общий для tools/moon_staff.py (сборка) и tools/moonstaff_check.py (проверка). Запуск сам по себе не нужен.

Спрайт = 128×128 RGBA в pack/assets/azerus/textures/item/moon_staff_fx/<имя>.png. Сигнатура для шейдера:
  тексель (0,0) = S0 = (177,126,90,3), тексель (1,0) = (K,90,126,3), K — вид спрайта 1..9 (таблица KINDS).
Альфа 3 нигде больше не встречается; остаток строк 0..1, столбцов 0..1 и рамка 2 px по краю — прозрачные.
Рисунок кругов — только внутри радиуса 61 px от центра (63.5, 63.5), лучей — во внутреннем квадрате 124×124.
Без шейдера (Iris, старый пак) сигнатура отрезается ванильным порогом альфы 0.1, видна обычная картинка с оттенком.
"""
import json
import math
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
NS = 'azerus'
ASSETS = os.path.join(ROOT, 'pack', 'assets')
FX_DIR = os.path.join(ASSETS, NS, 'textures', 'item', 'moon_staff_fx')

SPRITE = 128                      # шейдер: MS_SPRITE_PX
S0 = (177, 126, 90, 3)            # шейдер: сигнатура в (0,0)
KINDS = {'ring': 1, 'runes': 2, 'hexagram': 3, 'core': 4,
         'beam_up': 5, 'beam_down': 6, 'beam_west': 7, 'beam_east': 8, 'flare': 9}
CIRCLE_ART_R = 61.0               # рисунок кругов не дальше этого радиуса (px)
MARK_G, MARK_B = 233, 253         # маркер вершины: Color = (R, 233, 253), R ≤ 250 — параметр

hexc = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
CYAN = [hexc(h) for h in ('#0b2a4a', '#1560a8', '#2fa8e8', '#7fd8ff', '#e8fbff')]
WHITE = (255, 255, 255)

# ------------------------------------------------------------------------------------------- поле пикселей
C = (SPRITE - 1) / 2.0            # центр (63.5, 63.5) в индексах текселей
YY, XX = np.mgrid[0:SPRITE, 0:SPRITE].astype(np.float64)
DX, DY = XX - C, YY - C
RR = np.hypot(DX, DY)
ANG = np.arctan2(DX, -DY) % (2 * math.pi)   # 0 — вверх, по часовой стрелке (y вниз)


class Layer:
    """Холст спрайта: рисуем «полосами» по расстоянию до линии — чёткий пиксель-арт без шума."""

    def __init__(self):
        self.rgb = np.zeros((SPRITE, SPRITE, 3), np.float64)
        self.a = np.zeros((SPRITE, SPRITE), np.float64)

    def put(self, mask, color, alpha):
        """Кладёт цвет туда, где mask, если новая альфа не меньше старой (линия поверх свечения)."""
        m = mask & (alpha >= self.a)
        self.rgb[m] = color
        self.a[m] = alpha

    def line(self, dist, width=0.75, glow=True, core=WHITE):
        """dist — поле расстояний (px) до линии. Ядро белое, кайма голубая, ореол — ступенями альфы."""
        if glow:
            self.put(dist < width + 4.5, CYAN[1], 48)
            self.put(dist < width + 2.8, CYAN[2], 96)
        self.put(dist < width + 1.0, CYAN[3], 255)
        self.put(dist < width, core, 255)

    def image(self, kind, circle):
        a = np.round(self.a).astype(int)
        a[(a > 0) & (a < 8)] = 0       # никакой альфы 1..7 (в т. ч. 3 — сигнатура)
        if circle:
            a[RR > CIRCLE_ART_R] = 0
        border = np.zeros_like(a, bool)
        border[:2, :] = border[-2:, :] = border[:, :2] = border[:, -2:] = True
        a[border] = 0
        rgb = np.clip(np.round(self.rgb), 0, 255).astype(np.uint8)
        rgb[a == 0] = 0
        out = np.dstack([rgb, a.astype(np.uint8)])
        out[0, 0] = S0
        out[0, 1] = (kind, 90, 126, 3)
        return out


def ring_dist(r):
    return np.abs(RR - r)


def seg_dist(ax, ay, bx, by, px=DX, py=DY):
    """Расстояние от точек (px, py) до отрезка a-b."""
    vx, vy = bx - ax, by - ay
    L2 = vx * vx + vy * vy or 1e-9
    t = np.clip(((px - ax) * vx + (py - ay) * vy) / L2, 0, 1)
    return np.hypot(px - ax - t * vx, py - ay - t * vy)


def polar_xy(r, ang):
    """Точка на радиусе r под углом ang (0 вверх, по часовой) → смещение от центра (x, y вниз)."""
    return r * math.sin(ang), -r * math.cos(ang)


# ------------------------------------------------------------------------------------------- спрайты круга
def sprite_ring():
    L = Layer()
    band = (RR > 52.5) & (RR < 57.5)
    L.put(band, CYAN[1], 40)                                  # светящееся поле между кольцами
    for i in range(48):                                       # насечки
        a = 2 * math.pi * i / 48
        r0 = 53.0 if i % 4 == 0 else 55.0
        x0, y0 = polar_xy(r0, a)
        x1, y1 = polar_xy(57.0, a)
        L.line(seg_dist(x0, y0, x1, y1), 0.45, glow=False, core=CYAN[3] if i % 4 else WHITE)
    L.line(ring_dist(58.2), 0.8)
    L.line(ring_dist(52.0), 0.55)
    for i in range(6):                                        # шесть шариков на внешнем кольце
        x, y = polar_xy(58.2, 2 * math.pi * i / 6)
        d = np.hypot(DX - x, DY - y)
        L.put(d < 3.6, CYAN[2], 140)
        L.put(d < 2.6, CYAN[3], 255)
        L.put(d < 1.4, WHITE, 255)
    return L.image(KINDS['ring'], True)


# Руны: свои абстрактные знаки (не письменность): штрихи на сетке 5×5 точек, по 2–4 штриха на знак.
_STROKES = [((0, 0), (0, 4)), ((4, 0), (4, 4)), ((2, 0), (2, 4)), ((0, 0), (4, 0)), ((0, 4), (4, 4)),
            ((0, 2), (4, 2)), ((0, 0), (4, 4)), ((4, 0), (0, 4)), ((0, 0), (2, 2)), ((4, 0), (2, 2)),
            ((2, 2), (0, 4)), ((2, 2), (4, 4)), ((0, 2), (2, 0)), ((2, 0), (4, 2)), ((0, 2), (2, 4)),
            ((2, 4), (4, 2)), ((1, 1), (3, 1)), ((1, 3), (3, 3))]


def rune_glyphs(n=24):
    """Детерминированный набор знаков: у каждого вертикальная «основа» и 1–3 штриха вокруг."""
    out = []
    state = 0x5eed
    for i in range(n):
        g = [_STROKES[2] if i % 3 else _STROKES[i % 2]]
        k = 1 + (i * 7) % 3
        for _ in range(k):
            state = (state * 1103515245 + 12345) & 0x7fffffff
            s = _STROKES[3 + state % (len(_STROKES) - 3)]
            if s not in g:
                g.append(s)
        out.append(g)
    return out


def sprite_runes():
    L = Layer()
    L.line(ring_dist(48.6), 0.5)
    L.line(ring_dist(37.4), 0.5)
    L.put((RR > 38.6) & (RR < 47.4), CYAN[0], 36)
    glyphs = rune_glyphs()
    n = len(glyphs)
    cell = 2 * math.pi / n
    # локальные координаты знака: tx — вдоль окружности (px на радиусе 43), ty — по радиусу (вниз = к центру)
    idx = np.floor(ANG / cell).astype(int) % n
    a_mid = (idx + 0.5) * cell
    tx = (ANG - a_mid) * 43.0
    ty = 43.0 - RR
    dist = np.full(RR.shape, 99.0)
    G = 1.45  # px на шаг сетки знака (5 точек → 5.8 px)
    for gi, g in enumerate(glyphs):
        m = idx == gi
        if not m.any():
            continue
        for (ax, ay), (bx, by) in g:
            d = seg_dist((ax - 2) * G, (ay - 2) * G, (bx - 2) * G, (by - 2) * G, tx[m], ty[m])
            dist[m] = np.minimum(dist[m], d)
    band = (RR > 38.0) & (RR < 48.0)
    dist[~band] = 99.0
    L.put(dist < 1.9, CYAN[2], 90)
    L.put(dist < 0.95, CYAN[3], 255)
    L.put(dist < 0.5, WHITE, 255)
    # точки-разделители между знаками
    for i in range(n):
        x, y = polar_xy(43.0, i * cell)
        d = np.hypot(DX - x, DY - y)
        L.put(d < 1.1, CYAN[3], 255)
    return L.image(KINDS['runes'], True)


def sprite_hexagram():
    L = Layer()
    R = 34.5
    dist = np.full(RR.shape, 99.0)
    for off in (0.0, math.pi):
        pts = [polar_xy(R, off + 2 * math.pi * k / 3) for k in range(3)]
        for k in range(3):
            (ax, ay), (bx, by) = pts[k], pts[(k + 1) % 3]
            dist = np.minimum(dist, seg_dist(ax, ay, bx, by))
    L.line(dist, 0.8)
    L.line(ring_dist(19.5), 0.55)
    L.line(ring_dist(R), 0.45, glow=False, core=CYAN[3])
    for k in range(6):
        x, y = polar_xy(R, math.pi * k / 3)
        d = np.hypot(DX - x, DY - y)
        L.put(d < 2.4, CYAN[3], 255)
        L.put(d < 1.3, WHITE, 255)
    return L.image(KINDS['hexagram'], True)


def sprite_core():
    L = Layer()
    steps = [(30, CYAN[1], 40), (24, CYAN[2], 70), (18, CYAN[2], 100), (12, CYAN[3], 130), (7, CYAN[4], 160)]
    for r, col, al in steps:
        L.put(RR < r, col, al)
    L.line(ring_dist(11.5), 0.45, glow=False, core=WHITE)
    for k in range(8):                                        # маленький круг знаков
        a = 2 * math.pi * k / 8
        x0, y0 = polar_xy(13.0, a)
        x1, y1 = polar_xy(16.5, a)
        L.put(seg_dist(x0, y0, x1, y1) < 0.6, WHITE, 255)
    L.put(RR < 2.6, WHITE, 255)
    return L.image(KINDS['core'], True)


def sprite_flare():
    L = Layer()
    dist_long = np.full(RR.shape, 99.0)
    for k in range(8):
        a = math.pi * k / 4
        ln = 58.0 if k % 2 == 0 else 34.0
        x, y = polar_xy(ln, a)
        d = seg_dist(0, 0, x, y)
        # луч сужается к концу: допустимая толщина падает с радиусом
        w = (2.6 if k % 2 == 0 else 1.8) * np.clip(1 - RR / ln, 0, 1)
        dist_long = np.minimum(dist_long, d - w)
    L.put(dist_long < 3.0, CYAN[1], 60)
    L.put(dist_long < 1.4, CYAN[3], 200)
    L.put(dist_long < 0.3, WHITE, 255)
    for r, col, al in [(22, CYAN[1], 50), (14, CYAN[2], 110), (8, CYAN[3], 200), (4.5, WHITE, 255)]:
        L.put(RR < r, col, al)
    return L.image(KINDS['flare'], True)


# ------------------------------------------------------------------------------------------- спрайты луча
def beam_art():
    """Луч «вверх» (K5): поперёк — u (x), вдоль — v (y). Профиль: белое ядро, мягкий голубой край, штрихи вдоль."""
    rgb = np.zeros((SPRITE, SPRITE, 3), np.float64)
    a = np.zeros((SPRITE, SPRITE), np.float64)
    w = (XX - 2 + 0.5) / 124.0
    d = np.abs(2 * w - 1)
    # штрихи: 14 дорожек поперёк, в каждой — длинные чёрточки со своим шагом
    lane = np.clip(np.floor(w * 14), 0, 13).astype(int)
    period = 40 + (lane * 13) % 37
    length = 14 + (lane * 7) % 19
    phase = (lane * 29) % 64
    dash = (((YY - 2) + phase) % period) < length
    bands = [(0.22, WHITE, 255, 255), (0.45, CYAN[4], 200, 230), (0.68, CYAN[3], 110, 170), (0.9, CYAN[2], 40, 90)]
    for lim, col, al_off, al_on in reversed(bands):
        m = d < lim
        rgb[m] = col
        a[m] = np.where(dash[m], al_on, al_off)
    inner = (XX >= 2) & (XX < 126) & (YY >= 2) & (YY < 126)
    a[~inner] = 0
    return rgb, a


def beam_sprite(kind_name):
    rgb, a = beam_art()
    if kind_name == 'beam_down':           # вдоль −v: отражение по вертикали
        rgb, a = rgb[::-1], a[::-1]
    elif kind_name == 'beam_west':         # вдоль +u: транспонирование
        rgb, a = rgb.transpose(1, 0, 2), a.T
    elif kind_name == 'beam_east':         # вдоль −u: транспонирование + отражение по горизонтали
        rgb, a = rgb.transpose(1, 0, 2)[:, ::-1], a.T[:, ::-1]
    L = Layer()
    L.rgb, L.a = rgb.copy(), a.copy()
    return L.image(KINDS[kind_name], False)


SPRITE_FUNCS = {'ring': sprite_ring, 'runes': sprite_runes, 'hexagram': sprite_hexagram, 'core': sprite_core,
                'flare': sprite_flare}


def all_sprites():
    out = {}
    for name in KINDS:
        out[name] = SPRITE_FUNCS[name]() if name in SPRITE_FUNCS else beam_sprite(name)
    return out


# ------------------------------------------------------------------------------------------- модели эффектов
def _face(tex):
    return {'uv': [0, 0, 16, 16], 'texture': '#' + tex, 'tintindex': 0}


def circle_model():
    # Порядок отрисовки = порядок элементов: от дальнего (для самого мага) к ближнему.
    layers = [('1', 8.15), ('2', 8.05), ('3', 7.95), ('4', 7.85)]   # ring, runes, hexagram, core; шаг 0.1 px — без z-fighting вдали
    els = []
    for tex, z in layers:
        els.append({'from': [0, 0, z], 'to': [16, 16, z], 'shade': False, 'light_emission': 15,
                    'faces': {'north': _face(tex), 'south': _face(tex)}})
    return {'textures': {'1': f'{NS}:item/moon_staff_fx/ring', '2': f'{NS}:item/moon_staff_fx/runes',
                         '3': f'{NS}:item/moon_staff_fx/hexagram', '4': f'{NS}:item/moon_staff_fx/core',
                         'particle': f'{NS}:item/moon_staff_fx/ring'},
            'elements': els}


def beam_model():
    els = []
    for ang in (0, 22.5, -22.5, 45, -45):         # «горизонтальные» плоскости, грани up/down
        e = {'from': [0, 8, 8], 'to': [16, 8, 16], 'shade': False, 'light_emission': 15,
             'faces': {'up': _face('1'), 'down': _face('2')}}
        if ang:
            e['rotation'] = {'angle': ang, 'axis': 'z', 'origin': [8, 8, 8]}
        els.append(e)
    for ang in (0, 22.5, -22.5):                  # «вертикальные», грани west/east
        e = {'from': [8, 0, 8], 'to': [8, 16, 16], 'shade': False, 'light_emission': 15,
             'faces': {'west': _face('3'), 'east': _face('4')}}
        if ang:
            e['rotation'] = {'angle': ang, 'axis': 'z', 'origin': [8, 8, 8]}
        els.append(e)
    els.append({'from': [-8, -8, 8], 'to': [24, 24, 8], 'shade': False, 'light_emission': 15,
                'faces': {'north': _face('5'), 'south': _face('5')}})
    return {'textures': {'1': f'{NS}:item/moon_staff_fx/beam_up', '2': f'{NS}:item/moon_staff_fx/beam_down',
                         '3': f'{NS}:item/moon_staff_fx/beam_west', '4': f'{NS}:item/moon_staff_fx/beam_east',
                         '5': f'{NS}:item/moon_staff_fx/flare', 'particle': f'{NS}:item/moon_staff_fx/flare'},
            'elements': els}


def item_def(name):
    return {'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{name}',
                      'tints': [{'type': 'minecraft:custom_model_data', 'index': 0, 'default': -1}]}}


def write_all():
    os.makedirs(FX_DIR, exist_ok=True)
    sprites = all_sprites()
    for name, arr in sprites.items():
        Image.fromarray(arr, 'RGBA').save(os.path.join(FX_DIR, name + '.png'))
    mdir = os.path.join(ASSETS, NS, 'models', 'item')
    idir = os.path.join(ASSETS, NS, 'items')
    os.makedirs(mdir, exist_ok=True)
    os.makedirs(idir, exist_ok=True)
    for name, model in (('moon_staff_circle', circle_model()), ('moon_staff_beam', beam_model())):
        with open(os.path.join(mdir, name + '.json'), 'w', encoding='utf-8') as fh:
            json.dump(model, fh, indent=1)
        with open(os.path.join(idir, name + '.json'), 'w', encoding='utf-8') as fh:
            json.dump(item_def(name), fh, indent=1)
    return sprites


# ------------------------------------------------------------------------------------------- копия шейдера (CPU)
# Повторяет azerus:moonstaff_fragment.glsl построчно (numpy, векторно по пикселям). t — секунды (GameTime·1200).
TAU = 2 * math.pi
EDGE = 0.5 - 2.0 / 128.0


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def _rot(lx, ly, a):
    px, py = lx - 0.5, ly - 0.5
    c, s = math.cos(a), math.sin(a)
    return c * px - s * py + 0.5, s * px + c * py + 0.5


def _mip2_alpha(spr):
    """Мип-уровень 2 (32×32) альфы спрайта — среднее 4×4, как приближение ванильной генерации мипов."""
    a = spr[..., 3].astype(np.float64) / 255.0
    return a.reshape(32, 4, 32, 4).mean(axis=(1, 3))


def _sample(spr, qx, qy):
    """texture(atlas, uv(q)) с nearest: тексель 2 + q·124 внутри спрайта."""
    qx, qy = np.clip(qx, 0, 1), np.clip(qy, 0, 1)
    ix = np.clip(np.floor(2 + qx * 124).astype(int), 0, SPRITE - 1)
    iy = np.clip(np.floor(2 + qy * 124).astype(int), 0, SPRITE - 1)
    return spr[iy, ix].astype(np.float64) / 255.0


def _sample_halo(spr, qx, qy):
    m = _mip2_alpha(spr)
    qx, qy = np.clip(qx, 0, 1), np.clip(qy, 0, 1)
    ix = np.clip(np.floor((2 + qx * 124) / 4).astype(int), 0, 31)
    iy = np.clip(np.floor((2 + qy * 124) / 4).astype(int), 0, 31)
    return m[iy, ix]


def shade(kind, spr, lx, ly, param, t):
    """kind 1..9, lx/ly — msLocal (0..1, массивы), param — msParam, t — секунды. → RGBA (…, 4) 0..1+."""
    lx = np.asarray(lx, np.float64)
    ly = np.asarray(ly, np.float64)
    out = np.zeros(lx.shape + (4,))
    if kind <= 4:
        c = param
        a = {1: TAU * 0.25 * t, 2: -TAU * t / 6.0, 3: TAU * t / 12.0, 4: 0.0}[kind]
        qx, qy = _rot(lx, ly, a)
        tex = _sample(spr, qx, qy)
        halo = _sample_halo(spr, qx, qy)
        pulse = 0.5 + 0.5 * math.sin(TAU * 1.5 * t)
        if kind == 1:
            appear = np.ones_like(lx)
        elif kind == 2:
            ang = np.arctan2(lx - 0.5, -(ly - 0.5)) % TAU
            appear = smoothstep(ang, ang + 0.15, np.full_like(lx, c * (TAU + 0.15)))
        elif kind == 3:
            appear = np.full_like(lx, smoothstep(0.25, 0.7, c))
        else:
            appear = np.full_like(lx, 0.4 + 0.6 * c)
        bright = 0.55 + 0.45 * c + 0.12 * pulse
        if c >= 0.999:
            bright += 0.25 * (0.5 + 0.5 * math.sin(TAU * 4.0 * t))
        rgb = tex[..., :3] * bright + np.array([0.35, 0.8, 1.0]) * (halo * 0.6 * (0.5 + 0.5 * c))[..., None]
        al = np.maximum(tex[..., 3], halo * 0.45) * appear
        al = np.where(np.hypot(qx - 0.5, qy - 0.5) > EDGE, 0.0, al)
        out[..., :3], out[..., 3] = rgb, al
        return out
    if kind <= 8:
        s = {5: ly, 6: 1 - ly, 7: lx, 8: 1 - lx}[kind]
        w = lx if kind in (5, 6) else ly
        d = np.abs(2 * w - 1)
        L = max(param * 250.0 / 8.0, 0.5)
        ph = (s * L * 0.5 - t * 6.0) % 1.0
        st = _sample(spr, w, ph) if kind in (5, 6) else _sample(spr, ph, w)
        streak = st[..., 3]
        core = 1 - smoothstep(0.0, 0.42, d)
        soft = 1 - smoothstep(0.1, 1.0, d)
        taper = smoothstep(0.0, 0.4 / L, s) * (1 - smoothstep(1 - 1.2 / L, 1.0, s))
        rgb = np.array([0.72, 0.9, 1.0]) * (1 - core)[..., None] + core[..., None]
        al = np.clip(soft * (0.12 + 0.28 * streak) + core * 0.85, 0, 1) * taper
        out[..., :3], out[..., 3] = rgb, al
        return out
    qx, qy = _rot(lx, ly, TAU * 1.0 * t)
    tex = _sample(spr, qx, qy)
    out[..., :3] = tex[..., :3] * (1.2 + 0.3 * math.sin(TAU * 3.0 * t))
    out[..., 3] = np.where(np.hypot(qx - 0.5, qy - 0.5) > EDGE, 0.0, tex[..., 3])
    return out


def over(dst, src):
    """Наложение (src не premultiplied) на RGB-холст dst 0..1."""
    a = np.clip(src[..., 3:4], 0, 1)
    return dst * (1 - a) + np.clip(src[..., :3], 0, 1) * a


def render_circle(sprites, c, t, size=256, bg=(0.06, 0.07, 0.1)):
    """Круг анфас, как его видит маг: слои в порядке модели (кольцо, руны, гексаграмма, ядро)."""
    v = (np.arange(size) + 0.5) / size
    lx, ly = np.meshgrid(v, v)
    img = np.ones((size, size, 3)) * np.array(bg)
    for name in ('ring', 'runes', 'hexagram', 'core'):
        img = over(img, shade(KINDS[name], sprites[name], lx, ly, c, t))
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


def render_beam_side(sprites, R, t, length_px=640, width_px=64, bg=(0.06, 0.07, 0.1)):
    """Луч сбоку (сверху): 8 плоскостей звезды, каждая сжата поперёк на |cos φ| (φ — наклон плоскости)."""
    L = R / 8.0
    H = width_px * 2
    img = np.ones((H, length_px, 3)) * np.array(bg)
    xs = (np.arange(length_px) + 0.5) / length_px            # s: 0 — у круга, 1 — конец
    ys = (np.arange(H) + 0.5) / H * 2 - 1                    # −1..1 в полуширинах
    S_, Y_ = np.meshgrid(xs, ys)
    planes = [(5, 0), (5, 22.5), (5, -22.5), (5, 45), (5, -45), (7, 90), (7, 67.5), (7, 112.5)]
    for kind, phi in planes:
        k = abs(math.cos(math.radians(phi)))
        if k < 0.05:
            continue
        w = Y_ / k * 0.5 + 0.5
        inside = (w >= 0) & (w <= 1)
        lx, ly = (w, S_) if kind == 5 else (S_, w)
        name = 'beam_up' if kind == 5 else 'beam_west'
        col = shade(kind, sprites[name], lx, ly, R / 250.0, t)
        col[..., 3] *= inside
        img = over(img, col)
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


def render_flare(sprites, t, size=256, bg=(0.06, 0.07, 0.1)):
    v = (np.arange(size) + 0.5) / size
    lx, ly = np.meshgrid(v, v)
    img = np.ones((size, size, 3)) * np.array(bg)
    img = over(img, shade(9, sprites['flare'], lx, ly, 1.0, t))
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


def load_sprites():
    return {n: np.asarray(Image.open(os.path.join(FX_DIR, n + '.png')).convert('RGBA')) for n in KINDS}
