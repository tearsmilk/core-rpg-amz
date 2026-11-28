package ink.hoka.odyssey.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackFilesCollectorTest {
    @TempDir
    Path tmp;

    @Test
    void collectsZipPathsSkippingServiceFiles() throws Exception {
        Files.createDirectories(tmp.resolve("assets/coreshaders/items"));
        Files.writeString(tmp.resolve("assets/coreshaders/items/cube_x.json"), "{}");
        Files.writeString(tmp.resolve("assets/coreshaders/items/half.json.tmp"), "no");
        Files.writeString(tmp.resolve(".DS_Store"), "no");
        Map<String, byte[]> m = PackFilesCollector.collect(tmp);
        assertEquals(List.of("assets/coreshaders/items/cube_x.json"), List.copyOf(m.keySet()));
        assertTrue(PackFilesCollector.collect(tmp.resolve("missing")).isEmpty());
    }

    @Test
    void bundledPackHasShaderAndAllImages() throws Exception {
        Map<String, byte[]> m = PackFilesCollector.collect(Path.of("src/main/resources/pack"));
        assertTrue(m.containsKey("assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.fsh"));
        assertTrue(m.containsKey("assets/coreshaders/shaders/include/cs_fragment.glsl"));
        for (String img : Settings.DEFAULT_IMAGES) {
            for (String rel : PackFiles.paths(img)) {
                assertTrue(m.containsKey("assets/" + rel), rel);
            }
        }
        assertEquals(76, m.size());
    }
}
