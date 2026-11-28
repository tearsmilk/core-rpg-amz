package ink.hoka.odyssey.spacephys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThrustTest {
    private static final double DT = Units.TICK;

    @Test
    void forwardThrustUsesOrientationAndBurnsFuel() {
        Thrust t = new Thrust();
        Quat q = Quat.fromYawPitchRoll(90, 0, 0);              // взгляд на −X (запад)
        Vec3d a = t.accelerate(1, 0, 0, q, DT);
        assertEquals(-2.0, a.x(), 1e-12);
        assertEquals(0, a.y(), 1e-12);
        assertEquals(0, a.z(), 1e-12);
        assertEquals(60 - 2.0 * DT, t.fuel(), 1e-12);
    }

    @Test
    void diagonalIsNotFaster() {
        Thrust t = new Thrust(Thrust.Spec.unlimited(2));
        Vec3d a = t.accelerate(1, 1, 1, Quat.IDENTITY, DT);
        assertEquals(2.0, a.length(), 1e-12);
        assertEquals(Double.POSITIVE_INFINITY, t.fuel());
        assertEquals(1, t.fuelFraction(), 0);
    }

    @Test
    void fuelRunsOutAfterCapacity() {
        Thrust t = new Thrust();
        double dv = 0;
        for (int i = 0; i < 1000; i++) {                       // 50 с при 2 б/с² — больше, чем 60 б/с запаса
            dv += t.accelerate(0, 0, 1, Quat.IDENTITY, DT).length() * DT;
        }
        assertEquals(Units.DELTA_V_CAPACITY, dv, 1e-9);         // ровно запас Δv, не больше
        assertTrue(t.isEmpty());
        assertEquals(Vec3d.ZERO, t.accelerate(1, 0, 0, Quat.IDENTITY, DT));
        t.refill(10);
        assertEquals(10, t.fuel(), 0);
        t.refill();
        assertEquals(60, t.fuel(), 0);
        t.setFuel(1000);
        assertEquals(60, t.fuel(), 0);
    }

    @Test
    void sasStopsWithoutOvershoot() {
        Thrust t = new Thrust();
        State s = new State(0, 0, 0, 3, -1, 0.5);
        for (int i = 0; i < 200; i++) {
            Vec3d a = t.control(0, 0, 0, Quat.IDENTITY, true, s.velocity(), DT);
            Integrator.DEFAULT.step(Field.EMPTY, s, DT, a);
        }
        assertEquals(0, s.speed(), 1e-12);
        double v0 = Math.sqrt(9 + 1 + 0.25);
        assertEquals(60 - v0, t.fuel(), 1e-9);                   // SAS тоже тратит Δv
        assertEquals(Vec3d.ZERO, t.control(0, 0, 0, Quat.IDENTITY, false, new Vec3d(1, 0, 0), DT));
    }

    @Test
    void manualInputOverridesSas() {
        Thrust t = new Thrust();
        Vec3d a = t.control(0, 0, 1, Quat.IDENTITY, true, new Vec3d(5, 0, 0), DT);
        assertEquals(2.0, a.y(), 1e-12);
        assertEquals(0, a.x(), 1e-12);
    }

    @Test
    void specValidation() {
        assertTrue(Thrust.Spec.unlimited(1).isUnlimited());
        assertFalse(Thrust.Spec.DEFAULT.isUnlimited());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new Thrust.Spec(-1, 1));
    }
}
