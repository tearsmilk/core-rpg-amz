package dev.tearsmilk.textshader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRulesTest {

    private static final String SLASH = "/";

    private static Path root() {
        return Path.of(System.getProperty("textshader.root", "."));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path include(String name) {
        return root().resolve("pack/assets/textshader/shaders/include").resolve(name);
    }

    private static List<String> arrayItems(String src, String name) {
        Matcher m = Pattern.compile(name + "\\[[A-Z_0-9]+\\] = [a-z0-9]+\\[\\]\\((.*?)\\);", Pattern.DOTALL).matcher(src);
        assertTrue(m.find(), name);
        List<String> out = new ArrayList<>();
        Matcher v = Pattern.compile("vec3\\([^)]*\\)|true|false").matcher(m.group(1));
        while (v.find()) {
            out.add(v.group());
        }
        return out;
    }

    @Test
    void shaderTablesMatchJava() {
        String common = read(include("ts_common.glsl"));
        Matcher n = Pattern.compile("#define TS_EFFECTS (\\d+)").matcher(common);
        assertTrue(n.find());
        assertEquals(Effect.ID_LIMIT, Integer.parseInt(n.group(1)));
        assertTrue(common.contains("#define TS_RESERVED_FROM " + Effect.RESERVED_FROM));
        assertTrue(common.contains("#define TS_RESERVED_TO " + Effect.RESERVED_TO));
        List<String> own = arrayItems(common, "TS_OWN");
        assertEquals(Effect.ID_LIMIT, own.size());
        for (Effect e : Effect.values()) {
            assertEquals(e.ownColors(), Boolean.parseBoolean(own.get(e.id())), e.key());
        }
        List<String> pal = arrayItems(common, "TS_PALETTE");
        assertEquals(Palette.values().length, pal.size());
        for (Palette p : Palette.values()) {
            String[] f = pal.get(p.ordinal()).replace("vec3(", "").replace(")", "").split(",");
            int[] rgb = {(p.rgb() >> 16) & 255, (p.rgb() >> 8) & 255, p.rgb() & 255};
            for (int i = 0; i < 3; i++) {
                assertEquals(rgb[i] / 255.0, Double.parseDouble(f[i].trim()), 0.0001, p.key());
            }
        }
        assertEquals(Effect.ID_LIMIT, arrayItems(common, "TS_WORLD").size());
        assertTrue(common.contains("effect == " + Effect.BOUNCE.id() + ") {\n        return vec2(4.0, 40.0);"));
        String effects = read(include("ts_effects.glsl"));
        for (Effect e : Effect.values()) {
            assertTrue(effects.contains("id == " + e.id() + ")"), e.key());
        }
        String preview = read(root().resolve("tools/preview.py"));
        for (Effect e : Effect.values()) {
            assertTrue(preview.contains("\"" + e.key() + "\""), e.key());
        }
    }

    @Test
    void everyMessageKeyUsedInCodeExists() throws IOException {
        String config = read(root().resolve("src/main/resources/config.yml"));
        Pattern use = Pattern.compile("(?:line|send|get|raw)\\(\\s*(?:[a-zA-Z]+,\\s*)?\"([a-z0-9-]+)\"");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root().resolve("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = use.matcher(read(p));
                while (m.find()) {
                    String key = m.group(1);
                    if (!config.contains("\n  " + key + ":")) {
                        missing.add(p.getFileName() + ": " + key);
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void noCommentsAndNoLongDashes() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root())) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String rel = root().relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("build/") || rel.startsWith(".gradle/") || rel.startsWith("gradle/") || rel.startsWith("preview/")
                        || rel.startsWith("gradlew") || rel.contains("__pycache__") || rel.endsWith(".png") || rel.endsWith(".gif") || rel.endsWith(".jar")) {
                    continue;
                }
                String s = read(p);
                if (s.indexOf((char) 0x2014) >= 0 || s.indexOf((char) 0x2013) >= 0) {
                    bad.add("dash: " + rel);
                }
                boolean code = rel.endsWith(".java") || rel.endsWith(".glsl") || rel.endsWith(".vsh") || rel.endsWith(".fsh");
                if (code && (s.contains(SLASH + SLASH) || s.contains(SLASH + "*"))) {
                    bad.add("comment: " + rel);
                }
            }
        }
        assertEquals(List.of(), bad);
    }
}
