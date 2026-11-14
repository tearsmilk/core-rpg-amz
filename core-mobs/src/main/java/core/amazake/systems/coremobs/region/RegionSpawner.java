package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.geo.BlockPos;
import core.amazake.systems.coremobs.mob.MobCatalog;
import core.amazake.systems.coremobs.mob.MobDef;
import core.amazake.systems.coremobs.mob.MobService;
import core.amazake.systems.coremobs.util.Weighted;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Глобальный таймер регионов (поток глобального тика): решает, где и кого спавнить, и раздаёт работу потокам
 * регионов мира. Сущности и блоки трогаются только в задачах {@code RegionScheduler} — в потоке, которому
 * принадлежит это место.
 */
public final class RegionSpawner {

    private final Plugin plugin;
    private final RegionStore regions;
    private final MobService mobs;
    private final Supplier<MobCatalog> catalog;
    private final RegionView view;
    private final Map<String, Long> nextSpawnAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<>();

    public RegionSpawner(final Plugin plugin, final RegionStore regions, final MobService mobs,
                         final Supplier<MobCatalog> catalog, final RegionView view) {
        this.plugin = plugin;
        this.regions = regions;
        this.mobs = mobs;
        this.catalog = catalog;
        this.view = view;
    }

    /** Сбросить таймер региона (после изменения настроек — спавн попробует сразу). */
    public void resetTimer(final String region) {
        this.nextSpawnAt.remove(region);
    }

    /** Такт глобального таймера. */
    public void tick() {
        this.checkTracked();
        final long now = System.nanoTime();
        final List<RegionView.PlayerPos> players = List.copyOf(this.view.positions().values());
        for (final Region region : this.regions.all()) {
            final Long next = this.nextSpawnAt.get(region.name());
            if (next != null && now - next < 0) {
                continue;
            }
            this.nextSpawnAt.put(region.name(), now + region.spawn().intervalSeconds() * 1_000_000_000L);
            this.trySpawn(region, players);
        }
        this.nextSpawnAt.keySet().removeIf(name -> this.regions.get(name) == null);
    }

    private void trySpawn(final Region region, final List<RegionView.PlayerPos> players) {
        final SpawnSettings s = region.spawn();
        if (!s.enabled() || s.mobs().isEmpty() || s.maxAlive() <= 0) {
            return;
        }
        final World world = Bukkit.getWorld(region.world());
        if (world == null) {
            return;
        }
        final UUID worldId = world.getUID();
        final List<RegionView.PlayerPos> near = new ArrayList<>();
        boolean active = false;
        for (final RegionView.PlayerPos p : players) {
            if (!p.world().equals(worldId)) {
                continue;
            }
            near.add(p);
            if (region.polygon().distanceOutside(p.x(), p.z()) <= s.playerRadius()) {
                active = true;
            }
        }
        if (!active) {
            return;
        }
        final AtomicInteger inFlight = this.pending.computeIfAbsent(region.name(), k -> new AtomicInteger());
        final int free = s.maxAlive() - this.mobs.alive(region.name()) - inFlight.get();
        final int count = Math.min(free, s.amount().roll(ThreadLocalRandom.current()));
        final MobCatalog cat = this.catalog.get();
        for (int i = 0; i < count; i++) {
            final String mobId = Weighted.pick(s.mobs(), ThreadLocalRandom.current());
            final MobDef def = cat.get(mobId);
            if (def == null) {
                continue;
            }
            final BlockPos column = this.pickColumn(region, near, s.minPlayerDistance());
            if (column == null) {
                continue;
            }
            inFlight.incrementAndGet();
            Bukkit.getRegionScheduler().execute(this.plugin, world, column.x() >> 4, column.z() >> 4, () -> {
                try {
                    this.spawnAt(world, region, column.x(), column.z(), def);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    /** Случайный столбец внутри полигона не ближе {@code minDistance} к игрокам (по горизонтали). */
    private BlockPos pickColumn(final Region region, final List<RegionView.PlayerPos> players, final int minDistance) {
        for (int attempt = 0; attempt < 12; attempt++) {
            final BlockPos b = region.polygon().randomBlock(ThreadLocalRandom.current(), 40);
            if (b == null) {
                return null;
            }
            boolean ok = true;
            for (final RegionView.PlayerPos p : players) {
                final double dx = p.x() - (b.x() + 0.5);
                final double dz = p.z() - (b.z() + 0.5);
                if (dx * dx + dz * dz < (double) minDistance * minDistance) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return b;
            }
        }
        return null;
    }

    /** Поток региона столбца: найти, где моб может стоять, и заспавнить. Чанк не загружаем. */
    private void spawnAt(final World world, final Region region, final int x, final int z, final MobDef def) {
        if (!world.isChunkLoaded(x >> 4, z >> 4) || this.regions.get(region.name()) == null) {
            return;
        }
        final int top = Math.min(region.maxY(), world.getMaxHeight() - 2);
        final int bottom = Math.max(region.minY(), world.getMinHeight() + 1);
        final List<Integer> spots = new ArrayList<>();
        for (int y = bottom; y <= top; y++) {
            final Block below = world.getBlockAt(x, y - 1, z);
            final Block feet = world.getBlockAt(x, y, z);
            final Block head = world.getBlockAt(x, y + 1, z);
            if (below.isSolid() && feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()) {
                spots.add(y);
            }
        }
        if (spots.isEmpty()) {
            return;
        }
        final int y = spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
        final Location at = new Location(world, x + 0.5, y, z + 0.5, ThreadLocalRandom.current().nextFloat() * 360f, 0f);
        this.mobs.spawn(def, at, region.name());
    }

    /** Каждому мобу регионов — проверка в потоке, где он стоит: жив ли, не ушёл ли за радиус деспавна. */
    private void checkTracked() {
        for (final MobService.Tracked t : this.mobs.tracked().values()) {
            final World world = Bukkit.getWorld(t.world());
            if (world == null) {
                this.mobs.untrack(t.id());
                continue;
            }
            Bukkit.getRegionScheduler().execute(this.plugin, world, t.x() >> 4, t.z() >> 4, () -> this.check(t));
        }
    }

    private void check(final MobService.Tracked t) {
        final Entity e = Bukkit.getEntity(t.id());
        if (e == null || e.isDead() || !e.isValid()) {
            this.mobs.untrack(t.id());
            return;
        }
        if (!Bukkit.isOwnedByCurrentRegion(e)) {
            // Моб перешёл в регион другого потока: дождёмся, пока его учёт обновит событие или следующий такт.
            return;
        }
        final Region region = this.regions.get(t.region());
        if (region == null) {
            e.remove();
            this.mobs.untrack(t.id());
            return;
        }
        final Location loc = e.getLocation();
        if (region.despawnRadius() >= 0
            && region.distanceOutside(loc.getX(), loc.getY(), loc.getZ()) > region.despawnRadius()) {
            e.remove();
            this.mobs.untrack(t.id());
            return;
        }
        if (loc.getBlockX() != t.x() || loc.getBlockZ() != t.z()) {
            this.mobs.track(new MobService.Tracked(t.id(), t.mob(), t.region(), t.world(), loc.getBlockX(), loc.getBlockZ()));
        }
    }
}
