package ink.hoka.odyssey.client.spaceflight;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.spacephys.Vec3d;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Повёрнутая рамка на гранях блоков: ноги на поверхности, голова на дальнем конце, сдвиг при смене рамки. */
class SurfaceBoxTest {
    private static final double H = 1.8;
    private static final double W = 0.6;
    private static final double EYE = 1.62;
    private static final double EPS = 1e-9;

    private static double c(Vec3d v, int a) {
        return SurfaceBox.comp(v, a);
    }

    private static boolean inside(double[] b, Vec3d p, double eps) {
        return p.x() >= b[0] - eps && p.x() <= b[3] + eps && p.y() >= b[1] - eps && p.y() <= b[4] + eps
                && p.z() >= b[2] - eps && p.z() <= b[5] + eps;
    }


    @Test
    void floorAndFreeAreVanilla() {
        double[] vanilla = {0.2, 64, 0.2, 0.8, 65.8, 0.8};
        assertArrayEquals(vanilla, SurfaceBox.box(-1, 0.5, 64, 0.5, H, W), EPS);
        assertArrayEquals(vanilla, SurfaceBox.box(SurfaceBox.FLOOR, 0.5, 64, 0.5, H, W), EPS);
        assertFalse(SurfaceBox.turned(-1));
        assertFalse(SurfaceBox.turned(SurfaceBox.FLOOR));
        Vec3d eye = SurfaceBox.eye(-1, H, W, EYE);
        assertEquals(0, eye.x(), EPS);
        assertEquals(EYE, eye.y(), EPS);
        assertEquals(0, eye.z(), EPS);
    }

    /** Потолок: та же коробка, что у игры (сдвигать нечего), но ноги вверху, глаза внизу. */
    @Test
    void ceilingSameBoxHeadDown() {
        assertArrayEquals(SurfaceBox.box(-1, 3, 10, 4, H, W), SurfaceBox.box(3, 3, 10, 4, H, W), EPS);
        assertEquals(H, SurfaceBox.feet(3, H, W).y(), EPS);                        // ноги — у верхней грани
        assertEquals(H - EYE, SurfaceBox.eye(3, H, W, EYE).y(), EPS);             // глаза — 0.18 над низом
    }

    /** Для всех шести граней: 1.8 вдоль нормали, 0.6 поперёк; ноги на грани рамки у поверхности, голова — на дальней. */
    @Test
    void geometryForAllNormals() {
        Vec3d pos = new Vec3d(10.5, 64, -3.5);
        for (int s = 0; s < 6; s++) {
            Vec3d n = BodyFrame.NORMALS[s];
            int axis = s / 2;
            double[] size = SurfaceBox.size(s, H, W);
            for (int a = 0; a < 3; a++) assertEquals(a == axis ? H : W, size[a], EPS, "грань " + s + ", ось " + a);
            double[] b = SurfaceBox.box(s, pos.x(), pos.y(), pos.z(), H, W);
            assertEquals(pos.y(), b[1], EPS, "позиция — низ рамки");                  // соглашение игры
            assertEquals(pos.x(), (b[0] + b[3]) / 2, EPS);
            assertEquals(pos.z(), (b[2] + b[5]) / 2, EPS);
            Vec3d feet = pos.add(SurfaceBox.feet(s, H, W));
            Vec3d head = feet.add(n.scale(H));
            Vec3d eye = pos.add(SurfaceBox.eye(s, H, W, EYE));
            // ноги — на стороне рамки к −n (там поверхность), голова — на противоположной
            double feetFace = n.dot(new Vec3d(1, 1, 1)) > 0 ? b[axis] : b[axis + 3];
            double headFace = n.dot(new Vec3d(1, 1, 1)) > 0 ? b[axis + 3] : b[axis];
            assertEquals(feetFace, c(feet, axis), EPS, "ноги на поверхности, грань " + s);
            assertEquals(headFace, c(head, axis), EPS, "голова на дальнем конце, грань " + s);
            assertTrue(inside(b, feet, EPS) && inside(b, head, EPS) && inside(b, eye, EPS), "грань " + s);
            assertEquals(EYE, eye.sub(feet).dot(n), EPS, "глаза в 1.62 от ног вдоль нормали, грань " + s);
            assertEquals(0, eye.sub(feet).sub(n.scale(EYE)).length(), EPS, "глаза на оси тела, грань " + s);
            // середина модели (BodyFrame.centre по настоящей рамке) — середина рамки
            Vec3d mid = SurfaceBox.mid(s, H, W);
            Vec3d centre = BodyFrame.centre(s, false, mid, H / 2, H);
            assertEquals(0, centre.sub(mid).length(), EPS);
        }
    }

