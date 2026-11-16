# 共通 — общее для модулей core_effects: типы Java, ключи PDC/атрибутов, текущие настройки, отправка сообщений,
# объект плагина (для showEntity и планировщика при выгрузке) и Java-задачи без Python (для @on_unload).

import struct as _struct

import java
import amazake

import settings

Bukkit = amazake.Bukkit
Material = java.type("org.bukkit.Material")
Sound = java.type("org.bukkit.Sound")
SoundCategory = java.type("org.bukkit.SoundCategory")
Particle = java.type("org.bukkit.Particle")
Location = java.type("org.bukkit.Location")
Vector = java.type("org.bukkit.util.Vector")
GameMode = java.type("org.bukkit.GameMode")
NamespacedKey = java.type("org.bukkit.NamespacedKey")
PersistentDataType = java.type("org.bukkit.persistence.PersistentDataType")
PotionEffectType = java.type("org.bukkit.potion.PotionEffectType")
Player = java.type("org.bukkit.entity.Player")
Entity = java.type("org.bukkit.entity.Entity")
LivingEntity = java.type("org.bukkit.entity.LivingEntity")
Projectile = java.type("org.bukkit.entity.Projectile")
Component = java.type("net.kyori.adventure.text.Component")
Key = java.type("net.kyori.adventure.key.Key")
NamedTextColor = java.type("net.kyori.adventure.text.format.NamedTextColor")
ShadowColor = java.type("net.kyori.adventure.text.format.ShadowColor")
Title = java.type("net.kyori.adventure.title.Title")
TitleTimes = java.type("net.kyori.adventure.title.Title$Times")
Duration = java.type("java.time.Duration")
System = java.type("java.lang.System")

# --- ключи (пространство имён coreeffects — те же, что у Java-системы) --------------------------------------------
状態鍵 = "coreeffects:state"          # эффекты игрока: строка 効果帳.符号化()
包帯鍵 = "coreeffects:bandage"        # метка бинта на предмете (байт 1)
幻影鍵 = "coreeffects:phantom"        # метка фантома паники (значение — id экземпляра системы)
恐慌攻撃鍵 = "coreeffects:panic_damage"
恐慌体力鍵 = "coreeffects:panic_health"
恐慌速度鍵 = "coreeffects:panic_speed"
気絶速度鍵 = "coreeffects:faint_speed"
気絶跳躍鍵 = "coreeffects:faint_jump"
全修飾鍵 = (恐慌攻撃鍵, 恐慌体力鍵, 恐慌速度鍵, 気絶速度鍵, 気絶跳躍鍵)
全属性 = ("attack_damage", "max_health", "movement_speed", "jump_strength")

NK = {k: amazake.key(k) for k in (状態鍵, 包帯鍵, 幻影鍵) + 全修飾鍵}
属性 = {a: amazake.attribute(a) for a in 全属性}

# --- ответы PlaceholderAPI ---------------------------------------------------------------------------------------------
# «UUID игрока» → java.util.Map<параметр, ответ> (готовит поток игрока), «» → ответы по умолчанию и <эффект>_name.
# Читает Java-мост (bridge.py) из любого потока без Python.
_HashMap = java.type("java.util.HashMap")
PAPI表 = java.type("java.util.concurrent.ConcurrentHashMap")()


def 表を出す(鍵, 表):
    m = _HashMap()
    for k, v in 表.items():
        m.put(k, v)
    PAPI表.put(鍵, m)


# --- текущие настройки (подменяются целиком при /effects reload) -------------------------------------------------
_現 = [settings.既定]


def 設定():
    return _現[0]


def 設定を置く(s):
    _現[0] = s


# --- сообщения ------------------------------------------------------------------------------------------------------

def 送る(宛先, s, 鍵, 解析=None, **素):
    """Сообщение messages.<鍵> с префиксом; пустой шаблон — не отправлять. 解析 — плейсхолдеры с MiniMessage
    внутри (как Placeholder.parsed: подставляются в текст до разбора), 素 — как есть (строки) или Component."""
    型 = s.文.取る(鍵)
    if not 型 or not 型.strip():
        return
    文 = s.接頭辞 + 型
    if 解析:
        for k, v in 解析.items():
            文 = 文.replace("<" + k + ">", v)
    amazake.send(宛先, 文, **素)


def 解析済み(文, 解析=None, **素):
    """MiniMessage → Component (без префикса) с теми же правилами плейсхолдеров."""
    if 解析:
        for k, v in 解析.items():
            文 = 文.replace("<" + k + ">", v)
    return amazake.mm(文, **素)


# --- плагин и Java-задачи core-python ---------------------------------------------------------------------------------
# Нужны объект плагина (player.showEntity(plugin, e)) и Java-задачи, которые выполнятся и после закрытия контекста
# Python (уборка при выгрузке; при остановке сервера — сразу, как runOnceForPlayer у Java-системы).
# Новый core-python даёт для этого публичный API: amazake.plugin(), amazake.find_class(), amazake.run_java_*.
# Старый core-python.azs (до его передеплоя) такого API не имеет — тогда, и только тогда, SystemContext ядра берётся
# отражением по закрытым полям хоста (ScriptApi.script → PyScript.platform → BukkitPlatform.context).
# TODO: убрать _旧文脈 и ветки «not _新API», когда на проде будет новый core-python.

_新API = all(hasattr(amazake, n) for n in ("plugin", "find_class", "run_java_for_player", "run_java_on_entity",
                                          "run_java_global"))
_文脈 = []
_文脈警告 = [False]


