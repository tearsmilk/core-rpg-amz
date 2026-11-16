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
