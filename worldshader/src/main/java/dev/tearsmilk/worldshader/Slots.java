package dev.tearsmilk.worldshader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Slots {

    private Slots() {
    }

    public record Claim(UUID id, int slot, long created) {
    }

    public static int free(Collection<Integer> used) {
        for (int s = 0; s < ZoneCode.SLOTS; s++) {
            if (!used.contains(s)) {
                return s;
            }
        }
        return -1;
    }

    public static Map<UUID, Integer> repair(List<Claim> claims) {
        List<Claim> sorted = new ArrayList<>(claims);
        sorted.sort(Comparator.comparingLong(Claim::created).thenComparing(c -> c.id().toString()));
        Set<Integer> used = new HashSet<>();
        List<Claim> clash = new ArrayList<>();
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (Claim c : sorted) {
            if (c.slot() >= 0 && c.slot() < ZoneCode.SLOTS && used.add(c.slot())) {
                out.put(c.id(), c.slot());
            } else {
                clash.add(c);
            }
        }
        for (Claim c : clash) {
            int s = free(used);
            if (s >= 0) {
                used.add(s);
            }
            out.put(c.id(), s);
        }
        return out;
    }
}
