# 論理 — чистая логика core_effects (без amazake и Java: проверяется tests_core_effects.py обычным python3).
#
# Порт пакетов Java-системы core-effects: effect/EffectType, effect/Active, effect/EffectBook, logic/Durations,
# logic/Range, logic/BleedingRules, logic/WarmthRules, logic/Disorient, logic/ItemFacts, logic/CurseFilter,
# hud/Overlay. Поведение и строки хранения — те же (тесты повторяют Java-тесты с теми же ожидаемыми значениями).

import math
import re
import threading

# ---------------------------------------------------------------------------------------------------------------
# Мелочи, повторяющие Java (trim, parseInt/parseLong, split без пустых хвостов)
# ---------------------------------------------------------------------------------------------------------------

_整数 = re.compile(r"[+-]?[0-9]+")


def java_trim(文字列):
    """String.trim(): срезать символы ≤ ' ' с краёв."""
    始 = 0
    終 = len(文字列)
    while 始 < 終 and 文字列[始] <= " ":
        始 += 1
    while 終 > 始 and 文字列[終 - 1] <= " ":
        終 -= 1
    return 文字列[始:終]


def java_int(文字列, ビット=32):
    """Integer.parseInt / Long.parseLong: только [+-]цифры ASCII, в пределах типа; иначе ValueError."""
    if not _整数.fullmatch(文字列):
        raise ValueError(文字列)
    値 = int(文字列)
    限界 = 1 << (ビット - 1)
    if 値 < -限界 or 値 >= 限界:
        raise ValueError(文字列)
    return 値


def java_split(文字列, 区切り):
    """String.split: пустые элементы в конце отбрасываются."""
    部分 = 文字列.split(区切り)
    while len(部分) > 1 and 部分[-1] == "":
        部分.pop()
    return 部分


def java_str(値):
    """Object.toString() для значений из YAML (для предупреждений и getString)."""
    if 値 is None:
        return "null"
    if isinstance(値, bool):
        return "true" if 値 else "false"
    if isinstance(値, float):
        if 値 != 値:
            return "NaN"
        if 値 in (float("inf"), float("-inf")):
            return "Infinity" if 値 > 0 else "-Infinity"
        return repr(値)
    if isinstance(値, (list, tuple)):
        return "[" + ", ".join(java_str(v) for v in 値) + "]"
    if isinstance(値, dict):
        return "{" + ", ".join(str(k) + "=" + java_str(v) for k, v in 値.items()) + "}"
    return str(値)


def 数か(値):
    """instanceof Number (bool — не число, как Boolean в Java)."""
    return isinstance(値, (int, float)) and not isinstance(値, bool)


# ---------------------------------------------------------------------------------------------------------------
# 効果の種類 (EffectType) — id: ключ в config.yml, PDC, PlaceholderAPI и командах
# ---------------------------------------------------------------------------------------------------------------

出血 = "bleeding"
恐慌 = "panic"
温熱 = "warmth"
呪い = "curse"

#: порядок как у enum EffectType (EnumMap: хранение, список, actionbar)
種類一覧 = (出血, 恐慌, 温熱, 呪い)

#: название по умолчанию (в сообщениях берётся из config.yml)
既定題名 = {出血: "Кровотечение", 恐慌: "Паника", 温熱: "Тепло", 呪い: "Проклятье"}

別名 = {
    出血: ("кровотечение", "кровь", "bleed"),
    恐慌: ("паника", "страх", "fear"),
    温熱: ("тепло", "жар", "warm"),
    呪い: ("проклятье", "проклятие", "curse"),
}


def 種類を解析(生):
    """По id или русскому/английскому названию, без учёта регистра; None — не найдено."""
    if 生 is None:
        return None
    s = java_trim(str(生)).lower()
    for 種 in 種類一覧:
        if s == 種 or s in 別名[種]:
            return 種
    return None


# ---------------------------------------------------------------------------------------------------------------
# 継続時間 (Durations): «90», «2m», «1m30s», «1h», «inf» → секунды; тики → «m:ss»
# ---------------------------------------------------------------------------------------------------------------

無限 = -1
最大秒 = 86400 * 7
_部分 = re.compile(r"([0-9]+)([hms]?)")


