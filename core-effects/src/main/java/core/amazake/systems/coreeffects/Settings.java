package core.amazake.systems.coreeffects;

import core.amazake.systems.coreeffects.effect.EffectType;
import core.amazake.systems.coreeffects.logic.Durations;
import core.amazake.systems.coreeffects.logic.Range;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/** Настройки из config.yml — неизменяемый снимок; неверное значение → предупреждение и значение по умолчанию. */
public record Settings(
    String prefix,
    boolean clearOnDeath,
    Map<EffectType, Common> common,
    Hud hud,
    Screen screen,
    Bleeding bleeding,
    Panic panic,
    Warmth warmth,
    Curse curse,
    Bandage bandage,
    Messages messages
) {

    /** Общее для каждого эффекта: название, длительность по умолчанию, чем лечится. */
    public record Common(String name, long defaultSeconds, Set<String> curedBy) {
    }

    public record Hud(boolean actionbar, int actionbarIntervalTicks, String separator, Map<EffectType, String> format,
                      String unconsciousFormat, boolean papi, String papiIdentifier) {
    }

    public record Screen(boolean enabled, int refreshTicks, boolean darkness, boolean blindnessWhenFainted,
                         boolean nausea) {
    }

    public record Bleeding(double damage, int damageIntervalTicks, double minHealth, int faintAfterTicks,
                           int refaintAfterTicks, int faintTicks, boolean particles, int particleIntervalTicks) {
    }

    public record Panic(double bonusDamage, double maxHealthPenalty, double slowness, boolean darkness,
                        int nauseaEveryTicks, int nauseaTicks, boolean disorient, double disorientStrength,
                        double disorientSpeed, Range twistChangeSeconds, Range twistDegrees, Phantoms phantoms) {
    }

    public record Phantoms(boolean enabled, Range intervalSeconds, int maxAlive, Range lifetimeSeconds,
                           Range distance, double approachSpeed, List<String> types, boolean sounds, Range burst,
                           double vanishDistance) {
    }

    public record Warmth(double heal, int healIntervalTicks, int deadlineTicks, List<Integer> warnAtPercent,
                         Set<String> quenchedBy, int nauseaAtPercent, String deathMessage) {
    }

    public record Curse(Range intervalSeconds, Range amount, double wholeStackChance, boolean hotbar,
                        boolean offhand, boolean dropInstead, Set<String> protectMaterials, List<String> protectNbt,
                        boolean protectNamed, boolean protectCustomModel, int pickupDelayTicks) {
    }

    public record Bandage(String material, String model, String name, List<String> lore, int cooldownTicks,
                          boolean others) {
    }

    public record Messages(Map<String, String> map) {
        public String get(final String key) {
            return this.map.getOrDefault(key, "");
        }
    }

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
        Map.entry("usage", "<gray>/effects give <игрок> <эффект> [время] | remove <игрок> <эффект> | clear <игрок|all> | list [игрок] | bandage [игрок] [кол-во] | reload</gray>"),
        Map.entry("no-player", "<red>Игрок <white><player></white> не в сети.</red>"),
        Map.entry("no-effect", "<red>Нет эффекта «<white><effect></white>». Есть: bleeding, panic, warmth, curse.</red>"),
        Map.entry("bad-time", "<red>Неверное время «<white><time></white>»: 90, 2m, 1m30s, inf.</red>"),
        Map.entry("given", "<green>Игроку <white><player></white> — <effect> на <white><time></white>.</green>"),
        Map.entry("removed", "<green>У <white><player></white> снят эффект <effect>.</green>"),
        Map.entry("not-active", "<gray>У <white><player></white> нет эффекта <effect>.</gray>"),
        Map.entry("cleared", "<green>У <white><player></white> сняты все эффекты (<white><count></white>).</green>"),
        Map.entry("cleared-all", "<green>Сняты все эффекты у <white><count></white> игроков в сети.</green>"),
        Map.entry("list-empty", "<gray>У <white><player></white> нет эффектов.</gray>"),
        Map.entry("list", "<gray>Эффекты <white><player></white>: <list></gray>"),
        Map.entry("bandage-given", "<green>Игроку <white><player></white> выдано бинтов: <white><amount></white>.</green>"),
        Map.entry("reloaded", "<green>config.yml перечитан.</green>"),
        Map.entry("got-bleeding", "<#d02030>Вы истекаете кровью!</#d02030> <gray>Перевяжитесь бинтом.</gray>"),
        Map.entry("got-panic", "<dark_gray>Вас охватывает <#8a6cff>паника</#8a6cff>...</dark_gray>"),
        Map.entry("got-warmth", "<#ffb050>По телу разливается тепло.</#ffb050> <gray>Не забывайте пить молоко или зелье лечения!</gray>"),
        Map.entry("got-curse", "<#a040ff>На вас лежит проклятье.</#a040ff> <gray>Вещи начнут пропадать...</gray>"),
        Map.entry("ended", "<gray>Эффект <effect> прошёл.</gray>"),
        Map.entry("cured", "<green>Эффект <effect> снят.</green>"),
        Map.entry("bandaged-other", "<green>Вы перевязали <white><player></white>.</green>"),
        Map.entry("bandaged-by", "<green><white><player></white> перевязал вас.</green>"),
        Map.entry("bandage-useless", "<gray>Перевязывать нечего.</gray>"),
        Map.entry("faint", "<dark_red>Вы теряете сознание от потери крови...</dark_red>"),
        Map.entry("wake", "<gray>Вы приходите в себя.</gray>"),
        Map.entry("quenched", "<#ffb050>Жар отступает.</#ffb050>"),
        Map.entry("warmth-warn-1", "<#ffb050>Вам жарко. Выпейте молока или зелье лечения.</#ffb050>"),
        Map.entry("warmth-warn-2", "<#ff7a30>Жар нарастает! Срочно выпейте молока или зелье лечения!</#ff7a30>"),
        Map.entry("warmth-warn-3", "<red><bold>Вы сгораете изнутри!</bold> Ещё немного — и конец.</red>"),
        Map.entry("curse-dropped", "<#a040ff>Проклятье вырвало из рук: <white><item></white> ×<amount></#a040ff>"),
        Map.entry("curse-took", "<#a040ff>Проклятье забрало: <white><item></white> ×<amount></#a040ff>"),
        Map.entry("death-bleeding", "<player> истёк кровью"),
        Map.entry("death-warmth", "<player> сгорел изнутри от жара")
    );

    public static final Settings DEFAULTS = from(new MemoryConfiguration(), w -> { });

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Map<EffectType, Common> common = new EnumMap<>(EffectType.class);
        common.put(EffectType.BLEEDING, common(c, EffectType.BLEEDING, "<#d02030>Кровотечение</#d02030>", 120, List.of("bandage"), warn));
        common.put(EffectType.PANIC, common(c, EffectType.PANIC, "<#8a6cff>Паника</#8a6cff>", 30, List.of("milk"), warn));
        common.put(EffectType.WARMTH, common(c, EffectType.WARMTH, "<#ffb050>Тепло</#ffb050>", 300, List.of(), warn));
        common.put(EffectType.CURSE, common(c, EffectType.CURSE, "<#a040ff>Проклятье</#a040ff>", 180, List.of(), warn));

        final Map<EffectType, String> fmt = new EnumMap<>(EffectType.class);
        fmt.put(EffectType.BLEEDING, str(c, "hud.format.bleeding", "<font:coreeffects:icons></font> <#ff4050><time></#ff4050>"));
        fmt.put(EffectType.PANIC, str(c, "hud.format.panic", "<font:coreeffects:icons></font> <#a890ff><time></#a890ff>"));
        fmt.put(EffectType.WARMTH, str(c, "hud.format.warmth", "<font:coreeffects:icons></font> <#ffb050><time></#ffb050> <gray><thirst></gray>"));
        fmt.put(EffectType.CURSE, str(c, "hud.format.curse", "<font:coreeffects:icons></font> <#c070ff><time></#c070ff>"));
        final Hud hud = new Hud(c.getBoolean("hud.actionbar", true),
            intIn(c, "hud.actionbar-interval-ticks", 20, 5, 200, warn),
            str(c, "hud.separator", "  "), Map.copyOf(fmt),
            str(c, "hud.unconscious", "<dark_red>Без сознания <white><time></white></dark_red>"),
            c.getBoolean("hud.placeholderapi", true),
            ident(c, "hud.placeholderapi-identifier", "coreeffects", warn));

        final Screen screen = new Screen(c.getBoolean("screen.overlay", true),
            intIn(c, "screen.refresh-seconds", 3, 1, 30, warn) * 20,
            c.getBoolean("screen.darkness", true), c.getBoolean("screen.blindness-when-fainted", true),
            c.getBoolean("screen.nausea", true));

        final Bleeding bleeding = new Bleeding(
            dblIn(c, "bleeding.damage", 1.0, 0, 40, warn),
            secTicks(c, "bleeding.damage-interval-seconds", 4, 1, 600, warn),
            dblIn(c, "bleeding.min-health", 2.0, 0, 1000, warn),
            secTicks(c, "bleeding.faint-after-seconds", 60, 0, 86400, warn),
            secTicks(c, "bleeding.refaint-after-seconds", 30, 1, 86400, warn),
            secTicks(c, "bleeding.faint-seconds", 8, 1, 600, warn),
            c.getBoolean("bleeding.particles", true),
            intIn(c, "bleeding.particle-interval-ticks", 5, 1, 100, warn));

        final List<String> phantomTypes = new ArrayList<>();
        for (final String t : c.getStringList("panic.phantoms.types")) {
            phantomTypes.add(t.trim().toUpperCase(Locale.ROOT));
        }
        if (!c.contains("panic.phantoms.types")) {
            phantomTypes.addAll(List.of("ZOMBIE", "SKELETON", "HUSK", "ENDERMAN", "WITHER_SKELETON", "PLAYER"));
        }
        final Phantoms phantoms = new Phantoms(c.getBoolean("panic.phantoms.enabled", true),
            range(c, "panic.phantoms.interval-seconds", new Range(1, 2), 1, 600, warn),
            intIn(c, "panic.phantoms.max-alive", 10, 0, 40, warn),
            range(c, "panic.phantoms.lifetime-seconds", new Range(2, 4), 1, 60, warn),
            range(c, "panic.phantoms.distance", new Range(2, 5), 1, 32, warn),
            dblIn(c, "panic.phantoms.approach-speed", 0.3, 0, 1, warn),
            List.copyOf(phantomTypes), c.getBoolean("panic.phantoms.sounds", true),
            range(c, "panic.phantoms.burst", new Range(2, 3), 1, 10, warn),
            dblIn(c, "panic.phantoms.vanish-distance", 0.7, 0.2, 5, warn));
        final Panic panic = new Panic(
            dblIn(c, "panic.bonus-damage", 2.5, 0, 100, warn),
            dblIn(c, "panic.max-health-penalty", 4.0, 0, 100, warn),
            dblIn(c, "panic.slowness", 0.35, 0, 0.95, warn),
            c.getBoolean("panic.darkness", true),
            secTicks(c, "panic.nausea-every-seconds", 12, 0, 3600, warn),
            secTicks(c, "panic.nausea-seconds", 4, 1, 60, warn),
            c.getBoolean("panic.disorient", true),
            dblIn(c, "panic.disorient-strength", 0.75, 0, 1, warn),
            dblIn(c, "panic.disorient-speed", 0.2, 0.01, 1, warn),
            range(c, "panic.twist-change-seconds", new Range(2, 4), 1, 60, warn),
            range(c, "panic.twist-degrees", new Range(60, 300), 0, 360, warn),
            phantoms);

        final List<Integer> warnAt = new ArrayList<>();
        for (final Object o : c.getList("warmth.warn-at-percent", List.of(50, 75, 90))) {
            if (o instanceof Number n && n.intValue() > 0 && n.intValue() < 100) {
                warnAt.add(n.intValue());
            } else {
                warn.accept("warmth.warn-at-percent: «" + o + "» — нужно число 1..99, пропускаю");
            }
        }
        warnAt.sort(Integer::compare);
        final Warmth warmth = new Warmth(
            dblIn(c, "warmth.heal", 0.5, 0, 40, warn),
            secTicks(c, "warmth.heal-interval-seconds", 4, 1, 600, warn),
            secTicks(c, "warmth.deadline-seconds", 120, 0, 86400, warn),
            List.copyOf(warnAt.size() > 3 ? warnAt.subList(warnAt.size() - 3, warnAt.size()) : warnAt),
            tokens(c, "warmth.quenched-by", List.of("milk", "healing-potion")),
            intIn(c, "warmth.nausea-at-percent", 90, 0, 100, warn),
            str(c, "warmth.death-message", ""));

        final Set<String> protect = new LinkedHashSet<>();
        for (final String m : c.getStringList("curse.protect-materials")) {
            protect.add(m.trim().toUpperCase(Locale.ROOT));
        }
        final Curse curse = new Curse(
            range(c, "curse.interval-seconds", new Range(8, 15), 1, 3600, warn),
            range(c, "curse.amount", new Range(1, 3), 1, 64, warn),
            dblIn(c, "curse.whole-stack-chance", 0.0, 0, 1, warn),
            c.getBoolean("curse.hotbar", true), c.getBoolean("curse.offhand", false),
            c.getBoolean("curse.drop-instead", true), Set.copyOf(protect),
            List.copyOf(c.contains("curse.protect-nbt") ? c.getStringList("curse.protect-nbt")
                : List.of("MMOITEMS_ITEM_ID", "nexo:id", "coreskyrift:", "coreportals:", "rezusmenu:", "corerpg:", "zatochka", "magicseals")),
            c.getBoolean("curse.protect-named", false), c.getBoolean("curse.protect-custom-model", true),
            intIn(c, "curse.pickup-delay-ticks", 60, 0, 1200, warn));

        final Bandage bandage = new Bandage(str(c, "bandage.material", "PAPER").toUpperCase(Locale.ROOT),
            str(c, "bandage.model", "coreeffects:bandage"), str(c, "bandage.name", "<white>Бинт</white>"),
            c.contains("bandage.lore") ? List.copyOf(c.getStringList("bandage.lore"))
                : List.of("<gray>ПКМ — перевязать рану.</gray>", "<gray>ПКМ по игроку — перевязать его.</gray>",
                    "<dark_gray>Останавливает кровотечение.</dark_gray>"),
            intIn(c, "bandage.cooldown-ticks", 20, 0, 1200, warn), c.getBoolean("bandage.others", true));

        final Map<String, String> msgs = new java.util.HashMap<>(DEFAULT_MESSAGES);
        final ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) {
            for (final String k : ms.getKeys(false)) {
                msgs.put(k, ms.getString(k, ""));
            }
        }
        return new Settings(str(c, "prefix", "<dark_gray>[<#d02030>Эффекты</#d02030>]</dark_gray> "),
            c.getBoolean("clear-on-death", true), Map.copyOf(common), hud, screen, bleeding, panic, warmth, curse,
            bandage, new Messages(Map.copyOf(msgs)));
    }

    public Common of(final EffectType t) {
        return this.common.get(t);
    }

    // ------------------------------------------------------------------------------------------------ разбор

    private static Common common(final ConfigurationSection c, final EffectType t, final String name, final long secs,
                                 final List<String> cured, final Consumer<String> warn) {
        final String p = t.id() + ".";
        long def = secs;
        final Object raw = c.get(p + "default-seconds");
        if (raw != null) {
            final Long v = Durations.parseSeconds(raw.toString());
            if (v == null) {
                warn.accept(p + "default-seconds: «" + raw + "» — нужно 90, 2m, 1m30s или inf; беру " + secs);
            } else {
                def = v;
            }
        }
        return new Common(str(c, p + "name", name), def, tokens(c, p + "cured-by", cured));
    }

    private static Set<String> tokens(final ConfigurationSection c, final String path, final List<String> def) {
        final List<String> raw = c.contains(path) ? c.getStringList(path) : def;
        final Set<String> out = new LinkedHashSet<>();
        for (final String s : raw) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
        }
        return Set.copyOf(out);
    }

    private static String str(final ConfigurationSection c, final String path, final String def) {
        final String s = c.getString(path);
        return s == null ? def : s;
    }

    private static String ident(final ConfigurationSection c, final String path, final String def, final Consumer<String> warn) {
        final String s = str(c, path, def).trim().toLowerCase(Locale.ROOT);
        if (!s.matches("[a-z0-9_-]{1,32}")) {
            warn.accept(path + ": «" + s + "» — нужны латинские буквы, цифры, _ и -; беру " + def);
            return def;
        }
        return s;
    }

    private static int intIn(final ConfigurationSection c, final String path, final int def, final int lo, final int hi,
                             final Consumer<String> warn) {
        final Object raw = c.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= lo && n.intValue() <= hi) {
            return n.intValue();
        }
        warn.accept(path + ": «" + raw + "» — нужно целое " + lo + ".." + hi + ", беру " + def);
        return def;
    }

    private static int secTicks(final ConfigurationSection c, final String path, final int defSeconds, final int lo,
                                final int hi, final Consumer<String> warn) {
        final Object raw = c.get(path);
        if (raw == null) {
            return defSeconds * 20;
        }
        if (raw instanceof Number n && n.doubleValue() >= lo && n.doubleValue() <= hi) {
            return (int) Math.round(n.doubleValue() * 20);
        }
        warn.accept(path + ": «" + raw + "» — нужно число секунд " + lo + ".." + hi + ", беру " + defSeconds);
        return defSeconds * 20;
    }

    private static double dblIn(final ConfigurationSection c, final String path, final double def, final double lo,
                                final double hi, final Consumer<String> warn) {
        final Object raw = c.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= lo && n.doubleValue() <= hi) {
            return n.doubleValue();
        }
        warn.accept(path + ": «" + raw + "» — нужно число " + lo + ".." + hi + ", беру " + def);
        return def;
    }

    private static Range range(final ConfigurationSection c, final String path, final Range def, final int lo,
                               final int hi, final Consumer<String> warn) {
        final Object raw = c.get(path);
        if (raw == null) {
            return def;
        }
        final Range r = Range.parse(raw, lo, hi);
        if (r == null) {
            warn.accept(path + ": «" + raw + "» — нужно число или диапазон «a-b» в " + lo + ".." + hi + ", беру " + def);
            return def;
        }
        return r;
    }
}
