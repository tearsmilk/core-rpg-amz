package ink.hoka.odyssey.client.spaceflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import org.junit.jupiter.api.Test;

/** Тяготение грани: шаг как у vanilla, прыжок и падение обратно, вход, углы, взгляд в системе грани. */
class SurfaceGravityTest {
    private static final double EPS = 1e-6;
    private static final Vec3d UP = new Vec3d(0, 1, 0);

    private static void near(Vec3d want, Vec3d got, double eps) {
        assertTrue(want.distance(got) < eps, () -> "ждали " + want + ", а вышло " + got);
    }

    /** Ходьба по обычному блоку разгоняется до скорости vanilla — 0,216 блока/тик (4,3 б/с), бег — 0,28. */
    @Test
    void walkSpeedLikeVanilla() {
        for (Vec3d n : BodyFrame.NORMALS) {
            Vec3d wish = SurfaceGravity.between(UP, n).rotate(new Vec3d(0, 0, 1));
            for (boolean sprint : new boolean[] {false, true}) {
                Vec3d v = Vec3d.ZERO;
                double moved = 0;
                for (int i = 0; i < 200; i++) {
                    v = SurfaceGravity.step(v, n, true, 0.6, wish, 0.1, sprint);
                    moved = v.dot(wish);                      // шаг этого тика — с разгоном (как vanilla move)
                    v = v.sub(n.scale(v.dot(n)));             // блок не пустил дальше грани
                }
                assertEquals(sprint ? 0.2806 : 0.2159, moved, 1e-3);
            }
        }
    }

    /** Прыжок вдоль нормали стены поднимается на ~1,25 блока и возвращается к ней, как прыжок vanilla. */
    @Test
    void jumpFallsBackToTheFace() {
        Vec3d n = new Vec3d(-1, 0, 0);                         // стоим на стене, «вверх» — к −X
        Vec3d v = SurfaceGravity.jump(Vec3d.ZERO, n, new Vec3d(0, 1, 0), false);
        double h = 0, top = 0;
        int ticks = 0;
        do {
            h += v.dot(n);
            top = Math.max(top, h);
            v = SurfaceGravity.step(v, n, false, 0.6, Vec3d.ZERO, 0.1, false);
            ticks++;
        } while (h > 0 && ticks < 100);
        assertEquals(1.25, top, 0.03);
        assertTrue(ticks > 10 && ticks < 14, "в воздухе " + ticks + " тиков");
    }

    @Test
    void pushOffLeavesAlongNormal() {
        Vec3d n = new Vec3d(0, -1, 0);
        Vec3d v = SurfaceGravity.pushOff(new Vec3d(0.1, 0.05, 0), n, 0.25);
        assertEquals(0.25, v.dot(n), EPS);
        assertEquals(0.1, v.x(), EPS);
    }

    /** Вход: грань стороной ног (ближе 60° к верху камеры), иначе — та, на которую летишь, если не вниз головой. */
    @Test
    void entryByFeetOrLanding() {
        // стоит у стены +X (блок со стороны −X) и на полу: камера ровная — пол
        assertEquals(2, SurfaceGravity.entry(i -> i == 0 || i == 2, UP, Vec3d.ZERO));
        // касается только стены сбоку, стоит ровно, не летит в неё — нет
        assertEquals(-1, SurfaceGravity.entry(i -> i == 0, UP, Vec3d.ZERO));
        // камера наклонена к стене больше чем на 60° от вертикали — стена
        Vec3d tilted = new Vec3d(Math.sin(Math.toRadians(70)), Math.cos(Math.toRadians(70)), 0);
        assertEquals(0, SurfaceGravity.entry(i -> i == 0, tilted, Vec3d.ZERO));
        // прилетел на стену (летит в неё), ногами скорее к ней, чем от неё — стена
        Vec3d side = new Vec3d(Math.sin(Math.toRadians(30)), Math.cos(Math.toRadians(30)), 0);
        assertEquals(0, SurfaceGravity.entry(i -> i == 0, side, new Vec3d(-0.1, 0, 0)));
        // вниз головой к потолку — даже прилетев — нет
        assertEquals(-1, SurfaceGravity.entry(i -> i == 3, UP, new Vec3d(0, 0.3, 0)));
    }

