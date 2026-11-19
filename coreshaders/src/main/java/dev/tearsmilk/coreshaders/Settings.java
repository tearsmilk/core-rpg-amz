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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class Settings {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final List<String> DEFAULT_IMAGES = List.of("sky_sea", "space", "nether", "aurora", "end", "sunset",
        "volcano", "hell_end", "heaven", "walk", "rain", "sea_sand");
    public static final Map<String, String> DEFAULT_NAMES = defaultNames();
    public static final int CONFIG_VERSION = 3;
    public static final List<String> ADDED_IN_2 = List.of("sunset", "volcano", "hell_end", "heaven", "walk", "rain",
        "sea_sand");

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
        messages = c.getConfigurationSection("messages");
    }

    public static List<String> upgrade(FileConfiguration c) {
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
        c.set("config-version", CONFIG_VERSION);
        return added;
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
