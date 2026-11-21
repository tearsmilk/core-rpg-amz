package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZonesTest {
    @TempDir
    Path tmp;

    @Test
    void bundledConfigZonesAreValid() throws Exception {
        Path f = tmp.resolve("config.yml");
        Files.copy(Path.of("src/main/resources/config.yml"), f);
        List<String> errors = new ArrayList<>();
        List<Zones.Zone> z = Zones.read(new ModuleConfig(f), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(3, z.size());
        assertEquals(Zones.Kind.NEBULA, z.get(1).kind());
        assertEquals(0xB060FF, z.get(1).color());
    }

    @Test
    void badZonesSkippedAndRoundTrip() {
        ModuleConfig c = new ModuleConfig(tmp.resolve("c.yml"));
        c.set("zones.ok.type", "stars");
        c.set("zones.ok.radius", 10);
        c.set("zones.badtype.type", "comet");
        c.set("zones.badtype.radius", 10);
        c.set("zones.norad.type", "nebula");
        Zones.write(c, new Zones.Zone("neb", Zones.Kind.NEBULA, 12, -34, 500, 0.25, 0x40A0FF));
        c.save();
        c.reload();
        List<String> errors = new ArrayList<>();
        List<Zones.Zone> z = Zones.read(c, errors);
        assertEquals(List.of("ok", "neb"), z.stream().map(Zones.Zone::id).toList());
        assertEquals(2, errors.size(), errors.toString());
        assertEquals(new Zones.Zone("neb", Zones.Kind.NEBULA, 12, -34, 500, 0.25, 0x40A0FF), z.get(1));
    }

    @Test
    void glslShape() {
        assertEquals("// spacesky: области космоса — файл пишет модуль spacesky из config.yml, руками не менять.\n"
            + "#define SS_ZONES 0\n", Zones.glsl(List.of()));
        String g = Zones.glsl(List.of(new Zones.Zone("a", Zones.Kind.MILKY_WAY, 1, 2, 3, 0.5, 0),
            new Zones.Zone("b", Zones.Kind.NEBULA, -1.5, 0, 100, 0.3, 0xFF8000)));
        assertTrue(g.contains("#define SS_ZONES 2"), g);
        assertTrue(g.contains("vec4(1.000, 2.000, 3.000, 0.500),  // a"), g);
        assertTrue(g.contains("vec4(-1.500, 0.000, 100.000, 0.300)   // b"), g);
        assertTrue(g.contains("vec4(1.0, 1.000, 0.502, 0.000)"), g);
    }
}
