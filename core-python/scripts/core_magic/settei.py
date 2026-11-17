# 設定 — неизменяемый снимок config.yml. Без сервера и без amazake: разбор dict, который даёт amazake.config
# (YAML → JSON → dict). Числа вне допустимых пределов зажимаются, непонятные значения заменяются значениями по
# умолчанию; о каждой замене — замечание (警告 → лог). Снимок подменяется целиком при /cmagic reload (現在).
#
# Метка шейдера (G=233, B=253) и номера спрайтов — константы кода (keisan.py), не настройки: их знает шейдер.

import re
import struct
from collections import namedtuple


def f32(x):
    """Округлить до float Java (GraalPy не передаёт в параметр float значение double с потерей точности)."""
    return struct.unpack("f", struct.pack("f", float(x)))[0]


# --------------------------------------------------------------------------------------------- значения по умолчанию

既定文言 = {
    "cooldown": "<#7fd8ff>Посох восстанавливается: <white><s> с",
    "charge": "<#7fd8ff>Заряд <bar>",
    "ready": "<#7fd8ff>✦ Круг готов — отпустите ПКМ!",
    "too-early": "<gray>Заклинание рассеялось — держите ПКМ <white><need> с</white>.",
    "too-long": "<gray>Круг не удержан и погас.",
    "given": "<green>Выдан <white>Посох Лунного Странника</white> ×<amount> игроку <player>.",
    "no-player": "<red>Игрок <player> не в сети.",
    "reloaded": "<green>core_magic: конфиг перечитан.",
    "info": "<#7fd8ff>core_magic</#7fd8ff> <gray>— заклинаний сейчас: <white><casts></white>, эффектов в мире: "
            "<white><fx></white>, барьеров: <white><barriers></white>; ваша перезарядка: <white><cd></white> с.<newline><gray>Заряд <white><charge> с</white>, "
            "луч до <white><range></white> бл., урон <white><damage></white>, перезарядка <white><cooldown> с</white>.",
    "usage": "<gray>/cmagic give [ник] [кол-во] | reload | info",
    "console-needs-player": "<red>Из консоли укажите игрока: /cmagic give <ник> [кол-во]",
    "bad-amount": "<red>Количество — число от 1 до 64.",
    "mode-beam": "<#7fd8ff>Заклинание: <white>Луч</white> <dark_gray>(Shift+ЛКМ — сменить)",
    "mode-barrier": "<#7fd8ff>Заклинание: <white>Барьер</white> <dark_gray>(Shift+ЛКМ — сменить)",
    "barrier-charge": "<#7fd8ff>Барьер <bar>",
    "barrier-ready": "<#7fd8ff>✦ Барьер поднят на <white><s> с</white> — можно отпустить ПКМ",
    "barrier-cooldown": "<#7fd8ff>Барьер восстанавливается: <white><s> с",
    "barrier-too-early": "<gray>Барьер рассыпался — держите ПКМ <white><need> с</white>.",
    "barrier-no-room": "<gray>Здесь барьер не встанет.",
}

