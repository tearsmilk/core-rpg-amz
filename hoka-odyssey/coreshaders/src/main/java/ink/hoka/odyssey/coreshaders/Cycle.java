package ink.hoka.odyssey.coreshaders;

import java.util.List;

public final class Cycle {
    private Cycle() {
    }

    public static String next(List<String> items, String current, int step) {
        if (items == null || items.isEmpty()) {
            return current;
        }
        int i = items.indexOf(current);
        if (i < 0) {
            return items.get(0);
        }
        int n = items.size();
        return items.get(((i + step) % n + n) % n);
    }
}
