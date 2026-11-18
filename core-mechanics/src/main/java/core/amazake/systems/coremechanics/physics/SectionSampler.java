package core.amazake.systems.coremechanics.physics;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * Снимки коллизии секций мира. Чтение блоков — ТОЛЬКО в потоке региона, которому принадлежит чанк секции: запрос
 * ставится задачей региона ({@code getRegionScheduler().execute}), ответ уходит обратно в поток физики через
 * {@code reply}. Незагруженный чанк не грузится — ответ {@link SectionSample#unloaded}.
 *
 * <p>Форма коллизии считается один раз на состояние блока ({@link BlockData}) и кэшируется (общий кэш, любой регион).</p>
 */
public final class SectionSampler {

    private final Plugin plugin;
    private final Map<BlockData, float[]> shapes = new ConcurrentHashMap<>();

    public SectionSampler(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** Любой поток: снять секцию (sx, sy, sz) в потоке её региона и отдать в reply (вызывается в потоке региона). */
    public void request(final World world, final int sx, final int sy, final int sz, final Consumer<SectionSample> reply) {
        Bukkit.getRegionScheduler().execute(this.plugin, world, sx, sz, () -> reply.accept(this.sample(world, sx, sy, sz)));
    }

    /** Поток региона чанка (sx, sz). */
    SectionSample sample(final World world, final int sx, final int sy, final int sz) {
        if (!world.isChunkLoaded(sx, sz)) {
            return SectionSample.unloaded(sx, sy, sz);
        }
        final int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        final float[][] out = new float[16 * SectionSample.LAYERS * 16][];
        final int ox = sx << 4, oy = sy << 4, oz = sz << 4;
        for (int ly = -1; ly <= 16; ly++) {
            final int y = oy + ly;
            if (y < minY || y >= maxY) {
                continue;
            }
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    final BlockData bd = world.getBlockData(ox + lx, y, oz + lz);
                    out[SectionSample.index(lx, ly, lz)] = this.boxes(world, bd, ox + lx, y, oz + lz);
                }
            }
        }
        return new SectionSample(sx, sy, sz, true, out);
    }

    private float[] boxes(final World world, final BlockData bd, final int x, final int y, final int z) {
        final Material m = bd.getMaterial();
        if (m.isAir()) {
            return SectionMesher.EMPTY;
        }
        if (positional(m)) {
            return compute(world, bd, x, y, z);   // форма зависит от позиции — без кэша
        }
        final float[] c = this.shapes.get(bd);
        if (c != null) {
            return c;
        }
        final float[] v = compute(world, bd, x, y, z);
        this.shapes.putIfAbsent(bd, v);
        return v;
    }

    /** Блоки, чья коллизия зависит от позиции (случайный сдвиг) или блок-сущности. */
    private static boolean positional(final Material m) {
        return m == Material.BAMBOO || m == Material.POINTED_DRIPSTONE || m == Material.MOVING_PISTON
            || Tag.SHULKER_BOXES.isTagged(m);
    }

    private static float[] compute(final World world, final BlockData bd, final int x, final int y, final int z) {
        try {
            final Collection<BoundingBox> boxes = bd.getCollisionShape(new Location(world, x, y, z)).getBoundingBoxes();
            return fromBoxes(boxes);
        } catch (final RuntimeException e) {
            return bd.isOccluding() ? SectionMesher.FULL : SectionMesher.EMPTY;
        }
    }

    /** Боксы → плоский массив; один бокс 0..1 — канонический {@link SectionMesher#FULL}. */
    static float[] fromBoxes(final Collection<BoundingBox> boxes) {
        if (boxes.isEmpty()) {
            return SectionMesher.EMPTY;
        }
        final float[] out = new float[boxes.size() * 6];
        int i = 0;
        for (final BoundingBox b : boxes) {
            out[i++] = (float) b.getMinX();
            out[i++] = (float) b.getMinY();
            out[i++] = (float) b.getMinZ();
            out[i++] = (float) b.getMaxX();
            out[i++] = (float) b.getMaxY();
            out[i++] = (float) b.getMaxZ();
        }
        if (out.length == 6 && near(out[0], 0) && near(out[1], 0) && near(out[2], 0)
            && near(out[3], 1) && near(out[4], 1) && near(out[5], 1)) {
            return SectionMesher.FULL;
        }
        return out;
    }

    private static boolean near(final float v, final float w) {
        return Math.abs(v - w) < 1e-4f;
    }
}
