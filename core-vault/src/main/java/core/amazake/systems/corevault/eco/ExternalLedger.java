package core.amazake.systems.corevault.eco;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.money.Money;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Балансы другого провайдера Vault (например, встроенной экономики Vault 2.x). Классы Vault загрузчик системы не
 * видит, поэтому вызовы — отражением по интерфейсу {@code net.milkbowl.vault.economy.Economy}. Vault-интерфейс
 * не атомарен целиком: «задать» — это снять/добавить разницу, перевод — снять и зачислить (при отказе зачисления
 * снятое возвращается).
 */
public final class ExternalLedger implements Ledger {

    private final Object provider;
    private final Supplier<Settings> settings;
    private final String name;
    private final Method getBalance;
    private final Method deposit;
    private final Method withdraw;
    private final Method hasAccount;
    private final Method createAccount;
    private final Method success;

    public ExternalLedger(final Class<?> economy, final Object provider, final Supplier<Settings> settings)
        throws ReflectiveOperationException {
        this.provider = provider;
        this.settings = settings;
        this.getBalance = economy.getMethod("getBalance", OfflinePlayer.class);
        this.deposit = economy.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        this.withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
        this.hasAccount = economy.getMethod("hasAccount", OfflinePlayer.class);
        this.createAccount = economy.getMethod("createPlayerAccount", OfflinePlayer.class);
        this.success = this.withdraw.getReturnType().getMethod("transactionSuccess");
        String n;
        try {
            n = String.valueOf(economy.getMethod("getName").invoke(provider));
        } catch (final InvocationTargetException | RuntimeException e) {
            n = provider.getClass().getName();
        }
        this.name = n;
    }

    public Object provider() {
        return this.provider;
    }

    @Override
    public String describe() {
        return "Vault: " + this.name;
    }

    @Override
    public boolean internal() {
        return false;
    }

    private int decimals() {
        return this.settings.get().economy().currency().decimals();
    }

    private static OfflinePlayer player(final UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }

    private Object call(final Method m, final Object... args) {
        try {
            return m.invoke(this.provider, args);
        } catch (final InvocationTargetException e) {
            final Throwable c = e.getCause();
            throw new IllegalStateException("провайдер Vault «" + this.name + "»: " + (c == null ? e : c), c == null ? e : c);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public long balance(final UUID id) {
        final Object v = this.call(this.getBalance, player(id));
        return v instanceof Number n ? Money.fromDouble(n.doubleValue(), this.decimals()) : 0;
    }

    @Override
    public boolean hasAccount(final UUID id) {
        return Boolean.TRUE.equals(this.call(this.hasAccount, player(id)));
    }

    private void ensure(final OfflinePlayer p) {
        if (!Boolean.TRUE.equals(this.call(this.hasAccount, p))) {
            this.call(this.createAccount, p);
        }
    }

    private boolean ok(final Object response) {
        try {
            return response != null && Boolean.TRUE.equals(this.success.invoke(response));
        } catch (final ReflectiveOperationException e) {
            return false;
        }
    }

    private static String error(final Object response) {
        try {
            final Object m = response.getClass().getField("errorMessage").get(response);
            return m == null || String.valueOf(m).isBlank() ? "провайдер отказал" : String.valueOf(m);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return "провайдер отказал";
        }
    }

    @Override
    public synchronized Result deposit(final UUID id, final String name, final long amount) {
        if (amount < 0) {
            return this.withdraw(id, name, -amount);
        }
        final OfflinePlayer p = player(id);
        this.ensure(p);
        final long before = this.balance(id);
        if (before > this.settings.get().economy().maxBalance() - amount) {
            return Result.fail(before, "превышен потолок счёта");
        }
        final Object r = this.call(this.deposit, p, Money.toDouble(amount, this.decimals()));
        return this.ok(r) ? Result.ok(this.balance(id)) : Result.fail(before, error(r));
    }

    @Override
    public synchronized Result withdraw(final UUID id, final String name, final long amount) {
        if (amount < 0) {
            return this.deposit(id, name, -amount);
        }
        final OfflinePlayer p = player(id);
        this.ensure(p);
        final long before = this.balance(id);
        if (before < amount) {
            return Result.fail(before, "недостаточно средств");
        }
        final Object r = this.call(this.withdraw, p, Money.toDouble(amount, this.decimals()));
        return this.ok(r) ? Result.ok(this.balance(id)) : Result.fail(before, error(r));
    }

    @Override
    public synchronized Result set(final UUID id, final String name, final long amount) {
        final long target = Math.max(0, Math.min(this.settings.get().economy().maxBalance(), amount));
        final long now = this.balance(id);
        if (target == now) {
            return Result.ok(now);
        }
        return target > now ? this.deposit(id, name, target - now) : this.withdraw(id, name, now - target);
    }

    @Override
    public synchronized Result transfer(final UUID from, final String fromName, final UUID to, final String toName,
                                        final long amount) {
        if (from.equals(to)) {
            return Result.fail(this.balance(from), "нельзя перевести самому себе");
        }
        if (amount <= 0) {
            return Result.fail(this.balance(from), "сумма должна быть больше нуля");
        }
        final long toBefore = this.balance(to);
        if (toBefore > this.settings.get().economy().maxBalance() - amount) {
            return Result.fail(this.balance(from), "у получателя превышен потолок счёта");
        }
        final Result w = this.withdraw(from, fromName, amount);
        if (!w.ok()) {
            return w;
        }
        final Result d = this.deposit(to, toName, amount);
        if (!d.ok()) {
            this.deposit(from, fromName, amount);
            return Result.fail(this.balance(from), d.error());
        }
        return Result.ok(this.balance(from));
    }
}
