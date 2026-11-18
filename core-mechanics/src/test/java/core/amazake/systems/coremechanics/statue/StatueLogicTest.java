package core.amazake.systems.coremechanics.statue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coremechanics.doll.Piece;
import core.amazake.systems.coremechanics.doll.PoseMath;
import core.amazake.systems.coremechanics.skin.BodyColors;
import core.amazake.systems.coremechanics.skin.BodyLooks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class StatueLogicTest {

    @Test
    void posesAndAnimsAreWellFormed() {
        assertEquals(22, DollPoses.POSES.size());
        assertEquals(12, DollPoses.ANIMS.size());
        for (final float[] p : DollPoses.POSES.values()) {
            assertEquals(PoseMath.POSE_LEN, p.length);
        }
        for (final DollPoses.Anim a : DollPoses.ANIMS.values()) {
            assertTrue(a.frames().size() >= 2, a.title());
            assertTrue(a.ticks() > 0);
            for (final float[] f : a.frames()) {
                assertEquals(PoseMath.POSE_LEN, f.length);
            }
        }
        assertEquals("T-поза", DollPoses.POSE_TITLES.get("tpose"));
        assertEquals("entity.zombie.ambient", DollPoses.ANIMS.get("zombie").sound());
        // копия — правка не портит каталог
        final float[] p = DollPoses.pose("stand");
        p[0] = 9;
        assertEquals(0.75f, DollPoses.POSES.get("stand")[0]);
        assertNull(DollPoses.pose("нет"));
    }

    @Test
    void interpolationHitsKeyframesAndWraps() {
        final List<float[]> frames = List.of(new float[] {0, 0}, new float[] {10, 20});
        assertArrayEquals(new float[] {0, 0}, PoseInterp.interp(frames, 4, 0));
        assertArrayEquals(new float[] {10, 20}, PoseInterp.interp(frames, 4, 4));
        assertArrayEquals(new float[] {0, 0}, PoseInterp.interp(frames, 4, 8));   // по кругу
        assertArrayEquals(new float[] {5, 10}, PoseInterp.interp(frames, 4, 2));  // smoothstep(0.5) = 0.5
        final float[] q = PoseInterp.interp(frames, 4, 1);                        // smoothstep(0.25) = 0.15625
        assertEquals(1.5625f, q[0], 1e-5);
        assertArrayEquals(new float[] {0, 0}, PoseInterp.interp(frames, 4, -8));
    }

    @Test
    void approachConvergesAndReportsChange() {
        final float[] cur = {0, 0};
        final float[] target = {10, -10};
        assertTrue(PoseInterp.approach(cur, target, 0.5f));
        assertEquals(5f, cur[0], 1e-6);
        for (int i = 0; i < 40; i++) {
            PoseInterp.approach(cur, target, 0.5f);
        }
        assertFalse(PoseInterp.approach(cur, target, 0.5f));
        assertEquals(10f, cur[0], 1e-2);
    }

    @Test
    void summonCommandHasRootAndPassengers() {
        final List<Piece> pieces = new ArrayList<>();
        pieces.add(BodyLooks.head("dGV4", "sig"));
        pieces.addAll(BodyLooks.blocks(BodyColors.STEVE));
        final String cmd = SummonExport.command(PoseMath.full(0, 64, 0, 0, DollPoses.pose("wave")), pieces, 0, 64, 0, "cm_statue");
        assertTrue(cmd.startsWith("summon minecraft:block_display ~ ~ ~ {Tags:[\"cm_statue\"],Passengers:[{id:\"minecraft:item_display\""));
        assertTrue(cmd.contains("value:\"dGV4\""));
        assertTrue(cmd.contains("block_state:{Name:\"minecraft:cyan_terracotta\"}"));
        assertEquals(pieces.size(), cmd.split("transformation:").length - 1);
        assertTrue(cmd.endsWith("}]}"));
        assertEquals("1.0f", SummonExport.f(1f));
        assertEquals("-0.5f", SummonExport.f(-0.5f));
        assertEquals("0.12346f", SummonExport.f(0.123456f));
    }

    @Test
    void statueSaveLoadRoundTrip() {
        final Statue s = new Statue(UUID.randomUUID());
        s.name = "Rezus";
        s.owner = UUID.randomUUID();
        s.tex = "t";
        s.sig = "s";
        s.worldName = "world";
        s.x = 1.5;
        s.y = 64;
        s.z = -3.25;
        s.yaw = 90;
        s.pose = DollPoses.pose("dab");
        s.anim = "dance";
        s.frames.add(DollPoses.pose("stand"));
        s.frames.add(DollPoses.pose("tpose"));
        s.frameTicks = 7;
        s.playing = true;
        final YamlConfiguration y = new YamlConfiguration();
        s.save(y.createSection("statues." + s.id));
        final YamlConfiguration back = new YamlConfiguration();
        assertNotNull(back);
        final YamlConfiguration re;
        try {
            re = new YamlConfiguration();
            re.loadFromString(y.saveToString());
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
        final Statue r = Statue.load(s.id.toString(), re.getConfigurationSection("statues." + s.id));
        assertEquals("Rezus", r.name);
        assertEquals(s.owner, r.owner);
        assertEquals(-3.25, r.z);
        assertEquals(90f, r.yaw);
        assertArrayEquals(DollPoses.pose("dab"), r.pose);
        assertEquals("dance", r.anim);
        assertEquals(2, r.frames.size());
        assertArrayEquals(DollPoses.pose("tpose"), r.frames.get(1));
        assertEquals(7, r.frameTicks);
        assertTrue(r.playing);
    }
}
