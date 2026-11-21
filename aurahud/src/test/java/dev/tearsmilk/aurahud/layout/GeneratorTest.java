package dev.tearsmilk.aurahud.layout;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorTest {

    private static Path root() {
        return Path.of(System.getProperty("aurahud.root", "."));
    }

    private static boolean write() {
        return "write".equals(System.getProperty("aurahud.golden", ""));
    }

    private static void golden(Path file, String text) throws IOException {
        if (write()) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        assertTrue(Files.isRegularFile(file), file + " missing, run gradle test -Daurahud.golden=write");
        assertEquals(Files.readString(file, StandardCharsets.UTF_8), text, file.toString());
    }

    @Test
    void shippedConfigIsTheVanillaOne() throws IOException {
        String text = GlslConfig.generate(LayoutEngine.place(HudSettings.vanilla()));
        assertTrue(text.contains("#define AH_ACTIVE 0\n"));
        golden(root().resolve("pack/assets/" + GlslConfig.PATH), text);
    }

    @Test
    void presetConfigsMatchTheGoldenFilesCompiledByGlslCheck() throws IOException {
        for (String name : Presets.BUILT_IN) {
            YamlConfiguration y = Presets.builtIn(name);
            String text = GlslConfig.generate(LayoutEngine.place(HudSettings.read(y.getConfigurationSection("hud"))));
            assertEquals(!name.equals("vanilla"), text.contains("#define AH_ACTIVE 1\n"), name);
            golden(root().resolve("tools/configs/" + name + ".glsl"), text);
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString("hud:\n  hotbar:\n    layout: grid\n    per-line: 2\n    slots:\n      '5':\n        hidden: true\n    attack-indicator: false\n"
                    + "  offhand:\n    enabled: false\n  hearts:\n    enabled: false\n  armor:\n    layout: column\n  food:\n    reverse: true\n"
                    + "  air:\n    layout: grid\n  mount:\n    enabled: false\n  xp:\n    rotate: 90\n    level: false\n  item-name:\n    enabled: false\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        golden(root().resolve("tools/configs/stress.glsl"), GlslConfig.generate(LayoutEngine.place(HudSettings.read(y.getConfigurationSection("hud"))),
                NameGlyphs.select(new LinkedHashSet<>(List.of(new Placement.Point(4, 5), new Placement.Point(-8, 20), new Placement.Point(-1, 9))))));
    }

    @Test
    void everyConstantTheShadersUseIsGenerated() throws IOException {
        String text = GlslConfig.generate(LayoutEngine.place(HudSettings.vanilla()));
        Pattern use = Pattern.compile("\\bAH_[A-Z_]+\\b");
        List<String> missing = new ArrayList<>();
        Path dir = root().resolve("pack/assets/aurahud/shaders/include");
        String common = Files.readString(dir.resolve("ah_common.glsl"), StandardCharsets.UTF_8);
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Matcher m = use.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    String n = m.group();
                    if (!text.contains(" " + n + " ") && !text.contains(" " + n + "[") && !common.contains("#define " + n + " ")) {
                        missing.add(p.getFileName() + ": " + n);
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void outputIsPlainAsciiWithoutComments() {
        String text = GlslConfig.generate(LayoutEngine.place(HudSettings.vanilla()));
        assertFalse(text.contains("/" + "/"));
        for (char c : text.toCharArray()) {
            assertTrue(c < 128, "non ascii");
        }
        assertTrue(text.contains("const vec2 AH_HEARTS_POS[40] = vec2[40]("));
        assertTrue(text.contains("const vec2 AH_HOTBAR_BASE = vec2(-91.0, -22.0);"));
        assertTrue(text.contains("const vec2 AH_ARMOR_ROW_STEP = vec2(0.0, -10.0);"));
    }
}
