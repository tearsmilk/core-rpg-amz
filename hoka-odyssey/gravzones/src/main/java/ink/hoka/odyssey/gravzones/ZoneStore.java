package ink.hoka.odyssey.gravzones;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Файл module/gravzones/zones.json. Формат:
 * <pre>{"version": 1, "zones": [{"name": "a", "map": "world", "min": [x, y, z], "max": [x, y, z], "g": 0.5, "created": 1}]}</pre>
 * Запись — целиком во временный файл и переименование (файл не бывает наполовину записан). Записи нумеруются: если
 * два сохранения из разных потоков пришли не по порядку, старое не затирает новое.
 */
final class ZoneStore {
    private final Path file;
    private final Object lock = new Object();
    private long written = -1;

    ZoneStore(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    static String toJson(Collection<Zone> zones) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray arr = new JsonArray();
        for (Zone z : zones) {
            JsonObject o = new JsonObject();
            o.addProperty("name", z.name());
            o.addProperty("map", z.map());
            o.add("min", triple(z.minX(), z.minY(), z.minZ()));
            o.add("max", triple(z.maxX(), z.maxY(), z.maxZ()));
            o.addProperty("g", z.g());
            o.addProperty("created", z.created());
            arr.add(o);
        }
        root.add("zones", arr);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static JsonArray triple(int a, int b, int c) {
        JsonArray t = new JsonArray();
        t.add(a);
        t.add(b);
        t.add(c);
        return t;
    }

    /** Разобрать файл. Битые записи пропускаются, о них сообщается в warn. */
    static List<Zone> fromJson(String json, Consumer<String> warn) {
        List<Zone> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("zones")) {
            return out;
        }
        long seq = 0;
        for (JsonElement e : root.getAsJsonObject().getAsJsonArray("zones")) {
            try {
                JsonObject o = e.getAsJsonObject();
                JsonArray a = o.getAsJsonArray("min");
                JsonArray b = o.getAsJsonArray("max");
                double g = o.get("g").getAsDouble();
                String name = o.get("name").getAsString();
                if (!Physics.valid(g)) {
                    warn.accept("зона " + name + ": сила " + g + " вне 0.1…13 — пропущена");
                    continue;
                }
                long created = o.has("created") ? o.get("created").getAsLong() : ++seq;
                seq = Math.max(seq, created);
                out.add(new Zone(name, o.get("map").getAsString(),
                    a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(),
                    b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), g, created));
            } catch (RuntimeException ex) {
                warn.accept("битая запись " + e + ": " + ex.getMessage());
            }
        }
        return out;
    }

    /** Прочитать (нет файла — пусто). Вызывать не в тике. */
    List<Zone> load(Consumer<String> warn) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        return fromJson(Files.readString(file, StandardCharsets.UTF_8), warn);
    }

    /** Записать снимок номер version. Вызывать не в тике. */
    void save(long version, String json) throws IOException {
        synchronized (lock) {
            if (version <= written) {
                return;                                 // уже записан более новый снимок
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            written = version;
        }
    }
}
