package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(5, z.size());
        assertEquals(Zones.Kind.NEBULA, z.get(1).kind());
        assertEquals(0xB060FF, z.get(1).color());
        assertNull(z.get(1).profile(), "без profile — во всех галактиках");
        assertEquals("andromeda", z.get(3).profile());
        assertEquals("galaxy1", z.get(4).profile(), "у galaxy1 своя туманность — иначе он не отличался бы от space");
        assertEquals(-1, z.get(4).color(), "без color — цвет nebula профиля");
        // все профили областей из примера есть в профилях примера
        List<Profiles.Profile> ps = Profiles.read(new ModuleConfig(f), errors);
        for (Zones.Zone zone : z) {
            assertTrue(zone.profile() == null || Profiles.byId(ps, zone.profile()) != null, zone.id());
        }
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
        assertEquals(-1, z.get(0).color(), "без color — цвет туманностей профиля");
    }

    @Test
    void profileBindingRoundTripAndGlsl() {
        ModuleConfig c = new ModuleConfig(tmp.resolve("p.yml"));
        Zones.write(c, new Zones.Zone("glow", Zones.Kind.NEBULA, 0, 0, 100, 0.3, -1, "andromeda"));
        Zones.write(c, new Zones.Zone("mw", Zones.Kind.MILKY_WAY, 0, 0, 100, 0.3, -1, null));
        Zones.write(c, new Zones.Zone("lost", Zones.Kind.STARS, 0, 0, 100, 0.3, -1, "nowhere"));
        c.set("zones.bad.type", "stars");
        c.set("zones.bad.radius", 5);
        c.set("zones.bad.profile", "Плохо");
        c.save();
        c.reload();
        assertNull(c.get("zones.glow.color"), "цвет профиля не пишется");
        List<String> errors = new ArrayList<>();
        List<Zones.Zone> z = Zones.read(c, errors);
        assertEquals(List.of("glow", "mw", "lost"), z.stream().map(Zones.Zone::id).toList());
        assertEquals(1, errors.size(), errors.toString());
        assertEquals("andromeda", z.get(0).profile());
        List<String> glslErrors = new ArrayList<>();
        String g = Zones.glsl(z, java.util.Map.of("space", 0, "andromeda", 2), glslErrors);
        assertTrue(g.contains("#define SS_ZONES 2\n"), g);
        // вид + 4 * (профиль + 1): туманность (1) профиля 2 → 13; Млечный путь без профиля → 0; цвет профиля — -1
        assertTrue(g.contains("vec4(13.0, -1.0, -1.0, -1.0),"), g);
        assertTrue(g.contains("vec4(0.0, -1.0, -1.0, -1.0)\n"), g);
        assertTrue(g.contains("// glow (andromeda)"), g);
        assertEquals(1, glslErrors.size(), "область профиля, которого нет, пропущена: " + glslErrors);
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
        // целый центр — для параллакса туманности (вычитание в int)
        assertTrue(g.contains("const ivec2 SS_ZONE_IPOS[SS_ZONES] = ivec2[SS_ZONES](\n    ivec2(1, 2),\n    ivec2(-1, 0)\n);"), g);
    }
}
