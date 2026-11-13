package core.amazake.systems.corerpg.sharpen;

import core.amazake.systems.corerpg.util.Gear;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Настройки заточки из sharpen.yml. Неизменяемый снимок: модуль держит его в {@code AtomicReference} и подменяет
 * целиком в reload. Неверные значения — предупреждение и значение по умолчанию.
 *
 * @param mode               royal-quest (таблицы RQ по грейду и камню) или simple (таблицы плагина × множители)
 * @param maxLevel           максимум заточки по грейду (C, B, A), 0..11
 * @param armorBonus         бонус защиты брони и щита, %; индекс = уровень 0..11 (индекс 0 всегда 0)
 * @param weaponBonus        бонус атаки оружия и инструментов, %; индекс = уровень 0..11
 * @param legendaryChances   +11 в режиме RQ: шанс по заточке жертвы 0..10, %
 * @param legendaryStat      сколько очков каждого из четырёх параметров даёт надетый предмет +11
 * @param rqSuccess          таблица успеха RQ
 * @param rqDestroy          таблица уничтожения RQ
 * @param simpleSuccess      режим simple: шанс успеха по категории для уровней 1..11 (индекс 0 → +1)
 * @param simpleDestroy      режим simple: шанс уничтожения по категории для уровней 1..11
 * @param multipliers        режим simple: множитель шанса успеха по камню
 * @param consumables        внешний вид расходников
 * @param npcEnabled         открывать меню правым кликом по сущности с именем из npcNames
 * @param npcNames           имена NPC (без цветов)
 * @param menuTitle          заголовок меню (MiniMessage)
 */
