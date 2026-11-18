# 論理 — чистая логика core_weapons (без сервера и без amazake): кадры замаха, строки custom_model_data, геометрия.
# Перенос Java-классов logic/Frames, logic/CmdStrings, logic/Geometry. Проверяется tests_core_weapons.py (python3).
#
# Мир: x — восток, y — вверх, z — юг; yaw Minecraft: 0 — на юг (+z), 90 — на запад.

import math
import re

# Метки кадров всех систем начинаются так — по ней снимаются хвосты (выход, сбой, выключение).
接頭辞 = "cw_swing_"

_整数 = re.compile(r"[+-]?[0-9]+\Z")


def java整数(文字):
    """Integer.parseInt: строгое целое в диапазоне int, иначе ValueError (как NumberFormatException)."""
    if not _整数.match(文字):
        raise ValueError(文字)
    値 = int(文字)
    if 値 < -2 ** 31 or 値 > 2 ** 31 - 1:
        raise ValueError(文字)
    return 値


def java分割(文字, 区切り=":"):
    """String.split(":") Java: хвостовые пустые части отбрасываются."""
    部分 = 文字.split(区切り)
    while len(部分) > 1 and 部分[-1] == "":
        部分.pop()
    if len(部分) == 1 and 部分[0] == "" and 文字 != "":
        return []
    return 部分


# ---------------------------------------------------------------------------------------------- кадры

class コマ:
    """Кадры замаха: строка custom_model_data и сколько тиков её держать (Java Frames)."""

    __slots__ = ("名前", "時間", "_合計")

    def __init__(self, 名前, 時間):
        self.名前 = tuple(名前)
        self.時間 = tuple(時間)
        self._合計 = sum(self.時間)

    @staticmethod
    def 解析(生, 警告=None):
        """«имя:тиков» (тиков по умолчанию 2). Имена без префикса cw_swing_ не принимаются."""
        名前 = []
        時間 = []
        for r in 生 or []:
            r = str(r)
            p = java分割(r.strip())
            name = p[0].strip() if p else ""
            if not name.startswith(接頭辞):
                if 警告:
                    警告("«" + r + "»: имя кадра должно начинаться с " + 接頭辞)
                continue
            t = 2
            if len(p) > 1:
                try:
                    t = java整数(p[1].strip())
                except ValueError:
                    if 警告:
                        警告("«" + r + "»: тики не число, взято 2")
            名前.append(name)
            時間.append(max(1, min(20, t)))
        return コマ(名前, 時間)

    def 合計(self):
        return self._合計

    def 時点(self, 経過):
        """Кадр на тике 経過 от начала замаха; None — замах кончился (вернуть основную модель)."""
        if 経過 < 0:
            return None
        acc = 0
        for i, t in enumerate(self.時間):
            acc += t
            if 経過 < acc:
                return self.名前[i]
        return None

    def 進み(self, 経過):
        """Доля пройденного замаха 0..1."""
        t = self._合計
        return 1.0 if t == 0 else max(0.0, min(1.0, 経過 / t))

    def __eq__(self, other):
        return isinstance(other, コマ) and self.名前 == other.名前 and self.時間 == other.時間

    def __repr__(self):
        return "コマ(%r, %r)" % (list(self.名前), list(self.時間))


# ------------------------------------------------------------------------------- строки custom_model_data
# Своя метка кадра — первой (её читает select index 0), чужие строки не трогаются.

def 枠を外す(文字列):
    """Список без меток кадров."""
    return [s for s in (文字列 or []) if s is not None and not s.startswith(接頭辞)]


def 枠を付ける(文字列, 枠):
    """Список с кадром 枠 первым; прежние метки кадров убраны."""
    return [枠] + 枠を外す(文字列)


def 枠あり(文字列):
    if 文字列 is None:
        return False
    for s in 文字列:
        if s is not None and s.startswith(接頭辞):
            return True
    return False


# ---------------------------------------------------------------------------------------------- геометрия

