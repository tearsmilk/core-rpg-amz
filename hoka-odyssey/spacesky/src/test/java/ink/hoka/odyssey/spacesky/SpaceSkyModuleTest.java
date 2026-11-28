package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.ModuleConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.world.timeline.Timeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Совместимость с боевым ядром без патча (профиль дыр), облака при reload, запись zones с комментариями. */
class SpaceSkyModuleTest {
    @TempDir
    Path tmp;

    private static Map<String, Integer> idx() {
        return Map.of("space", 0, "galaxy1", 1, "mars", 2);
    }

    private static List<Holes.Hole> abyss() {
        return List.of(Holes.of("galaxy1", "abyss", 0.5, 65, -600, 6, 12, 0, 6, 1, 1, 1));
    }

    @Test
    void unpatchedCoreAllOverworldHolesInSpace() {
        // боевой сервер: ядро без патча, map.yml galaxy1 с dimension: hoka:galaxy1 — мир всё равно overworld
        Map<String, String> file = Map.of("galaxy1", "hoka:galaxy1", "world", MapDimensions.DEFAULT);
        Map<String, String> eff = SpaceSkyModule.effectiveDims(file, false,
            Map.of("galaxy1", MapDimensions.DEFAULT, "world", MapDimensions.DEFAULT));
        assertEquals(Map.of("galaxy1", MapDimensions.DEFAULT, "world", MapDimensions.DEFAULT), eff);
        assertEquals(List.of(0), Holes.withProfiles(abyss(), eff, idx()).stream().map(Holes.Hole::profile).toList(),
            "дыра — в профиле space: при everywhere: true видна в небе");
        // и незагруженная карта — тоже overworld
        assertEquals(MapDimensions.DEFAULT, SpaceSkyModule.effectiveDims(file, false, Map.of()).get("galaxy1"));
    }

    @Test
    void patchedCoreUnloadedMapUsesMapYml() {
        Map<String, String> file = Map.of("galaxy1", "hoka:galaxy1");
        Map<String, String> eff = SpaceSkyModule.effectiveDims(file, true, Map.of("world", MapDimensions.DEFAULT));
        assertEquals(Map.of("galaxy1", "hoka:galaxy1"), eff, "чужие загруженные карты не добавляются");
        assertEquals(List.of(1), Holes.withProfiles(abyss(), eff, idx()).stream().map(Holes.Hole::profile).toList());
    }

    @Test
    void patchedCoreLoadedMapUsesInstance() {
        // map.yml поменяли после загрузки: мир ещё overworld — дыра в space до перезагрузки карты
        Map<String, String> file = Map.of("galaxy1", "hoka:galaxy1");
        Map<String, String> eff = SpaceSkyModule.effectiveDims(file, true, Map.of("galaxy1", MapDimensions.DEFAULT));
        assertEquals(MapDimensions.DEFAULT, eff.get("galaxy1"));
        // патч виден и по миру, загруженному с типом мира профиля (даже если отражение не нашло метод)
        Map<String, String> eff2 = SpaceSkyModule.effectiveDims(Map.of("galaxy1", MapDimensions.DEFAULT, "mars", "hoka:mars"),
            false, Map.of("galaxy1", "hoka:galaxy1"));
        assertEquals(Map.of("galaxy1", "hoka:galaxy1", "mars", "hoka:mars"), eff2);
    }

    @Test
    void coreDimensionDetection() throws Exception {
        // в тестах ядро — без патча (server.jar на classpath нет или он без Maps.dimensionKeys)
        assertFalse(SpaceSkyModule.coreAppliesDimension());
        assertTrue(SpaceSkyModule.hasMethod("java.lang.String", "isBlank"));
        assertFalse(SpaceSkyModule.hasMethod("java.lang.String", "noSuchMethod"));
        assertFalse(SpaceSkyModule.hasMethod("no.such.Clazz", "x"));
        // имена в отражении совпадают с тем, что добавляет патч ядра
        String patch = Files.readString(Path.of("../docs/core-dimension.patch"), StandardCharsets.UTF_8);
        String cls = SpaceSkyModule.CORE_MAPS_CLASS;
        assertTrue(patch.contains("+++ b/server/src/main/java/" + cls.replace('.', '/') + ".java"), cls);
        assertTrue(patch.contains("public static List<String> " + SpaceSkyModule.CORE_MAPS_METHOD + "()"), patch);
    }

