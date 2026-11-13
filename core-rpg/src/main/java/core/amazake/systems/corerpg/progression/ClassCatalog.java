package core.amazake.systems.corerpg.progression;

import core.amazake.systems.corerpg.aspect.Aspect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** classes.yml: классы и профессии. Неизменяемый снимок, читается из любого потока. */
public record ClassCatalog(Map<String, RpgClass> classes, Map<String, RpgProfession> professions) {

    /** Пассивки, которые понимает бой. Другие ключи допустимы, но о них предупреждаем. */
    public static final Set<String> KNOWN_PASSIVES = Set.of("rage", "headshot", "backstab", "dual_wield");

    /** Базовый класс. */
    public record RpgClass(String key, String displayName, List<String> description, Material icon, String color,
                           String primaryStat, List<String> aliases, String hint, Material hintIcon,
                           List<Bonus> bonuses, Set<String> passives) {
        public RpgClass {
            description = List.copyOf(description);
            aliases = List.copyOf(aliases);
            bonuses = List.copyOf(bonuses);
            passives = Collections.unmodifiableSet(new LinkedHashSet<>(passives));
        }
    }

    /** Профессия: ветка класса {@code parent}. */
    public record RpgProfession(String key, String displayName, List<String> description, Material icon, String color,
                                String parent, List<String> aliases, List<Bonus> bonuses, List<Aspect> lockedAspects,
                                Set<String> passives) {
        public RpgProfession {
            description = List.copyOf(description);
            aliases = List.copyOf(aliases);
            bonuses = List.copyOf(bonuses);
            lockedAspects = List.copyOf(lockedAspects);
            passives = Collections.unmodifiableSet(new LinkedHashSet<>(passives));
        }
    }

    public static final ClassCatalog EMPTY = new ClassCatalog(Map.of(), Map.of());

    public ClassCatalog {
        classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        professions = Collections.unmodifiableMap(new LinkedHashMap<>(professions));
    }

    public RpgClass clazz(final String key) {
        return key == null ? null : this.classes.get(key);
    }

    public RpgProfession profession(final String key) {
        return key == null ? null : this.professions.get(key);
    }

