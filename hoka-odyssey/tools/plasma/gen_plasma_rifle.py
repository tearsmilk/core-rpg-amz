# Плазменная винтовка ПВ-7 (plasma_rifle) — наша модель для модуля guns, собранная кодом (не TACZ).
#
# Пишет в guns/assets-hoka/:
#   models/plasma_rifle.bbmodel      — модель от первого лица (кости, кубы, текстура, анимации, hoka_view, hoka_hidden);
#   models/plasma_rifle_lod.bbmodel  — та же модель без рук и «камер» с разделом display — предмет hoitems;
#   textures/plasma_rifle_hud.png    — силуэт для HUD (384×128, серый, ствол влево, как у остальных);
#   textures/plasma_rifle_slot.png   — иконка в инвентаре (64×64);
#   textures/plasma_cell.png         — иконка «Плазменной ячейки» (32×32);
#   textures/plasma_flash.png        — голубая вспышка у ствола (128×128);
#   textures/plasma_wire_stock.png   — иконка встроенного каркасного приклада для окна обвеса (64×64).
# И превью (вид сбоку и 3/4) — путь первым аргументом (необязательно).
#
# Формат — как у конвертированных моделей TACZ (geo2bbmodel.py): .bbmodel 4.10, «free»; ключи анимаций — в
# соглашении 4.x (ядро при загрузке переворачивает rotation x,y и position x, поэтому здесь они записаны
# перевёрнутыми). Система координат — как у ak47.bbmodel (ствол к −Z, ось канала y = 8.6, глаз idle_view сзади),
# поэтому оружие лежит в руках так же, как АКМ. Положения рук (righthand_pos / lefthand_pos) посчитаны под нашу
# рукоять и цевьё (кость + поворот, растяжение руки по длине ×1,5 — в static_idle).
#
# Текстура рисуется здесь же (у каждой грани куба свой прямоугольник: цвет материала, шум, светлая/тёмная кромка),
# кости с окончанием _illuminated клиентский модуль рисует на полной яркости (полосы ячейки, кончики стволов,
# индикаторы). Запуск: python3 tools/plasma/gen_plasma_rifle.py [превью.png]
import base64, io, json, math, os, random, sys, uuid as uuidlib

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, '..', '..', 'guns', 'assets-hoka'))
NAME = 'plasma_rifle'

BORE = 8.6          # ось канала (как muzzle_pos у АКМ)
IRON_Y = 11.6       # линия прицеливания (целик и мушка)


def uid(seed):
    return str(uuidlib.uuid5(uuidlib.NAMESPACE_URL, 'hoka-plasma/' + seed))


# ------------------------------------------------------------------ материалы (RGB) и рисунок граней
MAT = {
    'navy':      (34, 48, 92),
    'navy_l':    (48, 68, 124),
    'navy_d':    (22, 30, 58),
    'steel':     (58, 66, 86),
    'gun':       (36, 40, 52),
    'black':     (16, 18, 26),
    'red':       (138, 26, 34),
    'red_d':     (92, 16, 24),
    'rubber':    (28, 28, 34),
    'cyan':      (70, 226, 255),
    'cyan_w':    (190, 248, 255),
    'white':     (236, 250, 255),
    'blue_glow': (60, 150, 255),
}
GLOW = {'cyan', 'cyan_w', 'white', 'blue_glow'}


class Model:
    def __init__(self):
        self.bones = {}          # name -> dict(name, origin, rotation, parent, children[], cubes[])
        self.order = []
        self.roots = []

    def bone(self, name, origin, parent=None, rotation=(0, 0, 0)):
        b = {'name': name, 'origin': [float(v) for v in origin], 'rotation': [float(v) for v in rotation],
             'parent': parent, 'children': [], 'cubes': []}
        self.bones[name] = b
        self.order.append(name)
        if parent is None:
            self.roots.append(name)
        else:
            self.bones[parent]['children'].append(name)
        return name

    def cube(self, bone, frm, to, mat, origin=None, rotation=(0, 0, 0), pattern=None, name=None):
        frm = [float(min(a, b)) for a, b in zip(frm, to)]
        to2 = [float(max(a, b)) for a, b in zip(frm, to)]
        c = {'bone': bone, 'from': frm, 'to': to2, 'mat': mat, 'pattern': pattern,
             'origin': [float(v) for v in (origin if origin is not None else [0, 0, 0])],
             'rotation': [float(v) for v in rotation], 'name': name or mat}
        self.bones[bone]['cubes'].append(c)
        return c


def box(m, bone, x0, x1, y0, y1, z0, z1, mat, **kw):
    return m.cube(bone, (x0, y0, z0), (x1, y1, z1), mat, **kw)


def sym(m, bone, x0, x1, y0, y1, z0, z1, mat, **kw):
    """Пара кубов, зеркальных по x (x0..x1 — правая сторона, x > 0)."""
    box(m, bone, x0, x1, y0, y1, z0, z1, mat, **kw)
    box(m, bone, -x1, -x0, y0, y1, z0, z1, mat, **kw)


# ------------------------------------------------------------------ руки: положение под нашу рукоять и цевьё
# Матрица кости руки (точка кости + поворот, ось руки ×1,5) — как у рук от первого лица в клиентском модуле: рука
# игрока ставится в кость *_pos. Числа подобраны под нашу пистолетную рукоять (z ≈ 7…9) и цевьё (z ≈ −10).
HAND_R = np.array([[-0.9962, -0.1307, 0.0, -3.74],
                   [-0.0151, 0.2595, -0.9848, 1.86],
                   [0.0858, -1.4716, -0.1736, 24.25]])
HAND_L = np.array([[-0.5532, 0.4648, 0.7733, -3.19],
                   [-0.6693, 0.5809, -0.6340, 4.93],
                   [-0.4959, -1.3025, -0.0069, 8.70]])
ARM_STRETCH = (1.0, 1.5, 1.0)


def euler_zyx(R):
    """R = Rz·Ry·Rx (градусы), как Blockbench."""
    y = -math.asin(max(-1.0, min(1.0, R[2, 0])))
    x = math.atan2(R[2, 1], R[2, 2])
    z = math.atan2(R[1, 0], R[0, 0])
    return [round(math.degrees(v), 4) for v in (x, y, z)]


def hand_bone(W):
    A = W[:, :3].copy()
    R = A / np.array(ARM_STRETCH)[None, :]
    # ортонормировать (числа округлены)
    u, _, vt = np.linalg.svd(R)
    R = u @ vt
    return [round(float(v), 4) for v in W[:, 3]], euler_zyx(R)


