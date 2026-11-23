package ink.hoka.odyssey.client.starmap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * Слой блоков карты: вид сверху на то, что прогружено у клиента. Текстура {@value #TEX}×{@value #TEX} пикселей,
 * 1 пиксель = 1 блок, левый верхний угол — чанк {@link #originCX}/{@link #originCZ} (вокруг игрока; ушёл далеко —
 * слой собирается заново вокруг нового места). На столбец — самый верхний не-воздух; цвет — цвет блока на карте
 * (MapColor), переведённый в палитру радара: ниже тебя — зелёный и темнее, выше — голубой и ярче.
 * <p>
 * Чанки читаются по кругу от центра понемногу за кадр ({@link #step}, бюджет в наносекундах) — в потоке отрисовки:
 * мир клиента из другого потока читать нельзя, а так рендер не стоит дольше бюджета. Текстура отправляется на
 * видеокарту не чаще {@value #UPLOAD_MS} мс. Пустые чанки (космос) почти ничего не стоят: секции «только воздух»
 * пропускаются целиком. По каждому чанку запоминается, сколько в нём непустых столбцов — из этого карта рисует
 * «засветки» астероидов и станций при большом обзоре, когда пиксели блоков уже мельче экранных.
 */
final class Terrain {
    static final int TEX = 1024;
    static final int CH = TEX / 16;
    private static final int UPLOAD_MS = 150;
    private static final short NONE = Short.MIN_VALUE;
    /** Уходим от центра слоя дальше этого (в чанках) — слой собирается заново вокруг игрока. */
    private static final int RECENTER = CH / 4;

    final Identifier id = Identifier.fromNamespaceAndPath("hoka_starmap", "terrain");
    private DynamicTexture tex;
    private NativeImage img;
    int originCX, originCZ;
    private final short[] height = new short[TEX * TEX];
    /** На чанк: −1 — не прочитан или не прогружен, иначе число непустых столбцов (0..256). */
    final short[] chunkCount = new short[CH * CH];
    /** На чанк: средняя высота верхних блоков. */
    final short[] chunkTop = new short[CH * CH];
    private final int[] order;
    private int scanPos;
    private int scanned;
    private boolean dirty;
    private long lastUpload;
    private ClientLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final int[] top = new int[256];
    private final BlockState[] states = new BlockState[256];

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
        return tex != null;
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
        if (tex == null) {
            tex = new DynamicTexture(() -> "hoka starmap terrain", TEX, TEX, true);
            img = tex.getPixels();
            mc.getTextureManager().register(id, tex);
            recenter(mc);
        }
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        if (lv != level || Math.abs(pcx - (originCX + CH / 2)) > RECENTER || Math.abs(pcz - (originCZ + CH / 2)) > RECENTER) {
            recenter(mc);
        }
        int refY = mc.player.getBlockY();
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
            scanChunk(lv, idx, cx, cz, refY);
        } while (++iter < order.length && System.nanoTime() < end);   // за кадр — не больше одного круга
        long now = System.currentTimeMillis();
        if (dirty && now - lastUpload >= UPLOAD_MS) {
            tex.upload();
            dirty = false;
            lastUpload = now;
        }
    }

    private void recenter(Minecraft mc) {
        level = mc.level;
        originCX = (mc.player.getBlockX() >> 4) - CH / 2;
        originCZ = (mc.player.getBlockZ() >> 4) - CH / 2;
        Arrays.fill(height, NONE);
        Arrays.fill(chunkCount, (short) -1);
        Arrays.fill(chunkTop, (short) 0);
        img.fillRect(0, 0, TEX, TEX, 0);
        scanPos = 0;
        scanned = 0;
        dirty = true;
    }

    private void forget(int idx) {
        chunkCount[idx] = -1;
        int bx = (idx % CH) * 16, bz = (idx / CH) * 16;
        img.fillRect(bx, bz, 16, 16, 0);
        for (int z = 0; z < 16; z++) Arrays.fill(height, (bz + z) * TEX + bx, (bz + z) * TEX + bx + 16, NONE);
        dirty = true;
    }

    private void scanChunk(ClientLevel lv, int idx, int cx, int cz, int refY) {
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
                    BlockState st = sec.getBlockState(col & 15, ly, col >> 4);
                    if (!st.isAir()) {
                        top[col] = baseY + ly;
                        states[col] = st;
                        found++;
                    }
                }
            }
        }
        int bx = (idx % CH) * 16, bz = (idx / CH) * 16;
        long sum = 0;
        for (int col = 0; col < 256; col++) {
            int lx = col & 15, lz = col >> 4;
            int p = (bz + lz) * TEX + bx + lx;
            int y = top[col];
            if (y == Integer.MIN_VALUE) {
                height[p] = NONE;
                img.setPixel(bx + lx, bz + lz, 0);
                continue;
            }
            sum += y;
            height[p] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, y));
            int mapCol;
            try {
                MapColor mc = states[col].getMapColor(lv, pos.set(cx * 16 + lx, y, cz * 16 + lz));
                mapCol = mc == null ? 0 : mc.col;
            } catch (RuntimeException e) {
                mapCol = 0;
            }
            img.setPixel(bx + lx, bz + lz, phosphor(mapCol, y - refY, y));
            states[col] = null;
        }
        chunkCount[idx] = (short) found;
        chunkTop[idx] = (short) (found == 0 ? 0 : Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, sum / found)));
        dirty = true;
    }

    /**
     * Цвет блока в палитре радара (ARGB): яркость — от яркости цвета блока на карте и высоты относительно игрока;
     * оттенок — зелёный ниже тебя, голубой выше; через каждые 4 блока высоты — чуть темнее (горизонтали).
     */
    static int phosphor(int mapRgb, int dy, int y) {
        float lum;
        if (mapRgb == 0) lum = 0.55f;
        else lum = (0.30f * ((mapRgb >> 16) & 255) + 0.59f * ((mapRgb >> 8) & 255) + 0.11f * (mapRgb & 255)) / 255f;
        float h = Math.max(-1f, Math.min(1f, dy / 48f));
        float bright = 0.22f + 0.5f * lum + 0.28f * h;
        if (((y >> 2) & 1) == 0) bright *= 0.88f;
        bright = Math.max(0.10f, Math.min(1f, bright));
        float up = Math.max(0f, h);
        float r = (40 + 50 * up) * bright, g = (255 - 25 * up) * bright, b = (130 + 125 * up) * bright;
        return 0xFF000000 | ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    void close(Minecraft mc) {
        if (tex != null) {
            mc.getTextureManager().release(id);                 // закрывает и текстуру, и картинку
            tex = null;
            img = null;
        }
        level = null;
    }
}
