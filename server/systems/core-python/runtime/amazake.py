"""
amazake — API Python-систем сервера (core-python, GraalPy).

    import amazake
    from amazake import on, command, log

Каждая Python-система (папка scripts/<имя>/ с manifest.yml) живёт в своём контексте: свои модули, свои
глобальные переменные. Всё, что зарегистрировано через этот модуль (слушатели, команды, задачи, временные
атрибуты и эффекты игроков), снимается само при `/python unload` / `reload` и при выключении core-python.

ПОТОКИ (как у систем Amazake — сервер многопоточный, регионы как в Folia):
  * обработчик события вызывается в потоке, где случилось событие (обычно — регион игрока/сущности);
  * команда — в потоке отправителя (игрок — его регион, консоль — глобальный поток);
  * игрока трогаем только в его потоке: run_for_player / run_once_for_player;
  * блоки и сущности — в потоке их региона: run_in_region(место, fn);
  * мир целиком (время, погода) — run_global; файлы и сеть — run_async;
  * верхний уровень модуля и @on_load выполняются в асинхронном потоке загрузки — там мир не трогаем,
    только объявляем обработчики и читаем конфиги.

ПРОИЗВОДИТЕЛЬНОСТЬ: на обычном JDK GraalPy работает без JIT (интерпретатор), простой цикл Python медленнее
CPython примерно в 5–15 раз; на каждый контекст — один GIL, т.е. обработчики одной системы из разных регионов
выполняются по очереди. Обработчики должны быть короткими; частые события (PlayerMoveEvent и т.п.) лучше
не слушать, а опрашивать игроков задачей раз в 10–20 тиков. Не вызывайте из Python блокирующие методы Java
(ожидание future, синхронная загрузка чанка из чужого региона) — пока поток ждёт, он держит GIL системы.

Java доступна напрямую:  import java;  Material = java.type("org.bukkit.Material")
(или amazake.java_class("…") — ищет и в плагинах).
"""

import json as _json
import sys as _sys
import polyglot as _polyglot
import java as _java

_host = _polyglot.import_value("amazake_host")


def _u(x):
    """Обёртка 包む(…) → Java-объект; остальное как есть. Все функции модуля разворачивают свои аргументы."""
    return x._生 if isinstance(x, 包み) else x


# ---------------------------------------------------------------------------------------------
# Сведения о системе
# ---------------------------------------------------------------------------------------------

#: Имя системы (= папка в scripts/).
name = _host.name()
#: Версия из manifest.yml.
version = _host.version()
#: Папка с кодом системы (scripts/<имя>/).
script_folder = _host.scriptFolder()


def data_folder():
    """Папка данных системы: systems/core-python/data/<имя>/ (создаётся при первом вызове)."""
    return _host.dataFolder()


# ---------------------------------------------------------------------------------------------
# Лог (и print → лог)
# ---------------------------------------------------------------------------------------------

class _Log:
    """Лог системы: строки в консоли сервера с префиксом [py:<имя>]."""

    def info(self, *parts):
        _host.log("info", " ".join(str(p) for p in parts))

    def warn(self, *parts):
        _host.log("warn", " ".join(str(p) for p in parts))

    def error(self, *parts):
        _host.log("error", " ".join(str(p) for p in parts))

    def debug(self, *parts):
        _host.log("debug", " ".join(str(p) for p in parts))


#: amazake.log.info("…"), .warn, .error, .debug
log = _Log()


class _LogWriter:
    def __init__(self, level):
        self._level = level
        self._buf = ""

    def write(self, s):
        self._buf += str(s)
        while "\n" in self._buf:
            line, self._buf = self._buf.split("\n", 1)
            if line.strip():
                _host.log(self._level, line)
        return len(s)

    def flush(self):
        if self._buf.strip():
            _host.log(self._level, self._buf)
        self._buf = ""


_sys.stdout = _LogWriter("info")
_sys.stderr = _LogWriter("warn")

# ---------------------------------------------------------------------------------------------
# Java
# ---------------------------------------------------------------------------------------------


def java_class(name):
    """Класс Java по полному имени — сервер (Bukkit/Paper), затем плагины. Как java.type, но видит плагины.

        Material = amazake.java_class("org.bukkit.Material")
        Material.DIAMOND
    """
    try:
        return _java.type(name)
    except Exception:
        return _static(_host.findClass(name))


def plugin_class(plugin, name):
    """Класс из API плагина (или None, если плагина нет / он выключен).

        NexoItems = amazake.plugin_class("Nexo", "com.nexomc.nexo.api.NexoItems")
        if NexoItems: NexoItems.itemFromId("azerus_guren_chestplate")
    """
    c = _host.pluginClass(plugin, name)
    return None if c is None else _static(c)


def _static(cls):
    # java.lang.Class → «статическая» сторона класса (Class.static), чтобы звать статические методы
    try:
        return getattr(cls, "static")
    except Exception:
        return cls


def find_class(name):
    """Объект java.lang.Class по полному имени — сервер, затем плагины (для getMethod, world.spawn(…, cls), isInstance).
    В отличие от java_class — сам Class, а не его статическая сторона.

        BlockDisplay = amazake.find_class("org.bukkit.entity.BlockDisplay")
        world.spawn(место, BlockDisplay, лямбда)
    """
    return _host.findClass(name)


def plugin():
    """Плагин (org.bukkit.plugin.Plugin), от имени которого работает core-python: для API Bukkit, которым нужен
    Plugin, — player.showEntity(amazake.plugin(), e), entity.getScheduler().execute(amazake.plugin(), …) и т.п.
    Задачи через amazake.run_* предпочтительнее: их хост отменит сам при выгрузке."""
    return _host.plugin()


Bukkit = _java.type("org.bukkit.Bukkit")

# ---------------------------------------------------------------------------------------------
# Java-задачи, которые переживают выгрузку (чистая Java, без Python)
# ---------------------------------------------------------------------------------------------
# run_* выше отменяются при выгрузке, а контекст Python закрывается сразу после @on_unload. То, что надо доделать в
# чужом потоке уже после этого (снять кадр с предмета игрока, удалить сущность в другом регионе, записать PDC), —
# через эти функции: задача — Java-Runnable (amazake.java_task(...) или любой Java-объект Runnable), функция Python
# не принимается. Звать можно из любого потока, в том числе из @on_unload. Задачи НЕ отменяются выгрузкой системы;
# пока сервер останавливается (планировщики уже не работают), они выполняются сразу. Ошибка задачи — в лог системы.


# Исключения Java приходят в Python как polyglot.ForeignException — это BaseException, но НЕ Exception:
# `except Exception` их не ловит. Функции ниже переводят ошибки проверки хоста в обычные ValueError/TypeError.
_ForeignException = getattr(_polyglot, "ForeignException", None)
_ForeignObject = getattr(_polyglot, "ForeignObject", None)


def _checked(fn, *args):
    if _ForeignException is None:
        return fn(*args)
    try:
        return fn(*args)
    except _ForeignException as e:
        try:
            text = str(e.getMessage())
        except BaseException:
            text = str(e)
        raise ValueError(text) from None


def _java_runnable(task, where):
    if task is None or (_ForeignObject is not None and not isinstance(task, _ForeignObject)):
        raise TypeError(where + ": нужен Java Runnable (amazake.java_task(...)), а не " + type(task).__name__
                        + " — задача может выполниться после выгрузки системы, когда Python уже закрыт")
    return task


