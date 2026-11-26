package ink.hoka.odyssey.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Файлы модуля в jar: module.yml и config.yml разбираются YAML (сервер иначе не загрузит модуль), ассеты на месте. */
class ResourcesTest {
    private static Map<?, ?> yaml(String res) throws Exception {
        try (InputStream in = ResourcesTest.class.getResourceAsStream(res)) {
            assertNotNull(in, res);
            Object o = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(o instanceof Map<?, ?>, res);
            return (Map<?, ?>) o;
        }
    }

    @Test
    void moduleYmlParses() throws Exception {
        Map<?, ?> m = yaml("/module.yml");
        assertEquals("guns", m.get("id"));
        assertEquals(GunsModule.class.getName(), m.get("main"));
        assertTrue(String.valueOf(m.get("description")).contains("TACZ"));
    }

    @Test
    void configYmlParses() throws Exception {
        Map<?, ?> c = yaml("/config.yml");
        assertEquals(Boolean.FALSE, c.get("log-hits"));
        assertTrue(((Map<?, ?>) c.get("guns")).containsKey("ak47"));
    }

    @Test
    void taczAssetsAndNoticeInJar() {
        assertNotNull(Assets.resource(Assets.NOTICE));
        assertNotNull(Assets.resource("models/ak47.bbmodel"));
        for (String f : Assets.ITEM_FILES) {
            assertNotNull(Assets.resource(f), f);
        }
        for (Assets.SoundEntry s : Assets.SOUNDS) {
            assertNotNull(Assets.resource("sounds/" + s.file()), s.file());
        }
    }
}
