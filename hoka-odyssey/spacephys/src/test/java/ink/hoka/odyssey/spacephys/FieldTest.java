package ink.hoka.odyssey.spacephys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FieldTest {
    private static final double MU = Units.muFromOrbit(50, 60);

    @Test
    void unitsAndReferenceNumbers() {
        assertEquals(1370.8, MU, 0.1);                                  // μ = 1371 → r = 50, T = 60 с
        assertEquals(5.24, Orbit.circularSpeed(MU, 50), 0.01);
        assertEquals(7.40, Orbit.escapeSpeed(MU, 50), 0.01);
        assertEquals(60, Orbit.period(MU, 50), 1e-9);
        assertEquals(0.005, Units.accelToPerTick(Units.THRUST_ACCEL), 1e-15);
        assertEquals(3.0, Units.toPerTick(60), 1e-15);
        assertEquals(2.0, Units.accelFromPerTick(0.005), 1e-15);
        assertEquals(9.8 * 100 * 100, Units.muFromSurfaceGravity(9.8, 100), 1e-9);
        assertEquals(9.8, Units.surfaceGravity(Units.muFromSurfaceGravity(9.8, 100), 100), 1e-12);
        assertEquals(100 * Math.pow(0.001, 0.4), Units.sphereOfInfluence(100, 1, 1000), 1e-9);
    }

    @Test
    void planetOutsideIsInverseSquare() {
        Field f = Field.of(Body.planet("p", 10, 20, 30, MU, 5));
        Vec3d a = f.acceleration(10 + 50, 20, 30);
        assertEquals(-MU / 2500, a.x(), 1e-12);
        assertEquals(0, a.y(), 0);
        assertEquals(0, a.z(), 0);
        Vec3d a2 = f.acceleration(10, 20 + 100, 30);
        assertEquals(-MU / 10000, a2.y(), 1e-12);                       // 1/r²: вдвое дальше — вчетверо слабее
    }

    @Test
    void planetInsideIsUniformSphereAndContinuous() {
        double r0 = 20;
        Field f = Field.of(Body.planet("p", 0, 0, 0, MU, r0));
        Vec3d atSurfaceOut = f.acceleration(r0, 0, 0);
        Vec3d justInside = f.acceleration(r0 - 1e-9, 0, 0);
        assertEquals(atSurfaceOut.x(), justInside.x(), 1e-6);
        assertEquals(-MU * 10 / (r0 * r0 * r0), f.acceleration(10, 0, 0).x(), 1e-12); // линейно по r
        assertEquals(0, f.acceleration(0, 0, 0).length(), 0);           // в центре — ноль, без сингулярности
        assertEquals(f.potential(r0, 0, 0), f.potential(r0 - 1e-9, 0, 0), 1e-6);
    }

    @Test
    void plummerSmallBody() {
        double eps = 2;
        Field f = Field.of(Body.small("st", 0, 0, 0, 10, eps, 0));
        double r = 3;
        assertEquals(-10 * r / Math.pow(r * r + eps * eps, 1.5), f.acceleration(r, 0, 0).x(), 1e-12);
        assertEquals(0, f.acceleration(0, 0, 0).length(), 0);
    }

    @Test
    void paczynskiWiitaHole() {
        double rs = 3;
        Field f = Field.of(Body.hole("bh", 0, 0, 0, 100, rs));
        double r = 10;
        assertEquals(-100 / ((r - rs) * (r - rs)), f.acceleration(0, 0, r).z(), 1e-12);
        assertEquals(9, Orbit.isco(rs), 0);
        // внутри горизонта поле конечное (захват ловит интегратор)
        assertTrue(f.acceleration(0, 1, 0).isFinite());
        assertSame(f.bodies().get(0), f.horizonAt(0, 2.9, 0));
        assertNull(f.horizonAt(0, 3.1, 0));
        assertThrows(IllegalArgumentException.class, () -> Body.hole("bad", 0, 0, 0, 1, 0));
    }

    @Test
    void superpositionOfAllBodiesWithoutReachCutoff() {
        Body a = Body.planet("a", -100, 0, 0, MU, 5);
        Body b = Body.planet("b", 100, 0, 0, MU, 5);
        Field f = Field.of(a, b);
        assertEquals(0, f.acceleration(0, 0, 0).length(), 1e-15);       // симметрия — сумма ноль
        Vec3d far = f.acceleration(0, 1e5, 0);
        assertTrue(far.y() < 0 && far.length() > 0);                     // очень далеко — всё равно тянет
        Field one = Field.of(a);
        Vec3d p = new Vec3d(7, 13, -4);
        Vec3d sum = one.acceleration(p).add(Field.of(b).acceleration(p));
        assertEquals(sum.x(), f.acceleration(p).x(), 1e-15);
        assertEquals(sum.y(), f.acceleration(p).y(), 1e-15);
    }

    @Test
    void accelerationIsMinusGradientOfPotential() {
        Field f = Field.of(
                Body.planet("p", 0, 0, 0, MU, 20),
                Body.small("s", 60, 10, 0, 5, 1, 0),
                Body.hole("h", -80, 0, 30, 200, 3));
        double hStep = 1e-5;
        for (Vec3d p : new Vec3d[] {new Vec3d(30, 5, -7), new Vec3d(10, 2, 3), new Vec3d(-70, 4, 25),
                new Vec3d(61, 10.5, 0.3)}) {
            Vec3d a = f.acceleration(p);
            double gx = (f.potential(p.x() + hStep, p.y(), p.z()) - f.potential(p.x() - hStep, p.y(), p.z())) / (2 * hStep);
            double gy = (f.potential(p.x(), p.y() + hStep, p.z()) - f.potential(p.x(), p.y() - hStep, p.z())) / (2 * hStep);
            double gz = (f.potential(p.x(), p.y(), p.z() + hStep) - f.potential(p.x(), p.y(), p.z() - hStep)) / (2 * hStep);
            assertEquals(-gx, a.x(), 1e-6 * (1 + a.length()), p.toString());
            assertEquals(-gy, a.y(), 1e-6 * (1 + a.length()), p.toString());
            assertEquals(-gz, a.z(), 1e-6 * (1 + a.length()), p.toString());
        }
    }

    @Test
    void sphereOfInfluenceFadesSmoothly() {
        Field f = Field.of(Body.small("st", 0, 0, 0, 10, 1, 40));
        double inner = Field.of(Body.small("st", 0, 0, 0, 10, 1, 0)).acceleration(20, 0, 0).x();
        assertEquals(inner, f.acceleration(20, 0, 0).x(), 0);           // внутри 0,75·soi — полное поле
        assertEquals(0, f.acceleration(40, 0, 0).x(), 0);                // на границе и дальше — ноль
        assertEquals(0, f.acceleration(100, 0, 0).x(), 0);
        double mid = f.acceleration(35, 0, 0).x();
        double full = Field.of(Body.small("st", 0, 0, 0, 10, 1, 0)).acceleration(35, 0, 0).x();
        assertTrue(mid < 0 && mid > full);                               // между — ослаблено
        // непрерывность у обеих границ затухания
        assertEquals(f.acceleration(30, 0, 0).x(), f.acceleration(30 + 1e-9, 0, 0).x(), 1e-9);
        assertEquals(0, f.acceleration(40 - 1e-9, 0, 0).x(), 1e-9);
    }

    @Test
    void tidalStretch() {
        Field f = Field.of(Body.planet("p", 0, 0, 0, MU, 5));
        assertEquals(2 * MU * 1.8 / (50.0 * 50 * 50), f.tidal(50, 0, 0), 1e-12);
        Field hole = Field.of(Body.hole("bh", 0, 0, 0, 100, 3));
        assertTrue(hole.tidal(0, 0, 3.2) > Units.TIDAL_DAMAGE_THRESHOLD);  // у самого горизонта рвёт
        assertTrue(hole.tidal(0, 0, 100) < Units.TIDAL_DAMAGE_THRESHOLD);
    }

    @Test
    void dominantBody() {
        Body p = Body.planet("p", 0, 0, 0, MU, 5);
        Body m = Body.planet("m", 100, 0, 0, 10, 2);
        Field f = Field.of(p, m);
        assertSame(p, f.dominant(30, 0, 0));
        assertSame(m, f.dominant(97, 0, 0));
        assertNull(Field.EMPTY.dominant(0, 0, 0));
    }

    @Test
    void dynamicalTime() {
        Field f = Field.of(Body.planet("p", 0, 0, 0, MU, 5));
        assertEquals(Math.sqrt(50.0 * 50 * 50 / MU), f.dynamicalTime(50, 0, 0), 1e-12);
        assertEquals(Double.POSITIVE_INFINITY, Field.EMPTY.dynamicalTime(0, 0, 0));
    }
}