# ------------------------------------------------------------------ геометрия
def build():
    m = Model()
    m.bone('root', (0, BORE, 6.0))
    m.bone('body', (0, BORE, 0), 'root')

    # --- ствольная коробка
    m.bone('receiver', (0, BORE, 4), 'body')
    box(m, 'receiver', -1.05, 1.05, 6.2, 8.6, -2.6, 10.0, 'navy', pattern='panel')          # нижняя
    box(m, 'receiver', -1.15, 1.15, 8.6, 10.35, -2.3, 10.0, 'navy_l', pattern='panel')      # верхняя
    box(m, 'receiver', -0.95, 0.95, 10.35, 10.65, -2.0, 9.6, 'navy_d')                      # крышка
    box(m, 'receiver', -1.2, 1.2, 6.4, 10.4, 9.6, 10.25, 'gun')                              # задняя плита
    box(m, 'receiver', -1.12, 1.12, 9.85, 10.45, -2.6, -1.9, 'steel')                       # передний пояс
    sym(m, 'receiver', 1.05, 1.2, 7.55, 8.05, -2.2, 9.4, 'red')                              # красная полоса
    sym(m, 'receiver', 1.15, 1.22, 9.2, 9.95, -1.6, 1.6, 'black', pattern='vents')           # жалюзи сбоку
    sym(m, 'receiver', 1.05, 1.15, 6.25, 6.55, 0.0, 9.0, 'steel')                            # нижняя кромка
    box(m, 'receiver', -1.2, -1.15, 9.4, 9.8, 1.6, 6.6, 'black')                             # паз рукояти заряжания
    # магазинная шахта
    box(m, 'receiver', -1.2, 1.2, 5.55, 6.25, -0.7, 2.75, 'gun', pattern='panel')
    box(m, 'receiver', -1.25, 1.25, 5.35, 5.6, -0.8, 2.85, 'red_d')
    # спусковая скоба и крючок
    box(m, 'receiver', -0.28, 0.28, 4.55, 4.85, 3.0, 6.7, 'gun')
    box(m, 'receiver', -0.28, 0.28, 4.55, 6.25, 3.0, 3.3, 'gun')
    box(m, 'receiver', -0.14, 0.14, 5.15, 6.25, 4.6, 5.0, 'steel', origin=(0, 6.2, 4.8), rotation=(-10, 0, 0))
    # индикаторы (светятся): правая сторона — три, левая — два
    m.bone('lights_illuminated', (1.2, 9.6, 7), 'receiver')
    box(m, 'lights_illuminated', 1.15, 1.3, 9.45, 9.8, 6.4, 6.85, 'cyan')
    box(m, 'lights_illuminated', 1.15, 1.3, 9.45, 9.8, 7.2, 7.65, 'cyan')
    box(m, 'lights_illuminated', 1.15, 1.3, 9.45, 9.8, 8.0, 8.45, 'white')
    box(m, 'lights_illuminated', -1.3, -1.15, 9.45, 9.8, 7.6, 8.05, 'cyan')
    box(m, 'lights_illuminated', -1.3, -1.15, 9.45, 9.8, 8.4, 8.85, 'cyan_w')
    box(m, 'lights_illuminated', -1.21, -1.15, 8.85, 9.0, -1.4, 1.4, 'blue_glow')          # тонкая полоска слева
    # переводчик огня (справа)
    m.bone('selector', (1.2, 7.8, 8.2), 'receiver')
    box(m, 'selector', 1.2, 1.38, 7.62, 7.98, 7.0, 9.0, 'steel')
    box(m, 'selector', 1.2, 1.45, 7.5, 8.1, 8.6, 9.05, 'red')
    # рукоять заряжания (слева)
    m.bone('charge_handle', (-1.2, 9.6, 2.5), 'receiver')
    box(m, 'charge_handle', -2.0, -1.15, 9.38, 9.82, 2.05, 2.95, 'steel')
    box(m, 'charge_handle', -2.35, -2.0, 9.25, 9.95, 1.95, 3.05, 'red')

    # --- планка сверху (крепление прицелов)
    m.bone('rail', (0, 10.9, 3.5), 'body')
    box(m, 'rail', -0.7, 0.7, 10.6, 10.95, -1.6, 8.9, 'gun')
    z = -1.4
    while z < 8.8:
        box(m, 'rail', -0.8, 0.8, 10.95, 11.2, z, z + 0.5, 'steel')
        z += 1.0

    # --- механический прицел (TACZ-правило: кость sight прячется, когда стоит прицел)
    m.bone('sight', (0, 11.2, 0), 'body')
    box(m, 'sight', -0.8, 0.8, 11.2, 11.45, 7.6, 8.6, 'gun')                                # целик: основание
    sym(m, 'sight', 0.22, 0.8, 11.45, 11.95, 7.75, 8.45, 'navy_d')                           # «уши» целика
    box(m, 'sight', -0.5, 0.5, 10.7, 11.05, -13.3, -12.3, 'gun')                            # мушка: основание
    sym(m, 'sight', 0.3, 0.52, 11.05, 11.95, -13.2, -12.4, 'navy_d')                         # защита мушки
    box(m, 'sight', -0.11, 0.11, 11.05, 11.5, -12.95, -12.65, 'steel')                      # мушка
    m.bone('sight_illuminated', (0, 11.55, -12.8), 'sight')
    box(m, 'sight_illuminated', -0.1, 0.1, 11.5, 11.62, -12.92, -12.68, 'cyan_w')

    # --- цевьё
    m.bone('handguard', (0, BORE, -6.5), 'body')
    box(m, 'handguard', -1.25, 1.25, 6.55, 10.25, -10.8, -2.6, 'navy', pattern='panel')
    box(m, 'handguard', -0.95, 0.95, 10.25, 10.55, -10.6, -2.8, 'navy_d')
    box(m, 'handguard', -0.95, 0.95, 6.3, 6.55, -10.4, -3.0, 'navy_d')
    sym(m, 'handguard', 1.25, 1.32, 8.3, 9.7, -10.2, -3.4, 'black', pattern='vents_z')       # прорези
    box(m, 'handguard', -1.38, 1.38, 6.45, 10.4, -11.4, -10.8, 'red')                       # красное кольцо
    box(m, 'handguard', -1.32, 1.32, 6.5, 10.35, -3.0, -2.6, 'steel')
    box(m, 'handguard', -0.7, 0.7, 6.0, 6.3, -9.8, -4.0, 'gun', pattern='rail_y')           # планка снизу (рукоять)
    box(m, 'handguard', -1.55, -1.25, 8.2, 9.0, -9.6, -5.4, 'gun', pattern='rail_z')        # планка слева (ЛЦУ)

    # --- воротник-радиатор перед стволами
    m.bone('collar', (0, BORE, -12.5), 'body')
    box(m, 'collar', -2.0, 2.0, 6.9, 10.3, -13.9, -11.3, 'navy_l', pattern='panel')
    box(m, 'collar', -1.6, 1.6, 6.5, 10.7, -13.9, -11.3, 'navy_l')
    for zf in (-13.6, -12.85, -12.1):                                                         # рёбра радиатора
        box(m, 'collar', -2.25, 2.25, 7.1, 10.1, zf, zf + 0.35, 'steel')
        box(m, 'collar', -1.8, 1.8, 6.3, 10.9, zf, zf + 0.35, 'steel')
    box(m, 'collar', -2.05, 2.05, 6.85, 10.35, -11.4, -11.15, 'red_d')
    m.bone('collar_illuminated', (0, BORE, -13.95), 'collar')
    for (x0, x1, y0, y1) in ((-1.45, 1.45, 10.25, 10.4), (-1.45, 1.45, 6.8, 6.95),
                             (1.3, 1.45, 6.95, 10.25), (-1.45, -1.3, 6.95, 10.25)):
        box(m, 'collar_illuminated', x0, x1, y0, y1, -14.0, -13.9, 'cyan')

    # --- вращающийся блок из четырёх стволов (поворот вокруг оси канала — в анимации shoot)
    m.bone('barrels', (0, BORE, -13.9), 'body')
    box(m, 'barrels', -0.45, 0.45, BORE - 0.45, BORE + 0.45, -19.6, -13.9, 'gun')            # ось
    r = 0.82
    tips = []
    for k in range(4):
        a = math.radians(45 + 90 * k)
        cx, cy = r * math.cos(a), BORE + r * math.sin(a)
        box(m, 'barrels', cx - 0.42, cx + 0.42, cy - 0.42, cy + 0.42, -20.5, -13.9, 'steel', pattern='barrel')
        tips.append((cx, cy))
    for zc, mat in ((-14.9, 'navy_d'), (-17.3, 'red_d'), (-19.9, 'navy_d')):               # обоймы стволов
        box(m, 'barrels', -1.55, 1.55, BORE - 1.55, BORE + 1.55, zc - 0.25, zc + 0.25, mat)
        box(m, 'barrels', -1.55, 1.55, BORE - 1.55, BORE + 1.55, zc - 0.22, zc + 0.22, mat,
            origin=(0, BORE, zc), rotation=(0, 0, 45))
    m.bone('barrel_tips_illuminated', (0, BORE, -20.6), 'barrels')
    for cx, cy in tips:
        box(m, 'barrel_tips_illuminated', cx - 0.36, cx + 0.36, cy - 0.36, cy + 0.36, -20.75, -20.45, 'cyan_w')
        box(m, 'barrel_tips_illuminated', cx - 0.43, cx + 0.43, cy - 0.43, cy + 0.43, -20.45, -20.2, 'cyan')

    # --- пистолетная рукоять
    m.bone('grip', (0, 6.2, 7.3), 'body')
    gk = dict(origin=(0, 6.2, 7.3), rotation=(-15, 0, 0))
    box(m, 'grip', -0.8, 0.8, 1.6, 6.3, 6.2, 8.5, 'navy_d', pattern='grip', **gk)
    box(m, 'grip', -0.55, 0.55, 1.9, 6.0, 8.5, 8.8, 'red', **gk)
    box(m, 'grip', -0.85, 0.85, 1.35, 1.65, 6.1, 8.7, 'gun', **gk)

    # --- энергоячейка (изогнутый «рожок» из сегментов) — кость magazine, для перезарядки
    m.bone('magazine', (0, 6.2, 1.0), 'body')
    m.bone('mag_standard', (0, 6.2, 1.0), 'magazine')
    m.bone('cell_illuminated', (0, 4.0, 0), 'mag_standard')
    p = np.array([6.2, 1.0])            # (y, z) верх сегмента
    ang = 0.0
    seg_h = 1.95
    for i in range(4):
        y, zc = float(p[0]), float(p[1])
        kw = dict(origin=(0, y, zc), rotation=(ang, 0, 0))
        box(m, 'mag_standard', -1.05, 1.05, y - seg_h - 0.05, y, zc - 1.55, zc + 1.55, 'navy', pattern='panel', **kw)
        box(m, 'mag_standard', -1.1, 1.1, y - 0.25, y - 0.05, zc - 1.6, zc + 1.6, 'gun', **kw)
        sym(m, 'cell_illuminated', 1.05, 1.12, y - 1.25, y - 0.85, zc - 1.2, zc + 1.2, 'cyan', **kw)
        box(m, 'cell_illuminated', -0.3, 0.3, y - 1.45, y - 0.65, zc - 1.62, zc - 1.55, 'cyan_w', **kw)
        ang += 8.0
        a = math.radians(ang - 4.0)
        p = p + np.array([-seg_h * math.cos(a), -seg_h * math.sin(a)])
    y, zc = float(p[0]), float(p[1])
    box(m, 'mag_standard', -1.2, 1.2, y - 0.45, y + 0.05, zc - 1.7, zc + 1.7, 'red_d',
        origin=(0, y, zc), rotation=(ang - 8.0, 0, 0))
    m.bone('additional_magazine', (0, 6.2, 1.0), 'body')

    # --- каркасный приклад (встроенный обвес plasma_wire_stock: кость = display.adapter)
    m.bone('plasma_wire_stock', (0, 8.7, 10.25), 'body')
    st = 'plasma_wire_stock'
    box(m, st, -1.0, 1.0, 6.6, 10.2, 10.25, 11.0, 'gun')                                     # шарнир
    box(m, st, -1.05, 1.05, 8.2, 8.7, 10.3, 10.95, 'red')
    sym(m, st, 0.35, 0.75, 9.55, 9.95, 11.0, 20.7, 'steel')                                  # верхние тяги
    # нижние тяги — наклонные
    dz, dy = 20.7 - 11.0, 4.7 - 6.9
    L = math.hypot(dz, dy)
    a = math.degrees(math.atan2(-dy, dz))
    sym(m, st, 0.35, 0.75, 6.7, 7.1, 11.0, 11.0 + L, 'steel', origin=(0, 6.9, 11.0), rotation=(a, 0, 0))
    # раскос
    dz2, dy2 = 18.2 - 13.0, 5.4 - 9.6
    L2 = math.hypot(dz2, dy2)
    a2 = math.degrees(math.atan2(-dy2, dz2))
    box(m, st, -0.3, 0.3, 9.4, 9.75, 13.0, 13.0 + L2, 'steel', origin=(0, 9.6, 13.0), rotation=(a2, 0, 0))
    box(m, st, -0.85, 0.85, 9.4, 10.1, 15.8, 17.0, 'navy_d')                                 # щека
    box(m, st, -1.0, 1.0, 4.2, 10.5, 20.6, 21.5, 'navy', pattern='panel')                    # затыльник
    box(m, st, -1.05, 1.05, 4.0, 10.7, 21.5, 21.9, 'rubber', pattern='grip')
    box(m, st, -1.08, 1.08, 6.9, 7.5, 20.55, 21.55, 'red')

    # --- трубка для прикладов AR (переходник TACZ ar_stock_adapter — видна только с таким прикладом)
    m.bone('ar_stock_adapter', (0, 8.7, 10.25), 'body')
    box(m, 'ar_stock_adapter', -0.65, 0.65, 8.05, 9.35, 10.25, 13.8, 'gun')
    box(m, 'ar_stock_adapter', -0.8, 0.8, 7.9, 9.5, 10.25, 10.8, 'steel')

    # --- точки обвеса и вспышки
    m.bone('positioning2', (0, 0, 0), 'body')
    m.bone('scope_pos', (0, 11.2, 3.5), 'positioning2')
    m.bone('muzzle_pos', (0, BORE, -20.75), 'positioning2')
    m.bone('muzzle_flash', (0, BORE, -21.6), 'positioning2')
    m.bone('laser_pos', (-1.55, 8.6, -7.5), 'positioning2', rotation=(0, 0, 90))
    m.bone('grip_pos', (0, 6.0, -7.0), 'positioning2')
    m.bone('stock_pos', (0, 8.7, 13.4), 'positioning2')

    # --- руки
    o, r_ = hand_bone(HAND_R)
    m.bone('righthand', o, 'root', rotation=r_)
    m.bone('righthand_pos', o, 'righthand')
    o, r_ = hand_bone(HAND_L)
    m.bone('lefthand', o, 'root', rotation=r_)
    m.bone('lefthand_pos', o, 'lefthand')

    # --- «глаза»
    m.bone('camera', (-2.5, 12.5, 15.0), None, rotation=(0, 0, -2.5))
    m.bone('views', (0, 12, 12))
    m.bone('idle_view', (-2.5, 12.5, 15.0), 'views', rotation=(0, 0, -2.5))
    m.bone('iron_view', (0, IRON_Y, 14.5), 'views')
    m.bone('refit_view', (-28.0, 9.5, 0.5), 'views', rotation=(0, -89.9, 0))
    m.bone('refit_scope_view', (-13.5, 12.7, 16.5), 'views', rotation=(0, -50, 0))
    m.bone('refit_stock_view', (-16.0, 9.0, 14.0), 'views', rotation=(0, -90, 0))
    m.bone('refit_laser_view', (-12.0, 12.0, -21.0), 'views', rotation=(-15, -135, 0))
    m.bone('refit_grip_view', (-16.0, 5.0, -7.0), 'views', rotation=(0, -90, 0))
    return m