def java_task(target, method, *args, type=None):
    """Java-Runnable без Python: при run() вызывает target.method(*args). Метод (публичный) выбирается и аргументы
    переводятся в Java сразу, поэтому задача работает и после выгрузки системы.
    args — объекты Java, str, int, float, bool, None (списки/dict Python — нельзя: они умрут вместе с контекстом).
    int → int/long/…, float (и 2.0) → только double/float (в Object — Double). Равные перегрузки — самая
    специфичная, как в javac; если её нет — ValueError: задайте type или передайте Java-объект нужного типа.
    type — класс/интерфейс (имя, java.type или Class), где искать метод.

        @amazake.on_unload
        def 片付け():
            for p, 鍵 in 印.items():   # игроки в разных регионах — каждому в его потоке
                pdc = p.getPersistentDataContainer()
                amazake.run_java_for_player(p, amazake.java_task(pdc, "remove", 鍵))
            for e in 影:
                amazake.remove_entity(e)
    """
    # float отдельно: 2.0 по значению на стороне Java не отличить от 2 (иначе выбралась бы перегрузка int).
    args = [_u(a) for a in args]
    return _checked(_host.javaTask, _u(target), method, args, [isinstance(a, float) for a in args], _u(type))


def run_java_for_player(player, task):
    """Java-Runnable один раз в потоке игрока (как SystemContext.runOnceForPlayer у Java-систем): через тик; пока
    core-python выключается и поток владеет игроком (или сервер останавливается) — сразу. False — игрок ушёл."""
    return bool(_checked(_host.runJavaForPlayer, _u(player), _java_runnable(_u(task), "run_java_for_player")))


def run_java_on_entity(entity, task, delay=0):
    """Java-Runnable в потоке сущности через delay тиков; delay=0 и поток уже владеет ею — сразу.
    При остановке сервера: владелец — сразу (delay не ждём), иначе планировщик, а если он не принял — сразу.
    False — сущность удалена / планировщик отказал."""
    return bool(_checked(_host.runJavaOnEntity, _u(entity), _java_runnable(_u(task), "run_java_on_entity"), int(delay)))


def run_java_global(task, delay=0):
    """Java-Runnable в глобальном потоке через delay тиков; delay=0 и мы в глобальном потоке — сразу.
    При остановке сервера: в глобальном потоке — сразу, иначе планировщик, а если он не принял — сразу."""
    return bool(_checked(_host.runJavaGlobal, _java_runnable(_u(task), "run_java_global"), int(delay)))


def remove_entity(entity):
    """Удалить сущность (не игрока) из любого потока: сразу, если поток ею владеет, иначе — в её потоке."""
    return bool(_checked(_host.removeEntity, _u(entity)))


# ---------------------------------------------------------------------------------------------
# События
# ---------------------------------------------------------------------------------------------

_PRIORITIES = ("lowest", "low", "normal", "high", "highest", "monitor")


def on(event, priority="normal", ignore_cancelled=False):
    """Декоратор: функция вызывается на событие Bukkit/Paper — в потоке события.

        @amazake.on("PlayerJoinEvent")               # короткое имя (ищется в пакетах Bukkit/Paper)
        def 参加(event):
            amazake.send(event.getPlayer(), "<green>Привет!")

        @amazake.on(java.type("org.bukkit.event.entity.EntityDamageByEntityEvent"), priority="high",
                    ignore_cancelled=True)
        def 攻撃(event): ...

    event — класс Java (java.type), его полное или короткое имя.
    priority — lowest | low | normal | high | highest | monitor.
    """
    if priority not in _PRIORITIES:
        raise ValueError("priority: " + " | ".join(_PRIORITIES))

    def deco(fn):
        _host.listen(_u(event), priority, bool(ignore_cancelled), fn)
        return fn

    return deco


def on_load(fn):
    """Декоратор: вызвать после загрузки всего модуля (асинхронный поток; мир не трогать — только планировать)."""
    _host.onLoad(fn)
    return fn


def on_unload(fn):
    """Декоратор: вызвать при выгрузке (до закрытия контекста). Атрибуты/эффекты из API снимаются сами."""
    _host.onUnload(fn)
    return fn


# ---------------------------------------------------------------------------------------------
# Команды
# ---------------------------------------------------------------------------------------------

class Command:
    """Команда, созданная @amazake.command. Автодополнение — декоратором @cmd.tab."""

    def __init__(self, handle):
        self._handle = handle

    def tab(self, fn):
        """Декоратор: fn(sender, args) -> список вариантов для последнего аргумента (фильтр по началу — сам)."""

        def wrapper(sender, args):
            r = fn(sender, list(args))
            return list(r) if r else []

        self._handle.setCompleter(wrapper)
        return fn


def command(name, permission=None, aliases=None, description=None, usage=None):
    """Декоратор: команда /name. fn(sender, args) — args: список строк. Выполняется в потоке отправителя.

    Свойства по умолчанию берутся из manifest.yml (commands.<name>): permission, aliases, description, usage.

        @amazake.command("hello", permission="myscript.use", aliases=["привет"])
        def こんにちは(sender, args):
            amazake.send(sender, "<gold>Привет, <name>!", name=sender.getName())

        @こんにちは.tab
        def 補完(sender, args):
            return ["a", "b"]

    Команда занята плагином → доступна как /py-<система>:<name>.
    """
    spec = _host.manifestCommand(name)
    if spec is not None:
        permission = permission if permission is not None else spec[0]
        aliases = aliases if aliases is not None else list(spec[1])
        description = description if description is not None else spec[2]
        usage = usage if usage is not None else spec[3]

    def deco(fn):
        def run(sender, label, args):
            fn(sender, list(args))

        handle = _host.command(name, permission, list(aliases or []), description or "", usage or ("/" + name), run)
        c = Command(handle)
        fn.tab = c.tab
        fn.command = c
        return fn

    return deco


# ---------------------------------------------------------------------------------------------
# Потоки и задачи
# ---------------------------------------------------------------------------------------------
# Все run_* возвращают задачу: task.cancel(), task.isCancelled(). Повторяющуюся задачу можно остановить,
# вернув из функции False. При выгрузке системы все задачи отменяются сами.


def run_for_player(player, fn, period=20):
    """Повторять fn() в потоке игрока каждые period тиков (первый раз — через тик). Сама отменяется при выходе."""
    return _host.runForPlayer(_u(player), fn, int(period))


def run_once_for_player(player, fn, delay=1):
    """Один раз fn() в потоке игрока через delay тиков (минимум 1)."""
    return _host.runOnceForPlayer(_u(player), fn, int(delay))


def run_in_region(where, fn, delay=0, period=0):
    """fn() в потоке региона места/сущности/блока; delay/period — в тиках (period > 0 — повторять)."""
    return _host.runInRegion(_u(where), fn, int(delay), int(period))


def run_global(fn, delay=0, period=0):
    """fn() в глобальном потоке (время, погода, мир целиком); delay/period — в тиках."""
    return _host.runGlobal(fn, int(delay), int(period))


def run_async(fn, delay_ms=0, period_ms=0):
    """fn() вне тиков (файлы, сеть, тяжёлые расчёты); delay_ms/period_ms — в миллисекундах."""
    return _host.runAsync(fn, int(delay_ms), int(period_ms))


def owns(thing):
    """Владеет ли текущий поток игроком/сущностью/местом/блоком (можно ли трогать его прямо сейчас)."""
    return _host.owns(_u(thing))


# ---------------------------------------------------------------------------------------------
# Конфиг и файлы
# ---------------------------------------------------------------------------------------------


def config(file="config.yml", defaults=None):
    """YAML из папки системы → dict (ключи — строки). Нет файла → defaults (или {}).

        設定 = amazake.config("sets.yml")
    """
    raw = _host.readConfig(file)
    if raw is None:
        return dict(defaults or {})
    data = _json.loads(str(raw))
    if defaults:
        merged = dict(defaults)
        merged.update(data)
        return merged
    return data


class Storage:
    """JSON-файл в папке данных системы. save() пишет атомарно (временный файл + rename).

        倉庫 = amazake.storage("top.json")
        データ = 倉庫.load({})
        倉庫.save_async(データ)       # из тиков — только async
    """

    def __init__(self, file):
        self.file = file

    def load(self, default=None):
        raw = _host.readText(self.file)
        return default if raw is None else _json.loads(str(raw))

    def save(self, obj):
        _host.writeText(self.file, _json.dumps(obj, ensure_ascii=False, indent=2))

    def save_async(self, obj):
        text = _json.dumps(obj, ensure_ascii=False, indent=2)
        return run_async(lambda: _host.writeText(self.file, text))


