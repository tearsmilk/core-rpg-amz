package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.gravity.SpaceflightLink.Orientation;
import ink.hoka.odyssey.spacephys.Quat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Протокол поворота тел (op 3/4/5) между gravity и клиентским модулем spaceflight. */
class SpaceflightLinkTest {

    private static byte[] c2s(double w, double x, double y, double z, int flags) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(SpaceflightLink.OP_ORIENT);
            out.writeFloat((float) w);
            out.writeFloat((float) x);
            out.writeFloat((float) y);
            out.writeFloat((float) z);
            out.writeByte(flags);
        }
        return b.toByteArray();
    }

    @Test
    void decodesAndNormalises() throws IOException {
        Orientation o = SpaceflightLink.decodeOrientation(c2s(1.5, 0, 0, 0, 0xFF));
        assertNotNull(o);
        assertEquals(1, o.w(), 1e-6);
        assertEquals(0, o.x(), 1e-6);
        // неизвестные биты отброшены, неверная грань под ногами (7) — «свободно»
        assertEquals(SpaceflightLink.FLAG_PRONE | SpaceflightLink.FLAG_ACTIVE, o.flags());
        assertTrue(o.prone());
        assertTrue(o.active());
        assertEquals(-1, o.surface());
    }

    @Test
    void surfaceBits() throws IOException {
        for (int s = 0; s < SpaceflightLink.SURFACES; s++) {
            int raw = SpaceflightLink.FLAG_ACTIVE | ((s + 1) << SpaceflightLink.SURFACE_SHIFT) | 0xC0;
            Orientation o = SpaceflightLink.decodeOrientation(c2s(1, 0, 0, 0, raw));
            assertNotNull(o);
            assertEquals(s, o.surface());
            assertFalse(o.prone());
            assertTrue(o.active());
            // зрителям уходит как есть (без старших мусорных битов)
            byte[] out = SpaceflightLink.orientation(7, o);
            assertEquals(SpaceflightLink.FLAG_ACTIVE | ((s + 1) << SpaceflightLink.SURFACE_SHIFT), out[21] & 0xFF);
        }
        assertEquals(-1, SpaceflightLink.decodeOrientation(c2s(1, 0, 0, 0, 2)).surface());   // свободно
        assertEquals(SpaceflightLink.FLAG_ACTIVE, SpaceflightLink.sanitizeFlags(2 | (7 << 2)));
        assertEquals(SpaceflightLink.FLAG_PRONE | (6 << 2), SpaceflightLink.sanitizeFlags(1 | (6 << 2) | 0x80));
    }

    @Test
    void rejectsGarbage() throws IOException {
        assertNull(SpaceflightLink.decodeOrientation(null));
        assertNull(SpaceflightLink.decodeOrientation(new byte[] {3, 0, 0}));
        assertNull(SpaceflightLink.decodeOrientation(c2s(Double.NaN, 0, 0, 0, 2)));
        assertNull(SpaceflightLink.decodeOrientation(c2s(Double.POSITIVE_INFINITY, 0, 0, 0, 2)));
        assertNull(SpaceflightLink.decodeOrientation(c2s(0, 0, 0, 0, 2)));       // нулевой кватернион
        assertNull(SpaceflightLink.decodeOrientation(c2s(1e6, 0, 0, 0, 2)));     // далеко не единичный
        byte[] wrongOp = c2s(1, 0, 0, 0, 2);
        wrongOp[0] = 9;
        assertNull(SpaceflightLink.decodeOrientation(wrongOp));
    }

    @Test
    void acceptsTrailingBytes() throws IOException {
        byte[] d = c2s(1, 0, 0, 0, 2);
        byte[] longer = new byte[d.length + 4];
        System.arraycopy(d, 0, longer, 0, d.length);
        assertNotNull(SpaceflightLink.decodeOrientation(longer));
    }

    @Test
    void encodesS2C() throws IOException {
        Quat q = Quat.fromYawPitchRoll(30, 20, 10);
        Orientation o = new Orientation((float) q.w(), (float) q.x(), (float) q.y(), (float) q.z(),
            SpaceflightLink.FLAG_ACTIVE);
        byte[] data = SpaceflightLink.orientation(1234567, o);
        assertEquals(22, data.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        assertEquals(SpaceflightLink.OP_ORIENT, in.readUnsignedByte());
        assertEquals(1234567, in.readInt());
        assertEquals(o.w(), in.readFloat());
        assertEquals(o.x(), in.readFloat());
        assertEquals(o.y(), in.readFloat());
        assertEquals(o.z(), in.readFloat());
        assertEquals(SpaceflightLink.FLAG_ACTIVE, in.readUnsignedByte());

        byte[] stop = SpaceflightLink.orientationStop(-5);
        in = new DataInputStream(new ByteArrayInputStream(stop));
        assertEquals(5, stop.length);
        assertEquals(SpaceflightLink.OP_ORIENT_STOP, in.readUnsignedByte());
        assertEquals(-5, in.readInt());
    }

    @Test
    void caps() {
        assertEquals(1, SpaceflightLink.decodeCaps(new byte[] {5, 1}));
        assertEquals(-1, SpaceflightLink.decodeCaps(new byte[] {5}));
        assertEquals(-1, SpaceflightLink.decodeCaps(new byte[] {3, 1}));
    }

    private static Orientation of(Quat q, int flags) {
        return new Orientation((float) q.w(), (float) q.x(), (float) q.y(), (float) q.z(), flags);
    }

    @Test
    void tiltAndLying() {
        int on = SpaceflightLink.FLAG_ACTIVE;
        Orientation upright = of(Quat.fromYawPitchRoll(45, 0, 0), on);
        assertEquals(0, upright.tilt(), 1e-3);
        assertFalse(BodyRelay.lying(upright, false));
        // смотрит вниз — верх камеры наклонён на 70°: лежит
        Orientation down = of(Quat.fromYawPitchRoll(0, 70, 0), on);
        assertEquals(70, down.tilt(), 1e-3);
        assertTrue(BodyRelay.lying(down, false));
        // 55° — встаёт только ниже 50°, ложится только выше 60° (гистерезис)
        Orientation mid = of(Quat.fromYawPitchRoll(0, 55, 0), on);
        assertFalse(BodyRelay.lying(mid, false));
        assertTrue(BodyRelay.lying(mid, true));
        // вверх ногами
        assertEquals(180, of(Quat.fromYawPitchRoll(0, 0, 180), on).tilt(), 1e-3);
        // «лёжа» — всегда лежит, тело вдоль взгляда: взгляд в горизонт — наклон 90°
        Orientation prone = of(Quat.fromYawPitchRoll(0, 0, 0), on | SpaceflightLink.FLAG_PRONE);
        assertEquals(90, prone.tilt(), 1e-3);
        assertTrue(BodyRelay.lying(prone, false));
    }

    @Test
    void fallbackFlags() {
        assertEquals((byte) 0x82, BodyRelay.fallbackFlags((byte) 0x02, true));
        assertEquals((byte) 0x02, BodyRelay.fallbackFlags((byte) 0x02, false));
    }

    @Test
    void angle() {
        Orientation a = of(Quat.fromYawPitchRoll(0, 0, 0), 2);
        Orientation b = of(Quat.fromYawPitchRoll(10, 0, 0), 2);
        assertEquals(10, a.angleTo(b), 1e-3);
    }
}
