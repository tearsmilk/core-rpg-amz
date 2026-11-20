package dev.tearsmilk.tabshader;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
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
    private static final List<String> COLOURS = List.of("pink", "blue", "red", "green", "yellow", "purple", "white");
    private static final List<Integer> NOTCHES = List.of(6, 10, 12, 20);

    private static Path root() {
        return Path.of(System.getProperty("tabshader.root", "."));
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

    private static BufferedImage image(Path p) {
        try {
            return ImageIO.read(p.toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void markerMatchesShader() {
        String vertex = read(assets().resolve("tabshader/shaders/include/th_text_vertex.glsl"));
        assertTrue(vertex.contains("#define TH_MARK_R " + Backing.MARK_R));
        Matcher m = Pattern.compile("#define TH_MARK_TEXEL ivec4\\((\\d+), (\\d+), (\\d+), (\\d+)\\)").matcher(vertex);
        assertTrue(m.find());
        BufferedImage marker = image(assets().resolve("tabshader/textures/font/marker.png"));
        assertEquals(8, marker.getWidth());
        assertEquals(8, marker.getHeight());
        int argb = marker.getRGB(3, 4);
        assertEquals(Integer.parseInt(m.group(1)), (argb >> 16) & 255);
        assertEquals(Integer.parseInt(m.group(2)), (argb >> 8) & 255);
        assertEquals(Integer.parseInt(m.group(3)), argb & 255);
        assertEquals(Integer.parseInt(m.group(4)), (argb >>> 24) & 255);
        for (Backing.Kind k : Backing.Kind.values()) {
            if (k != Backing.Kind.ACTIONBAR) {
                assertTrue(vertex.contains("if (kind == " + k.id() + ")"), k.name());
            }
        }
        String font = read(assets().resolve("tabshader/font/hud.json"));
        assertTrue(font.contains("tabshader:font/marker.png"));
        assertTrue(font.contains("\\ue000") || font.contains("\uE000"));
        assertTrue(font.contains("\"\\ue001\": -9") || font.contains("\"\uE001\": -9"));
        assertEquals("tabshader", Backing.FONT.namespace());
        assertEquals("hud", Backing.FONT.value());
    }

    @Test
    void bossBarSprites() {
        Path dir = assets().resolve("minecraft/textures/gui/sprites/boss_bar");
        List<String[]> want = new ArrayList<>();
        for (int i = 0; i < COLOURS.size(); i++) {
            if (COLOURS.get(i).equals("yellow")) {
                assertFalse(Files.exists(dir.resolve("yellow_background.png")));
                assertFalse(Files.exists(dir.resolve("yellow_progress.png")));
                continue;
            }
            want.add(new String[]{COLOURS.get(i) + "_background.png", "250", String.valueOf(i)});
            want.add(new String[]{COLOURS.get(i) + "_progress.png", "251", String.valueOf(i)});
        }
        for (int i = 0; i < NOTCHES.size(); i++) {
            int n = NOTCHES.get(i);
            want.add(new String[]{"notched_" + n + "_background.png", "252", String.valueOf(i)});
            want.add(new String[]{"notched_" + n + "_progress.png", "253", String.valueOf(i)});
        }
        for (String[] w : want) {
            BufferedImage img = image(dir.resolve(w[0]));
            assertEquals(182, img.getWidth(), w[0]);
            assertEquals(5, img.getHeight(), w[0]);
            int kind = Integer.parseInt(w[1]);
            int variant = Integer.parseInt(w[2]);
            int coded = 0;
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 182; x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 255;
                    if (a == 0) {
                        continue;
                    }
                    coded++;
                    int v = (((argb >> 16) & 1) << 2) | (((argb >> 8) & 1) << 1) | (argb & 1);
                    assertArrayEquals(new int[]{kind, variant}, new int[]{a, v}, w[0] + " " + x + "," + y);
                }
            }
            assertTrue(coded >= (kind >= 252 ? 9 : 500), w[0]);
            if (kind == 251) {
                int mid = img.getRGB(90, 2);
                assertTrue(((mid >> 16) & 255) > 150, w[0]);
            }
        }
        String frag = read(assets().resolve("tabshader/shaders/include/th_sprite_fragment.glsl"));
        assertTrue(frag.contains("kind < 250 || kind > 253"));
    }

    @Test
    void pingSpritesCarryKind() {
        Path dir = assets().resolve("minecraft/textures/gui/sprites/icon");
        for (String name : List.of("ping_1", "ping_2", "ping_3", "ping_4", "ping_5", "ping_unknown")) {
            BufferedImage img = image(dir.resolve(name + ".png"));
            assertEquals(10, img.getWidth(), name);
            assertEquals(8, img.getHeight(), name);
            int coded = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 10; x++) {
                    int a = (img.getRGB(x, y) >>> 24) & 255;
                    assertTrue(a == 0 || a == 254, name + " " + x + "," + y + " a=" + a);
                    coded += a == 254 ? 1 : 0;
                }
            }
            assertTrue(coded >= 10, name);
        }
        String frag = read(assets().resolve("tabshader/shaders/include/th_sprite_fragment.glsl"));
        assertTrue(frag.contains("#define TH_PING_KIND 254"));
        assertTrue(frag.contains("abs(rows - 8.0) > 0.05 || abs(x_end - 10.0) > 0.05"));
        assertTrue(frag.contains("if (thSprNoPing > 0.5) {"));
        String vert = read(assets().resolve("tabshader/shaders/include/th_sprite_vertex.glsl"));
        assertTrue(vert.contains("thSprNoPing = th_tab_noping(pos) ? 1.0 : 0.0;"));
        String guiVert = read(assets().resolve("tabshader/shaders/include/th_gui_vertex.glsl"));
        assertTrue(guiVert.contains("thGuiA = vec4(pos.x, pos.y - origin, origin, 1.0);"));
        assertTrue(guiVert.contains("thGuiC = vec4(pos.x, pos.y - origin, origin, 1.0);"));
        String guiFrag = read(assets().resolve("tabshader/shaders/include/th_gui_fragment.glsl"));
        assertTrue(guiFrag.contains("vec4 rect = vec4(th_gui_corner(thGuiA), th_gui_corner(thGuiC));"));
    }

    @Test
    void hookFilesCallOurShaders() {
        Path inc = assets().resolve("tabshader/shaders/include");
        assertTrue(read(inc.resolve("th_hook_gui_vertex.glsl")).contains("th_gui_vertex(pos, color, vertexId);"));
        assertTrue(read(inc.resolve("th_hook_gui_fragment.glsl")).contains("return th_gui_fragment(color);"));
        assertTrue(read(inc.resolve("th_hook_sprite_vertex.glsl")).contains("th_sprite_vertex(pos, uv, vertexId);"));
        assertTrue(read(inc.resolve("th_hook_sprite_fragment.glsl")).contains("return th_sprite_fragment(color, tex, uv);"));
        String tv = read(inc.resolve("th_hook_text_vertex.glsl"));
        assertTrue(tv.contains("pos = th_layout_shift(pos);") && tv.contains("th_text_vertex(pos, color, uv, vertexId, outColor);"));
        assertTrue(read(inc.resolve("th_hook_text_fragment.glsl")).contains("return th_text_fragment(color, vertexColor);"));
        Path core = assets().resolve("minecraft/shaders/core");
        String text = read(core.resolve("rendertype_text.vsh"));
        assertTrue(text.indexOf("th_hook_text_position(pos, color);") < text.indexOf("vec4(pos, 1.0)"));
        assertTrue(text.indexOf("vec4(pos, 1.0)") < text.indexOf("ts_hook_text_vertex("));
        assertTrue(text.indexOf("ts_hook_text_vertex(") < text.indexOf("th_hook_text_vertex("));
        String[] names;
        try (Stream<Path> s = Files.list(core)) {
            names = s.map(p -> p.getFileName().toString()).sorted().toArray(String[]::new);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertArrayEquals(new String[]{"gui.fsh", "gui.vsh", "position_tex_color.fsh", "position_tex_color.vsh",
                "rendertype_text.fsh", "rendertype_text.vsh"}, names);
        assertFalse(Files.exists(assets().resolve("textshader")));
        assertFalse(read(inc.resolve("th_text_vertex.glsl")).contains("uniform sampler2D Sampler0;"));
    }

    @Test
    void everyMessageKeyUsedInCodeExists() throws IOException {
        String config = read(root().resolve("src/main/resources/config.yml"));
        Pattern use = Pattern.compile("(?:send|get|raw)\\(\\s*(?:[a-zA-Z]+,\\s*)?\"([a-z0-9-]+)\"\\s*[,)]");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root().resolve("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = use.matcher(read(p));
                while (m.find()) {
                    if (!config.contains("\n  " + m.group(1) + ":")) {
                        missing.add(p.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        for (Part part : Part.values()) {
            if (!config.contains("\n  part-" + part.key() + ":")) {
                missing.add("part-" + part.key());
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
                if (rel.startsWith("build/") || rel.startsWith(".gradle/") || rel.startsWith("gradle/")
                        || rel.startsWith("preview/") || rel.startsWith("gradlew") || rel.contains("__pycache__")
                        || rel.endsWith(".png") || rel.endsWith(".gif") || rel.endsWith(".jar")) {
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
