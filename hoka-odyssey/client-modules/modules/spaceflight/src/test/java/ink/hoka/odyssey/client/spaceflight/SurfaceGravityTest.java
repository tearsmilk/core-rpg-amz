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

    /**
     * Мини-мир для сходов с края: пол (+Y), блоки — отрезки по X с верхом на своей высоте, рамка 0,6 шириной. Тик —
     * как {@code SpaceflightModule.surfaceStep}: опора, своя плоскость, {@link SurfaceGravity#keeps}, шаг, прыжок,
     * удар о верх блока.
     */
    private static final class Walk {
        final double[][] blocks;                               // {x0, x1, верх}
        double x, y;
        Vec3d v = Vec3d.ZERO;
        double plane;
        boolean free;
        int ticks;

        Walk(double x, double y, double[]... blocks) {
            this.blocks = blocks;
            this.x = x;
            this.y = y;
            plane = y;
        }

        /** Верх блоков под рамкой (не выше ног), −∞ — пусто. */
        double top() {
            double t = Double.NEGATIVE_INFINITY;
            for (double[] b : blocks) {
                if (x - 0.29 < b[1] && x + 0.29 > b[0] && b[2] <= y + 1e-9) t = Math.max(t, b[2]);
            }
            return t;
        }

        /** Ещё под рамкой есть блок (хоть краем). */
        boolean supported() {
            return y - top() <= SurfaceGravity.SEAM;
        }

        void tick(Vec3d wish, boolean jump) {
            double g = y - top();
            boolean grounded = g <= 0.05 && v.y() <= 1e-3;
            if (grounded) {
                plane = y;
            } else if (!SurfaceGravity.keeps(plane - y, g <= SurfaceGravity.STEP_DOWN ? g : Double.POSITIVE_INFINITY)) {
                free = true;
                v = SurfaceGravity.drift(v, UP);
                return;
            }
            Vec3d next = SurfaceGravity.step(v, UP, grounded, 0.6, wish, 0.1, false);
            if (grounded && jump) next = SurfaceGravity.jump(next, UP, wish, false);
            double t = top();
            x += next.x();
            double ny = y + next.y();
            double t2 = top();
            double floor = Math.max(t, t2);
            if (ny < floor && y >= floor - 1e-9) {
                ny = floor;
                next = new Vec3d(next.x(), 0, next.z());
            }
            y = ny;
            v = next;
            ticks++;
        }
    }

    private static final Vec3d EAST = new Vec3d(1, 0, 0);

    /** Обычный прыжок (и зажатый пробел — прыжок за прыжком) режим не меняет: падаешь обратно на ту же плоскость. */
    @Test
    void jumpStaysOnTheFace() {
        Walk w = new Walk(0, 0, new double[] {-100, 100, 0});
        double top = 0;
        for (int i = 0; i < 200; i++) {
            w.tick(Vec3d.ZERO, true);
            top = Math.max(top, w.y);
            assertTrue(!w.free, "прыжок вывел в невесомость на тике " + i);
        }
        assertEquals(1.25, top, 0.03);
        // прыжок с бегом вперёд по большой площадке — тоже на месте
        Walk r = new Walk(-50, 0, new double[] {-100, 100, 0});
        for (int i = 0; i < 200; i++) {
            r.tick(EAST, true);
            assertTrue(!r.free);
        }
        assertTrue(r.y >= -1e-9);
    }

    /** Сошёл с края в воздух — сразу свободный полёт: на первом же тике, когда под рамкой ничего, а не через 8. */
    @Test
    void stepOffEdgeIsFreeImmediately() {
        Walk w = new Walk(-3, 0, new double[] {-100, 0, 0});
        int offAt = -1;
        while (!w.free && w.ticks < 200) {
            w.tick(EAST, false);
            if (offAt < 0 && !w.supported()) offAt = w.ticks;
        }
        assertTrue(w.free, "так и не сошёл с грани");
        assertTrue(offAt > 0);
        assertEquals(offAt, w.ticks, "между сходом с края и невесомостью — тики");
        // ушёл недалеко за край (рамка целиком над пустотой — меньше шага за ним), к грани не тянет
        assertTrue(w.x - 0.29 >= 0 && w.x - 0.29 < 0.3, "x = " + w.x);
        assertTrue(w.v.y() >= 0, "скорость к грани не погашена: " + w.v);
        assertTrue(w.v.x() > 0.1, "скорость вдоль грани пропала: " + w.v);
        // то же самое — пешком через угол площадки из чистой логики: на плоскости, опоры нет — полёт
        assertTrue(!SurfaceGravity.keeps(0, Double.POSITIVE_INFINITY));
        assertTrue(!SurfaceGravity.keeps(0.08, Double.POSITIVE_INFINITY));
    }

    /** Стыки блоков и плит (щели в сотые доли блока) не роняют: 60 блоков пола, каждый следующий ниже на 0,02–0,04. */
    @Test
    void seamsBetweenBlocksDoNotDrop() {
        double[][] floor = new double[60][];
        double h = 0;
        for (int i = 0; i < floor.length; i++) {
            floor[i] = new double[] {i - 30, i - 29, h};
            h -= i % 2 == 0 ? 0.02 : 0.04;
        }
        Walk w = new Walk(-29.5, 0, floor);
        for (int i = 0; i < 400 && w.x < 29; i++) {
            w.tick(EAST, false);
            assertTrue(!w.free, "упал на стыке у x = " + w.x);
        }
        assertTrue(w.x >= 29, "x = " + w.x + ", y = " + w.y);
        assertTrue(SurfaceGravity.keeps(0, 0.03));
        assertTrue(SurfaceGravity.keeps(0, SurfaceGravity.SEAM));
    }

    /** Ступенька вниз до 0,6 (плита, лестница) — обычная ходьба; уступ в блок в воздух — невесомость. */
    @Test
    void smallStepDownWalksButLedgeIsFree() {
        Walk slab = new Walk(-2, 0, new double[] {-100, 0, 0}, new double[] {0, 100, -0.5});
        for (int i = 0; i < 100; i++) {
            slab.tick(EAST, false);
            assertTrue(!slab.free, "ступенька в полблока вывела в невесомость");
        }
        assertEquals(-0.5, slab.y, 1e-9);
        assertTrue(slab.x > 10);

        Walk ledge = new Walk(-2, 0, new double[] {-100, 0, 0}, new double[] {0, 100, -1});
        for (int i = 0; i < 100 && !ledge.free; i++) ledge.tick(EAST, false);
        assertTrue(ledge.free, "уступ в блок — это шаг в воздух");
        assertTrue(ledge.y > -0.1, "упал до невесомости: y = " + ledge.y);

        assertTrue(SurfaceGravity.keeps(0.1, 0.45));             // падаешь на плиту ниже
        assertTrue(!SurfaceGravity.keeps(0.1, Double.POSITIVE_INFINITY));
        assertTrue(!SurfaceGravity.keeps(0, 0.9));               // уступ глубже 0,6
    }

    /** Прыжок унёс за край: пока выше своей плоскости — режим тот же, опустился ниже неё — невесомость сразу. */
    @Test
    void jumpOverTheEdgeIsFreeBelowThePlane() {
        Walk w = new Walk(-0.6, 0, new double[] {-100, 0, 0});
        w.tick(EAST, true);
        boolean wasAbove = false;
        while (!w.free && w.ticks < 100) {
            double before = w.y;
            w.tick(EAST, false);
            if (w.y > 0.5) wasAbove = true;
            if (w.free) assertTrue(before < -SurfaceGravity.SEAM, "вышел, ещё не опустившись ниже плоскости: " + before);
        }
        assertTrue(wasAbove);
        assertTrue(w.free);
        assertTrue(w.y > -0.6, "провалился ниже плоскости на " + -w.y);
        // выше своей плоскости (в прыжке) — остаёшься, даже если под тобой пусто
        assertTrue(SurfaceGravity.keeps(-1.0, Double.POSITIVE_INFINITY));
    }

    /** Внутренний угол (стена выше шага) — по-прежнему плавный переход на стену. */
    @Test
    void innerCornerStillTransfers() {
        SurfaceGravity g = new SurfaceGravity();
        g.enter(2, Quat.fromYawPitchRoll(-90, 0, 0), 0);
        Vec3d wish = new Vec3d(1, 0, 0);
        int c = SurfaceGravity.innerCorner(g.face(), wish, i -> i == 1);
        assertEquals(1, c);
        g.transfer(c, g.target(), 0);
        assertEquals(1, g.face());
        near(new Vec3d(-1, 0, 0), g.normal(), 1e-9);
        // переход плавный: сразу после него видно прежний взгляд
        assertTrue(g.shown(0).up().dot(UP) > 0.99);
    }

    @Test
    void feetAlongTheNormal() {
        double[] lo = {1, 2, 3}, hi = {1.6, 3.8, 3.6};
        assertEquals(2, SurfaceGravity.feet(lo, hi, UP), EPS);
        assertEquals(-3.8, SurfaceGravity.feet(lo, hi, new Vec3d(0, -1, 0)), EPS);
        assertEquals(1, SurfaceGravity.feet(lo, hi, new Vec3d(1, 0, 0)), EPS);
        assertEquals(-3.6, SurfaceGravity.feet(lo, hi, new Vec3d(0, 0, -1)), EPS);
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

    /**
     * Смена грани: камера и тело доворачиваются вместе и плавно — сразу после перехода видно прежние взгляд и тело,
     * на полпути — поворот на половину угла, через surface_turn_time — новая грань.
     */
    @Test
    void transferTurnsCameraAndBodyTogether() {
        SurfaceGravity g = new SurfaceGravity();
        g.turnSeconds(0.5);
        g.enter(2, Quat.fromYawPitchRoll(90, 10, 0), 0);      // пол
        long t = 10_000_000_000L;
        Quat look0 = g.shown(t), body0 = g.body(t);
        near(UP, body0.up(), 1e-9);
        g.transfer(1, look0, t);                               // стена −X
        near(look0.forward(), g.shown(t).forward(), 1e-9);
        near(body0.up(), g.body(t).up(), 1e-9);
        near(body0.forward(), g.body(t).forward(), 1e-9);
        Vec3d half = g.body(t + 250_000_000L).up();
        assertEquals(Math.sqrt(0.5), half.dot(UP), 1e-9);      // 45° из 90°
        // камера на полпути повёрнута так же, как тело
        assertEquals(45, Math.toDegrees(FreeLook.angle(look0, g.shown(t + 250_000_000L))), 1e-6);
        near(new Vec3d(-1, 0, 0), g.body(t + 500_000_001L).up(), 1e-9);
    }
}
