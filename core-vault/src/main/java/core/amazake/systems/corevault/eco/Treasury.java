package core.amazake.systems.corevault.eco;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.data.AccountStore;
import core.amazake.systems.corevault.vault.VaultHook;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Экономика: выбирает, где лежат балансы ({@link Settings.Backend}), и держит провайдер Vault. {@link #refresh()}
 * — в глобальном потоке (enable, reload, появление/уход Vault или чужого провайдера); остальные методы — из
 * любого потока.
 */
public final class Treasury {

    private final Logger logger;
    private final AccountStore store;
    private final Supplier<Settings> settings;
    private final InternalLedger internal;
    private final VaultHook vault;
    private volatile Ledger active;
    private volatile String lastState = "";

    public Treasury(final Logger logger, final AccountStore store, final Supplier<Settings> settings, final VaultHook vault) {
        this.logger = logger;
        this.store = store;
        this.settings = settings;
        this.internal = new InternalLedger(store, settings);
        this.vault = vault;
        this.active = this.internal;
    }

    public InternalLedger internal() {
        return this.internal;
    }

    public Ledger ledger() {
        return this.active;
    }

    public VaultHook vault() {
        return this.vault;
    }

    public boolean enabled() {
        return this.settings.get().modules().economy();
    }

    /** Пересобрать: какой бэкенд, регистрировать ли провайдер Vault. Глобальный поток. */
    public void refresh() {
        final Settings s = this.settings.get();
        final Class<?> economy = VaultHook.economyClass();
        if (!s.modules().economy()) {
            this.vault.unregister();
            this.active = this.internal;
            this.state("модуль economy выключен");
            return;
        }
        final RegisteredServiceProvider<?> other = this.vault.other(economy);
        final Settings.Backend b = s.economy().backend();
        if ((b == Settings.Backend.VAULT || b == Settings.Backend.AUTO) && other != null) {
            this.vault.unregister();
            try {
                final Ledger current = this.active;
                if (!(current instanceof ExternalLedger ext) || ext.provider() != other.getProvider()) {
                    this.active = new ExternalLedger(economy, other.getProvider(), this.settings);
                }
                this.state("балансы у " + this.active.describe() + " (плагин " + other.getPlugin().getName() + ")");
                return;
            } catch (final ReflectiveOperationException | RuntimeException e) {
                this.logger.log(Level.WARNING, "Vault: не удалось подключиться к провайдеру " + other.getProvider().getClass().getName()
                    + " — беру свои балансы", e);
            }
        } else if (b == Settings.Backend.VAULT) {
            this.logger.warning("economy.backend: vault, но другого провайдера экономики Vault нет — беру свои балансы.");
        }
        this.active = this.internal;
        if (economy != null && s.economy().vaultRegister()) {
            this.vault.register(economy, this.internal, this.store, this.settings, this::enabled, s.economy().vaultPriority());
        } else {
            this.vault.unregister();
        }
        this.state("свои балансы (players.yml)" + (economy == null ? ", Vault не установлен"
            : this.vault.registered() ? ", провайдер Vault зарегистрирован" : ", провайдер Vault не регистрируется"));
    }

    private void state(final String text) {
        if (!text.equals(this.lastState)) {
            this.lastState = text;
            this.logger.info("core-vault: экономика — " + text + ".");
        }
    }

    public String describe() {
        return this.lastState;
    }

    /** Выключение: снять провайдер. */
    public void shutdown() {
        this.vault.unregister();
        this.active = this.internal;
    }

    // ------------------------------------------------------------------------------------------- операции

    public long balance(final UUID id) {
        return this.active.balance(id);
    }

    public Ledger.Result deposit(final UUID id, final String name, final long amount) {
        return this.active.deposit(id, name, amount);
    }

    public Ledger.Result withdraw(final UUID id, final String name, final long amount) {
        return this.active.withdraw(id, name, amount);
    }

    public Ledger.Result set(final UUID id, final String name, final long amount) {
        return this.active.set(id, name, amount);
    }

    public Ledger.Result transfer(final UUID from, final String fromName, final UUID to, final String toName, final long amount) {
        return this.active.transfer(from, fromName, to, toName, amount);
    }
}