# ------------------------------------------------------------------ текстура: раскладка граней и рисунок
FACES = ('north', 'south', 'east', 'west', 'up', 'down')
TEXEL = 2   # текселей на пиксель модели


def face_size(c, f):
    sx, sy, sz = (c['to'][i] - c['from'][i] for i in range(3))
    return {'north': (sx, sy), 'south': (sx, sy), 'east': (sz, sy), 'west': (sz, sy),
            'up': (sx, sz), 'down': (sx, sz)}[f]


def layout(cubes):
    rects = []
    for ci, c in enumerate(cubes):
        for f in FACES:
            w, h = face_size(c, f)
            rects.append((ci, f, max(1, math.ceil(w * TEXEL - 1e-6)), max(1, math.ceil(h * TEXEL - 1e-6))))
    for size in (128, 256, 512, 1024):
        order = sorted(rects, key=lambda r: (-r[3], -r[2]))
        x = y = shelf = 0
        pos = {}
        ok = True
        for ci, f, w, h in order:
            if x + w > size:
                x, y, shelf = 0, y + shelf, 0
            if y + h > size:
                ok = False
                break
            pos[(ci, f)] = (x, y, w, h)
            x += w
            shelf = max(shelf, h)
        if ok:
            return size, pos
    raise RuntimeError('не помещается')


