package core.amazake.systems.coreeffects;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreeffects.papi.PapiHook;
import core.amazake.systems.coreeffects.runtime.EffectManager;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * core-effects — кастомные эффекты: кровотечение, паника, тепло, проклятье. Иконки — шрифт пакета (actionbar) или
 * MythicHUD через плейсхолдеры; затемнение и края экрана — глифы шрифта {@code coreeffects:overlay} в заголовке;
 * общий core-шейдер не трогается.
 */
public final class CoreEffectsSystem implements AmazakeSystem, Listener {

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
    private volatile SystemContext context;
    private volatile EffectManager manager;
    private volatile PapiHook papi;

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.loadSettings();
        final EffectManager m = new EffectManager(context, this.settings::get);
        this.manager = m;
        context.registerListener(m);
        context.registerListener(this);
        context.registerCommand(new EffectsCommand(context, this.settings::get, m, this::reloadNow));
        this.papi = new PapiHook(context.logger(), "Смените hud.placeholderapi-identifier в config.yml.");
        this.hookPapi();
        m.start();
        context.logger().info("core-effects включён: кровотечение, паника, тепло, проклятье.");
    }

    @Override
    public void disable(final SystemContext context) {
        final PapiHook p = this.papi;
        if (p != null) {
            p.unregister();
        }
        final EffectManager m = this.manager;
        if (m != null) {
            m.shutdown();
        }
    }

    @Override
    public void reload(final SystemContext context) {
        this.reloadNow();
    }

    private void reloadNow() {
        this.loadSettings();
        this.hookPapi();
        this.context.logger().info("core-effects: настройки перечитаны.");
    }

    private void loadSettings() {
        this.settings.set(Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w)));
    }

    private void hookPapi() {
        final PapiHook p = this.papi;
        if (p == null) {
            return;
        }
        final Settings s = this.settings.get();
        final Plugin papiPlugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (!s.hud().papi() || papiPlugin == null || !papiPlugin.isEnabled()) {
            p.unregister();
            return;
        }
        final EffectManager m = this.manager;
        p.register(papiPlugin, s.hud().papiIdentifier(), "tearsmilk", this.context.info().version(),
            (OfflinePlayer player, String params) -> m == null || player == null ? null : m.placeholder(player.getUniqueId(), params));
    }

    @EventHandler
    public void onPluginEnable(final PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) {
            this.hookPapi();
        }
    }

    @EventHandler
    public void onPluginDisable(final PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) {
            final PapiHook p = this.papi;
            if (p != null) {
                p.unregister();
            }
        }
    }
}
