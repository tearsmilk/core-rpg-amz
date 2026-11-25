package ink.hoka.odyssey.client.spaceflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import java.util.Random;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Камера: игра строит поворот как {@code rotationYXZ(π − yaw, −pitch, roll)} (Camera.setRotation + крен ядра) и
 * смотрит вдоль −Z; FreeLook — Ry(−yaw)·Rx(pitch)·Rz(roll), взгляд +Z. Доказываем Qcam = Qlook·Ry(π) с roll камеры
 * = −roll FreeLook — на случайных углах и на разложении произвольных ориентаций (петли, «вверх ногами»).
 */
class CameraConventionTest {
    private static final double EPS = 1e-5;

    private static Quaternionf joml(Quat q) {
        return new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w());
    }

    /** Совпадение поворотов с точностью до знака кватерниона. */
    private static void assertSameRotation(Quaternionf expected, Quaternionf actual) {
        double d = Math.abs(expected.x * actual.x + expected.y * actual.y + expected.z * actual.z
                + expected.w * actual.w);
        assertEquals(1, d, EPS, () -> expected + " vs " + actual);
    }

    private static Quaternionf game(double yawDeg, double pitchDeg, double camRollDeg) {
        return new Quaternionf().rotationYXZ((float) (Math.PI - Math.toRadians(yawDeg)),
                (float) -Math.toRadians(pitchDeg), (float) Math.toRadians(camRollDeg));
    }

    @Test
    void cameraEqualsGameRotationForRandomAngles() {
        Random r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            double yaw = r.nextDouble() * 360 - 180;
            double pitch = r.nextDouble() * 180 - 90;
            double roll = r.nextDouble() * 360 - 180;
            Quat look = Quat.fromYawPitchRoll(yaw, pitch, roll);
            assertSameRotation(game(yaw, pitch, -roll), joml(BodyFrame.camera(look)));
        }
    }

    @Test
    void cameraLooksAlongLookAndKeepsUp() {
        Random r = new Random(2);
        for (int i = 0; i < 500; i++) {
            Quat look = randomOrientation(r);
            Quaternionf cam = joml(BodyFrame.camera(look));
            Vector3f fwd = cam.transform(new Vector3f(0, 0, -1));    // камера игры смотрит вдоль −Z
            Vector3f up = cam.transform(new Vector3f(0, 1, 0));
            Vec3d f = look.forward(), u = look.up();
            assertEquals(f.x(), fwd.x, EPS);
            assertEquals(f.y(), fwd.y, EPS);
            assertEquals(f.z(), fwd.z, EPS);
            assertEquals(u.x(), up.x, EPS);
            assertEquals(u.y(), up.y, EPS);
            assertEquals(u.z(), up.z, EPS);
        }
    }

    /** Углы из FreeLook.decompose (их модуль тоже кладёт в CameraState) дают тот же поворот, что и кватернион. */
    @Test
    void decomposedAnglesMatchQuaternion() {
        Random r = new Random(3);
        FreeLook look = new FreeLook();
        for (int i = 0; i < 2000; i++) {
            look.set(randomOrientation(r));
            look.decompose();
            assertTrue(look.pitch >= -90 && look.pitch <= 90);
            Quaternionf fromAngles = game(look.yaw, look.pitch, -look.roll);
            assertSameRotation(fromAngles, joml(BodyFrame.camera(look.orientation())));
        }
    }

    /** F5 «лицом к себе»: игра берёт yaw + 180, −pitch — это Qcam·Ry(π) = Qlook (крен — тот же, что у FreeLook). */
    @Test
    void mirroredThirdPersonIsLookItself() {
        Random r = new Random(4);
        for (int i = 0; i < 500; i++) {
            double yaw = r.nextDouble() * 360 - 180, pitch = r.nextDouble() * 180 - 90, roll = r.nextDouble() * 360 - 180;
            Quat look = Quat.fromYawPitchRoll(yaw, pitch, roll);
            assertSameRotation(game(yaw + 180, -pitch, roll), joml(look));
        }
    }

    static Quat randomOrientation(Random r) {
        double w = r.nextGaussian(), x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian();
        return new Quat(w, x, y, z).normalize();
    }
}
