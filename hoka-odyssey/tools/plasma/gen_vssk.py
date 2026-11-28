# Плазменная ВССК «Выхлоп» (vssk_vykhlop) — наша модель для модуля guns, собранная кодом (не TACZ). Общая форма —
# как у настоящей ВССК «Выхлоп» (компактный буллпап: пистолетная рукоять перед магазином, магазин за рукоятью, планка
# сверху с откидными мушкой и целиком, огромный толстый цилиндрический интегрированный глушитель длиной почти с
# остальную винтовку); числа, кубы и текстура — свои. Окраска одна — чёрный металл (воронёная сталь и графит, светлая
# кромка граней) с несколькими маленькими тусклыми голубыми индикаторами плазмы.
#
# Пишет в guns/assets-hoka/:
#   models/vssk_vykhlop.bbmodel      — модель от первого лица (кости, кубы, текстура, анимации, hoka_view, hoka_hidden);
#   models/vssk_vykhlop_lod.bbmodel  — та же без рук и «камер», с разделом display — предмет hoitems;
#   textures/vssk_vykhlop_hud.png    — силуэт для HUD (384×128);
#   textures/vssk_vykhlop_slot.png   — иконка в инвентаре (64×64);
#   textures/heavy_plasma_cell.png   — иконка «Тяжёлой плазменной ячейки» (32×32);
#   textures/vssk_buttpad.png        — иконка встроенного затыльника (окно обвеса, 64×64);
#   textures/vssk_suppressor.png     — иконка встроенного глушителя (окно обвеса, 64×64).
# И превью (сбоку и 3/4; без обвеса и с прицелом, двумя ЛЦУ и рукоятью) — путь первым аргументом (необязательно).
#
# Общее с ПВ-7 (формат .bbmodel, раскладка и рисунок текстуры, руки, анимации, растеризация) — из gen_plasma_rifle.py:
# система координат та же (ствол к −Z, ось канала y = 8,6), рукоять и руки — там же, где у ПВ-7 (руки те же).
# Запуск: python3 tools/plasma/gen_vssk.py [превью.png]
import math, os, sys, uuid as uuidlib

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_plasma_rifle as pr          # noqa: E402

OUT = pr.OUT
NAME = 'vssk_vykhlop'
BORE = pr.BORE
IRON_Y = 12.1          # линия прицеливания (целик и мушка на верхней планке)
SUP_R = 2.25           # «радиус» глушителя (апофема 12-угольника)
SUP_FRONT = -29.0      # торец глушителя
SUP_BACK = -7.5        # глушитель входит в переднюю часть корпуса


def uid(seed):
    return str(uuidlib.uuid5(uuidlib.NAMESPACE_URL, 'hoka-vssk/' + seed))


pr.uid = uid           # свои uuid (не совпадают с ПВ-7)

# ------------------------------------------------------------------ материалы: только чёрный металл
pr.GLOW.add('cyan_dim')
MAT = dict(pr.MAT_BLACK, **{
    'blued':      (36, 38, 44),      # воронёная сталь корпуса
    'blued_l':    (52, 55, 63),
    'blued_d':    (22, 23, 28),
    'graphite':   (54, 56, 63),      # труба глушителя — графит
    'graphite_d': (36, 37, 43),
    'steel':      (82, 86, 96),      # мушка, целик, кромки
    'polymer':    (30, 31, 36),
    'rubber':     (18, 18, 21),
    'black':      (9, 10, 12),
    'navy_d':     (16, 17, 21),      # (прорези «vents» в рисунке берут этот цвет)
    'cyan_dim':   (34, 128, 150),    # тусклые индикаторы плазмы
    'cyan':       (60, 175, 210),
    'cyan_w':     (120, 205, 225),
})

box, sym = pr.box, pr.sym