    @Test
    void reloadReportsCloudChanges() {
        Profiles.Profile reg = Profiles.defaults("ocean", Profiles.Kind.PLANET).withIndex(1);
        SpaceDimension.Registration r = new SpaceDimension.Registration(
            List.of(Profiles.defaults("space", Profiles.Kind.GALAXY).withIndex(0), reg), Map.of());
        ModuleConfig c = new ModuleConfig(tmp.resolve("c.yml"));
        c.set("profiles.ocean.type", "planet");
        c.set("profiles.ocean.cloud-color", "#FF0000");
        c.set("profiles.ocean.zenith", "#112233");
        List<String> errors = new ArrayList<>();
        List<Profiles.Profile> cur = SpaceSkyModule.current(r, Profiles.read(c, errors), new ArrayList<>(), errors);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("profiles.ocean: clouds/cloud-color — после перезапуска"), errors.toString());
        assertEquals("#112233", Profiles.byId(cur, "ocean").zenith().hex(), "прочие цвета применяются");
        // без изменений облаков — без ошибок
        errors.clear();
        c.set("profiles.ocean.cloud-color", null);
        SpaceSkyModule.current(r, Profiles.read(c, errors), new ArrayList<>(), errors);
        assertEquals(List.of(), errors);
        c.set("profiles.ocean.clouds", false);
        SpaceSkyModule.current(r, Profiles.read(c, errors), new ArrayList<>(), errors);
        assertEquals(1, errors.size(), errors.toString());
    }

    @Test
    void zonesYamlKeepsComments() throws Exception {
        Path f = tmp.resolve("config.yml");
        Files.writeString(f, """
            # шапка
            everywhere: true   # режим

            # профили
            profiles:
              space:
                type: galaxy

            # области: поля
            zones:
              old:              # пропадёт (комментарий внутри раздела)
                type: stars
                x: 1
                z: 2
                radius: 100

            # хвост
            tail: 1
            """, StandardCharsets.UTF_8);
        Zones.saveYaml(f, c -> {
            Zones.write(c, new Zones.Zone("neb", Zones.Kind.NEBULA, 10, -20, 1000, 0.3, 0xB060FF, "andromeda"));
            Zones.write(c, new Zones.Zone("no", Zones.Kind.STARS, 0, 0, 50, 0.3, -1, null));
        });
        String out = Files.readString(f, StandardCharsets.UTF_8);
        for (String keep : List.of("# шапка", "everywhere: true   # режим", "# профили", "# области: поля", "# хвост", "tail: 1")) {
            assertTrue(out.contains(keep), keep + "\n" + out);
        }
        ModuleConfig c = new ModuleConfig(f);
        assertEquals(List.of("old", "neb", "no"), List.copyOf(c.keys("zones")), out);
        List<String> errors = new ArrayList<>();
        List<Zones.Zone> zs = Zones.read(c, errors);
        assertEquals(List.of(), errors);
        assertEquals(new Zones.Zone("neb", Zones.Kind.NEBULA, 10, -20, 1000, 0.3, 0xB060FF, "andromeda"), zs.get(1));
        assertEquals("no", zs.get(2).id(), "имя «no» — строка, а не false");
        assertEquals(1, c.getInt("tail", 0));
        // удалить всё — раздел пустой, остальное цело
        Zones.saveYaml(f, cc -> {
            for (String id : cc.keys("zones")) {
                cc.set("zones." + id, null);
            }
        });
        out = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(out.contains("zones: {}\n"), out);
        assertTrue(out.contains("# хвост\ntail: 1"), out);
        assertEquals(0, new ModuleConfig(f).keys("zones").size());
    }

    @Test
    void zonesYamlBrokenFileUntouched() throws Exception {
        Path f = tmp.resolve("config.yml");
        String broken = "zones:\n  a: [1, 2\n";
        Files.writeString(f, broken, StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> Zones.saveYaml(f, c -> c.set("zones.b.type", "stars")));
        assertEquals(broken, Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void zonesYamlAppendsSection() {
        Map<String, Object> z = new LinkedHashMap<>();
        z.put("a", new LinkedHashMap<>(Map.of("type", "stars")));
        assertEquals("x: 1\nzones:\n  \"a\": {type: stars}\n", Zones.replaceYaml("x: 1\n", z));
    }

    @Test
    void planetVanillaSunFollowsDayCurve() {
        Timeline t = SpaceDimension.timeline(Profiles.defaults("mars", Profiles.Kind.PLANET).withIndex(2));
        JsonObject tracks = Timeline.REGISTRY_CODEC.encode(Transcoder.JSON, t).orElseThrow().getAsJsonObject()
            .getAsJsonObject("tracks");
        for (String a : List.of("minecraft:visual/sun_angle", "minecraft:visual/moon_angle")) {
            JsonObject e = tracks.getAsJsonObject(a).getAsJsonObject("ease");
            assertEquals("[0.362,0.241,0.638,0.759]", e.getAsJsonArray("cubic_bezier").toString(), a);
        }
    }
}
