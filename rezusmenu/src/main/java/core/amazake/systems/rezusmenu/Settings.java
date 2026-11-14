package core.amazake.systems.rezusmenu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Настройки из config.yml. Неизменяемый снимок: reload подменяет его целиком. */
public record Settings(
    String prefix,
    String dialogTitle,
    String dialogBody,
    List<MenuEntry> menus,
    double sensitivity,
    boolean invulnerable,
    int refreshTicks,
    boolean debugClicks,
    Placeholders ph,
    Resources resources,
    List<Stat> stats,
    List<Stat> equipmentLines,
    Map<String, ClassInfo> classes,
    Map<String, String> professions,
    Material noClassIcon,
    Map<String, JewelRule> jewelry,
    List<String> hudHideCommands,
    List<String> hudShowCommands,
    boolean shaderCursor,
    double glyphOffsetX,
    double glyphOffsetY,
    boolean swapHandsOpens,
    boolean swapHandsSneak,
    int stashBaseRows,
    int stashMaxExtraRows
) {

    /** Кнопка в диалоге /menuses. */
    public record MenuEntry(String id, String label, String tooltip) {
    }

    /**
     * Строка «название — значение» из плейсхолдеров (или готового текста). {@code fallbackName} — подпись, если
     * плейсхолдер названия не подставился.
     */
    public record Stat(String name, String value, String fallbackName) {
    }

    /** Откуда брать класс, уровень, опыт и очки (PlaceholderAPI; по умолчанию — RoyalQuestClasses и MMOCore). */
    public record Placeholders(String level, String exp, String expNeeded, String className, String classKey,
                               String profession, String professionKey, String needsProfession, String skillPoints,
                               String attributePoints) {
    }

    /** Мана или энергия: подпись, текущее и максимум. */
    public record ResourceType(String id, String label, String current, String max) {
    }

    /**
     * Ресурс класса, как в MythicHUD: сначала по классу ({@code classes}), иначе по имени ресурса MMOCore
     * ({@code namePlaceholder} = {@code energyName} — энергия, иначе мана).
     */
    public record Resources(Map<String, String> classes, String namePlaceholder, String energyName,
                            Map<String, ResourceType> types) {

        public ResourceType type(final String id) {
            return this.types.get(id);
        }
    }

    /** Класс RoyalQuestClasses: название, иконка и профессии (ключи). */
    public record ClassInfo(String key, String name, Material icon, List<String> professions) {
    }

    /**
     * Что можно положить в слот бижутерии: типы MMOItems, материалы и/или NBT-метки (подстрока в NBT предмета, напр.
     * {@code jewel:"ring"} или {@code myplugin:ring}). Все списки пустые — что угодно.
     */
    public record JewelRule(Set<String> types, Set<Material> materials, List<String> nbt) {

        public JewelRule(final Set<String> types, final Set<Material> materials) {
            this(types, materials, List.of());
        }

        public boolean any() {
            return this.types.isEmpty() && this.materials.isEmpty() && this.nbt.isEmpty();
        }
    }

    /** Слоты бижутерии (ключи в jewelry). */
    public static final List<String> JEWEL_SLOTS = List.of("amulet", "ring", "totem", "artifact");

    public static final List<Stat> DEFAULT_STATS = List.of(
        new Stat("%hl_stats_primary_name%", "%hl_stats_primary%", "Сила"),
        new Stat("Ловкость", "%hl_stats_agility%", "Ловкость"),
        new Stat("Выносливость", "%hl_stats_stamina%", "Выносливость"),
        new Stat("Удача", "%hl_stats_luck%", "Удача"));

    public static final List<Stat> DEFAULT_EQUIPMENT = List.of(
        new Stat("Уровень", "%hl_level%", "Уровень"),
        new Stat("Свободные очки параметров", "%hl_stats_available%", ""),
        new Stat("Очки навыков", "%mmocore_skill_points%", ""));

    public static final Placeholders DEFAULT_PH = new Placeholders("%hl_level%", "%hl_exp%", "%hl_exp_needed%",
        "%hl_class%", "%hl_class_key%", "%hl_profession%", "%hl_profession_key%", "%hl_needs_profession%",
        "%mmocore_skill_points%", "%hl_stats_available%");

    public static final Resources DEFAULT_RESOURCES = new Resources(
        Map.of("swordsman", "energy", "archer", "energy", "thief", "energy", "mage", "mana"),
        "%mmocore_mana_name%", "Энергия",
        Map.of("mana", new ResourceType("mana", "Мана", "%mmocore_mana%", "%mmocore_stat_max_mana%"),
            "energy", new ResourceType("energy", "Энергия", "%mmocore_mana%", "%mmocore_stat_max_mana%")));

    public static final Map<String, ClassInfo> DEFAULT_CLASSES = ordered(
        new ClassInfo("swordsman", "Мечник", Material.IRON_SWORD, List.of("crusader", "dark_knight")),
        new ClassInfo("archer", "Стрелок", Material.BOW, List.of("hunter", "sniper")),
        new ClassInfo("mage", "Маг", Material.BLAZE_ROD, List.of("wizard", "warlock")),
        new ClassInfo("thief", "Вор", Material.GOLDEN_SWORD, List.of("rogue", "assassin")));

    public static final Map<String, String> DEFAULT_PROFESSIONS = Map.of(
        "crusader", "Крестоносец", "dark_knight", "Тёмный рыцарь", "hunter", "Охотник", "sniper", "Снайпер",
        "wizard", "Волшебник", "warlock", "Чернокнижник", "rogue", "Разбойник", "assassin", "Ассасин");

    public static final Map<String, JewelRule> DEFAULT_JEWELRY = Map.of(
        "amulet", new JewelRule(Set.of("AMULET", "NECKLACE", "ACCESSORY"), Set.of()),
        "ring", new JewelRule(Set.of("RING", "ACCESSORY"), Set.of()),
        "totem", new JewelRule(Set.of("TOTEM"), Set.of(Material.TOTEM_OF_UNDYING)),
        "artifact", new JewelRule(Set.of("ARTIFACT", "RELIC", "ORNAMENT", "TALISMAN"), Set.of()));

    public static final Settings DEFAULTS = new Settings(
        "<dark_gray>[<gold>Меню</gold>]</dark_gray> ",
        "<gold>Меню сервера</gold>",
        "<gray>Выберите, что открыть.</gray>",
        List.of(new MenuEntry("rpg", "<gold>RPG-инвентарь</gold>", "<gray>Персонаж, снаряжение и инвентарь</gray>")),
        0.045, true, 10, false,
        DEFAULT_PH, DEFAULT_RESOURCES, DEFAULT_STATS, DEFAULT_EQUIPMENT, DEFAULT_CLASSES, DEFAULT_PROFESSIONS,
        Material.BOOK, DEFAULT_JEWELRY, List.of(), List.of(), true, 0.0, 0.0, true, false, 3, 6);

    private static Map<String, ClassInfo> ordered(final ClassInfo... infos) {
        final Map<String, ClassInfo> m = new LinkedHashMap<>();
        for (final ClassInfo i : infos) {
            m.put(i.key(), i);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Settings d = DEFAULTS;
        final List<MenuEntry> menus = new ArrayList<>();
        final ConfigurationSection m = c.getConfigurationSection("menus");
        if (m != null) {
            for (final String id : m.getKeys(false)) {
                final ConfigurationSection e = m.getConfigurationSection(id);
                if (e == null || !e.getBoolean("enabled", true)) {
                    continue;
                }
                final String key = id.toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_\\-]{1,32}")) {
                    warn.accept("menus." + id + ": id — латиница в нижнем регистре, цифры, _ и -");
                    continue;
                }
                menus.add(new MenuEntry(key, e.getString("label", key), e.getString("tooltip", "")));
            }
        } else {
            menus.addAll(d.menus());
        }
        double sensitivity = c.getDouble("rpg.cursor-speed", d.sensitivity());
        if (!(sensitivity >= 0.005 && sensitivity <= 0.5)) {
            warn.accept("rpg.cursor-speed: " + sensitivity + " вне 0.005..0.5, беру " + d.sensitivity());
            sensitivity = d.sensitivity();
        }
        int refresh = c.getInt("rpg.refresh-ticks", d.refreshTicks());
        if (refresh < 2 || refresh > 200) {
            warn.accept("rpg.refresh-ticks: " + refresh + " вне 2..200, беру " + d.refreshTicks());
            refresh = d.refreshTicks();
        }
        final Placeholders p = d.ph();
        final String pp = "rpg.placeholders.";
        final Placeholders ph = new Placeholders(
            c.getString(pp + "level", p.level()), c.getString(pp + "exp", p.exp()),
            c.getString(pp + "exp-needed", p.expNeeded()), c.getString(pp + "class", p.className()),
            c.getString(pp + "class-key", p.classKey()), c.getString(pp + "profession", p.profession()),
            c.getString(pp + "profession-key", p.professionKey()),
            c.getString(pp + "needs-profession", p.needsProfession()),
            c.getString(pp + "skill-points", p.skillPoints()), c.getString(pp + "attribute-points", p.attributePoints()));
        // config.yml от v1: старые сдвиги глифов (0.5 / 2.0) теперь лишние — формула клиента учтена в коде.
        double offX = c.getDouble("art.glyph-offset-x", d.glyphOffsetX());
        double offY = c.getDouble("art.glyph-offset-y", d.glyphOffsetY());
        if (c.contains("rpg.eye-offset") || c.contains("rpg.player-scale")) {
            warn.accept("это config.yml от первой версии меню: rpg.eye-offset и rpg.player-scale больше не нужны, "
                + "плейсхолдеры теперь RoyalQuestClasses/MMOCore — сверьте файл с новым (удалите его, и он создастся заново)");
            if (offX == 0.5 && offY == 2.0) {
                offX = 0;
                offY = 0;
            }
        }
        Material noClass = material(c.getString("rpg.no-class-icon", d.noClassIcon().name()), warn, "rpg.no-class-icon");
        if (noClass == null) {
            noClass = d.noClassIcon();
        }
        return new Settings(
            c.getString("messages.prefix", d.prefix()),
            c.getString("dialog.title", d.dialogTitle()),
            c.getString("dialog.body", d.dialogBody()),
            List.copyOf(menus),
            sensitivity,
            c.getBoolean("rpg.invulnerable", d.invulnerable()),
            refresh,
            c.getBoolean("rpg.debug-clicks", d.debugClicks()),
            ph,
            resources(c.getConfigurationSection("rpg.resource"), d.resources()),
            stats(c, "rpg.stats", d.stats()),
            stats(c, "rpg.equipment-lines", d.equipmentLines()),
            classes(c.getConfigurationSection("rpg.classes"), d.classes(), warn),
            strings(c.getConfigurationSection("rpg.professions"), d.professions()),
            noClass,
            jewelry(c.getConfigurationSection("rpg.jewelry"), d.jewelry(), warn),
            commands(c, "hud.hide-commands"),
            commands(c, "hud.show-commands"),
            c.getBoolean("art.shader-cursor", d.shaderCursor()),
            offX,
            offY,
            c.getBoolean("open-key.swap-hands", d.swapHandsOpens()),
            c.getBoolean("open-key.sneak", d.swapHandsSneak()),
            Math.max(1, Math.min(6, c.getInt("stash.base-rows", d.stashBaseRows()))),
            Math.max(0, Math.min(30, c.getInt("stash.max-extra-rows", d.stashMaxExtraRows()))));
    }

    private static Resources resources(final ConfigurationSection r, final Resources fallback) {
        if (r == null) {
            return fallback;
        }
        final Map<String, String> classes = new LinkedHashMap<>();
        final ConfigurationSection cs = r.getConfigurationSection("classes");
        if (cs != null) {
            for (final String k : cs.getKeys(false)) {
                classes.put(k.toLowerCase(Locale.ROOT), String.valueOf(cs.getString(k)).toLowerCase(Locale.ROOT));
            }
        } else {
            classes.putAll(fallback.classes());
        }
        final Map<String, ResourceType> types = new LinkedHashMap<>(fallback.types());
        final ConfigurationSection ts = r.getConfigurationSection("types");
        if (ts != null) {
            for (final String id : ts.getKeys(false)) {
                final ConfigurationSection t = ts.getConfigurationSection(id);
                if (t == null) {
                    continue;
                }
                final String key = id.toLowerCase(Locale.ROOT);
                final ResourceType base = types.getOrDefault(key, new ResourceType(key, id, "", ""));
                types.put(key, new ResourceType(key, t.getString("label", base.label()),
                    t.getString("current", base.current()), t.getString("max", base.max())));
            }
        }
        return new Resources(Map.copyOf(classes), r.getString("name", fallback.namePlaceholder()),
            r.getString("energy-name", fallback.energyName()), Map.copyOf(types));
    }

    private static Map<String, ClassInfo> classes(final ConfigurationSection s, final Map<String, ClassInfo> fallback,
                                                  final Consumer<String> warn) {
        if (s == null) {
            return fallback;
        }
        final Map<String, ClassInfo> out = new LinkedHashMap<>();
        for (final String k : s.getKeys(false)) {
            final ConfigurationSection e = s.getConfigurationSection(k);
            if (e == null) {
                continue;
            }
            final String key = k.toLowerCase(Locale.ROOT);
            final ClassInfo base = fallback.get(key);
            Material icon = material(e.getString("icon", base == null ? "BOOK" : base.icon().name()), warn,
                "rpg.classes." + k + ".icon");
            if (icon == null) {
                icon = Material.BOOK;
            }
            final List<String> profs = e.isList("professions")
                ? e.getStringList("professions").stream().map(x -> x.toLowerCase(Locale.ROOT)).toList()
                : base == null ? List.of() : base.professions();
            out.put(key, new ClassInfo(key, e.getString("name", base == null ? k : base.name()), icon, List.copyOf(profs)));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static Map<String, String> strings(final ConfigurationSection s, final Map<String, String> fallback) {
        if (s == null) {
            return fallback;
        }
        final Map<String, String> out = new LinkedHashMap<>();
        for (final String k : s.getKeys(false)) {
            out.put(k.toLowerCase(Locale.ROOT), String.valueOf(s.getString(k)));
        }
        return Map.copyOf(out);
    }

    private static Map<String, JewelRule> jewelry(final ConfigurationSection s, final Map<String, JewelRule> fallback,
                                                  final Consumer<String> warn) {
        final Map<String, JewelRule> out = new LinkedHashMap<>(fallback);
        if (s == null) {
            return Map.copyOf(out);
        }
        for (final String slot : JEWEL_SLOTS) {
            final ConfigurationSection e = s.getConfigurationSection(slot);
            if (e == null) {
                continue;
            }
            final Set<String> types = new java.util.HashSet<>();
            for (final String t : e.getStringList("types")) {
                types.add(t.strip().toUpperCase(Locale.ROOT));
            }
            final Set<Material> mats = new java.util.HashSet<>();
            for (final String raw : e.getStringList("materials")) {
                final Material mat = material(raw, warn, "rpg.jewelry." + slot + ".materials");
                if (mat != null) {
                    mats.add(mat);
                }
            }
            final List<String> nbt = new ArrayList<>();
            for (final String raw : e.getStringList("nbt")) {
                if (!raw.isBlank()) {
                    nbt.add(raw.strip());
                }
            }
            out.put(slot, new JewelRule(Set.copyOf(types), Set.copyOf(mats), List.copyOf(nbt)));
        }
        return Map.copyOf(out);
    }

    private static Material material(final String raw, final Consumer<String> warn, final String path) {
        final Material m = raw == null ? null : Material.matchMaterial(raw.strip());
        if (m == null || !m.isItem()) {
            warn.accept(path + ": нет такого предмета «" + raw + "»");
            return null;
        }
        return m;
    }

    /** Команды консоли без ведущего «/»; пустые строки пропускаются. */
    private static List<String> commands(final ConfigurationSection c, final String path) {
        final List<String> out = new ArrayList<>();
        for (final String raw : c.getStringList(path)) {
            final String cmd = raw.strip().startsWith("/") ? raw.strip().substring(1) : raw.strip();
            if (!cmd.isEmpty()) {
                out.add(cmd);
            }
        }
        return List.copyOf(out);
    }

    private static List<Stat> stats(final ConfigurationSection c, final String path, final List<Stat> fallback) {
        if (!c.isList(path)) {
            return fallback;
        }
        final List<Stat> out = new ArrayList<>();
        for (final Map<?, ?> row : c.getMapList(path)) {
            final Object name = row.get("name");
            final Object value = row.get("value");
            final Object def = row.get("default");
            if (name != null && value != null) {
                final String n = name.toString();
                out.add(new Stat(n, value.toString(), def != null ? def.toString() : n.contains("%") ? "" : n));
            }
        }
        return List.copyOf(out);
    }
}
