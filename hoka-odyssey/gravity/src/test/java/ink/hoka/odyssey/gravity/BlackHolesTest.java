package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlackHolesTest {

    @Test
    void diskFieldsReadWithDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "black-holes:\n  g:\n    a: {x: 1, y: 2, z: 3, disk-tilt: 30, disk-yaw: 90, disk-temp: 1.4}\n"
            + "    b: {x: 0, disk-size: 0, disk-spin: -2, disk-brightness: 99, disk-tilt: 400}\n    c: {x: 5}\n");
        List<BlackHole> l = Settings.from(new ModuleConfig(file)).space().holes("g");
        assertEquals(new HoleDisk(30, 90, 6, 1, 1, 1.4), l.get(0).disk());
        HoleDisk b = l.get(1).disk();
        assertEquals(0, b.size(), "0 — без диска");
        assertEquals(-2, b.spin());
        assertEquals(5, b.brightness(), "подрезано до 5");
        assertEquals(90, b.tilt(), "подрезано до 90");
        assertEquals(HoleDisk.DEFAULT, l.get(2).disk());
    }

    @Test
    void diskFieldsSurviveRewrite(@TempDir Path dir) throws IOException {
        Map<String, List<BlackHole>> holes = new LinkedHashMap<>();
        holes.put("g", List.of(new BlackHole("a", 1, 2, 3, 6, 1500, 4, 12, new HoleDisk(25, -40, 8, -1.5, 2, 0.7)),
            new BlackHole("b", 0, 0, 0, 6, 1500, 4, 12)));
        Path file = dir.resolve("config.yml");
        String text = HoleYaml.replace("maps: []\n", holes);
        assertTrue(text.contains("\"a\": {x: 1, y: 2, z: 3, horizon: 6, reach: 1500, pull: 4, max-speed: 12, disk-tilt: 25, "
            + "disk-yaw: -40, disk-size: 8, disk-spin: -1.5, disk-brightness: 2, disk-temp: 0.7}"), text);
        assertTrue(text.contains("\"b\": {x: 0, y: 0, z: 0, horizon: 6, reach: 1500, pull: 4, max-speed: 12}"),
            "по умолчанию disk-* не пишутся: " + text);
        Files.writeString(file, text);
        assertEquals(holes.get("g"), Settings.from(new ModuleConfig(file)).space().holes("g"));
    }

    @Test
    void serviceListsHolesWithMapAndNotifies(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "black-holes:\n  g1:\n    a: {x: 1, y: 2, z: 3, horizon: 5, disk-yaw: 45}\n"
            + "  \"2\":\n    b: {x: 4}\n");
        Settings s = Settings.from(new ModuleConfig(file));
        List<String> warns = new ArrayList<>();
        BlackHolesService svc = new BlackHolesService(() -> s, warns::add);
        List<BlackHoles.Hole> l = svc.holes();
        assertEquals(new BlackHoles.Hole("g1", "a", 1, 2, 3, 5, 12, 45, 6, 1, 1, 1), l.get(0));
        assertEquals("2", l.get(1).map());

        AtomicInteger calls = new AtomicInteger();
        Runnable off = svc.onChange(calls::incrementAndGet);
        svc.onChange(() -> {
            throw new IllegalStateException("сломан");
        });
        svc.changed();
        assertEquals(1, calls.get());
        assertEquals(1, warns.size(), "сломанный подписчик не мешает остальным");
        off.run();
        svc.changed();
        assertEquals(1, calls.get(), "после отписки не зовётся");
        assertEquals(List.of(), new BlackHolesService(() -> null, warns::add).holes());
    }
}
