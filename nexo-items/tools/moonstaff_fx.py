#!/usr/bin/env python3
"""«Посох Лунного Странника»: спрайты эффектов (магический круг, луч, барьер, огненный круг и кометы «Огненного шара»),
модели эффектов, определения предметов
эффектов и Python-копия шейдерного хука (azerus:moonstaff_fragment.glsl) — для превью и для проверки.

Модуль общий для tools/moon_staff.py (сборка) и tools/moonstaff_check.py (проверка). Запуск сам по себе не нужен.

Спрайт = 128×128 RGBA в pack/assets/azerus/textures/item/moon_staff_fx/<имя>.png. Сигнатура для шейдера:
  тексель (0,0) = S0 = (177,126,90,3), тексель (1,0) = (K,90,126,3), K — вид спрайта 1..19 (таблица KINDS),
  K20..K31 — резерв.
Альфа 3 нигде больше не встречается; остаток строк 0..1, столбцов 0..1 и рамка 2 px по краю — прозрачные.
Рисунок кругов (и всех радиальных спрайтов) — только внутри радиуса 61 px от центра (63.5, 63.5), лучей и хвостов
комет — во внутреннем квадрате 124×124.
K10 hex_cell — ячейка барьера (шестигранник «вершиной вверх», внешний радиус ровно HEX_R_PX = 60 px), классы альфы:
  255 — обвод, 254 — блёстка, 209..239 — фаска, 96..208 — заливка, 0 — вне шестигранника (шейдер различает по альфе).
K11..K14 — огненный круг (кольца, руны, печать, языки пламени; цвета огня «запечены» в спрайты), у K14 альфа 254 —
искры (мерцают). K15..K18 — хвост кометы (4 ориентации, как K5..K8), K19 — голова кометы (радиальная).
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
         'hex_cell': 10,
         'fire_ring': 11, 'fire_runes': 12, 'fire_sigil': 13, 'fire_flames': 14,
         'comet_tail_up': 15, 'comet_tail_down': 16, 'comet_tail_west': 17, 'comet_tail_east': 18,
         'comet_core': 19,
         'tp_ring': 20, 'tp_runes': 21, 'tp_hexagram': 22, 'tp_core': 23,
         'ember_ring': 24, 'ember_runes': 25, 'ember_hexagram': 26, 'ember_core': 27,
         'slash_crescent': 28, 'slash_streak': 29,
         'ember_beam_up': 30, 'ember_beam_down': 31, 'ember_beam_west': 32, 'ember_beam_east': 33}   # K34..K39 — резерв
# Круг телепорта (core_items, свиток): K20..23 = K1..4 той же логики шейдера, цвет фиолетовый (каналы R и G переставлены).
TP_CIRCLE = {'tp_ring': 'ring', 'tp_runes': 'runes', 'tp_hexagram': 'hexagram', 'tp_core': 'core'}
# Палка core_weapons, «Угли» (Shift+ПКМ): K24..27 = K1..4 той же логики шейдера, красная перекраска (см. ember_sprite),
# свечение красное. K28 — серп «Удара в спину», K29 — белая вспышка-штрих поперёк серпа; K30..33 — дымный
# тёмно-красный луч «Углей» (4 ориентации, как K5..K8, своя ветка шейдера).
EMBER_CIRCLE = {'ember_ring': 'ring', 'ember_runes': 'runes', 'ember_hexagram': 'hexagram', 'ember_core': 'core'}
BEAMS = ('beam_up', 'beam_down', 'beam_west', 'beam_east')
EMBER_BEAMS = ('ember_beam_up', 'ember_beam_down', 'ember_beam_west', 'ember_beam_east')
TAILS = ('comet_tail_up', 'comet_tail_down', 'comet_tail_west', 'comet_tail_east')
FIRE_CIRCLE = ('fire_ring', 'fire_runes', 'fire_sigil', 'fire_flames')
FLAMES_WIDE = 1.25                # плоскость языков пламени в 1.25 раза шире кольца (модель moon_staff_fire_circle)
CIRCLE_ART_R = 61.0               # рисунок кругов не дальше этого радиуса (px)
MARK_G, MARK_B = 233, 253         # маркер вершины: Color = (R, 233, 253), R ≤ 250 — параметр

hexc = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
CYAN = [hexc(h) for h in ('#0b2a4a', '#1560a8', '#2fa8e8', '#7fd8ff', '#e8fbff')]
WHITE = (255, 255, 255)
# Огонь: тёмно-красный → белый накал. Самый тёмный тон — только тонкой кромкой.
FIRE = [hexc(h) for h in ('#7a1404', '#d2400a', '#ff7a12', '#ffb43a', '#ffe08a', '#fff8e0')]

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

    def fire_line(self, dist, width=0.75, glow=True, core=None, edge=None):
        """Линия огня: ядро — белый накал, кайма — золото, ореол — оранжевый ступенями альфы."""
        if glow:
            self.put(dist < width + 4.0, FIRE[1], 56)
            self.put(dist < width + 2.4, FIRE[2], 120)
        self.put(dist < width + 1.0, FIRE[3] if edge is None else edge, 255)
        self.put(dist < width, FIRE[5] if core is None else core, 255)

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


# ------------------------------------------------------------------------------------------- огненный круг (K11..K14)
def _dot(L, x, y, r_glow, r_mid, r_core, glow=FIRE[2], mid=FIRE[4], core=FIRE[5], a_glow=150):
    d = np.hypot(DX - x, DY - y)
    L.put(d < r_glow, glow, a_glow)
    L.put(d < r_mid, mid, 255)
    L.put(d < r_core, core, 255)


def sprite_fire_ring():
    """K11: внешний золотой обод 55..60 (кромки: белый накал внутри, красно-оранжевая снаружи), кольцо 49..51,
    48 насечек 51..55 (каждая 4-я — длинная, белая), тонкие кольца 34..35 и 22..23."""
    L = Layer()
    L.put((RR >= 60.0) & (RR < 61.0), FIRE[2], 150)               # ореол снаружи обода
    L.put((RR >= 55.0) & (RR < 60.0), FIRE[3], 255)               # обод
    L.put((RR >= 59.0) & (RR < 60.0), FIRE[1], 255)
    L.put((RR >= 55.0) & (RR < 56.0), FIRE[5], 255)
    for i in range(16):                                           # раскалённые заклёпки на ободе
        x, y = polar_xy(57.5, 2 * math.pi * (i + 0.5) / 16)
        d = np.hypot(DX - x, DY - y)
        L.rgb[d < 1.6] = FIRE[4]
        L.rgb[d < 0.9] = FIRE[5]
    for i in range(48):                                           # насечки
        a = 2 * math.pi * i / 48
        major = i % 4 == 0
        x0, y0 = polar_xy(50.5 if major else 51.5, a)
        x1, y1 = polar_xy(55.0, a)
        L.put(seg_dist(x0, y0, x1, y1) < (0.9 if major else 0.55), FIRE[5] if major else FIRE[4], 255)
    L.fire_line(np.abs(RR - 50.0), 0.6)                           # кольцо 49..51
    L.fire_line(np.abs(RR - 34.5), 0.25, core=FIRE[4])            # тонкие кольца
    L.fire_line(np.abs(RR - 22.5), 0.25, core=FIRE[4])
    return L.image(KINDS['fire_ring'], True)


# Огненные руны: свой набор абстрактных знаков (не письменность) — углы, «галочки», засечки на сетке 5×5 точек.
_FIRE_STROKES = [((2, 0), (2, 4)), ((0, 4), (2, 0)), ((2, 0), (4, 4)),
                 ((0, 1), (2, 3)), ((2, 3), (4, 1)), ((0, 2), (4, 2)), ((1, 0), (3, 0)), ((1, 4), (3, 4)),
                 ((0, 0), (2, 2)), ((2, 2), (4, 0)), ((0, 4), (2, 2)), ((2, 2), (4, 4)), ((0, 3), (2, 1)),
                 ((2, 1), (4, 3)), ((4, 0), (4, 2)), ((0, 2), (0, 4)), ((1, 1), (3, 1)), ((1, 3), (3, 3))]


def fire_glyphs(n=20):
    """Детерминированный набор: «основа» (вертикаль или шатёр ^) и 1–3 штриха вокруг (свой seed)."""
    out = []
    state = 0xF1A3E
    for i in range(n):
        g = [_FIRE_STROKES[0]] if i % 3 else [_FIRE_STROKES[1], _FIRE_STROKES[2]]
        k = 1 + (i * 5) % 3
        for _ in range(k):
            state = (state * 1103515245 + 12345) & 0x7fffffff
            st = _FIRE_STROKES[3 + (state >> 7) % (len(_FIRE_STROKES) - 3)]
            if st not in g:
                g.append(st)
        out.append(g)
    return out


def sprite_fire_runes():
    """K12: 20 угловатых знаков в полосе 38..48 (заливка #ffd25a, обвод 1 px #ff7a12), тонкие границы полосы,
    ромбики-разделители."""
    L = Layer()
    L.fire_line(np.abs(RR - 48.6), 0.3, glow=False, core=FIRE[4], edge=FIRE[2])
    L.fire_line(np.abs(RR - 37.4), 0.3, glow=False, core=FIRE[4], edge=FIRE[2])
    glyphs = fire_glyphs()
    n = len(glyphs)
    cell = 2 * math.pi / n
    idx = np.floor(ANG / cell).astype(int) % n
    a_mid = (idx + 0.5) * cell
    tx = (ANG - a_mid) * 43.0
    ty = 43.0 - RR
    dist = np.full(RR.shape, 99.0)
    G = 1.55
    for gi, g in enumerate(glyphs):
        m = idx == gi
        for (ax, ay), (bx, by) in g:
            d = seg_dist((ax - 2) * G, (ay - 2) * G, (bx - 2) * G, (by - 2) * G, tx[m], ty[m])
            dist[m] = np.minimum(dist[m], d)
    dist[~((RR > 38.0) & (RR < 48.0))] = 99.0
    L.put(dist < 2.6, FIRE[2], 110)                               # мягкий жар вокруг знака
    L.put(dist < 1.75, FIRE[2], 255)                              # обвод #ff7a12
    L.put(dist < 0.85, (255, 210, 90), 255)                       # заливка #ffd25a
    for i in range(n):                                            # ромбики-разделители
        x, y = polar_xy(43.0, i * cell)
        d = np.abs(DX - x) + np.abs(DY - y)
        L.put(d < 2.2, FIRE[2], 255)
        L.put(d < 1.1, FIRE[5], 255)
    return L.image(KINDS['fire_runes'], True)


def sprite_fire_sigil():
    """K13: гексаграмма (два треугольника, r 33, линии 2 px #ffe08a), малая пентаграмма r 20, белое ядро r ≤ 5 с мягким
    свечением до r 14."""
    L = Layer()
    for r, col, al in [(14, FIRE[2], 60), (11.5, FIRE[3], 85), (9, FIRE[3], 110), (7, FIRE[4], 140)]:
        L.put(RR < r, col, al)
    dist = np.full(RR.shape, 99.0)
    for off in (0.0, math.pi):
        pts = [polar_xy(33.0, off + 2 * math.pi * k / 3) for k in range(3)]
        for k in range(3):
            (ax, ay), (bx, by) = pts[k], pts[(k + 1) % 3]
            dist = np.minimum(dist, seg_dist(ax, ay, bx, by))
    L.fire_line(dist, 1.0, core=FIRE[4], edge=FIRE[3])
    pd = np.full(RR.shape, 99.0)
    pts = [polar_xy(20.0, 2 * math.pi * k / 5) for k in range(5)]
    for k in range(5):
        (ax, ay), (bx, by) = pts[k], pts[(k + 2) % 5]
        pd = np.minimum(pd, seg_dist(ax, ay, bx, by))
    L.fire_line(pd, 0.55, core=FIRE[5], edge=FIRE[3])
    for k in range(6):                                            # раскалённые вершины гексаграммы
        x, y = polar_xy(33.0, math.pi * k / 3)
        _dot(L, x, y, 3.4, 2.2, 1.2)
    L.put(RR < 5.0, FIRE[4], 255)
    L.put(RR < 3.5, FIRE[5], 255)
    return L.image(KINDS['fire_sigil'], True)


def sprite_fire_flames():
    """K14 (плоскость в 1.25 раза шире): 22 языка пламени наружу от r_s 46 до ≤ 61 (у основания #ffb43a, к кончику
    #d2400a, слегка закручены), мягкое свечение r_s 31..46 у основания языков (#ff7a12, альфа 40..90, к центру гаснет), ~40 искр (альфа ровно 254)."""
    L = Layer()
    for r, al in [(46.0, 90), (44.0, 75), (41.5, 60), (38.5, 48), (35.0, 40), (31.0, 0)]:
        m = RR < r                                                # кольцо свечения у основания языков (к центру гаснет)
        L.rgb[m] = FIRE[2]
        L.a[m] = al
    n = 22
    rng = np.random.default_rng(14)
    lens = 11.0 + 4.0 * rng.random(n)
    lens[::2] = np.minimum(lens[::2] + 1.5, 15.0)
    for k in range(n):
        a0 = 2 * math.pi * k / n
        ln = lens[k]
        u = (RR - 45.0) / ln                                      # 0 у основания … 1 кончик
        ang = a0 + 0.10 * np.clip(u, 0, 1) ** 1.5                 # закрутка по часовой
        da = (ANG - ang + math.pi) % (2 * math.pi) - math.pi
        off = da * RR                                             # px поперёк языка
        hw = 4.6 * np.clip(1 - u, 0, 1) ** 0.75 * np.clip(0.55 + 2.2 * u, 0, 1)
        inside = (u >= 0) & (u <= 1) & (np.abs(off) < hw)
        core = inside & (np.abs(off) < hw * 0.45) & (u < 0.55)
        col = np.zeros(RR.shape + (3,))
        uu = np.clip(u, 0, 1)[..., None]
        base, mid, tip = np.array(FIRE[3], float), np.array(FIRE[2], float), np.array(FIRE[1], float)
        col = np.where(uu < 0.5, base + (mid - base) * (uu / 0.5), mid + (tip - mid) * ((uu - 0.5) / 0.5))
        col = np.round(col / 16) * 16                             # ступени цвета (чёткие полосы)
        al = np.round((235 - 95 * np.clip(u, 0, 1)) / 15) * 15
        m = inside & (al >= L.a)
        L.rgb[m] = col[m]
        L.a[m] = al[m]
        L.rgb[core] = FIRE[4]
        L.a[core] = np.maximum(L.a[core], 235)
    # искры: альфа ровно 254 (класс мерцания в шейдере), r_s 18..58
    pts = 0
    erng = np.random.default_rng(1414)
    while pts < 40:
        r = 18 + 40 * math.sqrt(erng.random()) if pts % 3 else 47 + 11 * erng.random()
        a = 2 * math.pi * erng.random()
        x, y = polar_xy(r, a)
        ix, iy = int(round(C + x)), int(round(C + y))
        if not (3 <= ix <= 124 and 3 <= iy <= 124) or L.a[iy, ix] == 254:
            continue
        cells = [(ix, iy)] + ([(ix + 1, iy), (ix, iy + 1)] if pts % 4 == 0 else [])
        for cx, cy in cells:
            L.rgb[cy, cx] = FIRE[5] if pts % 2 else FIRE[4]
            L.a[cy, cx] = 254
        pts += 1
    return L.image(KINDS['fire_flames'], True)


# ------------------------------------------------------------------------------------------- комета (K15..K19)
def heat(s):
    """Цвет хвоста по s (0 голова … 1 кончик) — как в шейдере. s — массив."""
    s = np.asarray(s, np.float64)[..., None]
    c0, c1, c2, c3 = (np.array(v) for v in ((1.0, 0.97, 0.85), (1.0, 0.78, 0.3), (1.0, 0.5, 0.1), (0.85, 0.2, 0.03)))
    a = c0 + (c1 - c0) * np.clip(s / 0.18, 0, 1)
    b = c1 + (c2 - c1) * np.clip((s - 0.18) / 0.32, 0, 1)
    c = c2 + (c3 - c2) * np.clip((s - 0.5) / 0.5, 0, 1)
    return np.where(s < 0.18, a, np.where(s < 0.5, b, c))


def comet_tail_art():
    """Хвост «вверх» (K15): поперёк — u (x), вдоль — v (y, 0 голова … 1 кончик). Альфа — штрихи вдоль (как у луча,
    шейдер берёт их как узор), цвет — огненный градиент (виден без шейдера)."""
    w = (XX - 2 + 0.5) / 124.0
    v = (YY - 2 + 0.5) / 124.0
    d = np.abs(2 * w - 1)
    lane = np.clip(np.floor(w * 12), 0, 11).astype(int)
    period = 30 + (lane * 11) % 29
    length = 10 + (lane * 7) % 15
    phase = (lane * 37) % 64
    dash = (((YY - 2) + phase) % period) < length
    a = np.zeros((SPRITE, SPRITE))
    for lim, al_off, al_on in reversed([(0.25, 210, 255), (0.5, 150, 230), (0.75, 90, 170), (0.95, 40, 110)]):
        m = d < lim
        a[m] = np.where(dash[m], al_on, al_off)
    rgb = heat(np.clip(v, 0, 1)) * 255.0
    whit = (np.clip(1 - d / 0.3, 0, 1) * np.clip(1 - v / 0.5, 0, 1))[..., None]
    rgb = rgb * (1 - whit) + np.array(FIRE[5], float) * whit
    rgb = np.round(rgb / 8) * 8
    inner = (XX >= 2) & (XX < 126) & (YY >= 2) & (YY < 126)
    a[~inner] = 0
    return rgb, a


def tail_sprite(kind_name):
    rgb, a = comet_tail_art()
    if kind_name == 'comet_tail_down':
        rgb, a = rgb[::-1], a[::-1]
    elif kind_name == 'comet_tail_west':
        rgb, a = rgb.transpose(1, 0, 2), a.T
    elif kind_name == 'comet_tail_east':
        rgb, a = rgb.transpose(1, 0, 2)[:, ::-1], a.T[:, ::-1]
    L = Layer()
    L.rgb, L.a = rgb.copy(), a.copy()
    return L.image(KINDS[kind_name], False)


def sprite_comet_core():
    """K19: радиальная голова кометы: белое ядро r ≤ 10, жёлтое 10..22 (альфа 255 → 200), оранжевое 22..40
    (200 → 90), красно-оранжевое 40..58 (90 → 0)."""
    L = Layer()
    r = RR
    rgb = np.zeros((SPRITE, SPRITE, 3))
    a = np.zeros((SPRITE, SPRITE))
    bands = [(0, 10, (255, 255, 240), 255, 255), (10, 22, (255, 226, 120), 255, 200),
             (22, 40, (255, 140, 30), 200, 90), (40, 58, (230, 70, 10), 90, 0)]
    for r0, r1, col, a0, a1 in bands:
        m = (r >= r0) & (r < r1)
        rgb[m] = col
        a[m] = a0 + (a1 - a0) * (r[m] - r0) / (r1 - r0)
    L.rgb, L.a = rgb, a
    return L.image(KINDS['comet_core'], True)


# ------------------------------------------------------------------------------------------- палка: «Удар в спину» и «Угли»
SLASH_HALF = math.radians(110.0)  # шейдер MS_SLASH_HALF: серп от −110° до +110° (0 — вверх, по часовой)
SLASH_RO = 58.0                   # внешний край серпа, px
SLASH_W = 25.0                    # наибольшая толщина серпа (посередине дуги), px
# Огонь серпа по глубине от внешней кромки (u = 0) внутрь (u = 1): белый накал → золото → оранжевый → алый → розовый.
SLASH_RAMP = [(0.00, (255, 255, 245), 255), (0.06, (255, 240, 180), 255), (0.18, (255, 186, 70), 255),
              (0.38, (255, 110, 30), 248), (0.60, (250, 45, 45), 228), (0.80, (255, 50, 120), 188),
              (1.00, (255, 90, 170), 90)]


def _ramp(u, ramp):
    """Кусочно-линейная палитра: u (массив 0..1) → (rgb, alpha)."""
    u = np.clip(u, 0.0, 1.0)
    rgb = np.zeros(u.shape + (3,))
    a = np.zeros(u.shape)
    for (u0, c0, a0), (u1, c1, a1) in zip(ramp, ramp[1:]):
        m = (u >= u0) & (u <= u1)
        k = ((u[m] - u0) / (u1 - u0))[..., None]
        rgb[m] = np.array(c0) * (1 - k) + np.array(c1) * k
        a[m] = a0 + (a1 - a0) * k[..., 0]
    return rgb, a


def slash_progress(ang):
    """Угол (0 — вверх, по часовой, радианы) → доля дуги серпа p (0 — левый кончик, 1 — правый), вне дуги — <0 / >1."""
    a = (np.asarray(ang, float) + math.pi) % (2 * math.pi) - math.pi
    return (a + SLASH_HALF) / (2 * SLASH_HALF)


def sprite_slash_crescent():
    """K28: огненный серп. Толщина sin(πp)^0.7 (кончики острые), внешняя кромка — белый накал, внутрь — золото,
    оранжевый, алый, розовый дым; внутри — два тонких «следа» (вихрь) и искры снаружи. Плавные градиенты."""
    p = slash_progress(ANG)
    on = (p >= 0) & (p <= 1)
    w = SLASH_W * np.sin(np.pi * np.clip(p, 0, 1)) ** 0.7
    u = (SLASH_RO - RR) / np.maximum(w, 1e-6)
    rgb, a = _ramp(u, SLASH_RAMP)
    edge = np.clip((SLASH_RO + 0.8 - RR) / 1.6, 0, 1)          # мягкая (1.6 px) внешняя кромка
    body = on & (u <= 1.0) & (w > 0.6)
    a = np.where(body, a * edge, 0.0)
    # вихрь: два тонких следа внутри серпа (короче и тусклее), розово-оранжевые
    for r0, p0, p1, wid, col, al in ((SLASH_RO - SLASH_W - 5.0, 0.12, 0.86, 2.6, (255, 120, 70), 170),
                                     (SLASH_RO - SLASH_W - 12.0, 0.28, 0.74, 2.0, (255, 80, 150), 120)):
        q = np.clip((p - p0) / (p1 - p0), 0, 1)
        inside = (p >= p0) & (p <= p1)
        ww = wid * np.sin(np.pi * q) ** 0.8
        d = np.abs(RR - r0)
        m = inside & (d < ww + 1.0)
        k = np.clip(1 - d / (ww + 1.0), 0, 1)
        aa = al * k
        upd = m & (aa > a)
        rgb[upd] = col
        a[upd] = aa[upd]
    # искры над кромкой: детерминированные точки
    rng = np.random.default_rng(28)
    for _ in range(14):
        pp = rng.uniform(0.15, 0.95)
        rr = SLASH_RO - rng.uniform(-1.5, 6.0)
        if rr > CIRCLE_ART_R - 2.5:
            rr = CIRCLE_ART_R - 2.5
        ang = (pp * 2 - 1) * SLASH_HALF
        x, y = polar_xy(rr, ang)
        d = np.hypot(DX - x, DY - y)
        m = d < 1.6
        col = (255, 240, 200) if rng.random() < 0.5 else (255, 160, 80)
        upd = m & (a < 230)
        rgb[upd] = col
        a[upd] = 230
    L = Layer()
    L.rgb, L.a = rgb, a
    return L.image(KINDS['slash_crescent'], True)


def sprite_slash_streak():
    """K29: белая вспышка-штрих: горизонтальная линия через центр (длина 112 px, к концам сужается) и малая
    четырёхлучевая звезда в центре. Альфа = «близость к оси» (255 — ядро, к краю каймы падает до 8): шейдер гасит
    штрих порогом по альфе — линия истончается, а не сереет. Цвет по альфе: ядро белое, кайма — розово-оранжевая."""
    half = 56.0
    k = np.clip(1 - np.abs(DX) / half, 0, 1)
    core = 0.35 + 1.25 * k ** 1.4                                # полутолщина ядра, px
    glow = core + 1.5 + 3.5 * k
    d = np.abs(DY)
    line = np.where(k > 0, np.clip(1 - np.maximum(d - core, 0) / (glow - core), 0, 1), 0.0)
    line = np.where(d <= core, 1.0, line * 0.92)
    star = np.zeros_like(RR)
    for ang, ln in ((0.0, 10.0), (math.pi / 2, 20.0), (math.pi, 10.0), (3 * math.pi / 2, 20.0)):
        x, y = polar_xy(ln, ang)
        dd = seg_dist(0, 0, x, y)
        w = 0.4 + 1.6 * np.clip(1 - RR / ln, 0, 1)
        star = np.maximum(star, np.clip(1 - np.maximum(dd - w, 0) / 3.0, 0, 1) * (dd < w + 3.0))
    bloom = np.clip(1 - RR / 10.0, 0, 1) ** 1.2
    v = np.maximum(np.maximum(line, star), bloom)
    a = np.where(v > 0.03, 8 + 247 * v, 0)
    rgb = np.array([255, 120, 120]) * (1 - v[..., None]) + np.array([255, 255, 255]) * v[..., None]
    rgb = np.where((v < 0.75)[..., None], np.array([255, 150, 110]) * (1 - v[..., None] / 0.75)
                   + np.array([255, 225, 230]) * (v[..., None] / 0.75), rgb)
    L = Layer()
    L.rgb, L.a = rgb, a
    return L.image(KINDS['slash_streak'], True)


def _tile_noise(h, w, cells_v, cells_u, seed, octaves=3):
    """Плавный шум 0..1, бесшовный по обеим осям (значения в узлах сетки, косинусная интерполяция)."""
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        cv, cu = cells_v * 2 ** o, cells_u * 2 ** o
        g = rng.random((cv, cu))
        y = np.arange(h) / h * cv
        x = np.arange(w) / w * cu
        y0 = np.floor(y).astype(int)
        x0 = np.floor(x).astype(int)
        fy = (1 - np.cos(np.pi * (y - y0))) / 2
        fx = (1 - np.cos(np.pi * (x - x0))) / 2
        Y0, X0 = np.meshgrid(y0 % cv, x0 % cu, indexing='ij')
        Y1, X1 = np.meshgrid((y0 + 1) % cv, (x0 + 1) % cu, indexing='ij')
        FY, FX = np.meshgrid(fy, fx, indexing='ij')
        v = (g[Y0, X0] * (1 - FX) + g[Y0, X1] * FX) * (1 - FY) + (g[Y1, X0] * (1 - FX) + g[Y1, X1] * FX) * FY
        out += amp * v
        tot += amp
        amp *= 0.5
    return out / tot


def ember_beam_art():
    """Луч «Углей» «вверх» (K30): поперёк — u, вдоль — v (бесшовно по v: шейдер прокручивает). Альфа — дымный шум
    (шейдер берёт его как «клубы» и искры: альфа ≥ 236 — искра), цвет — для вида без шейдера: алое ядро, тёмно-
    красные края."""
    n = _tile_noise(124, 124, 4, 3, 30)
    n = (n - n.min()) / (n.max() - n.min())
    sp = _tile_noise(124, 124, 24, 60, 31, octaves=1)
    a_in = 30 + 200 * n
    a_in = np.where(sp > 0.9, 250, a_in)                       # искры
    w = (np.arange(124) + 0.5) / 124
    d = np.abs(2 * w - 1)[None, :].repeat(124, 0)
    col = np.where((d < 0.3)[..., None], np.array([255, 90, 40]), np.where((d < 0.65)[..., None],
                   np.array([160, 16, 10]), np.array([40, 6, 6])))
    rgb = np.zeros((SPRITE, SPRITE, 3))
    a = np.zeros((SPRITE, SPRITE))
    rgb[2:126, 2:126] = col
    a[2:126, 2:126] = a_in
    return rgb, a


def ember_beam_sprite(kind_name):
    rgb, a = ember_beam_art()
    if kind_name == 'ember_beam_down':
        rgb, a = rgb[::-1], a[::-1]
    elif kind_name == 'ember_beam_west':
        rgb, a = rgb.transpose(1, 0, 2), a.T
    elif kind_name == 'ember_beam_east':
        rgb, a = rgb.transpose(1, 0, 2)[:, ::-1], a.T[:, ::-1]
    L = Layer()
    L.rgb, L.a = rgb.copy(), a.copy()
    return L.image(KINDS[kind_name], False)


def ember_sprite(name, base):
    """Красная копия спрайта круга: голубые тона → алые (R' = B, G' = 0.5·R, B' = 0.36·R): тёмно-синий → багровый,
    голубой → алый, светло-голубой → коралловый, белый → тёплый белый. Своя метка K в (1,0)."""
    a = base.copy()
    body = a[..., 3] != 3
    r = base[..., 0].astype(np.float64)
    b = base[..., 2].astype(np.float64)
    a[..., 0][body] = b[body].astype(np.uint8)
    a[..., 1][body] = np.clip(np.round(r * 0.5), 0, 255).astype(np.uint8)[body]
    a[..., 2][body] = np.clip(np.round(r * 0.36), 0, 255).astype(np.uint8)[body]
    a[0, 1] = (KINDS[name], 90, 126, 3)
    return a


SPRITE_FUNCS = {'ring': sprite_ring, 'runes': sprite_runes, 'hexagram': sprite_hexagram, 'core': sprite_core,
                'flare': sprite_flare, 'hex_cell': sprite_hex_cell,
                'fire_ring': sprite_fire_ring, 'fire_runes': sprite_fire_runes, 'fire_sigil': sprite_fire_sigil,
                'fire_flames': sprite_fire_flames, 'comet_core': sprite_comet_core,
                'slash_crescent': sprite_slash_crescent, 'slash_streak': sprite_slash_streak}


def tp_sprite(name, base):
    """Фиолетовая копия спрайта круга: R↔G, своя метка K в (1,0)."""
    a = base.copy()
    body = a[..., 3] != 3
    a[..., 0][body], a[..., 1][body] = base[..., 1][body], base[..., 0][body]
    a[0, 1] = (KINDS[name], 90, 126, 3)
    return a


def all_sprites():
    out = {}
    for name in KINDS:
        if name in TP_CIRCLE or name in EMBER_CIRCLE:
            continue
        if name in SPRITE_FUNCS:
            out[name] = SPRITE_FUNCS[name]()
        elif name in TAILS:
            out[name] = tail_sprite(name)
        elif name in EMBER_BEAMS:
            out[name] = ember_beam_sprite(name)
        else:
            out[name] = beam_sprite(name)
    for name, base in TP_CIRCLE.items():
        out[name] = tp_sprite(name, out[base])
    for name, base in EMBER_CIRCLE.items():
        out[name] = ember_sprite(name, out[base])
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


def tp_circle_model():
    m = circle_model()
    m['textures'] = {k: v.replace('/ring', '/tp_ring').replace('/runes', '/tp_runes').replace('/hexagram', '/tp_hexagram')
                     .replace('/core', '/tp_core') for k, v in m['textures'].items()}
    return m


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


def fire_circle_model():
    """Огненный круг: 4 плоскости XY (грани north/south). Языки пламени — первая, в 1.25 раза шире (выходят за обод);
    затем кольца, руны, печать; шаг 0.1 px по z."""
    layers = [('4', -2, 18, 8.25), ('1', 0, 16, 8.15), ('2', 0, 16, 8.05), ('3', 0, 16, 7.95)]
    els = []
    for tex, a, b, z in layers:
        els.append({'from': [a, a, z], 'to': [b, b, z], 'shade': False, 'light_emission': 15,
                    'faces': {'north': _face(tex), 'south': _face(tex)}})
    fx = f'{NS}:item/moon_staff_fx/'
    return {'textures': {'1': fx + 'fire_ring', '2': fx + 'fire_runes', '3': fx + 'fire_sigil', '4': fx + 'fire_flames',
                         'particle': fx + 'fire_ring'},
            'elements': els}


def comet_model():
    """Комета: ось хвоста — +Z модели, голова в (8,8,8) (= начало дисплея), хвост z 8..32 (12 px шириной, 24 длиной):
    5 «горизонтальных» плоскостей (up K15 / down K16, повороты вокруг z 0, ±22.5, ±45) и 3 «вертикальных»
    (west K17 / east K18, 0, ±22.5). Голова — 3 ортогональные плоскости 12 px (K19) и ещё две горизонтальные,
    повёрнутые на ±45° вокруг z."""
    els = []

    def el(frm, to, faces, ang=0):
        e = {'from': frm, 'to': to, 'shade': False, 'light_emission': 15,
             'faces': {f: _face(t) for f, t in faces.items()}}
        if ang:
            e['rotation'] = {'angle': ang, 'axis': 'z', 'origin': [8, 8, 8]}
        els.append(e)

    for ang in (0, 22.5, -22.5, 45, -45):
        el([2, 8, 8], [14, 8, 32], {'up': '1', 'down': '2'}, ang)
    for ang in (0, 22.5, -22.5):
        el([8, 2, 8], [8, 14, 32], {'west': '3', 'east': '4'}, ang)
    el([2, 2, 8], [14, 14, 8], {'north': '5', 'south': '5'})
    el([2, 8, 2], [14, 8, 14], {'up': '5', 'down': '5'})
    el([8, 2, 2], [8, 14, 14], {'west': '5', 'east': '5'})
    for ang in (45, -45):
        el([2, 8, 2], [14, 8, 14], {'up': '5', 'down': '5'}, ang)
    fx = f'{NS}:item/moon_staff_fx/'
    return {'textures': {'1': fx + 'comet_tail_up', '2': fx + 'comet_tail_down', '3': fx + 'comet_tail_west',
                         '4': fx + 'comet_tail_east', '5': fx + 'comet_core', 'particle': fx + 'comet_core'},
            'elements': els}


def ember_orb_model():
    """Сфера «Углей» (core_weapons): модель круга посоха со спрайтами ember_* (K24..27)."""
    m = circle_model()
    m['textures'] = {k: v.replace('/ring', '/ember_ring').replace('/runes', '/ember_runes')
                     .replace('/hexagram', '/ember_hexagram').replace('/core', '/ember_core')
                     for k, v in m['textures'].items()}
    return m


def ember_beam_model():
    """Луч «Углей»: звезда из 8 плоскостей вдоль +Z (z 8..16, как moon_staff_beam), без вспышки у начала (там сфера)."""
    m = beam_model()
    m['elements'] = m['elements'][:8]
    fx = f'{NS}:item/moon_staff_fx/'
    m['textures'] = {'1': fx + 'ember_beam_up', '2': fx + 'ember_beam_down', '3': fx + 'ember_beam_west',
                     '4': fx + 'ember_beam_east', 'particle': fx + 'ember_beam_up'}
    return m


def slash_model():
    """«Удар в спину»: серп (K28, плоскость 16×16 px, z 8.05) и вспышка-штрих (K29, 20×20 px, z 7.95, повёрнута на
    −22.5° вокруг z — наискось через серп). Грани north и south (видно с обеих сторон)."""
    fx = f'{NS}:item/moon_staff_fx/'
    return {'textures': {'1': fx + 'slash_crescent', '2': fx + 'slash_streak', 'particle': fx + 'slash_crescent'},
            'elements': [{'from': [0, 0, 8.05], 'to': [16, 16, 8.05], 'shade': False, 'light_emission': 15,
                          'faces': {'north': _face('1'), 'south': _face('1')}},
                         {'from': [-2, -2, 7.95], 'to': [18, 18, 7.95], 'shade': False, 'light_emission': 15,
                          'rotation': {'angle': -22.5, 'axis': 'z', 'origin': [8, 8, 8]},
                          'faces': {'north': _face('2'), 'south': _face('2')}}]}


EFFECT_MODELS = {'moon_staff_circle': circle_model, 'moon_staff_beam': beam_model, 'moon_staff_barrier': barrier_model,
                 'moon_staff_fire_circle': fire_circle_model, 'moon_staff_comet': comet_model,
                 'teleport_circle': tp_circle_model,
                 'stick_ember_orb': ember_orb_model, 'stick_ember_beam': ember_beam_model, 'stick_slash': slash_model}


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
    for name, fn in EFFECT_MODELS.items():
        model = fn()
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
    """kind 1..33, lx/ly — msLocal (0..1, массивы), param — msParam, t — секунды. → RGBA (…, 4) 0..1+."""
    lx = np.asarray(lx, np.float64)
    ly = np.asarray(ly, np.float64)
    out = np.zeros(lx.shape + (4,))
    violet = 20 <= kind <= 23                 # K20..23 — фиолетовый круг телепорта (логика K1..4)
    if violet:
        kind -= 19
    red = 24 <= kind <= 27                    # K24..27 — красная сфера «Углей» (логика K1..4)
    if red:
        kind -= 23
    if kind in (28, 29):
        # «Удар в спину»: msParam = (g + 125·C)/250, g — тик появления mod 125, C — крит. Возраст в тиках: t·20 = GameTime·24000.
        code = int(math.floor(param * 250.0 + 0.5))
        crit = code // 125
        cr = float(crit)
        age = (t * 20.0 - (code - 125 * crit)) % 125.0
        if age > 117.0:
            age = 0.0
        dx, dy = lx - 0.5, ly - 0.5
        r = np.hypot(dx, dy)
        if kind == 28:
            p = (np.arctan2(dx, -dy) + SLASH_HALF) / (2 * SLASH_HALF)
            head = 1.15 * float(smoothstep(0.0, 3.0, age))
            tail = 1.25 * float(smoothstep(3.0, 8.5, age)) - 0.2
            vis = np.clip((head - p) / 0.08, 0, 1) * np.clip((p - tail) / 0.18, 0, 1)
            fade = 1.0 - float(smoothstep(4.0, 9.0, age))
            tex = _sample(spr, lx, ly)
            halo = _sample_halo(spr, lx, ly)
            fl = 0.88 + 0.12 * np.sin(TAU * (p * 5.0 - t * 4.0) + r * 60.0)
            lit = tex[..., :3] * (fl * (1.0 + 0.25 * cr))[..., None]
            hx = (head - p) / 0.07
            lit = lit + np.array([1.0, 0.85, 0.6]) * (np.exp(-hx * hx) * (1.0 - float(smoothstep(2.5, 4.0, age))) * 0.8)[..., None]
            glow = np.array([1.0, 0.3, 0.22]) * (1 - cr) + np.array([1.0, 0.45, 0.7]) * cr
            ta = tex[..., 3:4]
            rgb = glow * (1 - ta) + lit * ta
            if crit == 1:
                rgb = rgb * 0.82 + np.array([1.0, 0.9, 0.95]) * 0.18
            al = np.maximum(tex[..., 3], halo * (0.45 + 0.25 * cr)) * vis * fade
            al = np.where(r > EDGE, 0.0, al)
            out[..., :3], out[..., 3] = rgb, np.clip(al, 0, 1)
            return out
        on = float(smoothstep(0.5, 1.5, age)) * (1.0 - float(smoothstep(2.5 + 2.0 * cr, 6.0 + 2.0 * cr, age)))
        reach = 0.5 * float(smoothstep(0.5, 2.5, age)) + 0.03
        tex = _sample(spr, lx, ly)
        thr = 1.02 - on
        rgb = tex[..., :3] * (1.0 + 0.15 * cr)
        al = (smoothstep(thr, thr + 0.12, tex[..., 3]) * np.minimum(1.0, tex[..., 3] * (1.1 + 0.3 * cr))
              * (1.0 - smoothstep(reach - 0.04, reach, np.abs(dx))))
        al = np.where(r > EDGE, 0.0, al)
        out[..., :3], out[..., 3] = rgb, np.clip(al, 0, 1)
        return out
    if kind >= 30:
        # Луч «Углей» (ориентации K5..8): клубы дыма — две прокрутки шума, алое ядро, искры (альфа ≥ 236).
        kb = kind - 25
        s_ = {5: ly, 6: 1 - ly, 7: lx, 8: 1 - lx}[kb]
        w = lx if kb in (5, 6) else ly
        L = max(param * 250.0 / 8.0, 0.5)
        wob = 0.03 * np.sin(TAU * (s_ * L * 0.5 - t * 3.0))
        d = np.abs(2 * (w + wob) - 1)
        ph1 = (s_ * L * 0.25 - t * 2.0) % 1.0
        ph2 = (s_ * L * 0.5 - t * 5.0 + 0.37) % 1.0
        n1 = (_sample(spr, w, ph1) if kb <= 6 else _sample(spr, ph1, w))[..., 3]
        n2 = (_sample(spr, 1 - w, ph2) if kb <= 6 else _sample(spr, ph2, 1 - w))[..., 3]
        smoke = 0.6 * n1 + 0.4 * n2
        core = 1 - smoothstep(0.0, 0.3, d)
        mid = 1 - smoothstep(0.15, 0.62, d)
        outer = 1 - smoothstep(0.3, 1.0, d + 0.3 * (0.5 - smoke))
        rgb = np.array([0.09, 0.012, 0.01]) * (1 - mid)[..., None] + np.array([0.78, 0.05, 0.02]) * mid[..., None]
        k = (core * (0.65 + 0.35 * n2))[..., None]
        rgb = rgb * (1 - k) + np.array([1.0, 0.5, 0.2]) * k
        spark = (n2 >= 0.925) * (1 - smoothstep(0.5, 0.9, d))
        rgb = rgb * (1 - spark)[..., None] + np.array([1.0, 0.72, 0.35]) * spark[..., None]
        taper = smoothstep(0.0, 0.4 / L, s_) * (1 - smoothstep(1 - 1.2 / L, 1.0, s_))
        al = np.clip(outer * (0.3 + 0.5 * smoke) + mid * 0.45 + core * 0.5 + spark * 0.5, 0, 1) * taper
        out[..., :3], out[..., 3] = rgb, al
        return out
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
    if 11 <= kind <= 14:
        # Огненный круг (копия K1..4 со своими скоростями, ореолом и мерцанием).
        c = param
        a = {11: TAU * t / 20.0, 12: -TAU * t / 12.0, 13: TAU * t / 30.0, 14: 0.0}[kind]
        qx, qy = _rot(lx, ly, a)
        flick = 1.0
        if kind == 14:
            dqx, dqy = qx - 0.5, qy - 0.5
            fa = np.arctan2(dqx, -dqy)
            k = 1.0 + 0.035 * np.sin(TAU * 2.0 * t + 24.0 * fa)
            qx, qy = 0.5 + dqx * k, 0.5 + dqy * k
            flick = (0.85 + 0.15 * np.sin(TAU * 5.0 * t + 7.0 * fa))[..., None]
        tex = _sample(spr, qx, qy)
        halo = _sample_halo(spr, qx, qy)
        pulse = 0.5 + 0.5 * math.sin(TAU * 1.5 * t)
        if kind == 12:
            ang = np.arctan2(lx - 0.5, -(ly - 0.5)) % TAU
            appear = smoothstep(ang, ang + 0.15, np.full_like(lx, c * (TAU + 0.15)))
        elif kind == 13:
            appear = np.full_like(lx, smoothstep(0.2, 0.65, c))
        elif kind == 14:
            appear = np.full_like(lx, 0.35 + 0.65 * c)
        else:
            appear = np.ones_like(lx)
        bright = 0.8 + 0.35 * c + 0.1 * pulse
        if c >= 0.999:
            bright += 0.25 * (0.5 + 0.5 * math.sin(TAU * 4.0 * t))
        lit = tex[..., :3] * bright * flick
        lit = lit + np.array([0.0, 0.35, 0.25]) * np.maximum(lit[..., :1] - 1.0, 0.0)
        glow = np.array([1.0, 0.56, 0.16]) * (0.85 + 0.3 * c)
        ta = tex[..., 3:4]
        rgb = glow * (1 - ta) + lit * ta
        al = np.maximum(tex[..., 3], halo * 0.55) * appear
        if kind == 14:
            ember = np.abs(tex[..., 3] * 255.0 - 254.0) < 0.5        # nearest = textureLod(…, 0)
            tw = np.maximum(0.5 + 0.5 * np.sin(TAU * t / 1.5 + qx * 37.0 + qy * 23.0), 0.0) ** 6.0
            rgb_e = np.array([1.0, 0.62, 0.2]) * (1 - tw)[..., None] + np.array([1.6, 1.35, 0.85]) * tw[..., None]
            rgb = np.where(ember[..., None], rgb_e, rgb)
            al = np.where(ember, (0.55 + 0.45 * tw) * appear, al)
        al = np.where(np.hypot(qx - 0.5, qy - 0.5) > EDGE, 0.0, al)
        out[..., :3], out[..., 3] = rgb, al
        return out
    if kind >= 15:
        code = int(math.floor(param * 250.0 + 0.5))
        burst = code // 125
        g = code - 125 * burst
        if kind <= 18:
            kb = kind - 10
            s = {5: ly, 6: 1 - ly, 7: lx, 8: 1 - lx}[kb]
            w = lx if kb in (5, 6) else ly
            d = np.abs(2 * w - 1)
            ph = (s * 3.0 - t * 4.0 + g / 125.0) % 1.0
            st = _sample(spr, w, ph) if kind <= 16 else _sample(spr, ph, w)
            streak = st[..., 3]
            width = 0.95 + (0.15 - 0.95) * s
            body = 1 - smoothstep(0.0, width, d)
            rgb = heat(s) * (0.9 + 0.25 * streak)[..., None]
            al = body * (0.55 + 0.45 * streak) * np.maximum(1 - s, 0.0) ** 1.3 * smoothstep(0.0, 0.05, s)
            if burst == 1:
                al = al * 0.35
            out[..., :3], out[..., 3] = rgb, np.clip(al, 0, 1)
            return out
        rr = np.hypot(lx - 0.5, ly - 0.5)
        tex = _sample(spr, lx, ly)
        halo = _sample_halo(spr, lx, ly)
        pulse = 0.9 + 0.1 * math.sin(TAU * 8.0 * t + g)
        rgb = tex[..., :3] * pulse + np.array([1.0, 0.6, 0.15]) * (halo * 0.6)[..., None]
        al = np.maximum(tex[..., 3], halo * 0.5)
        if burst == 1:
            rgb = rgb * 0.45 + 0.55
            al = np.minimum(1.0, al * 1.4 + 0.1 * (1 - smoothstep(0.3, EDGE, rr)))
        al = np.where(rr > EDGE, 0.0, al)
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
        glowC = [1.0, 0.2, 0.1] if red else ([0.8, 0.35, 1.0] if violet else [0.35, 0.8, 1.0])
        rgb = tex[..., :3] * bright + np.array(glowC) * (halo * 0.6 * (0.5 + 0.5 * c))[..., None]
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


# ------------------------------------------------------------------------------------------- «Огненный шар»: 3D-превью
# Копии формул core_magic/keisan.py (流星R, 炎陣回転, 流星回転, 落下点, 出発点, 発射tick) — только для превью; (g) в
# moonstaff_check сверяет 流星R/流星B基 с keisan, если он есть.
METEOR_B = 125                    # keisan.流星B基
METEOR_G_MAX = 124                # keisan.流星g上限


def meteor_R(g, B):
    """R цвета кометы: g + 125·B (g 0..124 — зерно мерцания, B 0 — падает, 1 — вспышка удара), максимум 249."""
    return min(max(int(g), 0), METEOR_G_MAX) + METEOR_B * min(max(int(B), 0), 1)


def meteor_R_decode(R):
    """Как шейдер: code = floor(R/250·250 + 0.5) → (g, B)."""
    code = int(math.floor(R / 250.0 * 250.0 + 0.5))
    B = code // METEOR_B
    return code - METEOR_B * B, B


FIRE_CIRCLE_Q = (0.0, math.sqrt(0.5), math.sqrt(0.5), 0.0)   # keisan.炎陣回転: rotationX(π/2).rotateY(π)


def quat_mat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def quat_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz)


def comet_quat(u):
    """keisan.流星回転: rotationTo(+Z → u) ⊗ Ry(π); u ≈ −Z → ось Y, угол π."""
    u = np.asarray(u, float) / np.linalg.norm(u)
    dot = float(u[2])
    if dot < -1 + 1e-9:
        rt = (0.0, 1.0, 0.0, 0.0)
    else:
        ax = np.cross([0.0, 0.0, 1.0], u)
        rt = (float(ax[0]), float(ax[1]), float(ax[2]), 1.0 + dot)
        n = math.sqrt(sum(v * v for v in rt))
        rt = tuple(v / n for v in rt)
    return quat_mul(rt, (0.0, 1.0, 0.0, 0.0))


RY_PI = np.diag([-1.0, 1.0, -1.0])   # ItemDisplay сам поворачивает модель на 180° вокруг Y

_FACEINFO = {
    'down': [('x0', 'y0', 'z1'), ('x0', 'y0', 'z0'), ('x1', 'y0', 'z0'), ('x1', 'y0', 'z1')],
    'up': [('x0', 'y1', 'z0'), ('x0', 'y1', 'z1'), ('x1', 'y1', 'z1'), ('x1', 'y1', 'z0')],
    'north': [('x1', 'y1', 'z0'), ('x1', 'y0', 'z0'), ('x0', 'y0', 'z0'), ('x0', 'y1', 'z0')],
    'south': [('x0', 'y1', 'z1'), ('x0', 'y0', 'z1'), ('x1', 'y0', 'z1'), ('x1', 'y1', 'z1')],
    'west': [('x0', 'y1', 'z0'), ('x0', 'y0', 'z0'), ('x0', 'y0', 'z1'), ('x0', 'y1', 'z1')],
    'east': [('x1', 'y1', 'z1'), ('x1', 'y0', 'z1'), ('x1', 'y0', 'z0'), ('x1', 'y1', 'z0')],
}


def bake_quads(model):
    """Ванильный порядок вершин FaceInfo: [(texture_var, 4×3 вершины в px модели после поворота элемента)];
    вершина 0 — msLocal (0,0), 1 — (0,1), 3 — (1,0)."""
    out = []
    for e in model['elements']:
        (x0, y0, z0), (x1, y1, z1) = e['from'], e['to']
        env = dict(x0=x0, y0=y0, z0=z0, x1=x1, y1=y1, z1=z1)
        R = np.eye(3)
        o = np.zeros(3)
        if 'rotation' in e:
            a = math.radians(e['rotation']['angle'])
            c, s = math.cos(a), math.sin(a)
            R = {'x': np.array([[1, 0, 0], [0, c, -s], [0, s, c]]), 'y': np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
                 'z': np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[e['rotation']['axis']]
            o = np.array(e['rotation']['origin'], float)
        for face, fd in e['faces'].items():
            pts = np.array([[env[k] for k in v] for v in _FACEINFO[face]], float)
            out.append((fd['texture'].lstrip('#'), (R @ (pts - o).T).T + o))
    return out


def display_quads(model, pos, T, q, scale, R, t=0.0):
    """Квады дисплея в мире: world = pos + T + L(q)·Ry(π)·scale·(px/16 − 0.5). → [(K, имя, P0, Eu, Ev, R, t)]."""
    M = quat_mat(q) @ RY_PI * scale
    base = np.asarray(pos, float) + np.asarray(T, float)
    out = []
    for var, pts in bake_quads(model):
        name = model['textures'][var].split('/')[-1]
        w = (M @ (pts / 16.0 - 0.5).T).T + base
        out.append((KINDS[name], name, w[0], w[3] - w[0], w[1] - w[0], R, t))
    return out


class Camera:
    def __init__(self, pos, look, w, h, fov=70.0):
        self.pos = np.asarray(pos, float)
        f = np.asarray(look, float) - self.pos
        f /= np.linalg.norm(f)
        up = np.array([0.0, 1.0, 0.0]) if abs(f[1]) < 0.99 else np.array([0.0, 0.0, -1.0])
        r = np.cross(f, up)
        r /= np.linalg.norm(r)
        self.f, self.r, self.u = f, r, np.cross(r, f)
        self.w, self.h = w, h
        self.k = math.tan(math.radians(fov) / 2)
        ys, xs = np.mgrid[0:h, 0:w] + 0.5
        nx = (xs / w * 2 - 1) * self.k * w / h
        ny = (1 - ys / h * 2) * self.k
        d = self.f + nx[..., None] * self.r + ny[..., None] * self.u
        self.dirs = d / np.linalg.norm(d, axis=-1, keepdims=True)

    def project(self, p):
        v = np.asarray(p, float) - self.pos
        z = v @ self.f
        if z <= 1e-6:
            return None
        x = (v @ self.r) / z / (self.k * self.w / self.h)
        y = (v @ self.u) / z / self.k
        return (x + 1) / 2 * self.w, (1 - y) / 2 * self.h, z


def sky_ground(cam, ground_y=None, glow=()):
    """Ночное небо; при ground_y — земля (тёмный камень/трава) с отсветом огня glow = [(точка, радиус, сила)]."""
    img = night_sky(cam.w, cam.h)
    if ground_y is not None:
        d = cam.dirs
        lam = (ground_y - cam.pos[1]) / np.where(d[..., 1] < -1e-6, d[..., 1], -1e-6)
        hit = (d[..., 1] < -1e-6)
        X = cam.pos + lam[..., None] * d
        chk = ((np.floor(X[..., 0]) + np.floor(X[..., 2])) % 2)[..., None]
        col = np.array([0.10, 0.13, 0.09]) * (1 - chk) + np.array([0.12, 0.15, 0.10]) * chk
        fog = np.clip(lam / 60.0, 0, 1)[..., None]
        col = col * (1 - fog) + np.array([0.05, 0.06, 0.1]) * fog
        for p, r, s in glow:
            dd = np.hypot(X[..., 0] - p[0], X[..., 2] - p[2])
            col = col + np.array([1.0, 0.45, 0.1]) * (s * np.clip(1 - dd / r, 0, 1) ** 2)[..., None]
        img = np.where(hit[..., None], col, img)
    return img


def render_quads(cam, quads, img):
    """Квады (не premultiplied) поверх img, от дальних к ближним; задняя грань не рисуется (cull, как в игре)."""
    order = sorted(quads, key=lambda q: -np.linalg.norm(q[2] + (q[3] + q[4]) / 2 - cam.pos))
    for K, name, P0, Eu, Ev, R, t in order:
        n = np.cross(Ev, Eu)
        cen = P0 + (Eu + Ev) / 2
        if (cam.pos - cen) @ n <= 0:
            continue
        cs = [cam.project(P0 + a * Eu + b * Ev) for a in (0, 1) for b in (0, 1)]
        if any(c is None for c in cs):
            x0, x1, y0, y1 = 0, cam.w, 0, cam.h
        else:
            x0 = max(int(min(c[0] for c in cs)) - 1, 0)
            x1 = min(int(max(c[0] for c in cs)) + 2, cam.w)
            y0 = max(int(min(c[1] for c in cs)) - 1, 0)
            y1 = min(int(max(c[1] for c in cs)) + 2, cam.h)
        if x0 >= x1 or y0 >= y1:
            continue
        d = cam.dirs[y0:y1, x0:x1]
        den = d @ n
        ok = np.abs(den) > 1e-9
        lam = ((P0 - cam.pos) @ n) / np.where(ok, den, 1.0)
        X = cam.pos + lam[..., None] * d - P0
        a = (X @ Eu) / (Eu @ Eu)
        b = (X @ Ev) / (Ev @ Ev)
        ins = ok & (lam > 0) & (a >= 0) & (a <= 1) & (b >= 0) & (b <= 1)
        if not ins.any():
            continue
        col = shade(K, SPRITES_CACHE[name], np.clip(a, 0, 1), np.clip(b, 0, 1), R / 250.0, t)
        col[..., 3] *= ins
        img[y0:y1, x0:x1] = over(img[y0:y1, x0:x1], col)
    return img


SPRITES_CACHE = {}


def add_glow_points(cam, img, pts, rad_px, color, strength):
    """Частицы-«точки» (огонь, искры): мягкие пятна, аддитивно. pts — [(точка, множитель)]."""
    for p, m in pts:
        pr = cam.project(p)
        if pr is None:
            continue
        x, y, z = pr
        r = max(rad_px * 18.0 / z, 1.0)
        x0, x1 = max(int(x - 2 * r), 0), min(int(x + 2 * r) + 1, cam.w)
        y0, y1 = max(int(y - 2 * r), 0), min(int(y + 2 * r) + 1, cam.h)
        if x0 >= x1 or y0 >= y1:
            continue
        ys, xs = np.mgrid[y0:y1, x0:x1] + 0.5
        f = np.exp(-((xs - x) ** 2 + (ys - y) ** 2) / (r * r))
        img[y0:y1, x0:x1] += np.array(color) * (strength * m * f)[..., None]
    return img


def scatter(n, radius, first_center, rng):
    """keisan.落下点: равномерно по диску r = R·√u, θ = 2π·u₂; комета 0 — в центре."""
    out = []
    for i in range(n):
        if i == 0 and first_center:
            out.append((0.0, 0.0))
            continue
        r = radius * math.sqrt(rng.random())
        th = 2 * math.pi * rng.random()
        out.append((r * math.cos(th), r * math.sin(th)))
    return out


def launch_ticks(n, spread):
    """keisan.発射tick."""
    return [0] if n == 1 else [int(math.floor(i * spread / (n - 1))) for i in range(n)]
