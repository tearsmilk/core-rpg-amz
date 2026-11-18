# 道具 — посох: узнать по предмету (PDC nexo:id), выдать (Nexo или запасной предмет без Nexo), вернуть компонент
# consumable (удержание ПКМ с анимацией лука; без него посох не «натягивается»).

import java
import amazake

import kyoutsuu
import settei
from kyoutsuu import ItemStack, Material, DataComponentTypes, Key
from settei import f32

Consumable = java.type("io.papermc.paper.datacomponent.item.Consumable")
ItemUseAnimation = java.type("io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation")
ItemLore = java.type("io.papermc.paper.datacomponent.item.ItemLore")
UseCooldown = java.type("io.papermc.paper.datacomponent.item.UseCooldown")
CONSUMABLE = DataComponentTypes.CONSUMABLE
USE_COOLDOWN = DataComponentTypes.USE_COOLDOWN

#: Своя группа перезарядки предмета (компонент use_cooldown). По ней player.setCooldown(группа, тики) рисует
#: «шторку» перезарядки только на посохе, и клиент сам не начинает удержание ПКМ, пока она идёт. Без своей группы
#: перезарядка легла бы на весь DIAMOND_SWORD — то есть на все азерус-оружия на этом материале.
冷却組 = "azerus:moon_staff"

# Вид запасного посоха (без Nexo) — как в nexo-items/items/azerus_weapons.yml.
名前 = "<gradient:#ffd36a:#ff4a5a:#7fd8ff>Посох Лунного Странника</gradient>"
説明 = [
    "<gray>Посох странника, что ходит",
    "<gray>лунными дорогами между мирами.",
    "",
    "<#7fd8ff>✦ <white>Луч</white><gray>: зажмите <white>ПКМ</white> на <white>3 с</white> — магический круг;",
    "<gray>   отпустите — <white>луч света</white> пронзит всех на пути:",
    "<gray>   <#ff6a6a>10 урона</#ff6a6a>, до <white>29 блоков</white>, перезарядка <white>3 с</white>.",
    "<#7fd8ff>✦ <white>Барьер</white><gray>: зажмите <white>ПКМ</white> на <white>1 с</white> — стена",
    "<gray>   из шестигранников на <white>10 с</white>, гасит лучи магии;",
    "<gray>   перезарядка <white>15 с</white>.",
    "<#ffa040>✦ <white>Огненный шар</white><gray>: зажмите <white>ПКМ</white> на <white>3 с</white> — в небе",
    "<gray>   над целью раскрывается огненный круг; отпустите —",
    "<gray>   <white>6 комет</white> обрушатся вокруг цели: <#ff6a6a>6 урона</#ff6a6a>",
    "<gray>   и поджог в радиусе <white>3 блоков</white>, перезарядка <white>12 с</white>.",
    "<#7fd8ff>✦ <white>Shift + ЛКМ</white><gray> — сменить заклинание.",
]
模型 = "azerus:moon_staff"


def 杖か(item):
    """Это посох? Читает только PDC (nexo:id)."""
    if item is None:
        return False
    try:
        if item.isEmpty():
            return False
        return amazake.data(item).get_string(kyoutsuu.NEXO_ID) == settei.今().杖.nexo_id
    except BaseException:
        return False


def 消費部品(秒=None):
    s = settei.今().杖.秒 if 秒 is None else 秒
    return (Consumable.consumable().consumeSeconds(f32(s)).animation(ItemUseAnimation.BOW)
            .sound(Key.key("minecraft", "intentionally_empty")).hasConsumeParticles(False).build())


def 冷却部品():
    return UseCooldown.useCooldown(f32(max(0.05, settei.今().冷却.秒))).cooldownGroup(kyoutsuu.鍵(冷却組)).build()


