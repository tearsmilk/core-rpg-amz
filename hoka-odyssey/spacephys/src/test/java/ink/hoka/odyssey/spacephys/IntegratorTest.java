package ink.hoka.odyssey.spacephys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntegratorTest {
    private static final double DT = Units.TICK;
    private static final double R = 50;
    private static final double T = 60;
    private static final double MU = Units.muFromOrbit(R, T);
    private static final double CY = 64;   // центр планеты над «полом» карты
    private static final Body PLANET = Body.planet("p", 0, CY, 0, MU, 5);
    private static final Integrator I = Integrator.DEFAULT;

    @Test
    void circularOrbitClosesAfter100Revolutions() {
        Field f = Field.of(PLANET);
        double v = Orbit.circularSpeed(MU, R);
        State s = new State(R, CY, 0, 0, 0, v);
        int ticks = (int) Math.round(100 * T / DT);          // 120 000 тиков = 100 оборотов
        assertEquals(1, I.substeps(f, s.x, s.y, s.z, DT));   // на r = 50 хватает одного подшага
        double maxRadiusError = 0;
        for (int i = 0; i < ticks; i++) {
            assertNull(I.step(f, s, DT));
            maxRadiusError = Math.max(maxRadiusError, Math.abs(PLANET.distance(s.x, s.y, s.z) - R) / R);
        }
        double closure = Math.sqrt((s.x - R) * (s.x - R) + (s.y - CY) * (s.y - CY) + s.z * s.z) / R;
        assertTrue(closure < 0.005, "орбита не замкнулась: " + closure);
        assertTrue(maxRadiusError < 0.005, "радиус уплыл: " + maxRadiusError);
        assertEquals(CY, s.y, 1e-9);                          // плоскость орбиты не меняется
    }

    @Test
    void verletKeepsOrbitShapeButLagsInPhase() {
        // простой KDK: форма орбиты держится (радиус), а фаза за 100 оборотов отстаёт на ≈ (ω·dt)²/3·2π·100
        Field f = Field.of(PLANET);
        State s = new State(R, CY, 0, 0, 0, Orbit.circularSpeed(MU, R));
        double maxRadiusError = 0;
        for (int i = 0; i < (int) Math.round(100 * T / DT); i++) {
            assertNull(Integrator.VERLET.step(f, s, DT));
            maxRadiusError = Math.max(maxRadiusError, Math.abs(PLANET.distance(s.x, s.y, s.z) - R) / R);
        }
        double closure = Math.sqrt((s.x - R) * (s.x - R) + s.z * s.z) / R;
        double w = 2 * Math.PI / T * DT;
        assertEquals(2 * Math.PI * 100 * w * w / 3, closure, 0.0005);
        assertTrue(maxRadiusError < 1e-4, "радиус уплыл: " + maxRadiusError);
    }

    @Test
    void energyAndAngularMomentumOver1e5Ticks() {
        energyAndAngularMomentum(Integrator.DEFAULT, 1e-6);
        energyAndAngularMomentum(Integrator.VERLET, 1e-4);
    }

    private static void energyAndAngularMomentum(Integrator integ, double energyTolerance) {
        Field f = Field.of(PLANET);
        State s = new State(R, CY, 0, 0, 0.3, 0.8 * Orbit.circularSpeed(MU, R)); // эллипс, слегка наклонный
        double e0 = s.energy(f);
        Vec3d l0 = s.angularMomentum(0, CY, 0);
        double maxDe = 0;
        for (int i = 0; i < 100_000; i++) {
            assertNull(integ.step(f, s, DT));
            maxDe = Math.max(maxDe, Math.abs(s.energy(f) - e0) / Math.abs(e0));
        }
        Vec3d l1 = s.angularMomentum(0, CY, 0);
        assertTrue(maxDe < energyTolerance, integ.scheme() + ": энергия гуляет: " + maxDe);
        assertTrue(l1.sub(l0).length() / l0.length() < 1e-10, "момент импульса не сохранился: " + l1 + " vs " + l0);
    }

    @Test
    void noBodiesNoThrustKeepsVelocityExactly() {
        State s = new State(1.5, 70, -3, 3.25, -0.125, 7.75);
        for (int i = 0; i < 10_000; i++) {
            assertNull(I.step(Field.EMPTY, s, DT));
            I.kick(Field.EMPTY, s, DT);
            assertNull(I.stepEuler(Field.EMPTY, s, DT));
        }
        assertEquals(3.25, s.vx, 0);
        assertEquals(-0.125, s.vy, 0);
        assertEquals(7.75, s.vz, 0);
        assertEquals(1.5 + 3.25 * DT * 20_000, s.x, 1e-6);   // прямая без торможения
    }

    @Test
    void constantThrustWithoutBodies() {
        State s = new State(0, 0, 0, 0, 0, 0);
        for (int i = 0; i < 20; i++) {
            I.step(Field.EMPTY, s, DT, 0, 0, 2.0);
        }
        assertEquals(2.0, s.vz, 1e-12);                       // 1 с при 2 б/с² → 2 б/с
        assertEquals(1.0, s.z, 1e-12);                        // x = a·t²/2 (KDK точен для постоянной силы)
    }

    @Test
    void fallIntoHoleDoesNotTunnelThroughHorizon() {
        double rs = 3;
        Body hole = Body.hole("bh", 10, 80, -20, 500, rs);
        Field f = Field.of(hole);
        State s = new State(10, 80, -20 + 40, 0, 0, 0);       // покой в 40 блоках
        double prevR = 40;
        Body captured = null;
        for (int i = 0; i < 100_000 && captured == null; i++) {
            captured = I.step(f, s, DT);
            double r = hole.distance(s.x, s.y, s.z);
            assertTrue(r <= prevR + 1e-9, "отскочил/прошил: r = " + r + " после " + prevR);
            assertTrue(r >= rs - 1e-9, "оказался внутри горизонта без захвата: " + r);
            prevR = r;
        }
        assertSame(hole, captured);
        assertEquals(rs, hole.distance(s.x, s.y, s.z), 1e-9); // остановлен ровно на горизонте
        assertEquals(10, s.x, 1e-12);
        assertEquals(80, s.y, 1e-12);
        assertTrue(s.z > -20);                                // с «нашей» стороны, а не с обратной
        assertEquals(I.maxSubsteps(), I.substeps(f, 10, 80, -20 + rs * 1.01, DT));
    }

    @Test
    void fastShotThroughHoleIsCaptured() {
        Body hole = Body.hole("bh", 0, 0, 0, 50, 1);
        Field f = Field.of(hole);
        State s = new State(-10, 0, 0, 2000, 0, 0);           // 100 блоков за тик — дальше дыры одним шагом
        assertSame(hole, I.step(f, s, DT));
        assertEquals(-1, s.x, 1e-9);
        State e = new State(-10, 0, 0, 2000, 0, 0);
        assertSame(hole, I.stepEuler(f, e, DT));
        assertEquals(-1, e.x, 1e-9);
    }

    @Test
    void fastFlybyOutsideHorizonIsNotCaptured() {
        Body hole = Body.hole("bh", 0, 0, 0, 50, 1);
        Field f = Field.of(hole);
        State s = new State(-10, 5, 0, 2000, 0, 0);           // прицельный параметр 5 > r_s
        assertNull(I.step(f, s, DT));
        assertTrue(s.x > 80);
    }

    @Test
    void alreadyInsideHorizonIsCapturedImmediately() {
        Body hole = Body.hole("bh", 0, 0, 0, 50, 2);
        State s = new State(0, 1, 0, 0, 0, 0);
        assertSame(hole, I.step(Field.of(hole), s, DT));
        assertEquals(1, s.y, 0);
    }

    @Test
    void deterministicBitForBit() {
        Field f = Field.of(
                PLANET,
                Body.small("station", 120, CY + 10, 40, 3, 2, 60),
                Body.hole("bh", -300, CY, 200, 900, 4));
        long[] a = simulate(f);
        long[] b = simulate(f);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], "расхождение в компоненте " + i);
        }
    }

    private static long[] simulate(Field f) {
        State s = new State(80, CY + 3, -15, 0.7, 0.1, 4.1);
        State e = s.copy();
        Thrust thrust = new Thrust();
        Quat q = Quat.fromYawPitchRoll(37, -12, 5);
        for (int i = 0; i < 20_000; i++) {
            Vec3d a = thrust.accelerate(i % 7 == 0 ? 1 : 0, i % 11 == 0 ? -1 : 0, 0.25, q, DT);
            I.step(f, s, DT, a);
            I.stepEuler(f, e, DT, a.x(), a.y(), a.z());
        }
        return new long[] {
                Double.doubleToRawLongBits(s.x), Double.doubleToRawLongBits(s.y), Double.doubleToRawLongBits(s.z),
                Double.doubleToRawLongBits(s.vx), Double.doubleToRawLongBits(s.vy), Double.doubleToRawLongBits(s.vz),
                Double.doubleToRawLongBits(e.x), Double.doubleToRawLongBits(e.vz),
                Double.doubleToRawLongBits(thrust.fuel())};
    }

    @Test
    void symplecticEulerOrbitStaysBounded() {
        Field f = Field.of(PLANET);
        State s = new State(R, CY, 0, 0, 0, Orbit.circularSpeed(MU, R));
        double maxErr = 0;
        for (int i = 0; i < 100_000; i++) {
            assertNull(I.stepEuler(f, s, DT));
            maxErr = Math.max(maxErr, Math.abs(PLANET.distance(s.x, s.y, s.z) - R) / R);
        }
        assertTrue(maxErr < 0.01, "Эйлер разошёлся: " + maxErr);
    }

    @Test
    void kickMatchesClientFormula() {
        Field f = Field.of(PLANET);
        State s = new State(R, CY, 0, 1, 2, 3);
        Vec3d a = f.acceleration(R, CY, 0);
        I.kick(f, s, DT, 0, 0.5, 0);
        assertEquals(1 + a.x() * DT, s.vx, 0);
        assertEquals(2 + (a.y() + 0.5) * DT, s.vy, 0);
        assertEquals(R, s.x, 0);                               // позицию не трогает
    }

    @Test
    void substepsGrowNearMassiveBody() {
        Field f = Field.of(PLANET);
        assertEquals(1, I.substeps(f, R, CY, 0, DT));
        int near = I.substeps(f, 5, CY, 0, DT);
        assertTrue(near >= 1 && near <= I.maxSubsteps());
        Integrator fine = new Integrator(0.001, 64);
        assertTrue(fine.substeps(f, 5, CY, 0, DT) > near);
        assertNotNull(Integrator.DEFAULT);
    }
}
