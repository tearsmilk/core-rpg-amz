package core.amazake.systems.coreportals;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreportals.portal.PortalManager;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * core-portals — тестовые предметы-порталы: «Портал» (смола) открывает перед игроком раму 2×3,5, которая поднимается
 * из земли и раскрывается, «Изумрудный портал» — кольцо-воронку с чёрной пустотой (дисплеи + core-шейдер из пакета
 * ресурсов). Вошедшего портал отправляет командой (по умолчанию {@code /warp dungeon1}).
 */
public final class CorePortalsSystem implements AmazakeSystem {

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
    private volatile SystemContext context;
    private volatile PortalManager portals;

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.loadSettings();
        this.portals = new PortalManager(context, this.settings::get,
            (player, template, resolvers) -> Text.send(player, this.settings.get(), template, resolvers));
        context.registerListener(this.portals);
        context.registerCommand(new PortalsCommand(context, this.settings::get, this.portals, this::reloadNow));
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.portals.sweepStale(player);
        }
        context.logger().info("core-portals включён: срок жизни портала " + this.settings.get().lifetimeTicks() / 20
            + " с, действие: " + this.settings.get().teleport().runAs() + " " + this.settings.get().teleport().commands());
    }

    @Override
    public void disable(final SystemContext context) {
        final PortalManager p = this.portals;
        if (p != null) {
            p.removeAllNow();
        }
    }

    @Override
    public void reload(final SystemContext context) {
        this.reloadNow();
    }

    private void reloadNow() {
        this.loadSettings();
        this.context.logger().info("core-portals: настройки перечитаны.");
    }

    private void loadSettings() {
        this.settings.set(Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w)));
    }
}
