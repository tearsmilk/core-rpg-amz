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
}