def 秒を解析(生):
    """Секунды из строки; «inf», «infinite», «∞», «навсегда» — 無限 (-1). Неверно, 0 или > 最大秒 — None."""
    if 生 is None:
        return None
    s = java_trim(str(生)).lower()
    if s in ("inf", "infinite", "∞", "навсегда"):
        return 無限
    s = s.replace("ч", "h").replace("м", "m").replace("с", "s")
    if not s:
        return None
    合計 = 0
    位置 = 0
    for m in _部分.finditer(s):
        if m.start() != 位置:
            return None
        位置 = m.end()
        数字 = m.group(1)
        if len(数字) > 9:
            return None
        n = int(数字)
        単位 = m.group(2)
        合計 += n * 3600 if 単位 == "h" else n * 60 if 単位 == "m" else n
        if 合計 > 最大秒:
            return None
    if 位置 != len(s) or 合計 <= 0:
        return None
    return 合計


def 時間表示(刻):
    """«m:ss» (или «h:mm:ss»); бесконечность — «∞»."""
    if 刻 < 0:
        return "∞"
    秒 = (刻 + 19) // 20
    時 = 秒 // 3600
    分 = 秒 % 3600 // 60
    s = 秒 % 60
    return "%d:%02d:%02d" % (時, 分, s) if 時 > 0 else "%d:%02d" % (分, s)


def 残り秒(刻):
    """Оставшиеся целые секунды (вверх); бесконечность — -1."""
    return -1 if 刻 < 0 else (刻 + 19) // 20


# ---------------------------------------------------------------------------------------------------------------
# 範囲 (Range): «a-b» или число
# ---------------------------------------------------------------------------------------------------------------

class 範囲:
    __slots__ = ("最小", "最大")

    def __init__(self, a, b):
        if a > b:
            a, b = b, a
        self.最小 = a
        self.最大 = b

    def 選ぶ(self, 乱数):
        """Равномерно в [最小, 最大] (乱数 — random.Random или модуль random)."""
        return self.最小 if self.最小 == self.最大 else self.最小 + 乱数.randrange(self.最大 - self.最小 + 1)

    def __eq__(self, 他):
        return isinstance(他, 範囲) and 他.最小 == self.最小 and 他.最大 == self.最大

    def __hash__(self):
        return hash((self.最小, self.最大))

    def __repr__(self):
        return str(self.最小) if self.最小 == self.最大 else "%d-%d" % (self.最小, self.最大)


def 範囲を解析(生, 下, 上):
    """«3», «2-5», «2..5»; вне [下, 上] или не число — None."""
    if 生 is None:
        return None
    if 数か(生):
        v = int(生)
        return 範囲(v, v) if 下 <= v <= 上 and float(生) == v else None
    s = java_trim(java_str(生)).replace("..", "-")
    try:
        線 = s.find("-", 1)
        if 線 < 0:
            a = java_int(s)
            b = a
        else:
            a = java_int(java_trim(s[:線]))
            b = java_int(java_trim(s[線 + 1:]))
    except ValueError:
        return None
    r = 範囲(a, b)
    return r if r.最小 >= 下 and r.最大 <= 上 else None


# ---------------------------------------------------------------------------------------------------------------
# 出血の規則 (BleedingRules): часы кровотечения и обморока
# ---------------------------------------------------------------------------------------------------------------

無 = "NONE"
傷 = "DAMAGE"
気絶 = "FAINT"
覚醒 = "WAKE"


def 出血の刻(未処置, 気絶残, 経過, 傷間隔, 気絶まで, 再気絶まで, 気絶刻):
    """Один тик: (未処置, 気絶残, 出来事). 未処置 — тиков без перевязки, 気絶残 — тиков обморока (0 — в сознании)."""
    if 気絶残 > 0:
        残 = 気絶残 - 1
        if 残 == 0:
            return max(0, 気絶まで - 再気絶まで), 0, 覚醒
        return 未処置, 残, 傷 if 傷間隔 > 0 and 経過 > 0 and 経過 % 傷間隔 == 0 else 無
    u = 未処置 + 1
    if 気絶まで > 0 and u >= 気絶まで:
        return u, 気絶刻, 気絶
    return u, 0, 傷 if 傷間隔 > 0 and 経過 > 0 and 経過 % 傷間隔 == 0 else 無


