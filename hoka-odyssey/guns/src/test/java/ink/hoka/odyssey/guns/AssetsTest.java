package ink.hoka.odyssey.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class AssetsTest {
    @TempDir
    Path dir;

    private static Map<?, ?> yaml(Path f) throws Exception {
        return (Map<?, ?>) new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(f));
    }

    @Test
    void insertsKeepingCommentsAndExisting() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, "# мои предметы\nitems:\n    ruby:\n        name: \"Рубин\"  # камень\n        icon: ruby.png\nother: 1\n");
        Map<String, String> e = new LinkedHashMap<>();
        e.put("ruby", "name: x\n");
        e.put("ak47", Assets.itemYaml(TestGuns.ak()));
        Assets.Result r = Assets.addEntries(f, "items", e);
        assertNull(r.manual());
        String text = Files.readString(f);
        assertTrue(text.contains("# мои предметы") && text.contains("# камень"));
        Map<?, ?> items = (Map<?, ?>) yaml(f).get("items");
        assertEquals("Рубин", ((Map<?, ?>) items.get("ruby")).get("name"));
        assertEquals("tacz/ak47_lod.bbmodel", ((Map<?, ?>) items.get("ak47")).get("model"));
        assertEquals(1, yaml(f).get("other"));
        assertTrue(Files.exists(dir.resolve("config.yml.bak-guns")));
        // второй раз — ничего
        assertEquals("все записи уже есть", Assets.addEntries(f, "items", e).message());
    }

    @Test
    void createsSectionAndFile() throws Exception {
        Path f = dir.resolve("sub/config.yml");
        Map<String, String> e = new LinkedHashMap<>();
        for (Assets.SoundEntry s : Assets.SOUNDS) {
            e.put(s.id(), Assets.soundYaml(s));
        }
        Assets.Result r = Assets.addEntries(f, "sounds", e);
        assertNull(r.manual());
        Map<?, ?> sounds = (Map<?, ?>) yaml(f).get("sounds");
        assertEquals(Assets.SOUNDS.size(), sounds.size());
        Map<?, ?> s3p = (Map<?, ?>) sounds.get("ak47_shoot_3p");
        assertEquals(128, s3p.get("range"));
        assertEquals("Выстрел АКМ", s3p.get("subtitle"));
    }

    @Test
    void inlineSectionGivesManualYaml() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, "items: {ruby: {icon: r.png}}\n");
        Assets.Result r = Assets.addEntries(f, "items", Map.of("ak47", "icon: a.png\n"));
        assertNotNull(r.manual());
        assertEquals("items: {ruby: {icon: r.png}}\n", Files.readString(f));
    }

    @Test
    void jarHasAssetsAndNotice() {
        assertNotNull(Assets.resource("models/ak47.bbmodel"));
        assertNotNull(Assets.resource(Assets.NOTICE));
        for (String rel : Assets.ITEM_FILES) {
            assertNotNull(Assets.resource(rel), rel);
        }
        for (Assets.SoundEntry s : Assets.SOUNDS) {
            assertNotNull(Assets.resource("sounds/" + s.file()), s.file());
        }
    }
}