既定音 = {
    "start": [{"sound": "block.beacon.activate", "volume": 0.7, "pitch": 1.6}],
    "hum": [{"sound": "block.beacon.ambient", "volume": 0.8, "pitch": 1.2}],
    "ready": [{"sound": "block.amethyst_block.resonate", "volume": 1.0, "pitch": 1.6},
              {"sound": "block.note_block.chime", "volume": 0.6, "pitch": 1.8}],
    "fire": [{"sound": "entity.warden.sonic_boom", "volume": 0.55, "pitch": 1.7},
             {"sound": "block.beacon.deactivate", "volume": 0.8, "pitch": 2.0},
             {"sound": "entity.firework_rocket.blast", "volume": 0.6, "pitch": 1.4}],
    "fizzle": [{"sound": "block.beacon.deactivate", "volume": 0.6, "pitch": 1.3}],
    "denied": [{"sound": "block.note_block.bass", "volume": 0.5, "pitch": 0.6}],
    "barrier-start": [{"sound": "block.amethyst_block.chime", "volume": 1.0, "pitch": 0.8},
                      {"sound": "block.beacon.activate", "volume": 0.5, "pitch": 1.9}],
    "barrier-cell": [{"sound": "block.amethyst_cluster.place", "volume": 0.6, "pitch": 1.0}],
    "barrier-formed": [{"sound": "block.beacon.power_select", "volume": 0.8, "pitch": 1.6},
                       {"sound": "block.amethyst_block.resonate", "volume": 1.0, "pitch": 1.2}],
    "barrier-hit": [{"sound": "block.amethyst_block.hit", "volume": 1.0, "pitch": 0.7},
                    {"sound": "block.amethyst_cluster.break", "volume": 0.8, "pitch": 1.4},
                    {"sound": "block.beacon.deactivate", "volume": 0.4, "pitch": 2.0}],
    "barrier-end": [{"sound": "block.amethyst_cluster.break", "volume": 0.7, "pitch": 1.0},
                    {"sound": "block.glass.break", "volume": 0.35, "pitch": 1.6}],
    "barrier-fizzle": [{"sound": "block.amethyst_block.break", "volume": 0.6, "pitch": 1.3}],
    "mode-beam": [{"sound": "block.amethyst_block.chime", "volume": 0.8, "pitch": 1.6}],
    "mode-barrier": [{"sound": "block.amethyst_cluster.place", "volume": 0.8, "pitch": 1.2},
                     {"sound": "block.note_block.chime", "volume": 0.5, "pitch": 1.0}],
}
音の名前 = tuple(既定音.keys())

# --------------------------------------------------------------------------------------------- записи

杖 = namedtuple("杖", "nexo_id 修理 秒 権限")
詠唱 = namedtuple("詠唱", "充填 最長 見張り 棒 棒長")
冷却 = namedtuple("冷却", "秒 失敗秒")
円 = namedtuple("円", "モデル 直径 始倍 前 下 更新 追従距離 追従角 移動時間 消散 閃光 閃光倍 消える 視距離")
光 = namedtuple("光", "モデル 射程 幅 始幅倍 伸び 保持 消え 視距離 傷 傷種 半径 対人 無敵無視 片長")
音 = namedtuple("音", "鍵 音量 高さ")
粒 = namedtuple("粒", "種類 数 広がり 速さ 毎")
#: Режим посоха: mode.default, toggle-debounce-ms, remember-on-item, show-on-select.
様式 = namedtuple("様式", "既定 間隔 品に記す 選択時")
#: Барьер (секция barrier, порядок ключей — как в config.yml).
結界 = namedtuple("結界", "モデル 充填 秒 冷却秒 失敗秒 前 上 半径 列 行 隙間 飛 始倍 広がり 閃光 消える 消散 打閃光 視距離")
#: Режимы посоха: луч и барьер.
様式名 = ("beam", "barrier")


class 設定(namedtuple("設定", "杖 禁止世界 詠唱 冷却 円 光 音表 唸り周期 終粒 跡粒 文言 様式 結界 形成粒 打粒 終結粒")):
    __slots__ = ()

    def 文(self, k):
        return self.文言.get(k, 既定文言.get(k, ""))


# --------------------------------------------------------------------------------------------- чтение

