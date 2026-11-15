package core.amazake.systems.coreweapons.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Кадры замаха: строка custom_model_data и сколько тиков её держать. */
public record Frames(List<String> names, List<Integer> ticks) {

    /** Метки кадров всех систем начинаются так — по ней снимаются хвосты (выход, сбой, выключение). */
    public static final String PREFIX = "cw_swing_";

    public Frames {
        names = List.copyOf(names);
        ticks = List.copyOf(ticks);
    }

    /** «имя:тиков» (тиков по умолчанию 2). Имена без префикса {@link #PREFIX} не принимаются. */
    public static Frames parse(final List<String> raw, final Consumer<String> warn) {
        final List<String> names = new ArrayList<>();
        final List<Integer> ticks = new ArrayList<>();
        for (final String r : raw) {
            final String[] p = r.trim().split(":");
            final String name = p[0].trim();
            if (!name.startsWith(PREFIX)) {
                warn.accept("«" + r + "»: имя кадра должно начинаться с " + PREFIX);
                continue;
            }
            int t = 2;
            if (p.length > 1) {
                try {
                    t = Integer.parseInt(p[1].trim());
                } catch (NumberFormatException e) {
                    warn.accept("«" + r + "»: тики не число, взято 2");
                }
            }
            names.add(name);
            ticks.add(Math.max(1, Math.min(20, t)));
        }
        return new Frames(names, ticks);
    }

    public int total() {
        int n = 0;
        for (final int t : this.ticks) {
            n += t;
        }
        return n;
    }

    /** Кадр на тике {@code age} от начала замаха; null — замах кончился (вернуть основную модель). */
    public String at(final int age) {
        if (age < 0) {
            return null;
        }
        int acc = 0;
        for (int i = 0; i < this.names.size(); i++) {
            acc += this.ticks.get(i);
            if (age < acc) {
                return this.names.get(i);
            }
        }
        return null;
    }

    /** Доля пройденного замаха 0..1 на тике {@code age}. */
    public double progress(final double age) {
        final int t = this.total();
        return t == 0 ? 1.0 : Math.max(0.0, Math.min(1.0, age / t));
    }
}
