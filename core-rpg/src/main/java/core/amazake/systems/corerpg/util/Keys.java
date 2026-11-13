package core.amazake.systems.corerpg.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Все ключи PersistentDataContainer системы. Пространство имён — имя системы в нижнем регистре ({@code core-rpg}),
 * оно стабильно между включениями. Отдельно — ключи старых плагинов, которые CoreRpg читает и переносит.
 */
public final class Keys {

    // предметы: заточка
    public final NamespacedKey sharpenLevel;
    public final NamespacedKey sharpenConsumable;
    // предметы: печати
    public final NamespacedKey sealId;
    public final NamespacedKey sealColor;
    public final NamespacedKey sealBound;
    public final NamespacedKey sealConsumable;
    // снаряды: снимок атакующего на момент выстрела
    public final NamespacedKey projectileSharpen;
    public final NamespacedKey projectileWeaponKind;
    // атрибуты: префикс модификаторов аспектов (aspect_<ключ>)
    private final Plugin plugin;

    /** Ключи плагинов Zatochka и MagicSeals: читаются для переноса старых предметов. */
    public static final NamespacedKey LEGACY_ZATOCHKA_LEVEL = new NamespacedKey("zatochka", "sharpen_level");
    public static final NamespacedKey LEGACY_ZATOCHKA_CONSUMABLE = new NamespacedKey("zatochka", "consumable_id");
    public static final NamespacedKey LEGACY_SEAL_ID = new NamespacedKey("magicseals", "seal_id");
    public static final NamespacedKey LEGACY_SEAL_COLOR = new NamespacedKey("magicseals", "seal_color");
    public static final NamespacedKey LEGACY_SEAL_BOUND = new NamespacedKey("magicseals", "bound_to");
    public static final NamespacedKey LEGACY_SEAL_CONSUMABLE = new NamespacedKey("magicseals", "consumable");

    public Keys(final Plugin plugin) {
        this.plugin = plugin;
        this.sharpenLevel = new NamespacedKey(plugin, "sharpen_level");
        this.sharpenConsumable = new NamespacedKey(plugin, "sharpen_consumable");
        this.sealId = new NamespacedKey(plugin, "seal_id");
        this.sealColor = new NamespacedKey(plugin, "seal_color");
        this.sealBound = new NamespacedKey(plugin, "seal_bound");
        this.sealConsumable = new NamespacedKey(plugin, "seal_consumable");
        this.projectileSharpen = new NamespacedKey(plugin, "projectile_sharpen");
        this.projectileWeaponKind = new NamespacedKey(plugin, "projectile_weapon_kind");
    }

    /** Ключ модификатора атрибута для аспекта, например {@code core-rpg:aspect_attack_damage}. */
    public NamespacedKey aspectModifier(final String aspectKey) {
        return new NamespacedKey(this.plugin, "aspect_" + aspectKey.replace(':', '_'));
    }

    /** Любой другой ключ системы. */
    public NamespacedKey key(final String path) {
        return new NamespacedKey(this.plugin, path);
    }
}
