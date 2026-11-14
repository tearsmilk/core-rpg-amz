package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.Settings;
import core.amazake.systems.coremobs.geo.BlockPos;
import core.amazake.systems.coremobs.geo.Polygon;
import core.amazake.systems.coremobs.util.Keys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Задача каждого игрока (его поток): запоминает позицию для спавнера и, пока в руке топорик, рисует частицами
 * выделение и регионы рядом. Частицы видит только он. Регионы, включённые для всех ({@code /mobs region show}),
 * рисуются линиями в {@link RegionDisplays}.
 */
public final class RegionView {

    /** Позиция игрока для глобального таймера: читать игрока вне его потока нельзя. */
    public record PlayerPos(UUID world, double x, double y, double z) {
    }

    private final Supplier<Settings> settings;
    private final RegionStore regions;
    private final Selections selections;
    private final Map<UUID, PlayerPos> positions = new ConcurrentHashMap<>();

    public RegionView(final Supplier<Settings> settings, final RegionStore regions, final Selections selections) {
        this.settings = settings;
        this.regions = regions;
        this.selections = selections;
    }

    public Map<UUID, PlayerPos> positions() {
        return this.positions;
    }

    public void forget(final UUID player) {
        this.positions.remove(player);
    }

    public static boolean isWand(final ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(Keys.WAND, PersistentDataType.BYTE);
    }

    /** Один такт задачи игрока. */
    public void tick(final Player player) {
        final Location loc = player.getLocation();
        this.positions.put(player.getUniqueId(), new PlayerPos(loc.getWorld().getUID(), loc.getX(), loc.getY(), loc.getZ()));

        if (!isWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        final Settings s = this.settings.get();
        final int[] budget = {s.particleMaxPerDraw()};
        final String world = loc.getWorld().getName();
        final Selections.Selection sel = this.selections.get(player.getUniqueId());
        if (sel != null && sel.world().equals(world)) {
            this.drawSelection(player, loc, sel.points(), s, budget);
        }
        for (final Region region : this.regions.all()) {
            // Показанный всем регион уже нарисован линиями: частицы поверх не нужны.
            if (region.show() || !region.world().equals(world)
                || region.polygon().distanceOutside(loc.getX(), loc.getZ()) > s.particleViewDistance()) {
                continue;
            }
            this.drawRegion(player, loc, region, s, budget);
        }
    }

    /** Контур региона частицами: нижние рёбра по высоте точек и столбики на вершинах — как линии для всех. */
    private void drawRegion(final Player player, final Location eye, final Region region, final Settings s, final int[] budget) {
        final Particle.DustOptions dust = new Particle.DustOptions(s.regionColor(), s.particleSize());
        for (final Outline.Segment seg : Outline.segments(region, 1000, true)) {
            final int parts = Math.max(1, (int) Math.ceil(seg.length() / s.particleSpacing()));
            for (int k = 0; k < parts; k++) {
                final double t = (double) k / parts;
                if (!this.dot(player, eye, seg.ax() + (seg.bx() - seg.ax()) * t, seg.ay() + (seg.by() - seg.ay()) * t,
                    seg.az() + (seg.bz() - seg.az()) * t, dust, s, budget)) {
                    return;
                }
            }
        }
    }

    private void drawSelection(final Player player, final Location eye, final List<BlockPos> points, final Settings s,
                               final int[] budget) {
        final Particle.DustOptions edge = new Particle.DustOptions(s.selectionColor(), s.particleSize());
        final Particle.DustOptions vertex = new Particle.DustOptions(s.vertexColor(), s.particleSize() * 1.4f);
        final int n = points.size();
        if (n >= 2) {
            final int edges = n >= 3 ? n : 1;
            for (int i = 0; i < edges; i++) {
                final BlockPos a = points.get(i);
                final BlockPos b = points.get((i + 1) % n);
                final List<double[]> line = new ArrayList<>();
                Polygon.edgePoints(a.x() + 0.5, a.z() + 0.5, b.x() + 0.5, b.z() + 0.5, s.particleSpacing(), line);
                for (int k = 0; k < line.size(); k++) {
                    final double t = (double) k / line.size();
                    final double y = a.y() + 1.1 + (b.y() - a.y()) * t;
                    if (!this.dot(player, eye, line.get(k)[0], y, line.get(k)[1], edge, s, budget)) {
                        return;
                    }
                }
            }
        }
        for (final BlockPos v : points) {
            for (double y = 1.1; y <= 2.6; y += 0.5) {
                if (!this.dot(player, eye, v.x() + 0.5, v.y() + y, v.z() + 0.5, vertex, s, budget)) {
                    return;
                }
            }
        }
    }

    /** Частица игроку, если близко и бюджет не кончился; false — бюджет исчерпан. */
    private boolean dot(final Player player, final Location eye, final double x, final double y, final double z,
                        final Particle.DustOptions dust, final Settings s, final int[] budget) {
        final double dx = x - eye.getX();
        final double dy = y - eye.getY();
        final double dz = z - eye.getZ();
        final double max = s.particleViewDistance();
        if (dx * dx + dy * dy + dz * dz > max * max) {
            return true;
        }
        if (budget[0]-- <= 0) {
            return false;
        }
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust, true);
        return true;
    }
}
