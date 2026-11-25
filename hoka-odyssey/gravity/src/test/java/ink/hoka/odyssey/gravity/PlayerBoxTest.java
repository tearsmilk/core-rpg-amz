package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.gravity.SpaceflightLink.Orientation;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minestom.server.collision.BoundingBox;
import org.junit.jupiter.api.Test;

/** Повёрнутая рамка игрока на стене/потолке: размеры, соглашение о позиции, флаг в op 3, запас проверки скорости. */
class PlayerBoxTest {
    private static final double EPS = 1e-9;

    private static byte[] c2s(int flags) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(SpaceflightLink.OP_ORIENT);
            out.writeFloat(1);
            out.writeFloat(0);
            out.writeFloat(0);
            out.writeFloat(0);
            out.writeByte(flags);
        }
        return b.toByteArray();
    }

    private static int flags(int surface, boolean box) {
        return SpaceflightLink.FLAG_ACTIVE | ((surface + 1) << SpaceflightLink.SURFACE_SHIFT)
            | (box ? SpaceflightLink.FLAG_BOX : 0);
    }

    @Test
    void sizesForAllFaces() {
        assertArrayEquals(new double[] {1.8, 0.6, 0.6}, PlayerBox.size(0), EPS);
        assertArrayEquals(new double[] {1.8, 0.6, 0.6}, PlayerBox.size(1), EPS);
        assertArrayEquals(new double[] {0.6, 1.8, 0.6}, PlayerBox.size(2), EPS);   // пол — обычная
        assertArrayEquals(new double[] {0.6, 1.8, 0.6}, PlayerBox.size(3), EPS);   // потолок — та же коробка
        assertArrayEquals(new double[] {0.6, 0.6, 1.8}, PlayerBox.size(4), EPS);
        assertArrayEquals(new double[] {0.6, 0.6, 1.8}, PlayerBox.size(5), EPS);
        assertArrayEquals(new double[] {0.6, 1.8, 0.6}, PlayerBox.size(-1), EPS);
        assertFalse(PlayerBox.turned(-1));
        assertFalse(PlayerBox.turned(PlayerBox.FLOOR));
        assertTrue(PlayerBox.turned(3));
    }

    /** Minestom: new BoundingBox(sx, sy, sz) — от позиции (−sx/2, 0, −sz/2): позиция — середина низа, как у клиента. */
    @Test
    void minestomBoxMatchesClientConvention() {
        double[] s = PlayerBox.size(0);
        BoundingBox bb = new BoundingBox(s[0], s[1], s[2]);
        assertEquals(-0.9, bb.minX(), EPS);
        assertEquals(0.9, bb.maxX(), EPS);
        assertEquals(0, bb.minY(), EPS);
        assertEquals(0.6, bb.maxY(), EPS);
        assertEquals(-0.3, bb.minZ(), EPS);
        assertEquals(0.3, bb.maxZ(), EPS);
    }

    @Test
    void boxFlag() throws IOException {
        for (int s = 0; s < SpaceflightLink.SURFACES; s++) {
            Orientation o = SpaceflightLink.decodeOrientation(c2s(flags(s, true)));
            assertNotNull(o);
            assertEquals(s, o.surface());
            assertEquals(PlayerBox.turned(s) ? s : -1, o.boxSurface(), "грань " + s);
            // зрителям уходит с битом рамки (их клиенты ставят ту же рамку), на полу — без него
            byte[] out = SpaceflightLink.orientation(7, o);
            assertEquals(PlayerBox.turned(s), (out[21] & SpaceflightLink.FLAG_BOX) != 0);
            assertEquals(-1, SpaceflightLink.decodeOrientation(c2s(flags(s, false))).boxSurface());
        }
        // бит рамки без грани — отбрасывается
        assertEquals(SpaceflightLink.FLAG_ACTIVE, SpaceflightLink.sanitizeFlags(SpaceflightLink.FLAG_ACTIVE
            | SpaceflightLink.FLAG_BOX));
        assertEquals(SpaceflightLink.FLAG_ACTIVE, SpaceflightLink.sanitizeFlags(SpaceflightLink.FLAG_ACTIVE
            | SpaceflightLink.FLAG_BOX | (7 << SpaceflightLink.SURFACE_SHIFT)));
    }

    /** Сдвиг при смене рамки проходит проверку скорости с разовым запасом, а без него — нет. */
    @Test
    void shiftCredit() {
        Inertia.MoveGuard g = new Inertia.MoveGuard();
        g.reset(0);
        g.tick(0, 0);
        assertTrue(g.credit < PlayerBox.MAX_SHIFT);
        assertEquals(Inertia.MoveGuard.Verdict.OVER, copy(g).check(PlayerBox.MAX_SHIFT, 0));
        g.credit += PlayerBox.SHIFT_CREDIT;
        assertEquals(Inertia.MoveGuard.Verdict.OK, g.check(PlayerBox.MAX_SHIFT, 3));
    }

    /** Скачок позиции при смене рамки не считается скоростью (замер — от новой позиции). */
    @Test
    void rebaseSkipsShift() {
        Spacer sp = new Spacer(ink.hoka.odyssey.spacephys.Thrust.Spec.DEFAULT);
        sp.observe(0, 0, 0);
        sp.observe(0.1, 0, 0);
        double before = sp.measured()[0];
        sp.rebase();
        sp.observe(1.5, 1.2, 0);
        assertEquals(before, sp.measured()[0], EPS);
        assertEquals(0, sp.measured()[1], EPS);
    }

    private static Inertia.MoveGuard copy(Inertia.MoveGuard g) {
        Inertia.MoveGuard c = new Inertia.MoveGuard();
        c.speed = g.speed;
        c.credit = g.credit;
        c.allowed = g.allowed;
        return c;
    }
}
