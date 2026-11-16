# 品 — бинт, «чем лечится» у предмета, сведения о предмете для проклятья (порт item/Items.java).

import java
import amazake

import logic
from common import Material, PersistentDataType, NamespacedKey, NK, 包帯鍵

ItemStack = java.type("org.bukkit.inventory.ItemStack")
PotionMeta = java.type("org.bukkit.inventory.meta.PotionMeta")
PotionEffectType = java.type("org.bukkit.potion.PotionEffectType")
Tag = java.type("org.bukkit.Tag")
DataComponentTypes = java.type("io.papermc.paper.datacomponent.DataComponentTypes")


def _属(型, 名):
    try:
        return getattr(型, 名)
    except Exception:
        return None


# Теги и компоненты снаряжения (то, чего нет в этой версии сервера, пропускается).
_道具タグ = [t for t in (_属(Tag, n) for n in (
    "ITEMS_SWORDS", "ITEMS_AXES", "ITEMS_PICKAXES", "ITEMS_SHOVELS", "ITEMS_HOES", "ITEMS_SPEARS",
    "ITEMS_HEAD_ARMOR", "ITEMS_CHEST_ARMOR", "ITEMS_LEG_ARMOR", "ITEMS_FOOT_ARMOR", "ITEMS_ENCHANTABLE_WEAPON",
    "ITEMS_ENCHANTABLE_DURABILITY")) if t is not None]
_容器タグ = [t for t in (_属(Tag, n) for n in ("ITEMS_SHULKER_BOXES", "ITEMS_BUNDLES")) if t is not None]
_道具部品 = [c for c in (_属(DataComponentTypes, n) for n in ("TOOL", "WEAPON", "BLOCKS_ATTACKS")) if c is not None]
_袋の中身 = _属(DataComponentTypes, "BUNDLE_CONTENTS")
_INSTANT_HEALTH = PotionEffectType.INSTANT_HEALTH


# --- 包帯 (бинт) -------------------------------------------------------------------------------------------------------

def 包帯(s, 数):
    """Стопка бинтов (не больше стопки материала)."""
    m = Material.matchMaterial(s.包帯.素材)
    if m is None or not m.isItem() or m.isAir():
        m = Material.PAPER
    item = ItemStack.of(m, max(1, min(m.getMaxStackSize(), 数)))
    meta = item.getItemMeta()
    meta.itemName(amazake.item_text(s.包帯.名前))
    meta.lore([amazake.item_text(l) for l in s.包帯.説明])
    # Метка coreeffects:bandage — байт 1 (BOOLEAN хранится байтом; как PersistentDataType.BYTE у Java-системы).
    meta.getPersistentDataContainer().set(NK[包帯鍵], PersistentDataType.BOOLEAN, True)
    model = s.包帯.モデル
    if model and model.strip():
        k = NamespacedKey.fromString(model.strip())
        if k is not None:
            meta.setItemModel(k)
    item.setItemMeta(meta)
    return item


def 包帯か(item):
    if item is None or item.isEmpty() or not item.hasItemMeta():
        return False
    return item.getPersistentDataContainer().has(NK[包帯鍵])


# --- чем лечится -----------------------------------------------------------------------------------------------------

def 治癒ポーションか(item):
    if item is None:
        return False
    meta = item.getItemMeta()
    if meta is None or not java.instanceof(meta, PotionMeta):
        return False
    base = meta.getBasePotionType()
    if base is not None and str(base.name()) in ("HEALING", "STRONG_HEALING"):
        return True
    for e in meta.getCustomEffects():
        if e.getType().equals(_INSTANT_HEALTH):
            return True
    return False


def 印(item):
    """Метки предмета для cured-by / quenched-by: имя материала (milk-bucket, ...) и синонимы."""
    出 = []
    if item is None or item.isEmpty():
        return 出
    名 = str(item.getType().name())
    出.append(名.lower().replace("_", "-"))
    if 名 == "MILK_BUCKET":
        出.append("milk")
    elif 名 == "HONEY_BOTTLE":
        出.append("honey")
    elif 名 in ("GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE"):
        出.append("golden-apple")
    if 治癒ポーションか(item):
        出.append("healing-potion")
    if 包帯か(item):
        出.append("bandage")
    return 出


# --- для проклятья ---------------------------------------------------------------------------------------------------

def 事実(item, nbt付き=True):
    """logic.品の事実 о предмете (поток владельца инвентаря). nbt付き=False — без строки NBT (она дорогая)."""
    m = item.getType()
    名 = str(m.name())
    最大 = item.getData(DataComponentTypes.MAX_DAMAGE)
    属性 = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS)
    道具 = any(t.isTagged(m) for t in _道具タグ) or any(item.hasData(c) for c in _道具部品)
    容器 = any(t.isTagged(m) for t in _容器タグ) or (_袋の中身 is not None and item.hasData(_袋の中身))
    モデル = item.getData(DataComponentTypes.ITEM_MODEL)
    独自 = item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA) or (モデル is not None and not モデル.equals(m.getKey()))
    nbt = ""
    if nbt付き and item.hasItemMeta():
        try:
            nbt = str(item.getItemMeta().getAsComponentString())
        except Exception:
            nbt = ""
    return logic.品の事実(
        名, int(最大) if 最大 is not None else int(m.getMaxDurability()),
        item.hasData(DataComponentTypes.EQUIPPABLE), 属性 is not None and not 属性.modifiers().isEmpty(),
        item.hasData(DataComponentTypes.DEATH_PROTECTION), 道具, 容器,
        item.hasData(DataComponentTypes.CUSTOM_NAME), 独自, nbt)


def 呪いが取れる(item, c, 包帯除外=True):
    """Можно ли проклятью взять этот предмет (CurseFilter; бинт — нет, кроме левой руки: как у Java-системы,
    там бинт не исключался). Сначала дешёвая проверка по материалу."""
    if item is None or item.isEmpty():
        return False
    if logic.素材で守られる(str(item.getType().name()), c.保護素材):
        return False
    if 包帯除外 and 包帯か(item):
        return False
    # Сначала без NBT (дешёвые проверки); полную строку NBT (getAsComponentString) — только если всё прочее прошло.
    if not logic.普通の品(事実(item, False), c.保護素材, (), c.保護名前, c.保護モデル):
        return False
    if not any(c.保護NBT):
        return True
    return logic.普通の品(事実(item), c.保護素材, c.保護NBT, c.保護名前, c.保護モデル)