def 前(yaw):
    """Вектор вперёд (горизонтальный) для yaw в градусах."""
    r = math.radians(yaw)
    return (-math.sin(r), 0.0, math.cos(r))


def 右(yaw):
    """Вектор вправо (горизонтальный): вперёд × вверх."""
    f = 前(yaw)
    return (-f[2], 0.0, f[0])


def 局所(yaw, 右へ, 上へ, 前へ):
    """Смещение от ног: right/up/forward → мировые dx, dy, dz."""
    f = 前(yaw)
    r = 右(yaw)
    return (r[0] * 右へ + f[0] * 前へ, 上へ, r[2] * 右へ + f[2] * 前へ)


def 輪(tick, 点数, 半径, 度毎tick, 傾き, 揺れ, yaw):
    """Точки кольца вокруг лезвия: 点数 частиц через равные углы, кольцо наклонено на 傾き градусов к игроку и
    качается на 揺れ. Смещения от центра (dx, dy, dz)."""
    f = 前(yaw)
    r = 右(yaw)
    t = math.radians(傾き)
    st = math.sin(t)
    ct = math.cos(t)
    out = []
    for i in range(点数):
        a = math.radians(tick * 度毎tick + 360.0 * i / 点数)
        ca = math.cos(a) * 半径
        sa = math.sin(a) * 半径
        # кольцо в горизонтальной плоскости (right, forward), наклонённое вокруг оси right
        up = sa * st + 揺れ * math.sin(tick * 0.25 + i * 2.1)
        fw = sa * ct
        out.append((r[0] * ca + f[0] * fw, up, r[2] * ca + f[2] * fw))
    return out


def 弧(s, 半径, 距離, look, yaw):
    """Точка дуги следа: удар сверху справа через верх влево вниз, в плоскости перед глазами. s 0..1 по дуге."""
    r = 右(yaw)
    # вверх относительно взгляда: right × look
    up = (r[1] * look[2] - r[2] * look[1], r[2] * look[0] - r[0] * look[2], r[0] * look[1] - r[1] * look[0])
    phi = math.radians(60 + 180 * s)
    c = math.cos(phi) * 半径
    sn = math.sin(phi) * 半径
    d = 距離 + 0.25 * math.sin(math.pi * s)   # середина дуги чуть дальше
    return (look[0] * d + r[0] * c + up[0] * sn,
            look[1] * d + r[1] * c + up[1] * sn,
            look[2] * d + r[2] * c + up[2] * sn)


def 帯(から, まで):
    """Столбцы (dx, dz) от центра, у которых расстояние до центра в [から, まで)."""
    out = []
    m = math.ceil(まで)
    for dx in range(-m, m + 1):
        for dz in range(-m, m + 1):
            d = math.sqrt(dx * dx + dz * dz)
            if から <= d < まで:
                out.append((dx, dz))
    return out


def 波半径(段, 長さ, 半径):
    """Радиус волны на шаге 段 (0..長さ−1): растёт от 0 до 半径, к концу замедляется."""
    x = max(0.0, min(1.0, (段 + 1.0) / 長さ))
    return 半径 * (1 - (1 - x) * (1 - x))


def 揺れ高さ(年齢, 高さ):
    """Высота подскока копии блока на тике её жизни: вверх за 2 тика, вниз за 3, потом 0."""
    if 年齢 <= 0 or 年齢 >= 5:
        return 0.0
    return 高さ * 年齢 / 2.0 if 年齢 <= 2 else 高さ * (5 - 年齢) / 3.0


_波の表 = {}


def 波の表(長さ, 半径):
    """Заранее посчитанная волна: для каждого шага (から, まで, столбцы). Кэш по (長さ, 半径) — в интерпретаторе
    GraalPy двойной цикл по квадрату на каждом шаге каждой дрожи слишком дорог, а результат всегда один и тот же."""
    k = (長さ, 半径)
    表 = _波の表.get(k)
    if 表 is None:
        表 = []
        prev = 0.0
        for 段 in range(長さ):
            r = 波半径(段, 長さ, 半径)
            から = 0.0 if 段 == 0 else prev
            表.append((から, r, tuple(帯(から, r))))
            prev = r
        _波の表[k] = 表
    return 表


