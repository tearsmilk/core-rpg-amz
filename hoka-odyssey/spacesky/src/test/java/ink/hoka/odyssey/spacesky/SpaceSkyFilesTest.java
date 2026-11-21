package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpaceSkyFilesTest {
    private static final Path RES = Path.of("src/main/resources");

    @Test
    void skyShaderAlwaysInPack() throws Exception {
        Map<String, byte[]> m = SpaceSkyModule.collect(RES.resolve(SpaceSkyModule.ALWAYS));
        assertEquals(List.of("assets/minecraft/shaders/core/sky.fsh", "assets/minecraft/shaders/core/sky.vsh",
            "assets/minecraft/shaders/core/stars.fsh", "assets/minecraft/shaders/include/fog.glsl",
            "assets/spacesky/shaders/include/zones.glsl"), List.copyOf(m.keySet()));
    }

    @Test
    void optionalPartsFollowConfig() throws Exception {
        assertEquals(List.of("pack"), SpaceSkyModule.parts(false, false, false));
        assertEquals(List.of("pack", "hide/sun", "hide/moon", "hide/clouds"), SpaceSkyModule.parts(true, true, true));
        assertEquals(List.of("assets/minecraft/textures/environment/celestial/sun.png"),
            List.copyOf(SpaceSkyModule.collect(RES.resolve("hide/sun")).keySet()));
        assertEquals(8, SpaceSkyModule.collect(RES.resolve("hide/moon")).size());
        assertTrue(SpaceSkyModule.collect(RES.resolve("hide/clouds")).containsKey("assets/minecraft/textures/environment/clouds.png"));
    }
}
