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

    /** Все файлы пака в режиме — как их собирает syncPack (части + сгенерированные include). */
    private static Set<String> packFiles(boolean everywhere, boolean sun, boolean moon, boolean clouds) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        for (String part : SkyMode.parts(everywhere, sun, moon, clouds)) {
            files.putAll(SpaceSkyModule.collect(RES.resolve(part)));
        }
        files.put(Zones.PATH, new byte[0]);
        files.put(SkyMode.PATH, new byte[0]);
        return files.keySet();
    }

    @Test
    void skyShaderAlwaysInPack() throws Exception {
        Map<String, byte[]> m = SpaceSkyModule.collect(RES.resolve(SkyMode.ALWAYS));
        assertEquals(List.of(CORE + "sky.fsh", CORE + "sky.vsh", SkyMode.PATH, Zones.PATH), List.copyOf(m.keySet()));
    }

    @Test
    void partsFollowConfig() throws Exception {
        assertEquals(List.of("pack", "everywhere"), SkyMode.parts(true, false, false, false));
        assertEquals(List.of("pack", "everywhere", "hide/sun", "hide/moon", "hide/clouds"),
            SkyMode.parts(true, true, true, true));
        // режим «только hoka:space»: текстуры общие для всех миров — не трогаются при любых hide-*
        assertEquals(List.of("pack", "marked"), SkyMode.parts(false, true, true, true));
        assertEquals(List.of("pack", "marked"), SkyMode.parts(false, false, false, false));
        assertEquals(List.of("assets/minecraft/textures/environment/celestial/sun.png"),
            List.copyOf(SpaceSkyModule.collect(RES.resolve("hide/sun")).keySet()));
        assertEquals(8, SpaceSkyModule.collect(RES.resolve("hide/moon")).size());
        assertTrue(SpaceSkyModule.collect(RES.resolve("hide/clouds")).containsKey("assets/minecraft/textures/environment/clouds.png"));
    }

    @Test
    void everywherePackFiles() throws Exception {
        assertEquals(Set.of(CORE + "sky.vsh", CORE + "sky.fsh", CORE + "stars.fsh", CORE + "position_color.vsh",
            "assets/minecraft/shaders/include/fog.glsl", Zones.PATH, SkyMode.PATH), packFiles(true, false, false, false));
        Set<String> all = packFiles(true, true, true, true);
        assertEquals(7 + 1 + 8 + 1, all.size(), all.toString());
        assertTrue(all.contains("assets/minecraft/textures/environment/clouds.png"));
        assertFalse(all.contains(CORE + "position_tex.vsh"));
    }

    @Test
    void markedPackFiles() throws Exception {
        Set<String> expected = Set.of(CORE + "sky.vsh", CORE + "sky.fsh", CORE + "position_tex.vsh", Zones.PATH,
            SkyMode.PATH);
        assertEquals(expected, packFiles(false, true, true, true));
        assertEquals(expected, packFiles(false, false, false, false));
    }

    @Test
    void modeGlslGenerated() throws Exception {
        String on = SkyMode.glsl(true, true, true);
        // запасной файл в jar совпадает с тем, что модуль пишет по умолчанию (everywhere: true)
        assertEquals(Files.readString(RES.resolve(SkyMode.ALWAYS).resolve(SkyMode.PATH), StandardCharsets.UTF_8), on);
        assertTrue(on.contains("#define SPACESKY_EVERYWHERE 1\n"), on);
        assertTrue(on.contains("#define SPACESKY_MARK (vec3(2.0, 2.0, 5.0) / 255.0)\n"), on);
        assertTrue(on.contains("#define SPACESKY_WATER_MARK (vec3(5.0, 6.0, 52.0) / 255.0)\n"), on);
        assertTrue(on.contains("bool spacesky_fog_marked(vec3 c)"), on);
        assertTrue(on.contains("#ifndef SPACESKY_MODE_GLSL\n"), on);
        String off = SkyMode.glsl(false, true, false);
        assertTrue(off.contains("#define SPACESKY_EVERYWHERE 0\n"), off);
        assertTrue(off.contains("#define SPACESKY_HIDE_SUN 1\n"), off);
        assertTrue(off.contains("#define SPACESKY_HIDE_MOON 0\n"), off);
    }

    @Test
    void shadersUseModeInclude() throws Exception {
        for (String f : List.of("pack/" + CORE + "sky.vsh", "pack/" + CORE + "sky.fsh", "marked/" + CORE + "position_tex.vsh",
            "everywhere/assets/minecraft/shaders/include/fog.glsl")) {
            String s = Files.readString(RES.resolve(f), StandardCharsets.UTF_8);
            assertTrue(s.contains("#moj_import <spacesky:mode.glsl>"), f);
        }
        // SPACESKY_BASE теперь только в mode.glsl — иначе в режиме false (без своего fog.glsl) sky.fsh не собрался бы
        assertFalse(Files.readString(RES.resolve("everywhere/assets/minecraft/shaders/include/fog.glsl"),
            StandardCharsets.UTF_8).contains("#define SPACESKY_BASE"));
    }
}