def _数か(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _区画(c, k):
    v = c.get(k) if isinstance(c, dict) else None
    return v if isinstance(v, dict) else {}


class _読み手:
    """Чтение секции с замечаниями «путь: что не так»."""

    def __init__(self, c, 道, 警告):
        self.c = c if isinstance(c, dict) else {}
        self.道 = 道
        self.警告 = 警告

    def _言う(self, k, m):
        self.警告(self.道 + "." + k + ": " + m)

    def 実数(self, k, 既定, lo, hi):
        v = self.c.get(k)
        if v is None:
            return float(既定)
        if not _数か(v):
            self._言う(k, "не число («" + str(v) + "»), взято " + str(既定))
            return float(既定)
        x = float(v)
        if x < lo or x > hi:
            y = min(hi, max(lo, x))
            self._言う(k, str(v) + " вне " + str(lo) + ".." + str(hi) + ", взято " + str(y))
            return float(y)
        return x

    def 整数(self, k, 既定, lo, hi):
        v = self.c.get(k)
        if v is None:
            return int(既定)
        if not _数か(v) or float(v) != int(v):
            self._言う(k, "не целое («" + str(v) + "»), взято " + str(既定))
            return int(既定)
        x = int(v)
        if x < lo or x > hi:
            y = min(hi, max(lo, x))
            self._言う(k, str(v) + " вне " + str(lo) + ".." + str(hi) + ", взято " + str(y))
            return y
        return x

    def 真偽(self, k, 既定):
        v = self.c.get(k)
        if v is None:
            return 既定
        if not isinstance(v, bool):
            self._言う(k, "ожидалось true/false, взято " + ("true" if 既定 else "false"))
            return 既定
        return v

    def 文字(self, k, 既定):
        v = self.c.get(k)
        if v is None:
            return 既定
        if isinstance(v, (dict, list)):
            self._言う(k, "ожидалась строка, взято «" + 既定 + "»")
            return 既定
        return str(v)


_鍵 = re.compile(r"[a-z0-9_.\-]+(:[a-z0-9_.\-/]+)?\Z")
_型鍵 = re.compile(r"[a-z0-9_.\-]+:[a-z0-9_.\-/]+\Z")


def _模型(r, k, 既定):
    s = r.文字(k, 既定).strip().lower()
    if not _型鍵.match(s):
        r._言う(k, "«" + s + "» — не ключ вида пространство:имя, взято " + 既定)
        return 既定
    return s


def _音列(生, 道, 既定, 警告):
    if 生 is None:
        生 = 既定
    if not isinstance(生, list):
        警告(道 + ": ожидался список {sound, volume, pitch}")
        生 = 既定
    out = []
    for i, x in enumerate(生):
        if not isinstance(x, dict):
            警告(道 + "[" + str(i) + "]: не секция {sound, volume, pitch}")
            continue
        r = _読み手(x, 道 + "[" + str(i) + "]", 警告)
        鍵 = r.文字("sound", "").strip().lower()
        if not 鍵 or not _鍵.match(鍵):
            警告(道 + "[" + str(i) + "].sound: «" + 鍵 + "» не ключ звука, пропущен")
            continue
        out.append(音(鍵, f32(r.実数("volume", 1.0, 0.0, 10.0)), f32(r.実数("pitch", 1.0, 0.5, 2.0))))
    return tuple(out)


_粒名 = re.compile(r"[A-Z_]+\Z")


def _粒(c, 道, 種類, 数, 広がり, 速さ, 毎, 警告):
    r = _読み手(c, 道, 警告)
    t = r.文字("type", 種類).strip().upper()
    if not _粒名.match(t):
        r._言う("type", "«" + t + "» — не имя Particle, взято " + 種類)
        t = 種類
    return 粒(t, r.整数("count", 数, 0, 200), r.実数("spread", 広がり, 0.0, 4.0), r.実数("speed", 速さ, 0.0, 2.0),
             r.実数("every", 毎, 0.25, 30.0) if 毎 is not None else 0.0)


def 解析(c, 警告=lambda m: None):
    """dict из amazake.config → 設定. 警告(текст) — замечания (в лог)."""
    c = c if isinstance(c, dict) else {}

    r = _読み手(_区画(c, "staff"), "staff", 警告)
    杖設定 = 杖(r.文字("nexo-id", "azerus_moon_staff").strip() or "azerus_moon_staff",
               r.真偽("repair-consumable", True),
               r.実数("consume-seconds", 3600.0, 60.0, 86400.0),
               r.文字("use-permission", "").strip())

    世界 = c.get("disabled-worlds")
    if 世界 is None:
        世界 = []
    if not isinstance(世界, list):
        警告("disabled-worlds: ожидался список имён миров")
        世界 = []
    禁止 = frozenset(str(w) for w in 世界 if isinstance(w, (str, int)))

    r = _読み手(_区画(c, "cast"), "cast", 警告)
    充填 = r.整数("charge-ticks", 60, 1, 1200)
    詠唱設定 = 詠唱(充填,
                 r.実数("max-hold-seconds", 30.0, 1.0, 600.0),
                 r.整数("watch-period-ticks", 2, 1, 20),
                 r.真偽("show-charge-bar", True),
                 r.整数("bar-length", 20, 4, 60))
    if 詠唱設定.最長 * 20.0 <= 充填:
        警告("cast.max-hold-seconds: меньше времени заряда — круг гас бы раньше, взято " + str(充填 / 20.0 + 5.0))
        詠唱設定 = 詠唱設定._replace(最長=充填 / 20.0 + 5.0)
    if 詠唱設定.最長 * 20.0 >= 杖設定.秒 * 20.0 * 0.2:
        # звук и частицы «поедания» начинаются после 21.875 % срока: держать дольше нельзя
        警告("cast.max-hold-seconds: должно быть заметно меньше staff.consume-seconds, взято 30")
        詠唱設定 = 詠唱設定._replace(最長=min(30.0, 杖設定.秒 * 0.1))

    r = _読み手(_区画(c, "cooldown"), "cooldown", 警告)
    冷却設定 = 冷却(r.実数("seconds", 3.0, 0.0, 3600.0), r.実数("after-fizzle-seconds", 0.5, 0.0, 3600.0))

    r = _読み手(_区画(c, "circle"), "circle", 警告)
    円設定 = 円(_模型(r, "item-model", "azerus:moon_staff_circle"),
               r.実数("diameter", 3.0, 0.25, 16.0),
               r.実数("start-scale", 0.3, 0.01, 1.0),
               r.実数("forward", 2.4, 0.0, 8.0),
               r.実数("down", 0.0, -4.0, 4.0),
               r.整数("update-ticks", 4, 1, 40),
               r.実数("follow-min-move", 0.03, 0.0, 2.0),
               r.実数("follow-min-angle", 0.75, 0.0, 45.0),
               r.整数("teleport-duration", 2, 0, 59),
               r.整数("fizzle-ticks", 6, 1, 60),
               r.整数("fire-flash-ticks", 3, 1, 40),
               r.実数("fire-flash-scale", 1.2, 0.1, 4.0),
               r.整数("fire-fade-ticks", 8, 1, 60),
               r.実数("view-range", 1.0, 0.05, 16.0))

    r = _読み手(_区画(c, "beam"), "beam", 警告)
    光設定 = 光(_模型(r, "item-model", "azerus:moon_staff_beam"),
               r.実数("range", 29.0, 1.0, 31.0),     # R = round(Lv*8) ≤ 250 → не длиннее 31.25 блока
               r.実数("width", 0.9, 0.05, 8.0),
               r.実数("start-width-factor", 1.4, 0.0, 8.0),
               r.整数("grow-ticks", 2, 1, 40),
               r.整数("hold-ticks", 3, 0, 100),
               r.整数("fade-ticks", 10, 1, 100),
               r.実数("view-range", 1.5, 0.05, 16.0),
               r.実数("damage", 10.0, 0.0, 10000.0),
               _模型(r, "damage-type", "minecraft:magic"),
               r.実数("hit-radius", 0.6, 0.0, 4.0),
               r.真偽("hit-players", True),
               r.真偽("ignore-invulnerability", True),
               r.実数("piece-length", 4.0, 1.0, 16.0))

    s = _区画(c, "sounds")
    音表 = {}
    for 名 in 音の名前:
        音表[名] = _音列(s.get(名), "sounds." + 名, 既定音[名], 警告)
    唸り = _読み手(s, "sounds", 警告).整数("hum-period-ticks", 20, 2, 200)

    p = _区画(c, "particles")
    終粒 = _粒(_区画(p, "end"), "particles.end", "END_ROD", 14, 0.25, 0.06, None, 警告)
    跡粒 = _粒(_区画(p, "trail"), "particles.trail", "ELECTRIC_SPARK", 1, 0.05, 0.0, 1.5, 警告)
    形成粒 = _粒(_区画(p, "barrier-form"), "particles.barrier-form", "ELECTRIC_SPARK", 3, 0.15, 0.02, None, 警告)
    打粒 = _粒(_区画(p, "barrier-hit"), "particles.barrier-hit", "END_ROD", 16, 0.12, 0.07, None, 警告)
    終結粒 = _粒(_区画(p, "barrier-end"), "particles.barrier-end", "ELECTRIC_SPARK", 2, 0.2, 0.03, None, 警告)

    r = _読み手(_区画(c, "mode"), "mode", 警告)
    既定様式 = r.文字("default", "beam").strip().lower()
    if 既定様式 not in 様式名:
        r._言う("default", "«" + 既定様式 + "» — не beam/barrier, взято beam")
        既定様式 = "beam"
    様式設定 = 様式(既定様式, r.整数("toggle-debounce-ms", 300, 50, 2000), r.真偽("remember-on-item", True),
                  r.真偽("show-on-select", True))

    r = _読み手(_区画(c, "barrier"), "barrier", 警告)
    結充填 = r.整数("charge-ticks", 20, 5, 200)
    結界設定 = 結界(_模型(r, "item-model", "azerus:moon_staff_barrier"),
                 結充填,
                 r.実数("duration-seconds", 10.0, 1.0, 120.0),
                 r.実数("cooldown-seconds", 15.0, 0.0, 3600.0),
                 r.実数("after-fizzle-seconds", 0.5, 0.0, 3600.0),
                 r.実数("forward", 2.0, 0.5, 8.0),
                 r.実数("up", 0.25, -4.0, 4.0),
                 r.実数("cell-radius", 0.5, 0.2, 1.5),
                 r.整数("columns", 3, 1, 7),
                 r.整数("rows", 5, 1, 9),
                 r.実数("cell-gap", 0.04, 0.0, 0.5),
                 r.整数("fly-ticks", 6, 1, 40),
                 r.実数("fly-start-scale", 0.15, 0.0, 1.0),
                 r.実数("fly-spread", 0.15, 0.0, 1.0),
                 r.整数("form-flash-ticks", 3, 0, 20),
                 r.整数("fade-ticks", 12, 1, 60),
                 r.整数("fizzle-ticks", 6, 1, 60),
                 r.整数("hit-flash-ticks", 4, 1, 20),
                 r.実数("view-range", 1.5, 0.05, 16.0))
    if 結界設定.列 * 結界設定.行 > 25:
        行 = max(1, 25 // 結界設定.列)
        r._言う("rows", "columns×rows = " + str(結界設定.列 * 結界設定.行) + " > 25 ячеек, рядов взято " + str(行))
        結界設定 = 結界設定._replace(行=行)
    if 結界設定.飛 >= 結界設定.充填:
        r._言う("fly-ticks", str(結界設定.飛) + " не меньше charge-ticks, взято " + str(結界設定.充填 - 1))
        結界設定 = 結界設定._replace(飛=結界設定.充填 - 1)

    文言 = dict(既定文言)
    m = c.get("messages")
    if isinstance(m, dict):
        for k, v in m.items():
            if isinstance(v, (str, int, float)) and not isinstance(v, bool):
                文言[str(k)] = str(v)
            elif isinstance(v, list):
                文言[str(k)] = "<newline>".join(str(x) for x in v)
    return 設定(杖設定, 禁止, 詠唱設定, 冷却設定, 円設定, 光設定, 音表, 唸り, 終粒, 跡粒, 文言, 様式設定, 結界設定, 形成粒,
              打粒, 終結粒)


#: Снимок по умолчанию (до чтения файла) и текущий (подменяет main при загрузке и /cmagic reload).
既定 = 解析({})
現在 = [既定]


def 今():
    return 現在[0]
