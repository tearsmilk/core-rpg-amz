package ink.hoka.odyssey.coreshaders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minestom.server.instance.Instance;

/** Эффекты одной карты: по id, по блоку и по чанку. Только из потока сервера. */
final class EffectWorld implements Faces.Lookup {
    final String map;
    final Instance instance;
    private final Map<UUID, Effect> byId = new LinkedHashMap<>();
    private final Map<Long, List<Effect>> byBlock = new HashMap<>();
    private final Map<Long, List<Effect>> byChunk = new HashMap<>();
    boolean dirty;
    /** Файл не прочитался — не перезаписывать его, пока не починят. */
    boolean readOnly;

    EffectWorld(String map, Instance instance) {
        this.map = map;
        this.instance = instance;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    Collection<Effect> all() {
        return byId.values();
    }

    int size() {
        return byId.size();
    }

    Effect get(UUID id) {
        return byId.get(id);
    }

    void add(Effect e) {
        byId.put(e.id, e);
        byBlock.computeIfAbsent(e.blockKey(), k -> new ArrayList<>(2)).add(e);
        byChunk.computeIfAbsent(e.chunkKey(), k -> new ArrayList<>()).add(e);
        dirty = true;
    }

    void remove(Effect e) {
        if (byId.remove(e.id) == null) {
            return;
        }
        drop(byBlock, e.blockKey(), e);
        drop(byChunk, e.chunkKey(), e);
        dirty = true;
    }

    /** Поменять данные (в том числе блок) — с переиндексацией. */
    void change(Effect e, EffectData d) {
        boolean known = byId.containsKey(e.id);
        if (known) {
            drop(byBlock, e.blockKey(), e);
            drop(byChunk, e.chunkKey(), e);
        }
        e.data = d;
        if (known) {
            byBlock.computeIfAbsent(e.blockKey(), k -> new ArrayList<>(2)).add(e);
            byChunk.computeIfAbsent(e.chunkKey(), k -> new ArrayList<>()).add(e);
        }
        dirty = true;
    }

    private static void drop(Map<Long, List<Effect>> m, long k, Effect e) {
        List<Effect> l = m.get(k);
        if (l != null && l.remove(e) && l.isEmpty()) {
            m.remove(k);
        }
    }

    List<Effect> at(int x, int y, int z) {
        List<Effect> l = byBlock.get(key(x, y, z));
        return l == null ? List.of() : List.copyOf(l);
    }

    List<Effect> inChunk(int cx, int cz) {
        List<Effect> l = byChunk.get(chunkKey(cx, cz));
        return l == null ? List.of() : List.copyOf(l);
    }

    /** Эффекты, центр блока которых ближе radius к точке. */
    List<Effect> near(double x, double y, double z, double radius) {
        List<Effect> out = new ArrayList<>();
        double r2 = radius * radius;
        int r = (int) Math.ceil(radius) + 1;
        if ((long) (2 * r + 1) * (2 * r + 1) * (2 * r + 1) > byId.size() * 4L) {
            for (Effect e : byId.values()) {
                if (dist2(e, x, y, z) <= r2) {
                    out.add(e);
                }
            }
            return out;
        }
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    List<Effect> l = byBlock.get(key(bx + dx, by + dy, bz + dz));
                    if (l == null) {
                        continue;
                    }
                    for (Effect e : l) {
                        if (dist2(e, x, y, z) <= r2) {
                            out.add(e);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static double dist2(Effect e, double x, double y, double z) {
        double dx = e.data.bx() + 0.5 - x;
        double dy = e.data.by() + 0.5 - y;
        double dz = e.data.bz() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean cube(int x, int y, int z) {
        List<Effect> l = byBlock.get(key(x, y, z));
        if (l != null) {
            for (Effect e : l) {
                if (e.data.shape() == Shape.CUBE) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean plane(int x, int y, int z, Dir face) {
        List<Effect> l = byBlock.get(key(x, y, z));
        if (l != null) {
            for (Effect e : l) {
                if (e.data.shape() == Shape.PLANE && e.data.face() == face) {
                    return true;
                }
            }
        }
        return false;
    }

    int[] masks(EffectData e) {
        return e.shape() == Shape.CUBE ? Faces.cubeMasks(e.rim(), e.bx(), e.by(), e.bz(), this)
            : Faces.planeMasks(e.rim(), e.bx(), e.by(), e.bz(), e.face(), this);
    }
}
