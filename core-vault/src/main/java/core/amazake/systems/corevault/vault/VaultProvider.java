package core.amazake.systems.corevault.vault;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.data.AccountStore;
import core.amazake.systems.corevault.eco.InternalLedger;
import core.amazake.systems.corevault.eco.Ledger;
import core.amazake.systems.corevault.money.Money;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Обработчик прокси {@code net.milkbowl.vault.economy.Economy}. Прокси создаётся в загрузчике плагина Vault
 * ({@code Proxy.newProxyInstance}), поэтому другие плагины видят обычную реализацию интерфейса; сама логика —
 * здесь, на счетах core-vault. Вызовы приходят из любых потоков; операции счёта атомарны.
 */
final class VaultProvider implements InvocationHandler {

    static final String NAME = "core-vault";

    private final InternalLedger ledger;
    private final AccountStore store;
    private final Supplier<Settings> settings;
    private final BooleanSupplier enabled;
    private final Constructor<?> response;
    private final Object success;
    private final Object failure;
    private final Object notImplemented;

    @SuppressWarnings({"unchecked", "rawtypes"})
    VaultProvider(final Class<?> economy, final InternalLedger ledger, final AccountStore store,
                  final Supplier<Settings> settings, final BooleanSupplier enabled) throws ReflectiveOperationException {
        this.ledger = ledger;
        this.store = store;
        this.settings = settings;
        this.enabled = enabled;
        final Class<?> resp = economy.getMethod("depositPlayer", OfflinePlayer.class, double.class).getReturnType();
        final Class type = Class.forName(resp.getName() + "$ResponseType", true, resp.getClassLoader());
        this.response = resp.getConstructor(double.class, double.class, type, String.class);
        this.success = Enum.valueOf(type, "SUCCESS");
        this.failure = Enum.valueOf(type, "FAILURE");
        this.notImplemented = Enum.valueOf(type, "NOT_IMPLEMENTED");
    }

    private int decimals() {
        return this.settings.get().economy().currency().decimals();
    }

    private double money(final long minor) {
        return Money.toDouble(minor, this.decimals());
    }

    private Object respond(final double amount, final double balance, final Object type, final String error) throws ReflectiveOperationException {
        return this.response.newInstance(amount, balance, type, error);
    }

    /** UUID и имя по аргументу Vault: OfflinePlayer или имя игрока (без запросов в сеть). */
    private record Who(UUID id, String name) {
    }

    private Who who(final Object arg) {
        if (arg instanceof OfflinePlayer p) {
            final String n = p instanceof Player online ? online.getName() : p.getName();
            return new Who(p.getUniqueId(), n);
        }
        if (arg instanceof String name) {
            final UUID known = this.store.byName(name);
            if (known != null) {
                return new Who(known, name);
            }
            final Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                return new Who(online.getUniqueId(), online.getName());
            }
            final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
            if (cached != null) {
                return new Who(cached.getUniqueId(), cached.getName() == null ? name : cached.getName());
            }
        }
        return null;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        final String m = method.getName();
        final int n = args == null ? 0 : args.length;
        if (method.getDeclaringClass() == Object.class) {
            return switch (m) {
                case "toString" -> "core-vault Economy";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(m);
            };
        }
        final Settings s = this.settings.get();
        switch (m) {
            case "isEnabled":
                return this.enabled.getAsBoolean();
            case "getName":
                return NAME;
            case "hasBankSupport":
                return false;
            case "fractionalDigits":
                return this.decimals();
            case "format":
                return s.economy().currency().format(Money.fromDouble((Double) args[0], this.decimals()));
            case "currencyNamePlural":
                return s.economy().currency().plural();
            case "currencyNameSingular":
                return s.economy().currency().singular();
            case "getBanks":
                return List.of();
            default:
                break;
        }
        if (m.startsWith("bank") || m.equals("createBank") || m.equals("deleteBank") || m.startsWith("isBank")) {
            return this.respond(0, 0, this.notImplemented, "core-vault не поддерживает банки");
        }
        final Who w = n > 0 ? this.who(args[0]) : null;
        switch (m) {
            case "hasAccount":
                return w != null && this.ledger.hasAccount(w.id());
            case "createPlayerAccount": {
                if (w == null) {
                    return false;
                }
                final boolean had = this.ledger.hasAccount(w.id());
                this.ledger.account(w.id(), w.name());
                return !had;
            }
            case "getBalance":
                return w == null ? 0.0 : this.money(this.ledger.balance(w.id()));
            case "has": {
                if (w == null) {
                    return false;
                }
                final long need = Money.fromDouble((Double) args[n - 1], this.decimals());
                return this.ledger.balance(w.id()) >= need;
            }
            case "withdrawPlayer":
            case "depositPlayer": {
                final double amount = (Double) args[n - 1];
                if (w == null) {
                    return this.respond(amount, 0, this.failure, "Игрок не найден");
                }
                if (!this.enabled.getAsBoolean()) {
                    return this.respond(amount, this.money(this.ledger.balance(w.id())), this.failure, "Экономика выключена");
                }
                if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
                    return this.respond(amount, this.money(this.ledger.balance(w.id())), this.failure, "Отрицательная сумма");
                }
                final long minor = Money.fromDouble(amount, this.decimals());
                final Ledger.Result r = m.equals("depositPlayer")
                    ? this.ledger.deposit(w.id(), w.name(), minor)
                    : this.ledger.withdraw(w.id(), w.name(), minor);
                return this.respond(amount, this.money(r.balance()), r.ok() ? this.success : this.failure, r.ok() ? null : r.error());
            }
            default:
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException("core-vault: метод Vault " + m + " не поддержан");
        }
    }
}
