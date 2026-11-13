package core.amazake.systems.corerpg.seals;

import java.util.Locale;

/** Цвет печати. Порядок значений бонусов в seals.yml: зелёная, синяя, фиолетовая, оранжевая. */
public enum SealColor {
    GREEN("green", "Зелёная", "green"),
    BLUE("blue", "Синяя", "aqua"),
    PURPLE("purple", "Фиолетовая", "light_purple"),
    ORANGE("orange", "Оранжевая", "gold");

    private final String id;
    private final String russian;
    private final String tag;

    SealColor(final String id, final String russian, final String tag) {
        this.id = id;
        this.russian = russian;
        this.tag = tag;
    }

    /** Идентификатор в PDC предмета и в командах: green, blue, purple, orange. */
    public String id() {
        return this.id;
    }

    /** «Зелёная», «Синяя»… */
    public String russian() {
        return this.russian;
    }

    /** Тег цвета MiniMessage. */
    public String tag() {
        return this.tag;
    }

    /** Индекс в списке значений бонуса (0..3). */
    public int index() {
        return this.ordinal();
    }

    /** По id из PDC (регистр не важен) или null. */
    public static SealColor byId(final String id) {
        if (id == null) {
            return null;
        }
        for (final SealColor color : values()) {
            if (color.id.equalsIgnoreCase(id.trim())) {
                return color;
            }
        }
        return null;
    }

    /** Ввод команды: green / зелёная / зеленая / зелёный… Возвращает null, если не распознан. */
    public static SealColor parse(final String input) {
        final SealColor byId = byId(input);
        if (byId != null || input == null) {
            return byId;
        }
        final String s = input.toLowerCase(Locale.ROOT).trim().replace('ё', 'е');
        if (s.startsWith("зелен")) {
            return GREEN;
        }
        if (s.startsWith("син")) {
            return BLUE;
        }
        if (s.startsWith("фиолет")) {
            return PURPLE;
        }
        if (s.startsWith("оранж")) {
            return ORANGE;
        }
        return null;
    }
}
