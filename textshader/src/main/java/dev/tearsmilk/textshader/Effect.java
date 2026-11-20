package dev.tearsmilk.textshader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public enum Effect {
    SHAKE("shake", "Тряска", "TNT", false, false),
    WAVE("wave", "Волна", "WATER_BUCKET", false, false),
    JUMP("jump", "Прыжки", "SLIME_BALL", false, false),
    FLIP("flip", "Переворот", "PAPER", false, false),
    SKEW("skew", "Наклон", "FEATHER", false, false),
    PULSE("pulse", "Пульс", "REDSTONE", false, false),
    RAINBOW("rainbow", "Радуга", "PRISMARINE_CRYSTALS", true, false),
    GRADIENT("gradient", "Градиент", "GLASS_PANE", false, false),
    SHIMMER("shimmer", "Блик", "GLOWSTONE_DUST", false, false),
    CHROMA("chroma", "Хроматика", "SPYGLASS", false, false),
    METAL("metal", "Металл", "IRON_INGOT", false, false),
    FIRE("fire", "Огонь", "BLAZE_POWDER", false, false),
    FADE("fade", "Переход", "GLASS_BOTTLE", false, false),
    BLINK("blink", "Мигание", "REDSTONE_TORCH", false, false),
    GLOW("glow", "Свечение", "GLOW_INK_SAC", false, false),
    OUTLINE("outline", "Обводка", "INK_SAC", false, false),
    THIN("thin", "Тонкая обводка", "STRING", false, false),
    PRIDE("pride", "Прайд", "WHITE_BANNER", true, false),
    LESBIAN("lesbian", "Флаг: лесби", "ORANGE_BANNER", true, false),
    MLM("mlm", "Флаг: млм", "CYAN_BANNER", true, false),
    BI("bi", "Флаг: би", "PURPLE_BANNER", true, false),
    TRANS("trans", "Флаг: транс", "LIGHT_BLUE_BANNER", true, false),
    PAN("pan", "Флаг: пан", "PINK_BANNER", true, false),
    ACE("ace", "Флаг: асек", "BLACK_BANNER", true, false),
    ARO("aro", "Флаг: аро", "GREEN_BANNER", true, false),
    ENBY("enby", "Флаг: небинарный", "YELLOW_BANNER", true, false),
    RAINBOW_WAVE("rainbowwave", "Радужная волна", "HEART_OF_THE_SEA", true, false),
    NEON("neon", "Неон", "SEA_LANTERN", false, false),
    GLITCH("glitch", "Глитч", "COMMAND_BLOCK", false, false),
    ICE("ice", "Лёд", "BLUE_ICE", false, false),
    HOLO("holo", "Голограмма", "TINTED_GLASS", false, false),
    TYPE("type", "Печатная машинка", "WRITABLE_BOOK", false, true),
    HEART("heart", "Сердцебиение", "RED_DYE", false, false),
    GOLD("gold", "Золото", "GOLD_INGOT", true, false),
    GALAXY("galaxy", "Галактика", "ENDER_EYE", true, false),
    QUAKE("quake", "Землетрясение", "GRAVEL", false, false),
    SWING("swing", "Маятник", "CLOCK", false, false),
    ECHO("echo", "Эхо", "ECHO_SHARD", false, false),
    JEB("jeb", "Радужные буквы", "SHEEP_SPAWN_EGG", true, false),
    TOXIC("toxic", "Токсик", "SLIME_BLOCK", true, false),
    VAMPIRE("vampire", "Вампир", "REDSTONE_BLOCK", true, false),
    GHOST("ghost", "Призрак", "PHANTOM_MEMBRANE", false, false),
    LAVA("lava", "Лава", "MAGMA_CREAM", true, false),
    HUE("hue", "Перелив", "AMETHYST_SHARD", true, false),
    WAVY_OUTLINE("wavyoutline", "Волна с обводкой", "NAUTILUS_SHELL", false, false),
    TYPE_CURSOR("typecursor", "Курсор печатной машинки", "BARRIER", false, false),
    SPARKLE("sparkle", "Искры", "NETHER_STAR", false, false),
    MATRIX("matrix", "Матрица", "EMERALD", true, false),
    FROST("frost", "Иней", "PACKED_ICE", true, false),
    ELECTRIC("electric", "Электро", "LIGHTNING_ROD", true, false),
    SUNSET("sunset", "Закат", "ORANGE_TULIP", true, false),
    OCEAN("ocean", "Океан", "PRISMARINE_SHARD", true, false),
    BOUNCE("bounce", "Падение с отскоком", "SLIME_BLOCK", false, true),
    SCRAMBLE("scramble", "Расшифровка", "OBSERVER", false, true),
    EXTRUDE("extrude", "Объём", "STONE_STAIRS", false, false),
    SWEEP("sweep", "Подчёркивание", "SPECTRAL_ARROW", false, false);

    public static final int MAX_LETTER = 63;
    public static final int MAX_EFFECTS = 64;
    public static final int RESERVED_FROM = 52;
    public static final int RESERVED_TO = 55;
    public static final int ID_LIMIT = 60;

    private static final List<Effect> VISIBLE;
    private static final Effect[] BY_ID = new Effect[MAX_EFFECTS];

    static {
        List<Effect> list = new ArrayList<>();
        for (Effect e : values()) {
            if (!e.hidden()) {
                list.add(e);
            }
            BY_ID[e.id()] = e;
        }
        VISIBLE = Collections.unmodifiableList(list);
    }

    public static boolean reserved(int id) {
        return id >= RESERVED_FROM && id <= RESERVED_TO;
    }

    private final String key;
    private final String title;
    private final String icon;
    private final boolean ownColors;
    private final boolean indexed;

    Effect(String key, String title, String icon, boolean ownColors, boolean indexed) {
        this.key = key;
        this.title = title;
        this.icon = icon;
        this.ownColors = ownColors;
        this.indexed = indexed;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public String icon() {
        return icon;
    }

    public boolean ownColors() {
        return ownColors;
    }

    public boolean indexed() {
        return indexed;
    }

    public int id() {
        int i = ordinal();
        return i < RESERVED_FROM ? i : i + RESERVED_TO - RESERVED_FROM + 1;
    }

    public boolean hidden() {
        return this == TYPE_CURSOR;
    }

    public String permission() {
        return "textshader.effect." + key;
    }

    public static List<Effect> visible() {
        return VISIBLE;
    }

    public static Effect byId(int id) {
        return id >= 0 && id < MAX_EFFECTS ? BY_ID[id] : null;
    }

    public static Effect byKey(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (Effect e : values()) {
            if (!e.hidden() && (e.key.equals(k) || e.name().toLowerCase(Locale.ROOT).replace("_", "").equals(k))) {
                return e;
            }
        }
        return null;
    }
}
