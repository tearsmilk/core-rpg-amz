package core.amazake.systems.coremobs.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Параметры model* в mobs.yml. */
class MobModelConfigTest {

    private static YamlConfiguration yaml(final String text) throws InvalidConfigurationException {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    private static MobDef.Model model(final String body, final List<String> warns) throws InvalidConfigurationException {
        return MobCatalog.model(yaml(body), warns::add);
    }

    @Test
    void noModelByDefault() throws Exception {
        final List<String> w = new ArrayList<>();
        assertNull(model("type: SKELETON\n", w));
        assertTrue(w.isEmpty());
    }

    @Test
    void defaults() throws Exception {
        final List<String> w = new ArrayList<>();
        final MobDef.Model m = model("model: skeleton_archer\n", w);
        assertEquals(new MobDef.Model("skeleton_archer", 1.0, true, "attack", "hit"), m);
        assertTrue(w.isEmpty());
    }

    @Test
    void allParams() throws Exception {
        final List<String> w = new ArrayList<>();
        final MobDef.Model m = model("""
            model: skeleton_archer
            model-scale: 1.5
            model-hide-base: false
            model-attack: shoot
            model-hit: none
            """, w);
        assertEquals(new MobDef.Model("skeleton_archer", 1.5, false, "shoot", null), m);
        assertTrue(w.isEmpty());
    }

    @Test
    void badValues() throws Exception {
        final List<String> w = new ArrayList<>();
        final MobDef.Model m = model("""
            model: skeleton_archer
            model-scale: -2
            model-attack: ""
            """, w);
        assertEquals(1.0, m.scale());
        assertNull(m.attack());
        assertEquals("hit", m.hit());
        assertEquals(1, w.size());

        w.clear();
        assertNull(model("model: 'skeleton archer'\n", w));
        assertEquals(1, w.size());

        w.clear();
        assertNull(model("model-scale: 2\n", w));
        assertEquals(1, w.size());
    }

    @Test
    void hitboxScale() throws Exception {
        final List<String> w = new ArrayList<>();
        // не указан — как model-scale
        MobDef.Model m = model("model: cave_crawler\nmodel-scale: 0.45\n", w);
        assertEquals(0.45, m.scale());
        assertEquals(0.45, m.hitboxScale());
        assertTrue(w.isEmpty());
        // указан — свой
        m = model("model: bone_lord\nmodel-scale: 1.4\nmodel-hitbox-scale: 1.2\n", w);
        assertEquals(new MobDef.Model("bone_lord", 1.4, 1.2, true, "attack", "hit"), m);
        assertTrue(w.isEmpty());
        // неверный — как model-scale, с предупреждением
        m = model("model: bone_lord\nmodel-scale: 1.4\nmodel-hitbox-scale: 0\n", w);
        assertEquals(1.4, m.hitboxScale());
        assertEquals(1, w.size());
        // без model — предупреждение
        w.clear();
        assertNull(model("model-hitbox-scale: 2\n", w));
        assertEquals(1, w.size());
    }

    @Test
    void parsedIntoMobDef() throws Exception {
        final List<String> w = new ArrayList<>();
        final MobCatalog c = MobCatalog.parse(yaml("""
            mobs:
              cave_skeleton:
                type: SKELETON
                model: skeleton_archer
                model-scale: 2
              forest_zombie:
                type: ZOMBIE
            """), w::add);
        assertTrue(w.isEmpty(), w.toString());
        assertEquals("skeleton_archer", c.get("cave_skeleton").model().id());
        assertEquals(2.0, c.get("cave_skeleton").model().scale());
        assertNull(c.get("forest_zombie").model());
    }

    /**
     * Файлы mobs.yml из ресурсов и для продакшена: без предупреждений по model*. В ресурсах модель только у первого
     * скелета (cave_skeleton); в продакшене моделей много — там только разбор без предупреждений. (Полный разбор не гоняем: снаряжение и эффекты требуют реестров
     * сервера.)
     */
    @Test
    void shippedFiles() throws Exception {
        for (final String path : new String[] {"src/main/resources/mobs.yml", "deploy/mobs.prod.yml"}) {
            final File f = new File(path);
            assertTrue(f.isFile(), path);
            final boolean prod = path.startsWith("deploy/");
            final ConfigurationSection mobs = YamlConfiguration.loadConfiguration(f).getConfigurationSection("mobs");
            assertNotNull(mobs, path);
            String firstSkeleton = null;
            int withModel = 0;
            for (final String id : mobs.getKeys(false)) {
                final ConfigurationSection m = mobs.getConfigurationSection(id);
                if (firstSkeleton == null && "SKELETON".equals(m.getString("type"))) {
                    firstSkeleton = id;
                }
                final List<String> w = new ArrayList<>();
                final MobDef.Model model = MobCatalog.model(m, w::add);
                assertTrue(w.isEmpty(), path + " " + id + ": " + w);
                if (model != null) {
                    withModel++;
                    // хитбокс без model-hitbox-scale — в масштабе модели
                    assertEquals(model.scale(), model.hitboxScale(), path + " " + id);
                    if (!prod) {
                        assertEquals("cave_skeleton", id, path);
                        assertEquals(new MobDef.Model("skeleton_archer", 1.0, true, "attack", "hit"), model, path);
                    }
                }
            }
            assertEquals("cave_skeleton", firstSkeleton, path);
            if (!prod) {
                assertEquals(1, withModel, path);
            } else {
                assertTrue(withModel >= 1, path);
            }
        }
    }
}