# ------------------------------------------------------------------------------------------- палка: приёмы
# «Удар в спину» и «Угли» (bou.py). Геометрия луча и владения регионом — копия core_magic/keisan.py (системы живут
# в разных контекстах, импорт между ними невозможен).

#: Метка шейдера moonstaff (ключ A): цвет custom_model_data = (R, 印G, 印B), R ≤ R上限 — параметр.
印G = 233
印B = 253
R上限 = 250
#: «Удар в спину»: R = g + 斬撃C基·C — g = игровой тик появления mod 斬撃C基 (24000 делится на 125 — шов дня не
#: виден), C — крит (удар со спины). Шейдер считает возраст серпа в тиках сам.
斬撃C基 = 125


def 挟む(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


def 視線(yaw, pitch):
    """Единичный вектор взгляда (как Location.getDirection): yaw 0 → +Z, 90 → −X; pitch > 0 — вниз."""
    ry = math.radians(yaw)
    rp = math.radians(pitch)
    xz = math.cos(rp)
    return (-xz * math.sin(ry), -math.sin(rp), xz * math.cos(ry))


def 角度(a, b):
    """Угол между векторами a и b (градусы); нулевой вектор → 180 (не «сзади», не «впереди»)."""
    la = math.sqrt(sum(x * x for x in a))
    lb = math.sqrt(sum(x * x for x in b))
    if la < 1e-9 or lb < 1e-9:
        return 180.0
    c = sum(x * y for x, y in zip(a, b)) / (la * lb)
    return math.degrees(math.acos(挟む(c, -1.0, 1.0)))


def 背後か(目標yaw, 攻撃者, 目標, 限度):
    """Атакующий за спиной цели: угол между взглядом цели (по горизонтали, её yaw) и направлением атакующий → цель
    (по горизонтали) меньше 限度 градусов. 攻撃者 / 目標 — (x, y, z) их положений."""
    f = 視線(目標yaw, 0.0)
    d = (目標[0] - 攻撃者[0], 0.0, 目標[2] - 攻撃者[2])
    return 角度((f[0], 0.0, f[2]), d) < 限度


def 点(始, 向き, t):
    return (始[0] + 向き[0] * t, 始[1] + 向き[1] * t, 始[2] + 向き[2] * t)


def 線分と箱(始, 向き, 長さ, 箱):
    """Метод плит: расстояние t ∈ [0, 長さ], на котором луч входит в AABB (minx, miny, minz, maxx, maxy, maxz), или
    None. Начало внутри — 0."""
    t0 = 0.0
    t1 = float(長さ)
    for i in range(3):
        o = 始[i]
        d = 向き[i]
        lo = 箱[i]
        hi = 箱[i + 3]
        if abs(d) < 1e-12:
            if o < lo or o > hi:
                return None
            continue
        inv = 1.0 / d
        ta = (lo - o) * inv
        tb = (hi - o) * inv
        if ta > tb:
            ta, tb = tb, ta
        if ta > t0:
            t0 = ta
        if tb < t1:
            t1 = tb
        if t0 > t1:
            return None
    return t0


def 広げる(箱, r):
    return (箱[0] - r, 箱[1] - r, 箱[2] - r, 箱[3] + r, 箱[4] + r, 箱[5] + r)


def 箱の中心(箱):
    return ((箱[0] + 箱[3]) / 2.0, (箱[1] + 箱[4]) / 2.0, (箱[2] + 箱[5]) / 2.0)


def 近接の的(目, 向き, 候補, 届き, 円錐, 余白):
    """«Удар в спину»: первая цель впереди. 候補 — [(id, AABB)]. Цель годится, если луч взгляда (бокс расширен на
    余白) входит в неё не дальше 届き, ИЛИ центр её бокса в конусе 円錐 градусов вокруг взгляда и ближе 届き + половина
    ширины бокса. Возвращает (t, id) ближайшей (t — расстояние по лучу или до центра) или None."""
    best = None
    for id, 箱 in 候補:
        t = 線分と箱(目, 向き, 届き, 広げる(箱, 余白))
        if t is None:
            c = 箱の中心(箱)
            v = (c[0] - 目[0], c[1] - 目[1], c[2] - 目[2])
            dist = math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if dist <= 届き + (箱[3] - 箱[0]) / 2.0 and 角度(向き, v) <= 円錐:
                t = dist
        if t is not None and (best is None or t < best[0]):
            best = (t, id)
    return best


def 区切る(長さ, 片長):
    """[0, 長さ] → куски [(a, b)] длиной 片長 (последний короче). 長さ ≤ 0 → []."""
    長さ = float(長さ)
    片長 = max(0.25, float(片長))
    out = []
    a = 0.0
    while a < 長さ - 1e-9:
        b = min(長さ, a + 片長)
        out.append((a, b))
        a = b
    return out


def 区間の箱(始, 向き, a, b, 余白):
    p = 点(始, 向き, a)
    q = 点(始, 向き, b)
    return (min(p[0], q[0]) - 余白, min(p[1], q[1]) - 余白, min(p[2], q[2]) - 余白,
            max(p[0], q[0]) + 余白, max(p[1], q[1]) + 余白, max(p[2], q[2]) + 余白)


def 箱の列(箱):
    """Столбцы чанков (cx, cz), которых касается AABB."""
    x0 = int(math.floor(箱[0])) >> 4
    x1 = int(math.floor(箱[3])) >> 4
    z0 = int(math.floor(箱[2])) >> 4
    z1 = int(math.floor(箱[5])) >> 4
    return {(cx, cz) for cx in range(x0, x1 + 1) for cz in range(z0, z1 + 1)}


def 使える長さ(始, 向き, 長さ, 片長, 余白, 持つか):
    """Длина луча, весь путь которой в нашем регионе (как core_magic): все столбцы чанков AABB куска должны пройти
    持つか(cx, cz); первый непрошедший кусок обрывает луч, как стена."""
    済 = {}
    使える = 0.0
    for a, b in 区切る(長さ, 片長):
        for col in 箱の列(区間の箱(始, 向き, a, b, 余白)):
            ok = 済.get(col)
            if ok is None:
                ok = bool(持つか(col[0], col[1]))
                済[col] = ok
            if not ok:
                return 使える
        使える = b
    return 使える


def 道の点(始, 向き, から, まで, 毎):
    """Точки вдоль луча от から до まで через каждые 毎 блоков (частицы)."""
    if 毎 <= 0 or まで <= から:
        return []
    out = []
    t = float(から)
    while t <= まで + 1e-9:
        out.append(点(始, 向き, t))
        t += 毎
    return out


def 斬撃R(game_tick, 会心):
    """R цвета серпа: (игровой тик mod 125) + 125, если крит. 0..249."""
    return int(game_tick) % 斬撃C基 + (斬撃C基 if 会心 else 0)


def 斬撃R解読(R):
    """Как шейдер: R → (g, крит)."""
    code = int(math.floor(R / 250.0 * 250.0 + 0.5))
    c = code // 斬撃C基
    return code - 斬撃C基 * c, c == 1


def 充填R(charge):
    """Заряд 0..1 → R = round(charge·250) (сфера «Углей», логика круга посоха)."""
    return int(math.floor(挟む(float(charge), 0.0, 1.0) * R上限 + 0.5))


def 光線R(長さ):
    """Видимая длина луча (блоки) → R = clamp(round(L·8), 1, 250) (шейдер: L = R/8)."""
    return int(挟む(int(math.floor(float(長さ) * 8.0 + 0.5)), 1, R上限))


def 秒の文字(秒):
    """Секунды для сообщения: «2.4», без хвоста «.0»; вверх до десятых."""
    s = "%.1f" % (math.ceil(float(秒) * 10.0 - 1e-9) / 10.0)
    return s[:-2] if s.endswith(".0") else s
