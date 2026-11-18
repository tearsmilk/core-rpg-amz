package core.amazake.systems.coremechanics.skin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coremechanics.doll.Piece;
import core.amazake.systems.coremechanics.physics.RagdollSpec;
import java.awt.image.BufferedImage;
import java.util.List;
import org.bukkit.Material;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SkinTest {

    private static void fill(final BufferedImage img, final int x0, final int y0, final int x1, final int y1, final int argb) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                img.setRGB(x, y, argb);
            }
        }
    }

    @Test
    void averageIgnoresTransparentPixels() {
        final BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        fill(img, 0, 0, 2, 4, 0xFFFF0000);   // красная половина
        fill(img, 2, 0, 4, 4, 0x000000FF);   // прозрачная синяя — не считается
        assertArrayEquals(new int[] {255, 0, 0}, SkinColors.avg(img, 0, 0, 4, 4));
        final BufferedImage empty = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        assertArrayEquals(new int[] {120, 120, 120}, SkinColors.avg(empty, 0, 0, 4, 4));
        // за краем картинки (старый скин 64×32) — без исключений
        assertArrayEquals(new int[] {120, 120, 120}, SkinColors.avg(empty, 2, 2, 40, 40));
    }

    @Test
    void nearestPaletteBlock() {
        assertEquals(Material.WHITE_CONCRETE, SkinColors.nearest(new int[] {207, 213, 214}));
        assertEquals(Material.RED_CONCRETE, SkinColors.nearest(new int[] {150, 30, 30}));
        assertEquals(Material.COAL_BLOCK, SkinColors.nearest(new int[] {16, 15, 15}));
    }

    @Test
    void bodyColorsFromSkinRegions() {
        final BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(skin, 20, 20, 28, 32, 0xFFCFD5D6);   // рубашка белая
        fill(skin, 44, 20, 48, 26, 0xFF8E2121);   // рукав красный
        fill(skin, 44, 28, 48, 32, 0xFFD2B2A1);   // кисть — белая терракота
        fill(skin, 4, 20, 8, 28, 0xFF2D2F8F);     // штаны синие
        fill(skin, 4, 29, 8, 32, 0xFF080A0F);     // обувь чёрная
        final BodyColors c = SkinColors.of(skin);
        assertEquals(Material.WHITE_CONCRETE, c.shirt());
        assertEquals(Material.RED_CONCRETE, c.sleeve());
        assertEquals(Material.WHITE_TERRACOTTA, c.hand());
        assertEquals(Material.BLUE_CONCRETE, c.pants());
        assertEquals(Material.BLACK_CONCRETE, c.shoes());
    }

    @Test
    void tenCubesCoverBody() {
        assertEquals(10, SkinBoxes.COUNT);
        int torso = 0;
        for (int i = 0; i < SkinBoxes.COUNT; i++) {
            if (SkinBoxes.part(i) == RagdollSpec.TORSO) {
                torso++;
            }
            final Vector3f sc = SkinBoxes.scale(i);
            assertEquals(0.5f, sc.z);
        }
        assertEquals(2, torso);
        // туловище: верхняя коробка 8×8 пикселей (масштаб 1×1), нижняя 8×4; центры на +2/16 и −4/16 от центра части
        assertEquals(new Vector3f(1f, 1f, 0.5f), SkinBoxes.scale(0));
        assertEquals(new Vector3f(1f, 0.5f, 0.5f), SkinBoxes.scale(1));
        assertEquals(2f / 16f, SkinBoxes.offset(0).y, 1e-6);
        assertEquals(-4f / 16f, SkinBoxes.offset(1).y, 1e-6);
    }

    @Test
    void boxSkinCopiesFrontFaceIntoHeadFront() {
        final BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(skin, 20, 20, 28, 32, 0xFF112233);   // перед туловища
        final BufferedImage head = SkinBoxes.boxSkin(skin, 0, false);
        assertEquals(64, head.getWidth());
        // перед головы — (8..16, 8..16) развёртки
        assertEquals(0xFF112233, head.getRGB(8, 8));
        assertEquals(0xFF112233, head.getRGB(15, 15));
        assertTrue(SkinBoxes.samePixels(head, SkinBoxes.boxSkin(skin, 0, false)));
        assertFalse(SkinBoxes.samePixels(head, SkinBoxes.boxSkin(skin, 2, false)));
    }

    @Test
    void looks() {
        final List<Piece> blocks = BodyLooks.blocks(BodyColors.STEVE);
        assertEquals(9, blocks.size());   // туловище + 2×(рукав, кисть) + 2×(штаны, обувь)
        assertEquals("block:minecraft:cyan_terracotta", blocks.get(0).look());
        final String[][] tex = new String[SkinBoxes.COUNT][];
        for (int i = 0; i < tex.length; i++) {
            tex[i] = new String[] {"v" + i, "s" + i};
        }
        final List<Piece> cubes = BodyLooks.cubes(tex);
        assertEquals(10, cubes.size());
        assertTrue(cubes.get(3).head());
        assertEquals("head:v3", cubes.get(3).look());
        assertEquals("head:", BodyLooks.head(null, null).look());
    }
}
