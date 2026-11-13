package core.amazake.systems.corerpg.progression;

import core.amazake.systems.corerpg.aspect.Aspect;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Бонус класса или профессии из classes.yml. Форматы:
 * <ul>
 *   <li>{@code aspect:<ключ>:<значение>} — аспект, в том числе целевой ({@code aspect:resistance:chaos:0.05});</li>
 *   <li>{@code attribute:<ИМЯ>:<значение>} — совместимость с плагином, переводится в аспект;</li>
 *   <li>{@code effect:<ЭФФЕКТ>:<уровень>[:<секунды>]} — эффект зелья; уровень 0 = I; 0 секунд = постоянно;</li>
 *   <li>{@code permission:<узел>} — право, пока у игрока этот класс или профессия.</li>
 * </ul>
 */
public sealed interface Bonus permits Bonus.AspectBonus, Bonus.EffectBonus, Bonus.PermissionBonus {

    /** Аспект: обычный ключ Aspect или целевой «resistance:fire». */
    record AspectBonus(String key, double value) implements Bonus {
    }

    /**
     * Эффект зелья.
     *
     * @param effectKey       ключ эффекта Minecraft без пространства имён (night_vision)
     * @param amplifier       0 = уровень I
     * @param durationSeconds 0 = постоянно
     */
    record EffectBonus(String effectKey, int amplifier, int durationSeconds) implements Bonus {
        public boolean permanent() {
            return this.durationSeconds <= 0;
        }
    }

    /** Право (PermissionAttachment). */
    record PermissionBonus(String node) implements Bonus {
    }

    /** Атрибуты плагина → аспекты. */
    Map<String, String> ATTRIBUTE_ASPECTS = Map.of(
        "attack_damage", "attack_damage",
        "armor", "armor",
        "max_health", "max_health",
        "movement_speed", "movement_speed",
        "attack_speed", "attack_speed",
        "attack_knockback", "knockback",
        "knockback_resistance", "knockback_resistance",
        "entity_interaction_range", "range");

    /** Старые имена эффектов Bukkit → ключи Minecraft. */
    Map<String, String> LEGACY_EFFECTS = Map.ofEntries(
        Map.entry("increase_damage", "strength"),
        Map.entry("damage_resistance", "resistance"),
        Map.entry("slow", "slowness"),
        Map.entry("fast_digging", "haste"),
        Map.entry("slow_digging", "mining_fatigue"),
        Map.entry("jump", "jump_boost"),
        Map.entry("heal", "instant_health"),
        Map.entry("harm", "instant_damage"),
        Map.entry("confusion", "nausea"),
        Map.entry("unluck", "bad_luck"));

    /** Разбор строки бонуса; ошибка — предупреждение и null. */
    static Bonus parse(final String raw, final String where, final Consumer<String> warn) {
        if (raw == null || raw.isBlank()) {
            warn.accept(where + ": пустой бонус, пропускаю");
            return null;
        }
        final String[] parts = raw.trim().split(":");
        final String type = parts[0].trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "aspect" -> {
                if (parts.length < 3) {
                    warn.accept(where + ": «" + raw + "» — нужно aspect:<ключ>:<значение>, пропускаю");
                    return null;
                }
                final String key = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1))
                    .trim().toLowerCase(Locale.ROOT);
                if (!Aspect.isTargetedKey(key) && Aspect.byKey(key) == null) {
                    warn.accept(where + ": «" + raw + "» — неизвестный аспект «" + key + "», пропускаю");
                    return null;
                }
                if (Aspect.isTargetedKey(key) && key.substring(key.indexOf(':') + 1).isBlank()) {
                    warn.accept(where + ": «" + raw + "» — у целевого аспекта нет цели, пропускаю");
                    return null;
                }
                final Double value = number(parts[parts.length - 1]);
                if (value == null) {
                    warn.accept(where + ": «" + raw + "» — значение не число, пропускаю");
                    return null;
                }
                return new AspectBonus(key, value);
            }
            case "attribute" -> {
                if (parts.length != 3) {
                    warn.accept(where + ": «" + raw + "» — нужно attribute:<ИМЯ>:<значение>, пропускаю");
                    return null;
                }
                String name = parts[1].trim().toLowerCase(Locale.ROOT);
                if (name.startsWith("minecraft.") || name.startsWith("minecraft_")) {
                    name = name.substring("minecraft.".length());
                }
                for (final String prefix : new String[] {"generic_", "generic.", "player_", "player."}) {
                    if (name.startsWith(prefix)) {
                        name = name.substring(prefix.length());
                    }
                }
                final String aspect = ATTRIBUTE_ASPECTS.get(name.replace('.', '_'));
                if (aspect == null) {
                    warn.accept(where + ": «" + raw + "» — атрибут " + parts[1] + " не поддерживается (можно: "
                        + String.join(", ", new java.util.TreeSet<>(ATTRIBUTE_ASPECTS.keySet())) + "), пропускаю");
                    return null;
                }
                final Double value = number(parts[2]);
                if (value == null) {
                    warn.accept(where + ": «" + raw + "» — значение не число, пропускаю");
                    return null;
                }
                return new AspectBonus(aspect, value);
            }
            case "effect" -> {
                if (parts.length < 2 || parts.length > 4) {
                    warn.accept(where + ": «" + raw + "» — нужно effect:<ЭФФЕКТ>:<уровень>[:<секунды>], пропускаю");
                    return null;
                }
                String key = parts[1].trim().toLowerCase(Locale.ROOT);
                if (key.startsWith("minecraft.")) {
                    key = key.substring("minecraft.".length());
                }
                key = LEGACY_EFFECTS.getOrDefault(key, key);
                if (!key.matches("[a-z0-9_./-]+")) {
                    warn.accept(where + ": «" + raw + "» — неверное имя эффекта, пропускаю");
                    return null;
                }
                final Integer amplifier = parts.length >= 3 ? integer(parts[2]) : Integer.valueOf(0);
                if (amplifier == null || amplifier < 0 || amplifier > 255) {
                    warn.accept(where + ": «" + raw + "» — уровень эффекта должен быть 0..255, пропускаю");
                    return null;
                }
                final Integer seconds = parts.length == 4 ? integer(parts[3]) : Integer.valueOf(0);
                if (seconds == null || seconds < 0 || seconds > 1_000_000) {
                    warn.accept(where + ": «" + raw + "» — длительность должна быть 0..1000000 секунд, пропускаю");
                    return null;
                }
                return new EffectBonus(key, amplifier, seconds);
            }
            case "permission" -> {
                final String node = raw.trim().substring(raw.trim().indexOf(':') + 1).trim();
                if (node.isEmpty() || node.contains(" ")) {
                    warn.accept(where + ": «" + raw + "» — нужно permission:<узел>, пропускаю");
                    return null;
                }
                return new PermissionBonus(node);
            }
            default -> {
                warn.accept(where + ": «" + raw + "» — неизвестный тип «" + type
                    + "» (aspect, attribute, effect, permission), пропускаю");
                return null;
            }
        }
    }

    private static Double number(final String s) {
        try {
            final double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? v : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(final String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
