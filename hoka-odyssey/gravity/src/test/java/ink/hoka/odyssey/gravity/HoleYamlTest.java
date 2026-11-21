package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HoleYamlTest {
    private static BlackHole hole(String name, double x) {
        return new BlackHole(name, x, 65, -10, 6, 1500, 4, 12);
    }

    @Test
    void numericAndBooleanNamesSurviveRoundTrip(@TempDir Path dir) throws IOException {
        Map<String, List<BlackHole>> holes = new LinkedHashMap<>();
        holes.put("2", List.of(hole("1", 1), hole("on", 2), hole("no", 3), hole("abyss", 4)));
        holes.put("galaxy1", List.of(hole("true", 5)));
        Path file = dir.resolve("config.yml");
        Files.writeString(file, HoleYaml.replace("# шапка\nmaps:\n  - \"2\"\n\nx: 1\n", holes));

        Settings s = Settings.from(new ModuleConfig(file));
        assertEquals(List.of("1", "on", "no", "abyss"), s.space().holes("2").stream().map(BlackHole::name).toList());
        assertEquals(2, s.space().holes("2").get(1).x());
        assertEquals("true", s.space().holes("galaxy1").get(0).name());
        assertTrue(Files.readString(file).startsWith("# шапка\n"), "комментарии на месте");
    }

    @Test
    void unquotedNumericKeysWrittenByHandAreRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "black-holes:\n  2:\n    1: {x: 7, y: 1, z: 2}\n    on: {x: 8}\n");
        Settings s = Settings.from(new ModuleConfig(file));
        List<BlackHole> l = s.space().holes("2");
        assertEquals(List.of("1", "true"), l.stream().map(BlackHole::name).toList());
        assertEquals(7, l.get(0).x());
        assertEquals(6, l.get(1).horizon(), "пропущенное поле — из hole-defaults");
    }
}