def storage(file):
    """Storage для файла data/<система>/<file>."""
    return Storage(file)


# ---------------------------------------------------------------------------------------------
# PDC: данные на игроках, сущностях, предметах
# ---------------------------------------------------------------------------------------------

def key(k):
    """NamespacedKey: "имя" → py_<система>:имя, "ns:имя" → как есть (например "nexo:id")."""
    return _host.key(k)


class Data:
    """Данные в PersistentDataContainer (значения — любые JSON: числа, строки, списки, dict).

    Ключ без двоеточия — в пространстве py_<система>. Игрок/сущность — только в их потоке.
    Для ItemStack изменения пишутся прямо в этот ItemStack (положите его обратно в инвентарь).
    """

    def __init__(self, holder):
        self.holder = _u(holder)

    def get(self, k, default=None):
        raw = _host.pdcGet(self.holder, k)
        if raw is None:
            return default
        try:
            return _json.loads(str(raw))
        except ValueError:
            return str(raw)

    def get_string(self, k, default=None):
        """Сырая строка (для чужих ключей вроде "nexo:id")."""
        raw = _host.pdcGet(self.holder, k)
        return default if raw is None else str(raw)

    def set(self, k, value):
        _host.pdcSet(self.holder, k, _json.dumps(value, ensure_ascii=False))

    def set_string(self, k, value):
        _host.pdcSet(self.holder, k, str(value))

    def has(self, k):
        return _host.pdcHas(self.holder, k)

    def remove(self, k):
        _host.pdcRemove(self.holder, k)


def data(holder):
    """Data(holder) — PDC игрока/сущности/блока-сущности/предмета."""
    return Data(holder)


# ---------------------------------------------------------------------------------------------
# Атрибуты и эффекты игроков (снимаются сами при выгрузке)
# ---------------------------------------------------------------------------------------------

def attribute(name):
    """Атрибут по имени: "armor", "max_health", "movement_speed", "attack_damage", …"""
    return _host.attribute(_属性名(name))


def modifier(k, amount, operation="add", slot="any"):
    """AttributeModifier для предметов: operation = add | multiply_base | multiply;
    slot = any | head | chest | legs | feet | armor | mainhand | offhand | hand."""
    return _host.modifier(k, float(amount), _演算名(operation), _部位名(slot))


def add_modifier(player, attr, k, amount, operation="add"):
    """Временный модификатор атрибута игрока (не сохраняется в файл игрока; снимается при выгрузке).
    Повторный вызов с тем же ключом заменяет значение. Только в потоке игрока."""
    _host.addPlayerModifier(_u(player), _属性名(attr), k, float(amount), _演算名(operation))


def remove_modifier(player, attr, k):
    """Снять модификатор, добавленный add_modifier. Только в потоке игрока."""
    return _host.removePlayerModifier(_u(player), _属性名(attr), k)


def effect_type(name):
    """PotionEffectType по имени ("slowness", "minecraft:speed", …)."""
    return _host.effectType(_効果名(name))


def add_effect(player, effect, duration=100, amplifier=0, ambient=True, particles=False, icon=True):
    """Эффект зелья от системы (снимается при выгрузке, если на игроке всё ещё он). Только в потоке игрока.
    effect — "speed", "regeneration", "fire_resistance", …; duration в тиках (-1 — бесконечно)."""
    _host.addPlayerEffect(_u(player), _効果名(effect), int(duration), int(amplifier), bool(ambient), bool(particles), bool(icon))


def remove_effect(player, effect):
    """Снять эффект, если он наш. Только в потоке игрока."""
    return _host.removePlayerEffect(_u(player), _効果名(effect))


# ---------------------------------------------------------------------------------------------
# Текст (MiniMessage)
# ---------------------------------------------------------------------------------------------

def mm(text, **placeholders):
    """MiniMessage → Component. Плейсхолдеры: mm("<gold>Привет, <name>", name="Rezus") (значения не парсятся)."""
    return _host.mm(text, _値表(placeholders))


def item_text(text, **placeholders):
    """Как mm, но без курсива (для названий и описаний предметов)."""
    return _host.itemText(text, _値表(placeholders))


def send(target, text, **placeholders):
    """Сообщение игроку/консоли в MiniMessage."""
    _u(target).sendMessage(_host.mm(text, _値表(placeholders)))


def actionbar(player, text, **placeholders):
    """Строка над хотбаром."""
    _u(player).sendActionBar(_host.mm(text, _値表(placeholders)))


# =============================================================================================
# 日本語 — японские имена API и обёртки Bukkit/Paper (docs/japanese-mode-vocabulary.md §4–§5)
# =============================================================================================
# Доступны всегда (и из обычных систем). Японские функции разворачивают обёртки во всех аргументах; японские
# декораторы событий/команд передают обработчику обёрнутые объекты (包み). Тексты ошибок — по-английски.

import re as _re

# ---------------------------------------------------------------------------------------------
# Японские имена значений (§4.5–§4.7): строки → английские имена Bukkit
# ---------------------------------------------------------------------------------------------

#: §4.5 События для @イベント時.
イベント名一覧 = {
    "プレイヤー参加": "PlayerJoinEvent", "プレイヤー退出": "PlayerQuitEvent", "アイテム消費": "PlayerItemConsumeEvent",
    "プレイヤー操作": "PlayerInteractEvent", "エンティティ操作": "PlayerInteractEntityEvent",
    "ダメージ": "EntityDamageEvent", "攻撃": "EntityDamageByEntityEvent", "エンティティ死亡": "EntityDeathEvent",
    "プレイヤー死亡": "PlayerDeathEvent", "リスポーン": "PlayerRespawnEvent", "ブロック破壊": "BlockBreakEvent",
    "ブロック設置": "BlockPlaceEvent", "体力回復": "EntityRegainHealthEvent",
    "チャット": "io.papermc.paper.event.player.AsyncChatEvent",
    "アイテムドロップ": "PlayerDropItemEvent", "アイテム拾い": "EntityPickupItemEvent", "持ち替え": "PlayerItemHeldEvent",
    "手の入れ替え": "PlayerSwapHandItemsEvent", "スニーク": "PlayerToggleSneakEvent", "ダッシュ": "PlayerToggleSprintEvent",
    "インベントリクリック": "InventoryClickEvent", "インベントリを閉じる": "InventoryCloseEvent",
    "発射体命中": "ProjectileHitEvent", "弓を射る": "EntityShootBowEvent", "テレポート": "PlayerTeleportEvent",
    "ワールド移動": "PlayerChangedWorldEvent", "満腹度変化": "FoodLevelChangeEvent", "移動": "PlayerMoveEvent",
}

#: Приоритеты @イベント時.
優先度一覧 = {"最低": "lowest", "低": "low", "通常": "normal", "高": "high", "最高": "highest", "監視": "monitor"}

#: §4.6 Атрибуты.
属性名一覧 = {
    "最大体力": "max_health", "防御力": "armor", "防具強度": "armor_toughness", "攻撃力": "attack_damage",
    "攻撃速度": "attack_speed", "攻撃ノックバック": "attack_knockback", "ノックバック耐性": "knockback_resistance",
    "移動速度": "movement_speed", "段差の高さ": "step_height", "幸運": "luck", "大きさ": "scale",
    "跳躍力": "jump_strength", "重力": "gravity", "安全落下距離": "safe_fall_distance",
    "ブロック到達距離": "block_interaction_range", "エンティティ到達距離": "entity_interaction_range",
    "最大衝撃吸収": "max_absorption",
}

