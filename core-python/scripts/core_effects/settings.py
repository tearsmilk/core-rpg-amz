# 設定 — разбор config.yml (порт Settings.java). Чистый модуль: на вход dict из amazake.config (YAML → JSON → dict),
# на выход неизменяемый снимок. Неверное значение → 警告(текст) и значение по умолчанию — те же ключи, пределы и
# тексты предупреждений, что в Java-системе. Семантика чтения — как у Bukkit ConfigurationSection
# (getBoolean: только true/false, getString: любое значение строкой, getStringList: строки и числа, contains: не null).

import re

import logic
from logic import java_str, java_trim, 数か, 範囲, 範囲を解析, 秒を解析, 出血, 恐慌, 温熱, 呪い

既定の文 = {
    "usage": "<gray>/effects give <игрок> <эффект> [время] | remove <игрок> <эффект> | clear <игрок|all> | list [игрок] | bandage [игрок] [кол-во] | reload</gray>",
    "no-player": "<red>Игрок <white><player></white> не в сети.</red>",
    "no-effect": "<red>Нет эффекта «<white><effect></white>». Есть: bleeding, panic, warmth, curse.</red>",
    "bad-time": "<red>Неверное время «<white><time></white>»: 90, 2m, 1m30s, inf.</red>",
    "given": "<green>Игроку <white><player></white> — <effect> на <white><time></white>.</green>",
    "removed": "<green>У <white><player></white> снят эффект <effect>.</green>",
    "not-active": "<gray>У <white><player></white> нет эффекта <effect>.</gray>",
    "cleared": "<green>У <white><player></white> сняты все эффекты (<white><count></white>).</green>",
    "cleared-all": "<green>Сняты все эффекты у <white><count></white> игроков в сети.</green>",
    "list-empty": "<gray>У <white><player></white> нет эффектов.</gray>",
    "list": "<gray>Эффекты <white><player></white>: <list></gray>",
    "bandage-given": "<green>Игроку <white><player></white> выдано бинтов: <white><amount></white>.</green>",
    "reloaded": "<green>config.yml перечитан.</green>",
    "got-bleeding": "<#d02030>Вы истекаете кровью!</#d02030> <gray>Перевяжитесь бинтом.</gray>",
    "got-panic": "<dark_gray>Вас охватывает <#8a6cff>паника</#8a6cff>...</dark_gray>",
    "got-warmth": "<#ffb050>По телу разливается тепло.</#ffb050> <gray>Не забывайте пить молоко или зелье лечения!</gray>",
    "got-curse": "<#a040ff>На вас лежит проклятье.</#a040ff> <gray>Вещи начнут пропадать...</gray>",
    "ended": "<gray>Эффект <effect> прошёл.</gray>",
    "cured": "<green>Эффект <effect> снят.</green>",
    "bandaged-other": "<green>Вы перевязали <white><player></white>.</green>",
    "bandaged-by": "<green><white><player></white> перевязал вас.</green>",
    "bandage-useless": "<gray>Перевязывать нечего.</gray>",
    "faint": "<dark_red>Вы теряете сознание от потери крови...</dark_red>",
    "wake": "<gray>Вы приходите в себя.</gray>",
    "quenched": "<#ffb050>Жар отступает.</#ffb050>",
    "warmth-warn-1": "<#ffb050>Вам жарко. Выпейте молока или зелье лечения.</#ffb050>",
    "warmth-warn-2": "<#ff7a30>Жар нарастает! Срочно выпейте молока или зелье лечения!</#ff7a30>",
    "warmth-warn-3": "<red><bold>Вы сгораете изнутри!</bold> Ещё немного — и конец.</red>",
    "curse-dropped": "<#a040ff>Проклятье вырвало из рук: <white><item></white> ×<amount></#a040ff>",
    "curse-took": "<#a040ff>Проклятье забрало: <white><item></white> ×<amount></#a040ff>",
    "death-bleeding": "<player> истёк кровью",
    "death-warmth": "<player> сгорел изнутри от жара",
}


class 束:
    """Неизменяемая группа настроек (как record): атрибуты из именованных аргументов."""

    def __init__(self, **項目):
        self.__dict__.update(項目)

    def __repr__(self):
        return "束(" + ", ".join(k + "=" + repr(v) for k, v in self.__dict__.items()) + ")"


class 文集:
    """messages.*: 取る(ключ) — шаблон или «»."""

    def __init__(self, 写像):
        self.写像 = dict(写像)

    def 取る(self, 鍵):
        return self.写像.get(鍵, "")


