package core.amazake.systems.corerpg.seals;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Раздел {@code settings:} файла seals.yml. Неизменяемый снимок: модуль держит его в AtomicReference и подменяет целиком
 * в reload.
 *
 * @param weightGreen          вес зелёного цвета (относительный)
 * @param arkoniteRerollChance шанс (0..100 %), что арконитовый сургуч перебросит цвет
 * @param classFilter          выбирать печати по классу и профессии игрока
 * @param npcName              имя сущности NPC «Мастер зачарования»; пусто — NPC выключен
 * @param menuTitle            заголовок меню (MiniMessage)
 * @param looks                материал и название каждого расходника
 */
public record SealSettings(int weightGreen, int weightBlue, int weightPurple, int weightOrange,
                           double arkoniteRerollChance, boolean classFilter, String npcName, String menuTitle,
                           Map<Consumable, Look> looks) {

    /** Внешний вид расходника: материал предмета и название (MiniMessage). */
    public record Look(Material material, String name) {
    }

    public static final int MAX_WEIGHT = 1_000_000;
    public static final Material DEFAULT_MATERIAL = Material.AMETHYST_SHARD;
    public static final SealSettings DEFAULTS = new SealSettings(60, 27, 10, 3, 100.0, true,
        "Мастер зачарования", "<dark_purple>Наложение печати", defaultLooks());

    public SealSettings {
        final Map<Consumable, Look> copy = new EnumMap<>(Consumable.class);
        copy.putAll(defaultLooks());
        copy.putAll(looks);
        looks = Collections.unmodifiableMap(copy);
    }

    private static Map<Consumable, Look> defaultLooks() {
        final Map<Consumable, Look> map = new EnumMap<>(Consumable.class);
        for (final Consumable c : Consumable.values()) {
            map.put(c, new Look(DEFAULT_MATERIAL, "<light_purple>" + c.defaultName() + "</light_purple>"));
        }
        return map;
    }

    /** Веса цветов в порядке зелёный, синий, фиолетовый, оранжевый. */
    public int[] weights() {
        return new int[] {this.weightGreen, this.weightBlue, this.weightPurple, this.weightOrange};
    }

    public Look look(final Consumable consumable) {
        return this.looks.get(consumable);
    }

    /** Разбор: {@code root} — весь seals.yml, читается раздел settings. Неверное — предупреждение и умолчание. */
    public static SealSettings from(final ConfigurationSection root, final Consumer<String> warn) {
        final String p = "settings.";
        final int[] defaults = DEFAULTS.weights();
        final String[] colors = {"green", "blue", "purple", "orange"};
        int[] weights = new int[4];
        long total = 0;
        for (int i = 0; i < 4; i++) {
            final String path = p + "roll-weights." + colors[i];
            weights[i] = readInt(root, path, defaults[i], 0, MAX_WEIGHT, warn);
            total += weights[i];
        }
        if (total <= 0) {
            warn.accept(p + "roll-weights: все веса нулевые, беру 60/27/10/3");
            weights = defaults;
        }

        double chance = DEFAULTS.arkoniteRerollChance();
        final String chancePath = p + "arkonite-reroll-chance";
        if (root.contains(chancePath)) {
            final Object raw = root.get(chancePath);
            if (raw instanceof final Number n && n.doubleValue() >= 0 && n.doubleValue() <= 100) {
                chance = n.doubleValue();
            } else {
                warn.accept(chancePath + ": «" + raw + "» — нужно число 0..100, беру " + DEFAULTS.arkoniteRerollChance());
            }
        }

        boolean classFilter = DEFAULTS.classFilter();
        final String filterPath = p + "class-filter";
        if (root.contains(filterPath)) {
            final Object raw = root.get(filterPath);
            if (raw instanceof final Boolean b) {
                classFilter = b;
            } else {
                warn.accept(filterPath + ": «" + raw + "» — нужно true или false, беру true");
            }
        }

        final String npc = root.getString(p + "npc-name", DEFAULTS.npcName());
        final String title = root.getString(p + "menu-title", DEFAULTS.menuTitle());

        final Map<Consumable, Look> looks = new EnumMap<>(Consumable.class);
        for (final Consumable c : Consumable.values()) {
            final String base = p + "consumables." + c.id() + ".";
            final Look def = DEFAULTS.look(c);
            Material material = def.material();
            final String rawMaterial = root.getString(base + "material");
            if (rawMaterial != null) {
                final Material parsed = parseMaterial(rawMaterial);
                if (parsed == null) {
                    warn.accept(base + "material: «" + rawMaterial + "» — неизвестный материал, беру " + def.material());
                } else {
                    material = parsed;
                }
            }
            String name = root.getString(base + "name", def.name());
            if (name == null || name.isBlank()) {
                warn.accept(base + "name: пусто, беру «" + c.defaultName() + "»");
                name = def.name();
            }
            looks.put(c, new Look(material, name));
        }
        return new SealSettings(weights[0], weights[1], weights[2], weights[3], chance, classFilter,
            npc == null ? "" : npc.trim(), title == null ? DEFAULTS.menuTitle() : title, looks);
    }

    /**
     * Материалы, которые не являются предметами (вода, огонь…), заменить на материал по умолчанию. Проверка нужна
     * серверу (реестр предметов), поэтому вынесена из {@link #from}.
     */
    public SealSettings withItemMaterials(final Predicate<Material> isItem, final Consumer<String> warn) {
        final Map<Consumable, Look> fixed = new EnumMap<>(Consumable.class);
        boolean changed = false;
        for (final Map.Entry<Consumable, Look> e : this.looks.entrySet()) {
            final Look look = e.getValue();
            if (isItem.test(look.material())) {
                fixed.put(e.getKey(), look);
            } else {
                warn.accept("settings.consumables." + e.getKey().id() + ".material: " + look.material()
                    + " не предмет, беру " + DEFAULT_MATERIAL);
                fixed.put(e.getKey(), new Look(DEFAULT_MATERIAL, look.name()));
                changed = true;
            }
        }
        return changed ? new SealSettings(this.weightGreen, this.weightBlue, this.weightPurple, this.weightOrange,
            this.arkoniteRerollChance, this.classFilter, this.npcName, this.menuTitle, fixed) : this;
    }

    static Material parseMaterial(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String name = raw.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").replace(' ', '_');
        if (name.startsWith("LEGACY_")) {
            return null;
        }
        final Material material = Material.getMaterial(name);
        if (material == null || material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return null;
        }
        return material;
    }

    private static int readInt(final ConfigurationSection root, final String path, final int def, final int min,
                               final int max, final Consumer<String> warn) {
        if (!root.contains(path)) {
            return def;
        }
        final Object raw = root.get(path);
        if (raw instanceof final Number n && n.doubleValue() == Math.rint(n.doubleValue())
            && n.longValue() >= min && n.longValue() <= max) {
            return n.intValue();
        }
        warn.accept(path + ": «" + raw + "» — нужно целое " + min + ".." + max + ", беру " + def);
        return def;
    }
}
