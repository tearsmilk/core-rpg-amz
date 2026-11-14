package core.amazake.systems.coremobs.geo;

import java.util.Locale;

/** Координаты блока. Строка вида {@code "10,64,-20"} — так точки хранятся в regions.yml. */
public record BlockPos(int x, int y, int z) {

    public static BlockPos parse(final String text) {
        final String[] parts = text.trim().split("\\s*,\\s*");
        if (parts.length != 3) {
            throw new IllegalArgumentException("точка «" + text + "» должна быть в виде x,y,z");
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("точка «" + text + "»: координаты должны быть целыми числами");
        }
    }

    public String format() {
        return String.format(Locale.ROOT, "%d,%d,%d", this.x, this.y, this.z);
    }
}
