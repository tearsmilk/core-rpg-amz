package core.amazake.systems.corevault.vault;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.data.AccountStore;
import core.amazake.systems.corevault.eco.InternalLedger;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

/**
 * Vault: поиск класса {@code Economy} в загрузчике плагина Vault, регистрация своего прокси в ServicesManager и
 * поиск чужого провайдера. Все методы — в глобальном потоке (enable/reload/disable, события плагинов и сервисов).
 */
public final class VaultHook {

    public static final String ECONOMY = "net.milkbowl.vault.economy.Economy";

    private final Logger logger;
    private final Plugin owner;
    private volatile Object proxy;
    private volatile Class<?> registeredClass;
    private volatile String registeredPriority;

    public VaultHook(final Logger logger, final Plugin owner) {
        this.logger = logger;
        this.owner = owner;
    }

    /** Класс Economy из включённого Vault или null. */
    public static Class<?> economyClass() {
        final Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return null;
        }
        try {
            return Class.forName(ECONOMY, false, vault.getClass().getClassLoader());
        } catch (final ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    public static String vaultVersion() {
        final Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        return vault == null ? null : vault.getPluginMeta().getVersion();
    }

    public boolean registered() {
        return this.proxy != null;
    }

    public boolean isOurs(final Object provider) {
        return provider != null && provider == this.proxy;
    }

    /** Лучший чужой провайдер экономики (не наш) или null. */
    public RegisteredServiceProvider<?> other(final Class<?> economy) {
        if (economy == null) {
            return null;
        }
        for (final RegisteredServiceProvider<?> r : Bukkit.getServicesManager().getRegistrations(economy)) {
            if (r.getProvider() != this.proxy) {
                return r;
            }
        }
        return null;
    }

    /** Кто сейчас главный провайдер (его берут плагины): имя или «нет». */
    public String active(final Class<?> economy) {
        if (economy == null) {
            return "нет Vault";
        }
        final RegisteredServiceProvider<?> r = Bukkit.getServicesManager().getRegistration(economy);
        if (r == null) {
            return "нет";
        }
        final String who = r.getProvider() == this.proxy ? VaultProvider.NAME : r.getProvider().getClass().getName()
            + " (" + r.getPlugin().getName() + ")";
        return who + ", " + r.getPriority();
    }

    /** Зарегистрировать (или перерегистрировать с новым приоритетом) свой провайдер. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean register(final Class<?> economy, final InternalLedger ledger, final AccountStore store,
                            final Supplier<Settings> settings, final BooleanSupplier enabled, final String priority) {
        if (this.proxy != null && this.registeredClass == economy && priority.equals(this.registeredPriority)) {
            return true;
        }
        this.unregister();
        try {
            final VaultProvider handler = new VaultProvider(economy, ledger, store, settings, enabled);
            final Object p = Proxy.newProxyInstance(economy.getClassLoader(), new Class<?>[] {economy}, handler);
            this.proxy = p;
            this.registeredClass = economy;
            this.registeredPriority = priority;
            Bukkit.getServicesManager().register((Class) economy, p, this.owner, ServicePriority.valueOf(priority));
            this.logger.info("Vault: core-vault зарегистрирован провайдером экономики (приоритет " + priority + ").");
            return true;
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            this.proxy = null;
            this.registeredClass = null;
            this.logger.log(Level.WARNING, "Vault: не удалось зарегистрировать провайдер экономики", e);
            return false;
        }
    }

    /** Снять свой провайдер (безопасно повторно). */
    public void unregister() {
        final Object p = this.proxy;
        final Class<?> c = this.registeredClass;
        this.proxy = null;
        this.registeredClass = null;
        this.registeredPriority = null;
        if (p != null && c != null) {
            try {
                Bukkit.getServicesManager().unregister(c, p);
                this.logger.info("Vault: провайдер экономики core-vault снят.");
            } catch (final RuntimeException e) {
                this.logger.log(Level.FINE, "Vault: unregister", e);
            }
        }
    }

    /** Vault выключился: его ServicesManager-записи снимает сервер, нам — забыть ссылки. */
    public void forget() {
        this.proxy = null;
        this.registeredClass = null;
        this.registeredPriority = null;
    }
}
