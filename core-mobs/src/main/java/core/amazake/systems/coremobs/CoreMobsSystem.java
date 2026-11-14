package core.amazake.systems.coremobs;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coremobs.drop.DropStore;
import core.amazake.systems.coremobs.mob.MobCatalog;
import core.amazake.systems.coremobs.mob.MobListener;
import core.amazake.systems.coremobs.mob.MobService;
import core.amazake.systems.coremobs.region.Region;
import core.amazake.systems.coremobs.region.RegionSpawner;
import core.amazake.systems.coremobs.region.RegionStore;
import core.amazake.systems.coremobs.region.RegionView;
import core.amazake.systems.coremobs.region.Selections;
import core.amazake.systems.coremobs.region.WandListener;
import core.amazake.systems.coremobs.util.Text;
import core.amazake.systems.coremobs.util.YamlFiles;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * core-mobs — свои мобы из mobs.yml, лут из инвентаря со всеми NBT, регионы спавна любой формы (полигон по
 * точкам топорика) с подсветкой частицами и радиусом деспавна.
 */
public final class CoreMobsSystem implements AmazakeSystem, Listener {

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
    private final AtomicReference<MobCatalog> catalog = new AtomicReference<>(MobCatalog.EMPTY);
    private final Map<UUID, ScheduledTask> playerTasks = new ConcurrentHashMap<>();
    private volatile SystemContext context;
    private volatile RegionStore regions;
    private volatile DropStore drops;
    private volatile MobService mobs;
    private volatile Selections selections;
    private volatile RegionView view;
    private volatile RegionSpawner spawner;
    private volatile ScheduledTask spawnTimer;

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        context.saveDefaultResource("mobs.yml");
        this.loadSettings();
        this.loadCatalog();
        this.regions = new RegionStore(context);
        this.regions.load();
        this.drops = new DropStore(context);
        this.drops.load();
        this.mobs = new MobService(this.catalog::get, () -> this.settings.get().nameFormat());
        this.selections = new Selections();
        this.view = new RegionView(this.settings::get, this.regions, this.selections);
        this.spawner = new RegionSpawner(context.plugin(), this.regions, this.mobs, this.catalog::get, this.view);

        context.registerListener(this);
        context.registerListener(new WandListener(this.settings::get, this.selections));
        context.registerListener(new MobListener(context.plugin(), this.mobs, this.catalog::get, this.drops));
        context.registerCommand(new MobsCommand(context, this.settings::get, this.catalog::get, this.mobs, this.drops,
            this.regions, this.selections, this.spawner, this::reloadAll));
        this.startSpawnTimer();

        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.startPlayer(player);
            this.adoptNear(player);
        }
        this.checkRegionMobs();
        context.logger().info("core-mobs включён: мобов " + this.catalog.get().all().size() + ", регионов "
            + this.regions.all().size() + ".");
    }

    @Override
    public void disable(final SystemContext context) {
        if (this.regions != null) {
            this.regions.saveNow();
        }
        if (this.drops != null) {
            this.drops.saveNow();
        }
        this.playerTasks.clear();
    }

    @Override
    public void reload(final SystemContext context) {
        this.reloadAll();
    }

    /** Перечитать config.yml, mobs.yml, regions.yml и лут. Глобальный поток. */
    void reloadAll() {
        this.regions.saveNow();
        this.drops.saveNow();
        this.loadSettings();
        this.loadCatalog();
        this.regions.load();
        this.drops.load();
        this.startSpawnTimer();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.startPlayer(player);
            this.adoptNear(player);
        }
        this.checkRegionMobs();
        this.context.logger().info("core-mobs: настройки перечитаны.");
    }

    private void loadSettings() {
        final Settings parsed = Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w));
        this.settings.set(parsed);
        Text.setPrefix(parsed.prefix());
    }

    private void loadCatalog() {
        try {
            this.catalog.set(MobCatalog.parse(YamlFiles.read(this.context.dataFolder().resolve("mobs.yml")),
                w -> this.context.logger().warning("mobs.yml: " + w)));
        } catch (final Exception e) {
            this.context.logger().log(Level.WARNING, "mobs.yml не прочитан, мобы оставлены прежними", e);
        }
    }

    /** Предупредить о регионах, где спавнятся мобы, которых нет в mobs.yml. */
    private void checkRegionMobs() {
        for (final Region r : this.regions.all()) {
            for (final String id : r.spawn().mobs().keySet()) {
                if (this.catalog.get().get(id) == null) {
                    this.context.logger().warning("Регион " + r.name() + ": моба «" + id + "» нет в mobs.yml, он не спавнится.");
                }
            }
            if (Bukkit.getWorld(r.world()) == null) {
                this.context.logger().warning("Регион " + r.name() + ": мир «" + r.world() + "» не загружен.");
            }
        }
    }

    private void startSpawnTimer() {
        final ScheduledTask old = this.spawnTimer;
        if (old != null) {
            old.cancel();
        }
        this.spawnTimer = this.context.runGlobalTimer(task -> this.spawner.tick(),
            Duration.ofMillis(this.settings.get().checkIntervalTicks() * 50L));
    }

    private void startPlayer(final Player player) {
        final RegionView v = this.view;
        final ScheduledTask task = this.context.runForPlayer(player, t -> v.tick(player), this.settings.get().particleIntervalTicks());
        if (task != null) {
            final ScheduledTask old = this.playerTasks.put(player.getUniqueId(), task);
            if (old != null) {
                old.cancel();
            }
        }
    }

    /**
     * Мобы регионов, которые уже стоят рядом с игроком (систему перезапустили): снова взять на учёт, а мобов
     * удалённых регионов убрать. Поток игрока.
     */
    private void adoptNear(final Player player) {
        final int r = this.settings.get().adoptRadius();
        this.context.runOnceForPlayer(player, () -> {
            for (final Entity e : player.getNearbyEntities(r, r, r)) {
                final String region = MobService.regionOf(e);
                final String mob = MobService.mobId(e);
                if (region == null || mob == null || this.mobs.tracked().containsKey(e.getUniqueId())) {
                    continue;
                }
                if (this.regions.get(region) == null || this.catalog.get().get(mob) == null) {
                    e.remove();
                    continue;
                }
                this.mobs.track(new MobService.Tracked(e.getUniqueId(), mob, region, e.getWorld().getUID(),
                    e.getLocation().getBlockX(), e.getLocation().getBlockZ()));
            }
        });
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        this.startPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        this.playerTasks.remove(id);
        this.view.forget(id);
        this.selections.forget(id);
    }
}
