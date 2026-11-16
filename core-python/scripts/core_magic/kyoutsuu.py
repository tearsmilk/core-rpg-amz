import math
# 共通 — общее для модулей core_magic: типы Java, f32, ключи PDC, предмет-эффект для дисплеев (метка шейдера),
# звуки и частицы, объект плагина и Java-задачи без Python (уборка в @on_unload). Приём ジャバ処理 перенесён из
# core_effects/common.py (копия, не импорт: системы живут в разных контекстах).

import java
import amazake

import keisan
from settei import f32

Bukkit = amazake.Bukkit
Material = java.type("org.bukkit.Material")
Location = java.type("org.bukkit.Location")
Vector = java.type("org.bukkit.util.Vector")
BoundingBox = java.type("org.bukkit.util.BoundingBox")
Particle = java.type("org.bukkit.Particle")
SoundCategory = java.type("org.bukkit.SoundCategory")
FluidCollisionMode = java.type("org.bukkit.FluidCollisionMode")
GameMode = java.type("org.bukkit.GameMode")
Color = java.type("org.bukkit.Color")
ItemStack = java.type("org.bukkit.inventory.ItemStack")
EquipmentSlot = java.type("org.bukkit.inventory.EquipmentSlot")
Player = java.type("org.bukkit.entity.Player")
LivingEntity = java.type("org.bukkit.entity.LivingEntity")
ArmorStand = java.type("org.bukkit.entity.ArmorStand")
ItemDisplayType = java.type("org.bukkit.entity.ItemDisplay")
ItemDisplayTransform = java.type("org.bukkit.entity.ItemDisplay$ItemDisplayTransform")
Billboard = java.type("org.bukkit.entity.Display$Billboard")
Brightness = java.type("org.bukkit.entity.Display$Brightness")
Transformation = java.type("org.bukkit.util.Transformation")
Vector3f = java.type("org.joml.Vector3f")
Quaternionf = java.type("org.joml.Quaternionf")
DataComponentTypes = java.type("io.papermc.paper.datacomponent.DataComponentTypes")
CustomModelData = java.type("io.papermc.paper.datacomponent.item.CustomModelData")
Key = java.type("net.kyori.adventure.key.Key")
EventResult = java.type("org.bukkit.event.Event$Result")
System = java.type("java.lang.System")

NEXO_ID = "nexo:id"
効果鍵 = "fx"               # py_core_magic:fx = circle | beam | barrier
主鍵 = "owner"              # py_core_magic:owner = UUID заклинателя
杖鍵 = "staff"              # py_core_magic:staff = 1 (запасной посох без Nexo)
NPC鍵 = amazake.key("corenpc:npc")
効果NK = amazake.key(効果鍵)

#: Выгрузка началась (@on_unload ставит первым делом): задачи, уже вошедшие в Python, ничего нового не создают.
終了済み = [False]


def クラス(名前):
    """java.lang.Class по имени (для world.spawn(…, ItemDisplay.class, …))."""
    t = java.type(名前)
    try:
        return getattr(t, "class")
    except BaseException:
        return t


ItemDisplay = クラス("org.bukkit.entity.ItemDisplay")


def 今ms():
    """Монотонное время, мс (перезарядки)."""
    return System.nanoTime() // 1000000


# --------------------------------------------------------------------------------------------- предметы эффектов

def 鍵(文字列):
    """«ns:name» → adventure Key."""
    ns, _, v = 文字列.partition(":")
    return Key.key(ns, v) if v else Key.key("minecraft", ns)


def 効果品(モデル, R):
    """Предмет для дисплея круга/луча: item_model + цвет custom_model_data (R, 233, 253) — ключ A шейдера."""
    item = ItemStack(Material.PAPER)
    item.setData(DataComponentTypes.ITEM_MODEL, 鍵(モデル))
    r = int(keisan.挟む(int(R), 0, keisan.R上限))
    cmd = CustomModelData.customModelData().addColor(Color.fromRGB(r, keisan.印G, keisan.印B)).build()
    item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd)
    return item


def 変形(前, 下, sx, sy, sz, 反転=False):
    """Transformation: перенос (0, −下, 前) в осях дисплея (+Z — взгляд), масштаб (sx, sy, sz).
    反転 — модель повёрнута на 180° вокруг оси Y дисплея (луч: модель вытянута в −Z, без поворота бил бы назад)."""
    回 = Quaternionf().rotationY(f32(math.pi)) if 反転 else Quaternionf()
    return Transformation(Vector3f(f32(0.0), f32(-下), f32(前)), 回,
                          Vector3f(f32(sx), f32(sy), f32(sz)), Quaternionf())


def 変形3(x, y, z, sx, sy, sz, 反転=True):
    """Transformation: перенос (x, y, z) в осях дисплея, масштаб (sx, sy, sz). 反転 — модель повёрнута на 180°
    вокруг Y (ItemDisplay сам рисует модель повёрнутой на 180°: с 反転 оси модели = оси дисплея)."""
    回 = Quaternionf().rotationY(f32(math.pi)) if 反転 else Quaternionf()
    return Transformation(Vector3f(f32(x), f32(y), f32(z)), 回, Vector3f(f32(sx), f32(sy), f32(sz)), Quaternionf())


