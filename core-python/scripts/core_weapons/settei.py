# 設定 — неизменяемый снимок config.yml (Java Settings). Без сервера и без amazake: разбор dict, который даёт
# amazake.config (YAML → JSON → dict), по тем же правилам, что Bukkit ConfigurationSection в Java-версии
# (getInt/getDouble — только числа, getBoolean — только true/false, getStringList — строки и простые значения).
# Снимок подменяется целиком при перечитывании (переменная 現在).

import re
import struct
from collections import namedtuple

import ronri


def f32(x):
    """Округлить до float Java (Java хранит громкость, высоту, размер как float; GraalPy не передаёт в параметр
    float значение double с потерей точности — поэтому всё, что идёт в float, округляем заранее)."""
    return struct.unpack("f", struct.pack("f", float(x)))[0]


# --------------------------------------------------------------------------------- чтение «как Bukkit»

def _数か(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def 区画(c, k):
    v = c.get(k) if isinstance(c, dict) else None
    return v if isinstance(v, dict) else None


def 整数(c, k, 既定):
    v = c.get(k)
    return int(v) if _数か(v) else 既定


def 実数(c, k, 既定):
    v = c.get(k)
    return float(v) if _数か(v) else float(既定)


def 真偽(c, k, 既定):
    v = c.get(k)
    return v if isinstance(v, bool) else 既定


def _java文字(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def 文字(c, k, 既定):
    v = c.get(k)
    return 既定 if v is None else _java文字(v)


def 文字列(c, k):
    """getStringList: не список → пусто; строки и простые значения → строки, остальное пропускается."""
    v = c.get(k)
    if not isinstance(v, list):
        return []
    return [_java文字(x) for x in v if isinstance(x, (str, int, float, bool))]


# ------------------------------------------------------------------------------------------ записи

class 音(namedtuple("音", "鍵 音量 高さ")):
    """Звук «ключ:громкость:высота» (ключ — как в /playsound, можно с minecraft:)."""
    __slots__ = ()

    @staticmethod
    def 解析(生):
        if 生 is None or str(生).strip() == "":
            return None
        p = ronri.java分割(str(生).strip())
        vol = 1.0
        pitch = 1.0
        n = len(p)
        if len(p) >= 3 and _数字か(p[-1]) and _数字か(p[-2]):
            vol = _java実数(p[-2])
            pitch = _java実数(p[-1])
            n -= 2
        elif len(p) >= 2 and _数字か(p[-1]):
            vol = _java実数(p[-1])
            n -= 1
        鍵 = ":".join(p[:n]).lower()
        return None if 鍵 == "" else 音(鍵, f32(vol), f32(pitch))


# Float.parseFloat: пробелы по краям, знак, NaN/Infinity, десятичная запись с экспонентой и суффиксом f/F/d/D.
_実数 = re.compile(r"[+-]?(NaN|Infinity|((\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)[fFdD]?)\Z")


def _数字か(s):
    return _実数.match(s.strip()) is not None


def _java実数(s):
    s = s.strip()
    if s[-1:] in ("f", "F", "d", "D") and not s.endswith("Infinity"):
        s = s[:-1]
    return float(s.replace("Infinity", "inf"))


試験品 = namedtuple("試験品", "素材 モデル 名前")
軌道 = namedtuple("軌道", "周期 点数 半径 度毎tick 傾き 揺れ 右 上 前 色1 色2 大きさ 炎毎 手の火")
振り = namedtuple("振り", "コマ 最小間隔 軌跡 軌跡点 軌跡半径 軌跡距離 色1 色2 音")
地震 = namedtuple("地震", "半径 長さ 待ち 粒 砂柱 揺らす 揺れ高さ 最大表示 跳ね 自分も 傷 音")


class 武器(namedtuple("武器", "id 試験品 軌道 振り 地震")):
    """Оружие: любое поведение может быть None (выключено)."""
    __slots__ = ()


class 設定(namedtuple("設定", "接頭 武器表 文言 棒")):
    __slots__ = ()

    def 武器(self, id):
        return None if id is None else self.武器表.get(id)


空 = 設定("", {}, {}, None)

#: Текущий снимок (подменяет main.読み込み).
現在 = 空


def _挟む(v, lo, hi):
    return max(lo, min(hi, v))


def _色(列, i, 既定, 警告):
    if 列 is None or len(列) <= i:
        return 既定
    s = 列[i].strip().replace("#", "")
    try:
        if not re.match(r"[+-]?[0-9a-fA-F]+\Z", s):
            raise ValueError(s)
        v = int(s, 16)
        if v < -2 ** 31 or v > 2 ** 31 - 1:
            raise ValueError(s)
        return v & 0xFFFFFF
    except ValueError:
        警告("цвет «" + 列[i] + "» не понят, взят " + format(既定, "x"))
        return 既定


def _実数列(生, 既定):
    if not isinstance(生, list) or len(生) != len(既定):
        return 既定
    out = []
    for o in 生:
        if not _数か(o):
            return 既定
        out.append(float(o))
    return out


def 解析(c, 警告=lambda m: None):
    """dict из amazake.config → 設定. 警告(текст) — замечания (в лог)."""
    c = c if isinstance(c, dict) else {}
    武器表 = {}
    ws = 区画(c, "weapons")
    if ws is not None:
        for id in ws:
            w = 区画(ws, id)
            if w is None:
                警告("weapons." + id + ": не секция")
                continue
            武器表[id] = _武器(id, w, lambda m, id=id: 警告("weapons." + id + "." + m))
    文言 = {}
    ms = 区画(c, "messages")
    if ms is not None:
        for k in ms:
            文言[k] = 文字(ms, k, "")
    棒 = None
    b = 区画(c, "stick")
    if b is not None and 真偽(b, "enabled", True):
        棒 = _棒(b, lambda m: 警告("stick." + m))
    return 設定(文字(c, "prefix", ""), 武器表, 文言, 棒)


def _武器(id, w, 警告):
    test = None
    t = 区画(w, "test-item")
    if t is not None:
        test = 試験品(文字(t, "material", "NETHERITE_SWORD").upper(), 文字(t, "item-model", ""), 文字(t, "name", id))
    orbit = None
    o = 区画(w, "orbit")
    if o is not None and 真偽(o, "enabled", True):
        中心 = _実数列(o.get("center"), [0.45, 2.15, 0.45])
        色 = 文字列(o, "colors")
        orbit = 軌道(max(1, 整数(o, "period", 2)), _挟む(整数(o, "points", 3), 1, 12),
                   実数(o, "radius", 0.75), 実数(o, "degrees-per-tick", 14), 実数(o, "tilt", 25),
                   実数(o, "bob", 0.12), 中心[0], 中心[1], 中心[2],
                   _色(色, 0, 0xFF3B2F, 警告), _色(色, 1, 0xFF9A3D, 警告),
                   f32(max(0.1, min(4.0, 実数(o, "size", 0.75)))), max(0, 整数(o, "flame-every", 2)),
                   真偽(o, "hand-ember", True))
    swing = None
    s = 区画(w, "swing")
    if s is not None and 真偽(s, "enabled", True):
        コマ = ronri.コマ.解析(文字列(s, "frames"), lambda m: 警告("swing.frames: " + m))
        色 = 文字列(s, "trail-colors")
        swing = 振り(コマ, max(1, 整数(s, "min-interval", 6)), 真偽(s, "trail", True),
                   _挟む(整数(s, "trail-points", 5), 1, 20), 実数(s, "trail-radius", 1.35),
                   実数(s, "trail-distance", 1.25), _色(色, 0, 0xFF2A1F, 警告), _色(色, 1, 0xFFB347, 警告),
                   音.解析(文字(s, "sound", "")))
    tremor = None
    r = 区画(w, "crit-tremor")
    if r is not None and 真偽(r, "enabled", True):
        音列 = []
        for raw in 文字列(r, "sounds"):
            sp = 音.解析(raw)
            if sp is not None:
                音列.append(sp)
        tremor = 地震(max(1.0, min(16.0, 実数(r, "radius", 6))),
                    _挟む(整数(r, "duration", 12), 2, 60), max(0, 整数(r, "cooldown", 30)),
                    _挟む(整数(r, "particles", 3), 0, 20), 真偽(r, "dust-pillar", True),
                    真偽(r, "shake-blocks", True), max(0.0, min(1.0, 実数(r, "shake-height", 0.22))),
                    _挟む(整数(r, "max-displays", 90), 0, 400), max(0.0, min(1.5, 実数(r, "kick", 0.12))),
                    真偽(r, "kick-attacker", False), max(0.0, 実数(r, "damage", 0)), tuple(音列))
    return 武器(id, test, orbit, swing, tremor)


# ------------------------------------------------------------------------------------------- палка (bou.py)
背刺 = namedtuple("背刺", "冷却 外れ冷却 届き 円錐 余白 傷 傷種 背角 倍率 無敵無視 大きさ 会心大きさ 始倍 回転 回転増 "
                        "前 寿命 視距離 音 会心音")
余燼 = namedtuple("余燼", "詠唱 冷却 中断冷却 射程 片長 傷 傷種 火秒 半径 貫通 無敵無視 球大きさ 球始倍 球前 球下 色周期 "
                        "幅 始幅倍 伸び 保持 消え 爆大きさ 爆時間 視距離 始音 唸り音 撃音 着弾音 中断音")
棒 = namedtuple("棒", "素材 nexo_ids 権限 禁止世界 対人 連打 背刺 余燼 拒否音")


def _音列(c, k):
    out = []
    for raw in 文字列(c, k):
        sp = 音.解析(raw)
        if sp is not None:
            out.append(sp)
    return tuple(out)


def _棒(b, 警告):
    """Секция stick: приёмы палки. Подсекции backstab / embers; выключенная (enabled: false) — None."""
    素材 = 文字(b, "material", "STICK").upper()
    ids = frozenset(s.strip() for s in 文字列(b, "nexo-ids") if s.strip())
    bs = None
    s = 区画(b, "backstab")
    if s is not None and 真偽(s, "enabled", True):
        bs = 背刺(max(0.0, 実数(s, "cooldown", 6.0)), max(0.0, 実数(s, "miss-cooldown", 1.0)),
                 max(0.5, min(8.0, 実数(s, "range", 3.5))), max(0.0, min(60.0, 実数(s, "cone", 20.0))),
                 max(0.0, min(1.0, 実数(s, "ray-margin", 0.2))), max(0.0, 実数(s, "damage", 6.0)),
                 文字(s, "damage-type", "minecraft:player_attack"), max(0.0, min(180.0, 実数(s, "back-angle", 60.0))),
                 max(0.0, 実数(s, "back-multiplier", 2.0)), 真偽(s, "ignore-invulnerability", True),
                 max(0.2, min(8.0, 実数(s, "slash-size", 2.6))), max(0.2, min(8.0, 実数(s, "crit-slash-size", 3.2))),
                 max(0.1, min(1.0, 実数(s, "slash-start-scale", 0.7))), 実数(s, "slash-roll", 28.0),
                 実数(s, "slash-roll-swing", 14.0), max(0.0, 実数(s, "slash-before-target", 0.45)),
                 _挟む(整数(s, "life-ticks", 8), 2, 8), max(0.1, 実数(s, "view-range", 1.0)),
                 _音列(s, "sounds"), _音列(s, "crit-sounds"))
    em = None
    e = 区画(b, "embers")
    if e is not None and 真偽(e, "enabled", True):
        em = 余燼(_挟む(int(round(実数(e, "cast-seconds", 1.0) * 20.0)), 1, 200), max(0.0, 実数(e, "cooldown", 10.0)),
                 max(0.0, 実数(e, "cancel-cooldown", 1.0)), max(1.0, min(48.0, 実数(e, "range", 16.0))),
                 max(1.0, min(16.0, 実数(e, "piece-length", 4.0))), max(0.0, 実数(e, "damage", 8.0)),
                 文字(e, "damage-type", "minecraft:magic"), max(0.0, 実数(e, "fire-seconds", 3.0)),
                 max(0.0, min(1.5, 実数(e, "hit-radius", 0.3))), 真偽(e, "pierce", False),
                 真偽(e, "ignore-invulnerability", True),
                 max(0.2, min(4.0, 実数(e, "orb-size", 1.15))), max(0.05, min(1.0, 実数(e, "orb-start-scale", 0.35))),
                 max(0.3, min(4.0, 実数(e, "orb-forward", 1.3))), 実数(e, "orb-down", 0.15),
                 _挟む(整数(e, "orb-update-ticks", 4), 1, 20),
                 max(0.1, min(3.0, 実数(e, "beam-width", 0.9))), max(0.0, min(1.0, 実数(e, "beam-start-width", 0.3))),
                 _挟む(整数(e, "beam-grow-ticks", 2), 1, 20), _挟む(整数(e, "beam-hold-ticks", 4), 0, 40),
                 _挟む(整数(e, "beam-fade-ticks", 4), 1, 20), max(0.2, min(6.0, 実数(e, "impact-size", 1.8))),
                 _挟む(整数(e, "impact-ticks", 4), 1, 20), max(0.1, 実数(e, "view-range", 1.5)),
                 _音列(e, "start-sounds"), _音列(e, "charge-sounds"), _音列(e, "fire-sounds"),
                 _音列(e, "impact-sounds"), _音列(e, "cancel-sounds"))
    if bs is None and em is None:
        警告("и backstab, и embers выключены")
    return 棒(素材, ids, 文字(b, "permission", ""), frozenset(文字列(b, "disabled-worlds")), 真偽(b, "pvp", True),
             max(0, 整数(b, "double-click-ms", 250)), bs, em, _音列(b, "denied-sounds"))
