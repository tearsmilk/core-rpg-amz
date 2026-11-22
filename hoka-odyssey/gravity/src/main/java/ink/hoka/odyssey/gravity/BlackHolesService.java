package ink.hoka.odyssey.gravity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Реализация {@link BlackHoles}: дыры — из текущих настроек модуля, подписчики — до выключения gravity. */
final class BlackHolesService implements BlackHoles {
    private final Supplier<Settings> settings;
    private final Consumer<String> warn;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    BlackHolesService(Supplier<Settings> settings, Consumer<String> warn) {
        this.settings = settings;
        this.warn = warn;
    }

    @Override
    public List<Hole> holes() {
        Settings s = settings.get();
        return s == null ? List.of() : list(s);
    }

    static List<Hole> list(Settings s) {
        List<Hole> out = new ArrayList<>();
        s.space().holes().forEach((map, l) -> {
            for (BlackHole h : l) {
                HoleDisk d = h.disk();
                out.add(new Hole(map, h.name(), h.x(), h.y(), h.z(), h.horizon(), d.tilt(), d.yaw(), d.size(), d.spin(),
                    d.brightness(), d.temp()));
            }
        });
        return List.copyOf(out);
    }

    @Override
    public Runnable onChange(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Настройки перечитаны — сообщить подписчикам (в тике сервера). */
    void changed() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException | LinkageError e) {
                warn.accept("подписчик на чёрные дыры: " + e);
            }
        }
    }

    void clear() {
        listeners.clear();
    }
}
