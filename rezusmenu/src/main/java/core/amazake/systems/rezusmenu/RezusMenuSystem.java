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

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.load();
        this.store = new PlayerStore(context);
        this.store.load();
        this.menus = new MenuManager(context, this.settings::get, this.store);
        context.registerListener(this);
        context.registerListener(this.menus);
        context.registerCommand(new MenusCommand(this.settings::get, this.menus, this::load));
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
    }

    @Override
    public void reload(final SystemContext context) {
        this.load();
    }

    private void load() {
        final Settings parsed = Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w));
        this.settings.set(parsed);
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
