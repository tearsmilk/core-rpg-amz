package ink.hoka.odyssey.spacesky;

import ink.hoka.odyssey.api.Services;
import ink.hoka.odyssey.gravity.BlackHoles;
import java.util.ArrayList;
import java.util.List;

/**
 * Мост к модулю gravity (softdepends): все обращения к его классам — только здесь. Без gravity на сервере класс
 * BlackHoles не найдётся — вызывающий ловит {@link LinkageError} и считает, что дыр нет.
 */
final class GravityHoles {
    private GravityHoles() {
    }

    /** Дыры gravity в единицах шейдера (пусто — gravity нет или дыр нет). */
    static List<Holes.Hole> read(Services services) {
        List<Holes.Hole> out = new ArrayList<>();
        services.get(BlackHoles.class).ifPresent(s -> {
            for (BlackHoles.Hole h : s.holes()) {
                Holes.Hole x = Holes.of(h.map(), h.name(), h.x(), h.y(), h.z(), h.horizon(), h.diskTilt(), h.diskYaw(),
                    h.diskSize(), h.diskSpin(), h.diskBrightness(), h.diskTemp());
                if (x != null) {
                    out.add(x);
                }
            }
        });
        return out;
    }

    /** Подписаться на изменения дыр; результат — отписка (null — gravity нет). */
    static Runnable subscribe(Services services, Runnable listener) {
        return services.get(BlackHoles.class).map(s -> s.onChange(listener)).orElse(null);
    }
}
