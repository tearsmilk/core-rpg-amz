package dev.tearsmilk.coreshaders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class Settings {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final List<String> DEFAULT_IMAGES = List.of("sky_sea", "space", "nether", "aurora", "end", "sunset",
        "volcano", "hell_end", "heaven", "walk", "rain", "sea_sand");
    public static final Map<String, String> DEFAULT_NAMES = defaultNames();
    public static final int CONFIG_VERSION = 4;
    public static final List<String> ADDED_IN_2 = List.of("sunset", "volcano", "hell_end", "heaven", "walk", "rain",
        "sea_sand");

    public static final Map<String, String> OLD_IN_3 = Map.of(
        "messages.upload-usage",
        "<red>Пиши: <white>/cs upload <имя> <https-ссылка на png/jpg></white>. Имя: a-z, 0-9, _.</red>",
        "messages.import-usage",
        "<red>Пиши: <white>/cs import <имя></white> (файл plugins/coreshaders/images/<имя>.png или .jpg). Есть: <white><list></white>.</red>",
        "messages.image-no-file",
        "<red>Нет файла <white><image>.png</white> (или .jpg) в <white><folder></white>.</red>",
        "messages.image-not-image",
        "<red><white><image></white> - не png и не jpg.</red>");
    public static final List<String> OLD_HELP_IN_3 = List.of(
        "<white>/cs upload <имя> <ссылка></white> <gray>- своя картинка из интернета</gray>",
        "<white>/cs import <имя></white> <gray>- своя картинка из plugins/coreshaders/images</gray>");
    public static final List<String> ADDED_IN_4 = List.of("messages.image-anim-frames", "messages.image-anim-budget",
        "messages.image-anim-total", "messages.image-anim-file", "messages.image-added-anim");
    public static final String ANIMATION = "custom-images.animation";

    public final List<String> images;
    public final Map<String, String> imageNames;
    public final Map<String, Integer> colors;
    public final Shape defaultShape;
    public final String defaultImage;
    public final int defaultColor;
    public final double maxDistance;
    public final double planeOffset;
    public final int brightness;
    public final boolean rimDefault;
    public final Actions.Trigger defaultTrigger;
    public final double defaultCooldown;
    public final int maxGroup;
    public final int customMax;
    public final long maxDownloadBytes;
    public final long maxPixels;
    public final int downloadTimeout;
    public final String packAssets;
    public final String packPlugin;
    public final List<String> reloadCommands;
    public final Map<String, Integer> customKinds;
    public final int animMaxFrames;
    public final int animWidth;
    public final boolean animInterpolate;
    public final long animMaxTextureBytes;
    public final long animTotalTextureBytes;
    public final long animMaxFileBytes;
    public final int animMaxSourceFrames;
    private final ConfigurationSection messages;

    public Settings(FileConfiguration c) {
        List<String> imgs = new ArrayList<>();
        for (String s : c.getStringList("images")) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty() && !imgs.contains(v)) {
                imgs.add(v);
            }
        }
        if (imgs.isEmpty()) {
            imgs.addAll(DEFAULT_IMAGES);
        }
        images = Collections.unmodifiableList(imgs);
        Map<String, String> names = new LinkedHashMap<>(DEFAULT_NAMES);
        ConfigurationSection ns = c.getConfigurationSection("image-names");
        if (ns != null) {
            for (String k : ns.getKeys(false)) {
                names.put(k.toLowerCase(Locale.ROOT), ns.getString(k, k));
            }
        }
        imageNames = Collections.unmodifiableMap(names);
        Map<String, Integer> cols = new LinkedHashMap<>();
        ConfigurationSection cs = c.getConfigurationSection("colors");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                Integer v = Colors.parse(cs.getString(k, ""), null);
                if (v != null) {
                    cols.put(k.toLowerCase(Locale.ROOT), v);
                }
            }
        }
        colors = Collections.unmodifiableMap(cols);
        Shape sh = Shape.parse(c.getString("defaults.shape", "cube"));
        defaultShape = sh == null ? Shape.CUBE : sh;
        String di = c.getString("defaults.image", images.get(0)).toLowerCase(Locale.ROOT);
        defaultImage = images.contains(di) ? di : images.get(0);
        Integer dc = Colors.parse(c.getString("defaults.color", "#FFFFFF"), colors);
        defaultColor = dc == null ? 0xFFFFFF : dc;
        maxDistance = Math.max(1.0, Math.min(64.0, c.getDouble("max-distance", 8.0)));
        planeOffset = Math.max(0.001, Math.min(0.2, c.getDouble("plane-offset", 0.015)));
        brightness = Math.max(0, Math.min(15, c.getInt("brightness", 15)));
        rimDefault = c.getBoolean("rim", false);
        Actions.Trigger tr = Actions.Trigger.parse(c.getString("commands.default-trigger", "enter"));
        defaultTrigger = tr == null || tr == Actions.Trigger.OFF ? Actions.Trigger.ENTER : tr;
        defaultCooldown = Math.max(0.0, Math.min(86400.0, c.getDouble("commands.default-cooldown", 2.0)));
        maxGroup = Math.max(1, Math.min(16384, c.getInt("commands.max-group", 1024)));
        customMax = Math.max(0, Math.min(Panorama.KIND_MAX - Panorama.KIND_MIN + 1, c.getInt("custom-images.max", 24)));
        maxDownloadBytes = (long) (Math.max(0.1, Math.min(256.0, c.getDouble("custom-images.max-download-mb", 16.0)))
            * 1024 * 1024);
        maxPixels = (long) (Math.max(0.1, Math.min(200.0, c.getDouble("custom-images.max-megapixels", 40.0)))
            * 1_000_000);
        downloadTimeout = Math.max(1, Math.min(300, c.getInt("custom-images.timeout-seconds", 20)));
        packAssets = c.getString("custom-images.pack-assets", "Nexo/pack/assets");
        packPlugin = c.getString("custom-images.pack-plugin", "Nexo");
        reloadCommands = List.copyOf(c.getStringList("custom-images.reload-commands"));
        Map<String, Integer> kinds = new LinkedHashMap<>();
        ConfigurationSection ks = c.getConfigurationSection("custom-images.kinds");
        if (ks != null) {
            for (String k : ks.getKeys(false)) {
                int v = ks.getInt(k, -1);
                String n = k.toLowerCase(Locale.ROOT);
                if (v >= Panorama.KIND_MIN && v <= Panorama.KIND_MAX && Panorama.validName(n)) {
                    kinds.put(n, v);
                }
            }
        }
        customKinds = Collections.unmodifiableMap(kinds);
        animMaxFrames = Math.max(2, Math.min(256, c.getInt("custom-images.animation.max-frames", 32)));
        animWidth = Panorama.frameWidth(c.getInt("custom-images.animation.width", 512));
        animInterpolate = c.getBoolean("custom-images.animation.interpolate", false);
        animMaxTextureBytes = mb(c.getDouble("custom-images.animation.max-texture-mb", 16.0));
        animTotalTextureBytes = mb(c.getDouble("custom-images.animation.total-texture-mb", 96.0));
        animMaxFileBytes = mb(c.getDouble("custom-images.animation.max-file-mb", 8.0));
        animMaxSourceFrames = Math.max(2, Math.min(10000, c.getInt("custom-images.animation.max-source-frames", 1000)));
        messages = c.getConfigurationSection("messages");
    }

    public static List<String> upgrade(FileConfiguration c) {
        return upgrade(c, new ArrayList<>());
    }

    public static List<String> upgrade(FileConfiguration c, List<String> kept) {
        List<String> added = new ArrayList<>();
        int version = c.getInt("config-version", 1);
        if (version >= CONFIG_VERSION) {
            return added;
        }
        List<String> imgs = new ArrayList<>(c.getStringList("images"));
        if (version < 2 && !imgs.isEmpty()) {
            List<String> low = new ArrayList<>();
            for (String s : imgs) {
                low.add(s.trim().toLowerCase(Locale.ROOT));
            }
            for (String img : ADDED_IN_2) {
                if (!low.contains(img)) {
                    imgs.add(img);
                    added.add(img);
                }
            }
            c.set("images", imgs);
        }
        if (version < 3 && c.getDefaults() != null) {
            for (String key : List.of("messages.help", "messages.wand-lore")) {
                List<String> def = c.getDefaults().getStringList(key);
                if (!def.isEmpty()) {
                    c.set(key, def);
                }
            }
        }
        if (version < 4 && c.getDefaults() != null) {
            upgradeTo4(c, c.getDefaults(), kept);
        }
        c.set("config-version", CONFIG_VERSION);
        return added;
    }

    private static void upgradeTo4(FileConfiguration c, Configuration d, List<String> kept) {
        for (Map.Entry<String, String> e : OLD_IN_3.entrySet()) {
            String key = e.getKey();
            String def = d.getString(key);
            if (def == null || !c.isSet(key)) {
                continue;
            }
            if (e.getValue().equals(c.getString(key))) {
                c.set(key, def);
            } else if (!def.equals(c.getString(key))) {
                kept.add(key);
            }
        }
        if (c.isSet("messages.help")) {
            List<String> help = new ArrayList<>(c.getStringList("messages.help"));
            List<String> defHelp = d.getStringList("messages.help");
            boolean changed = false;
            for (String old : OLD_HELP_IN_3) {
                int at = help.indexOf(old);
                String repl = replacement(old, defHelp);
                if (at >= 0 && repl != null) {
                    help.set(at, repl);
                    changed = true;
                } else if (repl != null && !help.contains(repl)) {
                    kept.add("messages.help");
                }
            }
            if (changed) {
                c.set("messages.help", help);
            }
        }
        for (String key : ADDED_IN_4) {
            Object def = d.get(key);
            if (def != null && !c.isSet(key)) {
                c.set(key, def);
            }
        }
        ConfigurationSection anim = d.getConfigurationSection(ANIMATION);
        if (anim != null) {
            for (String k : anim.getKeys(false)) {
                String key = ANIMATION + "." + k;
                if (!c.isSet(key)) {
                    c.set(key, anim.get(k));
                }
            }
        }
    }

    private static String replacement(String old, List<String> defHelp) {
        String head = old.substring(0, old.indexOf("</white>"));
        for (String s : defHelp) {
            if (s.startsWith(head + "</white>")) {
                return s;
            }
        }
        return null;
    }

    private static long mb(double v) {
        return (long) (Math.max(0.1, Math.min(4096.0, v)) * 1024 * 1024);
    }

    public boolean isCustom(String image) {
        return customKinds.containsKey(image);
    }

    private static Map<String, String> defaultNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sky_sea", "Небо и море");
        m.put("space", "Космос");
        m.put("nether", "Незер");
        m.put("aurora", "Северное сияние");
        m.put("end", "Энд");
        m.put("sunset", "Закат");
        m.put("volcano", "Вулкан");
        m.put("hell_end", "Ад и Энд");
        m.put("heaven", "Рай");
        m.put("walk", "Прогулка");
        m.put("rain", "Дождь");
        m.put("sea_sand", "Море и песок");
        return Collections.unmodifiableMap(m);
    }

    public String imageName(String image) {
        return imageNames.getOrDefault(image, image);
    }

    public Integer parseColor(String s) {
        return Colors.parse(s, colors);
    }

    public String colorList() {
        List<String> l = new ArrayList<>();
        for (String k : colors.keySet()) {
            if (k.chars().allMatch(ch -> ch < 128)) {
                l.add(k);
            }
        }
        return String.join(", ", l);
    }

    private String raw(String key) {
        if (messages == null) {
            return key;
        }
        String v = messages.getString(key);
        return v == null ? key : v;
    }

    public String rawText(String key) {
        return raw(key);
    }

    public static TagResolver colorTag(int rgb) {
        return Placeholder.styling("color_tag", TextColor.color(rgb & 0xFFFFFF));
    }

    public Component msg(String key, TagResolver... resolvers) {
        return MM.deserialize(raw("prefix") + raw(key), resolvers);
    }

    public Component plain(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> lines(String key, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (String s : messages.getStringList(key)) {
            out.add(MM.deserialize(s, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return out;
    }
}
