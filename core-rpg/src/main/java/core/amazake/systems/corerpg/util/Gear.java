package core.amazake.systems.corerpg.util;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Классификация предметов, общая для заточки, печатей и боя.
 *
 * <p>Грейд по материалу (единый для всех модулей, как решено в артефакте): незерит — A, алмаз и золото — B,
 * остальное (кожа, дерево, камень, медь, железо, кольчуга, черепаха, лук, арбалет, трезубец, булава, щит) — C.</p>
 */
public final class Gear {

    /** Грейд предмета. */
    public enum Grade { C, B, A }

    /**
     * Вид предмета. Заточка различает категории armor / weapon_1h / weapon_2h; печати — WEAPON / ARMOR / SHIELD.
     */
    public enum Kind {
        ARMOR("armor", "ARMOR"),
        WEAPON_1H("weapon_1h", "WEAPON"),
        WEAPON_2H("weapon_2h", "WEAPON"),
        RANGED("weapon_1h", "WEAPON"),
        SHIELD("weapon_1h", "SHIELD"),
        TOOL("weapon_1h", null);

        private final String sharpenCategory;
        private final String sealType;

        Kind(final String sharpenCategory, final String sealType) {
            this.sharpenCategory = sharpenCategory;
            this.sealType = sealType;
        }

        /** Категория таблиц шансов заточки: armor, weapon_1h, weapon_2h. */
        public String sharpenCategory() {
            return this.sharpenCategory;
        }

        /** Тип для печатей: WEAPON, ARMOR, SHIELD; null — печать не накладывается (инструменты). */
        public String sealType() {
            return this.sealType;
        }

        /** Бонус заточки — к атаке (иначе к защите). Щит и броня — защита. */
        public boolean attacks() {
            return this == WEAPON_1H || this == WEAPON_2H || this == RANGED || this == TOOL;
        }
    }

    private Gear() {
    }

    /** Вид предмета или null, если его нельзя точить и запечатывать. */
    public static Kind kind(final ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return kind(item.getType());
    }

    public static Kind kind(final Material material) {
        final String n = material.name();
        if (material == Material.SHIELD) {
            return Kind.SHIELD;
        }
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
            || material == Material.ELYTRA) {
            return Kind.ARMOR;
        }
        if (material == Material.BOW || material == Material.CROSSBOW) {
            return Kind.RANGED;
        }
        // Двуручное (решение артефакта): булава, трезубец, копья, топоры из алмаза и незерита.
        if (material == Material.MACE || material == Material.TRIDENT || n.endsWith("_SPEAR")
            || n.equals("DIAMOND_AXE") || n.equals("NETHERITE_AXE")) {
            return Kind.WEAPON_2H;
        }
        if (n.endsWith("_SWORD") || n.endsWith("_AXE")) {
            return Kind.WEAPON_1H;
        }
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")) {
            return Kind.TOOL;
        }
        return null;
    }

    public static Grade grade(final ItemStack item) {
        return item == null ? Grade.C : grade(item.getType());
    }

    public static Grade grade(final Material material) {
        final String n = material.name().toUpperCase(Locale.ROOT);
        if (n.startsWith("NETHERITE_")) {
            return Grade.A;
        }
        if (n.startsWith("DIAMOND_") || n.startsWith("GOLDEN_")) {
            return Grade.B;
        }
        return Grade.C;
    }
}
