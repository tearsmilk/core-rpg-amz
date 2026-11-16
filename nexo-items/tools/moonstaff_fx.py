#!/usr/bin/env python3
"""«Посох Лунного Странника»: спрайты эффектов (магический круг и луч), модели эффектов, определения предметов
эффектов и Python-копия шейдерного хука (azerus:moonstaff_fragment.glsl) — для превью и для проверки.

Модуль общий для tools/moon_staff.py (сборка) и tools/moonstaff_check.py (проверка). Запуск сам по себе не нужен.

Спрайт = 128×128 RGBA в pack/assets/azerus/textures/item/moon_staff_fx/<имя>.png. Сигнатура для шейдера:
  тексель (0,0) = S0 = (177,126,90,3), тексель (1,0) = (K,90,126,3), K — вид спрайта 1..10 (таблица KINDS).
Альфа 3 нигде больше не встречается; остаток строк 0..1, столбцов 0..1 и рамка 2 px по краю — прозрачные.
Рисунок кругов — только внутри радиуса 61 px от центра (63.5, 63.5), лучей — во внутреннем квадрате 124×124.
K10 hex_cell — ячейка барьера (шестигранник «вершиной вверх», внешний радиус ровно HEX_R_PX = 60 px), классы альфы:
  255 — обвод, 254 — блёстка, 209..239 — фаска, 96..208 — заливка, 0 — вне шестигранника (шейдер различает по альфе).
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
         'beam_up': 5, 'beam_down': 6, 'beam_west': 7, 'beam_east': 8, 'flare': 9,
         'hex_cell': 10}                                      # K11..K15 — резерв, не используются
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


# ------------------------------------------------------------------------------------------- ячейка барьера (K10)
HEX_R_PX = 60                     # внешний радиус шестигранника (до вершины), px; общий с core_magic (keisan)
SQ3 = math.sqrt(3.0)
# «Шестигранная» метрика (вершиной вверх): ρ = R на контуре шестигранника с внешним радиусом R.
RHO = np.maximum(np.abs(DX) * 2 / SQ3, np.abs(DY) + np.abs(DX) / SQ3)
# Сектор 0..5 = грань: 0 — верх-право, 1 — право, 2 — низ-право, 3 — низ-лево, 4 — лево, 5 — верх-лево
SECT = np.floor(ANG / (math.pi / 3)).astype(int) % 6
HEX_RIM = {5: (232, 251, 255), 4: (232, 251, 255), 0: (190, 240, 255), 1: (190, 240, 255), 3: (190, 240, 255),
           2: (127, 216, 255)}
HEX_FILL = [(227, 249, 255, 176), (180, 236, 250, 150), (131, 214, 239, 128), (88, 185, 222, 112),
            (58, 148, 192, 100)]
HEX_TEAL = (111, 224, 216, 120)


def hex_vertex(r, k):
    """Вершина k (0 — верх, по часовой) шестигранника радиуса r → (dx, dy вниз)."""
    return polar_xy(r, math.pi / 3 * k)


def sprite_hex_cell():
    """Ячейка барьера: толстый бело-голубой обвод, фаска, «матовая» кристаллическая заливка из 6 граней-треугольников
    и площадки, блёстки. Свет сверху слева. Чёткие полосы по ρ, без шума."""
    L = Layer()
    inside = RHO <= HEX_R_PX
    # заливка: грани по секторам (нормаль грани смотрит на свою сторону шестигранника)
    facet = {5: HEX_FILL[1], 4: HEX_FILL[2], 0: HEX_FILL[2], 3: HEX_TEAL, 1: HEX_FILL[3], 2: HEX_FILL[4]}
    fill = RHO <= 50
    for k, (r, g, b, a) in facet.items():
        L.put(fill & (SECT == k), (r, g, b), a)
    # рёбра граней (от вершины площадки к вершине шестигранника): светлые со стороны света, тёмные — с теневой
    ridge = np.full(RR.shape, 99.0)
    ridge_dark = np.full(RR.shape, 99.0)
    for k in range(6):
        x0, y0 = hex_vertex(22, k)
        x1, y1 = hex_vertex(49, k)
        d = seg_dist(x0, y0, x1, y1)
        if k in (4, 5, 0):
            ridge = np.minimum(ridge, d)
        else:
            ridge_dark = np.minimum(ridge_dark, d)
    L.rgb[fill & (ridge_dark < 0.6)] = HEX_FILL[3][:3]
    L.a[fill & (ridge_dark < 0.6)] = HEX_FILL[3][3]
    m = fill & (ridge < 0.6)
    L.rgb[m] = HEX_FILL[0][:3]
    L.a[m] = HEX_FILL[0][3]
    # площадка (плоский верх кристалла): чуть светлее окружения, обвод площадки — светлый сверху слева
    table = RHO <= 22
    L.rgb[table] = (150, 224, 245)
    L.a[table] = 138
    tedge = table & (RHO > 20.6)
    lit = tedge & np.isin(SECT, (4, 5, 0))
    L.rgb[lit] = HEX_FILL[0][:3]
    L.a[lit] = HEX_FILL[0][3]
    shd = tedge & np.isin(SECT, (1, 2, 3))
    L.rgb[shd] = HEX_FILL[3][:3]
    L.a[shd] = HEX_FILL[3][3]
    # блик на площадке: короткий косой штрих сверху слева (параллельно верх-левой грани)
    hl = seg_dist(-14.0, -3.0, -5.0, -8.2) < 1.0
    L.rgb[hl] = HEX_FILL[0][:3]
    L.a[hl] = HEX_FILL[0][3]
    # фаска 50 < ρ ≤ 52: светлая линия, со стороны света плотнее
    bevel = (RHO > 50) & (RHO <= 52)
    L.rgb[bevel] = (200, 243, 255)
    L.a[bevel] = np.where(np.isin(SECT, (4, 5, 0)), 236, 214)[bevel]
    # обвод 52 < ρ ≤ 60 (8 px): тон по стороне, самая внешняя кромка сверху слева — белая
    rim = (RHO > 52) & inside
    for k, col in HEX_RIM.items():
        mm = rim & (SECT == k)
        L.rgb[mm] = col
        L.a[mm] = 255
    outer = rim & (RHO > 58)
    L.rgb[outer & np.isin(SECT, (5, 4))] = WHITE
    L.rgb[(RHO > 59) & inside & (SECT == 0)] = (232, 251, 255)
    inner = rim & (RHO <= 53) & np.isin(SECT, (1, 2, 3))   # тёмная внутренняя кромка снизу справа
    L.rgb[inner] = (110, 200, 245)
    # блёстки (альфа 254 — шейдер мерцает): кресты 3×3 и одиночные у вершин, внутри обвода
    glints = [(hex_vertex(46, 5), True), (hex_vertex(46, 0), False), (hex_vertex(46, 2), True),
              (hex_vertex(44, 4), False), ((-9.0, -12.0), False)]
    for (gx, gy), big in glints:
        ix, iy = int(round(C + gx)), int(round(C + gy))
        pts = [(ix, iy)] + ([(ix - 1, iy), (ix + 1, iy), (ix, iy - 1), (ix, iy + 1)] if big else [])
        for x, y in pts:
            L.rgb[y, x] = WHITE
            L.a[y, x] = 254
    L.a[~inside] = 0
    return L.image(KINDS['hex_cell'], True)


def hex_classes(spr):
    """Классы альфы ячейки: rim / glint / bevel / fill / 0. → dict имя → маска (без сигнатуры)."""
    a = spr[..., 3].astype(int).copy()
    a[0, 0] = a[0, 1] = 0
    return {'rim': a == 255, 'glint': a == 254, 'bevel': (a >= 209) & (a <= 239), 'fill': (a >= 96) & (a <= 208),
            'zero': a == 0}


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
                'flare': sprite_flare, 'hex_cell': sprite_hex_cell}


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


def barrier_model():
    """Одна плоскость 16×16 px по центру (z = 8), грани north и south — спрайт виден незеркальным с обеих сторон."""
    return {'textures': {'1': f'{NS}:item/moon_staff_fx/hex_cell', 'particle': f'{NS}:item/moon_staff_fx/hex_cell'},
            'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'light_emission': 15,
                          'faces': {'north': _face('1'), 'south': _face('1')}}]}


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
    for name, model in (('moon_staff_circle', circle_model()), ('moon_staff_beam', beam_model()),
                        ('moon_staff_barrier', barrier_model())):
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
    """kind 1..10, lx/ly — msLocal (0..1, массивы), param — msParam, t — секунды. → RGBA (…, 4) 0..1+."""
    lx = np.asarray(lx, np.float64)
    ly = np.asarray(ly, np.float64)
    out = np.zeros(lx.shape + (4,))
    if kind == 10:
        # Ячейка барьера: msParam = (g + 25·L)/250, g — фаза мерцания, L — уровень (0..7 — альфа L/8, 8, 9 — вспышка).
        px = np.clip(lx * SPRITE, 0.5, SPRITE - 0.5)
        py = np.clip(ly * SPRITE, 0.5, SPRITE - 0.5)
        tex = spr[np.floor(py).astype(int), np.floor(px).astype(int)].astype(np.float64) / 255.0
        code = int(math.floor(param * 250.0 + 0.5))
        lv = code // 25
        g = code - 25 * lv
        ph = g / 24.0
        pos = ph * 0.8 + 0.2 * (lx - 0.5 * ly)
        band = 1.0 - smoothstep(0.0, 0.12, np.abs(pos - (((t / 4.0) % 1.0) * 1.6 - 0.3)))
        breath = 0.5 + 0.5 * math.sin(TAU * 0.5 * t + ph * 6.0)
        a255 = tex[..., 3] * 255.0
        rgb = np.zeros(lx.shape + (3,))
        al = np.zeros(lx.shape)
        rim = a255 > 254.5
        gl = (a255 > 253.5) & ~rim
        fi = (a255 >= 7.5) & ~rim & ~gl
        band3 = band[..., None]
        rgb_rim = tex[..., :3] * (1.05 + 0.2 * breath) + 0.35 * band3
        tw = np.maximum(0.5 + 0.5 * np.sin(TAU * t / 1.5 + ph * 11.0 + lx * 7.0), 0.0) ** 8.0
        rgb_gl = np.array([0.85, 0.97, 1.1]) * (1.0 - tw)[..., None] + 1.6 * tw[..., None]
        al_gl = 0.8 + 0.2 * tw
        base_fi = tex[..., :3] * np.array([0.82, 0.95, 1.12]) * (0.95 + 0.12 * breath) + np.array([0.02, 0.08, 0.22])
        rgb_fi = base_fi * 0.6 + np.array([0.72, 0.93, 1.0]) * 0.4 + np.array([0.6, 0.9, 1.0]) * 0.35 * band3
        al_fi = np.minimum(tex[..., 3] * (1.45 + 0.2 * band), 1.0)
        rgb = np.where(rim[..., None], rgb_rim, np.where(gl[..., None], rgb_gl, np.where(fi[..., None], rgb_fi, 0.0)))
        al = np.where(rim, 1.0, np.where(gl, al_gl, np.where(fi, al_fi, 0.0)))
        if lv <= 7:
            al = al * (lv / 8.0)
        elif lv == 9:
            rgb = rgb * 0.5 + 0.5
            al = np.clip(al * 1.3 + 0.1 * (al >= 0.004), 0.0, 1.0)
        out[..., :3], out[..., 3] = rgb, al
        return out
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


# ------------------------------------------------------------------------------------------- барьер: раскладка
# Копия формул core_magic/keisan.py (蜂巣, 組順, 輝き番号, 出現tick, 結界R, 表示倍率); moonstaff_check (f) сверяет.
def honeycomb(cols, rows, r):
    """Соты «вершиной вверх»: [(u, v)] по строкам снизу вверх, u — вправо (для мага), v — вверх, метры."""
    w = SQ3 * r
    h = 1.5 * r
    out = []
    for j in range(rows):
        s = -w / 4 if j % 2 == 0 else w / 4
        for i in range(cols):
            out.append(((i - (cols - 1) / 2.0) * w + s, (j - (rows - 1) / 2.0) * h))
    return out


def build_order(cells):
    """Порядок сборки от центра наружу: (round(u²+v², 6), round(atan2(u, v) mod 2π, 6))."""
    return sorted(range(len(cells)), key=lambda k: (round(cells[k][0] ** 2 + cells[k][1] ** 2, 6),
                                                    round(math.atan2(cells[k][0], cells[k][1]) % TAU, 6)))


def glow_slots(cells):
    """Фаза мерцания g 0..24: по диагонали d = u − 0.5·v (верх-лево → 0, низ-право → 24)."""
    d = [u - 0.5 * v for u, v in cells]
    lo, hi = min(d), max(d)
    if hi == lo:
        return [0 for _ in cells]
    return [int(round(24 * (x - lo) / (hi - lo))) for x in d]


def spawn_tick(m, n, charge, fly):
    return 0 if n == 1 else (m * (charge - fly)) // (n - 1)


def barrier_R(g, L):
    """R цвета custom_model_data: g + 25·L (g 0..24, L 0..9), максимум 249."""
    return min(max(int(g), 0), 24) + 25 * min(max(int(L), 0), 9)


def display_scale(r, gap):
    """Q: сторона квадрата модели (блоки) при scale 1 — 1 блок; шестигранник в нём — HEX_R_PX/128 стороны."""
    return r * (1 - gap) * SPRITE / HEX_R_PX


BARRIER = dict(cols=3, rows=5, r=0.5, gap=0.04, charge=20, fly=6, forward=2.0, up=0.25, start_scale=0.15,
               spread=0.15)


def barrier_cells(t_held=None, levels=None, cfg=BARRIER):
    """Ячейки для рисования: [(u, v, сторона, R)] в порядке отрисовки. t_held — тики удержания (None — собран,
    все L из levels, по умолчанию 8). Во время сборки: ячейка m появляется в spawn_tick и летит fly тиков из точки
    у навершия посоха (P0 в координатах мага: u = 0.3, v = −up − 0.2) с масштабом start_scale (линейно, как
    интерполяция дисплея), L = 9 в полёте и 7 после посадки; на тике charge — сформирован, все L = 9."""
    cells = honeycomb(cfg['cols'], cfg['rows'], cfg['r'])
    order = build_order(cells)
    g = glow_slots(cells)
    Q = display_scale(cfg['r'], cfg['gap'])
    n = len(cells)
    out = []
    for m, k in enumerate(order):
        u, v = cells[k]
        if t_held is None:
            L = 8 if levels is None else levels[k]
            out.append((u, v, Q, barrier_R(g[k], L)))
            continue
        s0 = spawn_tick(m, n, cfg['charge'], cfg['fly'])
        if t_held < s0:
            continue
        p = min(max((t_held - s0) / cfg['fly'], 0.0), 1.0)
        su = 0.3 + u * cfg['spread']
        sv = -cfg['up'] - 0.2 + v * cfg['spread']
        side = Q * (cfg['start_scale'] + (1 - cfg['start_scale']) * p)
        L = 9 if p < 1 else 7
        if t_held >= cfg['charge']:
            L = 9
        out.append((su + (u - su) * p, sv + (v - sv) * p, side, barrier_R(g[k], L)))
    return out


def night_sky(w, h, seed=3):
    ys = np.linspace(0, 1, h)[:, None, None]
    top, bot = np.array([0.02, 0.03, 0.08]), np.array([0.07, 0.10, 0.20])
    img = top * (1 - ys) + bot * ys + np.zeros((h, w, 3))
    rng = np.random.default_rng(seed)
    for _ in range(w * h // 1800):
        x, y = rng.integers(0, w), rng.integers(0, h)
        img[y, x] = rng.uniform(0.5, 1.0)
    return img


def render_cells(sprites, cells, t, w=512, h=640, ppm=128.0, centre=(0.0, 0.0), bg=None):
    """Ортогональный вид спереди, как видит маг (u вправо, v вверх). cells — [(u, v, сторона, R)]."""
    img = night_sky(w, h) if bg is None else bg.copy()
    spr = sprites['hex_cell']
    for u, v, side, R in cells:
        x0 = int(math.floor(w / 2 + (u - centre[0] - side / 2) * ppm))
        x1 = int(math.ceil(w / 2 + (u - centre[0] + side / 2) * ppm))
        y0 = int(math.floor(h / 2 - (v - centre[1] + side / 2) * ppm))
        y1 = int(math.ceil(h / 2 - (v - centre[1] - side / 2) * ppm))
        x0c, x1c, y0c, y1c = max(x0, 0), min(x1, w), max(y0, 0), min(y1, h)
        if x0c >= x1c or y0c >= y1c:
            continue
        ys, xs = np.mgrid[y0c:y1c, x0c:x1c] + 0.5
        lx = ((xs - w / 2) / ppm + centre[0] - (u - side / 2)) / side
        ly = ((v + side / 2) - (centre[1] - (ys - h / 2) / ppm)) / side
        ins = (lx >= 0) & (lx <= 1) & (ly >= 0) & (ly <= 1)
        col = shade(10, spr, np.clip(lx, 0, 1), np.clip(ly, 0, 1), R / 250.0, t)
        col[..., 3] *= ins
        img[y0c:y1c, x0c:x1c] = over(img[y0c:y1c, x0c:x1c], col)
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


def render_barrier(sprites, levels=None, t=0.0, w=512, h=640, ppm=128.0):
    """Собранный барьер (по умолчанию все L = 8)."""
    return render_cells(sprites, barrier_cells(None, levels), t, w, h, ppm)


def render_barrier_assembly(sprites, held, t=0.0, w=512, h=640, ppm=128.0):
    """Кадр сборки на тике удержания held (ячейки на пути полёта с интерполяцией масштаба)."""
    return render_cells(sprites, barrier_cells(held), t, w, h, ppm, centre=(0.0, -0.15))