# ---------------------------------------------------------------------------------------------------------------
# Чтение dict как ConfigurationSection
# ---------------------------------------------------------------------------------------------------------------

_無 = object()


class _節:
    def __init__(self, 根):
        self.根 = 根 if isinstance(根, dict) else {}

    def 値(self, 道):
        """get(path): значение по пути «a.b.c» или None."""
        今 = self.根
        for 名 in 道.split("."):
            if not isinstance(今, dict) or 名 not in 今:
                return None
            今 = 今[名]
        return 今

    def ある(self, 道):
        return self.値(道) is not None

    def 真偽(self, 道, 既定):
        v = self.値(道)
        return v if isinstance(v, bool) else 既定

    def 文字列(self, 道):
        """getString(path): любое значение строкой, нет — None."""
        v = self.値(道)
        return None if v is None else (v if isinstance(v, str) else java_str(v))

    def 一覧(self, 道, 既定=None):
        v = self.値(道)
        return v if isinstance(v, list) else 既定

    def 文字列一覧(self, 道):
        """getStringList: строки и числа/логические (строкой); прочее пропускается."""
        出 = []
        for o in self.一覧(道, []) or []:
            if isinstance(o, str):
                出.append(o)
            elif isinstance(o, (bool, int, float)):
                出.append(java_str(o))
        return 出


def _文字(c, 道, 既定):
    s = c.文字列(道)
    return 既定 if s is None else s


def _識別子(c, 道, 既定, 警告):
    s = java_trim(_文字(c, 道, 既定)).lower()
    if not re.fullmatch(r"[a-z0-9_-]{1,32}", s):
        警告(道 + ": «" + s + "» — нужны латинские буквы, цифры, _ и -; беру " + 既定)
        return 既定
    return s


def _整数(c, 道, 既定, 下, 上, 警告):
    生 = c.値(道)
    if 生 is None:
        return 既定
    if 数か(生) and float(生) == int(生) and 下 <= int(生) <= 上:
        return int(生)
    警告(道 + ": «" + java_str(生) + "» — нужно целое " + str(下) + ".." + str(上) + ", беру " + str(既定))
    return 既定


def _秒刻(c, 道, 既定秒, 下, 上, 警告):
    """Секунды → тики (round, как Math.round)."""
    生 = c.値(道)
    if 生 is None:
        return 既定秒 * 20
    if 数か(生) and 下 <= float(生) <= 上:
        return int(logic.math.floor(float(生) * 20 + 0.5))
    警告(道 + ": «" + java_str(生) + "» — нужно число секунд " + str(下) + ".." + str(上) + ", беру " + str(既定秒))
    return 既定秒 * 20


def _小数(c, 道, 既定, 下, 上, 警告):
    生 = c.値(道)
    if 生 is None:
        return 既定
    if 数か(生) and logic.math.isfinite(float(生)) and 下 <= float(生) <= 上:
        return float(生)
    警告(道 + ": «" + java_str(生) + "» — нужно число " + java_str(float(下)) + ".." + java_str(float(上))
       + ", беру " + java_str(float(既定)))
    return 既定


def _範囲(c, 道, 既定, 下, 上, 警告):
    生 = c.値(道)
    if 生 is None:
        return 既定
    r = 範囲を解析(生, 下, 上)
    if r is None:
        警告(道 + ": «" + java_str(生) + "» — нужно число или диапазон «a-b» в " + str(下) + ".." + str(上)
           + ", беру " + repr(既定))
        return 既定
    return r


def _印(c, 道, 既定):
    """Метки «чем лечится»: нижний регистр, _ → -."""
    生 = c.文字列一覧(道) if c.ある(道) else 既定
    出 = []
    for s in 生:
        if s is not None and java_trim(s):
            t = java_trim(s).lower().replace("_", "-")
            if t not in 出:
                出.append(t)
    return frozenset(出)


def _共通(c, 種, 名前, 秒, 治療, 警告):
    p = 種 + "."
    既定 = 秒
    生 = c.値(p + "default-seconds")
    if 生 is not None:
        v = 秒を解析(java_str(生))
        if v is None:
            警告(p + "default-seconds: «" + java_str(生) + "» — нужно 90, 2m, 1m30s или inf; беру " + str(秒))
        else:
            既定 = v
    return 束(名前=_文字(c, p + "name", 名前), 既定秒=既定, 治療=_印(c, p + "cured-by", 治療))


# ---------------------------------------------------------------------------------------------------------------
# Разбор
# ---------------------------------------------------------------------------------------------------------------

