package core.amazake.systems.corevault.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Счёт игрока: баланс (минорные единицы), уровень, опыт внутри уровня, уровни с невыданными наградами. Все поля —
 * под замком самого объекта: Vault, команды, события и PlaceholderAPI приходят из разных потоков.
 */
public final class Account {

    private final UUID id;
    private volatile String name;
    private long balance;
    private int level;
    private long exp;
    private final List<Integer> pendingRewards = new ArrayList<>();

    public Account(final UUID id, final String name, final long balance, final int level, final long exp) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.balance = balance;
        this.level = level;
        this.exp = exp;
    }

    public UUID id() {
        return this.id;
    }

    public String name() {
        return this.name;
    }

    public void name(final String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
    }

    public synchronized long balance() {
        return this.balance;
    }

    /** Только для хранилища и под замком вызывающего (транзакции). */
    public synchronized void balance(final long v) {
        this.balance = v;
    }

    public synchronized int level() {
        return this.level;
    }

    public synchronized long exp() {
        return this.exp;
    }

    public synchronized void levelExp(final int level, final long exp) {
        this.level = level;
        this.exp = exp;
    }

    public synchronized List<Integer> pendingRewards() {
        return List.copyOf(this.pendingRewards);
    }

    public synchronized void addPending(final List<Integer> levels) {
        this.pendingRewards.addAll(levels);
    }

    /** Забрать отложенные награды (они удаляются). */
    public synchronized List<Integer> drainPending() {
        final List<Integer> out = List.copyOf(this.pendingRewards);
        this.pendingRewards.clear();
        return out;
    }

    /** Снимок для записи. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(this.id, this.name, this.balance, this.level, this.exp, List.copyOf(this.pendingRewards));
    }

    public record Snapshot(UUID id, String name, long balance, int level, long exp, List<Integer> pending) {
    }
}
