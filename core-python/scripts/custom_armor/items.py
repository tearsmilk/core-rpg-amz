# カスタム防具 — アイテム（предметы брони）
#
# Предметы — из Nexo (вид, модель, слой брони на игроке), а характеристики этот модуль вешает на предмет сам:
# модификаторы атрибутов предмета (броня, твёрдость, отбрасывание, здоровье, скорость) — как у ванильной брони,
# работают без всякого кода. Метка py_custom_armor:stats хранит, по какой версии характеристик предмет собран:
# если sets.yml поменяли — при надевании предмет чинится сам.

import json
import java
import amazake

# --- Java の型 -------------------------------------------------------------------------------
Material = java.type("org.bukkit.Material")
ItemStack = java.type("org.bukkit.inventory.ItemStack")
EquipmentSlot = java.type("org.bukkit.inventory.EquipmentSlot")
DataComponentTypes = java.type("io.papermc.paper.datacomponent.DataComponentTypes")
Equippable = java.type("io.papermc.paper.datacomponent.item.Equippable")
ItemLore = java.type("io.papermc.paper.datacomponent.item.ItemLore")
Key = java.type("net.kyori.adventure.key.Key")

# 部位: имя части → (суффикс материала, слот экипировки, группа слотов модификатора)
部位表 = {
    "helmet": ("HELMET", "HEAD", "head"),
    "chestplate": ("CHESTPLATE", "CHEST", "chest"),
    "leggings": ("LEGGINGS", "LEGS", "legs"),
    "boots": ("BOOTS", "FEET", "feet"),
}
部位一覧 = list(部位表.keys())

# 能力の名前: ключ в sets.yml → (атрибут, операция)
能力表 = {
    "armor": ("armor", "add"),
    "toughness": ("armor_toughness", "add"),
    "knockback": ("knockback_resistance", "add"),
    "health": ("max_health", "add"),
    "speed": ("movement_speed", "multiply_base"),
}

能力キー = "stats"          # py_custom_armor:stats — версия характеристик на предмете
NEXOキー = "nexo:id"        # id предмета Nexo

# 索引: nexo-id → (セットID, 部位名); заполняется при загрузке sets.yml
索引 = {}
# 全セット: id комплекта → его dict из sets.yml
全セット = {}


def nexoのID(セットID, 部位名):
    """id предмета в Nexo: azerus_<комплект>_<часть>."""
    return "azerus_" + セットID + "_" + 部位名


def セットを読み込む():
    """sets.yml → 全セット и 索引. Возвращает число комплектов."""
    設定 = amazake.config("sets.yml")
    新セット = dict(設定.get("sets", {}))
    新索引 = {}
    for セットID, セット in 新セット.items():
        for 部位名 in セット.get("pieces", {}):
            if 部位名 not in 部位表:
                amazake.log.warn("sets.yml: у комплекта", セットID, "неизвестная часть", 部位名)
                continue
            新索引[nexoのID(セットID, 部位名)] = (セットID, 部位名)
    全セット.clear()
    全セット.update(新セット)
    索引.clear()
    索引.update(新索引)
    return len(全セット)


def 部品を判定(item):
    """Наш ли это предмет брони → (セットID, 部位名) или None."""
    if item is None or item.isEmpty():
        return None
    nid = amazake.data(item).get_string(NEXOキー)
    return 索引.get(nid) if nid else None


def 能力の指紋(セットID, 部位名):
    """Строка-версия характеристик части: меняется, если в sets.yml поменяли её числа."""
    部位 = 全セット[セットID]["pieces"][部位名]
    数値 = {k: 部位[k] for k in sorted(能力表) if k in 部位}
    return json.dumps([セットID, 部位名, 数値], sort_keys=True, separators=(",", ":"))


def 能力が古い(item):
    """У нашего предмета нет характеристик или они от старой версии sets.yml."""
    判定 = 部品を判定(item)
    if 判定 is None:
        return False
    return amazake.data(item).get_string(能力キー) != 能力の指紋(*判定)


def 能力を付ける(item, セットID, 部位名):
    """Вешает на предмет модификаторы атрибутов части (заменяя любые прежние) и метку версии."""
    部位 = 全セット[セットID]["pieces"][部位名]
    _, _, 枠 = 部位表[部位名]
    meta = item.getItemMeta()
    meta.setAttributeModifiers(None)        # предметные модификаторы заменяют ванильные значения материала
    for 名前, (属性, 演算) in 能力表.items():
        値 = 部位.get(名前)
        if not 値:
            continue
        meta.addAttributeModifier(
            amazake.attribute(属性),
            amazake.modifier(セットID + "_" + 部位名 + "_" + 名前, float(値), 演算, 枠),
        )
    item.setItemMeta(meta)
    amazake.data(item).set_string(能力キー, 能力の指紋(セットID, 部位名))
    return item


def _予備のアイテム(セットID, 部位名):
    """Без Nexo (тестовый сервер): тот же предмет вручную — материал, имя, описание, equippable с asset_id
    azerus:<комплект> и метка nexo:id. Иконка — ванильная; слой брони виден, только если в паке есть
    equipment-модель azerus:<комплект> (её генерирует Nexo)."""
    セット = 全セット[セットID]
    部位 = セット["pieces"][部位名]
    素材名, 枠名, _ = 部位表[部位名]
    素材 = Material.matchMaterial(セット.get("material", "IRON") + "_" + 素材名) or Material.matchMaterial("IRON_" + 素材名)
    item = ItemStack(素材)
    nid = nexoのID(セットID, 部位名)
    item.setData(DataComponentTypes.ITEM_NAME, amazake.item_text(部位.get("name", nid)))
    行 = [amazake.item_text(s) for s in セット.get("lore", [])]
    if 行:
        item.setData(DataComponentTypes.LORE, ItemLore.lore(行))
    装備 = Equippable.equippable(EquipmentSlot.valueOf(枠名)).assetId(Key.key("azerus", セットID)).build()
    item.setData(DataComponentTypes.EQUIPPABLE, 装備)
    amazake.data(item).set_string(NEXOキー, nid)
    return item


def アイテムを作る(セットID, 部位名):
    """Готовый предмет части: из Nexo (если есть) + характеристики."""
    nid = nexoのID(セットID, 部位名)
    item = None
    NexoItems = amazake.plugin_class("Nexo", "com.nexomc.nexo.api.NexoItems")
    if NexoItems is not None:
        builder = NexoItems.itemFromId(nid)
        if builder is not None:
            item = builder.build()
        else:
            amazake.log.warn("в Nexo нет предмета", nid, "— выдаю запасной (nexo-items/items/azerus_armor.yml залит?)")
    if item is None:
        item = _予備のアイテム(セットID, 部位名)
    return 能力を付ける(item, セットID, 部位名)
