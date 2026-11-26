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
}