def paint(cubes, size, pos, seed=7):
    rnd = np.random.default_rng(seed)
    img = np.zeros((size, size, 4), np.float32)
    for (ci, f), (x, y, w, h) in pos.items():
        c = cubes[ci]
        base = np.array(MAT[c['mat']], np.float32)
        glow = c['mat'] in GLOW
        tile = np.tile(base, (h, w, 1))
        if glow:
            # светящиеся: к центру светлее (почти белое ядро)
            yy, xx = np.mgrid[0:h, 0:w]
            d = np.maximum(np.abs((xx + 0.5) / w - 0.5), np.abs((yy + 0.5) / h - 0.5)) * 2
            k = np.clip(1 - d, 0, 1)[..., None]
            tile = tile * (0.85 + 0.15 * k) + (255 - tile) * 0.45 * k
        else:
            n = rnd.normal(0, 1, (h, w, 1)).astype(np.float32)
            tile = tile * (1 + 0.045 * n)
            pat = c['pattern']
            if pat == 'panel' and w >= 6 and h >= 6:
                # панели: тонкие тёмные швы через ~4 пикселя модели
                step = 8
                for xx in range(step, w - 2, step):
                    tile[:, xx:xx + 1] *= 0.72
                if h > 10:
                    tile[h // 2:h // 2 + 1, :] *= 0.75
            elif pat in ('vents', 'vents_z'):
                for xx in range(1, w, 3):
                    tile[1:h - 1, xx:xx + 1] = np.array(MAT['navy_d']) * 0.8
            elif pat in ('rail_y', 'rail_z'):
                for xx in range(0, w, 2):
                    tile[:, xx:xx + 1] *= 0.7
            elif pat == 'grip':
                yy, xx = np.mgrid[0:h, 0:w]
                tile[((xx + yy) % 3 == 0)] *= 0.7
            elif pat == 'barrel':
                for xx in range(2, w, 4):
                    tile[:, xx:xx + 1] *= 0.8
            # кромка: верх/лево светлее, низ/право темнее (воксельная «фаска»)
            if w >= 3 and h >= 3:
                tile[0, :] *= 1.18
                tile[:, 0] *= 1.12
                tile[h - 1, :] *= 0.72
                tile[:, w - 1] *= 0.8
        img[y:y + h, x:x + w, :3] = np.clip(tile, 0, 255)
        img[y:y + h, x:x + w, 3] = 255
    return Image.fromarray(img.astype(np.uint8), 'RGBA')


def png_b64(im):
    b = io.BytesIO()
    im.save(b, 'PNG', optimize=True)
    return 'data:image/png;base64,' + base64.b64encode(b.getvalue()).decode()


# ------------------------------------------------------------------ анимации
def flip(ch, v):
    # .bbmodel 4.x: ключи поворота x,y и сдвига x — с обратным знаком (ядро переворачивает обратно)
    x, y, z = v
    if ch == 'rotation':
        return [-x, -y, z]
    if ch == 'position':
        return [-x, y, z]
    return [x, y, z]


def anim(m, name, length, loop, tracks, sounds=()):
    animators = {}
    for bone, chans in tracks.items():
        keys = []
        for ch, kl in chans.items():
            for k in kl:
                t, v = k[0], k[1]
                interp = k[2] if len(k) > 2 else 'linear'
                vv = flip(ch, v)
                keys.append({'channel': ch, 'data_points': [{'x': round(vv[0], 4), 'y': round(vv[1], 4),
                                                             'z': round(vv[2], 4)}],
                             'time': round(t, 4), 'interpolation': interp,
                             'uuid': uid(f'{name}/{bone}/{ch}/{t}')})
        animators[uid('bone/' + bone)] = {'name': bone, 'type': 'bone', 'keyframes': keys}
    return {'uuid': uid('anim/' + name), 'name': name, 'loop': loop, 'override': False, 'length': length,
            'snapping': 60, 'animators': animators,
            'hoka_sounds': [{'time': t, 'effect': 'hoka:' + s} for t, s in sounds]}


ARM = [(0, ARM_STRETCH)]


def animations(m):
    S = 'plasma_rifle_'
    A = []
    A.append(anim(m, 'static_idle', 0.0, 'loop', {
        'righthand': {'scale': ARM}, 'lefthand': {'scale': ARM}}))
    A.append(anim(m, 'static_auto', 0.0, 'loop', {'selector': {'rotation': [(0, (0, 0, 0))]}}))
    A.append(anim(m, 'static_semi', 0.0, 'loop', {'selector': {'rotation': [(0, (-35, 0, 0))]}}))
    for nm, frm, to in (('switch_auto', -35, 0), ('switch_semi', 0, -35)):
        A.append(anim(m, nm, 0.45, 'once', {
            'selector': {'rotation': [(0.12, (frm, 0, 0)), (0.2, (to, 0, 0))]},
            'root': {'rotation': [(0, (0, 0, 0)), (0.12, (1.5, -2, 4), 'catmullrom'), (0.25, (0.5, -1, 2), 'catmullrom'),
                                  (0.45, (0, 0, 0), 'catmullrom')]}}))
    # выстрел: короткий толчок назад и вверх, блок стволов проворачивается на 90° (4 ствола — по одному на выстрел)
    A.append(anim(m, 'shoot', 0.4, 'once', {
        'root': {'position': [(0, (0, 0, 0)), (0.03, (0.05, 0.12, 0.75)), (0.1, (0, 0.05, 0.25), 'catmullrom'),
                              (0.22, (0, 0, 0), 'catmullrom')],
                 'rotation': [(0, (0, 0, 0)), (0.03, (1.4, 0.2, -0.6)), (0.1, (0.5, -0.1, 0.4), 'catmullrom'),
                              (0.25, (0, 0, 0), 'catmullrom')]},
        'barrels': {'rotation': [(0, (0, 0, 0)), (0.02, (0, 0, -20)), (0.09, (0, 0, -80)), (0.13, (0, 0, -90))]},
        'camera': {'rotation': [(0, (0, 0, 0)), (0.02, (-0.2, 0, 0.25)), (0.06, (0.1, 0, -0.3), 'catmullrom'),
                                (0.15, (0, 0, 0), 'catmullrom')]}}))
    # доставание и убирание
    A.append(anim(m, 'draw', 0.6, 'once', {
        'root': {'rotation': [(0, (18, -25, 30)), (0.15, (10, -12, 14), 'catmullrom'),
                              (0.32, (-1.5, 2, -2), 'catmullrom'), (0.45, (0.5, -0.5, 0.5), 'catmullrom'),
                              (0.6, (0, 0, 0), 'catmullrom')],
                 'position': [(0, (2, -9, 3)), (0.15, (1, -4, 1.5), 'catmullrom'), (0.32, (0, 0.3, 0), 'catmullrom'),
                              (0.6, (0, 0, 0), 'catmullrom')]},
        'camera': {'rotation': [(0, (0, 0, 0)), (0.2, (0.8, -0.2, 0.5), 'catmullrom'), (0.6, (0, 0, 0), 'catmullrom')]},
        'barrels': {'rotation': [(0, (0, 0, -180)), (0.45, (0, 0, 0), 'catmullrom')]}},
        sounds=[(0.0, S + 'draw')]))
    A.append(anim(m, 'put_away', 0.45, 'hold', {
        'root': {'rotation': [(0, (0, 0, 0)), (0.15, (4, -10, 8), 'catmullrom'), (0.45, (16, -40, 28), 'catmullrom')],
                 'position': [(0, (0, 0, 0)), (0.45, (2, -10, 3), 'catmullrom')]}}))

    # перезарядка: ячейка вниз и прочь (левая рука), новая снизу (additional_magazine), удар ладонью
    def reload_tracks(empty):
        L0 = (0, 0, 0)
        grab = (0.8, -4.6, 8.4)               # ладонь у ячейки
        off = (0, -60, 4)                     # «за кадром»
        t_in = 1.42
        tr = {
            'root': {'rotation': [(0, (0, 0, 0)), (0.3, (3, -6, 14), 'catmullrom'), (0.9, (5, -8, 18), 'catmullrom'),
                                  (1.45, (2, -6, 13), 'catmullrom')],
                     'position': [(0, (0, 0, 0)), (0.3, (0.5, 0.8, 0), 'catmullrom'), (1.45, (0.4, 0.6, 0), 'catmullrom')]},
            'magazine': {'position': [(0, L0), (0.48, L0), (0.58, (0, -0.9, 0.2)), (0.8, (0, -6.5, 1.2)),
                                      (1.05, off), (t_in - 0.01, off), (t_in, L0)]},
            'additional_magazine': {'position': [(0, off), (0.95, off), (0.96, (0, -14, 3)),
                                                 (1.3, (0, -2.4, 0.6), 'catmullrom'), (t_in, L0)]},
            'lefthand': {'scale': ARM,
                         'position': [(0, L0), (0.25, L0), (0.48, grab, 'catmullrom'), (0.58, (0.8, -5.5, 8.6)),
                                      (0.8, (0.8, -11.1, 9.6)), (0.96, (1.2, -18.6, 11.4)),
                                      (1.3, (0.8, -7.0, 9.0), 'catmullrom'), (t_in, grab),
                                      (1.52, (0.8, -3.6, 8.2), 'catmullrom')]},
            'camera': {'rotation': [(0, L0), (0.55, (0.4, 0, 0.3), 'catmullrom'), (t_in, (-0.3, 0, -0.4)),
                                    (t_in + 0.08, (0.2, 0, 0.2), 'catmullrom')]},
        }
        if not empty:
            tr['root']['rotation'] += [(1.75, (0, 0, 0), 'catmullrom')]
            tr['root']['position'] += [(1.75, (0, 0, 0), 'catmullrom')]
            tr['lefthand']['position'] += [(1.85, L0, 'catmullrom')]
            tr['camera']['rotation'] += [(2.0, L0, 'catmullrom')]
            return tr
        # с пустой ячейкой: рукоять заряжания слева — левой рукой назад и отпустить
        hg = (-1.3, 1.9, 12.4)                # ладонь у рукояти заряжания
        tr['root']['rotation'] += [(1.7, (-2, 10, 6), 'catmullrom'), (2.05, (-1, 8, 4), 'catmullrom'),
                                   (2.4, (0, 0, 0), 'catmullrom')]
        tr['root']['position'] += [(2.4, (0, 0, 0), 'catmullrom')]
        tr['lefthand']['position'] += [(1.72, hg, 'catmullrom'), (1.86, (-1.3, 1.9, 15.0)), (1.94, (-1.4, 2.1, 14.6)),
                                       (2.35, L0, 'catmullrom')]
        tr['charge_handle'] = {'position': [(0, L0), (1.72, L0), (1.86, (0, 0, 2.6)), (1.9, L0)]}
        tr['camera']['rotation'] += [(1.88, (0.5, 0, -0.4)), (1.95, (-0.2, 0, 0.2), 'catmullrom'),
                                     (2.4, L0, 'catmullrom')]
        tr['barrels'] = {'rotation': [(0, L0), (1.9, L0), (2.3, (0, 0, -360), 'catmullrom')]}
        return tr

    A.append(anim(m, 'reload_tactical', 2.0, 'once', reload_tracks(False),
                  sounds=[(0.5, S + 'cell_out'), (1.4, S + 'cell_in')]))
    A.append(anim(m, 'reload_empty', 2.5, 'once', reload_tracks(True),
                  sounds=[(0.5, S + 'cell_out'), (1.4, S + 'cell_in'), (1.74, S + 'charge')]))
    # осмотр: показать левый бок, потом правый, стволы прокрутить
    A.append(anim(m, 'inspect', 3.4, 'once', {
        'root': {'rotation': [(0, (0, 0, 0)), (0.5, (6, -32, 22), 'catmullrom'), (1.4, (8, -38, 26), 'catmullrom'),
                              (1.9, (-4, 25, -12), 'catmullrom'), (2.8, (-5, 28, -14), 'catmullrom'),
                              (3.4, (0, 0, 0), 'catmullrom')],
                 'position': [(0, (0, 0, 0)), (0.5, (-1.5, 1, -1), 'catmullrom'), (1.9, (1, 0.5, -1), 'catmullrom'),
                              (3.4, (0, 0, 0), 'catmullrom')]},
        'barrels': {'rotation': [(0, (0, 0, 0)), (1.0, (0, 0, 0)), (1.6, (0, 0, -360), 'catmullrom')]}},
        sounds=[(0.05, S + 'draw')]))
    return A


# ------------------------------------------------------------------ запись .bbmodel
def element(c, ci, pos, uvw):
    faces = {}
    for f in FACES:
        x, y, w, h = pos[(ci, f)]
        u0, v0, u1, v1 = x / TEXEL, y / TEXEL, (x + w) / TEXEL, (y + h) / TEXEL
        faces[f] = {'uv': [u0, v0, u1, v1], 'texture': 0}
    return {'name': c['name'], 'type': 'cube', 'box_uv': False, 'rescale': False, 'locked': False,
            'from': [round(v, 4) for v in c['from']], 'to': [round(v, 4) for v in c['to']], 'autouv': 0,
            'color': 0, 'origin': [round(v, 4) for v in c['origin']], 'rotation': c['rotation'], 'faces': faces,
            'uuid': uid(f'cube/{ci}')}


def write_model(m, path, name, cubes, size, pos, tex, keep, display=None, anims=None, extra=None):
    index = {id(c): i for i, c in enumerate(cubes)}
    elements = []

    def node(bn):
        b = m.bones[bn]
        ch = []
        for c in b['cubes']:
            ci = index[id(c)]
            elements.append(element(c, ci, pos, size // TEXEL))
            ch.append(elements[-1]['uuid'])
        for cb in b['children']:
            if keep(cb):
                ch.append(node(cb))
        return {'name': bn, 'origin': [round(v, 4) for v in b['origin']], 'rotation': b['rotation'],
                'uuid': uid('bone/' + bn), 'export': True, 'isOpen': False, 'visibility': True, 'children': ch}

    outliner = [node(r) for r in m.roots if keep(r)]
    uvw = size // TEXEL
    d = {'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': False}, 'name': name,
         'model_identifier': '', 'visible_box': [1, 1, 0], 'resolution': {'width': uvw, 'height': uvw},
         'elements': elements, 'outliner': outliner,
         'textures': [{'path': '', 'name': name + '.png', 'folder': '', 'namespace': '', 'id': '0',
                       'particle': False, 'render_mode': 'default', 'visible': True, 'mode': 'bitmap',
                       'saved': True, 'uuid': uid('tex/' + name), 'width': size, 'height': size,
                       'uv_width': uvw, 'uv_height': uvw, 'source': png_b64(tex)}],
         'animations': anims or []}
    if display:
        d['display'] = display
    if extra:
        d.update(extra)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(d, f, ensure_ascii=False, separators=(',', ':'))


# ------------------------------------------------------------------ поза и растеризация (иконки, превью)
def Rxyz(rot):
    x, y, z = (math.radians(v) for v in rot)
    Rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    Ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    Rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def M4(R=None, t=None):
    M = np.eye(4)
    if R is not None:
        M[:3, :3] = R
    if t is not None:
        M[:3, 3] = t
    return M


def world_faces(m, cubes, pos, size, keep):
    """Грани в позе по умолчанию: [(4×3 точки, 4×2 uv в текселях)]."""
    mats = {}

    def walk(bn, parent):
        b = m.bones[bn]
        o = np.array(b['origin'])
        M = parent @ M4(t=o) @ M4(Rxyz(b['rotation'])) @ M4(t=-o)
        mats[bn] = M
        for c in b['children']:
            walk(c, M)

    for r in m.roots:
        walk(r, np.eye(4))
    index = {id(c): i for i, c in enumerate(cubes)}
    out = []
    for bn in m.order:
        if not keep(bn):
            continue
        for c in m.bones[bn]['cubes']:
            ci = index[id(c)]
            o = np.array(c['origin'])
            CM = mats[bn] @ M4(t=o) @ M4(Rxyz(c['rotation'])) @ M4(t=-o)
            x0, y0, z0 = c['from']
            x1, y1, z1 = c['to']
            q = {'north': [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)],
                 'south': [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)],
                 'east': [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)],
                 'west': [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)],
                 'up': [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
                 'down': [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)]}
            for f in FACES:
                tx, ty, tw, th = pos[(ci, f)]
                uv = np.array([(tx, ty), (tx, ty + th), (tx + tw, ty + th), (tx + tw, ty)], float)
                P = np.array([(CM @ np.array([*p, 1.0]))[:3] for p in q[f]])
                out.append((P, uv, c['mat'] in GLOW))
    return out


