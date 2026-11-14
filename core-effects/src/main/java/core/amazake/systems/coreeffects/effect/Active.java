package core.amazake.systems.coreeffects.effect;

/**
 * Состояние одного эффекта у игрока. Меняется только в потоке игрока (через {@link EffectBook}, под его замком).
 *
 * <ul>
 *   <li>{@code remaining} — осталось тиков, -1 — бесконечно;</li>
 *   <li>{@code elapsed} — тиков с наложения (для периодов: урон, лечение);</li>
 *   <li>{@code a}, {@code b} — свои у каждого эффекта: кровотечение — тиков без перевязки и тиков обморока;
 *       тепло — тиков без питья и последняя ступень предупреждения; проклятье — тиков до следующей потери.</li>
 * </ul>
 */
public final class Active {

    long remaining;
    long elapsed;
    long a;
    long b;

    Active(final long remaining, final long elapsed, final long a, final long b) {
        this.remaining = remaining;
        this.elapsed = elapsed;
        this.a = a;
        this.b = b;
    }

    public long remaining() {
        return this.remaining;
    }

    public long elapsed() {
        return this.elapsed;
    }

    public long a() {
        return this.a;
    }

    public long b() {
        return this.b;
    }

    public void setA(final long a) {
        this.a = a;
    }

    public void setB(final long b) {
        this.b = b;
    }

    public boolean infinite() {
        return this.remaining < 0;
    }

    Active copy() {
        return new Active(this.remaining, this.elapsed, this.a, this.b);
    }
}
