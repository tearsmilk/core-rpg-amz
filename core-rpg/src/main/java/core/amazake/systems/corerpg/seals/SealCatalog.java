package core.amazake.systems.corerpg.seals;

import core.amazake.systems.corerpg.aspect.Aspect;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** Каталог печатей из раздела {@code seals:} файла seals.yml. Неизменяемый снимок; подменяется целиком в reload. */
public final class SealCatalog {

    /** Типы предметов, на которые печать может лечь в Minecraft. */
    public static final Set<String> TYPES = Set.of("WEAPON", "ARMOR", "SHIELD");
    /** Тип из Royal Quest, слота для него нет: молча отбрасывается. */
    public static final String ACCESSORY = "ACCESSORY";

    public static final SealCatalog EMPTY = new SealCatalog(Map.of());

    private final Map<String, SealDefinition> seals;
    private final Set<String> classKeys;

    private SealCatalog(final Map<String, SealDefinition> seals) {
        this.seals = Collections.unmodifiableMap(new LinkedHashMap<>(seals));
        final Set<String> classes = new TreeSet<>();
        for (final SealDefinition seal : this.seals.values()) {
            classes.addAll(seal.classes());
        }
        classes.remove(SealDefinition.ANY);
        this.classKeys = Collections.unmodifiableSet(classes);
    }

    public static SealCatalog of(final Collection<SealDefinition> seals) {
        final Map<String, SealDefinition> map = new LinkedHashMap<>();
        for (final SealDefinition seal : seals) {
            map.put(seal.id(), seal);
        }
        return new SealCatalog(map);
    }

    /** Разбор файла: берёт раздел {@code seals}. Ошибки отдельной печати — предупреждение, печать пропускается. */
    public static SealCatalog from(final ConfigurationSection root, final Consumer<String> warn) {
        final ConfigurationSection section = root.getConfigurationSection("seals");
        if (section == null) {
            warn.accept("seals.yml: нет раздела seals — каталог печатей пуст");
            return EMPTY;
        }
        final Map<String, SealDefinition> map = new LinkedHashMap<>();
        for (final String rawId : section.getKeys(false)) {
            final String id = rawId.toLowerCase(Locale.ROOT).trim();
            final ConfigurationSection s = section.getConfigurationSection(rawId);
            if (s == null) {
                warn.accept("seals." + rawId + ": ожидался раздел печати, пропускаю");
                continue;
            }
            if (map.containsKey(id)) {
                warn.accept("seals." + rawId + ": повтор id «" + id + "», беру последний");
            }
            map.put(id, parseSeal(id, rawId, s, warn));
        }
        return new SealCatalog(map);
    }

    private static SealDefinition parseSeal(final String id, final String rawId, final ConfigurationSection s,
                                            final Consumer<String> warn) {
        final String path = "seals." + rawId;
        String name = s.getString("name");
        if (name == null || name.isBlank()) {
            warn.accept(path + ".name: не задано, беру id");
            name = id;
        }
        final Set<String> types = new LinkedHashSet<>();
        final List<String> rawTypes = stringList(s, "types", path, warn);
        for (final String type : rawTypes) {
            final String t = type.toUpperCase(Locale.ROOT).trim();
            if (TYPES.contains(t)) {
                types.add(t);
            } else if (!ACCESSORY.equals(t)) {
                warn.accept(path + ".types: неизвестный тип «" + type + "» (WEAPON, ARMOR, SHIELD, ACCESSORY), пропускаю");
            }
        }
        if (types.isEmpty() && (rawTypes.isEmpty()
            || !rawTypes.stream().allMatch(t -> ACCESSORY.equals(t.toUpperCase(Locale.ROOT).trim())))) {
            warn.accept(path + ".types: нет ни одного известного типа (WEAPON, ARMOR, SHIELD) — печать не выпадет");
        }
        final Set<String> classes = new LinkedHashSet<>();
        for (final String c : stringList(s, "classes", path, warn)) {
            final String k = c.toLowerCase(Locale.ROOT).trim();
            if (!k.isEmpty()) {
                classes.add(k);
            }
        }
        final Map<String, SealDefinition.Values> bonuses = new LinkedHashMap<>();
        final ConfigurationSection b = s.getConfigurationSection("bonuses");
        if (b != null) {
            for (final String key : b.getKeys(false)) {
                final String k = key.toLowerCase(Locale.ROOT).trim();
                if (!isKnownBonus(k)) {
                    warn.accept(path + ".bonuses." + key + ": неизвестный бонус, пропускаю");
                    continue;
                }
                final SealDefinition.Values values = parseValues(b.getList(key), path + ".bonuses." + key, warn);
                if (values != null) {
                    bonuses.put(k, values);
                }
            }
        }
        if (bonuses.isEmpty()) {
            warn.accept(path + ": у печати нет бонусов");
        }
        final String origin = s.getString("origin", "plugin");
        if (s.contains("enabled") && !s.isBoolean("enabled")) {
            warn.accept(path + ".enabled: ожидалось true или false, а не «" + s.get("enabled") + "», считаю true");
        }
        final boolean enabled = s.getBoolean("enabled", true);
        return new SealDefinition(id, name, origin, types, classes, enabled, bonuses);
    }

