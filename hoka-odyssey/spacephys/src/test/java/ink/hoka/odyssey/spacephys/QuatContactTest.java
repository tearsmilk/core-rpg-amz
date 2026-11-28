package ink.hoka.odyssey.spacephys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class QuatContactTest {

    @Test
    void forwardMatchesMinecraftLookAngle() {
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            double yaw = rnd.nextDouble() * 360 - 180, pitch = rnd.nextDouble() * 180 - 90;
            Quat q = Quat.fromYawPitchRoll(yaw, pitch, rnd.nextDouble() * 360);
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            Vec3d look = new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
            Vec3d f = q.forward();
            assertEquals(look.x(), f.x(), 1e-12);
            assertEquals(look.y(), f.y(), 1e-12);
            assertEquals(look.z(), f.z(), 1e-12);
            // базис ортонормирован и правый: right = forward × up
            Vec3d r = q.right(), u = q.up();
            assertEquals(1, r.length(), 1e-12);
            assertEquals(0, f.dot(u), 1e-12);
            assertEquals(0, f.cross(u).sub(r).length(), 1e-12);
        }
    }

    @Test
    void rollClockwiseTiltsUpToTheRight() {
        Quat q = Quat.fromYawPitchRoll(0, 0, 30);
        assertTrue(q.up().dot(Quat.RIGHT) > 0.49);             // макушка ушла вправо
        assertEquals(0, q.forward().sub(Quat.FORWARD).length(), 1e-12);
    }

    @Test
    void axisAngleComposition() {
        Quat a = Quat.axisAngle(Quat.UP, Math.PI / 2);
        Vec3d v = a.mul(a).rotate(new Vec3d(1, 0, 0));
        assertEquals(-1, v.x(), 1e-12);
        assertEquals(1, a.normalize().norm(), 1e-15);
        Vec3d back = a.conjugate().rotate(a.rotate(new Vec3d(0.3, -2, 5)));
        assertEquals(0, back.sub(new Vec3d(0.3, -2, 5)).length(), 1e-12);
    }

    @Test
    void bounceWithRestitutionAndNoFriction() {
        Contact.Result r = Contact.collide(new Vec3d(3, -10, 0), new Vec3d(0, 1, 0));
        assertEquals(10, r.impactSpeed(), 1e-12);
        assertEquals(12, r.deltaVNormal(), 1e-12);
        assertEquals(2, r.velocity().y(), 1e-12);              // e = 0,2
        assertEquals(3, r.velocity().x(), 1e-12);              // без трения касательная не меняется
        assertTrue(r.damaging());                              // |Δv_n| = 12 > 8 б/с
        Contact.Result soft = Contact.collide(new Vec3d(0, -7, 0), new Vec3d(0, 1, 0));
        assertTrue(soft.damaging());                           // |v_n| = 7, но |Δv_n| = 8,4 > 8 (порог по Δv_n)
        assertFalse(Contact.collide(new Vec3d(0, -6, 0), new Vec3d(0, 1, 0)).damaging()); // 7,2 < 8
        assertEquals(0.4 * 2, Contact.impactDamage(soft, 2), 1e-12);
        assertEquals(4, Contact.impactDamage(10, 8, 2), 1e-12);
        assertEquals(0, Contact.impactDamage(5, 8, 2), 0);
    }

    @Test
    void frictionOnlyOnContact() {
        Contact.Result r = Contact.collide(new Vec3d(1, -1, 0), new Vec3d(0, 2, 0), 0, 0.5);
        assertEquals(0.5, r.velocity().x(), 1e-12);            // Δv_t = μ·Δv_n = 0,5
        assertEquals(0, r.velocity().y(), 1e-12);
        Contact.Result stick = Contact.collide(new Vec3d(0.1, -1, 0), new Vec3d(0, 1, 0), 0, 1);
        assertEquals(0, stick.velocity().x(), 0);              // не разворачивает касательную
        Contact.Result away = Contact.collide(new Vec3d(1, 1, 0), new Vec3d(0, 1, 0), 0.2, 1);
        assertEquals(new Vec3d(1, 1, 0), away.velocity());     // удаляемся — контакта нет
        assertFalse(away.damaging());
    }
}
