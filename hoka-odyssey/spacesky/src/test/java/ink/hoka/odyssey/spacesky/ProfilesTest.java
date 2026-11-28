package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfilesTest {
    private static final Path RES = Path.of("src/main/resources");

    @TempDir
    Path tmp;

    /** Разбор метки — как spacesky_code в profiles.glsl (тот же код на float). */
    static int code(double r, double g, double b) {
        float fr = (float) r;
        float fg = (float) g;
        float fb = (float) b;
        if (fg < 1e-6f || fr > fg * 0.02f) {
            return -1;
        }
        float k = fb / fg * 24.0f;
        float i = (float) Math.floor(k + 0.5f);
        if (Math.abs(k - i) > 0.3f || i > 31.5f) {
            return -1;
        }
        return (int) i;
    }

    static int code(int rgb) {
        return code(((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0);
    }

    @Test
    void marksUniqueAndDecodable() {
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < Profiles.MAX; i++) {
            int sky = Profiles.skyMark(i);
            int water = Profiles.waterMark(i);
            assertTrue(all.add(sky) && all.add(water), "метки не повторяются");
            assertEquals(0, sky >> 16, "красного в метке нет");
            assertEquals(i, code(sky));
            assertEquals(16 + i, code(water));
            assertEquals(i, Profiles.skyIndex(sky));
            assertEquals(-1, Profiles.skyIndex(water));
            // затемнение у дна мира и ночное зрение (умножение на число), затемнение экрана (r*0.7, g и b *0.6)
            for (double k : new double[] {0.004, 0.05, 0.3, 1.0, 2.5, 10.625}) {
                double g = Profiles.MARK_G / 255.0 * k;
                assertEquals(i, code(0, g, (sky & 255) / 255.0 * k), "×" + k);
                assertEquals(16 + i, code(0, g * 0.6, (water & 255) / 255.0 * k * 0.6), "затемнение ×" + k);
            }
        }
        assertEquals(0x001800, SpaceDimension.MARK);
        assertEquals(0x001810, SpaceDimension.WATER_MARK);
    }

    /**
     * Ванильные цвета тумана и неба 1.21.11 (client.jar: dimension_type, worldgen/biome, timeline/day; байткод
     * FogRenderer, погоды, воды, лавы, снега) — их смеси и затемнения меткой не считаются. Полная проверка (1,5 млн
     * сочетаний) — spacesky/tools/marks_check.py; здесь — основа: у всех них красный не меньше 12% зелёного.
     */
    @Test
    void vanillaColorsAreNotMarks() {
        int[] fog = {0xC0D8FF, 0x181318, 0x1A051A, 0x1B4745, 0x330303, 0x330808, 0x685F70, 0x817770};
        int[] sky = {0x6EB1FF, 0x76A8FF, 0x77A8FF, 0x78A7FF, 0x79A6FF, 0x7AA5FF, 0x7BA4FF, 0x7CA3FF, 0x7DA2FF, 0x7DA3FF,
            0x7FA1FF, 0x81A0FF, 0x829FFF, 0x839EFF, 0x859DFF, 0xB9B9B9};
        int[] water = {0x050533, 0x041633, 0x041F33, 0x232317, 0x4D7A60, 0x556980, 0x5DB7EF};
        int[] sunrise = {0xEFA333, 0xF5BA33, 0xFBD433, 0xFFE533, 0xE78733, 0xE47E33, 0xE07233, 0xDD6B33, 0xDA6333,
            0xD75C33, 0xD25133, 0xCC4733, 0xBE3733, 0xBB3533, 0xB73333, 0xB33333, 0xB23333};
        int[] other = {0x991A00, 0x9FBACC, 0xCCCCFF, 0x0F0F16, 0x7A7AFF};
        // туман/небо/закат: смеси (биомы, закат, туман с небом) и сутки/погода (умножение) — в байтах, как у клиента
        List<int[]> air = rgb(fog, sky, sunrise, other);
        // вода, лава, снег: смеси биомов в байтах (сутки воду не трогают)
        List<int[]> liquid = rgb(water, new int[] {0x991A00, 0x9FBACC});
        List<int[]> all = new ArrayList<>();
        for (List<int[]> set : List.of(air, liquid)) {
            for (int[] a : set) {
                for (int[] b : set) {
                    for (double t = 0; t <= 1.0001; t += 0.125) {
                        int[] m = {(int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t),
                            (int) Math.round(a[2] + (b[2] - a[2]) * t)};
                        if (set == liquid) {
                            all.add(m);
                            continue;
                        }
                        for (double k = 1.0; k >= 0.058; k -= 0.0627) {       // ночь: туман × #0f0f16 (0,059…)
                            all.add(new int[] {(int) Math.round(m[0] * k), (int) Math.round(m[1] * k), (int) Math.round(m[2] * k)});
                        }
                    }
                }
            }
        }
        int checked = 0;
        for (int[] c : all) {
            // дальше — числа с плавающей точкой: затемнение у дна мира, ночное зрение, затемнение экрана
            for (double k : new double[] {1.0, 0.01, 3.0}) {
                double r = c[0] / 255.0 * k;
                double g = c[1] / 255.0 * k;
                double b = c[2] / 255.0 * k;
                assertEquals(-1, code(r, g, b), c[0] + " " + c[1] + " " + c[2]);
                assertEquals(-1, code(r * 0.7, g * 0.6, b * 0.6), c[0] + " " + c[1] + " " + c[2] + " затемнение");
                checked++;
            }
        }
        assertTrue(checked > 100_000, "проверено " + checked);
    }

    private static List<int[]> rgb(int[]... sets) {
        List<int[]> out = new ArrayList<>();
        for (int[] set : sets) {
            for (int c : set) {
                out.add(new int[] {(c >> 16) & 255, (c >> 8) & 255, c & 255});
            }
        }
        return out;
    }

    @Test
    void withoutProfilesSectionOnlySpace() {
        List<String> errors = new ArrayList<>();
        List<Profiles.Profile> ps = Profiles.read(new ModuleConfig(tmp.resolve("none.yml")), errors);
        assertTrue(errors.isEmpty());
        assertEquals(1, ps.size());
        Profiles.Profile s = ps.getFirst();
        assertEquals("space", s.id());
        assertEquals("hoka:space", s.key());
        assertEquals(Profiles.Kind.GALAXY, s.kind());
        assertEquals(Profiles.SPACE_BG, s.background(), "космос как до профилей");
        assertEquals(1.0, s.stars());
    }

    @Test
    void bundledExamples() throws Exception {
        Path f = tmp.resolve("config.yml");
        Files.copy(RES.resolve("config.yml"), f);
        List<String> errors = new ArrayList<>();
        List<Profiles.Profile> ps = Profiles.read(new ModuleConfig(f), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(List.of("space", "galaxy1", "andromeda", "mars", "ocean"), ps.stream().map(Profiles.Profile::id).toList());
        Profiles.Profile andromeda = ps.get(2);
        assertFalse(andromeda.milkyWay());
        assertEquals(1.8, andromeda.stars());
        Profiles.Profile mars = ps.get(3);
        assertTrue(mars.planet());
        assertFalse(mars.clouds());
        assertEquals(0x6FA8FF, mars.sunset().rgb());
        assertEquals(1.6, mars.sunSize());
        assertEquals(mars.horizon(), mars.fog(), "туман по умолчанию — цвет горизонта");
        Profiles.Profile ocean = ps.get(4);
        assertTrue(ocean.clouds());
        assertEquals(7, ocean.moonSize());
        assertEquals(0xE8C9A0, ocean.moonColor().rgb());
        double[] md = Profiles.moonDir(ocean);
        assertEquals(1, Math.sqrt(md[0] * md[0] + md[1] * md[1] + md[2] * md[2]), 1e-9);
        assertEquals(Math.sin(Math.toRadians(35)), md[1], 1e-9);
    }

    @Test
    void badProfilesReported() {
        ModuleConfig c = new ModuleConfig(tmp.resolve("bad.yml"));
        c.set("profiles.mars.type", "planet");
        c.set("profiles.mars.zenith", "red");
        c.set("profiles.mars.sun-size", 500);
        c.set("profiles.mars.clouds", "yes");
        c.set("profiles.Bad.type", "galaxy");
        c.set("profiles.comet.type", "comet");
        c.set("profiles.space.type", "planet");
        List<String> errors = new ArrayList<>();
        List<Profiles.Profile> ps = Profiles.read(c, errors);
        assertEquals(List.of("space", "mars"), ps.stream().map(Profiles.Profile::id).toList(), "space — всегда первый");
        assertEquals(Profiles.Kind.GALAXY, ps.getFirst().kind(), "space — только галактика");
        Profiles.Profile mars = ps.get(1);
        assertEquals(Profiles.defaults("mars", Profiles.Kind.PLANET).zenith(), mars.zenith());
        assertEquals(30, mars.sunSize(), "подрезано");
        assertTrue(mars.clouds());
        assertEquals(5, errors.size(), errors.toString());
    }

    @Test
    void parallaxFields() {
        ModuleConfig c = new ModuleConfig(tmp.resolve("par.yml"));
        c.set("profiles.fast.type", "galaxy");
        c.set("profiles.fast.parallax", 2);
        c.set("profiles.fast.near-stars", 5);
        c.set("profiles.fast.dust", 0.5);
        c.set("profiles.fast.nebula-distance", 0);
        c.set("profiles.tiny.type", "galaxy");
        c.set("profiles.tiny.parallax", 0.01);
        c.set("profiles.off.type", "galaxy");
        c.set("profiles.off.parallax", 0);
        c.set("profiles.bad.type", "galaxy");
        c.set("profiles.bad.dust", "много");
        c.set("profiles.mars.type", "planet");
        c.set("profiles.mars.parallax", 3);
        c.set("profiles.mars.dust", 2);
        List<String> errors = new ArrayList<>();
        List<Profiles.Profile> ps = Profiles.read(c, errors);
        assertEquals(List.of("profiles.bad.dust: число"), errors);
        // по умолчанию у галактики — включено: плитки 256 / 24 блока, туманность на 3000 блоков
        Profiles.Profile space = ps.getFirst();
        assertTrue(space.particles());
        assertEquals(256, space.nearTile());
        assertEquals(24, space.dustTile());
        assertEquals(3000, space.nebulaDistance());
        Profiles.Profile fast = Profiles.byId(ps, "fast");
        assertEquals(128, fast.nearTile());
        assertEquals(12, fast.dustTile());
        assertEquals(2, fast.nearStars(), "подрезано до 2");
        assertEquals(0.5, fast.dust());
        assertEquals(0, fast.nebulaDistance());
        assertEquals(1024, Profiles.byId(ps, "tiny").nearTile(), "parallax не меньше 0,25");
        assertFalse(Profiles.byId(ps, "off").particles());
        assertEquals(1, Profiles.byId(ps, "bad").dust());
        Profiles.Profile mars = Profiles.byId(ps, "mars");
        assertFalse(mars.particles(), "у планеты параллакса нет");
        assertEquals(0, mars.parallax());
        // в шейдер: SS_PROF_9 = (доля звёзд, пыль, плитка звёзд, плитка пыли), SS_PROF_10.x — даль туманностей
        List<Profiles.Profile> idx = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            idx.add(ps.get(i).withIndex(i));
        }
        String g = Profiles.glsl(idx);
        assertTrue(g.contains("const vec4 SS_PROF_9[SS_PROFILES] = vec4[SS_PROFILES](\n"
            + "    vec4(1.00000, 1.00000, 256.0, 24.0),\n"
            + "    vec4(2.00000, 0.50000, 128.0, 12.0),\n"), g);
        assertTrue(g.contains("    vec4(0.00000, 0.00000, 0.0, 0.0),\n"), "off: частиц нет");
        assertTrue(g.contains("const vec4 SS_PROF_10[SS_PROFILES] = vec4[SS_PROFILES](\n"
            + "    vec4(3000.00000, 0.0, 0.0, 0.0),\n"
            + "    vec4(0.00000, 0.0, 0.0, 0.0),\n"), g);
    }

    @Test
    void atMostMaxProfiles() {
        ModuleConfig c = new ModuleConfig(tmp.resolve("many.yml"));
        for (int i = 0; i < Profiles.MAX + 3; i++) {
            c.set("profiles.p" + i + ".type", "galaxy");
        }
        List<String> errors = new ArrayList<>();
        List<Profiles.Profile> ps = Profiles.read(c, errors);
        assertEquals(Profiles.MAX, ps.size());
        assertEquals("space", ps.getFirst().id());
        assertFalse(errors.isEmpty());
    }

    @Test
    void profileOfDimension() {
        assertEquals("mars", Profiles.profileOf("hoka:mars"));
        assertEquals("space", Profiles.profileOf("hoka:space"));
        assertNull(Profiles.profileOf("minecraft:overworld"));
        assertNull(Profiles.profileOf("hoka:Bad Name"));
        assertNull(Profiles.profileOf(null));
    }

    private static List<Profiles.Profile> examples() {
        return List.of(Profiles.defaults("space", Profiles.Kind.GALAXY).withIndex(0),
            Profiles.defaults("mars", Profiles.Kind.PLANET).withIndex(1),
            Profiles.defaults("far", Profiles.Kind.GALAXY).withIndex(3));
    }

    @Test
    void glslArraysAndGaps() {
        String g = Profiles.glsl(examples());
        assertTrue(g.contains("#define SS_PROFILES 4\n"), "номер 2 пустой — заполнен космосом: " + g);
        assertTrue(g.contains("#define SS_PLANETS 1\n"), g);
        assertTrue(g.contains("#define SS_STARS_PLAIN 1\n"), g);
        assertTrue(g.contains("// профили: 0=space(galaxy) 1=mars(planet) 2=_2(galaxy) 3=far(galaxy)\n"), g);
        for (int k = 0; k <= 10; k++) {
            assertTrue(g.contains("const vec4 SS_PROF_" + k + "[SS_PROFILES] = vec4[SS_PROFILES](\n"), "массив " + k);
        }
        // вид — SS_PROF_0.w: планета 1
        assertTrue(g.contains("    vec4(0.66275, 0.78431, 0.94118, 1.00000),\n"), g);
        // туман: развёрнутая цепочка, у планеты — по времени суток и своя дымка
        assertTrue(g.contains("    else if (p == 1) {  // mars\n"), g);
        assertTrue(g.contains("es = 0.0; ee = 1024.00000;"), g);
        assertTrue(g.contains("int spacesky_code(vec3 c)"));
        assertTrue(g.startsWith("// spacesky: профили неба"));
        assertTrue(g.trim().endsWith("#endif"));
        // свои звёзды у галактики — без констант
        List<Profiles.Profile> custom = new ArrayList<>(examples());
        Profiles.Profile far = custom.remove(2);
        custom.add(new Profiles.Profile(far.id(), far.index(), far.kind(), far.background(), far.backgroundTop(),
            far.nebula(), far.milkyWay(), 2.0, far.starBrightness(), far.starTint(), far.zenith(), far.horizon(),
            far.zenithNight(), far.horizonNight(), far.sunset(), far.sun(), far.sunSize(), far.clouds(), far.cloudColor(),
            far.fog(), far.fogNight(), far.haze(), far.moonSize(), far.moonColor(), far.moonYaw(), far.moonPitch(),
            far.waterFog(), far.parallax(), far.nearStars(), far.dust(), far.nebulaDistance()));
        assertTrue(Profiles.glsl(custom).contains("#define SS_STARS_PLAIN 0\n"));
    }

    @Test
    void fallbackInJarIsSpaceOnly() throws Exception {
        String f = Files.readString(RES.resolve(SkyMode.ALWAYS).resolve(Profiles.PATH), StandardCharsets.UTF_8);
        assertEquals(Profiles.glsl(List.of(Profiles.defaults("space", Profiles.Kind.GALAXY).withIndex(0))), f);
        assertTrue(f.contains("#define SS_PROFILES 1\n"));
        assertTrue(f.contains("#define SS_PLANETS 0\n"));
    }

    @Test
    void phaseEncodingMatchesShader() {
        // таймлайн: fog_start = 30000 + 1000 * фаза; шейдер: fract((start - 30000) / 1000)
        for (double ph = 0; ph < 1; ph += 0.0625) {
            float start = Profiles.PHASE_BASE + Profiles.PHASE_SPAN * (float) ph;
            double back = ((start - 30000.0) / 1000.0) % 1.0;
            assertEquals(ph, back, 1e-4);
            float water = Profiles.WATER_START - (float) ph;
            assertEquals(ph, (-(water + 8.0)) % 1.0, 1e-5);
        }
    }
}
