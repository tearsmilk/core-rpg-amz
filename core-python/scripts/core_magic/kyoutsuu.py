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
効果鍵 = "fx"               # py_core_magic:fx = circle | beam
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
    except Exception:
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


def 変形(前, 下, sx, sy, sz):
    """Transformation: перенос (0, −下, 前) в осях дисплея (+Z — взгляд), без поворотов, масштаб (sx, sy, sz)."""
    return Transformation(Vector3f(f32(0.0), f32(-下), f32(前)), Quaternionf(),
                          Vector3f(f32(sx), f32(sy), f32(sz)), Quaternionf())


# --------------------------------------------------------------------------------------------- звук и частицы

def 鳴らす(world, 場所, 音列, 足す=0.0):
    """Список звуков settei.音 у места (категория PLAYERS). 足す — прибавка к высоте (гул растёт с зарядом)."""
    for s in 音列:
        try:
            world.playSound(場所, s.鍵, SoundCategory.PLAYERS, s.音量,
                            s.高さ if not 足す else f32(keisan.挟む(s.高さ + 足す, 0.5, 2.0)))
        except Exception as e:
            amazake.log.debug("звук " + s.鍵 + ": " + str(e))


_粒型 = {}


def 粒型(名前):
    t = _粒型.get(名前)
    if t is None:
        try:
            t = Particle.valueOf(名前)
        except Exception:
            amazake.log.warn("частица " + 名前 + " не найдена — заменена на END_ROD")
            t = Particle.END_ROD
        _粒型[名前] = t
    return t


def 散らす(world, 粒, x, y, z):
    if 粒.数 <= 0:
        return
    world.spawnParticle(粒型(粒.種類), float(x), float(y), float(z), int(粒.数),
                        float(粒.広がり), float(粒.広がり), float(粒.広がり), float(粒.速さ))


# --------------------------------------------------------------------------------------------- контекст core-python
# API amazake не отдаёт объект плагина (нужен для планировщика региона в @on_unload, когда Python-задачи уже
# отменены). Берём через отражение, как core_effects: ScriptApi.script → PyScript.platform → BukkitPlatform.context.
# Неудача — только предупреждение: дисплеи всё равно не сохраняются в мир (setPersistent(false)) и подчищаются
# при следующей загрузке системы.

_文脈 = []
_文脈警告 = [False]


def 文脈():
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
    except Exception as e:
        if not _文脈警告[0]:
            _文脈警告[0] = True
            amazake.log.warn("не найден контекст core-python (" + str(e) + "): дисплеи чужих регионов при выгрузке "
                             "не убрать сразу (они не сохраняются и подчистятся при следующей загрузке)")
        return None


def プラグイン():
    c = 文脈()
    if c is None:
        return None
    try:
        return c.plugin()
    except Exception:
        return None


# --------------------------------------------------------------------------------------------- Java-задачи без Python

_MH = java.type("java.lang.invoke.MethodHandles")
_MHP = java.type("java.lang.invoke.MethodHandleProxies")
_ArrayList = java.type("java.util.ArrayList")
_Void = java.type("java.lang.Void")
_Runnable = getattr(java.type("java.lang.Runnable"), "class")
_ObjectArray = java.type("java.lang.Object[]")
_ClassArray = java.type("java.lang.Class[]")


def _クラス(名):
    return amazake._host.findClass(名)


def ジャバ処理(型名, メソッド, 引数型名, 対象, *引数):
    """Runnable: 対象.メソッド(*引数) — только Java (публичный метод класса/интерфейса 型名; результат
    отбрасывается). Работает и после закрытия контекста Python."""
    型s = _ArrayList()
    for n in 引数型名:
        型s.add(_クラス(n))
    型 = _クラス(型名) if isinstance(型名, str) else 型名
    m = 型.getMethod(メソッド, 型s.toArray(_ClassArray(0)))
    mh = _MH.publicLookup().unreflect(m)
    if not m.getReturnType().equals(_Void.TYPE):
        mh = _MH.dropReturn(mh)
    値 = _ObjectArray(1 + len(引数))
    値[0] = 対象
    for i, v in enumerate(引数):
        値[i + 1] = v
    mh = _MH.insertArguments(mh, 0, 値)
    return _MHP.asInterfaceInstance(_Runnable, mh)


def ジャバで地域へ(場所, 処理):
    """Java-Runnable в потоке региона места (Bukkit.getRegionScheduler().execute). False — не вышло."""
    p = プラグイン()
    if p is None or 場所 is None:
        return False
    try:
        Bukkit.getRegionScheduler().execute(p, 場所, 処理)
        return True
    except Exception as e:
        amazake.log.debug("планировщик региона: " + str(e))
        return False


def 消す(d, 場所=None, Javaで=False):
    """Убрать дисплей из любого потока: свой регион — сразу; иначе задачей региона его места (場所 — последнее
    известное место; без него — d.getLocation()). Javaで — без Python (выгрузка)."""
    if d is None:
        return
    try:
        if amazake.owns(d):
            if d.isValid():
                d.remove()
            return
    except Exception:
        pass
    at = 場所
    if at is None:
        try:
            at = d.getLocation()
        except Exception:
            return
    if Javaで:
        ジャバで地域へ(at, ジャバ処理("org.bukkit.entity.Entity", "remove", [], d))
        return

    def 地域で():
        if d.isValid():
            d.remove()

    try:
        amazake.run_in_region(at, 地域で)
    except Exception:
        ジャバで地域へ(at, ジャバ処理("org.bukkit.entity.Entity", "remove", [], d))
