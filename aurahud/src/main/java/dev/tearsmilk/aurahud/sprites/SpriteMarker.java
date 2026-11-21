package dev.tearsmilk.aurahud.sprites;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class SpriteMarker {

    public static final String DIR = "minecraft/textures/gui/sprites/hud/";
    public static final String JAR = "aurasprites/";
    public static final int MARK_R = 167;
    public static final int MARK_B = 90;
    private static final String TEXTURE = "texture";
    private static final String ANIMATION = "animation";
    private static final String WIDTH = "width";
    private static final String HEIGHT = "height";
    private static final String STRATEGY = "mipmap_strategy";
    public static final String MCMETA = "{\n  \"texture\": {\n    \"mipmap_strategy\": \"mean\"\n  }\n}\n";

    public enum Family {
        HEART(1), ARMOR(2), FOOD(3), AIR(4), MOUNT(5);

        private final int code;

        Family(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public int marker() {
            return MARK_R << 16 | code << 8 | MARK_B;
        }
    }

    public record Result(Map<String, byte[]> files, List<String> unmarkable, List<String> backups, int sprites, int changed) {
    }

    private SpriteMarker() {
    }

    public static Map<String, Family> sprites() {
        Map<String, Family> out = new LinkedHashMap<>();
        for (String c : List.of("container", "container_blinking", "container_hardcore", "container_hardcore_blinking")) {
            out.put("heart/" + c, Family.HEART);
        }
        for (String type : List.of("", "poisoned_", "withered_", "absorbing_", "frozen_")) {
            for (String hc : List.of("", "hardcore_")) {
                for (String fill : List.of("full", "half")) {
                    for (String blink : List.of("", "_blinking")) {
                        out.put("heart/" + type + hc + fill + blink, Family.HEART);
                    }
                }
            }
        }
        for (String v : List.of("vehicle_container", "vehicle_full", "vehicle_half")) {
            out.put("heart/" + v, Family.MOUNT);
        }
        for (String v : List.of("armor_empty", "armor_half", "armor_full")) {
            out.put(v, Family.ARMOR);
        }
        for (String v : List.of("food_empty", "food_half", "food_full", "food_empty_hunger", "food_half_hunger", "food_full_hunger")) {
            out.put(v, Family.FOOD);
        }
        for (String v : List.of("air", "air_bursting", "air_empty")) {
            out.put(v, Family.AIR);
        }
        return out;
    }

    public static byte[] vanilla(String name) {
        try (InputStream in = SpriteMarker.class.getClassLoader().getResourceAsStream(JAR + DIR + name + ".png")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    public static Result prepare(Path assets, Path backupDir, List<Path> external) {
        return prepare(assets, backupDir, SpriteMarker::vanilla, external);
    }

    public static Map<String, byte[]> external(List<Path> sources) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (sources == null) {
            return out;
        }
        for (Path src : sources) {
            try {
                if (Files.isDirectory(src)) {
                    Path base = src.resolve("assets").resolve(DIR);
                    if (Files.isDirectory(base)) {
                        try (var walk = Files.walk(base)) {
                            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                                out.putIfAbsent(DIR + base.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                            }
                        }
                    }
                } else if (Files.isRegularFile(src) && src.getFileName().toString().endsWith(".zip")) {
                    try (ZipFile zip = new ZipFile(src.toFile())) {
                        Enumeration<? extends ZipEntry> en = zip.entries();
                        while (en.hasMoreElements()) {
                            ZipEntry e = en.nextElement();
                            if (!e.isDirectory() && e.getName().startsWith("assets/" + DIR)) {
                                try (InputStream in = zip.getInputStream(e)) {
                                    out.putIfAbsent(e.getName().substring("assets/".length()), in.readAllBytes());
                                }
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                out.putIfAbsent("", new byte[0]);
            }
        }
        out.remove("");
        return out;
    }

    public static Result prepare(Path assets, Path backupDir, Function<String, byte[]> vanilla, List<Path> externalSources) {
        Map<String, byte[]> ext = external(externalSources);
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> unmarkable = new ArrayList<>();
        List<String> backups = new ArrayList<>();
        int changed = 0;
        for (Map.Entry<String, Family> e : sprites().entrySet()) {
            String rel = DIR + e.getKey() + ".png";
            String metaRel = rel + ".mcmeta";
            try {
                byte[] existing = read(assets, rel);
                byte[] metaBytes = read(assets, metaRel);
                byte[] van = vanilla.apply(e.getKey());
                byte[] src = existing;
                if (ext.containsKey(rel) && (existing == null || sameAsMarked(existing, van, e.getValue()))) {
                    src = ext.get(rel);
                    if (metaBytes == null || new String(metaBytes, StandardCharsets.UTF_8).equals(MCMETA)) {
                        byte[] em = ext.get(metaRel);
                        metaBytes = em != null ? em : metaBytes;
                    }
                }
                if (src == null) {
                    src = van;
                }
                if (src == null) {
                    unmarkable.add(rel + " (нет исходной текстуры)");
                    continue;
                }
                JsonObject meta = metaBytes == null ? null : JsonParser.parseString(new String(metaBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                BufferedImage img = argb(ImageIO.read(new ByteArrayInputStream(src)));
                if (img == null) {
                    unmarkable.add(rel + " (не PNG)");
                    continue;
                }
                List<int[]> corners = corners(img, meta);
                if (corners.isEmpty()) {
                    unmarkable.add(rel + " (размер кадров не делится)");
                    continue;
                }
                int mark = e.getValue().marker();
                boolean done = true;
                boolean free = true;
                for (int[] c : corners) {
                    int px = img.getRGB(c[0], c[1]);
                    free &= (px >>> 24) == 0;
                    done &= px == mark;
                }
                if (!free) {
                    unmarkable.add(rel + " (непрозрачный угол)");
                    continue;
                }
                if (!done || src != existing) {
                    for (int[] c : corners) {
                        img.setRGB(c[0], c[1], mark);
                    }
                    byte[] out = png(img);
                    if (existing == null || !Arrays.equals(existing, out)) {
                        files.put(rel, out);
                        changed++;
                        if (existing != null && backup(backupDir, rel, existing)) {
                            backups.add(rel);
                        }
                    }
                }
                byte[] oldMeta = read(assets, metaRel);
                JsonObject next = mean(meta);
                if (next == null && meta != null && oldMeta == null) {
                    next = meta;
                }
                if (next != null) {
                    files.put(metaRel, meta == null ? MCMETA.getBytes(StandardCharsets.UTF_8)
                            : (new GsonBuilder().setPrettyPrinting().create().toJson(next) + "\n").getBytes(StandardCharsets.UTF_8));
                    if (oldMeta != null && !Arrays.equals(oldMeta, files.get(metaRel)) && backup(backupDir, metaRel, oldMeta)) {
                        backups.add(metaRel);
                    }
                    if (oldMeta != null && Arrays.equals(oldMeta, files.get(metaRel))) {
                        files.remove(metaRel);
                    }
                }
            } catch (Exception ex) {
                unmarkable.add(rel + " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
            }
        }
        return new Result(files, unmarkable, backups, sprites().size(), changed);
    }

    static boolean sameAsMarked(byte[] existing, byte[] vanilla, Family family) {
        if (vanilla == null) {
            return false;
        }
        try {
            BufferedImage a = argb(ImageIO.read(new ByteArrayInputStream(existing)));
            BufferedImage b = argb(ImageIO.read(new ByteArrayInputStream(vanilla)));
            if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                return false;
            }
            for (int[] c : corners(b, null)) {
                b.setRGB(c[0], c[1], family.marker());
            }
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    if (a.getRGB(x, y) != b.getRGB(x, y)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static JsonObject mean(JsonObject meta) {
        if (meta == null) {
            return new JsonObject();
        }
        JsonElement tex = meta.get(TEXTURE);
        if (tex != null && tex.isJsonObject()) {
            JsonElement s = tex.getAsJsonObject().get(STRATEGY);
            if (s != null && s.isJsonPrimitive() && "mean".equals(s.getAsString())) {
                return null;
            }
        }
        JsonObject copy = meta.deepCopy();
        JsonObject t = tex != null && tex.isJsonObject() ? copy.getAsJsonObject(TEXTURE) : new JsonObject();
        t.addProperty(STRATEGY, "mean");
        copy.add(TEXTURE, t);
        return copy;
    }

    static List<int[]> corners(BufferedImage img, JsonObject meta) {
        int w = img.getWidth();
        int h = img.getHeight();
        int fw = w;
        int fh = h;
        JsonObject anim = meta != null && meta.has(ANIMATION) && meta.get(ANIMATION).isJsonObject() ? meta.getAsJsonObject(ANIMATION) : null;
        if (anim != null) {
            Integer aw = anim.has(WIDTH) ? anim.get(WIDTH).getAsInt() : null;
            Integer ah = anim.has(HEIGHT) ? anim.get(HEIGHT).getAsInt() : null;
            if (aw == null && ah == null) {
                fw = Math.min(w, h);
                fh = fw;
            } else {
                fw = aw != null ? aw : w;
                fh = ah != null ? ah : h;
            }
        }
        List<int[]> out = new ArrayList<>();
        if (fw <= 0 || fh <= 0 || w % fw != 0 || h % fh != 0) {
            return out;
        }
        for (int y = 0; y < h; y += fh) {
            for (int x = 0; x < w; x += fw) {
                out.add(new int[]{x, y});
                out.add(new int[]{x, y + fh - 1});
                out.add(new int[]{x + fw - 1, y + fh - 1});
                out.add(new int[]{x + fw - 1, y});
            }
        }
        return out;
    }

    static BufferedImage argb(BufferedImage src) {
        if (src == null) {
            return null;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        ColorModel cm = src.getColorModel();
        Raster raster = src.getRaster();
        boolean gray = !(cm instanceof IndexColorModel) && cm.getColorSpace().getType() == ColorSpace.TYPE_GRAY;
        int bands = raster.getNumBands();
        int[] px = new int[bands];
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                if (gray) {
                    raster.getPixel(x, y, px);
                    int g = sample(px[0], cm.getComponentSize(0));
                    int a = bands > 1 ? sample(px[1], cm.getComponentSize(1)) : 255;
                    out.setRGB(x, y, a << 24 | g << 16 | g << 8 | g);
                } else {
                    out.setRGB(x, y, src.getRGB(x, y));
                }
            }
        }
        return out;
    }

    private static int sample(int v, int bits) {
        return bits == 8 ? v : bits > 8 ? v >> (bits - 8) : v * 255 / ((1 << bits) - 1);
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] read(Path assets, String rel) throws IOException {
        if (assets == null) {
            return null;
        }
        Path p = assets.resolve(rel);
        return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    private static boolean backup(Path backupDir, String rel, byte[] data) throws IOException {
        if (backupDir == null) {
            return false;
        }
        Path b = backupDir.resolve(rel);
        Files.createDirectories(b.getParent());
        Files.write(b, data);
        return true;
    }
}