def _旧文脈():
    """Только для старого core-python: SystemContext ядра через закрытые поля хоста, или None."""
    if _文脈:
        return _文脈[0]
    try:
        h = amazake._host
        f = h.getClass().getDeclaredField("script")
        f.setAccessible(True)
        s = f.get(h)
        f = s.getClass().getDeclaredField("platform")
        f.setAccessible(True)
        pf = f.get(s)
        f = pf.getClass().getDeclaredField("context")
        f.setAccessible(True)
        c = f.get(pf)
        if c is None:
            raise RuntimeError("context = null")
        _文脈.append(c)
        return c
    except BaseException as e:   # исключения Java (ForeignException) — не Exception
        if not _文脈警告[0]:
            _文脈警告[0] = True
            amazake.log.warn("старый core-python без amazake.plugin(), и его контекст не найден (" + str(e) + "): "
                             "фантомы паники выключены, уборка при выгрузке — только из потоков игроков")
        return None


def プラグイン():
    """Plugin core-python (для API Bukkit, которым нужен Plugin) или None."""
    if _新API:
        return amazake.plugin()
    c = _旧文脈()
    if c is None:
        return None
    try:
        return c.plugin()
    except BaseException:
        return None


# --- float для Java ------------------------------------------------------------------------------------------------
# GraalPy не сужает double → float с потерей точности: playSound(..., 0.7, 0.6), setYaw(угол), DustOptions(.., 1.1)
# с «неточными» числами бросают исключение. Всё, что уходит в float-параметр Java, — через f32 (точное float-значение).

_f32表 = {}


def f32(v):
    r = _f32表.get(v)
    if r is None:
        r = _struct.unpack("f", _struct.pack("f", float(v)))[0]
        if len(_f32表) < 256:
            _f32表[v] = r
    return r


def 角(v):
    """Угол (yaw) для float-параметра — без кэша (значения разные)."""
    return _struct.unpack("f", _struct.pack("f", float(v)))[0]


def 鳴らす(player, 場所, 音, 種別, 音量, 高さ):
    """player.playSound с float громкостью/высотой."""
    player.playSound(場所, 音, 種別, f32(音量), f32(高さ))


# --- Java-задачи без Python --------------------------------------------------------------------------------------------
# В @on_unload Python-задачи планировать бессмысленно (ядро их тут же отменяет, контекст закрывается). Поэтому то,
# что надо сделать в чужом потоке (PDC игрока, заголовок, удаление фантомов), — чистый Java-Runnable из MethodHandle:
# он работает и после закрытия контекста Python.

_MH = java.type("java.lang.invoke.MethodHandles")
_MT = java.type("java.lang.invoke.MethodType")
_MHP = java.type("java.lang.invoke.MethodHandleProxies")
_ArrayList = java.type("java.util.ArrayList")
_Void = java.type("java.lang.Void")
_Runnable = getattr(java.type("java.lang.Runnable"), "class")
_ObjectArray = java.type("java.lang.Object[]")
_ClassArray = java.type("java.lang.Class[]")


def _クラス(名):
    if _新API:
        return amazake.find_class(名)
    return amazake._host.findClass(名)   # старый core-python: тот же публичный метод хоста, без обёртки


def ジャバ処理(型名, メソッド, 引数型名, 対象, *引数):
    """Runnable: 対象.メソッド(*引数) — только Java (публичный метод класса/интерфейса 型名; результат отбрасывается).
    型名 — имя класса или сам объект Class."""
    型s = _ArrayList()
    for n in 引数型名:
        型s.add(_クラス(n))
    型 = _クラス(型名) if isinstance(型名, str) else 型名
    m = 型.getMethod(メソッド, 型s.toArray(_ClassArray(0)))
    mh = _MH.publicLookup().unreflect(m)
    if not m.getReturnType().equals(_Void.TYPE):
        mh = _MH.dropReturn(mh)
    # Явный Object[]: иначе GraalPy может принять объект-список (List) за сам массив varargs.
    値 = _ObjectArray(1 + len(引数))
    値[0] = 対象
    for i, v in enumerate(引数):
        値[i + 1] = v
    mh = _MH.insertArguments(mh, 0, 値)
    return _MHP.asInterfaceInstance(_Runnable, mh)


def ジャバで実体へ(実体, 処理):
    """Выполнить Java-Runnable в потоке сущности (сразу, если поток ею владеет или сервер останавливается).
    False — не вышло."""
    try:
        if _新API:
            return amazake.run_java_on_entity(実体, 処理)
        p = プラグイン()
        if p is None:
            return False
        return bool(実体.getScheduler().execute(p, 処理, None, 1))
    except BaseException as e:   # и исключения Java
        amazake.log.debug("планировщик сущности: " + str(e))
        return False


def ジャバで全体へ(処理):
    """Java-Runnable в глобальном потоке (сразу, если мы в нём). False — не вышло."""
    try:
        if _新API:
            return amazake.run_java_global(処理)
        if Bukkit.isGlobalTickThread():
            処理.run()
            return True
        p = プラグイン()
        if p is None:
            return False
        Bukkit.getGlobalRegionScheduler().execute(p, 処理)
        return True
    except BaseException as e:
        amazake.log.debug("глобальный планировщик: " + str(e))
        return False


def ジャバで人へ(player, 処理):
    """Java-Runnable в потоке игрока — как SystemContext.runOnceForPlayer у Java-системы: при остановке сервера
    (выключение систем) он выполняется сразу, иначе — в потоке игрока. False — не вышло."""
    try:
        if _新API:
            return amazake.run_java_for_player(player, 処理)
        c = _旧文脈()
        if c is None:
            return False
        return bool(c.runOnceForPlayer(player, 処理))
    except BaseException as e:
        amazake.log.debug("runOnceForPlayer: " + str(e))
        return False
