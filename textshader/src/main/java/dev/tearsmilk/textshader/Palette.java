package dev.tearsmilk.textshader;

import java.util.Locale;

public enum Palette {
    WHITE("white", "Белый", 0xFFFFFF, "WHITE_DYE"),
    RED("red", "Красный", 0xFF4646, "RED_DYE"),
    ORANGE("orange", "Оранжевый", 0xFF9628, "ORANGE_DYE"),
    YELLOW("yellow", "Жёлтый", 0xFFEB3C, "YELLOW_DYE"),
    LIME("lime", "Лаймовый", 0x8CFF46, "LIME_DYE"),
    GREEN("green", "Зелёный", 0x3CC85A, "GREEN_DYE"),
    AQUA("aqua", "Бирюзовый", 0x46E6D2, "CYAN_DYE"),
    CYAN("cyan", "Голубой", 0x6ED2FF, "LIGHT_BLUE_DYE"),
    BLUE("blue", "Синий", 0x506EFF, "BLUE_DYE"),
    PURPLE("purple", "Фиолетовый", 0xA05AFF, "PURPLE_DYE"),
    MAGENTA("magenta", "Пурпурный", 0xE650E6, "MAGENTA_DYE"),
    PINK("pink", "Розовый", 0xFF8CC8, "PINK_DYE"),
    GOLD("gold", "Золотой", 0xFFC83C, "GLOWSTONE_DUST"),
    GRAY("gray", "Серый", 0xAAAAB4, "GRAY_DYE"),
    BLACK("black", "Чёрный", 0x282830, "BLACK_DYE"),
    BROWN("brown", "Коричневый", 0xA0643C, "BROWN_DYE");

    private final String key;
    private final String title;
    private final int rgb;
    private final String icon;

    Palette(String key, String title, int rgb, String icon) {
        this.key = key;
        this.title = title;
        this.rgb = rgb;
        this.icon = icon;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public int rgb() {
        return rgb;
    }

    public String icon() {
        return icon;
    }

    public static Palette byId(int id) {
        Palette[] all = values();
        return id >= 0 && id < all.length ? all[id] : null;
    }

    public static Palette byKey(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.toLowerCase(Locale.ROOT);
        for (Palette p : values()) {
            if (p.key.equals(k) || p.name().toLowerCase(Locale.ROOT).equals(k)) {
                return p;
            }
        }
        return null;
    }
}