def _消費が正しいか(item):
    """consumable есть И с нашими значениями. Nexo, принявший секцию consumable, но не понявший какой-то ключ,
    дал бы компонент со значениями по умолчанию (1.6 с, анимация EAT): удержание кончалось бы на 32-м тике, и посох
    не стрелял бы никогда. Поэтому проверяем значения, а не только наличие."""
    if not item.hasData(CONSUMABLE):
        return False
    c = item.getData(CONSUMABLE)
    if c is None:
        return False
    return (float(c.consumeSeconds()) >= settei.今().杖.秒 - 0.01
            and c.animation().name() == "BOW"
            and c.consumeEffects().isEmpty())


def _冷却が正しいか(item):
    if not item.hasData(USE_COOLDOWN):
        return False
    u = item.getData(USE_COOLDOWN)
    g = None if u is None else u.cooldownGroup()
    return g is not None and g.asString() == 冷却組


def 壊れているか(item):
    """Посох с неверным/отсутствующим consumable или без своей группы перезарядки."""
    try:
        return not (_消費が正しいか(item) and _冷却が正しいか(item))
    except BaseException as e:
        amazake.log.debug("проверка компонентов посоха: " + str(e))
        return True


def 直すべきか(item):
    return settei.今().杖.修理 and 杖か(item) and 壊れているか(item)


def 直す(item):
    """Посоху с неверными компонентами — поставить наши consumable и use_cooldown (в этот же ItemStack).
    True — менял."""
    if not 直すべきか(item):
        return False
    _部品を付ける(item)
    return True


def _部品を付ける(item):
    if not _消費が正しいか(item):
        item.setData(CONSUMABLE, 消費部品())
    if not _冷却が正しいか(item):
        item.setData(USE_COOLDOWN, 冷却部品())


def 枠を直す(player, 枠):
    """Слот инвентаря 枠 (номер): посох без consumable — починить копию и положить обратно. Поток игрока."""
    inv = player.getInventory()
    item = inv.getItem(枠)
    if 直すべきか(item):
        copy = item.clone()
        直す(copy)
        inv.setItem(枠, copy)
        return True
    return False


def 全部直す(player):
    """Все слоты игрока (вход на сервер). Поток игрока."""
    inv = player.getInventory()
    n = 0
    for i in range(inv.getSize()):
        try:
            if 枠を直す(player, i):
                n += 1
        except BaseException:
            pass
    return n


def _予備():
    """Без Nexo (тестовый сервер): алмазный меч с item_model azerus:moon_staff, именем, описанием и nexo:id."""
    item = ItemStack(Material.DIAMOND_SWORD)
    item.setData(DataComponentTypes.ITEM_MODEL, kyoutsuu.鍵(模型))
    item.setData(DataComponentTypes.ITEM_NAME, amazake.item_text(名前))
    item.setData(DataComponentTypes.LORE, ItemLore.lore([amazake.item_text(s) for s in 説明]))
    d = amazake.data(item)
    d.set_string(kyoutsuu.NEXO_ID, settei.今().杖.nexo_id)
    d.set(kyoutsuu.杖鍵, 1)
    return item


def 作る():
    """Готовый посох: из Nexo (если есть), иначе запасной; consumable — всегда (если включён ремонт)."""
    nid = settei.今().杖.nexo_id
    item = None
    NexoItems = amazake.plugin_class("Nexo", "com.nexomc.nexo.api.NexoItems")
    if NexoItems is not None:
        try:
            b = NexoItems.itemFromId(nid)
            if b is not None:
                item = b.build()
            else:
                amazake.log.warn("в Nexo нет предмета", nid, "— выдаю запасной (azerus_weapons.yml залит?)")
        except BaseException as e:
            amazake.log.warn("Nexo не выдал", nid, ":", str(e), "— выдаю запасной")
    if item is None:
        item = _予備()
    try:
        if 壊れているか(item):
            _部品を付ける(item)
    except BaseException as e:
        amazake.log.warn("компоненты посоха: " + str(e))
    return item