def raster(faces, tex, view, W, H, margin=0.06, light=(-0.4, 0.8, 0.45), bg=(0, 0, 0, 0)):
    """Ортогональная проекция: view — 3×3 (строки: вправо, вверх, к зрителю)."""
    T = np.asarray(tex.convert('RGBA'), np.float32)
    V = np.array(view, float)
    allp = np.concatenate([f[0] for f in faces]) @ V.T
    mn, mx = allp.min(0), allp.max(0)
    sc = min(W * (1 - 2 * margin) / (mx[0] - mn[0]), H * (1 - 2 * margin) / (mx[1] - mn[1]))
    ox = (W - (mx[0] - mn[0]) * sc) / 2 - mn[0] * sc
    oy = (H - (mx[1] - mn[1]) * sc) / 2 + mx[1] * sc
    img = np.zeros((H, W, 4), np.float32)
    img[:] = bg
    zb = np.full((H, W), -1e9)
    L = np.array(light, float)
    L /= np.linalg.norm(L)
    for P, uv, glow in faces:
        Q = P @ V.T
        n = np.cross(P[1] - P[0], P[3] - P[0])
        nl = np.linalg.norm(n)
        if nl < 1e-9:
            continue
        n /= nl
        shade = 1.0 if glow else 0.45 + 0.55 * max(0.0, float(n @ L)) + 0.1 * abs(float(n[1]))
        sx = ox + Q[:, 0] * sc
        sy = oy - Q[:, 1] * sc
        for tri in ((0, 1, 2), (0, 2, 3)):
            ax, ay = sx[list(tri)], sy[list(tri)]
            x0, x1 = int(max(0, math.floor(ax.min()))), int(min(W - 1, math.ceil(ax.max())))
            y0, y1 = int(max(0, math.floor(ay.min()))), int(min(H - 1, math.ceil(ay.max())))
            if x1 < x0 or y1 < y0:
                continue
            yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
            px, py = xx + 0.5, yy + 0.5
            d = (ay[1] - ay[2]) * (ax[0] - ax[2]) + (ax[2] - ax[1]) * (ay[0] - ay[2])
            if abs(d) < 1e-12:
                continue
            w0 = ((ay[1] - ay[2]) * (px - ax[2]) + (ax[2] - ax[1]) * (py - ay[2])) / d
            w1 = ((ay[2] - ay[0]) * (px - ax[2]) + (ax[0] - ax[2]) * (py - ay[2])) / d
            w2 = 1 - w0 - w1
            ins = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not ins.any():
                continue
            z = w0 * Q[tri[0], 2] + w1 * Q[tri[1], 2] + w2 * Q[tri[2], 2]
            sub = zb[y0:y1 + 1, x0:x1 + 1]
            ok = ins & (z > sub + 1e-4)
            if not ok.any():
                continue
            u = w0 * uv[tri[0], 0] + w1 * uv[tri[1], 0] + w2 * uv[tri[2], 0]
            v = w0 * uv[tri[0], 1] + w1 * uv[tri[1], 1] + w2 * uv[tri[2], 1]
            ui = np.clip(u.astype(int), 0, T.shape[1] - 1)
            vi = np.clip(v.astype(int), 0, T.shape[0] - 1)
            col = T[vi, ui].copy()
            col[..., :3] *= shade
            sub[ok] = z[ok]
            img[y0:y1 + 1, x0:x1 + 1][ok] = col[ok]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGBA')


