package core.amazake.systems.coremechanics.skin;

import java.awt.image.BufferedImage;
import org.bukkit.Material;

/** Средние цвета частей скина → ближайшие блоки (перенесено из RagdollManager без изменений палитры). */
public final class SkinColors {

    private static final Object[][] PALETTE = {
        {Material.WHITE_CONCRETE, 207, 213, 214}, {Material.ORANGE_CONCRETE, 224, 97, 0}, {Material.MAGENTA_CONCRETE, 169, 48, 159},
        {Material.LIGHT_BLUE_CONCRETE, 36, 137, 199}, {Material.YELLOW_CONCRETE, 241, 175, 21}, {Material.LIME_CONCRETE, 94, 168, 24},
        {Material.PINK_CONCRETE, 214, 101, 143}, {Material.GRAY_CONCRETE, 55, 58, 62}, {Material.LIGHT_GRAY_CONCRETE, 125, 125, 115},
        {Material.CYAN_CONCRETE, 21, 119, 136}, {Material.PURPLE_CONCRETE, 100, 32, 156}, {Material.BLUE_CONCRETE, 45, 47, 143},
        {Material.BROWN_CONCRETE, 96, 60, 32}, {Material.GREEN_CONCRETE, 73, 91, 36}, {Material.RED_CONCRETE, 142, 33, 33},
        {Material.BLACK_CONCRETE, 8, 10, 15},
        {Material.WHITE_TERRACOTTA, 210, 178, 161}, {Material.ORANGE_TERRACOTTA, 162, 84, 38}, {Material.MAGENTA_TERRACOTTA, 150, 88, 109},
        {Material.LIGHT_BLUE_TERRACOTTA, 113, 109, 138}, {Material.YELLOW_TERRACOTTA, 186, 133, 35}, {Material.LIME_TERRACOTTA, 104, 118, 53},
        {Material.PINK_TERRACOTTA, 162, 78, 79}, {Material.GRAY_TERRACOTTA, 58, 42, 36}, {Material.LIGHT_GRAY_TERRACOTTA, 135, 107, 98},
        {Material.CYAN_TERRACOTTA, 87, 91, 91}, {Material.PURPLE_TERRACOTTA, 118, 70, 86}, {Material.BLUE_TERRACOTTA, 74, 60, 91},
        {Material.BROWN_TERRACOTTA, 77, 51, 36}, {Material.GREEN_TERRACOTTA, 76, 83, 42}, {Material.RED_TERRACOTTA, 143, 61, 47},
        {Material.BLACK_TERRACOTTA, 37, 23, 16}, {Material.TERRACOTTA, 152, 94, 68},
        {Material.BIRCH_PLANKS, 192, 175, 121}, {Material.PACKED_MUD, 142, 107, 80}, {Material.SMOOTH_SANDSTONE, 223, 214, 170},
        {Material.SNOW_BLOCK, 249, 254, 254}, {Material.COAL_BLOCK, 16, 15, 15}};

    private SkinColors() {
    }

    /** Цвета тела по скину 64×64 (или старому 64×32). */
    public static BodyColors of(final BufferedImage img) {
        return new BodyColors(
            nearest(avg(img, 20, 20, 28, 32)),
            nearest(avg(img, 44, 20, 48, 26)),
            nearest(avg(img, 44, 28, 48, 32)),
            nearest(avg(img, 4, 20, 8, 28)),
            nearest(avg(img, 4, 29, 8, 32)));
    }

    /** Средний цвет непрозрачных (альфа ≥ 128) пикселей прямоугольника [x0, x1) × [y0, y1); пусто — серый. */
    public static int[] avg(final BufferedImage img, final int x0, final int y0, final int x1, final int y1) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int x = x0; x < Math.min(x1, img.getWidth()); x++) {
            for (int y = y0; y < Math.min(y1, img.getHeight()); y++) {
                final int argb = img.getRGB(x, y);
                if ((argb >>> 24) < 128) {
                    continue;
                }
                r += (argb >> 16) & 255;
                g += (argb >> 8) & 255;
                b += argb & 255;
                n++;
            }
        }
        return n == 0 ? new int[] {120, 120, 120} : new int[] {(int) (r / n), (int) (g / n), (int) (b / n)};
    }

    /** Ближайший блок палитры (взвешенное расстояние RGB 3:4:2). */
    public static Material nearest(final int[] c) {
        Material best = Material.GRAY_CONCRETE;
        long bd = Long.MAX_VALUE;
        for (final Object[] e : PALETTE) {
            final long dr = c[0] - (int) e[1], dg = c[1] - (int) e[2], db = c[2] - (int) e[3];
            final long d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bd) {
                bd = d;
                best = (Material) e[0];
            }
        }
        return best;
    }
}