#: §4.6 Операции и слоты модификаторов.
演算名一覧 = {"加算": "add", "基礎乗算": "multiply_base", "乗算": "multiply"}
部位名一覧 = {"全て": "any", "頭": "head", "胴": "chest", "脚": "legs", "足": "feet", "防具": "armor", "利き手": "mainhand",
          "オフハンド": "offhand", "手": "hand"}

#: §4.7 Эффекты (официальные названия ja_jp).
効果名一覧 = {
    "移動速度上昇": "speed", "移動速度低下": "slowness", "採掘速度上昇": "haste", "採掘速度低下": "mining_fatigue",
    "攻撃力上昇": "strength", "即時回復": "instant_health", "即時ダメージ": "instant_damage", "跳躍力上昇": "jump_boost",
    "吐き気": "nausea", "再生能力": "regeneration", "耐性": "resistance", "火炎耐性": "fire_resistance",
    "水中呼吸": "water_breathing", "幸運": "luck", "暗闇": "darkness", "村の英雄": "hero_of_the_village",
    "コンジットパワー": "conduit_power", "透明化": "invisibility", "盲目": "blindness", "暗視": "night_vision",
    "空腹": "hunger", "弱体化": "weakness", "毒": "poison", "衰弱": "wither", "体力増強": "health_boost",
    "衝撃吸収": "absorption", "満腹度回復": "saturation", "発光": "glowing", "浮遊": "levitation",
    "落下速度低下": "slow_falling", "不運": "unluck", "不吉な予感": "bad_omen", "イルカの好意": "dolphins_grace",
}


def _属性名(n):
    return 属性名一覧.get(n, n) if isinstance(n, str) else n


def _演算名(n):
    return 演算名一覧.get(n, n) if isinstance(n, str) else n


def _部位名(n):
    return 部位名一覧.get(n, n) if isinstance(n, str) else n


def _効果名(n):
    if isinstance(n, str):
        return 効果名一覧.get(n, n)
    n = _u(n)
    try:
        return str(n.getKey().toString())   # PotionEffectType → "minecraft:speed"
    except Exception:
        return n


def _値表(ph):
    """Значения плейсхолдеров: обёртки → Java-объекты (Component и т.п.)."""
    for k, v in ph.items():
        if isinstance(v, 包み):
            ph[k] = v._生
    return ph


_TAG = _re.compile(r"[a-z0-9_-]+")


def _置換(文, 置換, 他):
    """Плейсхолдеры японских функций: 置換={…} и именованные аргументы с любыми именами (японские → безопасный тег)."""
    ph = {}
    n = 0
    for src in (置換, 他):
        if not src:
            continue
        for k, v in src.items():
            k = str(k)
            if not _TAG.fullmatch(k):
                tag = "jph" + str(n)
                n += 1
                文 = 文.replace("<" + k + ">", "<" + tag + ">")
                k = tag
            ph[k] = _u(v)
    return 文, ph


# ---------------------------------------------------------------------------------------------
# 包む — обёртка Java-объекта с японскими атрибутами (§5)
# ---------------------------------------------------------------------------------------------

_ForeignExecutable = getattr(_polyglot, "ForeignExecutable", None)
_ForeignList = getattr(_polyglot, "ForeignList", None)
_PROP = 0
_METH = 1


def _P(get, set=None):
    return (_PROP, get, set)


def _M(fn):
    return (_METH, fn)


class 包み:
    """Java-объект Bukkit/Paper с японскими атрибутами (таблицы §5). Чего нет в таблицах — атрибут самого Java-объекта:
    методы зовутся с развёрнутыми аргументами и возвращают сырой результат. 甘酒.解く(x) — сам Java-объект.

    Скорость: при первом обращении обёртка меняет свой класс на подкласс для этого Java-класса (один раз на класс),
    где японские имена — обычные property/методы Python; дальше — без отражения и без поиска по таблицам."""

    # Без __slots__: GraalPy меняет __class__ только у объектов с __dict__.
    #: японское имя → запись таблицы (у подклассов); у базового класса — ещё не определено.
    _属性 = None

    def __init__(self, 生):
        object.__setattr__(self, "_生", 生)

    def __getattr__(self, 名):
        # Сюда попадаем, только если имени нет у класса: базовый класс → определить вид и повторить.
        if type(self)._属性 is None and 名[:2] != "__":
            object.__setattr__(self, "__class__", _専用クラス(self._生))
            if 名 in type(self)._属性:
                return getattr(self, 名)
        if 名[:2] == "__":
            raise AttributeError(名)
        v = getattr(self._生, 名)
        if v.__class__ is _ForeignExecutable:
            def 生の方法(*a):
                try:
                    return v(*[_u(x) for x in a])
                except _FE as ex:
                    raise _ジャバエラー(ex) from None

            return 生の方法
        return v

    def __setattr__(self, 名, 値):
        t = type(self)._属性
        if t is None:
            object.__setattr__(self, "__class__", _専用クラス(self._生))
            t = type(self)._属性
        e = t.get(名)
        if e is None:
            setattr(self._生, 名, _u(値))
        elif e[0] == _PROP and e[2] is not None:
            try:
                e[2](self._生, 値)
            except _FE as ex:
                raise _ジャバエラー(ex) from None
        else:
            raise AttributeError("'%s' is read-only on %s" % (名, _単純名(self._生)))

    def __eq__(self, other):
        o = _u(other)
        return self._生 is o or (isinstance(o, _ForeignObject) and bool(self._生.equals(o)))

    def __ne__(self, other):
        return not self.__eq__(other)

    def __hash__(self):
        return int(self._生.hashCode())

    def __str__(self):
        return str(self._生)

    def __repr__(self):
        return "包み(" + str(self._生) + ")"

    def __bool__(self):
        return True


def _特性(g, st):
    def 読む(self):
        try:
            return g(self._生)
        except _FE as ex:
            raise _ジャバエラー(ex) from None

    if st is None:
        return property(読む)

    def 書く(self, v):
        try:
            st(self._生, v)
        except _FE as ex:
            raise _ジャバエラー(ex) from None

    return property(読む, 書く)


def _方法(f):
    def 方法(self, *a, **k):
        try:
            return f(self._生, *a, **k)
        except _FE as ex:
            raise _ジャバエラー(ex) from None

    return 方法


def _クラス化(表, 名="包み"):
    """Таблица японских атрибутов → подкласс 包み с property/методами (ничего не ищется при обращении)."""
    ns = {"_属性": 表}
    for k, e in 表.items():
        ns[k] = _特性(e[1], e[2]) if e[0] == _PROP else _方法(e[1])
    return type(名, (包み,), ns)


_専用キャッシュ = {}


_汎用 = None


def _専用クラス(生, 空なら=False):
    """Подкласс 包み для Java-класса объекта (создаётся один раз на класс). Нет японской таблицы: 空なら=True → None,
    иначе подкласс без японских имён (всё — атрибуты Java)."""
    global _汎用
    try:
        key = 生.getClass().getName()
    except BaseException:
        key = None
    c = _専用キャッシュ.get(key) if key is not None else None
    if c is None:
        t = _表を得る(生) if key is not None else _空表
        c = _クラス化(t) if t else False
        if key is not None:
            _専用キャッシュ[key] = c
    if c is False:
        if 空なら:
            return None
        if _汎用 is None:
            _汎用 = _クラス化(_空表)
        return _汎用
    return c


def 包む(物):
    """Java-объект → 包み (японские атрибуты). None, объекты Python, уже обёрнутые и объекты Java без японской таблицы
    (коллекции, массивы, Map, прочие классы) — как есть: их длина, перебор, индексы и истинность работают как у Java.
    Класс обёртки выбирается сразу (один вызов getClass на объект; таблица — один раз на класс Java)."""
    if 物 is None or isinstance(物, 包み) or _ForeignObject is None or not isinstance(物, _ForeignObject):
        return 物
    c = _専用クラス(物, True)
    if c is None:
        return 物
    w = object.__new__(c)
    object.__setattr__(w, "_生", 物)
    return w


