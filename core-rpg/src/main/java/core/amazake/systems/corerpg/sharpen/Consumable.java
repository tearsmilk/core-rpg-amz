package core.amazake.systems.corerpg.sharpen;

import java.util.List;
import java.util.Locale;

/**
 * Расходники заточки (как в плагине Zatochka). Набор фиксирован: механика привязана к id. Название, материал, лор и
 * русские имена для команды {@code /sharpen give} настраиваются в sharpen.yml (раздел {@code consumables}).
 */
public enum Consumable {
    SHARD("shard", 0, false, "Осколок философского камня", "AMETHYST_SHARD",
        "<light_purple>Осколок философского камня</light_purple>",
        List.of("<gray>Базовый камень заточки.", "<gray>Может уничтожить предмет."),
        List.of("осколок", "осколок_философского_камня")),
    CRUMB("crumb", 0, false, "Крошка философского камня", "AMETHYST_CLUSTER",
        "<white>Крошка философского камня</white>",
        List.of("<gray>Только для грейда C.", "<gray>Может уничтожить предмет."),
        List.of("крошка", "крошка_философского_камня")),
    CUBE("cube", 0, false, "Ашкалотский кубик", "LODESTONE",
        "<aqua>Ашкалотский кубик</aqua>",
        List.of("<gray>Не уничтожает предмет.", "<gray>Уровень может понизиться."),
        List.of("кубик", "ашкалотский_кубик")),
    BLACK("black", 1, false, "Чёрный философский камень", "BLACK_DYE",
        "<dark_gray>Чёрный философский камень</dark_gray>",
        List.of("<gray>Повышенный шанс успеха."),
        List.of("чёрный", "черный", "чёрный_камень", "черный_камень")),
    WHITE("white", 2, false, "Белый философский камень", "WHITE_DYE",
        "<white>Белый философский камень</white>",
        List.of("<gray>Высокий шанс успеха."),
        List.of("белый", "белый_камень")),
    RED("red", 3, false, "Красный философский камень", "RED_DYE",
        "<red>Красный философский камень</red>",
        List.of("<gray>Очень высокий шанс успеха."),
        List.of("красный", "красный_камень")),
    PURPLE("purple", 4, false, "Пурпурный философский камень", "PURPLE_DYE",
        "<dark_purple>Пурпурный философский камень</dark_purple>",
        List.of("<gray>Наивысший шанс успеха."),
        List.of("пурпурный", "пурпурный_камень", "фиолетовый")),
    RUNE_SAVE("rune_save", -1, true, "Руна Сохранения", "PAPER",
        "<green>Руна Сохранения</green>",
        List.of("<gray>При неудаче уровень не меняется", "<gray>и предмет не уничтожается."),
        List.of("руна_сохранения", "сохранение")),
    RUNE_COLD("rune_cold", -1, true, "Руна Холода", "BLUE_ICE",
        "<aqua>Руна Холода</aqua>",
        List.of("<gray>При неудаче уровень не меняется", "<gray>и предмет не уничтожается."),
        List.of("руна_холода", "холод"));

    private final String id;
    private final int column;
    private final boolean rune;
    private final String defaultName;
    private final String defaultMaterial;
    private final String defaultDisplay;
    private final List<String> defaultLore;
    private final List<String> defaultAliases;

    Consumable(final String id, final int column, final boolean rune, final String defaultName, final String defaultMaterial,
               final String defaultDisplay, final List<String> defaultLore, final List<String> defaultAliases) {
        this.id = id;
        this.column = column;
        this.rune = rune;
        this.defaultName = defaultName;
        this.defaultMaterial = defaultMaterial;
        this.defaultDisplay = defaultDisplay;
        this.defaultLore = defaultLore;
        this.defaultAliases = defaultAliases;
    }

    /** Ключ: shard, crumb, cube, black, white, red, purple, rune_save, rune_cold. */
    public String id() {
        return this.id;
    }

    /** Колонка таблиц RQ: 0 — осколок (и крошка, и кубик), 1 — чёрный … 4 — пурпурный; −1 для рун. */
    public int column() {
        return this.column;
    }

    public boolean isRune() {
        return this.rune;
    }

    public boolean isStone() {
        return !this.rune;
    }

    String defaultName() {
        return this.defaultName;
    }

    String defaultMaterial() {
        return this.defaultMaterial;
    }

    String defaultDisplay() {
        return this.defaultDisplay;
    }

    List<String> defaultLore() {
        return this.defaultLore;
    }

    List<String> defaultAliases() {
        return this.defaultAliases;
    }

    /** По id (регистр не важен) или null. */
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

    /** Нормализует имя для поиска: нижний регистр, ё → е, пробелы и дефисы → «_». */
    public static String normalize(final String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).trim().replace('ё', 'е').replaceAll("[\\s\\-]+", "_");
    }
}
