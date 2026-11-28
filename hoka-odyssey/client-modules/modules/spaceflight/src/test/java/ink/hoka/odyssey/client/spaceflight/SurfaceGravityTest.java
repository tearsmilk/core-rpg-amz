package ink.hoka.odyssey.client.spaceflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
     * Мини-мир: блоки — рамки {minX, minY, minZ, maxX, maxY, maxZ}, игрок — рамка 0,6×1,8×0,6 (ровная, как у ядра без
     * крючка рамки). Тик — как {@code SpaceflightModule.physics} + {@code surfaceStep}: вход из свободного полёта
     * (касание ногами или притянуло без клавиш), толчок, внутренний угол, тяготение в воздухе ({@link SurfaceGravity#pull}),
     * переворот на стену в прыжке, шаг и прыжок, затем движение с ударами о блоки по осям.
     */
    private static final class Sim {
        static final double CONTACT = 0.05, WALL_FROM = 1.0, REACH = SurfaceGravity.REACH, PUSH = 0.25;
        static final int REENTRY = 10, ESCAPE = 40;
        final List<double[]> blocks = new ArrayList<>();
        final SurfaceGravity g = new SurfaceGravity();
        double[] box;
        Vec3d v = Vec3d.ZERO;
        int reentry, escape, ticks, jumpDelay;
        long jumpTick;
        boolean jumpHeld, jumped, everFree, everLeft;

        Sim(double x, double y, double z) {
            box = new double[] {x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3};
        }

        Sim block(double x0, double y0, double z0, double x1, double y1, double z1) {
            blocks.add(new double[] {x0, y0, z0, x1, y1, z1});
            return this;
        }

        /** Встать на пол (+Y), взгляд — в +X. */
        Sim stand() {
            g.enter(2, Quat.fromYawPitchRoll(-90, 0, 0), 0);
            return this;
        }

        double x() { return (box[0] + box[3]) / 2; }
        double y() { return box[1]; }

        static boolean hit(double[] a, double[] b) {
            return a[0] < b[3] && a[3] > b[0] && a[1] < b[4] && a[4] > b[1] && a[2] < b[5] && a[5] > b[2];
        }

        /** Касание грани i (блок со стороны −NORMALS[i]) в пределах gap; part — только эта часть рамки (или null). */
        boolean touching(double[] part, int i, double gap) {
            double[] b = (part == null ? box : part).clone();
            int axis = i / 2;
            for (int k = 0; k < 3; k++) {
                if (k == axis) continue;
                b[k] += 0.01;
                b[k + 3] -= 0.01;
            }
            if (i % 2 == 0) {
                b[axis + 3] = b[axis];
                b[axis] -= gap;
            } else {
                b[axis] = b[axis + 3];
                b[axis + 3] += gap;
            }
            for (double[] bl : blocks) {
                if (hit(b, bl)) return true;
            }
            return false;
        }

        boolean touching(int i, double gap) {
            return touching(null, i, gap);
        }

        /** Касание грани i только от WALL_FROM вдоль n от ног и выше (стена выше шага). */
        boolean touchingHigh(int i, Vec3d n) {
            int axis = BodyFrame.normalIndex(n) / 2;
            double[] b = box.clone();
            if (n.x() + n.y() + n.z() > 0) b[axis] = Math.min(b[axis + 3], b[axis] + WALL_FROM);
            else b[axis + 3] = Math.max(b[axis], b[axis + 3] - WALL_FROM);
            return touching(b, i, CONTACT * 2);
        }

        boolean free() {
            return !g.active();
        }

        void tick(int fwd, boolean jump, boolean shift) {
            Vec3d was = v;
            if (reentry > 0) reentry--;
            if (!g.active() && escape > 0) {
                escape--;
                if (!SurfaceGravity.within(box, blocks, REACH)) escape = 0;
            }
            if (!g.active() && reentry == 0) {
                int f = SurfaceGravity.entry(i -> touching(i, CONTACT * 2), UP, was);
                if (f >= 0) {
                    g.enter(f, Quat.fromYawPitchRoll(-90, 0, 0), 0);
                } else if (escape == 0 && fwd == 0 && !jump && !shift) {
                    Vec3d up = SurfaceGravity.pull(null, false, box, blocks, REACH);
                    if (up != null) g.enter(SurfaceGravity.faceOf(up), up, Quat.fromYawPitchRoll(-90, 0, 0), 0);
                }
            }
            if (g.active()) surface(fwd, jump, shift);
            else {
                everFree = true;
                jumpHeld = jump;
            }
            move();
            ticks++;
        }

        private void surface(int fwd, boolean jump, boolean shift) {
            int face = g.face();
            Vec3d n = g.normal();
            boolean grounded = touching(face, CONTACT) && v.dot(n) <= 1e-3;
            boolean jumpPress = jump && !jumpHeld;
            jumpHeld = jump;
            if (jumpPress && (shift || (!grounded && jumped && ticks - jumpTick <= SurfaceGravity.DOUBLE_TAP))) {
                v = SurfaceGravity.pushOff(v, g.up(), PUSH);
                g.leave();
                reentry = REENTRY;
                escape = ESCAPE;
                everLeft = true;
                return;
            }
            Vec3d wish = g.wish(fwd, 0);
            Vec3d up = n;
            double turn = SurfaceGravity.tiltPerTick(SurfaceGravity.SNAP_SECONDS);
            if (grounded) {
                jumped = false;
                Vec3d n0 = n;
                int c = SurfaceGravity.innerCorner(face, wish, i -> touchingHigh(i, n0));
                if (c >= 0) {
                    g.transfer(c, g.target(), 0);
                    n = g.normal();
                    up = n;
                    wish = g.wish(fwd, 0);
                }
                g.tilt(n, turn, 0);
            } else {
                up = SurfaceGravity.pull(n, touching(face, REACH), box, blocks, REACH);
                if (up == null) {
                    g.leave();
                    reentry = REENTRY;
                    everLeft = true;
                    return;
                }
                int c = up == n ? SurfaceGravity.innerCorner(face, wish, i -> touching(i, CONTACT * 2)) : -1;
                if (c < 0 && up != n) {
                    Vec3d u = up;
                    c = SurfaceGravity.entry(i -> touching(i, CONTACT * 2), u, v);
                }
                if (c >= 0 && c != face) {
                    g.transfer(c, g.target(), 0);
                    face = c;
                    n = g.normal();
                    up = n;
                    wish = g.wish(fwd, 0);
                    grounded = touching(face, CONTACT * 2);
                } else {
                    g.tilt(up, turn, 0);
                }
            }
            Vec3d w = shift ? wish.scale(0.3) : wish;
            if (!grounded) {
                Vec3d t = BodyFrame.tangent(w, up);
                w = t.length() < 1e-9 ? Vec3d.ZERO : t.scale(w.length() / t.length());
            }
            Vec3d next = SurfaceGravity.step(v, up, grounded, 0.6, w, 0.1, false);
            if (jumpDelay > 0) jumpDelay--;
            if (!jump) jumpDelay = 0;
            if (grounded && jump && !shift && jumpDelay == 0) {
                next = SurfaceGravity.jump(next, n, g.forward(), false);
                jumpDelay = SurfaceGravity.JUMP_DELAY;
                jumped = true;
                jumpTick = ticks;
            }
            v = next;
        }

        /** Шаг с ударами о блоки: по осям Y, X, Z; упёрся — скорость по оси 0. */
        private void move() {
            double[] d = {v.x(), v.y(), v.z()};
            for (int axis : new int[] {1, 0, 2}) {
                double m = d[axis];
                for (double[] b : blocks) {
                    boolean overlap = true;
                    for (int k = 0; k < 3; k++) {
                        if (k != axis && !(box[k] < b[k + 3] - 1e-9 && box[k + 3] > b[k] + 1e-9)) overlap = false;
                    }
                    if (!overlap) continue;
                    if (m > 0 && box[axis + 3] <= b[axis] + 1e-9) m = Math.min(m, b[axis] - box[axis + 3]);
                    if (m < 0 && box[axis] >= b[axis + 3] - 1e-9) m = Math.max(m, b[axis + 3] - box[axis]);
                }
                box[axis] += m;
                box[axis + 3] += m;
                if (m != d[axis]) d[axis] = 0;
            }
            v = new Vec3d(d[0], d[1], d[2]);
        }
    }

    /** Прыжки (и зажатый пробел — прыжок за прыжком, с бегом вперёд) в пределах reach в невесомость не выводят. */
    @Test
    void jumpsNeverLeaveWithinReach() {
        Sim s = new Sim(0, 0, 0).block(-100, -1, -100, 100, 0, 100).stand();
        double top = 0;
        for (int i = 0; i < 200; i++) {
            s.tick(0, true, false);
            top = Math.max(top, s.y());
            assertTrue(!s.free(), "прыжок вывел в невесомость на тике " + i);
        }
        assertEquals(1.25, top, 0.05);
        assertEquals(2, s.g.face());
        Sim r = new Sim(-50, 0, 0).block(-100, -1, -100, 100, 0, 100).stand();
        for (int i = 0; i < 200; i++) {
            r.tick(1, true, false);
            assertTrue(!r.free());
        }
        assertTrue(r.x() > -30 && r.y() >= -1e-9 && r.y() < 1.3);
    }

    /** Сошёл с края астероида — не невесомость: тянет к ребру, обходишь его и встаёшь на боковую грань. */
    @Test
    void stepOffAsteroidEdgeCurvesOntoTheSide() {
        Sim s = new Sim(2, 5, 2.5).block(0, 0, 0, 5, 5, 5).stand();        // астероид 5×5×5, идём в +X
        boolean side = false;
        for (int i = 0; i < 200 && !side; i++) {
            s.tick(1, false, false);
            assertTrue(!s.free(), "сход с края вывел в невесомость на тике " + i + ", x = " + s.x() + ", y = " + s.y());
            side = s.g.face() == 0;
        }
        assertTrue(side, "не перешёл на боковую грань: грань " + s.g.face());
        assertTrue(s.box[0] >= 5 - 1e-6 && s.box[0] < 5.3, "minX = " + s.box[0]);
        // дальше стоит на боку: тяжесть к нему, верх доворачивается к +X
        for (int i = 0; i < 40; i++) s.tick(0, false, false);
        assertTrue(!s.free());
        assertEquals(0, s.g.face());
        assertTrue(s.g.up().x() > 0.99, "верх " + s.g.up());
        assertTrue(s.touching(0, 0.06), "не стоит на боку: minX = " + s.box[0]);
    }

    /** Дальше reach от всех блоков — свободный полёт; ближе и без клавиш — притянуло, упал на ближайшую грань. */
    @Test
    void freeOnlyFarFromBlocks() {
        Sim far = new Sim(0, 4, 0).block(-10, -1, -10, 10, 0, 10);         // 4 блока над полом
        for (int i = 0; i < 40; i++) far.tick(0, false, false);
        assertTrue(far.free());
        assertEquals(4, far.y(), 1e-9);

        Sim near = new Sim(0, 2.5, 0).block(-10, -1, -10, 10, 0, 10);
        for (int i = 0; i < 60; i++) near.tick(0, false, false);
        assertTrue(!near.free());
        assertEquals(2, near.g.face());
        assertEquals(0, near.y(), 1e-6);

        // на грани в воздухе, а блоки ушли дальше reach (подбросило) — свободный полёт
        Sim up = new Sim(0, 0, 0).block(-10, -1, -10, 10, 0, 10).stand();
        up.v = new Vec3d(0, 1.5, 0);
        for (int i = 0; i < 10 && !up.everLeft; i++) up.tick(0, false, false);
        assertTrue(up.everLeft && up.free());
        assertTrue(up.y() > SurfaceGravity.REACH);
    }

    /** Shift+пробел и двойной пробел отталкивают: блоки не тянут, пока не улетишь дальше reach, — улетаешь. */
    @Test
    void pushOffEscapes() {
        for (boolean chord : new boolean[] {true, false}) {
            Sim s = new Sim(0, 0, 0).block(-10, -1, -10, 10, 0, 10).stand();
            s.tick(0, false, false);
            if (chord) {
                s.tick(0, true, true);
            } else {
                s.tick(0, true, false);                                     // прыжок
                s.tick(0, false, false);
                s.tick(0, true, false);                                     // второе нажатие в прыжке
            }
            assertTrue(s.everLeft && s.free(), chord ? "Shift+пробел" : "двойной пробел");
            for (int i = 0; i < 60; i++) {
                s.tick(0, false, false);
                assertTrue(s.free(), "притянуло обратно на тике " + i + ", y = " + s.y());
            }
            assertTrue(s.y() > SurfaceGravity.REACH + 3, "y = " + s.y());
        }
    }

    /** Прыжок с «вперёд» в низкую стену (в один блок) — переворот на неё; шаг в неё без прыжка — нет. */
    @Test
    void jumpForwardIntoLowWallFlips() {
        Sim walk = new Sim(0, 0, 0.5).block(-10, -1, -10, 10, 0, 10).block(2, 0, -10, 3, 1, 10).stand();
        for (int i = 0; i < 40; i++) walk.tick(1, false, false);
        assertEquals(2, walk.g.face(), "шаг в низкую стену перевернул на неё");
        assertEquals(2 - 0.3, walk.x(), 1e-6);

        Sim s = new Sim(0, 0, 0.5).block(-10, -1, -10, 10, 0, 10).block(2, 0, -10, 3, 1, 10).stand();
        boolean flipped = false;
        for (int i = 0; i < 40 && !flipped; i++) {
            s.tick(1, s.x() > 1.6, false);                              // упёрся в стену — прыжок
            assertTrue(!s.free());
            flipped = s.g.face() == 1;
        }
        assertTrue(flipped, "не перевернуло на стену: грань " + s.g.face() + ", x = " + s.x() + ", y = " + s.y());
        assertEquals(2, s.box[3], 0.1);
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