    @Test
    void innerCornerOnlyIntoTallWall() {
        Vec3d wish = new Vec3d(1, 0, 0);                       // идём по полу в +X, стена со стороны +X — грань 1 (−X)
        assertEquals(1, SurfaceGravity.innerCorner(2, wish, i -> i == 1));
        assertEquals(-1, SurfaceGravity.innerCorner(2, wish, i -> false));          // ступенька — не стена
        assertEquals(-1, SurfaceGravity.innerCorner(2, new Vec3d(0, 0, 1), i -> i == 1));   // идём вдоль неё
    }

    @Test
    void outerEdgeIsTheSideOfTheBlockWeLeft() {
        // шли по полу в +X и сошли с края: боковая грань блока — +X (0)
        assertEquals(0, SurfaceGravity.outerEdge(2, new Vec3d(0.2, 0, 0), i -> i == 0));
        // касание грани, к которой идём (−X) — это не ребро, с которого сошли
        assertEquals(-1, SurfaceGravity.outerEdge(2, new Vec3d(0.2, 0, 0), i -> i == 1));
    }

    @Test
    void betweenRotatesNormals() {
        for (Vec3d a : BodyFrame.NORMALS) {
            for (Vec3d b : BodyFrame.NORMALS) {
                near(b, SurfaceGravity.between(a, b).rotate(a), 1e-9);
            }
        }
    }

    /** Вход сохраняет направление взгляда, камера в системе грани ровная (верх — по нормали, без крена). */
    @Test
    void enterKeepsViewAndLevelsCamera() {
        Quat look = Quat.fromYawPitchRoll(30, 20, 40);
        Vec3d n = new Vec3d(0, 0, -1);
        SurfaceGravity g = new SurfaceGravity();
        g.enter(5, look, 0);
        Quat t = g.target();
        near(look.forward(), t.forward(), 1e-6);
        // верх камеры — в плоскости (n, взгляд): без крена относительно грани
        assertEquals(0, t.right().dot(n), 1e-6);
        assertTrue(t.up().dot(n) > 0);
        // сразу после входа видно прежний взгляд, через SNAP_SECONDS — ровный
        near(look.up(), g.shown(0).up(), 1e-6);
        near(t.up(), g.shown((long) (SurfaceGravity.SNAP_SECONDS * 1e9) + 1).up(), 1e-9);
    }

    /** Мышь: курс — вокруг нормали, наклон — от грани, не больше 90°. */
    @Test
    void mouseInFaceFrame() {
        SurfaceGravity g = new SurfaceGravity();
        g.enter(0, Quat.fromYawPitchRoll(0, 0, 90), 0);       // стена +X
        Vec3d n = new Vec3d(1, 0, 0);
        g.turn(37, 0);
        assertEquals(0, g.target().forward().dot(n), 1e-9);   // курс не уводит взгляд с плоскости
        near(n, g.target().up(), 1e-9);
        g.turn(0, 200);
        assertEquals(90, g.pitch, 1e-6);
        assertEquals(-1, g.target().forward().dot(n), 1e-6);  // наклон 90° — в стену
    }

    /** Смена грани поворачивает систему вместе с гранью: шагнул с пола на стену — смотришь вдоль стены. */
    @Test
    void transferCarriesTheView() {
        SurfaceGravity g = new SurfaceGravity();
        g.enter(2, Quat.fromYawPitchRoll(-90, 0, 0), 0);      // пол, смотрим в +X
        near(new Vec3d(1, 0, 0), g.forward(), 1e-9);
        g.transfer(1, g.target(), 0);                         // в стену со стороны +X (нормаль −X)
        near(new Vec3d(0, 1, 0), g.forward(), 1e-9);          // теперь «вперёд» — вверх по стене
        near(new Vec3d(-1, 0, 0), g.target().up(), 1e-9);
        // скорость «в пол» после смены — «в стену» (вниз к новой грани)
        near(new Vec3d(0.2, 0, 0), SurfaceGravity.carry(2, 1, new Vec3d(0, -0.2, 0)), 1e-9);
    }
}
