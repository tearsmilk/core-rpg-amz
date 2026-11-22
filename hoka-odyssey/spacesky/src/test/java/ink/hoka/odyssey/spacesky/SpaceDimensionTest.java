package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.ModuleConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.util.RGBLike;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.registry.RegistryTranscoder;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.attribute.EnvironmentAttribute;
import net.minestom.server.world.timeline.Timeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpaceDimensionTest {
    @TempDir
    Path tmp;

    private static Profiles.Profile space() {
        return Profiles.defaults("space", Profiles.Kind.GALAXY).withIndex(0);
    }

    private static Profiles.Profile mars() {
        return Profiles.defaults("mars", Profiles.Kind.PLANET).withIndex(3);
    }

    @Test
    void galaxyTimelineConstantMarks() {
        Timeline t = SpaceDimension.timeline(space());
        assertNull(t.periodTicks());
        assertEquals(6, t.tracks().size());
        for (Map.Entry<EnvironmentAttribute<?>, Timeline.Track<?, ?>> e : t.tracks().entrySet()) {
            assertInstanceOf(EnvironmentAttribute.Modifier.Override.class, e.getValue().modifier(), e.getKey().key().asString());
            assertEquals(1, e.getValue().keyframes().size());
            assertEquals(0, e.getValue().keyframes().getFirst().ticks());
        }
        // вид в JSON — как ванильный timeline.json (modifier override по умолчанию не пишется)
        JsonObject tracks = Timeline.REGISTRY_CODEC.encode(Transcoder.JSON, t).orElseThrow()
            .getAsJsonObject().getAsJsonObject("tracks");
        assertEquals("#001800", value(tracks, "minecraft:visual/sky_color"));
        assertEquals("#001800", value(tracks, "minecraft:visual/fog_color"));
        assertEquals("#001810", value(tracks, "minecraft:visual/water_fog_color"));
        assertEquals("#00000000", value(tracks, "minecraft:visual/cloud_color"));
        assertEquals("#00000000", value(tracks, "minecraft:visual/sunrise_sunset_color"));
        assertEquals(0.0, tracks.getAsJsonObject("minecraft:visual/star_brightness").getAsJsonArray("keyframes")
            .get(0).getAsJsonObject().get("value").getAsDouble());
    }

    @Test
    void planetTimelineDayCycle() {
        Timeline t = SpaceDimension.timeline(mars());
        assertEquals(24000, t.periodTicks());
        JsonObject tracks = Timeline.REGISTRY_CODEC.encode(Transcoder.JSON, t).orElseThrow()
            .getAsJsonObject().getAsJsonObject("tracks");
        assertEquals("#001803", value(tracks, "minecraft:visual/fog_color"), "метка профиля №3");
        assertEquals("#001813", value(tracks, "minecraft:visual/water_fog_color"));
        // фаза суток — в начале тумана: две точки в тике 0 (31000, 30000) — от 30000 до 31000 за сутки
        var fs = tracks.getAsJsonObject("minecraft:visual/fog_start_distance");
        assertTrue(!fs.has("ease") || fs.get("ease").getAsString().equals("linear"), "линейно (по умолчанию): " + fs);
        var kf = fs.getAsJsonArray("keyframes");
        assertEquals(2, kf.size());
        assertEquals(0, kf.get(0).getAsJsonObject().get("ticks").getAsInt());
        assertEquals(31000.0, kf.get(0).getAsJsonObject().get("value").getAsDouble());
        assertEquals(0, kf.get(1).getAsJsonObject().get("ticks").getAsInt());
        assertEquals(30000.0, kf.get(1).getAsJsonObject().get("value").getAsDouble());
        var ws = tracks.getAsJsonObject("minecraft:visual/water_fog_start_distance").getAsJsonArray("keyframes");
        assertEquals(-9.0, ws.get(0).getAsJsonObject().get("value").getAsDouble());
        assertEquals(-8.0, ws.get(1).getAsJsonObject().get("value").getAsDouble());
        assertEquals(40000.0, tracks.getAsJsonObject("minecraft:visual/fog_end_distance").getAsJsonArray("keyframes")
            .get(0).getAsJsonObject().get("value").getAsDouble());
        for (String a : List.of("minecraft:visual/sky_light_factor", "minecraft:visual/sky_light_color",
            "minecraft:gameplay/sky_light_level", "minecraft:visual/sun_angle", "minecraft:visual/moon_angle",
            "minecraft:visual/cloud_color")) {
            assertTrue(tracks.has(a), a);
        }
        assertTrue(tracks.getAsJsonObject("minecraft:visual/cloud_color").getAsJsonArray("keyframes").size() > 1,
            "облака: день и ночь");
        // без облаков — прозрачные, одной точкой
        Profiles.Profile m = mars();
        Profiles.Profile noClouds = new Profiles.Profile(m.id(), m.index(), m.kind(), m.background(), m.backgroundTop(),
            m.nebula(), m.milkyWay(), m.stars(), m.starBrightness(), m.starTint(), m.zenith(), m.horizon(), m.zenithNight(),
            m.horizonNight(), m.sunset(), m.sun(), m.sunSize(), false, m.cloudColor(), m.fog(), m.fogNight(), m.haze(),
            m.moonSize(), m.moonColor(), m.moonYaw(), m.moonPitch(), m.waterFog());
        JsonObject t2 = Timeline.REGISTRY_CODEC.encode(Transcoder.JSON, SpaceDimension.timeline(noClouds)).orElseThrow()
            .getAsJsonObject().getAsJsonObject("tracks");
        assertEquals("#00000000", value(t2, "minecraft:visual/cloud_color"));
    }

    private static String value(JsonObject tracks, String attr) {
        JsonElement v = tracks.getAsJsonObject(attr).getAsJsonArray("keyframes").get(0).getAsJsonObject().get("value");
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString()
            : String.format("#%08x", v.getAsInt());
    }

    @Test
    void dimensionLikeOverworldWithMark() {
        RegistryKey<Timeline> tl = RegistryKey.unsafeOf(SpaceDimension.KEY);
        DimensionType d = SpaceDimension.dimension(null, space(), tl);
        assertEquals(-64, d.minY());
        assertEquals(384, d.height());
        assertEquals(384, d.logicalHeight());
        assertTrue(d.hasSkylight());
        assertEquals(false, d.hasCeiling());
        assertEquals(DimensionType.Skybox.OVERWORLD, d.skybox());
        assertEquals("#minecraft:infiniburn_overworld", d.infiniburn());
        for (EnvironmentAttribute<?> a : List.of(EnvironmentAttribute.SKY_COLOR, EnvironmentAttribute.FOG_COLOR)) {
            RGBLike c = (RGBLike) d.attributes().entries().get(a).argument();
            assertEquals(SpaceDimension.MARK, c.red() << 16 | c.green() << 8 | c.blue(), a.key().asString());
        }
        RGBLike w = (RGBLike) d.attributes().entries().get(EnvironmentAttribute.WATER_FOG_COLOR).argument();
        assertEquals(SpaceDimension.WATER_MARK, w.red() << 16 | w.green() << 8 | w.blue());
        assertEquals(6, d.attributes().entries().size());
        assertTrue(d.timelines().contains(tl));
        assertEquals(1, d.timelines().size());
        assertEquals(0, SpaceDimension.indexOf(d));
        assertEquals(Profiles.Kind.GALAXY, SpaceDimension.kindOf(d));
        DimensionType p = SpaceDimension.dimension(null, mars(), RegistryKey.unsafeOf("hoka:mars"));
        assertEquals(3, SpaceDimension.indexOf(p));
        assertEquals(Profiles.Kind.PLANET, SpaceDimension.kindOf(p));
    }

    @Test
    void registerOnceAndEncodeForClient() {
        MinecraftServer.init();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("test");
        List<Profiles.Profile> wanted = List.of(Profiles.defaults("space", Profiles.Kind.GALAXY),
            Profiles.defaults("mars", Profiles.Kind.PLANET), Profiles.defaults("ocean", Profiles.Kind.PLANET));
        SpaceDimension.Registration r = SpaceDimension.register(log, wanted);
        assertEquals(List.of(0, 1, 2), r.profiles().stream().map(Profiles.Profile::index).toList());
        assertEquals(SpaceDimension.Status.REGISTERED, r.status().get("mars"));
        // перезагрузка модуля: другой порядок в config.yml и новый профиль — номера из меток, новый — следующий свободный
        List<Profiles.Profile> again = List.of(Profiles.defaults("space", Profiles.Kind.GALAXY),
            Profiles.defaults("ocean", Profiles.Kind.PLANET), Profiles.defaults("andromeda", Profiles.Kind.GALAXY),
            Profiles.defaults("mars", Profiles.Kind.GALAXY));
        SpaceDimension.Registration r2 = SpaceDimension.register(log, again);
        assertEquals(SpaceDimension.Status.ALREADY, r2.status().get("ocean"));
        assertEquals(2, Profiles.byId(r2.profiles(), "ocean").index());
        assertEquals(3, Profiles.byId(r2.profiles(), "andromeda").index());
        assertEquals(Profiles.Kind.PLANET, Profiles.byId(r2.profiles(), "mars").kind(), "вид — как зарегистрирован");
        DimensionType d = MinecraftServer.getDimensionTypeRegistry().get(Key.key(SpaceDimension.KEY));
        DimensionType ow = MinecraftServer.getDimensionTypeRegistry().get(DimensionType.OVERWORLD);
        assertEquals(ow.minY(), d.minY());
        assertEquals(ow.height(), d.height());
        assertEquals(ow.infiniburn(), d.infiniburn());
        // атрибуты overworld (звуки, кровать, музыка) сохранены, небо — МЕТКА
        assertTrue(d.attributes().entries().keySet().containsAll(ow.attributes().entries().keySet()));
        JsonObject json = DimensionType.REGISTRY_CODEC.encode(new RegistryTranscoder<>(Transcoder.JSON, MinecraftServer.process()), d)
            .orElseThrow().getAsJsonObject();
        // список из одного элемента кодируется строкой — так ванильный HolderSet тоже читается
        assertEquals("hoka:space", json.get("timelines").getAsString(), json.toString());
        assertEquals("#001800", json.getAsJsonObject("attributes").get("minecraft:visual/sky_color").getAsString(), json.toString());
        assertEquals("#001810", json.getAsJsonObject("attributes").get("minecraft:visual/water_fog_color").getAsString(), json.toString());
        assertEquals(-64, json.get("min_y").getAsInt());
        DimensionType mars = MinecraftServer.getDimensionTypeRegistry().get(Key.key("hoka:mars"));
        JsonObject mj = DimensionType.REGISTRY_CODEC.encode(new RegistryTranscoder<>(Transcoder.JSON, MinecraftServer.process()), mars)
            .orElseThrow().getAsJsonObject();
        assertEquals("#001801", mj.getAsJsonObject("attributes").get("minecraft:visual/fog_color").getAsString(), mj.toString());
        assertEquals("hoka:mars", mj.get("timelines").getAsString());
        // пакет реестров для клиента собирается (и таймлайны профилей в нём есть)
        MinecraftServer.getDimensionTypeRegistry().registryDataPacket(MinecraftServer.process(), true);
        MinecraftServer.getTimelineRegistry().registryDataPacket(MinecraftServer.process(), true);
        assertTrue(MinecraftServer.getTimelineRegistry().getId(MinecraftServer.getTimelineRegistry().getKey(Key.key(SpaceDimension.KEY))) >= 0);
        assertTrue(MinecraftServer.getTimelineRegistry().getId(MinecraftServer.getTimelineRegistry().getKey(Key.key("hoka:mars"))) >= 0);
    }

    @Test
    void currentProfilesKeepRegisteredIndexes() {
        SpaceDimension.Registration reg = new SpaceDimension.Registration(List.of(space(), mars()), Map.of());
        List<Profiles.Profile> cfg = List.of(Profiles.defaults("space", Profiles.Kind.GALAXY),
            Profiles.defaults("newone", Profiles.Kind.GALAXY), Profiles.defaults("mars", Profiles.Kind.GALAXY));
        List<String> pending = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();
        List<Profiles.Profile> cur = SpaceSkyModule.current(reg, cfg, pending, errors);
        assertEquals(List.of("space", "mars"), cur.stream().map(Profiles.Profile::id).toList());
        assertEquals(3, cur.get(1).index());
        assertEquals(Profiles.Kind.PLANET, cur.get(1).kind(), "вид меняется только после перезапуска");
        assertEquals(List.of("newone"), pending);
        assertEquals(2, errors.size(), errors.toString());
    }

    @Test
    void mapDimensionWriteKeepsOtherKeys() throws Exception {
        Path maps = tmp.resolve("maps");
        Files.createDirectories(maps.resolve("space"));
        Files.createDirectories(maps.resolve("lobby"));
        Files.createDirectories(maps.resolve("nether"));
        ModuleConfig c = new ModuleConfig(MapDimensions.file(maps, "space"));
        c.set("generator", "void");
        c.set("spawn.x", 0.5);
        c.save();
        ModuleConfig n = new ModuleConfig(MapDimensions.file(maps, "nether"));
        n.set("dimension", "minecraft:the_nether");
        n.save();

        assertNull(MapDimensions.write(maps, "space", "hoka:space"));
        c.reload();
        assertEquals("hoka:space", c.getString("dimension", ""));
        assertEquals("void", c.getString("generator", ""));
        assertEquals(0.5, c.getDouble("spawn.x", 0));
        assertEquals(Map.of("space", "hoka:space", "lobby", "minecraft:overworld", "nether", "minecraft:the_nether"),
            MapDimensions.read(maps, List.of("space", "lobby", "nether", "missing")));
        // другой профиль — перезапись
        assertEquals("hoka:space", MapDimensions.write(maps, "space", "hoka:mars"));
        c.reload();
        assertEquals("hoka:mars", c.getString("dimension", ""));
        // off не трогает чужой тип мира
        assertEquals("minecraft:the_nether", MapDimensions.write(maps, "nether", null));
        n.reload();
        assertEquals("minecraft:the_nether", n.getString("dimension", ""));
        assertEquals("hoka:mars", MapDimensions.write(maps, "space", null));
        c.reload();
        assertNull(c.get("dimension"));
        assertEquals("void", c.getString("generator", ""));
        try {
            MapDimensions.write(maps, "missing", "hoka:space");
            throw new AssertionError("нет исключения");
        } catch (IllegalArgumentException expected) {
            // ок
        }
    }
}
