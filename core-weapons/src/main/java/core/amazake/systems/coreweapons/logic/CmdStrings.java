package core.amazake.systems.coreweapons.logic;

import java.util.ArrayList;
import java.util.List;

/** Строки custom_model_data: своя метка кадра — первой (её читает select index 0), чужие строки не трогаются. */
public final class CmdStrings {

    private CmdStrings() {
    }

    /** Список с кадром {@code frame} первым; прежние метки кадров ({@link Frames#PREFIX}) убраны. */
    public static List<String> with(final List<String> strings, final String frame) {
        final List<String> out = new ArrayList<>(without(strings));
        out.add(0, frame);
        return out;
    }

    /** Список без меток кадров. */
    public static List<String> without(final List<String> strings) {
        final List<String> out = new ArrayList<>();
        if (strings != null) {
            for (final String s : strings) {
                if (s != null && !s.startsWith(Frames.PREFIX)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public static boolean hasFrame(final List<String> strings) {
        if (strings == null) {
            return false;
        }
        for (final String s : strings) {
            if (s != null && s.startsWith(Frames.PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