def 解く(物):
    """包み → Java-объект (для остального — как есть)."""
    return 物._生 if isinstance(物, 包み) else 物


def _包む一覧(c):
    return [] if c is None else [包む(x) for x in c]


def _単純名(生):
    try:
        return str(生.getClass().getSimpleName())
    except BaseException:
        return type(生).__name__


class JavaError(BaseException):
    """Ошибка Java, брошенная из японских функций amazake, обёрток 包み и 呼ぶ. GraalPy не строит трассу Python для
    исключений Java (в логе была бы только первая строка функции), а это исключение Python — его трасса указывает на
    точную строку исходного (японского) файла. Как и polyglot.ForeignException — BaseException, но НЕ Exception:
    `捕まえる 例外` его не ловит; ловить — `捕まえる ジャバ例外` (= (ForeignException, JavaError)). .java — само
    исключение Java."""

    java = None


_FE = _ForeignException or ()
#: Встроенное японского режима ジャバ例外: и сырое исключение Java, и JavaError.
ジャバ例外 = tuple(c for c in (_ForeignException, JavaError) if c is not None)


def _java_text(e):
    try:
        return str(e.toString())
    except BaseException:
        return str(e)


def _ジャバエラー(e):
    err = JavaError(_java_text(e))
    err.java = e
    return err


def _守る(f):
    """Декоратор японских функций: ошибка Java → JavaError (трасса Python с точной строкой вызывающего кода)."""
    def g(*a, **k):
        try:
            return f(*a, **k)
        except _FE as e:
            raise _ジャバエラー(e) from None

    g.__name__ = f.__name__
    g.__qualname__ = f.__qualname__
    g.__doc__ = f.__doc__
    g.__wrapped__ = f
    return g


# --- ленивые классы Java ---------------------------------------------------------------------

_クラス = {}


def _型(名):
    c = _クラス.get(名)
    if c is None:
        try:
            c = _java.type(名)
        except BaseException:
            c = _static(_host.findClass(名))
        _クラス[名] = c
    return c


def _文(v):
    """str → Component (MiniMessage); обёртка → Java; Component — как есть."""
    return _host.mm(v, {}) if isinstance(v, str) else _u(v)


def _アイテム文(v):
    return _host.itemText(v, {}) if isinstance(v, str) else _u(v)


def _素材値(v):
    if isinstance(v, str):
        m = 素材(v)
        if m is None:
            raise ValueError("unknown material %r" % v)
        return m
    return _u(v)


def _効果値(v):
    return effect_type(v) if isinstance(v, str) else _u(v)


_最大体力 = []


def _最大体力属性():
    if not _最大体力:
        _最大体力.append(_host.attribute("max_health"))
    return _最大体力[0]


# --- таблицы видов -----------------------------------------------------------------------------

_送り主表 = {
    "名前": _P(lambda o: o.getName()),
    "送る": _M(lambda o, 文, 置換=None, **他: 送る(o, 文, 置換, **他)),
    "アクションバー": _M(lambda o, 文, 置換=None, **他: アクションバー(o, 文, 置換, **他)),
    "権限あり": _M(lambda o, 権限: bool(o.hasPermission(権限))),
    "管理者か": _P(lambda o: bool(o.isOp())),
}

_実体表 = {
    "名前": _P(lambda o: o.getName()),
    "識別子": _P(lambda o: o.getUniqueId()),
    "種類": _P(lambda o: o.getType()),
    "位置": _P(lambda o: 包む(o.getLocation())),
    "世界": _P(lambda o: 包む(o.getWorld())),
    "速度": _P(lambda o: o.getVelocity(), lambda o, v: o.setVelocity(_u(v))),
    "炎上時間": _P(lambda o: o.getFireTicks(), lambda o, v: o.setFireTicks(int(v))),
    "発光中": _P(lambda o: bool(o.isGlowing()), lambda o, v: o.setGlowing(bool(v))),
    "地上か": _P(lambda o: bool(o.isOnGround())),
    "有効か": _P(lambda o: bool(o.isValid())),
    "死亡か": _P(lambda o: bool(o.isDead())),
    "名札": _P(lambda o: o.customName(), lambda o, v: o.customName(None if v is None else _文(v))),
    "瞬間移動": _M(lambda o, 場所: o.teleportAsync(_u(場所))),
    "近くのエンティティ": _M(lambda o, 東西, 高さ=None, 南北=None: _包む一覧(o.getNearbyEntities(
        float(東西), float(東西 if 高さ is None else 高さ), float(東西 if 南北 is None else 南北)))),
    "消す": _M(lambda o: o.remove()),
    "永続データ": _P(lambda o: Data(o)),
}


def _効果付与(o, 効果, 時間, 強さ=0, 環境=False, 粒子=True, アイコン=True):
    """Постоянный (ванильный) эффект; значения по умолчанию — как у конструктора PotionEffect(type, dur, amp)."""
    return o.addPotionEffect(_型("org.bukkit.potion.PotionEffect")(
        _効果値(効果), int(時間), int(強さ), bool(環境), bool(粒子), bool(アイコン)))


_生物表 = {
    "体力": _P(lambda o: o.getHealth(), lambda o, v: o.setHealth(float(v))),
    "最大体力": _P(lambda o: o.getAttribute(_最大体力属性()).getValue()),
    "属性": _M(lambda o, 名前: 包む(o.getAttribute(attribute(名前)))),
    "効果付与": _M(_効果付与),
    "効果解除": _M(lambda o, 効果: o.removePotionEffect(_効果値(効果))),
    "効果あり": _M(lambda o, 効果: bool(o.hasPotionEffect(_効果値(効果)))),
    "効果一覧": _P(lambda o: _包む一覧(o.getActivePotionEffects())),
    "ダメージを与える": _M(lambda o, 量, 源=None: o.damage(float(量)) if 源 is None else o.damage(float(量), _u(源))),
    "目の位置": _P(lambda o: 包む(o.getEyeLocation())),
    "装備": _P(lambda o: o.getEquipment()),
}


def _渡す(o, *アイテム):
    rest = o.getInventory().addItem(*[_u(i) for i in アイテム])
    return _包む一覧(rest.values())


def _音を鳴らす(o, 音, 音量=1, 高さ=1):
    return o.playSound(o.getLocation(), _音値(音), float(音量), float(高さ))


_プレイヤー表 = {
    "インベントリ": _P(lambda o: 包む(o.getInventory())),
    "手のアイテム": _P(lambda o: 包む(o.getInventory().getItemInMainHand()),
                  lambda o, v: o.getInventory().setItemInMainHand(_u(v))),
    "左手のアイテム": _P(lambda o: 包む(o.getInventory().getItemInOffHand()),
                   lambda o, v: o.getInventory().setItemInOffHand(_u(v))),
    "渡す": _M(_渡す),
    "ゲームモード": _P(lambda o: o.getGameMode(), lambda o, v: o.setGameMode(_u(v))),
    "満腹度": _P(lambda o: o.getFoodLevel(), lambda o, v: o.setFoodLevel(int(v))),
    "レベル": _P(lambda o: o.getLevel(), lambda o, v: o.setLevel(int(v))),
    "飛行中": _P(lambda o: bool(o.isFlying())),
    "スニーク中": _P(lambda o: bool(o.isSneaking())),
    "ダッシュ中": _P(lambda o: bool(o.isSprinting())),
    "オンラインか": _P(lambda o: bool(o.isOnline())),
    "表示名": _P(lambda o: o.displayName(), lambda o, v: o.displayName(None if v is None else _文(v))),
    "タイトル表示": _M(lambda o, 題, 副題="": o.showTitle(_型("net.kyori.adventure.title.Title").title(_文(題), _文(副題)))),
    "音を鳴らす": _M(_音を鳴らす),
    "追い出す": _M(lambda o, 理由=None: o.kick() if 理由 is None else o.kick(_文(理由))),
}


