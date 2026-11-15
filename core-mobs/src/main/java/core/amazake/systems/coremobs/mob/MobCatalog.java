package core.amazake.systems.coremobs.mob;

import core.amazake.systems.coremobs.util.IntRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;

/** Каталог мобов из mobs.yml: неизменяемый, порядок как в файле. */
public final class MobCatalog {

    public static final Pattern ID = Pattern.compile("[a-z0-9_\\-]{1,48}");
    public static final MobCatalog EMPTY = new MobCatalog(Map.of());

    private static final Map<String, EquipmentSlot> SLOTS = Map.of(
        "hand", EquipmentSlot.HAND, "off-hand", EquipmentSlot.OFF_HAND, "helmet", EquipmentSlot.HEAD,
        "chestplate", EquipmentSlot.CHEST, "leggings", EquipmentSlot.LEGS, "boots", EquipmentSlot.FEET);

    private final Map<String, MobDef> mobs;

    private MobCatalog(final Map<String, MobDef> mobs) {
        this.mobs = mobs;
    }

    public MobDef get(final String id) {
        return id == null ? null : this.mobs.get(id.toLowerCase(Locale.ROOT));
    }

    public Map<String, MobDef> all() {
        return this.mobs;
    }

    public List<String> ids() {
        return List.copyOf(this.mobs.keySet());
    }

    public static MobCatalog parse(final ConfigurationSection root, final Consumer<String> warn) {
        final ConfigurationSection section = root.getConfigurationSection("mobs");
        if (section == null) {
            warn.accept("mobs.yml: нет раздела mobs");
            return EMPTY;
        }
        final Map<String, MobDef> out = new LinkedHashMap<>();
        for (final String rawId : section.getKeys(false)) {
            final String id = rawId.toLowerCase(Locale.ROOT);
            final ConfigurationSection m = section.getConfigurationSection(rawId);
            if (m == null) {
                continue;
            }
            if (!ID.matcher(id).matches()) {
                warn.accept("mobs." + rawId + ": имя моба — латиница в нижнем регистре, цифры, _ и -");
                continue;
            }
            try {
                out.put(id, parseMob(id, m, w -> warn.accept("mobs." + rawId + ": " + w)));
            } catch (final IllegalArgumentException e) {
                warn.accept("mobs." + rawId + " пропущен: " + e.getMessage());
            }
        }
        return new MobCatalog(Collections.unmodifiableMap(out));
    }

    static MobDef parseMob(final String id, final ConfigurationSection m, final Consumer<String> warn) {
        final String typeName = m.getString("type", "");
        final EntityType type;
        try {
            type = EntityType.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("type «" + typeName + "» — нет такого моба (пример: ZOMBIE, SKELETON, SPIDER)");
        }
        if (type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass()) || !type.isSpawnable()) {
            throw new IllegalArgumentException("type " + type + " — не моб, его нельзя заспавнить");
        }
        final double health = m.getDouble("health", 20.0);
        if (!(health > 0 && health <= 1_000_000)) {
            throw new IllegalArgumentException("health должно быть от 0 до 1000000");
        }
        IntRange exp = null;
        if (m.contains("exp")) {
            exp = IntRange.parse(m.getString("exp"));
            if (exp == null || exp.min() < 0) {
                warn.accept("exp «" + m.getString("exp") + "» — нужно число или диапазон 1-5, оставляю ванильный");
                exp = null;
            }
        }
        final Map<EquipmentSlot, Material> equipment = new EnumMap<>(EquipmentSlot.class);
        final ConfigurationSection eq = m.getConfigurationSection("equipment");
        if (eq != null) {
            for (final String key : eq.getKeys(false)) {
                final EquipmentSlot slot = SLOTS.get(key.toLowerCase(Locale.ROOT));
                if (slot == null) {
                    warn.accept("equipment." + key + " — слоты: " + String.join(", ", SLOTS.keySet()));
                    continue;
                }
                final Material material = Material.matchMaterial(eq.getString(key, ""));
                if (material == null || !material.isItem() || material.isAir()) {
                    warn.accept("equipment." + key + ": «" + eq.getString(key) + "» — нет такого предмета");
                    continue;
                }
                equipment.put(slot, material);
            }
        }
        return new MobDef(id, type,
            m.getString("name", id),
            m.getBoolean("show-name", true),
            m.getBoolean("show-health", true),
            health,
            positive(m, "damage", warn),
            positive(m, "speed", warn),
            positive(m, "armor", warn),
            positive(m, "follow-range", warn),
            positive(m, "knockback-resistance", warn),
            positive(m, "scale", warn),
            positive(m, "projectile-damage", warn),
            m.getBoolean("baby", false),
            m.getBoolean("glowing", false),
            m.getBoolean("silent", false),
            m.getBoolean("prevent-sunburn", true),
            m.getBoolean("vanilla-drops", false),
            m.getBoolean("drop-equipment", false),
            exp,
            Collections.unmodifiableMap(equipment),
            effects(m, "effects", false, warn),
            effects(m, "on-hit-effects", true, warn),
            Math.max(0, Math.min(16, m.getInt("size", 0))),
            m.getBoolean("no-fire", true));
    }

    private static Double positive(final ConfigurationSection m, final String key, final Consumer<String> warn) {
        if (!m.contains(key)) {
            return null;
        }
        final double v = m.getDouble(key, -1);
        if (!(v >= 0) || Double.isInfinite(v)) {
            warn.accept(key + " должно быть числом ≥ 0, оставляю ванильное");
            return null;
        }
        return v;
    }

    private static List<MobDef.Effect> effects(final ConfigurationSection m, final String key, final boolean onHit,
                                               final Consumer<String> warn) {
        final List<MobDef.Effect> out = new ArrayList<>();
        for (final Map<?, ?> raw : m.getMapList(key)) {
            final Object name = raw.get("effect");
            final PotionEffectType type = name == null ? null
                : Registry.MOB_EFFECT.get(NamespacedKey.minecraft(name.toString().trim().toLowerCase(Locale.ROOT)));
            if (type == null) {
                warn.accept(key + ": эффект «" + name + "» не найден (пример: poison, slowness, speed, strength)");
                continue;
            }
            final int level = Math.max(1, Math.min(255, number(raw.get("level"), 1).intValue()));
            final double seconds = number(raw.get("seconds"), onHit ? 5 : -1).doubleValue();
            final int ticks = !onHit && seconds <= 0 ? -1 : (int) Math.max(1, Math.round(seconds * 20));
            final double chance = Math.max(0, Math.min(100, number(raw.get("chance"), 100).doubleValue()));
            out.add(new MobDef.Effect(type, level - 1, ticks, chance));
        }
        return List.copyOf(out);
    }

    private static Number number(final Object o, final Number fallback) {
        if (o instanceof final Number n) {
            return n;
        }
        if (o != null) {
            try {
                return Double.parseDouble(o.toString().replace("%", "").trim());
            } catch (final NumberFormatException ignored) {
                // ниже — значение по умолчанию
            }
        }
        return fallback;
    }
}
