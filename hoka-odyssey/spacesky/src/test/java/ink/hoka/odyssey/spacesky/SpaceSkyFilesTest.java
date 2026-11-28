package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SpaceSkyFilesTest {
    private static final Path RES = Path.of("src/main/resources");
    private static final String CORE = "assets/minecraft/shaders/core/";
    private static final String FOG = "assets/minecraft/shaders/include/fog.glsl";

    /** Все файлы пака в режиме — как их собирает syncPack (части + сгенерированные include). */
    private static Set<String> packFiles(boolean everywhere, boolean sun, boolean moon, boolean clouds) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        for (String part : SkyMode.parts(everywhere, sun, moon, clouds)) {
            files.putAll(SpaceSkyModule.collect(RES.resolve(part)));
        }
        files.put(Zones.PATH, new byte[0]);
        files.put(SkyMode.PATH, new byte[0]);
        files.put(Holes.PATH, new byte[0]);
        files.put(Profiles.PATH, new byte[0]);
        return files.keySet();
    }

    @Test
    void skyShaderAlwaysInPack() throws Exception {
        Map<String, byte[]> m = SpaceSkyModule.collect(RES.resolve(SkyMode.ALWAYS));
        assertEquals(List.of(CORE + "position_tex.vsh", CORE + "sky.fsh", CORE + "sky.vsh", CORE + "stars.fsh",
            CORE + "stars.vsh", CORE + "terrain.fsh", FOG, Holes.PATH, SkyMode.PATH, Profiles.PATH, Zones.PATH),
            List.copyOf(m.keySet()));
    }

    @Test
    void partsFollowConfig() throws Exception {
        assertEquals(List.of("pack", "everywhere"), SkyMode.parts(true, false, false, false));
        assertEquals(List.of("pack", "everywhere", "hide/sun", "hide/moon", "hide/clouds"),
            SkyMode.parts(true, true, true, true));
        // режим «только миры профилей»: текстуры общие для всех миров — не трогаются при любых hide-*
        assertEquals(List.of("pack"), SkyMode.parts(false, true, true, true));
        assertEquals(List.of("pack"), SkyMode.parts(false, false, false, false));
        assertEquals(List.of("assets/minecraft/textures/environment/celestial/sun.png"),
            List.copyOf(SpaceSkyModule.collect(RES.resolve("hide/sun")).keySet()));
        assertEquals(8, SpaceSkyModule.collect(RES.resolve("hide/moon")).size());
        assertTrue(SpaceSkyModule.collect(RES.resolve("hide/clouds")).containsKey("assets/minecraft/textures/environment/clouds.png"));
    }

    @Test
    void everywherePackFiles() throws Exception {
        assertEquals(Set.of(CORE + "sky.vsh", CORE + "sky.fsh", CORE + "stars.vsh", CORE + "stars.fsh",
            CORE + "position_color.vsh", CORE + "position_tex.vsh", CORE + "terrain.fsh", FOG, Zones.PATH, SkyMode.PATH,
            Holes.PATH, Profiles.PATH),
            packFiles(true, false, false, false));
        Set<String> all = packFiles(true, true, true, true);
        assertEquals(12 + 1 + 8 + 1, all.size(), all.toString());
        assertTrue(all.contains("assets/minecraft/textures/environment/clouds.png"));
    }

    @Test
    void markedPackFiles() throws Exception {
        // проход звёзд (частицы параллакса; без метки — ванильная звезда) — в обоих режимах
        Set<String> expected = Set.of(CORE + "sky.vsh", CORE + "sky.fsh", CORE + "stars.vsh", CORE + "stars.fsh",
            CORE + "position_tex.vsh", CORE + "terrain.fsh", FOG, Zones.PATH, SkyMode.PATH, Holes.PATH, Profiles.PATH);
        assertEquals(expected, packFiles(false, true, true, true));
        assertEquals(expected, packFiles(false, false, false, false));
    }

    @Test
    void modeGlslGenerated() throws Exception {
        String on = SkyMode.glsl(true, true, true);
        // запасной файл в jar совпадает с тем, что модуль пишет по умолчанию (everywhere: true)
        assertEquals(Files.readString(RES.resolve(SkyMode.ALWAYS).resolve(SkyMode.PATH), StandardCharsets.UTF_8), on);
        assertTrue(on.contains("#define SPACESKY_EVERYWHERE 1\n"), on);
        assertTrue(on.contains("#ifndef SPACESKY_MODE_GLSL\n"), on);
        assertFalse(on.contains("SPACESKY_MARK"), "метки — в profiles.glsl");
        String off = SkyMode.glsl(false, true, false);
        assertTrue(off.contains("#define SPACESKY_EVERYWHERE 0\n"), off);
        assertTrue(off.contains("#define SPACESKY_HIDE_SUN 1\n"), off);
        assertTrue(off.contains("#define SPACESKY_HIDE_MOON 0\n"), off);
    }

    @Test
    void shadersUseModeInclude() throws Exception {
        for (String f : List.of("pack/" + CORE + "sky.vsh", "pack/" + CORE + "sky.fsh", "pack/" + CORE + "position_tex.vsh",
            "pack/" + FOG)) {
            String s = Files.readString(RES.resolve(f), StandardCharsets.UTF_8);
            assertTrue(s.contains("#moj_import <spacesky:mode.glsl>"), f);
            assertTrue(s.contains("#moj_import <spacesky:profiles.glsl>"), f);
            assertFalse(s.contains("SPACESKY_MARK"), f + ": старая метка");
        }
        assertFalse(Files.readString(RES.resolve("pack/" + FOG), StandardCharsets.UTF_8).contains("#define SPACESKY_BASE"));
    }

    @Test
    void fogKeepsClientDarkening() throws Exception {
        String fog = Files.readString(RES.resolve("pack/" + FOG), StandardCharsets.UTF_8);
        // туман профиля затемняется, как клиент затемнил метку (слепота, тьма, у дна мира)
        assertTrue(fog.contains("spacesky_mark_level(fogColor)"), fog);
        String p = Profiles.glsl(List.of(Profiles.defaults("space", Profiles.Kind.GALAXY).withIndex(0),
            Profiles.defaults("mars", Profiles.Kind.PLANET).withIndex(1)));
        assertTrue(p.contains("float spacesky_mark_level(vec3 c)"), p);
        // слепота/тьма подменили начало тумана — фазу не считать, дальности эффекта не трогать
        int guard = p.indexOf("if (es < 29999.0 || es > 31001.0) return");
        assertTrue(guard > 0 && guard < p.indexOf("es = 0.0; ee ="), p);
    }

    @Test
    void terrainFadesFromProfileFog() throws Exception {
        String t = Files.readString(RES.resolve("pack/" + CORE + "terrain.fsh"), StandardCharsets.UTF_8);
        assertTrue(t.contains("#moj_import <minecraft:fog.glsl>"), t);
        assertTrue(t.contains("spacesky_fog_color(FogColor, FogEnvironmentalStart, FogEnvironmentalEnd)"), t);
        assertFalse(t.contains("mix(FogColor *"), "чанк не должен проявляться из метки");
    }

    @Test
    void skyShadersAvoidPowOfNegative() throws Exception {
        // pow(x < 0, y) в GLSL не определён: у заката и Млечного пути — квадрат
        for (String f : List.of(CORE + "sky.vsh", CORE + "sky.fsh")) {
            String s = Files.readString(RES.resolve("pack/" + f), StandardCharsets.UTF_8);
            assertFalse(s.contains("pow((sun.y"), f);
            assertFalse(s.contains("pow(dot(d, axis)"), f);
        }
        String fsh = Files.readString(RES.resolve("pack/" + CORE + "sky.fsh"), StandardCharsets.UTF_8);
        int main = fsh.indexOf("void main()");
        assertTrue(fsh.indexOf("fwidth(") > main, "fwidth — только в main (однородное управление)");
    }
}