def 許容ダメージ(体力, ダメージ, 最低):
    """Урон, не опускающий здоровье ниже 最低 (0 — может убить)."""
    if ダメージ <= 0 or 体力 <= 0:
        return 0.0
    if 最低 <= 0:
        return min(体力, ダメージ)
    return max(0.0, min(ダメージ, 体力 - 最低))


def 血の濃さ(未処置, 気絶まで):
    """Сила крови на экране 1..3 по доле времени до обморока."""
    if 気絶まで <= 0:
        return 1
    f = 未処置 / 気絶まで
    return 1 if f < 0.4 else 2 if f < 0.75 else 3


# ---------------------------------------------------------------------------------------------------------------
# 温熱の規則 (WarmthRules): 渇き — тиков без молока/зелья лечения
# ---------------------------------------------------------------------------------------------------------------

def 温熱段階(渇き, 期限, 警告率):
    """Сколько порогов предупреждения пройдено (0..len)."""
    if 期限 <= 0:
        return 0
    段 = 0
    for p in 警告率:
        if 渇き * 100 >= 期限 * p:
            段 += 1
    return 段


def 致命(渇き, 期限):
    return 期限 > 0 and 渇き >= 期限


def 渇き割合(渇き, 期限):
    """Доля 0..1 пути к смерти."""
    return 0.0 if 期限 <= 0 else max(0.0, min(1.0, 渇き / 期限))


def 回復時(経過, 間隔):
    return 間隔 > 0 and 経過 > 0 and 経過 % 間隔 == 0


# ---------------------------------------------------------------------------------------------------------------
# 方向狂い (Disorient): yaw 0 — юг (+Z), вперёд = (−sin, cos), влево = (cos, sin)
# ---------------------------------------------------------------------------------------------------------------

def 狂った方向(前, 左, 視線yaw, ねじれ):
    """Единичный горизонтальный вектор (x, z) или None, если WASD не нажаты."""
    if 前 == 0 and 左 == 0:
        return None
    yaw = math.radians(視線yaw)
    x = 前 * -math.sin(yaw) + 左 * math.cos(yaw)
    z = 前 * math.cos(yaw) + 左 * math.sin(yaw)
    長 = math.hypot(x, z)
    x /= 長
    z /= 長
    t = math.radians(ねじれ)
    c = math.cos(t)
    s = math.sin(t)
    return (x * c - z * s, x * s + z * c)


def 混ぜる(vx, vz, 方向, 速さ, 強さ):
    """Смешать скорость с желаемой: 強さ 0 — как было, 1 — только желаемая."""
    return (vx * (1 - 強さ) + 方向[0] * 速さ * 強さ, vz * (1 - 強さ) + 方向[1] * 速さ * 強さ)


# ---------------------------------------------------------------------------------------------------------------
# 呪いの選別 (ItemFacts + CurseFilter): проклятье берёт только обычные предметы
# ---------------------------------------------------------------------------------------------------------------

class 品の事実:
    """Сведения о предмете (как record ItemFacts): material, max_durability, equippable, attributes,
    death_protection, tool_or_weapon, container, named, custom_model, nbt."""
    __slots__ = ("素材", "最大耐久", "装備可", "属性", "死の守り", "道具", "容器", "名前付き", "独自モデル", "nbt")

    def __init__(self, 素材, 最大耐久=0, 装備可=False, 属性=False, 死の守り=False, 道具=False, 容器=False,
                 名前付き=False, 独自モデル=False, nbt=""):
        self.素材 = 素材
        self.最大耐久 = 最大耐久
        self.装備可 = 装備可
        self.属性 = 属性
        self.死の守り = 死の守り
        self.道具 = 道具
        self.容器 = 容器
        self.名前付き = 名前付き
        self.独自モデル = 独自モデル
        self.nbt = nbt


#: снаряжение, которое не ломается и не во всех тегах
装備 = frozenset((
    "TOTEM_OF_UNDYING", "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "ELYTRA", "SHEARS", "FISHING_ROD",
    "FLINT_AND_STEEL", "BRUSH", "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK", "SPYGLASS", "COMPASS",
    "RECOVERY_COMPASS", "CLOCK", "LEAD", "SADDLE", "TURTLE_HELMET", "WOLF_ARMOR", "FILLED_MAP", "WRITABLE_BOOK",
    "WRITTEN_BOOK", "KNOWLEDGE_BOOK", "BUNDLE", "GOAT_HORN"))