def view_side_left():      # вид слева: ствол влево
    return [(0, 0, 1), (0, 1, 0), (-1, 0, 0)]


def view_side_right():     # вид справа: ствол вправо
    return [(0, 0, -1), (0, 1, 0), (1, 0, 0)]


def view_34(yaw=-35, pitch=18):
    R = Rxyz((pitch, 0, 0)) @ Rxyz((0, yaw, 0))
    return [R.T @ np.array(v) for v in ((0, 0, -1), (0, 1, 0), (1, 0, 0))]


# ------------------------------------------------------------------ иконки
def hud_icon(faces, tex):
    big = raster(faces, tex, view_side_left(), 384 * 3, 128 * 3, margin=0.03)
    a = np.asarray(big, np.float32)
    lum = (a[..., 0] * 0.3 + a[..., 1] * 0.55 + a[..., 2] * 0.15)
    g = np.clip(130 + (lum - 60) * 0.9, 95, 200)
    out = np.dstack([g, g, g, a[..., 3]]).astype(np.uint8)
    return Image.fromarray(out, 'RGBA').resize((384, 128), Image.LANCZOS)


def slot_icon(faces, tex):
    big = raster(faces, tex, view_side_right(), 512, 512, margin=0.02)
    a = np.asarray(big, np.float32).copy()
    a[..., :3] = np.clip(a[..., :3] * 1.45 + 12, 0, 255)          # в инвентаре — светлее (фон серый)
    big = Image.fromarray(a.astype(np.uint8), 'RGBA')
    big = big.rotate(32, resample=Image.BICUBIC, expand=False)
    bb = big.getbbox()
    big = big.crop(bb)
    s = max(big.size)
    sq = Image.new('RGBA', (s, s))
    sq.paste(big, ((s - big.size[0]) // 2, (s - big.size[1]) // 2))
    return sq.resize((60, 60), Image.LANCZOS).crop((-2, -2, 62, 62))


def cell_icon():
    S = 8
    im = Image.new('RGBA', (32 * S, 32 * S))
    d = ImageDraw.Draw(im)
    # банка ячейки: корпус, крышки, светящиеся полосы
    d.rounded_rectangle((9 * S, 4 * S, 23 * S, 28 * S), radius=2 * S, fill=MAT['navy'] + (255,),
                        outline=MAT['navy_d'] + (255,), width=S)
    d.rectangle((8 * S, 3 * S, 24 * S, 6 * S), fill=MAT['gun'] + (255,))
    d.rectangle((8 * S, 26 * S, 24 * S, 29 * S), fill=MAT['red_d'] + (255,))
    d.rectangle((14 * S, 1 * S, 18 * S, 3 * S), fill=MAT['steel'] + (255,))
    glow = Image.new('RGBA', im.size)
    g = ImageDraw.Draw(glow)
    for y in (8, 13, 18, 23):
        g.rectangle((11 * S, y * S, 21 * S, (y + 2) * S), fill=MAT['cyan'] + (255,))
    glow2 = glow.filter(ImageFilter.GaussianBlur(S * 1.2))
    im = Image.alpha_composite(im, glow2)
    im = Image.alpha_composite(im, glow)
    d = ImageDraw.Draw(im)
    for y in (8, 13, 18, 23):
        d.rectangle((13 * S, int((y + 0.6) * S), 19 * S, int((y + 1.4) * S)), fill=MAT['white'] + (255,))
    return im.resize((32, 32), Image.LANCZOS)


def flash_png():
    N = 128
    yy, xx = np.mgrid[0:N, 0:N]
    cx = cy = (N - 1) / 2
    dx, dy = (xx - cx) / cx, (yy - cy) / cy
    r = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx)
    star = np.clip(1 - r / (0.55 + 0.4 * np.abs(np.cos(3 * ang)) ** 6), 0, 1)
    core = np.clip(1 - r / 0.35, 0, 1) ** 1.5
    halo = np.clip(1 - r, 0, 1) ** 2.2
    a = np.clip(core * 1.0 + star * 0.8 + halo * 0.5, 0, 1)
    wht = np.clip(core * 1.3, 0, 1)
    col = np.zeros((N, N, 4), np.float32)
    base = np.array([60, 170, 255], np.float32)
    col[..., :3] = base * (1 - wht[..., None]) + 255 * wht[..., None]
    col[..., 3] = a * 255
    return Image.fromarray(col.astype(np.uint8), 'RGBA')


# ------------------------------------------------------------------ main
def main():
    m = build()
    cubes = [c for bn in m.order for c in m.bones[bn]['cubes']]
    size, pos = layout(cubes)
    tex = paint(cubes, size, pos)
    anims = animations(m)

    every = lambda bn: True
    write_model(m, os.path.join(OUT, 'models', NAME + '.bbmodel'), NAME, cubes, size, pos, tex, every, anims=anims,
                extra={'hoka_view': {'model_fov': 60.0, 'zoom_model_fov': 45.0},
                       'hoka_hidden': ['ar_stock_adapter']})
    # упрощённая (предмет hoitems): без рук, «глаз» и трубки AR; display — под точку рукояти (как у АКМ)
    lod_skip = {'righthand', 'righthand_pos', 'lefthand', 'lefthand_pos', 'camera', 'views', 'ar_stock_adapter',
                'additional_magazine'}

    def lod_keep(bn):
        return bn not in lod_skip and not (m.bones[bn]['parent'] == 'views')

    disp = {
        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 0.705, -3.255], 'scale': [0.6, 0.6, 0.6]},
        'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 0.705, -3.255], 'scale': [0.6, 0.6, 0.6]},
        'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 0.59, -2.71], 'scale': [0.5, 0.5, 0.5]},
        'firstperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 0.59, -2.71], 'scale': [0.5, 0.5, 0.5]},
        'ground': {'rotation': [0, 0, 0], 'translation': [0, 2.4, -1.2], 'scale': [0.6, 0.6, 0.6]},
        'fixed': {'rotation': [0, 90, 0], 'translation': [0, 0, 0], 'scale': [0.6, 0.6, 0.6]},
        'head': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [0.5, 0.5, 0.5]},
        'gui': {'rotation': [0, 90, 0], 'translation': [0, 0, 0], 'scale': [0.35, 0.35, 0.35]},
    }
    write_model(m, os.path.join(OUT, 'models', NAME + '_lod.bbmodel'), NAME + '_lod', cubes, size, pos, tex,
                lod_keep, display=disp)

    faces = world_faces(m, cubes, pos, size, lod_keep)
    os.makedirs(os.path.join(OUT, 'textures'), exist_ok=True)
    hud_icon(faces, tex).save(os.path.join(OUT, 'textures', NAME + '_hud.png'), optimize=True)
    slot_icon(faces, tex).save(os.path.join(OUT, 'textures', NAME + '_slot.png'), optimize=True)
    cell_icon().save(os.path.join(OUT, 'textures', 'plasma_cell.png'), optimize=True)
    flash_png().save(os.path.join(OUT, 'textures', 'plasma_flash.png'), optimize=True)
    stock = world_faces(m, cubes, pos, size, lambda bn: bn == 'plasma_wire_stock')
    si = raster(stock, tex, view_side_right(), 256, 256, margin=0.08)
    a = np.asarray(si, np.float32).copy()
    a[..., :3] = np.clip(a[..., :3] * 1.6 + 20, 0, 255)
    Image.fromarray(a.astype(np.uint8), 'RGBA').resize((64, 64), Image.LANCZOS).save(
        os.path.join(OUT, 'textures', 'plasma_wire_stock.png'), optimize=True)
    print(f'{NAME}: кубов {len(cubes)}, костей {len(m.order)}, текстура {size}×{size}, анимаций {len(anims)}')

    if len(sys.argv) > 1:
        side = raster(faces, tex, view_side_left(), 1600, 560, bg=(26, 28, 34, 255))
        tq = raster(faces, tex, view_34(), 1600, 700, bg=(26, 28, 34, 255))
        top = raster(faces, tex, view_34(-150, 12), 1600, 700, bg=(26, 28, 34, 255))
        sheet = Image.new('RGBA', (1600, 560 + 700 + 700), (26, 28, 34, 255))
        sheet.paste(side, (0, 0))
        sheet.paste(tq, (0, 560))
        sheet.paste(top, (0, 1260))
        sheet.convert('RGB').save(sys.argv[1])
        print('превью:', sys.argv[1])


if __name__ == '__main__':
    main()
