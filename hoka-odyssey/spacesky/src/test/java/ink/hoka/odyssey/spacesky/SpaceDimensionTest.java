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

    @Test
    void timelineOverridesSkyFogCloudsSunriseStars() {
        Timeline t = SpaceDimension.timeline();
        assertNull(t.periodTicks());
        assertEquals(5, t.tracks().size());
        for (Map.Entry<EnvironmentAttribute<?>, Timeline.Track<?, ?>> e : t.tracks().entrySet()) {
            assertInstanceOf(EnvironmentAttribute.Modifier.Override.class, e.getValue().modifier(), e.getKey().key().asString());
            assertEquals(1, e.getValue().keyframes().size());
            assertEquals(0, e.getValue().keyframes().getFirst().ticks());
        }
        // вид в JSON — как ванильный timeline.json (modifier override по умолчанию не пишется)
        JsonObject tracks = Timeline.REGISTRY_CODEC.encode(Transcoder.JSON, t).orElseThrow()
            .getAsJsonObject().getAsJsonObject("tracks");
        assertEquals("#020205", value(tracks, "minecraft:visual/sky_color"));
        assertEquals("#020205", value(tracks, "minecraft:visual/fog_color"));
        assertEquals("#00000000", value(tracks, "minecraft:visual/cloud_color"));
        assertEquals("#00000000", value(tracks, "minecraft:visual/sunrise_sunset_color"));
        assertEquals(0.0, tracks.getAsJsonObject("minecraft:visual/star_brightness").getAsJsonArray("keyframes")
            .get(0).getAsJsonObject().get("value").getAsDouble());
    }

    private static String value(JsonObject tracks, String attr) {
        JsonElement v = tracks.getAsJsonObject(attr).getAsJsonArray("keyframes").get(0).getAsJsonObject().get("value");
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString()
            : String.format("#%08x", v.getAsInt());
    }

    @Test
    void dimensionLikeOverworldWithMark() {
        RegistryKey<Timeline> tl = RegistryKey.unsafeOf(SpaceDimension.KEY);
        DimensionType d = SpaceDimension.dimension(null, tl);
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
        assertEquals(5, d.attributes().entries().size());
        assertTrue(d.timelines().contains(tl));
        assertEquals(1, d.timelines().size());
    }

    @Test
    void registerOnceAndEncodeForClient() {
        MinecraftServer.init();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("test");
        assertEquals(SpaceDimension.Result.REGISTERED, SpaceDimension.register(log));
        assertEquals(SpaceDimension.Result.ALREADY, SpaceDimension.register(log));   // перезагрузка модуля
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
        assertEquals("#020205", json.getAsJsonObject("attributes").get("minecraft:visual/sky_color").getAsString(), json.toString());
        assertEquals(-64, json.get("min_y").getAsInt());
        // пакет реестров для клиента собирается (и таймлайн hoka:space в нём есть)
        MinecraftServer.getDimensionTypeRegistry().registryDataPacket(MinecraftServer.process(), true);
        MinecraftServer.getTimelineRegistry().registryDataPacket(MinecraftServer.process(), true);
        assertTrue(MinecraftServer.getTimelineRegistry().getId(MinecraftServer.getTimelineRegistry().getKey(Key.key(SpaceDimension.KEY))) >= 0);
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

        assertNull(MapDimensions.write(maps, "space", true));
        c.reload();
        assertEquals("hoka:space", c.getString("dimension", ""));
        assertEquals("void", c.getString("generator", ""));
        assertEquals(0.5, c.getDouble("spawn.x", 0));
        assertEquals(Map.of("space", "hoka:space", "lobby", "minecraft:overworld", "nether", "minecraft:the_nether"),
            MapDimensions.read(maps, List.of("space", "lobby", "nether", "missing")));
        // off не трогает чужой тип мира
        assertEquals("minecraft:the_nether", MapDimensions.write(maps, "nether", false));
        n.reload();
        assertEquals("minecraft:the_nether", n.getString("dimension", ""));
        assertEquals("hoka:space", MapDimensions.write(maps, "space", false));
        c.reload();
        assertNull(c.get("dimension"));
        assertEquals("void", c.getString("generator", ""));
        try {
            MapDimensions.write(maps, "missing", true);
            throw new AssertionError("нет исключения");
        } catch (IllegalArgumentException expected) {
            // ок
        }
    }
}
