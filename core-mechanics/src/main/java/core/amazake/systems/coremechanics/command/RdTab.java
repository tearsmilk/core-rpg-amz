package core.amazake.systems.coremechanics.command;

import core.amazake.systems.coremechanics.statue.DollPoses;
import core.amazake.systems.coremechanics.statue.StatueManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Автодополнение /rd (чистая функция: имена игроков передаются снаружи). */
public final class RdTab {

    /** Подкоманды первого уровня (кроме ника). */
    public static final List<String> ROOT;

    static {
        final List<String> r = new ArrayList<>(List.of("clear", "info"));
        r.addAll(StatueManager.SUBS);
        ROOT = List.copyOf(r);
    }

    private RdTab() {
    }

    /**
     * @param online ники игроков в сети
     * @param known  ники всех известных серверу игроков (для /rd ник); могут повторять online
     */
    public static List<String> complete(final String[] a, final Collection<String> online, final Collection<String> known) {
        final List<String> out = new ArrayList<>();
        if (a.length == 0) {
            return out;
        }
        final String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            add(out, a[0], ROOT);
            add(out, a[0], online);
            for (final String n : known) {
                if (out.size() >= 60) {
                    break;
                }
                if (!out.contains(n) && n.toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))) {
                    out.add(n);
                }
            }
            return out;
        }
        if (a.length == 2) {
            switch (sub) {
                case "pose" -> add(out, a[1], DollPoses.POSES.keySet());
                case "anim" -> {
                    add(out, a[1], DollPoses.ANIMS.keySet());
                    add(out, a[1], List.of("stop"));
                }
                case "frame" -> add(out, a[1], List.of("add", "clear", "play", "stop", "speed"));
                case "statue" -> add(out, a[1], online);
                default -> {
                    // остальные подкоманды без аргументов
                }
            }
            return out;
        }
        if (a.length == 3) {
            if (sub.equals("statue")) {
                add(out, a[2], DollPoses.POSES.keySet());
                add(out, a[2], DollPoses.ANIMS.keySet());
            } else if (sub.equals("frame") && a[1].equalsIgnoreCase("speed")) {
                add(out, a[2], List.of("2", "5", "10", "20"));
            }
        }
        return out;
    }

    private static void add(final List<String> out, final String pre, final Iterable<String> vs) {
        final String p = pre.toLowerCase(Locale.ROOT);
        for (final String v : vs) {
            if (v.toLowerCase(Locale.ROOT).startsWith(p) && !out.contains(v)) {
                out.add(v);
            }
        }
    }
}
