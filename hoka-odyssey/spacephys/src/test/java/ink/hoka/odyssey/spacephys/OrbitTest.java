package ink.hoka.odyssey.spacephys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrbitTest {
    private static final double MU = Units.muFromOrbit(50, 60);
    private static final Body P = Body.planet("p", 0, 64, 0, MU, 5);

    @Test
    void circularElements() {
        double v = Orbit.circularSpeed(MU, 50);
        Orbit.Elements el = Orbit.elements(P, new State(50, 64, 0, 0, 0, -v));
        assertTrue(el.bound());
        assertEquals(0, el.eccentricity(), 1e-12);
        assertEquals(50, el.semiMajorAxis(), 1e-9);
        assertEquals(50, el.periapsis(), 1e-9);
        assertEquals(50, el.apoapsis(), 1e-9);
        assertEquals(60, el.period(), 1e-9);
        assertEquals(0, el.inclination(), 1e-12);             // плоскость XZ, обход против часовой сверху
        assertEquals(Math.PI, Orbit.elements(P, new State(50, 64, 0, 0, 0, v)).inclination(), 1e-12);
    }

    @Test
    void ellipticElements() {
        double v = 0.8 * Orbit.circularSpeed(MU, 50);
        Orbit.Elements el = Orbit.elements(new Vec3d(50, 0, 0), new Vec3d(0, 0, v), MU);
        assertEquals(0.36, el.eccentricity(), 1e-12);
        assertEquals(50, el.apoapsis(), 1e-9);                // стартуем из апоцентра
        assertEquals(50 * 0.64 / 1.36, el.periapsis(), 1e-9);
        assertEquals((el.apoapsis() + el.periapsis()) / 2, el.semiMajorAxis(), 1e-9);
    }

    @Test
    void escapeAndHyperbola() {
        Orbit.Elements par = Orbit.elements(new Vec3d(50, 0, 0), new Vec3d(0, Orbit.escapeSpeed(MU, 50), 0), MU);
        assertEquals(1, par.eccentricity(), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, par.apoapsis());
        assertEquals(Double.POSITIVE_INFINITY, par.period());
        Orbit.Elements hyp = Orbit.elements(new Vec3d(50, 0, 0), new Vec3d(0, 10, 0), MU);
        assertFalse(hyp.bound());
        assertTrue(hyp.eccentricity() > 1 && hyp.semiMajorAxis() < 0);
        assertEquals(50, hyp.periapsis(), 1e-9);
    }

    @Test
    void numericOrbitMatchesElements() {
        Field f = Field.of(P);
        State s = new State(50, 64, 0, 0, 0, 0.8 * Orbit.circularSpeed(MU, 50));
        Orbit.Elements el = Orbit.elements(P, s);
        double minR = Double.MAX_VALUE, maxR = 0;
        for (int i = 0; i < (int) (el.period() / Units.TICK) + 1; i++) {
            Integrator.DEFAULT.step(f, s, Units.TICK);
            double r = P.distance(s.x, s.y, s.z);
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
        }
        assertEquals(el.periapsis(), minR, 0.01 * el.periapsis());
        assertEquals(el.apoapsis(), maxR, 0.01 * el.apoapsis());
    }

    @Test
    void predictionMatchesIntegratorAndKeepsStart() {
        Field f = Field.of(P);
        State s = new State(50, 64, 0, 0, 0, Orbit.circularSpeed(MU, 50));
        State before = s.copy();
        Orbit.Prediction pr = Orbit.predict(f, s, 600, Units.TICK, 10);  // 30 с для HUD
        assertEquals(61, pr.count());
        assertNull(pr.captured());
        assertEquals(before.x, s.x, 0);                       // старт не меняется
        assertEquals(50, pr.point(0).x(), 0);
        State manual = before.copy();
        for (int i = 0; i < 600; i++) {
            Integrator.DEFAULT.step(f, manual, Units.TICK);
        }
        assertEquals(manual.x, pr.last().x(), 0);
        assertEquals(manual.z, pr.last().z(), 0);
        assertEquals(-50, pr.last().x(), 0.05);               // за полпериода — на другой стороне
    }

    @Test
    void predictionStopsAtHorizon() {
        Body hole = Body.hole("bh", 0, 0, 0, 500, 3);
        Orbit.Prediction pr = Orbit.predict(Field.of(hole), new State(0, 0, 30, 0, 0, 0), 10_000, Units.TICK, 50);
        assertSame(hole, pr.captured());
        assertEquals(3, pr.last().length(), 1e-9);
        assertTrue(pr.count() < 10_000 / 50 + 2);
    }

    @Test
    void holeCircularOrbitIsStable() {
        double rs = 3, mu = 500, r = 30;                       // r > ISCO = 9
        Body hole = Body.hole("bh", 0, 0, 0, mu, rs);
        Field f = Field.of(hole);
        State s = new State(r, 0, 0, 0, 0, Orbit.circularSpeedHole(mu, rs, r));
        double maxErr = 0;
        for (int i = 0; i < 50_000; i++) {
            assertNull(Integrator.DEFAULT.step(f, s, Units.TICK));
            maxErr = Math.max(maxErr, Math.abs(hole.distance(s.x, s.y, s.z) - r) / r);
        }
        assertTrue(maxErr < 0.005, "круговая орбита у дыры уплыла: " + maxErr);
        assertTrue(Orbit.escapeSpeedHole(mu, rs, r) > Orbit.escapeSpeed(mu, r)); // у дыры тянет сильнее Ньютона
    }
}
