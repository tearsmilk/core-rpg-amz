package ink.hoka.odyssey.client.guns;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Смесь положения оружия «от бедра» и «в прицеле». Обе матрицы — жёсткие (сдвиг + поворот): сдвиг смешивается по
 * осям со своими плавностями, поворот — сферически. Плавности подобраны «на глаз» как у пресета renew в TACZ:
 * вбок (x) — быстрый старт, вверх (y) — плавно и успевает к 85 % времени, вперёд (z) — с задержкой 20 %, поворот —
 * синусом. Чистая математика.
 */
final class AdsBlend {
    private AdsBlend() {
    }

    static Matrix4f blend(Matrix4f hip, Matrix4f ads, float p) {
        p = clamp01(p);
        if (p <= 0) return new Matrix4f(hip);
        if (p >= 1) return new Matrix4f(ads);
        Vector3f t0 = hip.getTranslation(new Vector3f());
        Vector3f t1 = ads.getTranslation(new Vector3f());
        Quaternionf q0 = hip.getNormalizedRotation(new Quaternionf());
        Quaternionf q1 = ads.getNormalizedRotation(new Quaternionf());
        float wx = easeOutCubic(p);
        float wy = smoothstep(clamp01(p / 0.85f));
        float wz = easeInOutCubic(clamp01((p - 0.2f) / 0.8f));
        float wr = easeOutSine(p);
        Vector3f t = new Vector3f(lerp(t0.x, t1.x, wx), lerp(t0.y, t1.y, wy), lerp(t0.z, t1.z, wz));
        Quaternionf q = new Quaternionf(q0).slerp(q1, wr);
        return new Matrix4f().translation(t).rotate(q);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    static float easeOutCubic(float p) {
        float q = 1 - p;
        return 1 - q * q * q;
    }

    static float smoothstep(float p) {
        return p * p * (3 - 2 * p);
    }

    static float easeInOutCubic(float p) {
        return p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2;
    }

    static float easeOutSine(float p) {
        return (float) Math.sin(p * Math.PI / 2);
    }
}