# --------------------------------------------------------------------------------------------- звук и частицы

def 鳴らす(world, 場所, 音列, 足す=0.0):
    """Список звуков settei.音 у места (категория PLAYERS). 足す — прибавка к высоте (гул растёт с зарядом)."""
    for s in 音列:
        try:
            world.playSound(場所, s.鍵, SoundCategory.PLAYERS, s.音量,
                            s.高さ if not 足す else f32(keisan.挟む(s.高さ + 足す, 0.5, 2.0)))
        except BaseException as e:
            amazake.log.debug("звук " + s.鍵 + ": " + str(e))


def 自分に鳴らす(p, 音列):
    """Звуки только этому игроку (у его глаз, категория PLAYERS). Поток игрока."""
    try:
        at = p.getEyeLocation()
    except BaseException:
        return
    for s in 音列:
        try:
            p.playSound(at, s.鍵, SoundCategory.PLAYERS, s.音量, s.高さ)
        except BaseException as e:
            amazake.log.debug("звук " + s.鍵 + ": " + str(e))


_粒型 = {}
_Void = java.type("java.lang.Void")


def 粒型(名前):
    """Particle по имени (кэш). Неизвестное имя или частица, которой нужны данные (DUST, BLOCK, …), — END_ROD:
    опечатка в config.yml не должна ломать выстрел."""
    t = _粒型.get(名前)
    if t is None:
        try:
            t = Particle.valueOf(名前)
            dt = t.getDataType()
            if dt is not None and not dt.equals(getattr(_Void, "class")):
                amazake.log.warn("частице " + 名前 + " нужны данные — заменена на END_ROD")
                t = Particle.END_ROD
        except BaseException:   # исключения Java (ForeignException) — не Exception
            amazake.log.warn("частица " + 名前 + " не найдена — заменена на END_ROD")
            t = Particle.END_ROD
        _粒型[名前] = t
    return t


def 散らす(world, 粒, x, y, z):
    if 粒.数 <= 0:
        return
    try:
        world.spawnParticle(粒型(粒.種類), float(x), float(y), float(z), int(粒.数),
                            float(粒.広がり), float(粒.広がり), float(粒.広がり), float(粒.速さ))
    except BaseException as e:
        amazake.log.debug("частицы " + 粒.種類 + ": " + str(e))


# --------------------------------------------------------------------------------------------- удаление дисплеев
# Новый core-python даёт amazake.remove_entity(e): сразу, если поток владеет сущностью, иначе — Java-задачей в её
# потоке (планировщик сущности, не региона: сущность могла уйти из места, где мы её видели в последний раз), и это
# работает даже из @on_unload, когда Python уже закрывается. Старый core-python.azs (он сейчас на проде и на
# тестовом сервере) такого API не имеет — тогда, и только тогда, берём плагин отражением по закрытым полям хоста
# (ScriptApi.script → PyScript.platform → BukkitPlatform.context, как core_effects) и сами ставим Java-Runnable
# Entity.remove в планировщик сущности. Неудача — только предупреждение: дисплеи не сохраняются в мир
# (setPersistent(false)) и подчищаются при следующей загрузке системы.
# TODO: убрать ветку «старый core-python», когда на проде будет новый core-python.azs.

_新API = hasattr(amazake, "remove_entity") and hasattr(amazake, "plugin")
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
            amazake.log.warn("старый core-python без amazake.remove_entity(), и его контекст не найден (" + str(e)
                             + "): дисплеи чужих потоков при выгрузке не убрать сразу (они не сохраняются и "
                             "подчистятся при следующей загрузке)")
        return None


def プラグイン():
    if _新API:
        return amazake.plugin()
    c = _旧文脈()
    if c is None:
        return None
    try:
        return c.plugin()
    except BaseException:
        return None


_MH = java.type("java.lang.invoke.MethodHandles")
_MHP = java.type("java.lang.invoke.MethodHandleProxies")
_Runnable = getattr(java.type("java.lang.Runnable"), "class")
_ClassArray = java.type("java.lang.Class[]")
_EntityClass = getattr(java.type("org.bukkit.entity.Entity"), "class")


def _Java削除(d):
    """Java-Runnable d.remove() без Python (переживает закрытие контекста). Только для старого core-python."""
    m = _EntityClass.getMethod("remove", _ClassArray(0))
    mh = _MH.publicLookup().unreflect(m).bindTo(d)
    return _MHP.asInterfaceInstance(_Runnable, mh)


def 消す(d):
    """Убрать дисплей из любого потока (в том числе из @on_unload): поток владеет им — сразу, иначе — в потоке
    самой сущности (её планировщиком)."""
    if d is None:
        return
    if _新API:
        try:
            amazake.remove_entity(d)
        except BaseException as e:
            amazake.log.debug("remove_entity: " + str(e))
        return
    try:
        if amazake.owns(d):
            if d.isValid():
                d.remove()
            return
    except BaseException:
        pass
    p = プラグイン()
    if p is None:
        return
    try:
        d.getScheduler().execute(p, _Java削除(d), None, 1)
    except BaseException as e:
        amazake.log.debug("планировщик сущности: " + str(e))
