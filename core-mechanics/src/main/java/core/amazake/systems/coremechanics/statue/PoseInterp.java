package core.amazake.systems.coremechanics.statue;

import java.util.List;

/** Интерполяция кадров анимации (перенесено из DollStudio). Чистая математика. */
public final class PoseInterp {

    private PoseInterp() {
    }

    /** Поза между кадрами по кругу: кадр длится ticks тиков, переход — плавный (smoothstep). */
    public static float[] interp(final List<float[]> frames, final int ticks, final int t) {
        final int n = frames.size();
        final int total = ticks * n;
        final int tt = Math.floorMod(t, total);
        final int i = tt / ticks;
        float u = (tt % ticks) / (float) ticks;
        u = u * u * (3 - 2 * u);
        final float[] a = frames.get(i), b = frames.get((i + 1) % n);
        final float[] out = new float[a.length];
        for (int k = 0; k < a.length; k++) {
            out[k] = a[k] + (b[k] - a[k]) * u;
        }
        return out;
    }

    /**
     * Шаг сглаживания текущей позы к цели (cur += (target − cur)·k). true — что-то изменилось (больше 0.001).
     */
    public static boolean approach(final float[] cur, final float[] target, final float k) {
        boolean changed = false;
        for (int i = 0; i < cur.length; i++) {
            final float d = target[i] - cur[i];
            if (Math.abs(d) > 1e-3) {
                cur[i] += d * k;
                changed = true;
            }
        }
        return changed;
    }
}
