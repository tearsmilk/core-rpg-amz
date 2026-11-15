package core.amazake.systems.corevault.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;

/** Топ богатых и топ уровней: пересчитывается вне потоков тика, читается из любого потока (неизменяемый снимок). */
public final class TopCache {

    public record Entry(UUID id, String name, long value) {
    }

    public record Snapshot(List<Entry> money, List<Entry> levels, long computedAt) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), 0);
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public Snapshot get() {
        return this.snapshot;
    }

    /**
     * Пересчитать. {@code balance} — баланс по UUID (свой или внешнего провайдера); уровни — «уровень × 2^32 +
     * опыт», чтобы при равном уровне выше стоял тот, у кого больше опыта.
     */
    public Snapshot compute(final Collection<Account> accounts, final ToLongFunction<UUID> balance, final int size) {
        final List<Entry> money = new ArrayList<>(accounts.size());
        final List<Entry> levels = new ArrayList<>(accounts.size());
        for (final Account a : accounts) {
            final Account.Snapshot s = a.snapshot();
            final String name = s.name().isEmpty() ? s.id().toString().substring(0, 8) : s.name();
            long b;
            try {
                b = balance.applyAsLong(s.id());
            } catch (final RuntimeException e) {
                b = 0;
            }
            money.add(new Entry(s.id(), name, b));
            levels.add(new Entry(s.id(), name, ((long) s.level() << 32) + Math.min(s.exp(), 0xFFFFFFFFL)));
        }
        final Comparator<Entry> order = Comparator.comparingLong(Entry::value).reversed().thenComparing(Entry::name);
        money.sort(order);
        levels.sort(order);
        final Snapshot out = new Snapshot(List.copyOf(money.subList(0, Math.min(size, money.size()))),
            List.copyOf(levels.subList(0, Math.min(size, levels.size()))), System.currentTimeMillis());
        this.snapshot = out;
        return out;
    }

    public static int levelOf(final Entry e) {
        return (int) (e.value() >>> 32);
    }
}