public record SharpenSettings(Mode mode, Map<Gear.Grade, Integer> maxLevel, List<Double> armorBonus,
                              List<Double> weaponBonus, List<Double> legendaryChances, int legendaryStat,
                              RqTable rqSuccess, RqTable rqDestroy, Map<String, List<Double>> simpleSuccess,
                              Map<String, List<Double>> simpleDestroy, Map<Consumable, Double> multipliers,
                              Map<Consumable, ConsumableDef> consumables, boolean npcEnabled, List<String> npcNames,
                              String menuTitle, Sounds sounds) {

    /** Наибольшая возможная заточка вообще. */
    public static final int HARD_MAX = 11;
    /** Категории таблиц шансов. */
    public static final List<String> CATEGORIES = List.of("armor", "weapon_1h", "weapon_2h");

    /** Режим шансов. */
    public enum Mode {
        ROYAL_QUEST("royal-quest"), SIMPLE("simple");

        private final String key;

        Mode(final String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }

        public static Mode byKey(final String raw) {
            if (raw == null) {
                return null;
            }
            final String k = raw.toLowerCase(Locale.ROOT).trim().replace('_', '-');
            for (final Mode m : values()) {
                if (m.key.equals(k)) {
                    return m;
                }
            }
            return "rq".equals(k) || "royalquest".equals(k) ? ROYAL_QUEST : null;
        }
    }

    /** Внешний вид расходника. */
    public record ConsumableDef(Consumable type, String name, Material material, String display, List<String> lore,
                                List<String> aliases) {
    }

    /** Звук: ключ ресурса (block.enchantment_table.use), громкость, высота. Пустой ключ — без звука. */
    public record SoundDef(String key, float volume, float pitch) {
        public static final SoundDef NONE = new SoundDef("", 1.0f, 1.0f);

        public boolean present() {
            return !this.key.isEmpty();
        }
    }

    /** Звуки результатов. */
    public record Sounds(SoundDef success, SoundDef fail, SoundDef destroy, SoundDef legendary, SoundDef legendaryFail) {
    }

    // ------------------------------------------------------------------ значения по умолчанию

    static final double[] DEFAULT_ARMOR = {0, 10, 22, 36, 52, 70, 90, 112, 136.2, 162.1, 200, 260};
    static final double[] DEFAULT_WEAPON = {0, 5, 11, 18, 26, 35.1, 45, 56.1, 68.2, 81, 100, 130};
    static final double[] DEFAULT_LEGENDARY = {10, 20, 30, 35, 40, 50, 60, 70, 80, 90, 100};
    /** Таблицы плагина Zatochka, уровни 1..11. */
    static final Map<String, double[]> DEFAULT_SIMPLE_SUCCESS = Map.of(
        "armor", new double[] {100, 90, 85, 75, 65, 50, 40, 30, 20, 20, 5},
        "weapon_1h", new double[] {100, 90, 75, 60, 50, 40, 30, 20, 14, 8, 3},
        "weapon_2h", new double[] {100, 80, 65, 50, 35, 25, 15, 10, 5, 3, 1});
    static final Map<String, double[]> DEFAULT_SIMPLE_DESTROY = Map.of(
        "armor", new double[] {0, 1, 1, 1, 2, 3, 4, 7, 12, 24, 40},
        "weapon_1h", new double[] {0, 1, 1, 2, 2, 4, 5, 8, 13, 28, 45},
        "weapon_2h", new double[] {0, 1, 2, 3, 5, 7, 10, 15, 22, 35, 50});
    static final Map<Consumable, Double> DEFAULT_MULTIPLIERS = defaultMultipliers();
    static final Sounds DEFAULT_SOUNDS = new Sounds(
        new SoundDef("block.enchantment_table.use", 1.0f, 1.0f),
        new SoundDef("entity.villager.no", 1.0f, 0.9f),
        new SoundDef("item.totem.use", 1.0f, 1.0f),
        new SoundDef("ui.toast.challenge_complete", 1.0f, 1.0f),
        new SoundDef("block.anvil.destroy", 1.0f, 1.0f));
    static final String DEFAULT_TITLE = "<dark_purple><bold>Улучшение предмета</bold></dark_purple>";
    static final String DEFAULT_NPC = "Королевский алхимик";

    public static final SharpenSettings DEFAULTS = from(new MemoryConfiguration(), w -> { });

    private static Map<Consumable, Double> defaultMultipliers() {
        final EnumMap<Consumable, Double> map = new EnumMap<>(Consumable.class);
        map.put(Consumable.SHARD, 1.0);
        map.put(Consumable.CRUMB, 1.0);
        map.put(Consumable.CUBE, 1.0);
        map.put(Consumable.BLACK, 2.0);
        map.put(Consumable.WHITE, 4.0);
        map.put(Consumable.RED, 6.0);
        map.put(Consumable.PURPLE, 8.0);
        return Collections.unmodifiableMap(map);
    }

    // ------------------------------------------------------------------ доступ

    /** Максимум для грейда. */
    public int maxLevel(final Gear.Grade grade) {
        final Integer v = this.maxLevel.get(grade);
        return v == null ? 0 : v;
    }

    /** Бонус в % для уровня: к атаке ({@code attacks}) или к защите. */
    public double bonus(final boolean attacks, final int level) {
        if (level <= 0) {
            return 0.0;
        }
        final List<Double> table = attacks ? this.weaponBonus : this.armorBonus;
        return table.get(Math.min(level, table.size() - 1));
    }

    /** Шанс +11 по заточке жертвы, %. */
    public double legendaryChance(final int sacrificeLevel) {
        final int i = Math.max(0, Math.min(sacrificeLevel, this.legendaryChances.size() - 1));
        return this.legendaryChances.get(i);
    }

    public double multiplier(final Consumable stone) {
        final Double v = this.multipliers.get(stone);
        return v == null ? 1.0 : v;
    }

    public ConsumableDef def(final Consumable type) {
        return this.consumables.get(type);
    }

    /** Расходник по id или русскому имени из настроек; null, если не найден. */
    public Consumable resolveConsumable(final String raw) {
        final Consumable direct = Consumable.byId(raw);
        if (direct != null) {
            return direct;
        }
        final String n = Consumable.normalize(raw);
        if (n.isEmpty()) {
            return null;
        }
        for (final ConsumableDef def : this.consumables.values()) {
            if (Consumable.normalize(def.name()).equals(n)) {
                return def.type();
            }
            for (final String alias : def.aliases()) {
                if (Consumable.normalize(alias).equals(n)) {
                    return def.type();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ разбор

    public static SharpenSettings from(final ConfigurationSection config, final Consumer<String> warn) {
        Mode mode = Mode.ROYAL_QUEST;
        final String modeRaw = config.getString("mode");
        if (modeRaw != null) {
            final Mode parsed = Mode.byKey(modeRaw);
            if (parsed == null) {
                warn.accept("mode: «" + modeRaw + "» — нужно royal-quest или simple, беру royal-quest");
            } else {
                mode = parsed;
            }
        }

        final EnumMap<Gear.Grade, Integer> max = new EnumMap<>(Gear.Grade.class);
        for (final Gear.Grade grade : Gear.Grade.values()) {
            final int def = grade == Gear.Grade.C ? 10 : 11;
            final String path = "max-level." + grade.name();
            int v = def;
            if (config.contains(path)) {
                final Object raw = config.get(path);
                if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                    && n.intValue() >= 0 && n.intValue() <= HARD_MAX) {
                    v = n.intValue();
                } else {
                    warn.accept(path + ": «" + raw + "» — нужно целое 0.." + HARD_MAX + ", беру " + def);
                }
            }
            max.put(grade, v);
        }
        final ConfigurationSection maxSection = config.getConfigurationSection("max-level");
        if (maxSection != null) {
            for (final String key : maxSection.getKeys(false)) {
                if (!isGradeKey(key)) {
                    warn.accept("max-level." + key + ": неизвестный грейд (есть C, B, A), пропускаю");
                }
            }
        } else if (config.contains("max-level")) {
            warn.accept("max-level: нужна карта «грейд: уровень» (C, B, A), беру значения по умолчанию");
        }

        final List<Double> armor = levelTable(config, "bonuses.armor", DEFAULT_ARMOR, 1, 0, 100000, warn);
        final List<Double> weapon = levelTable(config, "bonuses.weapon", DEFAULT_WEAPON, 1, 0, 100000, warn);
        final List<Double> legendary = levelTable(config, "legendary.chances", DEFAULT_LEGENDARY, 0, 0, 100, warn);

        int stat = 1;
        if (config.contains("legendary.stat-bonus")) {
            final Object raw = config.get("legendary.stat-bonus");
            if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && n.doubleValue() >= 0 && n.doubleValue() <= 100) {
                stat = n.intValue();
            } else {
                warn.accept("legendary.stat-bonus: «" + raw + "» — нужно целое 0..100, беру 1");
            }
        }

        final RqTable success = RqTable.from(config.getConfigurationSection("royal-quest.success"),
            "royal-quest.success", RqDefaults.SUCCESS, warn);
        final RqTable destroy = RqTable.from(config.getConfigurationSection("royal-quest.destroy"),
            "royal-quest.destroy", RqDefaults.DESTROY, warn);

        final Map<String, List<Double>> simpleSuccess = new LinkedHashMap<>();
        final Map<String, List<Double>> simpleDestroy = new LinkedHashMap<>();
        for (final String cat : CATEGORIES) {
            simpleSuccess.put(cat, shift(levelTable(config, "simple.success-chances." + cat,
                prepend(DEFAULT_SIMPLE_SUCCESS.get(cat)), 1, 0, 100, warn)));
            simpleDestroy.put(cat, shift(levelTable(config, "simple.destroy-chances." + cat,
                prepend(DEFAULT_SIMPLE_DESTROY.get(cat)), 1, 0, 100, warn)));
        }

        final EnumMap<Consumable, Double> multipliers = new EnumMap<>(DEFAULT_MULTIPLIERS);
        final ConfigurationSection multSection = config.getConfigurationSection("simple.stone-multipliers");
        if (multSection != null) {
            for (final String key : multSection.getKeys(false)) {
                final Consumable stone = Consumable.byId(key);
                if (stone == null || stone.isRune()) {
                    warn.accept("simple.stone-multipliers." + key + ": неизвестный камень, пропускаю");
                    continue;
                }
                final Object raw = multSection.get(key);
                if (raw instanceof Number n && n.doubleValue() > 0 && n.doubleValue() <= 100) {
                    multipliers.put(stone, n.doubleValue());
                } else {
                    warn.accept("simple.stone-multipliers." + key + ": «" + raw + "» — нужно число 0..100, беру "
                        + DEFAULT_MULTIPLIERS.get(stone));
                }
            }
        }

        final Map<Consumable, ConsumableDef> consumables = parseConsumables(config.getConfigurationSection("consumables"), warn);

        boolean npcEnabled = true;
        if (config.contains("npc.enabled")) {
            if (config.isBoolean("npc.enabled")) {
                npcEnabled = config.getBoolean("npc.enabled");
            } else {
                warn.accept("npc.enabled: «" + config.get("npc.enabled") + "» — нужно true или false, беру true");
            }
        }
        final List<String> npcNames = new ArrayList<>();
        if (config.isList("npc.names")) {
            for (final String s : config.getStringList("npc.names")) {
                if (s != null && !s.isBlank()) {
                    npcNames.add(s.trim());
                }
            }
        } else if (config.isString("npc.names")) {
            npcNames.add(config.getString("npc.names", DEFAULT_NPC).trim());
        } else {
            npcNames.add(DEFAULT_NPC);
        }
        if (npcEnabled && npcNames.isEmpty()) {
            warn.accept("npc.names: пустой список — меню по NPC не откроется");
        }

        final String title = config.getString("menu.title", DEFAULT_TITLE);

        final Sounds sounds = new Sounds(
            sound(config, "sounds.success", DEFAULT_SOUNDS.success(), warn),
            sound(config, "sounds.fail", DEFAULT_SOUNDS.fail(), warn),
            sound(config, "sounds.destroy", DEFAULT_SOUNDS.destroy(), warn),
            sound(config, "sounds.legendary", DEFAULT_SOUNDS.legendary(), warn),
            sound(config, "sounds.legendary-fail", DEFAULT_SOUNDS.legendaryFail(), warn));

        return new SharpenSettings(mode, Collections.unmodifiableMap(max), armor, weapon, legendary, stat, success, destroy,
            Collections.unmodifiableMap(simpleSuccess), Collections.unmodifiableMap(simpleDestroy),
            Collections.unmodifiableMap(multipliers), consumables, npcEnabled, List.copyOf(npcNames),
            title == null ? DEFAULT_TITLE : title, sounds);
    }

    private static double[] prepend(final double[] levels1to11) {
        final double[] out = new double[levels1to11.length + 1];
        System.arraycopy(levels1to11, 0, out, 1, levels1to11.length);
        return out;
    }

    private static List<Double> shift(final List<Double> withZero) {
        return List.copyOf(withZero.subList(1, withZero.size()));
    }

    /**
     * Таблица по уровням: карта «уровень: значение» или список. {@code first} — первый уровень, который читается
     * (0 или 1; для 1 индекс 0 остаётся из defaults). Неверная ячейка — предупреждение и значение по умолчанию.
     */
    static List<Double> levelTable(final ConfigurationSection config, final String path, final double[] defaults,
                                   final int first, final double min, final double max, final Consumer<String> warn) {
        final double[] values = defaults.clone();
        if (!config.contains(path)) {
            return toList(values);
        }
        if (config.isConfigurationSection(path)) {
            final ConfigurationSection section = config.getConfigurationSection(path);
            for (final String key : section.getKeys(false)) {
                final int level;
                try {
                    level = Integer.parseInt(key.trim());
                } catch (final NumberFormatException e) {
                    warn.accept(path + "." + key + ": уровень должен быть числом, пропускаю");
                    continue;
                }
                if (level < first || level >= values.length) {
                    warn.accept(path + "." + key + ": уровень вне " + first + ".." + (values.length - 1) + ", пропускаю");
                    continue;
                }
                values[level] = number(section.get(key), path + "." + key, values[level], min, max, warn);
            }
        } else if (config.isList(path)) {
            final List<?> list = config.getList(path);
            if (list.size() != values.length - first) {
                warn.accept(path + ": нужно " + (values.length - first) + " значений (уровни " + first + ".."
                    + (values.length - 1) + "), а их " + list.size() + "; недостающие — по умолчанию");
            }
            for (int i = 0; i < list.size() && first + i < values.length; i++) {
                values[first + i] = number(list.get(i), path + "[" + i + "]", values[first + i], min, max, warn);
            }
        } else {
            warn.accept(path + ": нужна карта «уровень: значение», беру значения по умолчанию");
        }
        return toList(values);
    }

    static double number(final Object raw, final String path, final double def, final double min, final double max,
                         final Consumer<String> warn) {
        double v;
        if (raw instanceof Number n) {
            v = n.doubleValue();
        } else if (raw instanceof String s) {
            try {
                v = Double.parseDouble(s.trim().replace(',', '.'));
            } catch (final NumberFormatException e) {
                warn.accept(path + ": «" + s + "» — не число, беру " + def);
                return def;
            }
        } else {
            warn.accept(path + ": «" + raw + "» — не число, беру " + def);
            return def;
        }
        if (Double.isNaN(v) || v < min || v > max) {
            warn.accept(path + ": " + raw + " вне " + fmt(min) + ".." + fmt(max) + ", беру " + fmt(def));
            return def;
        }
        return v;
    }

    private static String fmt(final double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static List<Double> toList(final double[] values) {
        final List<Double> out = new ArrayList<>(values.length);
        for (final double v : values) {
            out.add(v);
        }
        return List.copyOf(out);
    }

    private static Map<Consumable, ConsumableDef> parseConsumables(final ConfigurationSection section, final Consumer<String> warn) {
        final EnumMap<Consumable, ConsumableDef> out = new EnumMap<>(Consumable.class);
        if (section != null) {
            for (final String key : section.getKeys(false)) {
                if (Consumable.byId(key) == null) {
                    warn.accept("consumables." + key + ": неизвестный расходник (есть: shard, crumb, cube, black, white, red, "
                        + "purple, rune_save, rune_cold), пропускаю");
                }
            }
        }
        for (final Consumable type : Consumable.values()) {
            final ConfigurationSection s = section == null ? null : section.getConfigurationSection(type.id());
            final String path = "consumables." + type.id();
            if (s == null) {
                out.put(type, new ConsumableDef(type, type.defaultName(), Material.valueOf(type.defaultMaterial()),
                    type.defaultDisplay(), type.defaultLore(), type.defaultAliases()));
                continue;
            }
            final String name = s.getString("name", type.defaultName());
            Material material = Material.valueOf(type.defaultMaterial());
            final String matRaw = s.getString("material");
            if (matRaw != null) {
                final Material parsed = Material.matchMaterial(matRaw.trim());
                // isItem() требует реестров сервера: проверяется при создании предмета (SharpenItems.create).
                if (parsed == null || parsed.isLegacy() || parsed.name().endsWith("_AIR") || parsed == Material.AIR) {
                    warn.accept(path + ".material: «" + matRaw + "» — не предмет, беру " + type.defaultMaterial());
                } else {
                    material = parsed;
                }
            }
            final String display = s.getString("display", type.defaultDisplay());
            final List<String> lore = s.isList("lore") ? List.copyOf(s.getStringList("lore")) : type.defaultLore();
            final List<String> aliases = s.isList("aliases") ? List.copyOf(s.getStringList("aliases")) : type.defaultAliases();
            out.put(type, new ConsumableDef(type, name == null || name.isBlank() ? type.defaultName() : name, material,
                display == null ? type.defaultDisplay() : display, lore, aliases));
        }
        return Collections.unmodifiableMap(out);
    }

    private static SoundDef sound(final ConfigurationSection config, final String path, final SoundDef def,
                                  final Consumer<String> warn) {
        if (!config.contains(path)) {
            return def;
        }
        if (config.isString(path)) {
            return soundKey(config.getString(path), path, def, warn, def.volume(), def.pitch());
        }
        final ConfigurationSection s = config.getConfigurationSection(path);
        if (s == null) {
            warn.accept(path + ": нужна строка или раздел sound/volume/pitch, беру " + def.key());
            return def;
        }
        final float volume = (float) number(s.get("volume", (double) def.volume()), path + ".volume", def.volume(), 0, 10, warn);
        final float pitch = (float) number(s.get("pitch", (double) def.pitch()), path + ".pitch", def.pitch(), 0.5, 2, warn);
        return soundKey(s.getString("sound", def.key()), path + ".sound", def, warn, volume, pitch);
    }

    private static SoundDef soundKey(final String raw, final String path, final SoundDef def, final Consumer<String> warn,
                                     final float volume, final float pitch) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return new SoundDef("", volume, pitch);
        }
        final String key = raw.trim().toLowerCase(Locale.ROOT);
        if (!key.matches("([a-z0-9_.\\-]+:)?[a-z0-9_.\\-/]+")) {
            warn.accept(path + ": «" + raw + "» — не ключ звука (пример: block.anvil.use), беру " + def.key());
            return def;
        }
        return new SoundDef(key, volume, pitch);
    }

    /** Ключ грейда в конфиге: ровно C, B или A. */
    static boolean isGradeKey(final String key) {
        for (final Gear.Grade grade : Gear.Grade.values()) {
            if (grade.name().equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ таблица RQ

    /** Таблица RQ: категория × грейд × уровень 1..10 × камень (5 колонок). Неизменяемая. */
    public static final class RqTable {
        private final double[][][][] cells;

        private RqTable(final double[][][][] cells) {
            this.cells = cells;
        }

        static RqTable of(final double[][][][] source) {
            return new RqTable(deepCopy(source));
        }

        /** Шанс, %, для категории, грейда, целевого уровня 1..10 и колонки камня 0..4. */
        public double get(final String category, final Gear.Grade grade, final int targetLevel, final int column) {
            final int cat = CATEGORIES.indexOf(category);
            if (cat < 0 || targetLevel < 1 || targetLevel > 10 || column < 0 || column > 4) {
                return 0.0;
            }
            return this.cells[cat][grade.ordinal()][targetLevel - 1][column];
        }

        static RqTable from(final ConfigurationSection section, final String path, final double[][][][] defaults,
                            final Consumer<String> warn) {
            final double[][][][] cells = deepCopy(defaults);
            if (section == null) {
                return new RqTable(cells);
            }
            for (int c = 0; c < CATEGORIES.size(); c++) {
                final String cat = CATEGORIES.get(c);
                final ConfigurationSection catSection = section.getConfigurationSection(cat);
                if (catSection != null) {
                    for (final String key : catSection.getKeys(false)) {
                        if (!isGradeKey(key)) {
                            warn.accept(path + "." + cat + "." + key + ": неизвестный грейд (есть C, B, A), пропускаю");
                        }
                    }
                } else if (section.contains(cat)) {
                    warn.accept(path + "." + cat + ": нужна карта «грейд: таблица» (C, B, A), беру таблицу RQ");
                }
                for (final Gear.Grade grade : Gear.Grade.values()) {
                    final String p = cat + "." + grade.name();
                    if (!section.contains(p)) {
                        continue;
                    }
                    final ConfigurationSection rows = section.getConfigurationSection(p);
                    if (rows == null) {
                        warn.accept(path + "." + p + ": нужна карта «уровень: [5 чисел]», беру таблицу RQ");
                        continue;
                    }
                    for (final String key : rows.getKeys(false)) {
                        final int level;
                        try {
                            level = Integer.parseInt(key.trim());
                        } catch (final NumberFormatException e) {
                            warn.accept(path + "." + p + "." + key + ": уровень должен быть числом 1..10, пропускаю");
                            continue;
                        }
                        if (level < 1 || level > 10) {
                            warn.accept(path + "." + p + "." + key + ": уровень вне 1..10, пропускаю");
                            continue;
                        }
                        final List<?> list = rows.getList(key);
                        if (list == null || list.size() != 5) {
                            warn.accept(path + "." + p + "." + key + ": нужен список из 5 чисел (осколок, чёрный, белый, "
                                + "красный, пурпурный), беру таблицу RQ");
                            continue;
                        }
                        for (int i = 0; i < 5; i++) {
                            cells[c][grade.ordinal()][level - 1][i] = number(list.get(i), path + "." + p + "." + key + "[" + i + "]",
                                cells[c][grade.ordinal()][level - 1][i], 0, 100, warn);
                        }
                    }
                }
            }
            for (final String key : section.getKeys(false)) {
                if (!CATEGORIES.contains(key)) {
                    warn.accept(path + "." + key + ": неизвестная категория (есть armor, weapon_1h, weapon_2h), пропускаю");
                }
            }
            return new RqTable(cells);
        }

        private static double[][][][] deepCopy(final double[][][][] src) {
            final double[][][][] out = new double[src.length][][][];
            for (int a = 0; a < src.length; a++) {
                out[a] = new double[src[a].length][][];
                for (int b = 0; b < src[a].length; b++) {
                    out[a][b] = new double[src[a][b].length][];
                    for (int c = 0; c < src[a][b].length; c++) {
                        out[a][b][c] = src[a][b][c].clone();
                    }
                }
            }
            return out;
        }
    }
}
