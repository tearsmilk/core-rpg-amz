package core.amazake.systems.rezusmenu.profiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Раздел {@code profiles} config.yml. Неизменяемый снимок. */
public record ProfileConfig(
    boolean enabled,
    int freeSlots,
    int maxSlots,
    JoinMode openOnJoin,
    boolean requireChoice,
    int joinDelayTicks,
    double price,
    String currency,
    String priceLabel,
    Material priceIcon,
    String balancePlaceholder,
    List<String> takeCommands,
    String newLocation,
    List<String> newCommands,
    List<String> lobbyCommands,
    boolean separateEnderChest,
    boolean separateVanillaExp,
    boolean separateEffects,
    List<String> pdcPrefixes,
    String bridgeMode,
    List<String> onSave,
    List<String> onLoad,
    List<String> onCreate,
    List<Extra> extras,
    String levelPlaceholder,
    String classKeyPlaceholder,
    List<CardStat> stats,
    List<ClassChoice> classes,
    Map<String, String> worldNames,
    List<Area> areas,
    double characterYaw,
    double characterPitch
) {

    /** Когда показывать меню профилей при входе. */
    public enum JoinMode {
        /** Всегда. */
        ALWAYS,
        /** Если профилей больше одного или у текущего нет класса. */
        MULTIPLE,
        /** Только если у текущего профиля нет класса (новичок). */
        NEW,
        /** Никогда (только /menuses open profiles). */
        NEVER
    }

    /** Значение, своё у каждого профиля, которого нет в данных игрока (уровень core-vault): читается плейсхолдером, возвращается командой. */
    public record Extra(String name, String read, String restore, String fresh) {
    }

    /** Строка характеристики на карточке: название и значение — плейсхолдеры или текст. */
    public record CardStat(String name, String fallbackName, String value) {
    }

    /** Класс, который можно выбрать для нового профиля. */
    public record ClassChoice(String key, String name, String color, Material icon, List<String> description) {
    }

    /** Прямоугольник мира с названием места (если нет WorldGuard). */
    public record Area(String name, String world, int minX, int minZ, int maxX, int maxZ) {

        public boolean contains(final String w, final double x, final double z) {
            return this.world.equalsIgnoreCase(w) && x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
        }
    }

    public static final List<ClassChoice> DEFAULT_CLASSES = List.of(
        new ClassChoice("swordsman", "Мечник", "<red>", Material.IRON_SWORD,
            List.of("Ближний бой: щит и доспехи,", "крепкий и защищённый.")),
        new ClassChoice("archer", "Стрелок", "<green>", Material.BOW,
            List.of("Дальний бой: лук и арбалет,", "бьёт точно издалека.")),
        new ClassChoice("mage", "Маг", "<aqua>", Material.BLAZE_ROD,
            List.of("Стихии и заклинания,", "урон и контроль.")),
        new ClassChoice("thief", "Вор", "<yellow>", Material.GOLDEN_SWORD,
            List.of("Скрытный и ловкий боец", "ближнего боя.")));

    public static final List<CardStat> DEFAULT_STATS = List.of(
        new CardStat("%hl_stats_primary_name%", "Сила", "%hl_stats_primary%"),
        new CardStat("Ловкость", "Ловкость", "%hl_stats_agility%"),
        new CardStat("Выносливость", "Выносливость", "%hl_stats_stamina%"));

    public static final ProfileConfig DEFAULTS = new ProfileConfig(true, 2, 4, JoinMode.ALWAYS, true, 40,
        649, "vault", "649", Material.DIAMOND, "", List.of(), "", List.of(), List.of(), true, true, true,
        List.of("rezusmenu:jewel_", "rezusmenu:stash_"), "auto", List.of(), List.of(), List.of(),
        List.of(new Extra("corevault-level", "%corevault_exp_total%", "level set {player} exp {value}",
            "level set {player} exp 0")),
        "%hl_level%", "%hl_class_key%", DEFAULT_STATS, DEFAULT_CLASSES, Map.of(), List.of(), 28, 10);

    public static ProfileConfig from(final ConfigurationSection root, final Consumer<String> warn) {
        final ProfileConfig d = DEFAULTS;
        final ConfigurationSection c = root.getConfigurationSection("profiles");
        if (c == null) {
            return d;
        }
        int max = c.getInt("max-slots", d.maxSlots());
        if (max < 1 || max > 12) {
            warn.accept("profiles.max-slots: " + max + " вне 1..12, беру " + d.maxSlots());
            max = d.maxSlots();
        }
        final int free = Math.max(1, Math.min(max, c.getInt("free-slots", d.freeSlots())));
        JoinMode join = d.openOnJoin();
        final String rawJoin = c.getString("open-on-join", join.name());
        try {
            join = JoinMode.valueOf(rawJoin.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            warn.accept("profiles.open-on-join: «" + rawJoin + "» — always, multiple, new или never");
        }
        final String currency = c.getString("buy.currency", d.currency()).strip().toLowerCase(Locale.ROOT);
        if (!List.of("vault", "command", "none").contains(currency)) {
            warn.accept("profiles.buy.currency: «" + currency + "» — vault, command или none");
        }
        Material icon = Material.matchMaterial(c.getString("buy.icon", d.priceIcon().name()));
        if (icon == null || !icon.isItem()) {
            icon = d.priceIcon();
        }
        final double price = Math.max(0, c.getDouble("buy.price", d.price()));
        final List<Extra> extras = new ArrayList<>();
        if (c.isList("extra-values")) {
            for (final Map<?, ?> m : c.getMapList("extra-values")) {
                final Object read = m.get("read");
                final Object restore = m.get("restore");
                if (read != null && restore != null) {
                    extras.add(new Extra(String.valueOf(m.get("name")), read.toString(), strip(restore.toString()),
                        m.get("fresh") == null ? "" : strip(m.get("fresh").toString())));
                }
            }
        } else {
            extras.addAll(d.extras());
        }
        final List<CardStat> stats = new ArrayList<>();
        if (c.isList("card.stats")) {
            for (final Map<?, ?> m : c.getMapList("card.stats")) {
                final Object name = m.get("name");
                final Object value = m.get("value");
                if (name != null && value != null) {
                    final Object def = m.get("default");
                    stats.add(new CardStat(name.toString(), def != null ? def.toString() : name.toString().contains("%")
                        ? "" : name.toString(), value.toString()));
                }
            }
        } else {
            stats.addAll(d.stats());
        }
        final List<ClassChoice> classes = new ArrayList<>();
        final ConfigurationSection cs = c.getConfigurationSection("classes");
        if (cs != null) {
            for (final String k : cs.getKeys(false)) {
                final ConfigurationSection e = cs.getConfigurationSection(k);
                if (e == null) {
                    continue;
                }
                Material m = Material.matchMaterial(e.getString("icon", "BOOK"));
                if (m == null || !m.isItem()) {
                    warn.accept("profiles.classes." + k + ".icon: нет такого предмета");
                    m = Material.BOOK;
                }
                classes.add(new ClassChoice(k.toLowerCase(Locale.ROOT), e.getString("name", k), e.getString("color", "<white>"),
                    m, e.isList("description") ? List.copyOf(e.getStringList("description"))
                    : List.of(e.getString("description", ""))));
            }
        } else {
            classes.addAll(d.classes());
        }
        final Map<String, String> worlds = new LinkedHashMap<>();
        final ConfigurationSection ws = c.getConfigurationSection("locations.worlds");
        if (ws != null) {
            for (final String k : ws.getKeys(false)) {
                worlds.put(k, String.valueOf(ws.getString(k)));
            }
        }
        final List<Area> areas = new ArrayList<>();
        for (final Map<?, ?> m : c.getMapList("locations.areas")) {
            try {
                final List<?> from = (List<?>) m.get("from");
                final List<?> to = (List<?>) m.get("to");
                final int x1 = ((Number) from.get(0)).intValue();
                final int z1 = ((Number) from.get(1)).intValue();
                final int x2 = ((Number) to.get(0)).intValue();
                final int z2 = ((Number) to.get(1)).intValue();
                areas.add(new Area(String.valueOf(m.get("name")), String.valueOf(m.get("world")), Math.min(x1, x2),
                    Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)));
            } catch (final RuntimeException e) {
                warn.accept("profiles.locations.areas: строка " + m + " — нужно {name, world, from: [x, z], to: [x, z]}");
            }
        }
        final String mode = c.getString("class-bridge.mode", d.bridgeMode()).strip().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "api", "commands", "none").contains(mode)) {
            warn.accept("profiles.class-bridge.mode: «" + mode + "» — auto, api, commands или none");
        }
        return new ProfileConfig(
            c.getBoolean("enabled", d.enabled()),
            free,
            max,
            join,
            c.getBoolean("require-choice", d.requireChoice()),
            Math.max(0, Math.min(1200, c.getInt("join-delay-ticks", d.joinDelayTicks()))),
            price,
            currency,
            c.getString("buy.label", price == Math.rint(price) ? String.valueOf((long) price) : String.valueOf(price)),
            icon,
            c.getString("buy.balance-placeholder", d.balancePlaceholder()),
            commands(c, "buy.take-commands"),
            c.getString("new-profile.location", d.newLocation()),
            commands(c, "new-profile.commands"),
            commands(c, "lobby.commands"),
            c.getBoolean("separate.ender-chest", d.separateEnderChest()),
            c.getBoolean("separate.vanilla-exp", d.separateVanillaExp()),
            c.getBoolean("separate.effects", d.separateEffects()),
            c.isList("separate.pdc-prefixes") ? List.copyOf(c.getStringList("separate.pdc-prefixes")) : d.pdcPrefixes(),
            mode,
            commands(c, "class-bridge.on-save"),
            commands(c, "class-bridge.on-load"),
            commands(c, "class-bridge.on-create"),
            List.copyOf(extras),
            c.getString("card.level", d.levelPlaceholder()),
            c.getString("card.class-key", d.classKeyPlaceholder()),
            List.copyOf(stats),
            List.copyOf(classes),
            Map.copyOf(worlds),
            List.copyOf(areas),
            c.getDouble("character.yaw", d.characterYaw()),
            Math.max(0, Math.min(45, c.getDouble("character.pitch", d.characterPitch()))));
    }

    public ClassChoice classChoice(final String key) {
        for (final ClassChoice c : this.classes) {
            if (c.key().equalsIgnoreCase(key)) {
                return c;
            }
        }
        return null;
    }

    private static String strip(final String cmd) {
        final String s = cmd.strip();
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private static List<String> commands(final ConfigurationSection c, final String path) {
        final List<String> out = new ArrayList<>();
        for (final String raw : c.getStringList(path)) {
            final String cmd = strip(raw);
            if (!cmd.isEmpty()) {
                out.add(cmd);
            }
        }
        return List.copyOf(out);
    }
}
