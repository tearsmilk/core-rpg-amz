package dev.tearsmilk.worldshader;

public final class ZoneCode {

    public static final int RED = 215;
    public static final int MAX_RADIUS = 64;
    public static final int MAX_STRENGTH = 31;
    public static final int SLOTS = 8;
    public static final int TAG_A = 0;
    public static final int INFO = 1;
    public static final int CENTER = 2;
    public static final int PROJ = 5;
    public static final int BOX = 21;
    public static final int TAG_B = 25;
    public static final int ROW_PIXELS = 26;
    public static final int[] TAG = {87, 83, 200};
    public static final int[] SIGNATURE_RGB = {23, 211, 97};
    public static final int SIGNATURE_ALPHA = 13;
    public static final int COPIES = 2;

    private ZoneCode() {
    }

    public record Decoded(int slot, int radius, int strength) {
    }

    public static boolean valid(int slot, int radius, int strength) {
        return slot >= 0 && slot < SLOTS && radius >= 1 && radius <= MAX_RADIUS && strength >= 1 && strength <= MAX_STRENGTH;
    }

    public static int rgb(int slot, int radius, int strength) {
        if (!valid(slot, radius, strength)) {
            throw new IllegalArgumentException("slot " + slot + ", radius " + radius + ", strength " + strength);
        }
        return (RED << 16) | (radius << 8) | (slot * 32 + strength);
    }

    public static Decoded decode(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        int slot = b / 32;
        int strength = b % 32;
        if (r != RED || !valid(slot, g, strength)) {
            return null;
        }
        return new Decoded(slot, g, strength);
    }

    public static int[] tag(int slot) {
        return new int[]{TAG[0], TAG[1], TAG[2] + slot};
    }

    public static int[] float24(float v) {
        int bits = Float.floatToRawIntBits(v);
        return new int[]{(bits >>> 24) & 255, (bits >>> 16) & 255, (bits >>> 8) & 255};
    }

    public static float fromFloat24(int[] bytes) {
        return Float.intBitsToFloat((bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8));
    }
}
