package ink.hoka.odyssey.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtoTest {
    @Test
    void fireRoundTripAndNormalize() {
        byte[] b = Proto.fire(7, List.of(new Proto.Shot(0, 0, 0, 1), new Proto.Shot(100, 0, 0, 1.2f)));
        Proto.Msg m = Proto.decode(b);
        assertEquals(Proto.C_FIRE, m.op());
        assertEquals(7, m.fire().seq());
        assertEquals(2, m.fire().shots().size());
        assertEquals(100, m.fire().shots().get(1).ms());
        assertEquals(1f, m.fire().shots().get(1).dz(), 1e-6);
    }

    @Test
    void rejectsGarbage() {
        assertNull(Proto.decode(null));
        assertNull(Proto.decode(new byte[0]));
        assertNull(Proto.decode(new byte[] {99}));
        assertNull(Proto.decode(new byte[] {Proto.C_FIRE, 0, 0}));                     // обрезано
        assertNull(Proto.decode(Proto.fire(1, List.of(new Proto.Shot(0, 0, 0, 0)))));  // нулевой вектор
        assertNull(Proto.decode(Proto.fire(1, List.of(new Proto.Shot(0, Float.NaN, 0, 1)))));
        assertEquals(Proto.C_RELOAD, Proto.decode(new byte[] {Proto.C_RELOAD}).op());
        assertEquals(1, Proto.decode(new byte[] {Proto.C_FIREMODE, 1}).mode());
    }

    @Test
    void stateLayout() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Proto.state("ak47", 29, -1, 1, 5, 42)));
        assertEquals(Proto.S_STATE, in.readUnsignedByte());
        assertEquals("ak47", in.readUTF());
        assertEquals(29, in.readInt());
        assertEquals(-1, in.readInt());
        assertEquals(1, in.readUnsignedByte());
        assertEquals(5, in.readUnsignedByte());
        assertEquals(42, in.readInt());
    }

    @Test
    void configLayout() throws IOException {
        GunDef g = TestGuns.ak();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Proto.config(List.of(g), 0.35f)));
        assertEquals(Proto.S_CONFIG, in.readUnsignedByte());
        assertEquals(Proto.VERSION, in.readUnsignedByte());
        assertEquals(1, in.readUnsignedShort());
        assertEquals("ak47", in.readUTF());
        assertEquals("АКМ", in.readUTF());
        assertEquals("guns/ak47.bbmodel", in.readUTF());
        in.readUTF();
        in.readUTF();
        assertEquals(600f, in.readFloat());
        assertEquals(3, in.readUnsignedByte());
        assertEquals(30, in.readInt());
    }

    @Test
    void configEndsWithPlayerAnimationAndItemTrailers() throws IOException {
        GunDef g = TestGuns.ak();
        byte[] b = Proto.config(List.of(g), 0.35f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bo);
        o.writeFloat(0.35f);
        o.writeShort(1);
        o.writeUTF("ak47");
        o.writeUTF(g.playerAnimation());
        o.writeShort(1);
        o.writeUTF("ak47");
        o.writeUTF(g.item());
        o.writeShort(1);
        o.writeUTF("ak47");
        o.writeUTF("rifle");
        o.writeFloat(0f);
        o.writeByte(0);
        o.writeFloat(1f);
        o.writeShort(1);
        o.writeUTF("ak47");
        o.writeFloat(1f);                                     // отдача в вакууме АК — 1 блок
        byte[] tail = bo.toByteArray();
        assertEquals("guns/rifle_default.player_animation.json", g.playerAnimation());
        assertEquals(java.util.Arrays.toString(tail),
                java.util.Arrays.toString(java.util.Arrays.copyOfRange(b, b.length - tail.length, b.length)));
    }

    @Test
    void configWithoutBackGunHasEmptyItemTrailer() {
        GunDef g = TestGuns.ak();
        byte[] on = Proto.config(List.of(g), 0.35f, true);
        byte[] off = Proto.config(List.of(g), 0.35f, false);
        int items = 2 + (2 + "ak47".length()) + (2 + g.item().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        int kinds = 2 + (2 + "ak47".length()) + (2 + "rifle".length()) + 4 + 1 + 4    // третий хвост — тот же
                + 2 + (2 + "ak47".length()) + 4;                                      // и четвёртый
        assertEquals(on.length - items + 2, off.length);
        int end = off.length - kinds;
        assertEquals(0, off[end - 1]);
        assertEquals(0, off[end - 2]);
        assertEquals(java.util.Arrays.toString(java.util.Arrays.copyOf(on, end - 2)),
                java.util.Arrays.toString(java.util.Arrays.copyOf(off, end - 2)));
    }

    @Test
    void configKindsTrailer() throws IOException {
        java.util.Map<?, ?> c = TestGuns.config();
        GunDef awp = GunDef.read("ai_awp", GunDef.Cfg.of(c));
        GunDef glock = GunDef.read("glock_17", GunDef.Cfg.of(c));
        byte[] b = Proto.config(List.of(awp, glock), 0.35f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bo);
        o.writeShort(2);
        o.writeUTF("ai_awp");
        o.writeUTF("sniper");
        o.writeFloat(0.9f);
        o.writeByte(1);
        o.writeFloat(1f);
        o.writeUTF("glock_17");
        o.writeUTF("pistol");
        o.writeFloat(0f);
        o.writeByte(0);
        o.writeFloat(0.4f);
        o.writeShort(2);                                      // четвёртый хвост: отдача в вакууме
        o.writeUTF("ai_awp");
        o.writeFloat(3f);
        o.writeUTF("glock_17");
        o.writeFloat(0.5f);
        byte[] tail = bo.toByteArray();
        assertEquals(java.util.Arrays.toString(tail),
                java.util.Arrays.toString(java.util.Arrays.copyOfRange(b, b.length - tail.length, b.length)));
    }

    @Test
    void poseLayout() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                Proto.pose(17, Proto.POSE_HOLDING | Proto.POSE_RELOAD, 0.5f, 2.6f)));
        assertEquals(Proto.S_POSE, in.readUnsignedByte());
        assertEquals(17, in.readInt());
        assertEquals(Proto.POSE_HOLDING | Proto.POSE_RELOAD, in.readUnsignedByte());
        assertEquals(0.5f, in.readFloat());
        assertEquals(2.6f, in.readFloat());
        assertEquals(0, in.available());
    }

    @Test
    void backLayout() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Proto.back(23, "ak47")));
        assertEquals(Proto.S_BACK, in.readUnsignedByte());
        assertEquals(23, in.readInt());
        assertEquals("ak47", in.readUTF());
        assertEquals(0, in.available());
        DataInputStream none = new DataInputStream(new ByteArrayInputStream(Proto.back(23, null)));
        none.readUnsignedByte();
        none.readInt();
        assertEquals("", none.readUTF());
    }
}