def _アイテム表示名(o):
    m = o.getItemMeta()
    return None if m is None or not m.hasDisplayName() else m.displayName()


def _アイテム表示名設定(o, v):
    m = o.getItemMeta()
    if m is None:
        raise AttributeError("this item has no meta (empty item?)")
    m.displayName(None if v is None else _アイテム文(v))
    o.setItemMeta(m)


def _説明設定(o, v):
    if v is None:
        o.lore(None)
        return
    lst = _型("java.util.ArrayList")()
    for line in v:
        lst.add(_アイテム文(line))
    o.lore(lst)


def _ネクソ識別子(o):
    if o.isEmpty():
        return None
    raw = _host.pdcGet(o, "nexo:id")
    return None if raw is None else str(raw)


_アイテム表 = {
    # Только чтение: ItemStack.setType в Paper 1.21.11 устарел (может терять компоненты). Другой материал —
    # 甘酒.アイテム作成(素材, 数) или 甘酒.解く(x).withType(...).
    "種類": _P(lambda o: o.getType()),
    "数": _P(lambda o: o.getAmount(), lambda o, v: o.setAmount(int(v))),
    "最大スタック": _P(lambda o: o.getMaxStackSize()),
    "空か": _P(lambda o: bool(o.isEmpty())),
    "ネクソ識別子": _P(_ネクソ識別子),
    "表示名": _P(_アイテム表示名, _アイテム表示名設定),
    "説明": _P(lambda o: o.lore(), _説明設定),
    "同種か": _M(lambda o, 他: bool(o.isSimilar(_u(他)))),
    "複製": _M(lambda o: 包む(o.clone())),
    "copy": _M(lambda o: 包む(o.clone())),          # `.複製()` в японском режиме переводится в `.copy()`
    "永続データ": _P(lambda o: Data(o)),
}

#: Событие: (имя, геттеры-кандидаты, сеттер, обработка результата, обработка значения при записи).
_イベント候補 = (
    ("プレイヤー", ("getPlayer",), None, 包む, None),
    ("エンティティ", ("getEntity",), None, 包む, None),
    ("アイテム", ("getItem", "getItemDrop"), None, 包む, None),
    ("置き換えアイテム", ("getReplacement",), "setReplacement", 包む, _u),
    ("取消済み", ("isCancelled",), "setCancelled", bool, bool),
    ("ダメージ", ("getDamage",), "setDamage", None, float),
    ("最終ダメージ", ("getFinalDamage",), None, None, None),
    ("原因", ("getCause",), None, None, None),
    ("攻撃者", ("getDamager",), None, 包む, None),
    ("手", ("getHand",), None, None, None),
    ("行動", ("getAction",), None, None, None),
    ("ブロック", ("getClickedBlock", "getBlock"), None, 包む, None),
    ("移動元", ("getFrom",), None, 包む, None),
    ("移動先", ("getTo",), "setTo", 包む, _u),
    ("ドロップ", ("getDrops",), None, None, None),        # живой список Java
    ("経験値", ("getDroppedExp",), "setDroppedExp", None, int),
    ("参加メッセージ", ("joinMessage",), "joinMessage", None, lambda v: None if v is None else _文(v)),
    ("退出メッセージ", ("quitMessage",), "quitMessage", None, lambda v: None if v is None else _文(v)),
)


def _イベント表(methods):
    t = {}
    for 名, gets, setter, after, before in _イベント候補:
        g = next((m for m in gets if m in methods), None)
        if g is None:
            continue
        getter = (lambda o, g=g, a=after: a(getattr(o, g)())) if after else (lambda o, g=g: getattr(o, g)())
        st = None
        if setter is not None and setter in methods:
            st = (lambda o, v, s=setter, b=before: getattr(o, s)(b(v))) if before else \
                (lambda o, v, s=setter: getattr(o, s)(v))
        t[名] = _P(getter, st)
    if "setCancelled" in methods:
        t["取り消す"] = _M(lambda o: o.setCancelled(True))
    return t


def _召喚(o, 場所, 種類):
    t = _エンティティ種類値(種類)
    return 包む(o.spawnEntity(_u(場所), t))


def _粒子値(v):
    if isinstance(v, str):
        p = 粒子(v)
        if p is None:
            raise ValueError("unknown particle %r" % v)
        return p
    return _u(v)


def _粒子を出す(o, 粒子, 場所, 数=1, 広がり=0):
    p = _粒子値(粒子)
    d = float(広がり)
    return o.spawnParticle(p, _u(場所), int(数), d, d, d)


_世界表 = {
    "名前": _P(lambda o: o.getName()),
    "時刻": _P(lambda o: o.getTime(), lambda o, v: o.setTime(int(v))),
    "嵐か": _P(lambda o: bool(o.hasStorm()), lambda o, v: o.setStorm(bool(v))),
    "プレイヤー一覧": _P(lambda o: _包む一覧(o.getPlayers())),
    "ブロック": _M(lambda o, 東西, 高さ, 南北: 包む(o.getBlockAt(int(東西), int(高さ), int(南北)))),
    "召喚": _M(_召喚),
    "粒子を出す": _M(_粒子を出す),
    "音を鳴らす": _M(lambda o, 場所, 音, 音量=1, 高さ=1: o.playSound(_u(場所), _音値(音), float(音量), float(高さ))),
    "アイテムを落とす": _M(lambda o, 場所, アイテム: 包む(o.dropItemNaturally(_u(場所), _u(アイテム)))),
    "爆発": _M(lambda o, 場所, 強さ: bool(o.createExplosion(_u(場所), float(強さ)))),
    "雷": _M(lambda o, 場所: 包む(o.strikeLightning(_u(場所)))),
    "演出雷": _M(lambda o, 場所: 包む(o.strikeLightningEffect(_u(場所)))),
}


def _近く(o, 半径):
    r = float(半径)
    return _包む一覧(o.getWorld().getNearbyEntities(o, r, r, r))


_位置表 = {
    "東西": _P(lambda o: o.getX(), lambda o, v: o.setX(float(v))),
    "高さ": _P(lambda o: o.getY(), lambda o, v: o.setY(float(v))),
    "南北": _P(lambda o: o.getZ(), lambda o, v: o.setZ(float(v))),
    "水平角": _P(lambda o: o.getYaw(), lambda o, v: o.setYaw(float(v))),
    "仰角": _P(lambda o: o.getPitch(), lambda o, v: o.setPitch(float(v))),
    "世界": _P(lambda o: 包む(o.getWorld())),
    "向き": _P(lambda o: o.getDirection()),
    "ブロック": _P(lambda o: 包む(o.getBlock())),
    "ずらす": _M(lambda o, 東西=0, 高さ=0, 南北=0: 包む(o.clone().add(float(東西), float(高さ), float(南北)))),
    "距離": _M(lambda o, 他: o.distance(_u(他))),
    "距離の二乗": _M(lambda o, 他: o.distanceSquared(_u(他))),
    "複製": _M(lambda o: 包む(o.clone())),
    "copy": _M(lambda o: 包む(o.clone())),
    "近くのエンティティ": _M(_近く),
}

_ブロック表 = {
    "種類": _P(lambda o: o.getType(), lambda o, v: o.setType(_素材値(v))),
    "位置": _P(lambda o: 包む(o.getLocation())),
    "世界": _P(lambda o: 包む(o.getWorld())),
    "空か": _P(lambda o: bool(o.isEmpty())),
    "壊す": _M(lambda o: bool(o.breakNaturally())),
}

_インベントリ表 = {
    "中身": _P(lambda o: _包む一覧(o.getContents())),
    "入れる": _M(lambda o, *アイテム: _包む一覧(o.addItem(*[_u(i) for i in アイテム]).values())),
    "取り除く": _M(lambda o, *アイテム: _包む一覧(o.removeItem(*[_u(i) for i in アイテム]).values())),
    "含む": _M(lambda o, 物: bool(o.contains(_素材値(物) if isinstance(物, str) else _u(物)))),
}