def tube(m, bone, r, z0, z1, mat, n=12, pattern=None, eps=0.0):
    """Цилиндр по оси канала — 12-угольник из n/2 «досок» (объединение = выпуклый многоугольник с апофемой r).
    Торцы досок сдвинуты на сотые доли, чтобы совпадающие грани не мерцали."""
    half = r * math.tan(math.pi / n)
    for k in range(n // 2):
        d = 0.004 * k + eps
        box(m, bone, -half, half, BORE - r, BORE + r, z0 - d, z1 + d, mat, pattern=pattern,
            origin=(0, BORE, (z0 + z1) / 2), rotation=(0, 0, 180.0 / (n // 2) * k))


# ------------------------------------------------------------------ геометрия
def build():
    m = pr.Model()
    m.bone('root', (0, BORE, 6.0))
    m.bone('body', (0, BORE, 0), 'root')

    # --- корпус-буллпап: задняя часть (под щекой, с магазином) и широкая передняя (обхватывает глушитель)
    m.bone('receiver', (0, BORE, 8), 'body')
    rc = 'receiver'
    box(m, rc, -1.35, 1.35, 5.8, 10.4, 2.0, 19.6, 'blued', pattern='panel')                 # задняя часть
    box(m, rc, -1.22, 1.22, 10.4, 10.85, 12.6, 19.6, 'polymer', pattern='panel')            # щека
    box(m, rc, -1.28, 1.28, 4.4, 5.8, 15.6, 19.6, 'blued', pattern='panel')                 # низ приклада
    box(m, rc, -1.26, 1.26, 4.6, 5.8, 14.6, 15.8, 'blued', origin=(0, 5.8, 15.2), rotation=(-32, 0, 0))  # скос
    sym(m, rc, 1.35, 1.42, 6.4, 6.7, 3.0, 19.0, 'steel')                                     # нижняя кромка
    sym(m, rc, 1.35, 1.41, 8.0, 9.2, 15.0, 18.8, 'black', pattern='vents_z')                # насечка под щекой
    box(m, rc, -1.45, 1.45, 5.35, 5.85, 10.3, 14.7, 'blued_d')                               # магазинная шахта
    box(m, rc, -0.5, 0.5, 4.95, 5.8, 14.2, 14.7, 'steel')                                    # защёлка магазина
    # переход к передней части и сама передняя часть
    box(m, rc, -1.9, 1.9, 5.8, 10.7, 1.2, 2.6, 'blued_l', pattern='panel')
    box(m, rc, -2.35, 2.35, 5.8, 10.9, -7.8, 1.4, 'blued', pattern='panel')
    box(m, rc, -2.42, 2.42, 6.0, 10.7, -7.95, -7.6, 'steel')                                 # передний пояс
    sym(m, rc, 2.35, 2.42, 6.6, 7.8, -2.6, 0.8, 'black', pattern='vents_z')                 # прорези сбоку
    sym(m, rc, 2.35, 2.65, 8.2, 9.0, -7.1, -3.4, 'blued_d', pattern='rail_z')               # боковые планки (ЛЦУ)
    box(m, rc, -0.7, 0.7, 5.5, 5.8, -7.2, -1.2, 'blued_d', pattern='rail_y')                # планка снизу (рукоять)
    box(m, rc, -2.2, 2.2, 10.9, 11.0, -7.4, 1.2, 'blued_d')                                  # крышка
    # спусковая скоба и крючок (как у ПВ-7 — рука та же)
    box(m, rc, -0.28, 0.28, 4.55, 4.85, 3.0, 6.7, 'blued_l')
    box(m, rc, -0.28, 0.28, 4.55, 5.8, 3.0, 3.3, 'blued_l')
    box(m, rc, -0.14, 0.14, 4.95, 5.8, 4.6, 5.0, 'steel', origin=(0, 5.8, 4.8), rotation=(-10, 0, 0))
    # паз затворной рукоятки (слева)
    box(m, rc, -1.4, -1.35, 9.35, 9.85, 3.0, 9.4, 'black')

    # индикаторы плазмы — маленькие и тусклые: два справа под щекой, один слева, полоска на переднем поясе
    m.bone('lights_illuminated', (1.4, 9.8, 16.5), 'receiver')
    li = 'lights_illuminated'
    box(m, li, 1.35, 1.45, 9.65, 9.95, 16.0, 16.35, 'cyan_dim')
    box(m, li, 1.35, 1.45, 9.65, 9.95, 16.7, 17.05, 'cyan')
    box(m, li, -1.45, -1.35, 9.65, 9.95, 16.4, 16.75, 'cyan_dim')
    sym(m, li, 2.42, 2.46, 10.0, 10.2, -7.9, -7.65, 'cyan_dim')

    # затворная рукоятка (слева) — для перезарядки с пустым магазином и выстрела
    m.bone('charge_handle', (-1.35, 9.6, 4.0), 'receiver')
    box(m, 'charge_handle', -2.0, -1.3, 9.4, 9.8, 3.6, 4.4, 'steel')
    box(m, 'charge_handle', -2.3, -2.0, 9.3, 9.9, 3.5, 4.5, 'blued_l')

    # --- верхняя планка (крепление прицелов)
    m.bone('rail', (0, 11.1, 3.0), 'body')
    box(m, 'rail', -0.7, 0.7, 10.4, 11.0, 1.2, 12.8, 'blued_d')                              # стойка над задней частью
    box(m, 'rail', -0.72, 0.72, 11.0, 11.05, -7.2, 12.8, 'blued_l')
    z = -7.0
    while z < 12.6:
        box(m, 'rail', -0.82, 0.82, 11.05, 11.3, z, z + 0.5, 'steel')
        z += 1.0

    # --- механический прицел: мушка спереди и целик сзади на планке (кость sight прячется под оптикой)
    m.bone('sight', (0, 11.3, 3.0), 'body')
    box(m, 'sight', -0.8, 0.8, 11.3, 11.55, -7.0, -6.0, 'blued')                             # мушка: основание
    sym(m, 'sight', 0.3, 0.55, 11.55, 12.55, -6.9, -6.1, 'blued_d')                           # защита мушки
    box(m, 'sight', -0.11, 0.11, 11.55, 12.0, -6.65, -6.35, 'steel')                         # мушка
    box(m, 'sight', -0.8, 0.8, 11.3, 11.6, 11.2, 12.3, 'blued')                              # целик: основание
    sym(m, 'sight', 0.22, 0.8, 11.6, 12.3, 11.35, 12.15, 'blued_d')                           # «уши» целика
    m.bone('sight_illuminated', (0, 12.05, -6.5), 'sight')
    box(m, 'sight_illuminated', -0.1, 0.1, 12.0, 12.12, -6.62, -6.38, 'cyan_dim')

    # --- интегрированный глушитель: толстая труба почти с остальную винтовку
    m.bone('suppressor', (0, BORE, -8.0), 'body')
    sp = 'suppressor'
    tube(m, sp, SUP_R, SUP_FRONT + 1.0, SUP_BACK, 'graphite')                                 # труба
    tube(m, sp, SUP_R + 0.17, -9.6, -7.95, 'blued_l', eps=0.001)                              # хомут у корпуса
    tube(m, sp, SUP_R + 0.07, -16.4, -15.9, 'graphite_d', eps=0.002)                          # кольца
    tube(m, sp, SUP_R + 0.07, -22.6, -22.1, 'graphite_d', eps=0.003)
    tube(m, sp, SUP_R + 0.1, SUP_FRONT, SUP_FRONT + 1.05, 'blued_d', eps=0.0015)              # передняя крышка
    box(m, sp, -0.6, 0.6, BORE - 0.6, BORE + 0.6, SUP_FRONT - 0.05, SUP_FRONT + 0.2, 'black')  # выходное отверстие
    box(m, sp, -0.6, 0.6, BORE - 0.6, BORE + 0.6, SUP_FRONT - 0.04, SUP_FRONT + 0.2, 'black',
        origin=(0, BORE, SUP_FRONT), rotation=(0, 0, 45))
    m.bone('suppressor_illuminated', (0, BORE, SUP_FRONT), sp)
    for k in range(4):                                                                         # 4 точки на торце
        a = math.radians(45 + 90 * k)
        cx, cy = 1.45 * math.cos(a), BORE + 1.45 * math.sin(a)
        box(m, 'suppressor_illuminated', cx - 0.14, cx + 0.14, cy - 0.14, cy + 0.14, SUP_FRONT - 0.06,
            SUP_FRONT - 0.01, 'cyan_dim')
    box(m, 'suppressor_illuminated', -0.12, 0.12, BORE + SUP_R + 0.17, BORE + SUP_R + 0.22, -9.2, -8.3, 'cyan_dim')

    # --- пистолетная рукоять (как у ПВ-7: та же рука)
    m.bone('grip', (0, 6.2, 7.3), 'body')
    gk = dict(origin=(0, 6.2, 7.3), rotation=(-15, 0, 0))
    box(m, 'grip', -0.8, 0.8, 1.6, 6.3, 6.2, 8.5, 'polymer', pattern='grip', **gk)
    box(m, 'grip', -0.55, 0.55, 1.9, 6.0, 8.5, 8.75, 'blued_d', **gk)
    box(m, 'grip', -0.85, 0.85, 1.35, 1.65, 6.1, 8.7, 'blued_l', **gk)

    # --- магазин за рукоятью: тяжёлая плазменная ячейка (6 зарядов) — кость magazine, для перезарядки
    m.bone('magazine', (0, 5.8, 12.4), 'body')
    m.bone('mag_standard', (0, 5.8, 12.4), 'magazine')
    ms = 'mag_standard'
    box(m, ms, -1.1, 1.1, 2.5, 5.9, 10.75, 14.05, 'graphite', pattern='panel')
    box(m, ms, -1.16, 1.16, 4.9, 5.3, 10.7, 14.1, 'blued_d')                                  # поясок
    box(m, ms, -1.22, 1.22, 2.15, 2.55, 10.6, 14.2, 'blued_d')                                # донце
    box(m, ms, -0.5, 0.5, 2.05, 2.15, 11.6, 13.2, 'steel')
    m.bone('cell_illuminated', (0, 3.8, 12.4), ms)
    sym(m, 'cell_illuminated', 1.1, 1.15, 3.1, 4.6, 12.15, 12.45, 'cyan_dim')                # окошко заряда
    m.bone('additional_magazine', (0, 5.8, 12.4), 'body')

    # --- затыльник-буллпап (встроенный обвес vssk_buttpad: кость = display.adapter)
    m.bone('vssk_buttpad', (0, 8.6, 19.6), 'body')
    bp = 'vssk_buttpad'
    box(m, bp, -1.3, 1.3, 4.4, 10.85, 19.6, 20.45, 'blued_d', pattern='panel')
    box(m, bp, -1.36, 1.36, 4.2, 11.05, 20.45, 21.75, 'rubber', pattern='grip')
    box(m, bp, -1.38, 1.38, 7.3, 7.7, 20.4, 21.8, 'blued_l')

    # --- трубка для прикладов AR (переходник TACZ ar_stock_adapter — видна только с таким прикладом)
    m.bone('ar_stock_adapter', (0, 8.7, 19.6), 'body')
    box(m, 'ar_stock_adapter', -0.65, 0.65, 8.05, 9.35, 19.6, 23.2, 'blued_d')
    box(m, 'ar_stock_adapter', -0.85, 0.85, 7.85, 9.55, 19.6, 20.2, 'steel')

    # --- переходники ЛЦУ (пистолетные ЛЦУ, display.adapter laser_adapter): слева и справа (_2) на боковых планках
    for nm, s in (('laser_adapter', -1), ('laser_adapter_2', 1)):
        m.bone(nm, (2.65 * s, 8.6, -5.3), 'body')
        xa, xb = sorted((2.3 * s, 2.78 * s))
        xc, xd = sorted((2.7 * s, 2.82 * s))
        box(m, nm, xa, xb, 9.0, 9.22, -6.8, -3.7, 'blued_l')
        box(m, nm, xa, xb, 7.98, 8.2, -6.8, -3.7, 'blued_l')
        box(m, nm, xc, xd, 8.05, 9.15, -6.9, -6.55, 'steel')
        box(m, nm, xc, xd, 8.05, 9.15, -3.95, -3.6, 'steel')

    # --- точки обвеса и вспышки
    m.bone('positioning2', (0, 0, 0), 'body')
    m.bone('scope_pos', (0, 11.3, 3.5), 'positioning2')
    m.bone('muzzle_pos', (0, BORE, SUP_FRONT - 0.05), 'suppressor')
    m.bone('muzzle_flash', (0, BORE, SUP_FRONT - 0.6), 'positioning2')
    m.bone('laser_pos', (-2.65, 8.6, -5.3), 'positioning2', rotation=(0, 0, 90))
    m.bone('laser2_pos', (2.65, 8.6, -5.3), 'positioning2', rotation=(0, 0, -90))
    m.bone('grip_pos', (0, 5.5, -4.3), 'positioning2')
    m.bone('stock_pos', (0, 8.7, 22.75), 'positioning2')

    # --- руки (как у ПВ-7: рукоять и передний хват там же)
    o, r_ = pr.hand_bone(pr.HAND_R)
    m.bone('righthand', o, 'root', rotation=r_)
    m.bone('righthand_pos', o, 'righthand')
    o, r_ = pr.hand_bone(pr.HAND_L)
    m.bone('lefthand', o, 'root', rotation=r_)
    m.bone('lefthand_pos', o, 'lefthand')

    # --- «глаза»
    m.bone('camera', (-2.5, 12.9, 15.5), None, rotation=(0, 0, -2.5))
    m.bone('views', (0, 12, 12))
    m.bone('idle_view', (-2.5, 12.9, 15.5), 'views', rotation=(0, 0, -2.5))
    m.bone('iron_view', (0, IRON_Y, 14.8), 'views')
    m.bone('refit_view', (-34.0, 9.5, -3.5), 'views', rotation=(0, -89.9, 0))
    m.bone('refit_scope_view', (-13.5, 13.2, 16.5), 'views', rotation=(0, -50, 0))
    m.bone('refit_stock_view', (-16.0, 9.0, 21.0), 'views', rotation=(0, -90, 0))
    m.bone('refit_laser_view', (-13.0, 12.0, -19.0), 'views', rotation=(-15, -135, 0))
    m.bone('refit_laser2_view', (13.0, 12.0, -19.0), 'views', rotation=(-15, 135, 0))
    m.bone('refit_muzzle_view', (-16.0, 10.0, -30.0), 'views', rotation=(0, -120, 0))
    m.bone('refit_grip_view', (-16.0, 4.5, -4.3), 'views', rotation=(0, -90, 0))
    return m


# ------------------------------------------------------------------ анимации
ARM = pr.ARM
anim = pr.anim


def animations(m):
    S = NAME + '_'
    A = []
    A.append(anim(m, 'static_idle', 0.0, 'loop', {'righthand': {'scale': ARM}, 'lefthand': {'scale': ARM}}))
    # выстрел: сильный толчок назад и вверх, затворная рукоятка отходит и возвращается (самозарядная)
    A.append(anim(m, 'shoot', 0.6, 'once', {
        'root': {'position': [(0, (0, 0, 0)), (0.03, (0.12, 0.3, 1.7)), (0.12, (0.05, 0.15, 0.6), 'catmullrom'),
                              (0.32, (0, 0, 0), 'catmullrom')],
                 'rotation': [(0, (0, 0, 0)), (0.03, (3.4, 0.5, -1.2)), (0.12, (1.4, -0.3, 0.8), 'catmullrom'),
                              (0.38, (0, 0, 0), 'catmullrom')]},
        'charge_handle': {'position': [(0, (0, 0, 0)), (0.03, (0, 0, 2.2)), (0.1, (0, 0, 2.2)), (0.16, (0, 0, 0))]},
        'camera': {'rotation': [(0, (0, 0, 0)), (0.02, (-0.6, 0, 0.5)), (0.08, (0.25, 0, -0.5), 'catmullrom'),
                                (0.25, (0, 0, 0), 'catmullrom')]}}))
    A.append(anim(m, 'draw', 0.6, 'once', {
        'root': {'rotation': [(0, (18, -25, 30)), (0.15, (10, -12, 14), 'catmullrom'),
                              (0.32, (-1.5, 2, -2), 'catmullrom'), (0.45, (0.5, -0.5, 0.5), 'catmullrom'),
                              (0.6, (0, 0, 0), 'catmullrom')],
                 'position': [(0, (2, -9, 3)), (0.15, (1, -4, 1.5), 'catmullrom'), (0.32, (0, 0.3, 0), 'catmullrom'),
                              (0.6, (0, 0, 0), 'catmullrom')]},
        'camera': {'rotation': [(0, (0, 0, 0)), (0.2, (0.8, -0.2, 0.5), 'catmullrom'), (0.6, (0, 0, 0), 'catmullrom')]}},
        sounds=[(0.0, S + 'draw')]))
    A.append(anim(m, 'put_away', 0.45, 'hold', {
        'root': {'rotation': [(0, (0, 0, 0)), (0.15, (4, -10, 8), 'catmullrom'), (0.45, (16, -40, 28), 'catmullrom')],
                 'position': [(0, (0, 0, 0)), (0.45, (2, -10, 3), 'catmullrom')]}}))

    # перезарядка: оружие кренится, левая рука уходит назад к магазину за рукоятью, ячейка вниз и прочь, новая снизу
    def reload_tracks(empty):
        L0 = (0, 0, 0)
        grab = (0.8, -5.0, 19.8)              # ладонь у ячейки (за рукоятью)
        off = (0, -60, 4)
        t_in = 1.42
        tr = {
            'root': {'rotation': [(0, (0, 0, 0)), (0.3, (4, -8, 16), 'catmullrom'), (0.9, (6, -10, 20), 'catmullrom'),
                                  (1.45, (3, -8, 15), 'catmullrom')],
                     'position': [(0, (0, 0, 0)), (0.3, (0.6, 1.2, -1.0), 'catmullrom'),
                                  (1.45, (0.5, 1.0, -0.8), 'catmullrom')]},
            'magazine': {'position': [(0, L0), (0.48, L0), (0.58, (0, -0.9, 0.2)), (0.8, (0, -6.5, 1.0)),
                                      (1.05, off), (t_in - 0.01, off), (t_in, L0)]},
            'additional_magazine': {'position': [(0, off), (0.95, off), (0.96, (0, -14, 2)),
                                                 (1.3, (0, -2.4, 0.4), 'catmullrom'), (t_in, L0)]},
            'lefthand': {'scale': ARM,
                         'position': [(0, L0), (0.22, L0), (0.48, grab, 'catmullrom'), (0.58, (0.8, -5.9, 20.0)),
                                      (0.8, (0.8, -11.5, 20.8)), (0.96, (1.2, -18.6, 22.0)),
                                      (1.3, (0.8, -7.4, 20.4), 'catmullrom'), (t_in, grab),
                                      (1.52, (0.8, -4.0, 19.6), 'catmullrom')]},
            'camera': {'rotation': [(0, L0), (0.55, (0.5, 0, 0.4), 'catmullrom'), (t_in, (-0.4, 0, -0.5)),
                                    (t_in + 0.08, (0.25, 0, 0.25), 'catmullrom')]},
        }
        if not empty:
            tr['root']['rotation'] += [(1.75, (0, 0, 0), 'catmullrom')]
            tr['root']['position'] += [(1.75, (0, 0, 0), 'catmullrom')]
            tr['lefthand']['position'] += [(1.85, L0, 'catmullrom')]
            tr['camera']['rotation'] += [(2.0, L0, 'catmullrom')]
            return tr
        # с пустой ячейкой: затворная рукоятка слева — назад и отпустить (взвод плазменного разрядника)
        hg = (-1.3, 1.9, 13.9)
        tr['root']['rotation'] += [(1.7, (-2, 11, 7), 'catmullrom'), (2.05, (-1, 9, 5), 'catmullrom'),
                                   (2.4, (0, 0, 0), 'catmullrom')]
        tr['root']['position'] += [(2.4, (0, 0, 0), 'catmullrom')]
        tr['lefthand']['position'] += [(1.72, hg, 'catmullrom'), (1.86, (-1.3, 1.9, 16.6)), (1.94, (-1.4, 2.1, 16.2)),
                                       (2.35, L0, 'catmullrom')]
        tr['charge_handle'] = {'position': [(0, L0), (1.72, L0), (1.86, (0, 0, 2.6)), (1.9, L0)]}
        tr['camera']['rotation'] += [(1.88, (0.6, 0, -0.5)), (1.95, (-0.25, 0, 0.25), 'catmullrom'),
                                     (2.4, L0, 'catmullrom')]
        return tr

    A.append(anim(m, 'reload_tactical', 2.0, 'once', reload_tracks(False),
                  sounds=[(0.5, S + 'cell_out'), (1.4, S + 'cell_in')]))
    A.append(anim(m, 'reload_empty', 2.5, 'once', reload_tracks(True),
                  sounds=[(0.5, S + 'cell_out'), (1.4, S + 'cell_in'), (1.74, S + 'charge')]))
    # осмотр: левый бок с глушителем, потом правый
    A.append(anim(m, 'inspect', 3.4, 'once', {
        'root': {'rotation': [(0, (0, 0, 0)), (0.5, (6, -34, 22), 'catmullrom'), (1.4, (8, -40, 26), 'catmullrom'),
                              (1.9, (-4, 26, -12), 'catmullrom'), (2.8, (-5, 30, -14), 'catmullrom'),
                              (3.4, (0, 0, 0), 'catmullrom')],
                 'position': [(0, (0, 0, 0)), (0.5, (-1.5, 1, -1), 'catmullrom'), (1.9, (1, 0.5, -1), 'catmullrom'),
                              (3.4, (0, 0, 0), 'catmullrom')]},
        'charge_handle': {'position': [(0, (0, 0, 0)), (2.9, (0, 0, 0)), (3.0, (0, 0, 1.2)), (3.08, (0, 0, 0))]}},
        sounds=[(0.05, S + 'draw')]))
    return A


# ------------------------------------------------------------------ иконки
def cell_icon():
    S = 8
    im = Image.new('RGBA', (32 * S, 32 * S))
    d = ImageDraw.Draw(im)
    # тяжёлая ячейка: широкий графитовый корпус, тёмные крышки, два тусклых окошка заряда
    d.rounded_rectangle((7 * S, 5 * S, 25 * S, 28 * S), radius=2 * S, fill=MAT['graphite'] + (255,),
                        outline=MAT['blued_d'] + (255,), width=S)
    d.rectangle((6 * S, 4 * S, 26 * S, 8 * S), fill=MAT['blued_l'] + (255,))
    d.rectangle((6 * S, 25 * S, 26 * S, 29 * S), fill=MAT['blued_d'] + (255,))
    d.rectangle((13 * S, 2 * S, 19 * S, 4 * S), fill=MAT['steel'] + (255,))
    d.rectangle((7 * S, 15 * S, 25 * S, 16 * S), fill=MAT['blued_d'] + (255,))
    glow = Image.new('RGBA', im.size)
    g = ImageDraw.Draw(glow)
    for x0 in (10, 18):
        g.rectangle((x0 * S, 10 * S, (x0 + 4) * S, 22 * S), fill=(70, 200, 235, 255))
    im = Image.alpha_composite(im, glow.filter(ImageFilter.GaussianBlur(S * 1.2)))
    im = Image.alpha_composite(im, glow)
    d = ImageDraw.Draw(im)
    for x0 in (10, 18):
        for y in (11, 14, 17, 20):
            d.rectangle((int((x0 + 0.8) * S), y * S, int((x0 + 3.2) * S), int((y + 1) * S)), fill=(200, 245, 255, 255))
    return im.resize((32, 32), Image.LANCZOS)


def part_icon(m, cubes, pos, size, tex, bones, view, out, gain=1.6):
    faces = pr.world_faces(m, cubes, pos, size, lambda bn: bn in bones)
    im = pr.raster(faces, tex, view, 256, 256, margin=0.08)
    a = np.asarray(im, np.float32).copy()
    a[..., :3] = np.clip(a[..., :3] * gain + 24, 0, 255)
    Image.fromarray(a.astype(np.uint8), 'RGBA').resize((64, 64), Image.LANCZOS).save(out, optimize=True)


# ------------------------------------------------------------------ main
def main():
    m = build()
    cubes = [c for bn in m.order for c in m.bones[bn]['cubes']]
    size, pos = pr.layout(cubes)
    tex = pr.paint(cubes, size, pos, seed=11, mat=MAT)
    anims = animations(m)

    every = lambda bn: True
    pr.write_model(m, os.path.join(OUT, 'models', NAME + '.bbmodel'), NAME, cubes, size, pos, tex, every, anims=anims,
                   extra={'hoka_view': {'model_fov': 60.0, 'zoom_model_fov': 45.0},
                          'hoka_hidden': ['ar_stock_adapter', 'laser_adapter', 'laser_adapter_2']})
    lod_skip = {'righthand', 'righthand_pos', 'lefthand', 'lefthand_pos', 'camera', 'views', 'ar_stock_adapter',
                'additional_magazine', 'laser_adapter', 'laser_adapter_2'}

    def lod_keep(bn):
        return bn not in lod_skip and not (m.bones[bn]['parent'] == 'views')

    disp = {
        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 0.705, -3.255], 'scale': [0.6, 0.6, 0.6]},
        'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 0.705, -3.255], 'scale': [0.6, 0.6, 0.6]},
        'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 0.59, -2.71], 'scale': [0.5, 0.5, 0.5]},
        'firstperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 0.59, -2.71], 'scale': [0.5, 0.5, 0.5]},
        'ground': {'rotation': [0, 0, 0], 'translation': [0, 2.4, -1.2], 'scale': [0.55, 0.55, 0.55]},
        'fixed': {'rotation': [0, 90, 0], 'translation': [0, 0, 0], 'scale': [0.5, 0.5, 0.5]},
        'head': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [0.5, 0.5, 0.5]},
        'gui': {'rotation': [0, 90, 0], 'translation': [0, 0, 0], 'scale': [0.3, 0.3, 0.3]},
    }
    pr.write_model(m, os.path.join(OUT, 'models', NAME + '_lod.bbmodel'), NAME + '_lod', cubes, size, pos, tex,
                   lod_keep, display=disp)

    faces = pr.world_faces(m, cubes, pos, size, lod_keep)
    T = os.path.join(OUT, 'textures')
    os.makedirs(T, exist_ok=True)
    pr.hud_icon(faces, tex).save(os.path.join(T, NAME + '_hud.png'), optimize=True)
    pr.slot_icon(faces, tex).save(os.path.join(T, NAME + '_slot.png'), optimize=True)
    cell_icon().save(os.path.join(T, 'heavy_plasma_cell.png'), optimize=True)
    part_icon(m, cubes, pos, size, tex, {'vssk_buttpad'}, pr.view_34(-60, 15), os.path.join(T, 'vssk_buttpad.png'),
              gain=2.2)
    part_icon(m, cubes, pos, size, tex, {'suppressor', 'suppressor_illuminated'}, pr.view_34(-35, 15),
              os.path.join(T, 'vssk_suppressor.png'), gain=1.9)
    print(f'{NAME}: кубов {len(cubes)}, костей {len(m.order)}, текстура {size}×{size}, анимаций {len(anims)}')

    if len(sys.argv) > 1:
        # превью: без обвеса (сбоку, 3/4) и с прицелом Elcan 4x, двумя ЛЦУ laser_compact (с переходниками) и
        # вертикальной рукоятью (модели TACZ — только для картинки)
        att = os.path.join(OUT, '..', 'assets-tacz', 'models', 'att')
        bg = (26, 28, 34, 255)
        plain = pr.world_faces(m, cubes, pos, size, lod_keep)
        skip2 = lod_skip - {'laser_adapter', 'laser_adapter_2'}
        full = pr.world_faces(m, cubes, pos, size,
                              lambda bn: bn not in skip2 and bn != 'sight' and bn != 'sight_illuminated'
                              and m.bones[bn]['parent'] != 'views')
        allp = np.concatenate([f[0] for f in plain])
        mid, half = (allp.min(0) + allp.max(0)) / 2, (allp.max(0) - allp.min(0)) / 2 + 6
        for am, bone in (('scope_elcan_4x', 'scope_pos'), ('laser_compact', 'laser_pos'),
                         ('laser_compact', 'laser2_pos'), ('grip_vertical_military', 'grip_pos')):
            path = os.path.join(att, am + '.bbmodel')
            if os.path.isfile(path):
                # без служебных плоскостей моделей обвеса (стекло/сетка прицела во весь кадр и т. п.)
                full += [f for f in pr.att_faces(path, pr.bone_world(m, bone))
                         if np.ptp(f[0], axis=0).max() < 12 and np.all(np.abs(f[0].mean(0) - mid) < half)]
        W = 1600
        tiles = [pr.raster(plain, tex, pr.view_side_left(), W, 520, bg=bg),
                 pr.raster(plain, tex, pr.view_34(), W, 640, bg=bg),
                 pr.raster(full, tex, pr.view_side_left(), W, 560, bg=bg),
                 pr.raster(full, tex, pr.view_34(-145, 14), W, 680, bg=bg)]
        sheet = Image.new('RGBA', (W, sum(t.size[1] for t in tiles)), bg)
        y = 0
        for t in tiles:
            sheet.paste(t, (0, y))
            y += t.size[1]
        sheet.convert('RGB').save(sys.argv[1])
        print('превью:', sys.argv[1])


if __name__ == '__main__':
    main()
