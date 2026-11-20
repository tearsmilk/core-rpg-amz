package dev.tearsmilk.nameshader;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRulesTest {

    private static final String SLASH = "/";

    private static Path root() {
        return Path.of(System.getProperty("nameshader.root", "."));
    }

    private static Path assets() {
        return root().resolve("pack/assets");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void markerMatchesShader() {
        String vertex = read(assets().resolve("nameshader/shaders/include/ns_display_vertex.glsl"));
        assertTrue(vertex.contains("#define NS_MARK ivec4(" + Marker.MARK_R + ", " + Marker.MARK_G + ", " + Marker.MARK_B
                + ", " + Marker.MARK_A + ")"));
        String fragment = read(assets().resolve("nameshader/shaders/include/ns_display_fragment.glsl"));
        assertTrue(fragment.contains("#define NS_MARK_RGB vec3(" + Marker.MARK_R + ".0, " + Marker.MARK_G + ".0, "
                + Marker.MARK_B + ".0)"));
    }

    @Test
    void whiteGlyphRegionMatchesClient() {
        String tag = read(assets().resolve("nameshader/shaders/include/ns_tag.glsl"));
        assertTrue(tag.contains("#define NS_WHITE_U0 (0.01 / 256.0)"));
        assertTrue(tag.contains("#define NS_WHITE_U1 (4.99 / 256.0)"));
        assertTrue(tag.contains("#define NS_WHITE_V0 (9.01 / 256.0)"));
        assertTrue(tag.contains("#define NS_WHITE_V1 (16.99 / 256.0)"));
        assertTrue(tag.contains("vec2 lx = dFdx(local);") && tag.indexOf("vec2 lx = dFdx(local);") < tag.indexOf("return color;"));
        String disp = read(assets().resolve("nameshader/shaders/include/ns_display_fragment.glsl"));
        assertTrue(disp.indexOf("dFdx(nsPos)") < disp.indexOf("return color;"));
    }

    @Test
    void coreFilesCarryHooksAndAreOurs() throws IOException {
        Path core = assets().resolve("minecraft/shaders/core");
        String[] names;
        try (Stream<Path> s = Files.list(core)) {
            names = s.map(p -> p.getFileName().toString()).filter(n -> !ShaderHooks.canonicalPaths().contains(ShaderHooks.CORE_DIR + n))
                    .sorted().toArray(String[]::new);
        }
        assertArrayEquals(new String[]{"rendertype_text_background.fsh", "rendertype_text_background.vsh",
                "rendertype_text_background_see_through.fsh", "rendertype_text_background_see_through.vsh",
                "rendertype_text_see_through.fsh"}, names);
        for (String[] h : Commands.PACK_HOOKS) {
            assertTrue(Commands.hasHook(core.resolve(h[0]), h[1]), h[0]);
        }
        assertTrue(Commands.missingIncludes(assets().resolve("nameshader/shaders/include")).isEmpty());
        assertEquals(Commands.PACK_INCLUDES.size(), Commands.missingIncludes(core).size());
        String st = read(core.resolve("rendertype_text_see_through.fsh"));
        assertTrue(st.indexOf("ns_tag(") < st.indexOf("discard"));
        for (String other : List.of("textshader", "tabshader")) {
            Path o = root().getParent().resolve(other + "/pack/assets/minecraft/shaders/core");
            for (String n : names) {
                assertFalse(Files.exists(o.resolve(n)), other + " ships " + n);
            }
            assertFalse(Files.exists(root().getParent().resolve(other + "/pack/assets/nameshader")), other);
        }
    }

    @Test
    void everyMessageKeyUsedInCodeExists() throws IOException {
        String config = read(root().resolve("src/main/resources/config.yml"));
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(new StringReader(config));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Pattern use = Pattern.compile("(?:send|get|raw)\\(\\s*(?:[a-zA-Z]+,\\s*)?\"([a-z0-9-]+)\"\\s*[,)]");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root().resolve("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = use.matcher(read(p));
                while (m.find()) {
                    if (!y.isString("messages." + m.group(1))) {
                        missing.add(p.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
        assertFalse(y.getBoolean("nametags.style-text-displays"));
    }

    @Test
    void noCommentsAndNoLongDashes() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root())) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String rel = root().relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("build/") || rel.startsWith(".gradle/") || rel.startsWith("gradle/")
                        || rel.startsWith("tools/.cache/") || rel.startsWith("gradlew") || rel.contains("__pycache__")
                        || rel.endsWith(".png") || rel.endsWith(".jar") || rel.endsWith(".zip")) {
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
