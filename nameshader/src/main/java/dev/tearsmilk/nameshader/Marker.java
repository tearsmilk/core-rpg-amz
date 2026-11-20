package dev.tearsmilk.nameshader;

public final class Marker {

    public static final int VANILLA_DISPLAY_BACKGROUND = 0x40000000;
    public static final int MARK_A = 180;
    public static final int MARK_R = 92;
    public static final int MARK_G = 14;
    public static final int MARK_B = 42;
    public static final int MARK_ARGB = (MARK_A << 24) | (MARK_R << 16) | (MARK_G << 8) | MARK_B;

    private Marker() {
    }

    public static boolean vanillaDefault(boolean defaultFlag, int argb) {
        return defaultFlag || argb == VANILLA_DISPLAY_BACKGROUND;
    }

    public static boolean eligible(boolean defaultFlag, int argb, boolean skipTagged, boolean tagFilterPassed) {
        return !skipTagged && tagFilterPassed && vanillaDefault(defaultFlag, argb);
    }

    public static String encode(boolean defaultFlag, int argb) {
        return (defaultFlag ? "1" : "0") + ":" + Integer.toHexString(argb);
    }

    public static Original decode(String s) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf(':');
        if (i != 1 || (s.charAt(0) != '0' && s.charAt(0) != '1')) {
            return null;
        }
        try {
            long v = Long.parseLong(s.substring(2), 16);
            if (v < 0 || v > 0xFFFFFFFFL) {
                return null;
            }
            return new Original(s.charAt(0) == '1', (int) v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Original(boolean defaultFlag, int argb) {
    }
}
