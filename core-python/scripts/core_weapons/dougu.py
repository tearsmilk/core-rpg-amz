# 道具 — классы Java, узнать оружие по предмету, ставить/снимать кадр замаха (строка custom_model_data).
# Перенос Java runtime/ItemTools. Ключи PDC те же: nexo:id (так Nexo метит свои предметы) и coreweapons:id
# (своя метка — для оружия не из Nexo); метка копий блоков дрожи — coreweapons:tremor.

import java
import amazake
import ronri

Bukkit = amazake.Bukkit
ItemStack = java.type("org.bukkit.inventory.ItemStack")
Material = java.type("org.bukkit.Material")
Location = java.type("org.bukkit.Location")
Particle = java.type("org.bukkit.Particle")
DustTransition = java.type("org.bukkit.Particle$DustTransition")
Color = java.type("org.bukkit.Color")
SoundCategory = java.type("org.bukkit.SoundCategory")
EquipmentSlot = java.type("org.bukkit.inventory.EquipmentSlot")
PersistentDataType = java.type("org.bukkit.persistence.PersistentDataType")
DataComponentTypes = java.type("io.papermc.paper.datacomponent.DataComponentTypes")
CustomModelData = java.type("io.papermc.paper.datacomponent.item.CustomModelData")
Key = java.type("net.kyori.adventure.key.Key")
ArrayList = java.type("java.util.ArrayList")
Player = java.type("org.bukkit.entity.Player")

NEXO_ID = amazake.key("nexo:id")
自分のID = amazake.key("coreweapons:id")
印 = amazake.key("coreweapons:tremor")
CMD = DataComponentTypes.CUSTOM_MODEL_DATA
文字型 = PersistentDataType.STRING

#: Выгрузка началась (ставит @on_unload первым делом): шаги задач, уже вошедшие в Python, ничего нового не создают.
終了済み = [False]
#: «Спячка»: Java-система core-weapons ещё включена — игроков не ведём, дрожь не запускаем (см. main._眠るべきか).
停止中 = [False]


def クラス(名前):
    """java.lang.Class по имени (для world.spawn(…, BlockDisplay.class, …) и т.п.)."""
    t = java.type(名前)
    try:
        return getattr(t, "class")
    except Exception:
        return t


def 武器ID(item):
    """Id оружия или None. Читает только PDC (без копии ItemMeta)."""
    if item is None or item.isEmpty():
        return None
    pdc = item.getPersistentDataContainer()
    v = pdc.get(NEXO_ID, 文字型)
    if v is None:
        v = pdc.get(自分のID, 文字型)
    return None if v is None else str(v)


def _Javaの列(列):
    out = ArrayList()
    for s in 列:
        out.add(s)
    return out


def 枠付き(item, 枠):
    """Копия предмета с кадром 枠 (None — без кадра); None, если менять нечего."""
    if item is None or item.isEmpty():
        return None
    cmd = item.getData(CMD)
    文字列 = [] if cmd is None else [str(s) for s in cmd.strings()]
    if 枠 is None and not ronri.枠あり(文字列):
        return None
    if 枠 is not None and 文字列 and 文字列[0] == 枠:
        return None
    次 = ronri.枠を外す(文字列) if 枠 is None else ronri.枠を付ける(文字列, 枠)
    copy = item.clone()
    if not 次 and (cmd is None or (len(cmd.floats()) == 0 and len(cmd.flags()) == 0 and len(cmd.colors()) == 0)):
        copy.resetData(CMD)   # как было: компонента не было
    else:
        b = CustomModelData.customModelData().addStrings(_Javaの列(次))
        if cmd is not None:
            b.addFloats(cmd.floats()).addFlags(cmd.flags()).addColors(cmd.colors())
        copy.setData(CMD, b.build())
    return copy


def 全部外す(inv):
    """Снять кадры со всех предметов инвентаря (поток игрока). Сколько предметов поправлено."""
    n = 0
    全部 = inv.getContents()
    for i in range(len(全部)):
        直した = 枠付き(全部[i], None)
        if 直した is not None:
            inv.setItem(i, 直した)
            n += 1
    return n


def 粉(色1, 色2, 大きさ):
    return DustTransition(Color.fromRGB(色1), Color.fromRGB(色2), 大きさ)


# --------------------------------------------------------------------------------- после выгрузки
# При выгрузке контекст Python закрывается сразу после @on_unload, поэтому то, что надо сделать в чужом потоке
# (снять кадр с предмета игрока, удалить копию блока в другом регионе), отдаётся планировщику как чистый Java-объект:
# java.beans.Statement (готовый вызов метода) + java.beans.EventHandler (Runnable, который его выполняет).
# Новый core-python ставит такие задачи сам: amazake.run_java_on_entity (при остановке сервера — сразу) и даёт
# amazake.plugin(). Старый core-python.azs (до его передеплоя) этого API не имеет — тогда, и только тогда, Plugin
# достаётся отражением через закрытые поля хоста (ScriptApi.script → PyScript.platform → BukkitPlatform.context),
# а при неудаче берётся первый включённый плагин. TODO: убрать ветки «not _新API», когда на проде новый core-python.

_新API = hasattr(amazake, "plugin") and hasattr(amazake, "run_java_on_entity")
_プラグイン = [None]


def _旧プラグイン():
    """Только для старого core-python: Plugin core-python через закрытые поля хоста, или None."""
    try:
        h = amazake._host
        f = h.getClass().getDeclaredField("script")
        f.setAccessible(True)
        s = f.get(h)
        f = s.getClass().getDeclaredField("platform")
        f.setAccessible(True)
        pl = f.get(s)
        f = pl.getClass().getDeclaredField("context")
        f.setAccessible(True)
        return f.get(pl).plugin()
    except BaseException as e:   # исключения Java (ForeignException) — не Exception
        amazake.log.warn("core_weapons: старый core-python без amazake.plugin(), и Plugin через поля хоста не получен ("
                         + str(e) + ") — задачи уборки при выгрузке пойдут от первого включённого плагина")
        return None


def プラグイン():
    if _新API:
        return amazake.plugin()
    if _プラグイン[0] is not None:
        return _プラグイン[0]
    p = _旧プラグイン()
    if p is None:
        for x in Bukkit.getPluginManager().getPlugins():
            if x.isEnabled():
                p = x
                break
    _プラグイン[0] = p
    return p


def Java実行体(対象, メソッド, 引数=()):
    """Runnable без Python: при run() вызывает 対象.メソッド(*引数). Работает и после закрытия контекста."""
    Statement = java.type("java.beans.Statement")
    EventHandler = java.type("java.beans.EventHandler")
    try:
        arr = java.type("java.lang.Object[]")(len(引数))
        for i, a in enumerate(引数):
            arr[i] = a
    except Exception:
        arr = list(引数)   # хост сам превратит список в Object[]
    文 = Statement(対象, メソッド, arr)
    return EventHandler.create(クラス("java.lang.Runnable"), 文, "execute")


def 後で実体に(entity, 対象, メソッド, 引数=()):
    """Выполнить 対象.メソッド(*引数) в потоке сущности entity, без Python (сразу, если поток ею владеет или сервер
    останавливается). True — поставлено / выполнено."""
    if _新API:
        return amazake.run_java_on_entity(entity, Java実行体(対象, メソッド, 引数))
    pl = プラグイン()
    if pl is None:
        return False
    return bool(entity.getScheduler().execute(pl, Java実行体(対象, メソッド, 引数), None, 1))