def _装備欄(get, put):
    return _P(lambda o: 包む(getattr(o, get)()), lambda o, v: getattr(o, put)(_u(v)))


_プレイヤーインベントリ表 = {
    "手のアイテム": _装備欄("getItemInMainHand", "setItemInMainHand"),
    "左手のアイテム": _装備欄("getItemInOffHand", "setItemInOffHand"),
    "兜": _装備欄("getHelmet", "setHelmet"),
    "胸当て": _装備欄("getChestplate", "setChestplate"),
    "脚当て": _装備欄("getLeggings", "setLeggings"),
    "靴": _装備欄("getBoots", "setBoots"),
}

_属性値表 = {
    "値": _P(lambda o: o.getValue()),
    "基礎値": _P(lambda o: o.getBaseValue(), lambda o, v: o.setBaseValue(float(v))),
}

_効果表 = {
    "種類": _P(lambda o: o.getType()),
    "時間": _P(lambda o: o.getDuration()),
    "強さ": _P(lambda o: o.getAmplifier()),
}

_タスク表 = {
    "取り消す": _M(lambda o: o.cancel()),
    "取消済み": _P(lambda o: bool(o.isCancelled())),
}
_タスク = None   # подкласс 包み для задач (создаётся при первой задаче)


def _タスク包み(生):
    global _タスク
    if _タスク is None:
        _タスク = _クラス化(_タスク表, "タスク")
    return _タスク(生)

#: Виды по приоритету: (класс Java, таблица). Событие — отдельно (по методам класса).
_種類一覧 = (
    ("org.bukkit.entity.Player", _プレイヤー表),
    ("org.bukkit.entity.LivingEntity", _生物表),
    ("org.bukkit.entity.Entity", _実体表),
    ("org.bukkit.command.CommandSender", _送り主表),
    ("org.bukkit.inventory.ItemStack", _アイテム表),
    ("org.bukkit.event.Event", None),
    ("org.bukkit.World", _世界表),
    ("org.bukkit.Location", _位置表),
    ("org.bukkit.block.Block", _ブロック表),
    ("org.bukkit.inventory.PlayerInventory", _プレイヤーインベントリ表),
    ("org.bukkit.inventory.Inventory", _インベントリ表),
    ("org.bukkit.attribute.AttributeInstance", _属性値表),
    ("org.bukkit.potion.PotionEffect", _効果表),
    ("core.amazake.systems.corepython.host.Task", _タスク表),
)

_種類クラス = []
_表キャッシュ = {}
_空表 = {}


def _表を得る(生):
    """Таблица японских атрибутов для Java-класса объекта (считается один раз на класс)."""
    try:
        cls = 生.getClass()
        key = cls.getName()
    except BaseException:
        return _空表
    t = _表キャッシュ.get(key)
    if t is not None:
        return t
    if not _種類クラス:
        for 名, 表 in _種類一覧:
            try:
                _種類クラス.append((_型(名), 表))
            except BaseException:
                pass
    t = {}
    for c, 表 in _種類クラス:
        try:
            ok = _java.instanceof(生, c)
        except BaseException:
            ok = False
        if not ok:
            continue
        if 表 is None:
            表 = _イベント表({str(m.getName()) for m in cls.getMethods()})
        for k, e in 表.items():
            if k not in t:
                t[k] = e
    _表キャッシュ[key] = t
    return t


# ---------------------------------------------------------------------------------------------
# §4.1 Сведения, лог, Java
# ---------------------------------------------------------------------------------------------

名前 = name
版 = version
コードフォルダ = script_folder
データフォルダ = data_folder
_Log.情報 = _Log.info
_Log.警告 = _Log.warn
_Log.エラー = _Log.error
_Log.デバッグ = _Log.debug
記録 = log
ジャバ型 = java_class
プラグイン型 = plugin_class
クラスオブジェクト = find_class
プラグイン = plugin
サーバー = Bukkit


def ジャバタスク(対象, メソッド名, *引数, 型=None):
    """java_task: Java-Runnable без Python — при run() вызывает 対象.メソッド名(*引数)."""
    return java_task(対象, メソッド名, *引数, type=型)


def プレイヤーでジャバ実行(プレイヤー, タスク):
    """run_java_for_player."""
    return run_java_for_player(プレイヤー, タスク)


def エンティティでジャバ実行(エンティティ, タスク, 遅延=0):
    """run_java_on_entity."""
    return run_java_on_entity(エンティティ, タスク, 遅延)


def 大域でジャバ実行(タスク, 遅延=0):
    """run_java_global."""
    return run_java_global(タスク, 遅延)


def エンティティ削除(エンティティ):
    """remove_entity."""
    return remove_entity(エンティティ)


def 呼ぶ(対象, メソッド名, *引数):
    """Java-метод по имени-строке: 呼ぶ(x, "getStats") = 解く(x).getStats(); аргументы разворачиваются, результат сырой."""
    return getattr(_u(対象), メソッド名)(*[_u(a) for a in 引数])


def 現在ミリ秒():
    """System.currentTimeMillis()."""
    return _型("java.lang.System").currentTimeMillis()


# ---------------------------------------------------------------------------------------------
# §4.2 События, команды, жизненный цикл
# ---------------------------------------------------------------------------------------------

def イベント時(イベント, 優先度="通常", 取消済み無視=False):
    """@on, но обработчик получает обёрнутое событие (包み). イベント — класс, англ. имя или японское (§4.5)."""
    ev = イベント名一覧.get(イベント, イベント) if isinstance(イベント, str) else _u(イベント)
    pr = 優先度一覧.get(優先度, 優先度)
    if pr not in _PRIORITIES:
        raise ValueError("priority must be one of: " + " | ".join(list(優先度一覧) + list(_PRIORITIES)))

    def deco(処理):
        def 受け(e):
            return 処理(包み(e))     # всегда обёртка (и у события без японских имён)

        _host.listen(ev, pr, bool(取消済み無視), 受け)
        return 処理

    return _守る(deco)


起動時 = on_load
停止時 = on_unload


def コマンド(名前, 権限=None, 別名=None, 説明=None, 使い方=None):
    """@command, но 処理(送り主, 引数) получает обёрнутого отправителя. Автодополнение — @処理.補完."""
    spec = _host.manifestCommand(名前)
    if spec is not None:
        権限 = 権限 if 権限 is not None else spec[0]
        別名 = 別名 if 別名 is not None else list(spec[1])
        説明 = 説明 if 説明 is not None else spec[2]
        使い方 = 使い方 if 使い方 is not None else spec[3]

    def deco(処理):
        def run(sender, label, args):
            処理(包み(sender), list(args))

        handle = _host.command(名前, 権限, list(別名 or []), 説明 or "", 使い方 or ("/" + 名前), run)
        c = Command(handle)

        def 補完(f):
            def wrapper(sender, args):
                r = f(包み(sender), list(args))
                return list(r) if r else []

            handle.setCompleter(wrapper)
            return f

        処理.補完 = 補完
        処理.tab = c.tab
        処理.command = c
        return 処理

    return _守る(deco)


# ---------------------------------------------------------------------------------------------
# §4.3 Потоки и задачи (задачи возвращаются обёрнутыми: .取り消す(), .取消済み)
# ---------------------------------------------------------------------------------------------

def プレイヤーで繰り返す(プレイヤー, 処理, 周期=20):
    """run_for_player: 処理() в потоке игрока каждые 周期 тиков; `返す いいえ` — остановить."""
    return _タスク包み(run_for_player(プレイヤー, 処理, 周期))


def プレイヤーで一度(プレイヤー, 処理, 遅延=1):
    """run_once_for_player: один раз 処理() в потоке игрока через 遅延 тиков."""
    return _タスク包み(run_once_for_player(プレイヤー, 処理, 遅延))


