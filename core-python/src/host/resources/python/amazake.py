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


Bukkit = _java.type("org.bukkit.Bukkit")

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
        _host.listen(event, priority, bool(ignore_cancelled), fn)
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
    return _host.runForPlayer(player, fn, int(period))


def run_once_for_player(player, fn, delay=1):
    """Один раз fn() в потоке игрока через delay тиков (минимум 1)."""
    return _host.runOnceForPlayer(player, fn, int(delay))


def run_in_region(where, fn, delay=0, period=0):
    """fn() в потоке региона места/сущности/блока; delay/period — в тиках (period > 0 — повторять)."""
    return _host.runInRegion(where, fn, int(delay), int(period))


def run_global(fn, delay=0, period=0):
    """fn() в глобальном потоке (время, погода, мир целиком); delay/period — в тиках."""
    return _host.runGlobal(fn, int(delay), int(period))


def run_async(fn, delay_ms=0, period_ms=0):
    """fn() вне тиков (файлы, сеть, тяжёлые расчёты); delay_ms/period_ms — в миллисекундах."""
    return _host.runAsync(fn, int(delay_ms), int(period_ms))


def owns(thing):
    """Владеет ли текущий поток игроком/сущностью/местом/блоком (можно ли трогать его прямо сейчас)."""
    return _host.owns(thing)


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
        self.holder = holder

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
    return _host.attribute(name)


def modifier(k, amount, operation="add", slot="any"):
    """AttributeModifier для предметов: operation = add | multiply_base | multiply;
    slot = any | head | chest | legs | feet | armor | mainhand | offhand | hand."""
    return _host.modifier(k, float(amount), operation, slot)


def add_modifier(player, attr, k, amount, operation="add"):
    """Временный модификатор атрибута игрока (не сохраняется в файл игрока; снимается при выгрузке).
    Повторный вызов с тем же ключом заменяет значение. Только в потоке игрока."""
    _host.addPlayerModifier(player, attr, k, float(amount), operation)


def remove_modifier(player, attr, k):
    """Снять модификатор, добавленный add_modifier. Только в потоке игрока."""
    return _host.removePlayerModifier(player, attr, k)


def effect_type(name):
    """PotionEffectType по имени ("slowness", "minecraft:speed", …)."""
    return _host.effectType(name)


def add_effect(player, effect, duration=100, amplifier=0, ambient=True, particles=False, icon=True):
    """Эффект зелья от системы (снимается при выгрузке, если на игроке всё ещё он). Только в потоке игрока.
    effect — "speed", "regeneration", "fire_resistance", …; duration в тиках (-1 — бесконечно)."""
    _host.addPlayerEffect(player, effect, int(duration), int(amplifier), bool(ambient), bool(particles), bool(icon))


def remove_effect(player, effect):
    """Снять эффект, если он наш. Только в потоке игрока."""
    return _host.removePlayerEffect(player, effect)


# ---------------------------------------------------------------------------------------------
# Текст (MiniMessage)
# ---------------------------------------------------------------------------------------------

def mm(text, **placeholders):
    """MiniMessage → Component. Плейсхолдеры: mm("<gold>Привет, <name>", name="Rezus") (значения не парсятся)."""
    return _host.mm(text, placeholders)


def item_text(text, **placeholders):
    """Как mm, но без курсива (для названий и описаний предметов)."""
    return _host.itemText(text, placeholders)


def send(target, text, **placeholders):
    """Сообщение игроку/консоли в MiniMessage."""
    target.sendMessage(_host.mm(text, placeholders))


def actionbar(player, text, **placeholders):
    """Строка над хотбаром."""
    player.sendActionBar(_host.mm(text, placeholders))
