package core.amazake.systems.coreeffects.logic;

import java.util.List;
import java.util.Set;

/**
 * Что может забрать проклятье: только обычные предметы. Никогда — оружие, броня, инструменты и прочее снаряжение,
 * тотемы, контейнеры (шалкеры, мешки), предметы плагинов (по меткам в NBT) и всё из {@code protect-materials}.
 */
public final class CurseFilter {

    /** Снаряжение, которое не ломается и не во всех тегах. */
    private static final Set<String> EQUIPMENT = Set.of(
        "TOTEM_OF_UNDYING", "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "ELYTRA", "SHEARS", "FISHING_ROD",
        "FLINT_AND_STEEL", "BRUSH", "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK", "SPYGLASS", "COMPASS",
        "RECOVERY_COMPASS", "CLOCK", "LEAD", "SADDLE", "TURTLE_HELMET", "WOLF_ARMOR", "FILLED_MAP", "WRITABLE_BOOK",
        "WRITTEN_BOOK", "KNOWLEDGE_BOOK", "BUNDLE", "GOAT_HORN");

    /** Окончания имён материалов снаряжения (на случай, если тегов нет — тесты, будущие предметы). */
    private static final List<String> SUFFIXES = List.of(
        "_SWORD", "_AXE", "_PICKAXE", "_SHOVEL", "_HOE", "_SPEAR", "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS",
        "_HORSE_ARMOR", "_NAUTILUS_ARMOR", "_HARNESS", "_SHULKER_BOX", "_BUNDLE");

    private CurseFilter() {
    }

    /** Можно ли проклятью забрать этот предмет. */
    public static boolean ordinary(final ItemFacts f, final Set<String> protectMaterials, final List<String> protectNbt,
                                   final boolean protectNamed, final boolean protectCustomModel) {
        if (f == null || f.material() == null || f.material().isEmpty() || f.material().endsWith("AIR")) {
            return false;
        }
        final String m = f.material();
        if (f.maxDurability() > 0 || f.equippable() || f.attributes() || f.deathProtection() || f.toolOrWeapon()
            || f.container()) {
            return false;
        }
        if (EQUIPMENT.contains(m) || m.equals("SHULKER_BOX")) {
            return false;
        }
        for (final String s : SUFFIXES) {
            if (m.endsWith(s)) {
                return false;
            }
        }
        if (protectMaterials.contains(m)) {
            return false;
        }
        if (protectNamed && f.named()) {
            return false;
        }
        if (protectCustomModel && f.customModel()) {
            return false;
        }
        final String nbt = f.nbt() == null ? "" : f.nbt();
        for (final String mark : protectNbt) {
            if (!mark.isEmpty() && nbt.contains(mark)) {
                return false;
            }
        }
        return true;
    }

    /** Сколько забрать из стопки {@code count}: {@code want}, но не больше стопки; {@code whole} — всю. */
    public static int take(final int count, final int want, final boolean whole) {
        if (count <= 0) {
            return 0;
        }
        return whole ? count : Math.max(1, Math.min(count, want));
    }
}
