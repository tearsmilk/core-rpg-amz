package ink.hoka.odyssey.client.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class MathTest {
    private static final List<Proto.Key> PITCH = List.of(new Proto.Key(0, 0.8f, 0.8f), new Proto.Key(0.1f, 1.4f, 1.4f),
            new Proto.Key(0.15f, 0.8f, 0.8f), new Proto.Key(0.45f, -0.175f, -0.175f), new Proto.Key(0.6f, 0, 0));

    @Test
    void splinePassesThroughKeysAndReturnsToZero() {
        RecoilSpline s = new RecoilSpline();
        s.shot(PITCH, 1, 1000, new Random(1));
        assertEquals(0, s.value(0), 1e-6);
        assertEquals(0.8, s.value(30), 1e-6);
        assertEquals(1.4, s.value(130), 1e-6);
        assertEquals(-0.175, s.value(480), 1e-6);
        // сумма приращений по кадрам = значению в конце (0): камера возвращается
        double sum = 0;
        for (long t = 1000; t <= 1700; t += 16) sum += s.delta(t);
        assertEquals(0, sum, 1e-6);
    }

    @Test
    void fullAutoClimbs() {
        RecoilSpline s = new RecoilSpline();
        Random r = new Random(2);
        double sum = 0;
        long t = 0;
        for (int shot = 0; shot < 10; shot++) {
            s.shot(PITCH, 1, t, r);
            for (int f = 0; f < 6; f++) {
                t += 100 / 6;
                sum += s.delta(t);
            }
            t = (shot + 1) * 100L;
        }
        assertTrue(sum > 8 && sum < 16, "подъём за 10 выстрелов: " + sum);
    }

    @Test
    void adsBlendEndsExactly() {
        Matrix4f a = new Matrix4f().translation(1, 2, 3).rotateZ(0.3f);
        Matrix4f b = new Matrix4f().translation(-1, 0, 5);
        Vector3f p = new Vector3f(0.5f, 0.5f, 0.5f);
        assertTrue(AdsBlend.blend(a, b, 0).transformPosition(new Vector3f(p)).distance(a.transformPosition(new Vector3f(p))) < 1e-5);
        assertTrue(AdsBlend.blend(a, b, 1).transformPosition(new Vector3f(p)).distance(b.transformPosition(new Vector3f(p))) < 1e-5);
        float prev = -1;
        for (float x = 0; x <= 1; x += 0.05f) {                 // плавности монотонны
            float v = AdsBlend.easeInOutCubic(x);
            assertTrue(v >= prev - 1e-6);
            prev = v;
        }
    }

    @Test
    void viewmodelFovLooksTheSameAtAnyWorldFov() {
        assertEquals(1, ViewModel.fovScale(70, 70), 1e-6);
        assertEquals(1, ViewModel.fovScale(110, 0), 1e-6);                 // 0 — угол мира, как было
        // точка камеры (x, y, z): на экране x/(−z·tan(fov/2)). С углом мира 110 и сжатием k — как с углом 70.
        for (double world : new double[] {50, 70, 90, 110}) {
            float k = ViewModel.fovScale(world, 70);
            Vector3f p = ViewModel.fovMatrix(k).transformPosition(new Vector3f(0.1f, -0.2f, -0.5f));
            double screenWorld = p.x / (0.5 * Math.tan(Math.toRadians(world) / 2));
            double screen70 = 0.1 / (0.5 * Math.tan(Math.toRadians(70) / 2));
            assertEquals(screen70, screenWorld, 1e-5);
            assertEquals(-0.5f, p.z, 1e-6);                                  // глубина та же — стены как были
            Vector3f c = ViewModel.fovMatrix(k).transformPosition(new Vector3f(0, 0, -0.3f));
            assertEquals(0, c.x, 0);                                         // центр (прицел в ADS) на месте
            assertEquals(0, c.y, 0);
        }
        assertTrue(ViewModel.fovScale(110, 70) > 1.9f);                      // при 110 модель без сжатия вдвое меньше
    }
}
