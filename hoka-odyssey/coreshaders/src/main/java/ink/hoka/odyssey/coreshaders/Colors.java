package ink.hoka.odyssey.coreshaders;

import java.util.Locale;
import java.util.Map;

public final class Colors {
    private Colors() {
    }

    public static Integer parse(String input, Map<String, Integer> names) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (names != null) {
            Integer named = names.get(s.toLowerCase(Locale.ROOT));
            if (named != null) {
                return named;
            }
        }
        String hex = s;
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.length() == 3 && isHex(hex)) {
            int r = Character.digit(hex.charAt(0), 16);
            int g = Character.digit(hex.charAt(1), 16);
            int b = Character.digit(hex.charAt(2), 16);
            return (r * 17 << 16) | (g * 17 << 8) | (b * 17);
        }
        if (hex.length() == 6 && isHex(hex)) {
            return Integer.parseInt(hex, 16);
        }
        String[] parts = s.split("[,;\\s]+");
        if (parts.length == 3) {
            int[] c = new int[3];
            for (int i = 0; i < 3; i++) {
                try {
                    c[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (c[i] < 0 || c[i] > 255) {
                    return null;
                }
            }
            return (c[0] << 16) | (c[1] << 8) | c[2];
        }
        return null;
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
