package core.amazake.systems.coreeffects.effect;

import java.util.List;
import java.util.Locale;

/** Четыре эффекта системы. {@link #id()} — ключ в config.yml, PDC, PlaceholderAPI и командах. */
public enum EffectType {
    BLEEDING("bleeding", "Кровотечение", List.of("кровотечение", "кровь", "bleed")),
    PANIC("panic", "Паника", List.of("паника", "страх", "fear")),
    WARMTH("warmth", "Тепло", List.of("тепло", "жар", "warm")),
    CURSE("curse", "Проклятье", List.of("проклятье", "проклятие", "curse"));

    private final String id;
    private final String title;
    private final List<String> aliases;

    EffectType(final String id, final String title, final List<String> aliases) {
        this.id = id;
        this.title = title;
        this.aliases = aliases;
    }

    public String id() {
        return this.id;
    }

    /** Название по умолчанию (в сообщениях берётся из config.yml). */
    public String title() {
        return this.title;
    }

    /** По id или русскому/английскому названию, без учёта регистра; null — не найдено. */
    public static EffectType parse(final String raw) {
        if (raw == null) {
            return null;
        }
        final String s = raw.trim().toLowerCase(Locale.ROOT);
        for (final EffectType t : values()) {
            if (t.id.equals(s) || t.aliases.contains(s)) {
                return t;
            }
        }
        return null;
    }
}
