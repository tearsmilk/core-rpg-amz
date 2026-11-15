package core.amazake.systems.corevault.eco;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.data.Account;
import core.amazake.systems.corevault.data.AccountStore;
import java.util.UUID;
import java.util.function.Supplier;

/** Свои счета: операции атомарны под замком счёта; перевод берёт оба замка в порядке UUID (без взаимоблокировки). */
public final class InternalLedger implements Ledger {

    private final AccountStore store;
    private final Supplier<Settings> settings;

    public InternalLedger(final AccountStore store, final Supplier<Settings> settings) {
        this.store = store;
        this.settings = settings;
    }

    @Override
    public String describe() {
        return "core-vault (players.yml)";
    }

    @Override
    public boolean internal() {
        return true;
    }

    /** Счёт есть или заводится со стартовым балансом и стартовым уровнем. */
    public Account account(final UUID id, final String name) {
        final Settings s = this.settings.get();
        final Account a = this.store.getOrCreate(id, k -> new Account(k, name, s.economy().startBalance(),
            s.levels().curve().startLevel(), 0));
        if (name != null && !name.isEmpty() && a.name().isEmpty()) {
            this.store.rename(a, name);
        }
        return a;
    }

    @Override
    public long balance(final UUID id) {
        final Account a = this.store.get(id);
        return a == null ? this.settings.get().economy().startBalance() : a.balance();
    }

    @Override
    public boolean hasAccount(final UUID id) {
        return this.store.get(id) != null;
    }

    @Override
    public Result deposit(final UUID id, final String name, final long amount) {
        if (amount < 0) {
            return this.withdraw(id, name, -amount);
        }
        final Account a = this.account(id, name);
        final long max = this.settings.get().economy().maxBalance();
        synchronized (a) {
            final long b = a.balance();
            if (b > max - amount) {
                return Result.fail(b, "превышен потолок счёта");
            }
            a.balance(b + amount);
            this.store.markDirty();
            return Result.ok(b + amount);
        }
    }

    @Override
    public Result withdraw(final UUID id, final String name, final long amount) {
        if (amount < 0) {
            return this.deposit(id, name, -amount);
        }
        final Account a = this.account(id, name);
        synchronized (a) {
            final long b = a.balance();
            if (b < amount) {
                return Result.fail(b, "недостаточно средств");
            }
            a.balance(b - amount);
            this.store.markDirty();
            return Result.ok(b - amount);
        }
    }

    @Override
    public Result set(final UUID id, final String name, final long amount) {
        final Account a = this.account(id, name);
        final long v = Math.max(0, Math.min(this.settings.get().economy().maxBalance(), amount));
        synchronized (a) {
            a.balance(v);
            this.store.markDirty();
            return Result.ok(v);
        }
    }

    @Override
    public Result transfer(final UUID from, final String fromName, final UUID to, final String toName, final long amount) {
        if (from.equals(to)) {
            return Result.fail(this.balance(from), "нельзя перевести самому себе");
        }
        if (amount <= 0) {
            return Result.fail(this.balance(from), "сумма должна быть больше нуля");
        }
        final Account a = this.account(from, fromName);
        final Account b = this.account(to, toName);
        final Account first = from.compareTo(to) < 0 ? a : b;
        final Account second = first == a ? b : a;
        final long max = this.settings.get().economy().maxBalance();
        synchronized (first) {
            synchronized (second) {
                final long fa = a.balance();
                final long tb = b.balance();
                if (fa < amount) {
                    return Result.fail(fa, "недостаточно средств");
                }
                if (tb > max - amount) {
                    return Result.fail(fa, "у получателя превышен потолок счёта");
                }
                a.balance(fa - amount);
                b.balance(tb + amount);
                this.store.markDirty();
                return Result.ok(fa - amount);
            }
        }
    }
}
