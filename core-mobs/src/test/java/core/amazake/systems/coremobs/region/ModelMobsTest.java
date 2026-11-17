package core.amazake.systems.coremobs.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coremobs.mob.MobCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** {@code /mobs region addmob|removemob <регион> all-models}: выбор мобов с моделью и изменение спавна региона. */
class ModelMobsTest {

    private static MobCatalog catalog(final String text, final List<String> warns) throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return MobCatalog.parse(y, warns::add);
    }

    private static final String MOBS = """
        mobs:
          cave_skeleton:
            type: SKELETON
            model: skeleton_archer
          forest_zombie:
            type: ZOMBIE
          empty_model:
            type: ZOMBIE
            model: "  "
          bad_model:
            type: ZOMBIE
            model: "bad model!"
          venom_spider:
            type: SPIDER
            model: spider_queen
        """;

    @Test
    void selectsOnlyMobsWithModelInFileOrder() throws Exception {
        final List<String> w = new ArrayList<>();
        final MobCatalog c = catalog(MOBS, w);
        // пустая model — без модели; неверный id модели — моб без модели (предупреждение)
        assertEquals(List.of("cave_skeleton", "venom_spider"), ModelMobs.withModels(c.all()));
    }

    @Test
    void noModels() throws Exception {
        final MobCatalog c = catalog("mobs:\n  z:\n    type: ZOMBIE\n", new ArrayList<>());
        assertTrue(ModelMobs.withModels(c.all()).isEmpty());
        assertTrue(ModelMobs.withModels(Map.of()).isEmpty());
    }

    @Test
    void addNewAndUpdateExisting() {
        final Map<String, Integer> current = new LinkedHashMap<>();
        current.put("forest_zombie", 5);
        current.put("venom_spider", 3);
        final ModelMobs.Change ch = ModelMobs.add(current, List.of("cave_skeleton", "venom_spider"), 10);
        assertEquals(List.of("cave_skeleton"), ch.added());
        assertEquals(List.of("venom_spider"), ch.updated());
        assertTrue(ch.removed().isEmpty());
        // порядок прежних сохранён, новые — в конец, без дублей; моб без модели не тронут
        assertEquals(List.of("forest_zombie", "venom_spider", "cave_skeleton"), List.copyOf(ch.mobs().keySet()));
        assertEquals(Map.of("forest_zombie", 5, "venom_spider", 10, "cave_skeleton", 10), ch.mobs());
        // исходная карта не меняется
        assertEquals(Map.of("forest_zombie", 5, "venom_spider", 3), current);
    }

    @Test
    void addTwiceIsIdempotent() {
        final ModelMobs.Change first = ModelMobs.add(Map.of(), List.of("a", "b"), 1);
        final ModelMobs.Change second = ModelMobs.add(first.mobs(), List.of("a", "b"), 1);
        assertEquals(first.mobs(), second.mobs());
        assertTrue(second.added().isEmpty());
        assertEquals(List.of("a", "b"), second.updated());
    }

    @Test
    void removeOnlyModelMobs() {
        final Map<String, Integer> current = new LinkedHashMap<>();
        current.put("forest_zombie", 5);
        current.put("cave_skeleton", 2);
        final ModelMobs.Change ch = ModelMobs.remove(current, List.of("cave_skeleton", "venom_spider"));
        assertEquals(List.of("cave_skeleton"), ch.removed());
        assertEquals(Map.of("forest_zombie", 5), ch.mobs());
        assertTrue(ModelMobs.remove(ch.mobs(), List.of("cave_skeleton")).removed().isEmpty());
    }

    @Test
    void removeAlsoDropsLiteralAllModelsKey() throws Exception {
        final MobCatalog c = catalog(MOBS, new ArrayList<>());
        final List<String> ids = ModelMobs.withModelsAndLiteral(c.all());
        assertEquals(List.of("cave_skeleton", "venom_spider", ModelMobs.ALL_MODELS), ids);
        final Map<String, Integer> current = new LinkedHashMap<>();
        current.put(ModelMobs.ALL_MODELS, 1);
        current.put("forest_zombie", 5);
        current.put("venom_spider", 2);
        final ModelMobs.Change ch = ModelMobs.remove(current, ids);
        assertEquals(Map.of("forest_zombie", 5), ch.mobs());
        assertEquals(List.of("venom_spider", ModelMobs.ALL_MODELS), ch.removed());
    }

    @Test
    void mobNamedAllModelsIsRejected() throws Exception {
        final List<String> w = new ArrayList<>();
        final MobCatalog c = catalog("""
            mobs:
              all-models:
                type: ZOMBIE
                model: skeleton_archer
              ALL-MODELS:
                type: ZOMBIE
            """, w);
        assertNull(c.get(ModelMobs.ALL_MODELS));
        assertTrue(c.all().isEmpty());
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.get(0).contains("all-models"), w.get(0));
    }
}