    /** Встал на стену +X (стена у x = 0.2, обычная рамка у неё): новая рамка от стены на 1.8, глаза у прежних. */
    @Test
    void switchUprightToWall() {
        Vec3d pos = new Vec3d(0.5, 64, 0.5);
        double[] up = SurfaceBox.box(-1, pos.x(), pos.y(), pos.z(), H, W);
        Vec3d eye0 = pos.add(0, EYE, 0);
        List<Vec3d> cs = SurfaceBox.candidates(up, pos, eye0, 0, true, H, W, EYE);
        assertFalse(cs.isEmpty());
        Vec3d first = cs.get(0);
        assertEquals(0.2 + 0.9, first.x(), EPS);                                  // рамка [0.2; 2.0]
        assertEquals(64 + 1.2, first.y(), EPS);                                   // как можно выше — глаза ближе
        assertEquals(0.5, first.z(), EPS);
        for (Vec3d c : cs) {
            double[] b = SurfaceBox.box(0, c.x(), c.y(), c.z(), H, W);
            assertEquals(0.2, b[0], EPS, "ноги на стене");
            assertTrue(b[1] >= up[1] - EPS && b[4] <= up[4] + EPS, "по высоте — внутри прежней рамки");
            assertTrue(c.distance(pos) <= SurfaceBox.MAX_SHIFT + EPS);
        }
    }

    /** Для всех граней: из обычной рамки у поверхности — рамка грани, ноги на той же плоскости, сдвиг ограничен. */
    @Test
    void switchUprightToEveryFace() {
        Vec3d pos = new Vec3d(-7.5, 30, 2.5);
        double[] up = SurfaceBox.box(-1, pos.x(), pos.y(), pos.z(), H, W);
        Vec3d eye0 = pos.add(0, EYE, 0);
        for (int s = 0; s < 6; s++) {
            Vec3d n = BodyFrame.NORMALS[s];
            int axis = s / 2;
            double plane = n.dot(new Vec3d(1, 1, 1)) > 0 ? up[axis] : up[axis + 3];
            List<Vec3d> cs = SurfaceBox.candidates(up, pos, eye0, s, true, H, W, EYE);
            assertFalse(cs.isEmpty(), "грань " + s);
            for (Vec3d c : cs) {
                Vec3d feet = c.add(SurfaceBox.feet(s, H, W));
                assertEquals(plane, c(feet, axis), EPS, "ноги на плоскости поверхности, грань " + s);
                assertTrue(c.distance(pos) <= SurfaceBox.MAX_SHIFT + EPS, "грань " + s);
            }
            if (s == SurfaceBox.FLOOR || s == 3) {
                assertEquals(0, cs.get(0).distance(pos), EPS, "пол/потолок — без сдвига, грань " + s);
            }
        }
    }

    /** Отпустил стену: обычная рамка там, где были глаза (голова на месте, тело ниже), внутри длинной рамки. */
    @Test
    void releaseWallToUpright() {
        Vec3d pos = new Vec3d(1.1, 65.2, 0.5);                                  // на стене +X у x = 0.2
        double[] wall = SurfaceBox.box(0, pos.x(), pos.y(), pos.z(), H, W);
        Vec3d eye0 = pos.add(SurfaceBox.eye(0, H, W, EYE));
        assertEquals(0.2 + EYE, eye0.x(), EPS);
        List<Vec3d> cs = SurfaceBox.candidates(wall, pos, eye0, -1, false, H, W, EYE);
        Vec3d first = cs.get(0);
        assertEquals(2.0 - 0.3, first.x(), EPS);                                  // у головы (дальний конец рамки)
        assertEquals(65.2 + 0.6 - H, first.y(), EPS);                             // верх — по верху прежней рамки
        for (Vec3d c : cs) {
            double[] b = SurfaceBox.box(-1, c.x(), c.y(), c.z(), H, W);
            assertTrue(b[0] >= wall[0] - EPS && b[3] <= wall[3] + EPS, "по x — внутри длинной рамки");
            assertTrue(b[1] <= wall[1] + EPS && b[4] >= wall[4] - EPS, "по y — накрывает прежнюю рамку");
            assertTrue(c.distance(pos) <= SurfaceBox.MAX_SHIFT + EPS);
        }
    }

    /** Со стены — на пол (шагнул вниз в пол): ноги на полу, на той же высоте, что низ рамки. */
    @Test
    void wallToFloor() {
        Vec3d pos = new Vec3d(1.1, 64, 0.5);
        double[] wall = SurfaceBox.box(0, pos.x(), pos.y(), pos.z(), H, W);
        Vec3d eye0 = pos.add(SurfaceBox.eye(0, H, W, EYE));
        for (Vec3d c : SurfaceBox.candidates(wall, pos, eye0, SurfaceBox.FLOOR, true, H, W, EYE)) {
            assertEquals(64, c.y(), EPS);
        }
    }

    /** Внутренний угол стена +X → стена +Z: новая рамка от плоскости второй стены, сдвиг меньше блока. */
    @Test
    void wallToWall() {
        Vec3d pos = new Vec3d(1.1, 64, 0.5);
        double[] wall = SurfaceBox.box(0, pos.x(), pos.y(), pos.z(), H, W);
        Vec3d eye0 = pos.add(SurfaceBox.eye(0, H, W, EYE));
        List<Vec3d> cs = SurfaceBox.candidates(wall, pos, eye0, 4, true, H, W, EYE);
        assertFalse(cs.isEmpty());
        for (Vec3d c : cs) {
            double[] b = SurfaceBox.box(4, c.x(), c.y(), c.z(), H, W);
            assertEquals(wall[2], b[2], EPS, "ноги на плоскости стены +Z");
            assertEquals(H, b[5] - b[2], EPS);
            assertTrue(c.distance(pos) < 1.0);
        }
    }
}
