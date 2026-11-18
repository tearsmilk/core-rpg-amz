package core.amazake.systems.coremechanics.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Статичная геометрия мира в физ-системе: по меш-телу на секцию 16×16×16, ТОЛЬКО там, где рядом есть куклы.
 * Только поток физики.
 *
 * <p>В отличие от PasToSmith, блоки здесь не читаются напрямую: секция запрашивается у {@link Source} (снимок в потоке
 * региона), ответ приходит позже через {@link #accept}. Пока у секции нет ни одного меша, кукла рядом придерживается
 * кинематикой ({@link #require} вернёт false). Изменения мира ловятся событиями ({@link #markDirtyBlock}) и сверкой:
 * используемые секции переснимаются по кругу ({@link #verify}), меш пересобирается, если хэш геометрии изменился.</p>
 */
public final class WorldMeshes {

    /** Кто снимает секции (в проде — {@link SectionSampler} через задачу региона). */
    @FunctionalInterface
    public interface Source {
        void request(int sx, int sy, int sz);
    }

    /** Через сколько тиков без ответа запрос секции повторяется. */
    static final long REQUEST_TIMEOUT = 100;

    static final class Section {
        int body = -1;
        long hash;
        boolean built;      // есть меш (или известно, что пусто/чанк не загружен)
        boolean unloaded;   // последний снимок — чанк не загружен
        boolean dirty;
        long lastUsed;
        long requestedAt = -1;
        long verifiedAt;
        long changedAt = -1;
    }

    private final PhysicsWorld world;
    private final Source source;
    private final int minSy, maxSy;
    private final Map<Long, Section> sections = new HashMap<>();
    private List<Long> verifyQueue = new ArrayList<>();
    private int verifyPos;
    private int requestsThisTick;
    private long builds;
    private String lastError = "";

    /**
     * @param minY мин. высота мира (включительно)
     * @param maxY макс. высота мира (не включительно)
     */
    public WorldMeshes(final PhysicsWorld world, final Source source, final int minY, final int maxY) {
        this.world = world;
        this.source = source;
        this.minSy = Math.floorDiv(minY, 16);
        this.maxSy = Math.floorDiv(maxY - 1, 16);
    }

    public static long key(final int sx, final int sy, final int sz) {
        return ((long) (sx & 0x3FFFFFF) << 38) | ((long) (sz & 0x3FFFFFF) << 12) | (sy & 0xFFF);
    }

    static int keyX(final long k) {
        final int v = (int) ((k >>> 38) & 0x3FFFFFF);
        return (v & 0x2000000) != 0 ? v | ~0x3FFFFFF : v;
    }

    static int keyZ(final long k) {
        final int v = (int) ((k >>> 12) & 0x3FFFFFF);
        return (v & 0x2000000) != 0 ? v | ~0x3FFFFFF : v;
    }

    static int keyY(final long k) {
        final int v = (int) (k & 0xFFF);
        return (v & 0x800) != 0 ? v | ~0xFFF : v;
    }

    public void beginTick() {
        this.requestsThisTick = 0;
    }

    /**
     * Нужен меш всех секций, пересекающих AABB. false — какой-то секции ещё нет совсем (тело надо придержать).
     *
     * @param maxRequests лимит новых запросов снимков за тик (на все куклы мира)
     */
    public boolean require(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
                           final double maxZ, final long tick, final int maxRequests) {
        final int sx0 = floor16(minX), sx1 = floor16(maxX);
        final int sz0 = floor16(minZ), sz1 = floor16(maxZ);
        final int sy0 = Math.max(floor16(minY), this.minSy), sy1 = Math.min(floor16(maxY), this.maxSy);
        boolean ready = true;
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sy = sy0; sy <= sy1; sy++) {
                    final long k = key(sx, sy, sz);
                    final Section s = this.sections.computeIfAbsent(k, x -> new Section());
                    s.lastUsed = tick;
                    if (!s.built || s.dirty) {
                        this.request(s, sx, sy, sz, tick, maxRequests);
                    }
                    if (!s.built) {
                        ready = false;
                    }
                }
            }
        }
        return ready;
    }

    /** Меш в коробке перестраивался в этом тике (кукол там надо разбудить). */
    public boolean changedSince(final double minX, final double minY, final double minZ, final double maxX,
                                final double maxY, final double maxZ, final long sinceTick) {
        for (int sx = floor16(minX); sx <= floor16(maxX); sx++) {
            for (int sz = floor16(minZ); sz <= floor16(maxZ); sz++) {
                for (int sy = floor16(minY); sy <= floor16(maxY); sy++) {
                    final Section s = this.sections.get(key(sx, sy, sz));
                    if (s != null && s.changedAt >= sinceTick) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void request(final Section s, final int sx, final int sy, final int sz, final long tick, final int maxRequests) {
        if (s.requestedAt >= 0 && tick - s.requestedAt < REQUEST_TIMEOUT) {
            return;   // уже ждём ответа
        }
        if (this.requestsThisTick >= maxRequests) {
            return;
        }
        this.requestsThisTick++;
        s.requestedAt = tick;
        this.source.request(sx, sy, sz);
    }

    /** Пришёл снимок секции (поток физики). */
    public void accept(final SectionSample sample, final long tick) {
        final Section s = this.sections.get(key(sample.sx(), sample.sy(), sample.sz()));
        if (s == null) {
            return;   // секцию уже выгрузили
        }
        s.requestedAt = -1;
        s.verifiedAt = tick;
        if (!sample.loaded()) {
            // чанк не загружен — строить не из чего; считаем пустой, сверка переснимет позже
            if (s.body >= 0) {
                this.world.removeStatic(s.body);
                s.body = -1;
                s.changedAt = tick;
            }
            s.built = true;
            s.unloaded = true;
            s.dirty = false;
            return;
        }
        final SectionMesher.Result r = sample.mesh();
        if (s.built && !s.unloaded && r.hash() == s.hash) {
            s.dirty = false;   // ничего не изменилось
            return;
        }
        int body = -1;
        if (r.triangles() != null) {
            body = this.world.addStaticMesh(r.triangles(), sample.sx() << 4, sample.sy() << 4, sample.sz() << 4);
            if (body < 0) {
                this.lastError = "нет места под меш секции (physics.max-bodies)";
                s.dirty = true;   // старый меш (если был) остаётся, попробуем позже
                return;
            }
        }
        if (s.body >= 0) {
            this.world.removeStatic(s.body);
        }
        s.body = body;
        s.hash = r.hash();
        s.built = true;
        s.unloaded = false;
        s.dirty = false;
        s.changedAt = tick;
        this.builds++;
    }

    /** Блок изменился: его секция (и соседняя, если блок на границе) — на пересъёмку. */
    public void markDirtyBlock(final int x, final int y, final int z) {
        final int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        this.markDirty(cx, cy, cz);
        final int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx == 0) {
            this.markDirty(cx - 1, cy, cz);
        } else if (lx == 15) {
            this.markDirty(cx + 1, cy, cz);
        }
        if (ly == 0) {
            this.markDirty(cx, cy - 1, cz);
        } else if (ly == 15) {
            this.markDirty(cx, cy + 1, cz);
        }
        if (lz == 0) {
            this.markDirty(cx, cy, cz - 1);
        } else if (lz == 15) {
            this.markDirty(cx, cy, cz + 1);
        }
    }

    private void markDirty(final int sx, final int sy, final int sz) {
        final Section s = this.sections.get(key(sx, sy, sz));
        if (s != null) {
            s.dirty = true;
        }
    }

    /** Переснять по кругу несколько недавно использованных секций (изменения мира без событий: WorldEdit, /setblock). */
    public void verify(final long tick, final int perTick, final long interval, final int maxRequests) {
        for (int n = 0; n < perTick; n++) {
            if (this.verifyPos >= this.verifyQueue.size()) {
                this.verifyQueue = new ArrayList<>(this.sections.keySet());
                this.verifyPos = 0;
                if (this.verifyQueue.isEmpty()) {
                    return;
                }
            }
            final long k = this.verifyQueue.get(this.verifyPos++);
            final Section s = this.sections.get(k);
            if (s == null || !s.built || s.dirty || tick - s.lastUsed > 40 || tick - s.verifiedAt < interval) {
                continue;
            }
            this.request(s, keyX(k), keyY(k), keyZ(k), tick, maxRequests);
        }
    }

    /** Выгрузить секции, которые давно никому не нужны. */
    public void evict(final long tick, final long idleTicks) {
        for (final Iterator<Section> it = this.sections.values().iterator(); it.hasNext(); ) {
            final Section s = it.next();
            if (tick - s.lastUsed > idleTicks) {
                if (s.body >= 0) {
                    this.world.removeStatic(s.body);
                }
                it.remove();
            }
        }
    }

    public void destroyAll() {
        for (final Section s : this.sections.values()) {
            if (s.body >= 0) {
                this.world.removeStatic(s.body);
            }
        }
        this.sections.clear();
        this.verifyQueue = new ArrayList<>();
        this.verifyPos = 0;
    }

    public int sectionCount() {
        return this.sections.size();
    }

    public long builds() {
        return this.builds;
    }

    public String lastError() {
        return this.lastError;
    }

    private static int floor16(final double v) {
        return Math.floorDiv((int) Math.floor(v), 16);
    }
}
