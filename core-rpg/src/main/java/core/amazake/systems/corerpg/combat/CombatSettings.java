package core.amazake.systems.corerpg.combat;

import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

/**
 * Настройки боя из {@code combat.yml}: неизменяемый снимок, подменяется целиком при reload. Неверные значения —
 * предупреждение в консоль и значение по умолчанию.
 */
public record CombatSettings(
    Attack attack,
    Passives passives,
    DamageVs damageVs,
    Crit crit,
    Statuses statuses,
    Evasion evasion,
    ArmorSharpen armorSharpen,
    Resistance resistance,
    Lifesteal lifesteal,
    boolean healOnKill,
    Regen regen
) {

    /** Звук: ключ ресурса ({@code entity.player.attack.crit}), пустой — без звука. */
    public record SoundSpec(String key, float volume, float pitch) {
        public static final SoundSpec NONE = new SoundSpec("", 1.0f, 1.0f);

        public boolean enabled() {
            return !this.key.isEmpty();
        }
    }

    /** Какие удары считаются атакой игрока и усиливаются (шаги 1–5). */
    public record Attack(Set<DamageCause> causes, boolean sharpenWeapon) {
    }

    public record Rage(boolean enabled, double percentPerStep, double stepPercent, double maxPercent) {
    }

    public record Headshot(boolean enabled, double multiplier, double eyeOffset, String message) {
    }

    public record Backstab(boolean enabled, double angle, boolean meleeOnly, String message) {
    }

    public record DualWield(boolean enabled, double percent, Map<Material, Double> attack) {
    }

    public record Passives(Rage rage, Headshot headshot, Backstab backstab, DualWield dualWield) {
    }

    /** {@code damage_vs:monster} против монстров и стихийные {@code damage_vs:<стихия>} против всех. */
    public record DamageVs(boolean monster, List<String> elements) {
    }

    public record Crit(boolean enabled, double capPercent, double minDamagePercent, Particle particle, int particleCount,
                       SoundSpec sound, String message) {
    }

    /** Статус при ударе: эффект зелья (ключ ванили) или огонь. */
    public record Status(boolean enabled, String effect, int durationTicks, int amplifier) {
    }

    public record Statuses(Status bleed, Status poison, Status burn) {
    }

    public record Evasion(boolean enabled, double cap, Set<DamageCause> causes, SoundSpec sound, String message,
                          String attackerMessage) {
    }

    public record ArmorSharpen(boolean enabled, Set<DamageCause> ignoreCauses) {
    }

    /** Цель сопротивления: {@code resistance:<id>} срабатывает по причине урона или по атакующему. */
    public record ResistanceTarget(String id, Set<DamageCause> causes, Set<EntityType> attackers, Set<AttackerGroup> groups) {
    }

    public record Resistance(boolean enabled, double cap, Set<DamageCause> ignoreCauses, List<ResistanceTarget> targets) {
    }

    public record Lifesteal(boolean enabled, double capPercent, boolean limitToTargetHealth) {
    }

    public record Regen(boolean enabled, long periodTicks) {
    }

    private static final Pattern RESOURCE_KEY = Pattern.compile("[a-z0-9_.\\-/:]+");
    private static final Pattern TARGET_ID = Pattern.compile("[a-z0-9_]+");

    /** Значения по умолчанию = разбор пустой конфигурации. */
    public static final CombatSettings DEFAULTS = from(new MemoryConfiguration(), w -> { });

    /**
     * Описание пассивки для меню (MiniMessage) по текущим числам из combat.yml; выключенная пассивка помечена.
     * null — такой пассивки бой не знает.
     */
    public String passiveDescription(final String key) {
        if (key == null) {
            return null;
        }
        final Passives p = this.passives;
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "rage" -> labeled(p.rage().enabled(), "Ярость: +" + Text.num(p.rage().percentPerStep())
                + "% урона за каждые " + Text.num(p.rage().stepPercent()) + "% потерянного здоровья (до +"
                + Text.num(p.rage().maxPercent()) + "%)", "Ярость");
            case "headshot" -> labeled(p.headshot().enabled(),
                "Выстрел в голову: ×" + Text.num(p.headshot().multiplier()) + " урона", "Выстрел в голову");
            case "backstab" -> labeled(p.backstab().enabled(),
                "Удар в спину всегда критический" + (p.backstab().meleeOnly() ? " (ближний бой)" : ""), "Удар в спину");
            case "dual_wield" -> labeled(p.dualWield().enabled(),
                "Два клинка: +" + Text.num(p.dualWield().percent()) + "% атаки второй руки", "Два клинка");
            default -> null;
        };
    }

    private static String labeled(final boolean enabled, final String text, final String name) {
        return enabled ? text : name + " <dark_gray>(отключено)</dark_gray>";
    }

    public static CombatSettings from(final ConfigurationSection root, final Consumer<String> warn) {
        final ConfigurationSection config = root == null ? new MemoryConfiguration() : root;
        final Parser p = new Parser(config, warn);

        final Attack attack = new Attack(
            p.causes("attack.causes", EnumSet.of(DamageCause.ENTITY_ATTACK, DamageCause.ENTITY_SWEEP_ATTACK, DamageCause.PROJECTILE)),
            p.bool("attack.sharpen-weapon", true));

        final Passives passives = new Passives(
            new Rage(p.enabled("passives.rage", true),
                p.num("passives.rage.percent-per-step", 2.0, 0.0, 1000.0),
                p.num("passives.rage.step-percent", 5.0, 0.1, 100.0),
                p.num("passives.rage.max-percent", 40.0, 0.0, 10000.0)),
            new Headshot(p.enabled("passives.headshot", true),
                p.num("passives.headshot.multiplier", 1.5, 1.0, 100.0),
                p.num("passives.headshot.eye-offset", 0.3, -2.0, 2.0),
                p.str("passives.headshot.message", "<yellow>В голову!</yellow>")),
            new Backstab(p.enabled("passives.backstab", true),
                p.num("passives.backstab.angle", 120.0, 0.0, 180.0),
                p.bool("passives.backstab.melee-only", true),
                p.str("passives.backstab.message", "<dark_red>Удар в спину!</dark_red>")),
            new DualWield(p.enabled("passives.dual_wield", true),
                p.num("passives.dual_wield.percent", 60.0, 0.0, 1000.0),
                p.materialTable("passives.dual_wield.attack", defaultDualWieldTable())));

        final DamageVs damageVs = new DamageVs(p.bool("damage-vs.monster", true),
            p.ids("damage-vs.elements", List.of("fire", "air", "earth", "water")));

        final Crit crit = new Crit(p.enabled("crit", true),
            p.num("crit.cap-percent", 57.0, 0.0, 100.0),
            p.num("crit.min-damage-percent", 0.0, 0.0, 10000.0),
            p.particle("crit.particle", Particle.CRIT),
            (int) p.num("crit.particle-count", 12, 0, 200),
            p.sound("crit.sound", new SoundSpec("entity.player.attack.crit", 1.0f, 1.0f)),
            p.str("crit.message", "<gold>Критический удар!</gold>"));

        final Statuses statuses = new Statuses(
            p.status("statuses.bleed", "wither", 60, 0),
            p.status("statuses.poison", "poison", 60, 0),
            p.status("statuses.burn", "", 60, 0));

        final Evasion evasion = new Evasion(p.enabled("evasion", true),
            p.num("evasion.cap", 0.85, 0.0, 1.0),
            p.causes("evasion.causes", EnumSet.of(DamageCause.ENTITY_ATTACK, DamageCause.ENTITY_SWEEP_ATTACK, DamageCause.PROJECTILE)),
            p.sound("evasion.sound", new SoundSpec("entity.player.attack.nodamage", 1.0f, 1.4f)),
            p.str("evasion.message", "<green>Уворот!</green>"),
            p.str("evasion.attacker-message", "<gray>Промах!</gray>"));

        final ArmorSharpen armorSharpen = new ArmorSharpen(p.enabled("armor-sharpen", true),
            p.causes("armor-sharpen.ignore-causes", EnumSet.of(DamageCause.VOID, DamageCause.STARVATION, DamageCause.SUICIDE,
                DamageCause.KILL, DamageCause.CUSTOM, DamageCause.WORLD_BORDER)));

        final Resistance resistance = new Resistance(p.enabled("resistance", true),
            p.num("resistance.cap", 0.40, 0.0, 1.0),
            p.causes("resistance.ignore-causes", EnumSet.noneOf(DamageCause.class)),
            p.targets("resistance.targets"));

        final Lifesteal lifesteal = new Lifesteal(p.enabled("lifesteal", true),
            p.num("lifesteal.cap-percent", 20.0, 0.0, 100.0),
            p.bool("lifesteal.limit-to-target-health", false));

        final boolean healOnKill = p.enabled("heal-on-kill", true);
        final Regen regen = new Regen(p.enabled("regen", true), (long) p.num("regen.period-ticks", 20, 1, 1200));

        return new CombatSettings(attack, passives, damageVs, crit, statuses, evasion, armorSharpen, resistance, lifesteal,
            healOnKill, regen);
    }

    /** Атака мечей и топоров во второй руке (значение из подсказки предмета, включая базовую 1). */
    static Map<Material, Double> defaultDualWieldTable() {
        final Map<Material, Double> m = new EnumMap<>(Material.class);
        m.put(Material.WOODEN_SWORD, 4.0);
        m.put(Material.STONE_SWORD, 5.0);
        m.put(Material.COPPER_SWORD, 5.0);
        m.put(Material.IRON_SWORD, 6.0);
        m.put(Material.GOLDEN_SWORD, 4.0);
        m.put(Material.DIAMOND_SWORD, 7.0);
        m.put(Material.NETHERITE_SWORD, 8.0);
        m.put(Material.WOODEN_AXE, 7.0);
        m.put(Material.STONE_AXE, 9.0);
        m.put(Material.COPPER_AXE, 9.0);
        m.put(Material.IRON_AXE, 9.0);
        m.put(Material.GOLDEN_AXE, 7.0);
        m.put(Material.DIAMOND_AXE, 9.0);
        m.put(Material.NETHERITE_AXE, 10.0);
        return m;
    }

    /** Цели сопротивления по умолчанию (контракт, решение артефакта). DRAGON_BREATH устарел в API, но работает. */
    @SuppressWarnings("deprecation")
    static List<ResistanceTarget> defaultTargets() {
        final List<ResistanceTarget> list = new ArrayList<>();
        list.add(target("fire", EnumSet.of(DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA, DamageCause.HOT_FLOOR,
            DamageCause.CAMPFIRE), EnumSet.of(EntityType.BLAZE, EntityType.MAGMA_CUBE), EnumSet.noneOf(AttackerGroup.class)));
        list.add(target("chaos", EnumSet.of(DamageCause.MAGIC, DamageCause.WITHER, DamageCause.DRAGON_BREATH,
            DamageCause.SONIC_BOOM), EnumSet.noneOf(EntityType.class), EnumSet.noneOf(AttackerGroup.class)));
        list.add(target("undead", EnumSet.noneOf(DamageCause.class), EnumSet.noneOf(EntityType.class), EnumSet.of(AttackerGroup.UNDEAD)));
        list.add(target("humanoid", EnumSet.noneOf(DamageCause.class), EnumSet.noneOf(EntityType.class), EnumSet.of(AttackerGroup.HUMANOID)));
        list.add(target("water", EnumSet.of(DamageCause.DROWNING), EnumSet.of(EntityType.DROWNED, EntityType.GUARDIAN,
            EntityType.ELDER_GUARDIAN), EnumSet.noneOf(AttackerGroup.class)));
        list.add(target("air", EnumSet.of(DamageCause.FALL, DamageCause.FLY_INTO_WALL, DamageCause.LIGHTNING),
            EnumSet.of(EntityType.PHANTOM, EntityType.BREEZE, EntityType.GHAST), EnumSet.noneOf(AttackerGroup.class)));
        list.add(target("earth", EnumSet.of(DamageCause.FALLING_BLOCK, DamageCause.SUFFOCATION, DamageCause.CONTACT),
            EnumSet.noneOf(EntityType.class), EnumSet.noneOf(AttackerGroup.class)));
        list.add(target("poison", EnumSet.of(DamageCause.POISON), EnumSet.of(EntityType.SPIDER, EntityType.CAVE_SPIDER,
            EntityType.BEE), EnumSet.noneOf(AttackerGroup.class)));
        list.add(target("animal", EnumSet.noneOf(DamageCause.class), EnumSet.noneOf(EntityType.class), EnumSet.of(AttackerGroup.ANIMAL)));
        list.add(target("arthropod", EnumSet.noneOf(DamageCause.class), EnumSet.noneOf(EntityType.class), EnumSet.of(AttackerGroup.ARTHROPOD)));
        return List.copyOf(list);
    }

    private static ResistanceTarget target(final String id, final Set<DamageCause> causes, final Set<EntityType> attackers,
                                           final Set<AttackerGroup> groups) {
        return new ResistanceTarget(id, Collections.unmodifiableSet(causes), Collections.unmodifiableSet(attackers),
            Collections.unmodifiableSet(groups));
    }

    /** Разбор с предупреждениями: держит путь к значению в тексте предупреждения. */
    private static final class Parser {
        private final ConfigurationSection config;
        private final Consumer<String> warn;

        Parser(final ConfigurationSection config, final Consumer<String> warn) {
            this.config = config;
            this.warn = warn == null ? w -> { } : warn;
        }

        boolean bool(final String path, final boolean def) {
            if (!this.config.contains(path)) {
                return def;
            }
            final Object raw = this.config.get(path);
            if (raw instanceof Boolean b) {
                return b;
            }
            if (raw instanceof String s) {
                final String v = s.trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("да") || v.equals("yes") || v.equals("on")) {
                    return true;
                }
                if (v.equals("false") || v.equals("нет") || v.equals("no") || v.equals("off")) {
                    return false;
                }
            }
            this.warn.accept(path + ": «" + raw + "» — не true/false, беру " + def);
            return def;
        }

        /**
         * Флаг {@code <section>.enabled}. Если раздел записан скаляром ({@code bleed: false}), скаляр и есть флаг;
         * не булево значение — предупреждение и значение по умолчанию.
         */
        boolean enabled(final String section, final boolean def) {
            if (this.config.contains(section) && !this.config.isConfigurationSection(section)) {
                return this.bool(section, def);
            }
            return this.bool(section + ".enabled", def);
        }

        double num(final String path, final double def, final double min, final double max) {
            if (!this.config.contains(path)) {
                return def;
            }
            final Object raw = this.config.get(path);
            double value;
            if (raw instanceof Number n) {
                value = n.doubleValue();
            } else {
                try {
                    value = Double.parseDouble(String.valueOf(raw).trim().replace(',', '.'));
                } catch (final NumberFormatException e) {
                    this.warn.accept(path + ": «" + raw + "» — не число, беру " + fmt(def));
                    return def;
                }
            }
            if (!Double.isFinite(value) || value < min || value > max) {
                this.warn.accept(path + ": " + raw + " вне " + fmt(min) + ".." + fmt(max) + ", беру " + fmt(def));
                return def;
            }
            return value;
        }

        String str(final String path, final String def) {
            if (!this.config.contains(path)) {
                return def;
            }
            final Object raw = this.config.get(path);
            if (raw == null) {
                return "";
            }
            if (raw instanceof ConfigurationSection || raw instanceof List<?>) {
                this.warn.accept(path + ": ожидается строка, беру значение по умолчанию");
                return def;
            }
            return String.valueOf(raw);
        }

        Set<DamageCause> causes(final String path, final Set<DamageCause> def) {
            if (!this.config.contains(path)) {
                return Collections.unmodifiableSet(EnumSet.copyOf(def.isEmpty() ? EnumSet.noneOf(DamageCause.class) : def));
            }
            if (!this.config.isList(path)) {
                this.warn.accept(path + ": ожидается список причин урона, беру значения по умолчанию");
                return Collections.unmodifiableSet(def.isEmpty() ? EnumSet.noneOf(DamageCause.class) : EnumSet.copyOf(def));
            }
            final EnumSet<DamageCause> out = EnumSet.noneOf(DamageCause.class);
            for (final String raw : this.config.getStringList(path)) {
                final DamageCause cause = cause(raw);
                if (cause == null) {
                    this.warn.accept(path + ": неизвестная причина урона «" + raw + "», пропускаю");
                } else {
                    out.add(cause);
                }
            }
            return Collections.unmodifiableSet(out);
        }

        List<String> ids(final String path, final List<String> def) {
            if (!this.config.contains(path)) {
                return def;
            }
            if (!this.config.isList(path)) {
                this.warn.accept(path + ": ожидается список, беру " + def);
                return def;
            }
            final Set<String> out = new LinkedHashSet<>();
            for (final String raw : this.config.getStringList(path)) {
                final String id = raw.trim().toLowerCase(Locale.ROOT);
                if (TARGET_ID.matcher(id).matches()) {
                    out.add(id);
                } else {
                    this.warn.accept(path + ": неверный ключ «" + raw + "», пропускаю");
                }
            }
            return List.copyOf(out);
        }

        Particle particle(final String path, final Particle def) {
            if (!this.config.contains(path)) {
                return def;
            }
            final String raw = String.valueOf(this.config.get(path)).trim();
            if (raw.isEmpty() || raw.equalsIgnoreCase("none") || raw.equalsIgnoreCase("нет")) {
                return null;
            }
            try {
                final Particle particle = Particle.valueOf(raw.toUpperCase(Locale.ROOT));
                if (particle.getDataType() != Void.class) {
                    this.warn.accept(path + ": частица " + raw + " требует данных, беру " + def);
                    return def;
                }
                return particle;
            } catch (final IllegalArgumentException e) {
                this.warn.accept(path + ": неизвестная частица «" + raw + "», беру " + def);
                return def;
            }
        }

        SoundSpec sound(final String path, final SoundSpec def) {
            if (!this.config.contains(path)) {
                return def;
            }
            if (!this.config.isConfigurationSection(path)) {
                final String key = this.config.getString(path, "");
                if (key == null || key.isBlank()) {
                    return SoundSpec.NONE;
                }
                final String normalized = key.trim().toLowerCase(Locale.ROOT);
                if (!RESOURCE_KEY.matcher(normalized).matches()) {
                    this.warn.accept(path + ": неверный ключ звука «" + key + "», беру " + def.key());
                    return def;
                }
                return new SoundSpec(normalized, def.volume(), def.pitch());
            }
            final String rawKey = this.config.getString(path + ".key", def.key());
            String key = rawKey == null ? "" : rawKey.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !RESOURCE_KEY.matcher(key).matches()) {
                this.warn.accept(path + ".key: неверный ключ звука «" + rawKey + "», беру " + def.key());
                key = def.key();
            }
            final float volume = (float) this.num(path + ".volume", def.volume(), 0.0, 10.0);
            final float pitch = (float) this.num(path + ".pitch", def.pitch(), 0.5, 2.0);
            return key.isEmpty() ? SoundSpec.NONE : new SoundSpec(key, volume, pitch);
        }

        Status status(final String path, final String defEffect, final int defTicks, final int defAmplifier) {
            final boolean enabled = this.enabled(path, true);
            String effect = defEffect;
            if (!defEffect.isEmpty()) {
                final String raw = this.str(path + ".effect", defEffect).trim().toLowerCase(Locale.ROOT);
                final String bare = raw.startsWith("minecraft:") ? raw.substring("minecraft:".length()) : raw;
                if (bare.isEmpty() || !RESOURCE_KEY.matcher(bare).matches()) {
                    this.warn.accept(path + ".effect: неверный эффект «" + raw + "», беру " + defEffect);
                } else {
                    effect = bare;
                }
            }
            final int ticks = (int) this.num(path + ".duration-ticks", defTicks, 1, 20 * 60 * 10);
            final int amplifier = defEffect.isEmpty() ? 0 : (int) this.num(path + ".amplifier", defAmplifier, 0, 255);
            return new Status(enabled, effect, ticks, amplifier);
        }

        Map<Material, Double> materialTable(final String path, final Map<Material, Double> def) {
            if (!this.config.contains(path)) {
                return Collections.unmodifiableMap(def);
            }
            final ConfigurationSection section = this.config.getConfigurationSection(path);
            if (section == null) {
                this.warn.accept(path + ": ожидается таблица «МАТЕРИАЛ: атака», беру таблицу по умолчанию");
                return Collections.unmodifiableMap(def);
            }
            final Map<Material, Double> out = new EnumMap<>(Material.class);
            for (final String key : section.getKeys(false)) {
                final Material material = Material.matchMaterial(key);
                if (material == null || material.name().endsWith("AIR")) {
                    this.warn.accept(path + "." + key + ": неизвестный предмет, пропускаю");
                    continue;
                }
                final double value = this.num(path + "." + key, -1.0, 0.0, 1000.0);
                if (value >= 0.0) {
                    out.put(material, value);
                }
            }
            return Collections.unmodifiableMap(out);
        }

        List<ResistanceTarget> targets(final String path) {
            if (!this.config.contains(path)) {
                return defaultTargets();
            }
            final ConfigurationSection section = this.config.getConfigurationSection(path);
            if (section == null) {
                this.warn.accept(path + ": ожидается раздел целей, беру цели по умолчанию");
                return defaultTargets();
            }
            final Map<String, ResistanceTarget> out = new LinkedHashMap<>();
            for (final String rawId : section.getKeys(false)) {
                final String id = rawId.trim().toLowerCase(Locale.ROOT);
                if (!TARGET_ID.matcher(id).matches()) {
                    this.warn.accept(path + "." + rawId + ": неверный ключ цели, пропускаю");
                    continue;
                }
                final String base = path + "." + rawId;
                final Set<DamageCause> causes = this.causes(base + ".causes", EnumSet.noneOf(DamageCause.class));
                final EnumSet<EntityType> attackers = EnumSet.noneOf(EntityType.class);
                final EnumSet<AttackerGroup> groups = EnumSet.noneOf(AttackerGroup.class);
                if (this.config.contains(base + ".attackers") && !this.config.isList(base + ".attackers")) {
                    this.warn.accept(base + ".attackers: ожидается список, пропускаю");
                } else {
                    for (final String raw : this.config.getStringList(base + ".attackers")) {
                        final String value = raw.trim();
                        if (value.startsWith("#")) {
                            final AttackerGroup group = AttackerGroup.parse(value);
                            if (group == null) {
                                this.warn.accept(base + ".attackers: неизвестная группа «" + raw + "», пропускаю");
                            } else {
                                groups.add(group);
                            }
                            continue;
                        }
                        final EntityType type = entityType(value);
                        if (type == null) {
                            this.warn.accept(base + ".attackers: неизвестный тип сущности «" + raw + "», пропускаю");
                        } else {
                            attackers.add(type);
                        }
                    }
                }
                if (causes.isEmpty() && attackers.isEmpty() && groups.isEmpty()) {
                    this.warn.accept(base + ": у цели нет ни причин, ни атакующих — она никогда не сработает");
                }
                out.put(id, target(id, causes, attackers, groups));
            }
            return List.copyOf(out.values());
        }

        private static DamageCause cause(final String raw) {
            if (raw == null) {
                return null;
            }
            try {
                return DamageCause.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }

        private static EntityType entityType(final String raw) {
            String name = raw.trim().toUpperCase(Locale.ROOT);
            if (name.startsWith("MINECRAFT:")) {
                name = name.substring("MINECRAFT:".length());
            }
            try {
                final EntityType type = EntityType.valueOf(name);
                return type == EntityType.UNKNOWN ? null : type;
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }

        private static String fmt(final double v) {
            return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
        }
    }
}
