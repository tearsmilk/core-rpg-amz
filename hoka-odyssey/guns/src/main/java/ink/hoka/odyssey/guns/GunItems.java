package ink.hoka.odyssey.guns;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EquipmentSlotGroup;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.item.ItemAnimation;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.AttributeList;
import net.minestom.server.item.component.Consumable;
import net.minestom.server.item.component.SwingAnimation;
import net.minestom.server.item.component.TooltipDisplay;
import net.minestom.server.item.component.UseEffects;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.tag.Tag;

/**
 * Предметы оружия и патронов. Вид — из hoitems (модель TACZ в руке и иконка), поведение — компоненты поверх:
 * <ul>
 *   <li>CONSUMABLE (час, без звука и частиц) — ПКМ держится как «использование»: сервер получает начало/конец
 *       (прицел), а клиентский модуль сводит оружие к глазу. Анимация BOW: у ванильного клиента в прицеле поза лука —
 *       обе руки вперёд, а оружие в модели hoitems лежит вдоль руки (display thirdperson, как у TACZ), то есть
 *       стволом вперёд. Игроку с модулем «использование» в прицеле ставит сервер (ПКМ модуль забирает сам);</li>
 *   <li>USE_EFFECTS — в прицеле нельзя бежать и скорость ×aim-speed (как −20% в TACZ);</li>
 *   <li>дальность взаимодействия −10 (только в основной руке, в подсказке скрыто) — ЛКМ не ломает блоки и не бьёт,
 *       ПКМ не открывает двери: всё это делать на клиенте нечем, поэтому так;</li>
 *   <li>без взмаха руки, стопка 1, метки hoka_gun (id оружия), hoka_gun_sn (серийный номер), hoka_gun_ammo.</li>
 * </ul>
 */
final class GunItems {
    static final Tag<String> GUN = Tag.String("hoka_gun");
    static final Tag<String> SERIAL = Tag.String("hoka_gun_sn");
    static final Tag<Integer> AMMO = Tag.Integer("hoka_gun_ammo");
    static final Tag<String> AMMO_KIND = Tag.String("hoka_ammo");

    private final Links links;

    GunItems(Links links) {
        this.links = links;
    }

    /** Новое оружие: полный магазин (без патрона в патроннике). */
    ItemStack gun(GunDef g) {
        ItemStack base = links.item(g.item(), 1);
        if (base == null) {
            Material m = Material.fromKey(g.material());
            if (m == null) {
                m = Material.IRON_HORSE_ARMOR;
            }
            base = ItemStack.of(m).with(DataComponents.CUSTOM_NAME,
                    Component.text(g.name(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (String l : g.lore()) {
                lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            if (!lore.isEmpty()) {
                base = base.with(DataComponents.LORE, lore);
            }
        }
        return decorate(base, g, UUID.randomUUID().toString().substring(0, 8), g.magazine());
    }

    /** Повесить на предмет поведение оружия (и метки). */
    static ItemStack decorate(ItemStack s, GunDef g, String serial, int ammo) {
        AttributeList attrs = new AttributeList(List.of(
                new AttributeList.Modifier(Attribute.BLOCK_INTERACTION_RANGE,
                        new AttributeModifier("hoka:gun_reach_block", -10, AttributeOperation.ADD_VALUE),
                        EquipmentSlotGroup.MAIN_HAND, AttributeList.Display.Hidden.INSTANCE),
                new AttributeList.Modifier(Attribute.ENTITY_INTERACTION_RANGE,
                        new AttributeModifier("hoka:gun_reach_entity", -10, AttributeOperation.ADD_VALUE),
                        EquipmentSlotGroup.MAIN_HAND, AttributeList.Display.Hidden.INSTANCE)));
        return s.with(DataComponents.CONSUMABLE, new Consumable(3600f, ItemAnimation.BOW,
                        SoundEvent.INTENTIONALLY_EMPTY, false, List.of()))
                .with(DataComponents.USE_EFFECTS, new UseEffects(false, false, (float) g.aimSpeed()))
                .with(DataComponents.ATTRIBUTE_MODIFIERS, attrs)
                .with(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(false, Set.of(DataComponents.ATTRIBUTE_MODIFIERS)))
                .with(DataComponents.SWING_ANIMATION, new SwingAnimation(SwingAnimation.Type.NONE, 1))
                .with(DataComponents.MAX_STACK_SIZE, 1)
                .withAmount(1)
                .withTag(GUN, g.id())
                .withTag(SERIAL, serial)
                .withTag(AMMO, Math.max(0, ammo));
    }

    /** id оружия на предмете (метка hoka_gun) или null. */
    static String gunId(ItemStack s) {
        return s == null || s.isAir() ? null : s.getTag(GUN);
    }

    /** Предмет — «голое» оружие из /hoitem give (метки hoka_gun ещё нет): вернуть описание или null. */
    GunDef bareGun(ItemStack s, Iterable<GunDef> guns) {
        if (s == null || s.isAir() || s.getTag(GUN) != null) {
            return null;
        }
        String id = links.idOf(s);
        if (id == null) {
            return null;
        }
        for (GunDef g : guns) {
            if (id.equals(g.item())) {
                return g;
            }
        }
        return null;
    }

    /** Патроны для оружия g. */
    ItemStack ammo(GunDef g, int count) {
        ItemStack s = links.item(g.ammoItem(), count);
        if (s == null) {
            Material m = Material.fromKey(g.ammoMaterial());
            if (m == null) {
                m = Material.GOLD_NUGGET;
            }
            s = ItemStack.of(m, count).with(DataComponents.CUSTOM_NAME,
                    Component.text(g.ammoName(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        return s.withTag(AMMO_KIND, g.caliber());
    }

    /** Патроны ли это для g: предмет hoitems с id ammo-item или метка hoka_ammo = calibre. */
    boolean isAmmo(ItemStack s, GunDef g) {
        if (s == null || s.isAir()) {
            return false;
        }
        if (g.caliber().equals(s.getTag(AMMO_KIND))) {
            return true;
        }
        String id = links.idOf(s);
        return id != null && id.equals(g.ammoItem());
    }
}
