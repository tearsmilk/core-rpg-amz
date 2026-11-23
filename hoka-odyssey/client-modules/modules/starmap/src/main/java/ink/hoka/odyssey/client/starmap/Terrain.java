package ink.hoka.odyssey.client.starmap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Arrays;

/**
 * Высоты блоков вокруг: вид сверху на то, что прогружено у клиента. Сетка {@value #TEX}×{@value #TEX} столбцов,
 * левый верхний угол — чанк {@link #originCX}/{@link #originCZ} (вокруг игрока; ушёл далеко — сетка собирается
 * заново вокруг нового места). На столбец — высота самого верхнего не-воздуха. Картинку из высот строит
 * {@link TerrainView} (мягкие «кляксы» того, что выделяется над ровной землёй).
 * <p>
 * Чанки читаются по кругу от центра понемногу за кадр ({@link #step}, бюджет в наносекундах) — в потоке отрисовки:
 * мир клиента из другого потока читать нельзя, а так рендер не стоит дольше бюджета. Пустые чанки (космос) почти
 * ничего не стоят: секции «только воздух» пропускаются целиком. {@link #rev} растёт, когда высоты поменялись, —
 * по нему картинка понимает, что её пора перестроить.
 */
final class Terrain {
    static final int TEX = 1024;
    static final int CH = TEX / 16;
    static final short NONE = Short.MIN_VALUE;
    /** Уходим от центра слоя дальше этого (в чанках) — слой собирается заново вокруг игрока. */
    private static final int RECENTER = CH / 4;

    int originCX, originCZ;
    final short[] height = new short[TEX * TEX];
    /** На чанк: −1 — не прочитан или не прогружен, иначе число непустых столбцов (0..256). */
    final short[] chunkCount = new short[CH * CH];
    private final int[] order;
    private int scanPos;
    private int scanned;
    /** Растёт при каждом изменении высот. */
    int rev;
    private boolean started;
    private ClientLevel level;
    private final int[] top = new int[256];

    Terrain() {
        Integer[] idx = new Integer[CH * CH];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        double c = CH / 2.0 - 0.5;
        Arrays.sort(idx, (a, b) -> Double.compare(d2(a % CH - c, a / CH - c), d2(b % CH - c, b / CH - c)));
        order = new int[idx.length];
        for (int i = 0; i < idx.length; i++) order[i] = idx[i];
        Arrays.fill(height, NONE);
        Arrays.fill(chunkCount, (short) -1);
    }

    private static double d2(double x, double z) {
        return x * x + z * z;
    }

    boolean ready() {
        return started;
    }

    /** Доля прочитанных чанков (первый проход), 0..1. */
    float progress() {
        return Math.min(1f, scanned / (float) order.length);
    }

    /** Высота верхнего блока в столбце (x, z) или Integer.MIN_VALUE — неизвестно / пусто. */
    int heightAt(int x, int z) {
        int px = x - originCX * 16, pz = z - originCZ * 16;
        if (px < 0 || pz < 0 || px >= TEX || pz >= TEX) return Integer.MIN_VALUE;
        short h = height[pz * TEX + px];
        return h == NONE ? Integer.MIN_VALUE : h;
    }

    /** Читать чанки не дольше budgetNanos. */
    void step(Minecraft mc, long budgetNanos) {
        ClientLevel lv = mc.level;
        if (lv == null || mc.player == null) return;
        if (!started) {
            started = true;
            recenter(mc);
        }
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        if (lv != level || Math.abs(pcx - (originCX + CH / 2)) > RECENTER || Math.abs(pcz - (originCZ + CH / 2)) > RECENTER) {
            recenter(mc);
        }
        int rd = mc.options.getEffectiveRenderDistance() + 2;
        long end = System.nanoTime() + budgetNanos;
        int iter = 0;
        do {
            int idx = order[scanPos];
            scanPos++;
            if (scanPos >= order.length) scanPos = 0;
            if (scanned < order.length) scanned++;
            int cx = originCX + idx % CH, cz = originCZ + idx / CH;
            if (Math.abs(cx - pcx) > rd || Math.abs(cz - pcz) > rd) {
                if (chunkCount[idx] != -1) forget(idx);
                continue;                                         // дальше прорисовки — у клиента его нет
            }
            scanChunk(lv, idx, cx, cz);
        } while (++iter < order.length && System.nanoTime() < end);   // за кадр — не больше одного круга
    }

    private void recenter(Minecraft mc) {
        level = mc.level;
        originCX = (mc.player.getBlockX() >> 4) - CH / 2;
        originCZ = (mc.player.getBlockZ() >> 4) - CH / 2;
        Arrays.fill(height, NONE);
        Arrays.fill(chunkCount, (short) -1);
        scanPos = 0;
        scanned = 0;
        rev++;
    }

    private void forget(int idx) {
        chunkCount[idx] = -1;
        int bx = (idx % CH) * 16, bz = (idx / CH) * 16;
        for (int z = 0; z < 16; z++) Arrays.fill(height, (bz + z) * TEX + bx, (bz + z) * TEX + bx + 16, NONE);
        rev++;
    }

    private void scanChunk(ClientLevel lv, int idx, int cx, int cz) {
        LevelChunk chunk = lv.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof EmptyLevelChunk) {
            if (chunkCount[idx] != -1) forget(idx);
            return;
        }
        Arrays.fill(top, Integer.MIN_VALUE);
        LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinY();
        int found = 0;
        for (int si = sections.length - 1; si >= 0 && found < 256; si--) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) continue;
            int baseY = minY + si * 16;
            for (int ly = 15; ly >= 0 && found < 256; ly--) {
                for (int col = 0; col < 256; col++) {
                    if (top[col] != Integer.MIN_VALUE) continue;
                    if (!sec.getBlockState(col & 15, ly, col >> 4).isAir()) {
                        top[col] = baseY + ly;
                        found++;
                    }
                }
            }
        }
        int bx = (idx % CH) * 16, bz = (idx / CH) * 16;
        boolean changed = chunkCount[idx] != found;
        for (int col = 0; col < 256; col++) {
            int p = (bz + (col >> 4)) * TEX + bx + (col & 15);
            int y = top[col];
            short v = y == Integer.MIN_VALUE ? NONE : (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, y));
            if (height[p] != v) {
                height[p] = v;
                changed = true;
            }
        }
        chunkCount[idx] = (short) found;
        if (changed) rev++;
    }

    void close(Minecraft mc) {
        started = false;
        level = null;
    }
}
