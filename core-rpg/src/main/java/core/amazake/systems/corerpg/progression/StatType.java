package core.amazake.systems.corerpg.progression;

import core.amazake.systems.corerpg.aspect.Aspect;
import java.util.List;

/** Четыре параметра персонажа. Главный называется по классу: Сила, Точность или Интеллект. */
public enum StatType {
    PRIMARY("primary", Aspect.STAT_PRIMARY, "Главный параметр",
        List.of("primary", "главный", "сила", "точность", "интеллект", "strength", "accuracy", "intellect", "str", "int")),
    AGILITY("agility", Aspect.STAT_AGILITY, "Ловкость", List.of("agility", "ловкость", "agi", "dex")),
    STAMINA("stamina", Aspect.STAT_STAMINA, "Выносливость", List.of("stamina", "выносливость", "vit", "sta")),
    LUCK("luck", Aspect.STAT_LUCK, "Удача", List.of("luck", "удача", "luk"));

    private final String key;
    private final Aspect aspect;
    private final String label;
    private final List<String> names;

    StatType(final String key, final Aspect aspect, final String label, final List<String> names) {
        this.key = key;
        this.aspect = aspect;
        this.label = label;
        this.names = names;
    }

    public String key() {
        return this.key;
    }

    /** Аспект бонусных очков этого параметра (печати, заточка +11). */
    public Aspect aspect() {
        return this.aspect;
    }

    /** Название; для главного — общее, конкретное даёт класс. */
    public String label() {
        return this.label;
    }

    /** Имена для команды: {@code /stats add <имя>}, по-русски и по-английски. */
    public List<String> names() {
        return this.names;
    }

    /** По имени из команды или null. */
    public static StatType parse(final String input) {
        final String n = Names.normalize(input);
        for (final StatType type : values()) {
            if (type.names.contains(n)) {
                return type;
            }
        }
        return null;
    }
}
