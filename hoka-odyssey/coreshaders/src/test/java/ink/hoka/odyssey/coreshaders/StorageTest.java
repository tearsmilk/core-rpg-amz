package ink.hoka.odyssey.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {
    @TempDir
    Path tmp;

    private Settings settings() {
        return new Settings(new ModuleConfig(tmp.resolve("none.yml")), Map.of());
    }

    @Test
    void roundTrip() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Actions.Data acts = new Actions.Data(List.of(new Actions.Action(Actions.Executor.PLAYER, "homap tp world {player}"),
            new Actions.Action(Actions.Executor.CONSOLE, "say {player}")), Actions.Trigger.BOTH, 3.5, "op",
            "&aПривет, {player}", "grp");
        List<Effect> in = List.of(
            new Effect(a, new EffectData(Shape.CUBE, "space", 0xFF5050, Dir.UP, 1, -60, 3, false), null),
            new Effect(b, new EffectData(Shape.PLANE, "rain", 0xFFFFFF, Dir.WEST, -100, 70, 2_000_000, true), acts));
        String json = Storage.encode(in);
        Path f = tmp.resolve("effects/world.json");
        Storage.write(f, json);
        Storage.Loaded l = Storage.decode(Files.readString(f), settings());
        assertTrue(l.errors().isEmpty(), l.errors().toString());
        assertEquals(2, l.effects().size());
        Effect x = l.effects().get(0);
        Effect y = l.effects().get(1);
        assertEquals(a, x.id);
        assertEquals(in.get(0).data, x.data);
        assertNull(x.actions);
        assertEquals(b, y.id);
        assertEquals(in.get(1).data, y.data);
        assertEquals(acts, y.actions);
        assertEquals(json, Storage.encode(l.effects()));
    }

    @Test
    void badEntriesSkippedUnknownImageReplaced() {
        String json = "{\"version\":1,\"effects\":[{\"shape\":\"cube\",\"image\":\"gone\",\"x\":1,\"y\":2,\"z\":3},"
            + "{\"shape\":\"cube\"},{\"x\":0,\"y\":0,\"z\":0,\"face\":\"sideways\",\"color\":\"nope\"}]}";
        Storage.Loaded l = Storage.decode(json, settings());
        assertEquals(2, l.effects().size());
        assertEquals(1, l.errors().size(), l.errors().toString());
        assertEquals("sky_sea", l.effects().get(0).data.image());
        assertEquals(Dir.UP, l.effects().get(1).data.face());
        assertEquals(0xFFFFFF, l.effects().get(1).data.color());
        assertTrue(Storage.decode("{}", settings()).errors().size() == 1);
    }
}
