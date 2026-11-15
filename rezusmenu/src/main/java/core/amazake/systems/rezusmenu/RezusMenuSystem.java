package core.amazake.systems.rezusmenu;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.rezusmenu.util.Text;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * rezusmenu — {@code /menuses}: родная панель Minecraft со списком меню; RPG-меню — экран из дисплеев перед игроком,
 * кнопки нажимаются прицелом.
 */
public final class RezusMenuSystem implements AmazakeSystem, Listener {

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
    private volatile SystemContext context;
    private volatile MenuManager menus;
    private volatile PlayerStore store;
    private final AtomicReference<core.amazake.systems.rezusmenu.profiles.ProfileConfig> profileConfig =
        new AtomicReference<>(core.amazake.systems.rezusmenu.profiles.ProfileConfig.DEFAULTS);
    private volatile core.amazake.systems.rezusmenu.profiles.AccountStore accounts;
    private volatile core.amazake.systems.rezusmenu.profiles.ProfileService profiles;

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.load();
        this.store = new PlayerStore(context);
        this.store.load();
        this.menus = new MenuManager(context, this.settings::get, this.store);
        this.accounts = new core.amazake.systems.rezusmenu.profiles.AccountStore(context.dataFolder().resolve("profiles"),
            context.logger());
        final MenuManager m = this.menus;
        this.profiles = new core.amazake.systems.rezusmenu.profiles.ProfileService(context, this.profileConfig::get,
            this.accounts, (p, forced) -> context.runOnceForPlayer(p, () -> m.open(p, "profiles", forced)), m::realLocation);
        this.menus.profiles(this.profiles);
        context.registerListener(this);
        context.registerListener(this.menus);
        context.registerListener(this.profiles);
        context.registerCommand(new MenusCommand(this.settings::get, this.menus, this::load, this.profiles));
        context.registerCommand(new ProfilesCommand(this.menus));
        // Систему могли включить после падения, когда игроки уже онлайн: вернуть тем, кто остался наблюдателем.
        for (final Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            context.runOnceForPlayer(p, () -> {
                final core.amazake.systems.rezusmenu.screen.Screen.Saved saved = this.store.takePending(p.getUniqueId());
                if (saved != null) {
                    core.amazake.systems.rezusmenu.screen.Screen.restore(p, saved);
                    this.menus.restoreHud(p);
                }
            });
        }
        context.logger().info("rezusmenu включён: меню в списке " + this.settings.get().menus().size() + ".");
    }

    @Override
    public void disable(final SystemContext context) {
        if (this.menus != null) {
            this.menus.closeAll();
        }
        if (this.store != null) {
            this.store.saveNow();
        }
        if (this.accounts != null) {
            this.accounts.shutdown();
        }
    }

    @Override
    public void reload(final SystemContext context) {
        this.load();
    }

    private void load() {
        final Settings parsed = Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w));
        this.settings.set(parsed);
        this.profileConfig.set(core.amazake.systems.rezusmenu.profiles.ProfileConfig.from(this.context.loadConfig(),
            w -> this.context.logger().warning("config.yml: " + w)));
        Text.setPrefix(parsed.prefix());
    }

    /** Кнопка диалога /menuses. Событие может прийти не из потока игрока: само открытие — в его потоке. */
    @EventHandler
    public void onCustomClick(final PlayerCustomClickEvent event) {
        if (!HubDialog.NAMESPACE.equals(event.getIdentifier().namespace())
            || !event.getIdentifier().value().startsWith(HubDialog.OPEN)
            || !(event.getCommonConnection() instanceof final PlayerGameConnection game)) {
            return;
        }
        final Player player = game.getPlayer();
        final String id = event.getIdentifier().value().substring(HubDialog.OPEN.length());
        this.context.runOnceForPlayer(player, () -> this.menus.open(player, id));
    }
}