    /**
     * Список строк по ключу. Нет ключа — пустой. Одиночное значение вместо списка ({@code classes: swordsman}) —
     * предупреждение и список из одного элемента; раздел — предупреждение и пустой список.
     */
    static List<String> stringList(final ConfigurationSection s, final String key, final String path,
                                   final Consumer<String> warn) {
        if (!s.contains(key)) {
            return List.of();
        }
        if (s.isList(key)) {
            return s.getStringList(key);
        }
        final Object value = s.get(key);
        if (value == null || value instanceof ConfigurationSection) {
            warn.accept(path + "." + key + ": ожидался список, пропускаю");
            return List.of();
        }
        warn.accept(path + "." + key + ": ожидался список, а задано одно значение «" + value + "» — считаю списком из него");
        return List.of(String.valueOf(value));
    }

    /** Ключ бонуса: аспект или целевой «resistance:&lt;цель&gt;», «damage_vs:&lt;цель&gt;». */
    static boolean isKnownBonus(final String key) {
        if (Aspect.isTargetedKey(key)) {
            return key.indexOf(':') < key.length() - 1;
        }
        return Aspect.byKey(key) != null;
    }

    /** Список из 4 чисел; {@code ~} (null) — у цвета бонуса нет. null — ошибка (уже предупреждено). */
    static SealDefinition.Values parseValues(final List<?> raw, final String path, final Consumer<String> warn) {
        if (raw == null || raw.size() != 4) {
            warn.accept(path + ": нужно ровно 4 значения [зелёная, синяя, фиолетовая, оранжевая], пропускаю");
            return null;
        }
        final double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            final Object v = raw.get(i);
            if (v == null) {
                out[i] = Double.NaN;
            } else if (v instanceof final Number n) {
                out[i] = n.doubleValue();
            } else {
                try {
                    final String str = v.toString().trim();
                    out[i] = str.equals("~") || str.isEmpty() ? Double.NaN : Double.parseDouble(str.replace(',', '.'));
                } catch (final NumberFormatException e) {
                    warn.accept(path + ": «" + v + "» не число, пропускаю бонус");
                    return null;
                }
            }
            if (Double.isInfinite(out[i])) {
                warn.accept(path + ": бесконечное значение, пропускаю бонус");
                return null;
            }
        }
        return new SealDefinition.Values(out[0], out[1], out[2], out[3]);
    }

    public SealDefinition get(final String id) {
        return id == null ? null : this.seals.get(id.toLowerCase(Locale.ROOT).trim());
    }

    public Collection<SealDefinition> all() {
        return this.seals.values();
    }

    public int size() {
        return this.seals.size();
    }

    /** Ключи классов и профессий, встречающиеся в каталоге (без any), по алфавиту. */
    public Set<String> classKeys() {
        return this.classKeys;
    }

    /**
     * Пул для случайного выбора: включённые печати, подходящие по типу, и при classFilter — по классу или профессии
     * игрока. Если по классу ничего нет — все подходящие по типу.
     */
    public List<SealDefinition> pool(final String sealType, final String classId, final String professionId,
                                     final boolean classFilter) {
        final List<SealDefinition> byType = new ArrayList<>();
        for (final SealDefinition seal : this.seals.values()) {
            if (seal.enabled() && seal.accepts(sealType)) {
                byType.add(seal);
            }
        }
        if (!classFilter) {
            return List.copyOf(byType);
        }
        final List<SealDefinition> byClass = new ArrayList<>();
        for (final SealDefinition seal : byType) {
            if (seal.fitsClass(classId, professionId)) {
                byClass.add(seal);
            }
        }
        return List.copyOf(byClass.isEmpty() ? byType : byClass);
    }
}