def 領域で実行(場所, 処理, 遅延=0, 周期=0):
    """run_in_region."""
    return _タスク包み(run_in_region(場所, 処理, 遅延, 周期))


def 大域で実行(処理, 遅延=0, 周期=0):
    """run_global."""
    return _タスク包み(run_global(処理, 遅延, 周期))


def 非同期で実行(処理, 遅延ミリ秒=0, 周期ミリ秒=0):
    """run_async."""
    return _タスク包み(run_async(処理, 遅延ミリ秒, 周期ミリ秒))


担当か = owns

# ---------------------------------------------------------------------------------------------
# §4.4 Конфиг, файлы, PDC, атрибуты, эффекты, текст
# ---------------------------------------------------------------------------------------------


def 設定読込(ファイル="config.yml", 既定=None):
    """config: YAML из папки системы → dict."""
    return config(ファイル, 既定)


def _保管庫読む(self, 既定=None):
    return self.load(既定)


def _保管庫書く(self, 値):
    return self.save(値)


def _保管庫非同期で書く(self, 値):
    return _タスク包み(self.save_async(値))


Storage.読む = _保管庫読む
Storage.書く = _保管庫書く
Storage.非同期で書く = _保管庫非同期で書く


def 保管庫(ファイル):
    """storage: JSON-файл в папке данных системы (.読む / .書く / .非同期で書く)."""
    return Storage(ファイル)


キー = key


def _データ読む(self, 鍵, 既定=None):
    return self.get(鍵, 既定)


def _データ文字列で読む(self, 鍵, 既定=None):
    return self.get_string(鍵, 既定)


def _データ書く(self, 鍵, 値):
    return self.set(鍵, 値)


def _データ文字列で書く(self, 鍵, 値):
    return self.set_string(鍵, 値)


def _データある(self, 鍵):
    return bool(self.has(鍵))


def _データ消す(self, 鍵):
    return self.remove(鍵)


Data.読む = _データ読む
Data.文字列で読む = _データ文字列で読む
Data.書く = _データ書く
Data.文字列で書く = _データ文字列で書く
Data.ある = _データある
Data.消す = _データ消す


def 永続データ(物):
    """data: PDC игрока/сущности/предмета (.読む / .文字列で読む / .書く / .文字列で書く / .ある / .消す)."""
    return Data(物)


def 属性(名前):
    """attribute: имя английское или японское (最大体力, 防御力, …)."""
    return attribute(名前)


def 修飾子(鍵, 量, 演算="加算", 部位="全て"):
    """modifier: AttributeModifier для предметов."""
    return modifier(鍵, 量, 演算, 部位)


def 一時修飾子追加(プレイヤー, 属性名, 鍵, 量, 演算="加算"):
    """add_modifier: временный модификатор атрибута игрока (снимается при выгрузке). Только в потоке игрока."""
    add_modifier(プレイヤー, 属性名, 鍵, 量, 演算)


def 一時修飾子削除(プレイヤー, 属性名, 鍵):
    """remove_modifier."""
    return remove_modifier(プレイヤー, 属性名, 鍵)


def 効果種類(名前):
    """effect_type: PotionEffectType по английскому или японскому имени."""
    return effect_type(名前)


def 一時効果付与(プレイヤー, 効果, 時間=100, 強さ=0, 環境=True, 粒子=False, アイコン=True):
    """add_effect: эффект от системы, снимается при выгрузке. Постоянный — プレイヤー.効果付与(…)."""
    add_effect(プレイヤー, 効果, 時間, 強さ, 環境, 粒子, アイコン)


def 一時効果解除(プレイヤー, 効果):
    """remove_effect."""
    return remove_effect(プレイヤー, 効果)


def 装飾文(文, 置換=None, **他):
    """mm: MiniMessage → Component. Плейсхолдеры — 置換={"amount": "10"} или 名前=値 (имена любые, в т.ч. японские)."""
    t, ph = _置換(文, 置換, 他)
    return _host.mm(t, ph)


def アイテム文(文, 置換=None, **他):
    """item_text: как 装飾文, без курсива."""
    t, ph = _置換(文, 置換, 他)
    return _host.itemText(t, ph)


def 送る(相手, 文, 置換=None, **他):
    """send: сообщение игроку/консоли в MiniMessage."""
    t, ph = _置換(文, 置換, 他)
    _u(相手).sendMessage(_host.mm(t, ph))


def アクションバー(プレイヤー, 文, 置換=None, **他):
    """actionbar: строка над хотбаром."""
    t, ph = _置換(文, 置換, 他)
    _u(プレイヤー).sendActionBar(_host.mm(t, ph))


# --- новые помощники ---------------------------------------------------------------------------

def 空のアイテム():
    """ItemStack.empty() (обёрнут)."""
    return 包む(_型("org.bukkit.inventory.ItemStack").empty())


def アイテム作成(素材, 数=1):
    """new ItemStack(素材, 数) (обёрнут); 素材 — строка ("DIAMOND", "minecraft:diamond") или Material."""
    return 包む(_型("org.bukkit.inventory.ItemStack")(_素材値(素材), int(数)))


def 素材(名前):
    """Material по имени ("DIAMOND", "minecraft:diamond") или None."""
    return _型("org.bukkit.Material").matchMaterial(str(名前))


def _定数(cls_name, 名前):
    n = str(名前)
    if ":" in n:
        n = n.split(":", 1)[1]
    n = n.replace(".", "_").replace("-", "_").upper()
    try:
        return getattr(_型(cls_name), n)
    except BaseException:
        return None


def _エンティティ種類値(v):
    if isinstance(v, str):
        t = エンティティ種類(v)
        if t is None:
            raise ValueError("unknown entity type %r" % v)
        return t
    return _u(v)


def エンティティ種類(名前):
    """EntityType по имени ("ZOMBIE", "minecraft:zombie") или None."""
    return _定数("org.bukkit.entity.EntityType", 名前)


def 音(名前):
    """Sound по имени ("ENTITY_PLAYER_LEVELUP") или ключу ("entity.player.levelup") или None."""
    n = str(名前)
    if "." in n or ":" in n:
        try:
            k = _型("org.bukkit.NamespacedKey").fromString(n.lower() if ":" in n else "minecraft:" + n.lower())
            s = _型("org.bukkit.Registry").SOUNDS.get(k)
            if s is not None:
                return s
        except BaseException:
            pass
    return _定数("org.bukkit.Sound", n)


def _音値(v):
    if isinstance(v, str):
        s = 音(v)
        if s is None:
            raise ValueError("unknown sound %r" % v)
        return s
    return _u(v)


def 粒子(名前):
    """Particle по имени ("FLAME", "minecraft:flame") или None."""
    return _定数("org.bukkit.Particle", 名前)


def プレイヤー取得(名前):
    """Игрок онлайн по точному имени (обёрнут) или None."""
    return 包む(Bukkit.getPlayerExact(str(名前)))


def オンラインプレイヤー():
    """Список игроков онлайн (обёрнутых)."""
    return _包む一覧(Bukkit.getOnlinePlayers())


# Японские функции и методы: ошибка Java → JavaError с точной строкой вызывающего (японского) кода.
for _名, _値 in list(globals().items()):
    if (callable(_値) and not isinstance(_値, type) and _名[:1] > "\u3000" and _名 not in ("包む", "解く")
            and getattr(_値, "__module__", None) == __name__):
        globals()[_名] = _守る(_値)
for _型名 in (Data, Storage):
    for _名 in ("読む", "文字列で読む", "書く", "文字列で書く", "ある", "消す", "非同期で書く"):
        if _名 in _型名.__dict__:
            setattr(_型名, _名, _守る(_型名.__dict__[_名]))
del _名, _値, _型名

# Модуль 甘酒 — тот же объект, что amazake: `取り込む 甘酒`, `から 甘酒 取り込む 送る`.
_sys.modules["甘酒"] = _sys.modules[__name__]
