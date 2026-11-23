package ink.hoka.odyssey.client.starmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Свои точки на карте — отдельно для каждого сервера и карты (мира):
 * {@code <dataDir>/waypoints/<сервер>__<карта>.json} = {@code {"waypoints": [{"name", "x", "y", "z"}]}}.
 */
final class Waypoints {
    static final int MAX = 256;
    static final int MAX_NAME = 32;

    static final class Wp {
        String name;
        int x, y, z;

        Wp(String name, int x, int y, int z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;
    private final Logger log;
    private String key;
    private final List<Wp> list = new ArrayList<>();
    private boolean dirty;

    Waypoints(Path dataDir, Logger log) {
        this.dir = dataDir.resolve("waypoints");
        this.log = log;
    }

    List<Wp> all() {
        return list;
    }

    String key() {
        return key;
    }

    /** Перейти на точки другого сервера/карты (текущие сохраняются). */
    void use(String server, String map) {
        String k = safe(server) + "__" + safe(map);
        if (k.equals(key)) return;
        save();
        key = k;
        list.clear();
        dirty = false;
        Path f = dir.resolve(k + ".json");
        if (!Files.isRegularFile(f)) return;
        try {
            if (Files.size(f) > 1 << 20) throw new IOException("файл больше 1 МБ");
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray a = o.has("waypoints") ? o.getAsJsonArray("waypoints") : new JsonArray();
            for (JsonElement e : a) {
                if (list.size() >= MAX) break;
                if (!e.isJsonObject()) continue;
                JsonObject w = e.getAsJsonObject();
                String name = w.has("name") ? clean(w.get("name").getAsString()) : "";
                list.add(new Wp(name.isEmpty() ? "Точка" : name, coord(w, "x"), coord(w, "y"), coord(w, "z")));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("starmap: точки {} не прочитаны: {}", f, e.toString());
        }
    }

    Wp add(int x, int y, int z) {
        if (list.size() >= MAX) return null;
        int n = 0;
        for (Wp w : list) {
            if (w.name.startsWith("Точка ")) {
                try {
                    n = Math.max(n, Integer.parseInt(w.name.substring(6).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Wp w = new Wp("Точка " + (n + 1), x, y, z);
        list.add(w);
        dirty = true;
        return w;
    }

    void remove(Wp w) {
        if (list.remove(w)) dirty = true;
    }

    void rename(Wp w, String name) {
        String n = clean(name);
        if (!n.equals(w.name)) {
            w.name = n;
            dirty = true;
        }
    }

    void save() {
        if (!dirty || key == null) return;
        dirty = false;
        JsonArray a = new JsonArray();
        for (Wp w : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.name);
            o.addProperty("x", w.x);
            o.addProperty("y", w.y);
            o.addProperty("z", w.z);
            a.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("waypoints", a);
        Path f = dir.resolve(key + ".json");
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(key + ".json.tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("starmap: точки не сохранены в {}: {}", f, e.toString());
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_NAME; i++) {
            char c = s.charAt(i);
            if (c >= ' ' && c != '§' && c != 0x7F) b.append(c);
        }
        return b.toString().trim();
    }

    private static int coord(JsonObject w, String k) {
        if (!w.has(k)) return 0;
        double v = w.get(k).getAsDouble();
        if (!Double.isFinite(v)) return 0;
        return (int) Math.max(-30_000_000, Math.min(30_000_000, v));
    }

    /** Часть имени файла: латиница, цифры, «.-_», не длиннее 64. */
    static String safe(String s) {
        if (s == null || s.isBlank()) return "unknown";
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (b.length() >= 64) break;
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return b.toString();
    }
}