def 読む(根, 警告=lambda w: None):
    """dict config.yml → 束 настроек (Settings.from)."""
    c = _節(根)
    共通 = {
        出血: _共通(c, 出血, "<#d02030>Кровотечение</#d02030>", 120, ["bandage"], 警告),
        恐慌: _共通(c, 恐慌, "<#8a6cff>Паника</#8a6cff>", 30, ["milk"], 警告),
        温熱: _共通(c, 温熱, "<#ffb050>Тепло</#ffb050>", 300, [], 警告),
        呪い: _共通(c, 呪い, "<#a040ff>Проклятье</#a040ff>", 180, [], 警告),
    }
    書式 = {
        出血: _文字(c, "hud.format.bleeding", "<font:coreeffects:icons></font> <#ff4050><time></#ff4050>"),
        恐慌: _文字(c, "hud.format.panic", "<font:coreeffects:icons></font> <#a890ff><time></#a890ff>"),
        温熱: _文字(c, "hud.format.warmth",
                  "<font:coreeffects:icons></font> <#ffb050><time></#ffb050> <gray><thirst></gray>"),
        呪い: _文字(c, "hud.format.curse", "<font:coreeffects:icons></font> <#c070ff><time></#c070ff>"),
    }
    表示 = 束(
        アクションバー=c.真偽("hud.actionbar", True),
        バー間隔=_整数(c, "hud.actionbar-interval-ticks", 20, 5, 200, 警告),
        区切り=_文字(c, "hud.separator", "  "),
        書式=書式,
        気絶書式=_文字(c, "hud.unconscious", "<dark_red>Без сознания <white><time></white></dark_red>"),
        papi=c.真偽("hud.placeholderapi", True),
        papi識別子=_識別子(c, "hud.placeholderapi-identifier", "coreeffects", 警告))

    画面 = 束(
        有効=c.真偽("screen.overlay", True),
        更新刻=_整数(c, "screen.refresh-seconds", 3, 1, 30, 警告) * 20,
        暗闇=c.真偽("screen.darkness", True),
        気絶盲目=c.真偽("screen.blindness-when-fainted", True),
        吐き気=c.真偽("screen.nausea", True))

    出血設定 = 束(
        ダメージ=_小数(c, "bleeding.damage", 1.0, 0, 40, 警告),
        ダメージ間隔=_秒刻(c, "bleeding.damage-interval-seconds", 4, 1, 600, 警告),
        最低体力=_小数(c, "bleeding.min-health", 2.0, 0, 1000, 警告),
        気絶まで=_秒刻(c, "bleeding.faint-after-seconds", 60, 0, 86400, 警告),
        再気絶まで=_秒刻(c, "bleeding.refaint-after-seconds", 30, 1, 86400, 警告),
        気絶時間=_秒刻(c, "bleeding.faint-seconds", 8, 1, 600, 警告),
        粒子=c.真偽("bleeding.particles", True),
        粒子間隔=_整数(c, "bleeding.particle-interval-ticks", 5, 1, 100, 警告))

    幻影種類 = [java_trim(t).upper() for t in c.文字列一覧("panic.phantoms.types")]
    if not c.ある("panic.phantoms.types"):
        幻影種類 += ["ZOMBIE", "SKELETON", "HUSK", "ENDERMAN", "WITHER_SKELETON", "PLAYER"]
    幻影 = 束(
        有効=c.真偽("panic.phantoms.enabled", True),
        出現間隔=_範囲(c, "panic.phantoms.interval-seconds", 範囲(1, 2), 1, 600, 警告),
        最大数=_整数(c, "panic.phantoms.max-alive", 10, 0, 40, 警告),
        寿命=_範囲(c, "panic.phantoms.lifetime-seconds", 範囲(2, 4), 1, 60, 警告),
        距離=_範囲(c, "panic.phantoms.distance", 範囲(2, 5), 1, 32, 警告),
        接近速度=_小数(c, "panic.phantoms.approach-speed", 0.3, 0, 1, 警告),
        種類=tuple(幻影種類),
        音=c.真偽("panic.phantoms.sounds", True),
        群れ=_範囲(c, "panic.phantoms.burst", 範囲(2, 3), 1, 10, 警告),
        消失距離=_小数(c, "panic.phantoms.vanish-distance", 0.7, 0.2, 5, 警告))
    恐慌設定 = 束(
        追加攻撃=_小数(c, "panic.bonus-damage", 2.5, 0, 100, 警告),
        体力減少=_小数(c, "panic.max-health-penalty", 4.0, 0, 100, 警告),
        鈍足=_小数(c, "panic.slowness", 0.35, 0, 0.95, 警告),
        暗闇=c.真偽("panic.darkness", True),
        吐き気間隔=_秒刻(c, "panic.nausea-every-seconds", 12, 0, 3600, 警告),
        吐き気時間=_秒刻(c, "panic.nausea-seconds", 4, 1, 60, 警告),
        方向狂い=c.真偽("panic.disorient", True),
        狂い強さ=_小数(c, "panic.disorient-strength", 0.75, 0, 1, 警告),
        狂い速さ=_小数(c, "panic.disorient-speed", 0.2, 0.01, 1, 警告),
        ねじれ変更=_範囲(c, "panic.twist-change-seconds", 範囲(2, 4), 1, 60, 警告),
        ねじれ角=_範囲(c, "panic.twist-degrees", 範囲(60, 300), 0, 360, 警告),
        幻影=幻影)

    警告率 = []
    for o in c.一覧("warmth.warn-at-percent", [50, 75, 90]):
        if 数か(o) and 0 < int(o) < 100:
            警告率.append(int(o))
        else:
            警告("warmth.warn-at-percent: «" + java_str(o) + "» — нужно число 1..99, пропускаю")
    警告率.sort()
    温熱設定 = 束(
        回復=_小数(c, "warmth.heal", 0.5, 0, 40, 警告),
        回復間隔=_秒刻(c, "warmth.heal-interval-seconds", 4, 1, 600, 警告),
        期限=_秒刻(c, "warmth.deadline-seconds", 120, 0, 86400, 警告),
        警告率=tuple(警告率[-3:] if len(警告率) > 3 else 警告率),
        渇き癒し=_印(c, "warmth.quenched-by", ["milk", "healing-potion"]),
        吐き気率=_整数(c, "warmth.nausea-at-percent", 90, 0, 100, 警告),
        死亡文=_文字(c, "warmth.death-message", ""))

    保護素材 = frozenset(java_trim(m).upper() for m in c.文字列一覧("curse.protect-materials"))
    呪い設定 = 束(
        間隔=_範囲(c, "curse.interval-seconds", 範囲(8, 15), 1, 3600, 警告),
        量=_範囲(c, "curse.amount", 範囲(1, 3), 1, 64, 警告),
        全部確率=_小数(c, "curse.whole-stack-chance", 0.0, 0, 1, 警告),
        ホットバー=c.真偽("curse.hotbar", True),
        オフハンド=c.真偽("curse.offhand", False),
        落とす=c.真偽("curse.drop-instead", True),
        保護素材=保護素材,
        保護NBT=tuple(c.文字列一覧("curse.protect-nbt") if c.ある("curse.protect-nbt") else
                    ["MMOITEMS_ITEM_ID", "nexo:id", "coreskyrift:", "coreportals:", "rezusmenu:", "corerpg:",
                     "zatochka", "magicseals"]),
        保護名前=c.真偽("curse.protect-named", False),
        保護モデル=c.真偽("curse.protect-custom-model", True),
        拾い遅延=_整数(c, "curse.pickup-delay-ticks", 60, 0, 1200, 警告))

    包帯 = 束(
        素材=_文字(c, "bandage.material", "PAPER").upper(),
        モデル=_文字(c, "bandage.model", "coreeffects:bandage"),
        名前=_文字(c, "bandage.name", "<white>Бинт</white>"),
        説明=tuple(c.文字列一覧("bandage.lore") if c.ある("bandage.lore") else
                 ["<gray>ПКМ — перевязать рану.</gray>", "<gray>ПКМ по игроку — перевязать его.</gray>",
                  "<dark_gray>Останавливает кровотечение.</dark_gray>"]),
        待ち時間=_整数(c, "bandage.cooldown-ticks", 20, 0, 1200, 警告),
        他人=c.真偽("bandage.others", True))

    文 = dict(既定の文)
    節 = c.値("messages")
    if isinstance(節, dict):
        for k, v in 節.items():
            if v is None:
                continue   # как у Bukkit: «ключ: ~» — ключа нет, остаётся текст по умолчанию
            文[str(k)] = v if isinstance(v, str) else java_str(v)

    return 束(
        接頭辞=_文字(c, "prefix", "<dark_gray>[<#d02030>Эффекты</#d02030>]</dark_gray> "),
        死亡時解除=c.真偽("clear-on-death", True),
        共通=共通, 表示=表示, 画面=画面, 出血=出血設定, 恐慌=恐慌設定, 温熱=温熱設定, 呪い=呪い設定, 包帯=包帯,
        文=文集(文))


#: настройки по умолчанию (пустой config.yml)
既定 = 読む({})
