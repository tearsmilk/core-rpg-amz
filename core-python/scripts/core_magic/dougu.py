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
CONSUMABLE = DataComponentTypes.CONSUMABLE

# Вид запасного посоха (без Nexo) — как в nexo-items/items/azerus_weapons.yml.
名前 = "<gradient:#ffd36a:#ff4a5a:#7fd8ff>Посох Лунного Странника</gradient>"
説明 = [
    "<gray>Посох странника, что ходит",
    "<gray>лунными дорогами между мирами.",
    "",
    "<#7fd8ff>✦ <gray>Зажмите <white>ПКМ</white> на <white>3 с</white> — откроется магический круг.",
    "<#7fd8ff>✦ <gray>Отпустите — <white>луч света</white> пронзит всех на пути:",
    "<gray>   <#ff6a6a>10 урона</#ff6a6a>, до <white>29 блоков</white>.",
    "<#7fd8ff>✦ <gray>Перезарядка: <white>3 с</white>.",
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
    except Exception:
        return False


def 消費部品(秒=None):
    s = settei.今().杖.秒 if 秒 is None else 秒
    return (Consumable.consumable().consumeSeconds(f32(s)).animation(ItemUseAnimation.BOW)
            .sound(Key.key("minecraft", "intentionally_empty")).hasConsumeParticles(False).build())


def 直すべきか(item):
    return settei.今().杖.修理 and 杖か(item) and not item.hasData(CONSUMABLE)


def 直す(item):
    """Посоху без consumable — вернуть компонент (в этот же ItemStack). True — менял."""
    if not 直すべきか(item):
        return False
    item.setData(CONSUMABLE, 消費部品())
    return True


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
        except Exception:
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
        except Exception as e:
            amazake.log.warn("Nexo не выдал", nid, ":", str(e), "— выдаю запасной")
    if item is None:
        item = _予備()
    if not item.hasData(CONSUMABLE):
        item.setData(CONSUMABLE, 消費部品())
    return item
