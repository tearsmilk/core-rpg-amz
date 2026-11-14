package core.amazake.systems.coremobs.region;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import core.amazake.systems.coremobs.Settings;
import core.amazake.systems.coremobs.util.Keys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Контур включённых регионов ({@code /mobs region show}) для всех игроков: тонкие линии из блок-дисплеев. Это
 * сущности в мире, они не мерцают и не двигаются, в отличие от частиц.
 *
 * <p>Дисплеи не сохраняются в чанк: выгрузился чанк — линии пропали, загрузился — система ставит их снова. Каждая
 * линия создаётся и убирается в потоке региона, где стоит. Метка экземпляра системы отличает свои линии от
 * оставшихся после прошлого запуска.</p>
 */
public final class RegionDisplays implements Listener {

    private final Plugin plugin;
    private final RegionStore regions;
    private final Supplier<Settings> settings;
    private final String instance = UUID.randomUUID().toString();
    /** Поставленная линия: где стоит, чтобы убрать её в потоке того места. */
    private record Placed(UUID id, UUID world, int cx, int cz) {
    }

    /** «регион#номер» → линия. */
    private final Map<String, Placed> spawned = new ConcurrentHashMap<>();

    public RegionDisplays(final Plugin plugin, final RegionStore regions, final Supplier<Settings> settings) {
        this.plugin = plugin;
        this.regions = regions;
        this.settings = settings;
    }

    /** Перерисовать регион: убрать его линии и, если он показан, поставить заново. Любой поток. */
    public void rebuild(final String name) {
        this.remove(name);
        final Region region = this.regions.get(name);
        if (region != null && region.show()) {
            this.spawnAll(region);
        }
    }

    /** Перерисовать все регионы (включение системы, reload). */
    public void rebuildAll() {
        for (final String key : List.copyOf(this.spawned.keySet())) {
            this.remove(key.substring(0, key.lastIndexOf('#')));
        }
        for (final Region region : this.regions.all()) {
            if (region.show()) {
                this.spawnAll(region);
            }
        }
    }

    /** Убрать линии региона. Любой поток: каждая сущность убирается в своём. */
    public void remove(final String name) {
        final String prefix = name + "#";
        for (final Map.Entry<String, Placed> e : List.copyOf(this.spawned.entrySet())) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            final Placed p = e.getValue();
            this.spawned.remove(e.getKey(), p);
            final World world = Bukkit.getWorld(p.world());
            if (world == null) {
                continue;
            }
            Bukkit.getRegionScheduler().execute(this.plugin, world, p.cx(), p.cz(), () -> {
                final Entity entity = Bukkit.getEntity(p.id());
                if (entity != null && Bukkit.isOwnedByCurrentRegion(entity)) {
                    entity.remove();
                }
            });
        }
    }

    private void spawnAll(final Region region) {
        final World world = Bukkit.getWorld(region.world());
        if (world == null) {
            return;
        }
        final Map<Long, List<Outline.Segment>> byChunk = new HashMap<>();
        for (final Outline.Segment s : this.segments(region)) {
            byChunk.computeIfAbsent(chunkKey((int) Math.floor(s.ax()) >> 4, (int) Math.floor(s.az()) >> 4),
                k -> new ArrayList<>()).add(s);
        }
        byChunk.forEach((key, list) -> {
            final int cx = (int) (key >> 32);
            final int cz = (int) (long) key;
            Bukkit.getRegionScheduler().execute(this.plugin, world, cx, cz, () -> {
                if (world.isChunkLoaded(cx, cz)) {
                    list.forEach(s -> this.spawn(world, region.name(), s));
                }
            });
        });
    }

    private List<Outline.Segment> segments(final Region region) {
        final Settings s = this.settings.get();
        return Outline.segments(region, s.displaySegment(), s.displayPosts());
    }

    /** Чанк загрузился (его поток): поставить линии показанных регионов, которые начинаются в нём. */
    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        final Chunk chunk = event.getChunk();
        final World world = chunk.getWorld();
        final int x0 = chunk.getX() << 4;
        final int z0 = chunk.getZ() << 4;
        for (final Region region : this.regions.all()) {
            if (!region.show() || !region.world().equals(world.getName())
                || x0 + 16 < region.polygon().minX() - 1 || x0 > region.polygon().maxX() + 1
                || z0 + 16 < region.polygon().minZ() - 1 || z0 > region.polygon().maxZ() + 1) {
                continue;
            }
            for (final Outline.Segment s : this.segments(region)) {
                if ((int) Math.floor(s.ax()) >> 4 == chunk.getX() && (int) Math.floor(s.az()) >> 4 == chunk.getZ()) {
                    this.spawn(world, region.name(), s);
                }
            }
        }
    }

    @EventHandler
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        final String key = event.getEntity().getPersistentDataContainer().get(Keys.DISPLAY, PersistentDataType.STRING);
        if (key != null) {
            this.spawned.computeIfPresent(key, (k, p) -> p.id().equals(event.getEntity().getUniqueId()) ? null : p);
        }
    }

    /** Поставить одну линию. Поток региона, где она стоит. */
    private void spawn(final World world, final String region, final Outline.Segment s) {
        final String key = region + "#" + s.index();
        final Placed existing = this.spawned.get(key);
        if (existing != null) {
            final Entity e = Bukkit.getEntity(existing.id());
            if (e != null && e.isValid()) {
                return;
            }
        }
        final Location at = new Location(world, s.ax(), s.ay(), s.az());
        // Линии прошлого запуска системы или прошлой формы региона на этом месте — убрать.
        for (final Entity old : world.getNearbyEntities(at, 0.05, 0.05, 0.05, e -> e instanceof BlockDisplay)) {
            final PersistentDataContainer pdc = old.getPersistentDataContainer();
            if (key.equals(pdc.get(Keys.DISPLAY, PersistentDataType.STRING))) {
                old.remove();
            }
        }
        final Settings set = this.settings.get();
        final Vector3f dir = new Vector3f((float) (s.bx() - s.ax()), (float) (s.by() - s.ay()), (float) (s.bz() - s.az()));
        final float len = dir.length();
        final Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), dir.normalize(new Vector3f()));
        final float t = set.displayThickness();
        final Vector3f translation = rot.transform(new Vector3f(-t / 2, -t / 2, 0));
        final BlockDisplay display = world.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(set.displayBlock().createBlockData());
            d.setTransformation(new Transformation(translation, rot, new Vector3f(t, t, len), new Quaternionf()));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(set.displayViewRange());
            if (set.displayGlow()) {
                d.setGlowing(true);
                d.setGlowColorOverride(set.displayGlowColor());
            }
            d.setPersistent(false);
            d.getPersistentDataContainer().set(Keys.DISPLAY, PersistentDataType.STRING, key);
            d.getPersistentDataContainer().set(Keys.INSTANCE, PersistentDataType.STRING, this.instance);
        });
        this.spawned.put(key, new Placed(display.getUniqueId(), world.getUID(), at.getBlockX() >> 4, at.getBlockZ() >> 4));
    }

    /** Линия, оставшаяся от прошлого запуска системы (её никто не ведёт). */
    public boolean isStale(final Entity e) {
        final PersistentDataContainer pdc = e.getPersistentDataContainer();
        return pdc.has(Keys.DISPLAY, PersistentDataType.STRING)
            && !this.instance.equals(pdc.get(Keys.INSTANCE, PersistentDataType.STRING));
    }

    private static long chunkKey(final int x, final int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
