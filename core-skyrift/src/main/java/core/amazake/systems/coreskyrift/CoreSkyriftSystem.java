package core.amazake.systems.coreskyrift;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreskyrift.event.EventManager;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * core-skyrift — «Разлом небес» (незеритовый слиток): ПКМ — небо над игроком затягивает чёрным туманом, во всё
 * небо медленно раскрывается огромный адский разлом (кровавые пятна, лава, угли, тлеющие края), и из него падают
 * мобы core-mobs. Видят все игроки в радиусе: каждому — свой дисплей неба, который core-шейдер рисует на дальней
 * плоскости во весь экран.
 */
public final class CoreSkyriftSystem implements AmazakeSystem {

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
    private volatile SystemContext context;
    private volatile EventManager events;

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.loadSettings();
        final EventManager m = new EventManager(context, this.settings::get);
        this.events = m;
        context.registerListener(m);
        context.registerListener(m.spawner());
        context.registerCommand(new SkyriftCommand(context, this.settings::get, m, this::reloadNow));
        m.start();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            m.sweepStale(player);
        }
        final Settings s = this.settings.get();
        context.logger().info("core-skyrift включён: радиус " + Math.round(s.event().radius()) + ", событие "
            + core.amazake.systems.coreskyrift.anim.Timeline.of(s.times()).total() / 20 + " с, мобы "
            + (s.mobs().enabled() ? s.mobs().list().keySet() : "выключены"));
    }

    @Override
    public void disable(final SystemContext context) {
        final EventManager m = this.events;
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
        this.context.logger().info("core-skyrift: настройки перечитаны (идущие события доигрывают со старыми).");
    }

    private void loadSettings() {
        this.settings.set(Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w)));
    }
}
