# 効果 — дисплеи эффектов палки (bou.py) на шейдере moonstaff, тот же конвейер, что у посоха core_magic
# (kyoutsuu.効果品 / 変形, mahoujin.生む / 後で消す): ItemDisplay с бумагой + item_model + цвет custom_model_data
# (R, 印G, 印B) — ключ A шейдера (R — параметр), яркость 15/15 (UV2 = 240 — часть метки), спрайты с подписью K24..K33
# в паке (nexo-items/tools/moonstaff_fx.py) — ключ B. Вращение, рост серпа, дым луча считает шейдер по GameTime.
#
# Потоки: дисплей рождается и меняется только в потоке своего региона (amazake.owns проверяется перед spawn);
# отложенные шаги — run_in_region того же места (дисплей больше не двигается) или задачей игрока (сфера у его глаз).
# Все живые дисплеи — в 表示 (UUID → дисплей): при выгрузке их убирает amazake.remove_entity (чистая Java-задача в
# потоке сущности — работает и после закрытия контекста Python). Дисплеи не сохраняются в мир (setPersistent(false));
# хвосты после сбоя (метка py_core_weapons:fx) убирает 掃除 при загрузке системы.

import math

import java
import amazake

import dougu
import ronri
from settei import f32

Location = java.type("org.bukkit.Location")
Color = java.type("org.bukkit.Color")
ItemStack = java.type("org.bukkit.inventory.ItemStack")
Material = java.type("org.bukkit.Material")
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

ItemDisplay = dougu.クラス("org.bukkit.entity.ItemDisplay")

#: Модели эффектов (nexo-items/pack/assets/azerus/items/stick_*.json).
斬撃 = "azerus:stick_slash"
球 = "azerus:stick_ember_orb"
光線 = "azerus:stick_ember_beam"

効果鍵 = "fx"          # py_core_weapons:fx = slash | orb | beam | impact
主鍵 = "owner"         # py_core_weapons:owner = UUID игрока

#: Живые дисплеи: str(UUID дисплея) → дисплей. Пишут потоки регионов (рождение/удаление), читает @on_unload.
表示 = {}


def 鍵(文字列):
    ns, _, v = 文字列.partition(":")
    return Key.key(ns, v) if v else Key.key("minecraft", ns)


def 効果品(モデル, R):
    """Предмет для дисплея: бумага + item_model + цвет custom_model_data (R, 印G, 印B) — ключ A шейдера."""
    item = ItemStack(Material.PAPER)
    item.setData(DataComponentTypes.ITEM_MODEL, 鍵(モデル))
    r = int(ronri.挟む(int(R), 0, ronri.R上限))
    cmd = CustomModelData.customModelData().addColor(Color.fromRGB(r, ronri.印G, ronri.印B)).build()
    item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd)
    return item


def 変形(x, y, z, sx, sy, sz, 回=0.0):
    """Transformation: перенос (x, y, z) в осях дисплея, левый поворот Rz(回)·Ry(π), масштаб (sx, sy, sz).
    Ry(π) (反転, как core_magic kyoutsuu.変形3): ItemDisplay сам рисует модель повёрнутой на 180° вокруг Y — с ним
    оси модели = оси дисплея (+Z — взгляд). 回 — наклон вокруг взгляда (радианы)."""
    q = Quaternionf().rotationZ(f32(回)).rotateY(f32(math.pi))
    return Transformation(Vector3f(f32(x), f32(y), f32(z)), q, Vector3f(f32(sx), f32(sy), f32(sz)), Quaternionf())


def 場所(world, x, y, z, yaw=0.0, pitch=0.0):
    return Location(world, float(x), float(y), float(z), f32(yaw), f32(pitch))


def 生む(at, item, 形, 視距離, 種類, 主, 看板=False, 移動=0):
    """Дисплей эффекта в месте at — только из потока его региона (иначе None). 看板 — billboard CENTER (сфера
    смотрит на каждого зрителя), иначе FIXED по yaw/pitch места. 移動 — плавность телепорта (тики)."""
    if dougu.終了済み[0] or not amazake.owns(at):
        return None

    def 準備(e):
        e.setPersistent(False)
        e.setItemStack(item)
        e.setItemDisplayTransform(ItemDisplayTransform.NONE)
        e.setBillboard(Billboard.CENTER if 看板 else Billboard.FIXED)
        e.setBrightness(Brightness(15, 15))      # обязательно: UV2 = (240, 240) — часть метки шейдера
        e.setViewRange(f32(視距離))
        e.setDisplayWidth(f32(0.0))              # без отсечения по рамке
        e.setDisplayHeight(f32(0.0))
        e.setShadowRadius(f32(0.0))
        e.setShadowStrength(f32(0.0))
        e.setInterpolationDelay(0)
        e.setInterpolationDuration(0)
        e.setTeleportDuration(int(移動))
        e.setTransformation(形)
        d = amazake.data(e)
        d.set_string(効果鍵, 種類)
        d.set_string(主鍵, 主)

    d = at.getWorld().spawn(at, ItemDisplay, 準備)
    表示[str(d.getUniqueId())] = d
    if dougu.終了済み[0]:          # выгрузка началась, пока дисплей рождался
        消す(d)
        return None
    return d


def 動かす(d, 遅れ, 長さ, 形, item=None):
    """Через 遅れ тиков (поток региона дисплея): интерполяция к 形 за 長さ тиков (и новый предмет-цвет)."""
    at = d.getLocation()

    def 実行():
        if d.isValid() and amazake.owns(d):
            if item is not None:
                d.setItemStack(item)
            d.setInterpolationDelay(0)
            d.setInterpolationDuration(int(長さ))
            d.setTransformation(形)

    if 遅れ <= 0:
        実行()
    else:
        amazake.run_in_region(at, 実行, delay=int(遅れ))


def 消す(d):
    """Убрать дисплей из любого потока: свой регион — сразу, иначе — Java-задачей в потоке сущности."""
    if d is None:
        return
    try:
        表示.pop(str(d.getUniqueId()), None)
    except BaseException:
        pass
    try:
        if amazake.owns(d):
            if d.isValid():
                d.remove()
            return
    except BaseException:
        pass
    try:
        amazake.remove_entity(d)
    except BaseException as e:
        amazake.log.debug("remove_entity: " + str(e))


def 後で消す(d, 遅れ):
    """Убрать дисплей через 遅れ тиков в потоке его региона (дисплей больше не двигается)."""
    at = d.getLocation()

    def 消去():
        消す(d)

    try:
        amazake.run_in_region(at, 消去, delay=max(1, int(遅れ)))
    except BaseException as e:
        amazake.log.debug("задача региона: " + str(e))
        消す(d)


def 数():
    return len(表示)


def 片付け():
    """@on_unload: все живые дисплеи — чистыми Java-задачами в их потоках (контекст Python сейчас закроется)."""
    for d in list(表示.values()):
        try:
            amazake.remove_entity(d)
        except BaseException as e:
            amazake.log.debug("уборка дисплея: " + str(e))
    表示.clear()


def 掃除(p):
    """Поток игрока: хвосты прошлых запусков (дисплеи с меткой py_core_weapons:fx) рядом — убрать. → сколько."""
    n = 0
    try:
        近く = p.getNearbyEntities(48.0, 48.0, 48.0)
    except BaseException:
        return 0
    for e in 近く:
        try:
            if (java.instanceof(e, ItemDisplayType) and amazake.owns(e) and amazake.data(e).has(効果鍵)
                    and str(e.getUniqueId()) not in 表示):
                e.remove()
                n += 1
        except BaseException:
            pass
    return n
