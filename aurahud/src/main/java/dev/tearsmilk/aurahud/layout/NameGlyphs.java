package dev.tearsmilk.aurahud.layout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tearsmilk.aurahud.layout.Placement.Point;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class NameGlyphs {

    public static final Point VANILLA = new Point(0, 8);
    public static final List<Point> DEFAULT = List.of(VANILLA);
    public static final int MAX = 16;
    public static final int MAX_HEIGHT = 32;
    private static final int NAME = -59;
    private static final int CREATIVE = -45;
    private static final int ACTIONBAR = -72;
    private static final String PROVIDERS = "providers";
    private static final String TYPE = "type";
    private static final String ASCENT = "ascent";
    private static final String HEIGHT = "height";

    private NameGlyphs() {
    }

    public static List<Point> scan(Path assets) {
        Set<Point> found = new LinkedHashSet<>();
        if (assets == null || !Files.isDirectory(assets)) {
            return DEFAULT;
        }
        try (Stream<Path> spaces = Files.list(assets)) {
            for (Path ns : spaces.sorted().toList()) {
                Path fonts = ns.resolve("font");
                if (!Files.isDirectory(fonts)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(fonts)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                        found.addAll(read(f));
                    }
                }
            }
        } catch (IOException ignored) {
            return DEFAULT;
        }
        return select(found);
    }

    static List<Point> read(Path file) {
        List<Point> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has(PROVIDERS) || !root.getAsJsonObject().get(PROVIDERS).isJsonArray()) {
                return out;
            }
            for (JsonElement el : root.getAsJsonObject().getAsJsonArray(PROVIDERS)) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject p = el.getAsJsonObject();
                if (!p.has(TYPE) || !"bitmap".equals(p.get(TYPE).getAsString()) || !p.has(ASCENT)) {
                    continue;
                }
                int ascent = p.get(ASCENT).getAsInt();
                int height = p.has(HEIGHT) ? p.get(HEIGHT).getAsInt() : 8;
                if (height > 0 && height <= MAX_HEIGHT && ascent <= height) {
                    out.add(new Point(7 - ascent, height));
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    public static List<Point> select(Set<Point> found) {
        List<Point> all = new ArrayList<>(found);
        all.remove(VANILLA);
        all.sort(Comparator.comparingInt(Point::y).thenComparingInt(Point::x));
        List<Point> out = new ArrayList<>(DEFAULT);
        for (Point p : all) {
            if (out.size() >= MAX) {
                break;
            }
            if (!collides(p, List.of(p, VANILLA))) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    static boolean collides(Point p, List<Point> others) {
        for (Point q : others) {
            for (int base : new int[]{NAME, CREATIVE}) {
                for (int s = 0; s <= 1; s++) {
                    for (int t = 0; t <= 1; t++) {
                        if (base + p.x() + s == ACTIONBAR + q.x() + t || base + p.x() + p.y() + s == ACTIONBAR + q.x() + q.y() + t) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