#: окончания имён материалов снаряжения
語尾 = ("_SWORD", "_AXE", "_PICKAXE", "_SHOVEL", "_HOE", "_SPEAR", "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS",
      "_HORSE_ARMOR", "_NAUTILUS_ARMOR", "_HARNESS", "_SHULKER_BOX", "_BUNDLE")


def 素材で守られる(素材, 保護素材):
    """Часть проверки 普通の品 только по имени материала (дёшево — до чтения компонентов предмета)."""
    if not 素材 or 素材.endswith("AIR"):
        return True
    if 素材 in 装備 or 素材 == "SHULKER_BOX" or 素材.endswith(語尾):
        return True
    return 素材 in 保護素材


def 普通の品(f, 保護素材, 保護NBT, 保護名前, 保護モデル):
    """Можно ли проклятью забрать этот предмет (CurseFilter.ordinary)."""
    if f is None or not f.素材 or f.素材.endswith("AIR"):
        return False
    if f.最大耐久 > 0 or f.装備可 or f.属性 or f.死の守り or f.道具 or f.容器:
        return False
    if 素材で守られる(f.素材, 保護素材):
        return False
    if 保護名前 and f.名前付き:
        return False
    if 保護モデル and f.独自モデル:
        return False
    nbt = f.nbt or ""
    for 印 in 保護NBT:
        if 印 and 印 in nbt:
            return False
    return True


def 取る数(個数, 欲しい, 全部):
    """Сколько забрать из стопки: 欲しい, но не больше стопки; 全部 — всю."""
    if 個数 <= 0:
        return 0
    return 個数 if 全部 else max(1, min(個数, 欲しい))


# ---------------------------------------------------------------------------------------------------------------
# 効果帳 (EffectBook + Active): эффекты одного игрока
# ---------------------------------------------------------------------------------------------------------------

版 = "1"


class 効果:
    """Один эффект: 残り — тиков (-1 бесконечно), 経過 — с наложения, a/b — свои счётчики эффекта
    (кровотечение: без перевязки / обморок; тепло: без питья / ступень; проклятье: до потери)."""
    __slots__ = ("残り", "経過", "a", "b")

    def __init__(self, 残り, 経過, a, b):
        self.残り = 残り
        self.経過 = 経過
        self.a = a
        self.b = b

    def 無限か(self):
        return self.残り < 0

    def 写し(self):
        return 効果(self.残り, self.経過, self.a, self.b)


class 効果帳:
    """Пишет только поток игрока; читать можно из любого (PlaceholderAPI, команды) — под 錠, наружу — копии.
    Хранение в PDC — строка 符号化(): «1|bleeding:1200:40:900:0|panic:-1:0:0:0» (тип:осталось:прошло:a:b)."""

    def __init__(self):
        self.錠 = threading.Lock()
        self._中 = {}

    def 与える(self, 種, 刻, 初a):
        """Наложить или продлить (остаётся большее, бесконечность больше всего). True — эффект новый."""
        with self.錠:
            今 = self._中.get(種)
            if 今 is None:
                self._中[種] = 効果(刻, 0, 初a, 0)
                return True
            if 刻 < 0 or (今.残り >= 0 and 刻 > 今.残り):
                今.残り = 刻
            return False

    def 外す(self, 種):
        with self.錠:
            return self._中.pop(種, None) is not None

    def 全消し(self):
        """Снять все; список снятых (в порядке 種類一覧)."""
        with self.錠:
            あった = [t for t in 種類一覧 if t in self._中]
            self._中.clear()
            return あった

    def ある(self, 種):
        return 種 in self._中

    def 空(self):
        return not self._中

    def 取る(self, 種):
        """Копия состояния или None."""
        with self.錠:
            a = self._中.get(種)
            return None if a is None else a.写し()

    def 写し(self):
        """Копии всех активных, в порядке 種類一覧 (dict сохраняет порядок)."""
        with self.錠:
            return {t: self._中[t].写し() for t in 種類一覧 if t in self._中}

    def 生(self, 種):
        """Живой объект (только поток игрока, менять — под 錠)."""
        return self._中.get(種)

    def 進める(self, 刻):
        """Шаг времени: 経過 растёт, 残り уменьшается; кончившиеся удаляются и возвращаются (в порядке типов)."""
        終わり = []
        with self.錠:
            for t in 種類一覧:
                a = self._中.get(t)
                if a is None:
                    continue
                a.経過 += 刻
                if a.残り >= 0:
                    a.残り -= 刻
                    if a.残り <= 0:
                        del self._中[t]
                        終わり.append(t)
        return 終わり

    def 符号化(self):
        with self.錠:
            部 = [版]
            for t in 種類一覧:
                a = self._中.get(t)
                if a is not None:
                    部.append("%s:%d:%d:%d:%d" % (t, a.残り, a.経過, a.a, a.b))
            return "|".join(部)

    @staticmethod
    def 復号(生, 警告):
        """Разобрать строку 符号化(); битое и незнакомое пропускается, о каждом — 警告(часть, почему)."""
        帳 = 効果帳()
        if 生 is None or not java_trim(生):
            return 帳
        部 = java_split(生, "|")
        if 部[0] != 版:
            警告(生, "неизвестная версия «" + 部[0] + "»")
            return 帳
        for 片 in 部[1:]:
            f = java_split(片, ":")
            種 = 種類を解析(f[0]) if len(f) == 5 else None
            if 種 is None:
                警告(片, "не эффект")
                continue
            try:
                残り = java_int(f[1], 64)
                経過 = java_int(f[2], 64)
                if 残り == 0 or 残り < -1 or 経過 < 0:
                    警告(片, "неверное время")
                    continue
                帳._中[種] = 効果(残り, 経過, java_int(f[3], 64), java_int(f[4], 64))
            except ValueError:
                警告(片, "не число")
        return 帳


