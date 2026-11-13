package core.amazake.systems.corerpg.seals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Расходники печатей. id совпадают с плагином MagicSeals (старые предметы распознаются по ним). */
public enum Consumable {
    STONE_C("stone_c", "изумруд", "Изумрудный камень (C)", "Первое наложение печати на предметы грейда C"),
    STONE_B("stone_b", "сапфир", "Сапфировый камень (B)", "Первое наложение печати на предметы грейда B"),
    STONE_A("stone_a", "морион", "Морионовый камень (A)", "Первое наложение печати на предметы грейда A"),
    BLOOD_CRYSTAL("blood_crystal", "кристалл", "Кристалл крови", "Смена печати: новая печать и новый цвет"),
    WAX("wax", "сургуч", "Сургуч Черепа Ахи", "Делает печать оранжевой"),
    ACCOUNT_WAX("account_wax", "особый", "Особый сургуч", "Оранжевая печать, предмет становится персональным"),
    ARKONITE_WAX("arkonite_wax", "арконит", "Арконитовый сургуч", "С кристаллом крови: меняет только цвет"),
    HEART("heart", "сердце", "Сердце Аркона", "С камнем или кристаллом: цвет не ниже синего");

    /** Группы команды give. */
    public static final Map<String, List<Consumable>> GROUPS;

    static {
        final Map<String, List<Consumable>> groups = new LinkedHashMap<>();
        groups.put("all", List.of(values()));
        groups.put("stones", List.of(STONE_C, STONE_B, STONE_A));
        groups.put("wax", List.of(WAX, ACCOUNT_WAX, ARKONITE_WAX));
        GROUPS = Collections.unmodifiableMap(groups);
    }

    private final String id;
    private final String russianAlias;
    private final String defaultName;
    private final String hint;

    Consumable(final String id, final String russianAlias, final String defaultName, final String hint) {
        this.id = id;
        this.russianAlias = russianAlias;
        this.defaultName = defaultName;
        this.hint = hint;
    }

    public String id() {
        return this.id;
    }

    /** Русское имя для команды give: изумруд, сапфир, морион, кристалл, сургуч, особый, арконит, сердце. */
    public String russianAlias() {
        return this.russianAlias;
    }

    /** Название по умолчанию (как в MagicSeals). */
    public String defaultName() {
        return this.defaultName;
    }

    /** Строка лора: для чего расходник. */
    public String hint() {
        return this.hint;
    }

    public boolean isStone() {
        return this == STONE_C || this == STONE_B || this == STONE_A;
    }

    public boolean isWax() {
        return this == WAX || this == ACCOUNT_WAX || this == ARKONITE_WAX;
    }

    /** По id из PDC (stone_c…) или null. */
    public static Consumable byId(final String id) {
        if (id == null) {
            return null;
        }
        final String k = id.toLowerCase(Locale.ROOT).trim();
        for (final Consumable c : values()) {
            if (c.id.equals(k)) {
                return c;
            }
        }
        return null;
    }

    /** Ввод команды: id или русское имя (ё = е). Группы сюда не входят — см. {@link #resolve}. */
    public static Consumable parse(final String input) {
        final Consumable byId = byId(input);
        if (byId != null || input == null) {
            return byId;
        }
        final String k = input.toLowerCase(Locale.ROOT).trim().replace('ё', 'е');
        for (final Consumable c : values()) {
            if (c.russianAlias.equals(k)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Аргумент give: группа (all, stones, wax; по-русски «все», «камни», «сургучи») или один расходник. Пустой
     * список — не распознано. Обратите внимание: {@code wax} — группа из трёх сургучей; один Сургуч Черепа Ахи —
     * «сургуч».
     */
    public static List<Consumable> resolve(final String input) {
        if (input == null) {
            return List.of();
        }
        final String k = input.toLowerCase(Locale.ROOT).trim().replace('ё', 'е');
        final String group = switch (k) {
            case "все" -> "all";
            case "камни" -> "stones";
            case "сургучи" -> "wax";
            default -> k;
        };
        final List<Consumable> list = GROUPS.get(group);
        if (list != null) {
            return list;
        }
        final Consumable single = parse(k);
        return single == null ? List.of() : List.of(single);
    }

    /** Все варианты для автодополнения give: группы, id и русские имена. */
    public static List<String> completions() {
        final List<String> out = new ArrayList<>(GROUPS.keySet());
        out.add("все");
        out.add("камни");
        out.add("сургучи");
        for (final Consumable c : values()) {
            if (!GROUPS.containsKey(c.id)) {
                out.add(c.id);
            }
            out.add(c.russianAlias);
        }
        return List.copyOf(out);
    }
}