    /** Профессии класса в порядке конфига. */
    public List<RpgProfession> professionsOf(final String classKey) {
        final List<RpgProfession> out = new ArrayList<>();
        for (final RpgProfession p : this.professions.values()) {
            if (p.parent().equals(classKey)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Класс по ключу, имени или псевдониму (регистр, «ё» и подчёркивания не важны). */
    public RpgClass findClass(final String input) {
        final String n = Names.normalize(input);
        if (n.isEmpty()) {
            return null;
        }
        for (final RpgClass c : this.classes.values()) {
            if (Names.normalize(c.key()).equals(n) || Names.normalize(Names.stripTags(c.displayName())).equals(n)
                || c.aliases().stream().anyMatch(a -> Names.normalize(a).equals(n))) {
                return c;
            }
        }
        return null;
    }

    /** Профессия по ключу, имени или псевдониму. */
    public RpgProfession findProfession(final String input) {
        final String n = Names.normalize(input);
        if (n.isEmpty()) {
            return null;
        }
        for (final RpgProfession p : this.professions.values()) {
            if (Names.normalize(p.key()).equals(n) || Names.normalize(Names.stripTags(p.displayName())).equals(n)
                || p.aliases().stream().anyMatch(a -> Names.normalize(a).equals(n))) {
                return p;
            }
        }
        return null;
    }

    /** Пассивки класса и профессии. */
    public Set<String> passives(final String classId, final String professionId) {
        final RpgClass c = this.clazz(classId);
        final RpgProfession p = this.profession(professionId);
        if (c == null && p == null) {
            return Set.of();
        }
        final Set<String> out = new LinkedHashSet<>();
        if (c != null) {
            out.addAll(c.passives());
        }
        if (p != null) {
            out.addAll(p.passives());
        }
        return Set.copyOf(out);
    }

    /** Бонусы класса и профессии подряд. */
    public List<Bonus> bonuses(final String classId, final String professionId) {
        final List<Bonus> out = new ArrayList<>();
        final RpgClass c = this.clazz(classId);
        if (c != null) {
            out.addAll(c.bonuses());
        }
        final RpgProfession p = this.profession(professionId);
        if (p != null) {
            out.addAll(p.bonuses());
        }
        return out;
    }

    // --- разбор ---

    public static ClassCatalog from(final ConfigurationSection config, final Consumer<String> warn) {
        return from(config, warn, ClassCatalog::itemMaterial);
    }

    /** Предмет по имени, только настоящие предметы (не блоки-без-предмета и не воздух); null — нет такого. */
    static Material itemMaterial(final String name) {
        final Material m = Material.matchMaterial(name);
        return m == null || m.isAir() || !m.isItem() ? null : m;
    }

    /** То же с заданным поиском предметов (тесты без сервера не могут спрашивать реестры). */
    static ClassCatalog from(final ConfigurationSection config, final Consumer<String> warn,
                             final java.util.function.Function<String, Material> materials) {
        final Map<String, RpgClass> classes = new LinkedHashMap<>();
        final ConfigurationSection cs = config.getConfigurationSection("classes");
        if (cs == null) {
            warn.accept("classes: раздел не найден — классов нет");
        } else {
            for (final String rawKey : cs.getKeys(false)) {
                final String key = rawKey.toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_]{1,32}")) {
                    warn.accept("classes." + rawKey + ": ключ должен быть a-z, 0-9, _ (до 32), пропускаю");
                    continue;
                }
                final ConfigurationSection s = cs.getConfigurationSection(rawKey);
                if (s == null) {
                    warn.accept("classes." + rawKey + ": нужен раздел, пропускаю");
                    continue;
                }
                final String where = "classes." + rawKey;
                classes.put(key, new RpgClass(key,
                    s.getString("display-name", rawKey),
                    lines(s, "description"),
                    material(s.getString("icon"), Material.IRON_SWORD, where + ".icon", warn, materials),
                    s.getString("color", "<gold>"),
                    s.getString("primary-stat", "Сила"),
                    s.getStringList("aliases"),
                    s.getString("menu-hint", ""),
                    material(s.getString("menu-hint-icon"), Material.PAPER, where + ".menu-hint-icon", warn, materials),
                    bonuses(s, where, warn),
                    passives(s, where, warn)));
            }
        }
        final Map<String, RpgProfession> professions = new LinkedHashMap<>();
        final ConfigurationSection ps = config.getConfigurationSection("professions");
        if (ps != null) {
            for (final String rawKey : ps.getKeys(false)) {
                final String key = rawKey.toLowerCase(Locale.ROOT);
                final String where = "professions." + rawKey;
                if (!key.matches("[a-z0-9_]{1,32}")) {
                    warn.accept(where + ": ключ должен быть a-z, 0-9, _ (до 32), пропускаю");
                    continue;
                }
                if (classes.containsKey(key)) {
                    warn.accept(where + ": ключ совпадает с ключом класса, пропускаю");
                    continue;
                }
                final ConfigurationSection s = ps.getConfigurationSection(rawKey);
                if (s == null) {
                    warn.accept(where + ": нужен раздел, пропускаю");
                    continue;
                }
                final String parent = s.getString("parent", "").toLowerCase(Locale.ROOT);
                if (!classes.containsKey(parent)) {
                    warn.accept(where + ".parent: нет класса «" + parent + "», пропускаю профессию");
                    continue;
                }
                final List<Aspect> locked = new ArrayList<>();
                for (final String a : s.getStringList("locked-aspects")) {
                    final Aspect aspect = Aspect.byKey(a);
                    if (aspect == null) {
                        warn.accept(where + ".locked-aspects: неизвестный аспект «" + a + "», пропускаю");
                    } else {
                        locked.add(aspect);
                    }
                }
                professions.put(key, new RpgProfession(key,
                    s.getString("display-name", rawKey),
                    lines(s, "description"),
                    material(s.getString("icon"), classes.get(parent).icon(), where + ".icon", warn, materials),
                    s.getString("color", "<light_purple>"),
                    parent,
                    s.getStringList("aliases"),
                    bonuses(s, where, warn),
                    locked,
                    passives(s, where, warn)));
            }
        }
        return new ClassCatalog(classes, professions);
    }

    static List<String> lines(final ConfigurationSection s, final String path) {
        if (s.isList(path)) {
            return s.getStringList(path);
        }
        final String one = s.getString(path);
        return one == null || one.isEmpty() ? List.of() : List.of(one.split("\n"));
    }

    static Material material(final String name, final Material def, final String where, final Consumer<String> warn,
                             final java.util.function.Function<String, Material> materials) {
        if (name == null || name.isBlank()) {
            return def;
        }
        final Material m = materials.apply(name.trim());
        if (m == null) {
            warn.accept(where + ": «" + name + "» — нет такого предмета, беру " + def.name());
            return def;
        }
        return m;
    }

    private static List<Bonus> bonuses(final ConfigurationSection s, final String where, final Consumer<String> warn) {
        final List<Bonus> out = new ArrayList<>();
        for (final String raw : s.getStringList("bonuses")) {
            final Bonus b = Bonus.parse(raw, where + ".bonuses", warn);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    private static Set<String> passives(final ConfigurationSection s, final String where, final Consumer<String> warn) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String raw : s.getStringList("passives")) {
            final String p = raw.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            if (!KNOWN_PASSIVES.contains(p)) {
                warn.accept(where + ".passives: «" + p + "» бой не знает (известны " + KNOWN_PASSIVES + "), оставляю как есть");
            }
            out.add(p);
        }
        return out;
    }
}
