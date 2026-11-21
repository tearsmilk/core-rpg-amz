package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlListTest {
    @Test
    void replacesBlockListKeepingComments() {
        String in = "# шапка\nmaps:\n  - a\n  - b\n\n# дальше\nvertical:\n  thrust: 1\n";
        String out = YamlList.replace(in, "maps", List.of("a", "galaxy1"));
        assertEquals("# шапка\nmaps:\n  - a\n  - galaxy1\n\n# дальше\nvertical:\n  thrust: 1\n", out);
    }

    @Test
    void emptyAndInlineAndMissing() {
        assertEquals("maps: []\nx: 1\n", YamlList.replace("maps:\n  - a\nx: 1\n", "maps", List.of()));
        assertEquals("maps:\n  - c\nx: 1\n", YamlList.replace("maps: [a, b]\nx: 1\n", "maps", List.of("c")));
        assertEquals("x: 1\nmaps:\n  - c\n", YamlList.replace("x: 1\n", "maps", List.of("c")));
        assertEquals("maps:\n  - \"a b\"\n", YamlList.replace("", "maps", List.of("a b")));
    }

    @Test
    void bundledConfigRoundTrip(@TempDir Path dir) throws IOException {
        String text;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path file = dir.resolve("config.yml");
        Files.writeString(file, text);
        Settings s = Settings.from(new ModuleConfig(file));
        assertEquals(List.of("galaxy1"), List.copyOf(s.maps()), "по умолчанию вакуум на galaxy1");
        Settings noHint = new Settings(s.maps(), s.vThrust(), s.vMax(), s.vKeep(), s.hThrust(), s.hMax(), s.hKeep(),
            s.fallDamage(), s.groundJump(), "");
        assertEquals(MotionTest.S, noHint, "значения по умолчанию в коде и в config.yml совпадают");
        assertTrue(s.enterHint().startsWith("Невесомость"));

        Files.writeString(file, YamlList.replace(text, "maps", List.of("galaxy1", "moon")));
        ModuleConfig c = new ModuleConfig(file);
        assertEquals(List.of("galaxy1", "moon"), c.getStringList("maps"));
        assertEquals(8.0, c.getDouble("vertical.thrust", 0), "остальное не тронуто");
        assertTrue(Files.readString(file).contains("# Вверх/вниз"), "комментарии на месте");
    }
}
