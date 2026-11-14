package core.amazake.systems.coremobs.util;

import java.util.Map;
import java.util.random.RandomGenerator;

/** Случайный выбор ключа по весам. */
public final class Weighted {

    private Weighted() {
    }

    /** Ключ с вероятностью вес/сумма; null, если весов нет или все ≤ 0. */
    public static <K> K pick(final Map<K, Integer> weights, final RandomGenerator random) {
        long total = 0;
        for (final int w : weights.values()) {
            if (w > 0) {
                total += w;
            }
        }
        if (total <= 0) {
            return null;
        }
        long roll = random.nextLong(total);
        for (final Map.Entry<K, Integer> e : weights.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            roll -= e.getValue();
            if (roll < 0) {
                return e.getKey();
            }
        }
        return null;
    }
}
