package ink.hoka.odyssey.coreshaders;

import ink.hoka.odyssey.api.ModuleConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Настройки и сообщения из config.yml модуля. Неизменяемый снимок: после /hocs reload — новый объект. */
public final class Settings {
    public static final List<String> DEFAULT_IMAGES = List.of("sky_sea", "space", "nether", "aurora", "end", "sunset",
        "volcano", "hell_end", "heaven", "walk", "rain", "sea_sand");
    public static final Map<String, String> DEFAULT_NAMES = defaultNames();

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
    public final Map<String, Integer> customKinds;
    public final ImageBuilder.Anim anim;
    private final Map<String, Object> messages;
    private final Map<String, Object> defaultMessages;

    public Settings(ModuleConfig c, Map<String, Object> defaultMessages) {
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
        for (String k : c.keys("image-names")) {
            names.put(k.toLowerCase(Locale.ROOT), c.getString("image-names." + k, k));
        }
        imageNames = Collections.unmodifiableMap(names);
        Map<String, Integer> cols = new LinkedHashMap<>();
        for (String k : c.keys("colors")) {
            Integer v = Colors.parse(c.getString("colors." + k, ""), null);
            if (v != null) {
                cols.put(k.toLowerCase(Locale.ROOT), v);
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
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (String k : c.keys("custom-images.kinds")) {
            int v = c.getInt("custom-images.kinds." + k, -1);
            String n = k.toLowerCase(Locale.ROOT);
            if (v >= Panorama.KIND_MIN && v <= Panorama.KIND_MAX && Panorama.validName(n)) {
                kinds.put(n, v);
            }
        }
        customKinds = Collections.unmodifiableMap(kinds);
        String a = "custom-images.animation.";
        anim = new ImageBuilder.Anim(
            Math.max(2, Math.min(256, c.getInt(a + "max-frames", 32))),
            Panorama.frameWidth(c.getInt(a + "width", 512)),
            c.getBoolean(a + "interpolate", false),
            mb(c.getDouble(a + "max-texture-mb", 16.0)),
            mb(c.getDouble(a + "total-texture-mb", 96.0)),
            mb(c.getDouble(a + "max-file-mb", 8.0)),
            Math.max(2, Math.min(10000, c.getInt(a + "max-source-frames", 1000))));
        Map<String, Object> msgs = new LinkedHashMap<>();
        for (String k : c.keys("messages")) {
            msgs.put(k, c.get("messages." + k));
        }
        messages = msgs;
        this.defaultMessages = defaultMessages;
    }

    private static long mb(double v) {
        return (long) (Math.max(0.1, Math.min(4096.0, v)) * 1024 * 1024);
    }

    /** Раздел messages из config.yml внутри jar — значения по умолчанию для пропущенных ключей. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> bundledMessages(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream("config.yml")) {
            if (in == null) {
                return Map.of();
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
                if (root instanceof Map<?, ?> m && m.get("messages") instanceof Map<?, ?> msgs) {
                    return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) msgs));
                }
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return Map.of();
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

    private Object rawObj(String key) {
        Object v = messages.get(key);
        return v != null ? v : defaultMessages.get(key);
    }

    public String raw(String key) {
        Object v = rawObj(key);
        return v == null ? key : String.valueOf(v);
    }

    /** Цвет как текст #RRGGBB, окрашенный этим цветом. */
    public static Component color(int rgb) {
        return Component.text(Colors.hex(rgb), TextColor.color(rgb & 0xFFFFFF));
    }

    /** Подстановки: ключ, значение, ключ, значение… (значение — строка, число или Component). */
    static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** Сообщение с префиксом. */
    public Component msg(String key, Object... kv) {
        return Text.of(raw("prefix") + raw(key), vars(kv));
    }

    /** Без префикса. */
    public Component plain(String key, Object... kv) {
        return Text.of(raw(key), vars(kv));
    }

    /** Строка для названия предмета (без курсива). */
    public Component itemLine(String key, Object... kv) {
        return Text.item(raw(key), vars(kv));
    }

    public List<Component> lines(String key, boolean item, Object... kv) {
        List<Component> out = new ArrayList<>();
        Object v = rawObj(key);
        if (!(v instanceof List<?> l)) {
            return out;
        }
        Map<String, Object> vars = vars(kv);
        for (Object s : l) {
            out.add(item ? Text.item(String.valueOf(s), vars) : Text.of(String.valueOf(s), vars));
        }
        return out;
    }
}