# ---------------------------------------------------------------------------------------------------------------
# 画面の重ね (hud/Overlay): глифы шрифта coreeffects:overlay в заголовке
# ---------------------------------------------------------------------------------------------------------------

マス送り = 145
全幅 = 289
負一 = ""
戻る = ""
暗 = ""

#: слои: первый символ четырёх клеток (у сплошного затемнения — один символ)
層_暗 = "DARK_FILL"
層_縁 = "VIGNETTE"
層_血 = "BLOOD"
層_熱 = "HEAT"
層_呪 = "CURSE"
層_恐 = "PANIC"
層一覧 = (層_暗, 層_縁, 層_血, 層_熱, 層_呪, 層_恐)
_層の字 = {層_暗: 0xE010, 層_縁: 0xE011, 層_血: 0xE015, 層_熱: 0xE019, 層_呪: 0xE01D, 層_恐: 0xE021}


def _層の文字(層):
    if 層 == 層_暗:
        return 暗
    c = _層の字[層]
    return chr(c) + 負一 + chr(c + 1) + 戻る + chr(c + 2) + 負一 + chr(c + 3)


_層の文字列 = {層: _層の文字(層) for 層 in 層一覧}


def 重ねる(部品):
    """部品 — [(слой, сколько раз)]; строка заголовка, пусто — накладки нет."""
    片 = []
    for 層, 回数 in 部品:
        s = _層の文字列[層]
        for _ in range(回数):
            if 片:
                片.append(戻る)
            片.append(s)
    return "".join(片)


def 幅(文字列):
    """Ширина строки по шагам глифов (как считает клиент) — для тестов."""
    w = 0
    for c in 文字列:
        if c == 負一:
            w -= 1
        elif c == 戻る:
            w -= 全幅
        elif c == 暗:
            w += 全幅
        else:
            w += マス送り
    return w


# ---------------------------------------------------------------------------------------------------------------
# Шаг задачи: тики, через которые «переходит» задача с периодом 刻み (вместо проверок now % N == 0 каждый тик)
# ---------------------------------------------------------------------------------------------------------------

def 跨いだ(今, 刻み, 間隔):
    """Было ли кратное 間隔 среди тиков (今 − 刻み, 今] — то же, что «now % 間隔 == 0» на одном из них."""
    if 間隔 <= 0:
        return False
    return 今 // 間隔 != (今 - 刻み) // 間隔


def 跨いだ回数(今, 刻み, 間隔):
    """Сколько кратных 間隔 среди тиков (今 − 刻み, 今] (для частиц с интервалом меньше шага)."""
    if 間隔 <= 0:
        return 0
    return 今 // 間隔 - (今 - 刻み) // 間隔
