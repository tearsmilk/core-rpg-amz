package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HolesTest {
    private static final Path RES = Path.of("src/main/resources");

    private static Holes.Hole abyss() {
        return Holes.of("galaxy1", "abyss", 0.5, 65, -600, 6, 12, 0, 6, 1, 1, 1);
    }

    @Test
    void noHolesDefinesZero() {
        String g = Holes.glsl(List.of());
        assertTrue(g.contains("#define SS_HOLES 0\n"), g);
        assertFalse(g.contains("const vec4"), g);
    }

    @Test
    void fallbackInJarHasNoHoles() throws Exception {
        String f = Files.readString(RES.resolve(SkyMode.ALWAYS).resolve(Holes.PATH), StandardCharsets.UTF_8);
        assertTrue(f.contains("#define SS_HOLES 0\n"), f);
    }

    @Test
    void oneHoleGlsl() {
        Holes.Hole h = abyss();
        String g = Holes.glsl(List.of(h));
        assertTrue(g.contains("#define SS_HOLES 1\n"), g);
        assertTrue(g.contains("const vec4 SS_HOLE_POS[SS_HOLES] = vec4[SS_HOLES](\n    vec4(0.5000, 65.0000, -600.0000, 6.0000)"
            + "   // galaxy1:abyss\n);\n"), g);
        // наклон 12° к +x: нормаль (sin 12°, cos 12°, 0) — как в прототипе
        assertTrue(g.contains("const vec4 SS_HOLE_DISK[SS_HOLES] = vec4[SS_HOLES](\n    vec4(0.2079, 0.9781, 0.0000, 6.0000)\n);\n"), g);
        assertTrue(g.contains("const vec4 SS_HOLE_LOOK[SS_HOLES] = vec4[SS_HOLES](\n    vec4(1.0000, 1.0000, 1.0000, "
            + String.format(java.util.Locale.ROOT, "%.4f", h.seed()) + ")\n);\n"), g);
    }

    @Test
    void normalFromTiltAndYaw() {
        Holes.Hole h = Holes.of("m", "a", 0, 0, 0, 1, 90, 90, 6, 1, 1, 1);
        assertEquals(0, h.nx(), 1e-9);
        assertEquals(0, h.ny(), 1e-9);
        assertEquals(1, h.nz(), 1e-9, "наклон 90° в сторону +z");
        Holes.Hole flat = Holes.of("m", "a", 0, 0, 0, 1, 0, 45, 6, 1, 1, 1);
        assertEquals(1, flat.ny(), 1e-9);
        Holes.Hole tilted = Holes.of("m", "a", 0, 0, 0, 1, 30, 200, 6, 1, 1, 1);
        assertEquals(1, Math.sqrt(tilted.nx() * tilted.nx() + tilted.ny() * tilted.ny() + tilted.nz() * tilted.nz()), 1e-9);
    }

    @Test
    void valuesClampedAndBadHolesSkipped() {
        Holes.Hole h = Holes.of("m", "a", 0, 0, 0, 2, 500, Double.NaN, 100, -99, 50, 0);
        assertEquals(0, h.ny(), 1e-9, "наклон 500° подрезан до 90°");
        assertEquals(1, h.nx(), 1e-9, "yaw NaN — 0 (к +x)");
        assertEquals(12, h.outer());
        assertEquals(-5, h.spin());
        assertEquals(5, h.bright());
        assertEquals(0.2, h.temp());
        assertEquals(0, Holes.of("m", "a", 0, 0, 0, 2, 12, 0, 0, 1, 1, 1).outer(), "disk-size 0 — без диска");
        assertEquals(1.5, Holes.of("m", "a", 0, 0, 0, 2, 12, 0, 0.3, 1, 1, 1).outer());
        assertNull(Holes.of("m", "a", 0, 0, 0, 0, 12, 0, 6, 1, 1, 1), "горизонт 0");
        assertNull(Holes.of("m", "a", Double.NaN, 0, 0, 3, 12, 0, 6, 1, 1, 1));
    }

    @Test
    void seedFromNameStableAndSmall() {
        assertEquals(Holes.seed("abyss"), Holes.seed("abyss"));
        assertNotEquals(Holes.seed("abyss"), Holes.seed("abyss2"));
        for (String n : List.of("", "a", "abyss", "очень-длинное_имя-1234567890")) {
            double s = Holes.seed(n);
            assertTrue(s >= 0 && s < 64, n + " → " + s);
        }
    }

    @Test
    void atMostMaxHoles() {
        List<Holes.Hole> many = new ArrayList<>();
        for (int i = 0; i < Holes.MAX + 5; i++) {
            many.add(Holes.of("m", "h" + i, i * 100, 0, 0, 4, 12, 0, 6, 1, 1, 1));
        }
        String g = Holes.glsl(many);
        assertTrue(g.contains("#define SS_HOLES " + Holes.MAX + "\n"), g);
        assertTrue(g.contains("// m:h15\n"));
        assertFalse(g.contains("m:h16"));
    }

    @Test
    void commentNamesSanitized() {
        String g = Holes.glsl(List.of(Holes.of("map\nx", "a*/b", 0, 0, 0, 1, 0, 0, 6, 1, 1, 1)));
        assertTrue(g.contains("// map_x:a__b\n"), g);
    }

    @Test
    void skyShaderUsesHoles() throws Exception {
        String s = Files.readString(RES.resolve("pack/assets/minecraft/shaders/core/sky.fsh"), StandardCharsets.UTF_8);
        assertTrue(s.contains("#moj_import <spacesky:holes.glsl>"));
        assertTrue(s.contains("vec3 ss_holes("));
        // по одному шагу отбора на каждую возможную дыру
        for (int i = 0; i < Holes.MAX; i++) {
            assertTrue(s.contains("SSBH_CULL(" + i + ")"), "шаг " + i);
        }
        assertFalse(s.contains("SSBH_CULL(" + Holes.MAX + ")"));
    }
}
