package core.amazake.systems.coreeffects.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Эффекты одного игрока. Пишет только поток игрока; читать (PlaceholderAPI, события чужих регионов) можно из любого
 * потока — все методы под замком объекта, наружу отдаются копии.
 *
 * <p>Хранение в PDC игрока — строка {@link #encode()}: {@code 1|bleeding:1200:40:900:0|panic:-1:0:0:0}
 * (тип:осталось:прошло:a:b). Разбор {@link #decode} пропускает незнакомое и битое.</p>
 */
public final class EffectBook {

    public static final String VERSION = "1";

    private final EnumMap<EffectType, Active> active = new EnumMap<>(EffectType.class);

    /**
     * Наложить или продлить: если эффект уже есть, остаётся большая длительность (бесконечность — больше всего),
     * счётчики не сбрасываются. {@code ticks} — тики или -1.
     *
     * @return true — эффект новый
     */
    public synchronized boolean give(final EffectType type, final long ticks, final long initialA) {
        final Active cur = this.active.get(type);
        if (cur == null) {
            this.active.put(type, new Active(ticks, 0, initialA, 0));
            return true;
        }
        if (ticks < 0 || (cur.remaining >= 0 && ticks > cur.remaining)) {
            cur.remaining = ticks;
        }
        return false;
    }

    public synchronized boolean remove(final EffectType type) {
        return this.active.remove(type) != null;
    }

    public synchronized List<EffectType> clear() {
        final List<EffectType> was = new ArrayList<>(this.active.keySet());
        this.active.clear();
        return was;
    }

    public synchronized boolean has(final EffectType type) {
        return this.active.containsKey(type);
    }

    public synchronized boolean isEmpty() {
        return this.active.isEmpty();
    }

    /** Копия состояния (или null). */
    public synchronized Active get(final EffectType type) {
        final Active a = this.active.get(type);
        return a == null ? null : a.copy();
    }

    /** Копии всех активных, в порядке {@link EffectType}. */
    public synchronized Map<EffectType, Active> snapshot() {
        final EnumMap<EffectType, Active> copy = new EnumMap<>(EffectType.class);
        this.active.forEach((t, a) -> copy.put(t, a.copy()));
        return copy;
    }

    /**
     * Работа с живым состоянием под замком (только поток игрока): {@code body} получает сам объект и может менять
     * {@code a}/{@code b}.
     */
    public synchronized void edit(final EffectType type, final java.util.function.Consumer<Active> body) {
        final Active a = this.active.get(type);
        if (a != null) {
            body.accept(a);
        }
    }

    /**
     * Шаг времени на {@code ticks}: у каждого эффекта растёт {@code elapsed}, уменьшается {@code remaining}. Кончившиеся
     * удаляются и возвращаются.
     */
    public synchronized List<EffectType> advance(final long ticks) {
        final List<EffectType> ended = new ArrayList<>(0);
        final var it = this.active.entrySet().iterator();
        while (it.hasNext()) {
            final var e = it.next();
            final EffectType type = e.getKey();
            final Active a = e.getValue();
            a.elapsed += ticks;
            if (a.remaining >= 0) {
                a.remaining -= ticks;
                if (a.remaining <= 0) {
                    it.remove();
                    ended.add(type);
                }
            }
        }
        return ended;
    }

    public synchronized String encode() {
        final StringBuilder sb = new StringBuilder(VERSION);
        this.active.forEach((t, a) -> sb.append('|').append(t.id()).append(':').append(a.remaining).append(':')
            .append(a.elapsed).append(':').append(a.a).append(':').append(a.b));
        return sb.toString();
    }

    /** Разобрать строку {@link #encode()}; битое и незнакомое пропускается, о каждом — {@code warn}. */
    public static EffectBook decode(final String raw, final BiConsumer<String, String> warn) {
        final EffectBook book = new EffectBook();
        if (raw == null || raw.isBlank()) {
            return book;
        }
        final String[] parts = raw.split("\\|");
        if (!VERSION.equals(parts[0])) {
            warn.accept(raw, "неизвестная версия «" + parts[0] + "»");
            return book;
        }
        for (int i = 1; i < parts.length; i++) {
            final String[] f = parts[i].split(":");
            final EffectType type = f.length == 5 ? EffectType.parse(f[0]) : null;
            if (type == null) {
                warn.accept(parts[i], "не эффект");
                continue;
            }
            try {
                final long remaining = Long.parseLong(f[1]);
                final long elapsed = Long.parseLong(f[2]);
                if (remaining == 0 || remaining < -1 || elapsed < 0) {
                    warn.accept(parts[i], "неверное время");
                    continue;
                }
                book.active.put(type, new Active(remaining, elapsed, Long.parseLong(f[3]), Long.parseLong(f[4])));
            } catch (final NumberFormatException e) {
                warn.accept(parts[i], "не число");
            }
        }
        return book;
    }
}
