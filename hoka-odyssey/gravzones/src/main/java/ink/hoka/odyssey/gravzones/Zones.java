package ink.hoka.odyssey.gravzones;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Все зоны по картам (без Minestom — для тестов). Меняется только в тике сервера; списки карт неизменяемые и
 * заменяются целиком, поэтому чтение из другого потока (сохранение) видит целое состояние.
 */
final class Zones {
    private volatile Map<String, List<Zone>> byMap = Map.of();
    private long seq;

    /** Зона, которая действует в точке: из содержащих её — меньшая по объёму, при равенстве — созданная позже. */
    Zone at(String map, double x, double y, double z) {
        List<Zone> list = byMap.get(map);
        if (list == null) {
            return null;
        }
        Zone best = null;
        for (Zone zn : list) {
            if (zn.contains(x, y, z) && (best == null || zn.beats(best))) {
                best = zn;
            }
        }
        return best;
    }

    List<Zone> on(String map) {
        return byMap.getOrDefault(map, List.of());
    }

    boolean hasMap(String map) {
        return byMap.containsKey(map);
    }

    Zone get(String map, String name) {
        for (Zone zn : on(map)) {
            if (zn.name().equalsIgnoreCase(name)) {
                return zn;
            }
        }
        return null;
    }

    List<Zone> all() {
        List<Zone> out = new ArrayList<>();
        byMap.values().forEach(out::addAll);
        out.sort(Comparator.comparing(Zone::map).thenComparingLong(Zone::created));
        return out;
    }

    int size() {
        int n = 0;
        for (List<Zone> l : byMap.values()) {
            n += l.size();
        }
        return n;
    }

    /** Следующий номер создания. */
    long nextSeq() {
        return ++seq;
    }

    /** Добавить или заменить (по карте и имени без учёта регистра). */
    void put(Zone zn) {
        Map<String, List<Zone>> m = new HashMap<>(byMap);
        List<Zone> list = new ArrayList<>(m.getOrDefault(zn.map(), List.of()));
        list.removeIf(o -> o.name().equalsIgnoreCase(zn.name()));
        list.add(zn);
        list.sort(Comparator.comparingLong(Zone::created));
        m.put(zn.map(), Collections.unmodifiableList(list));
        seq = Math.max(seq, zn.created());
        byMap = Collections.unmodifiableMap(m);
    }

    Zone remove(String map, String name) {
        Zone zn = get(map, name);
        if (zn == null) {
            return null;
        }
        Map<String, List<Zone>> m = new HashMap<>(byMap);
        List<Zone> list = new ArrayList<>(m.get(map));
        list.remove(zn);
        if (list.isEmpty()) {
            m.remove(map);
        } else {
            m.put(map, Collections.unmodifiableList(list));
        }
        byMap = Collections.unmodifiableMap(m);
        return zn;
    }

    void replaceAll(Collection<Zone> zones) {
        byMap = Map.of();
        seq = 0;
        for (Zone zn : zones) {
            put(zn);
        }
    }

    /** Зоны той же карты, пересекающиеся с данной (кроме неё самой). */
    List<Zone> overlapping(Zone zn) {
        List<Zone> out = new ArrayList<>();
        for (Zone o : on(zn.map())) {
            if (!o.name().equalsIgnoreCase(zn.name()) && o.intersects(zn)) {
                out.add(o);
            }
        }
        return out;
    }
}
