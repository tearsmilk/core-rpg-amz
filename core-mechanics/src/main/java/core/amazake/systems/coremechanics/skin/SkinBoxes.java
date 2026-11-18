package core.amazake.systems.coremechanics.skin;

import core.amazake.systems.coremechanics.physics.RagdollSpec;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.joml.Vector3f;

/**
 * Геометрия «кубиков скина» (перенесено из SkinCubes). Клиент показывает у головы только кусок 8×8×8, поэтому
 * туловище, руки и ноги режутся на коробки, и для каждой собирается скин, у которого голова — грани этой коробки.
 * Голову можно растянуть неравномерно, так что коробки 4×8, 4×4, 8×8 и 8×4 (глубина 4) ложатся в 8×8×8 без потери
 * пикселей: туловище — 2 головы, руки и ноги — по 2, всего {@link #COUNT} = 10.
 */
public final class SkinBoxes {

    /** Часть, базовый UV, UV второго слоя, ширина в пикселях (номинальная). */
    private static final int[][] PARTS = {
        {RagdollSpec.TORSO, 16, 16, 16, 32, 8},
        {RagdollSpec.ARM_R, 40, 16, 40, 32, 4},
        {RagdollSpec.ARM_L, 32, 48, 48, 48, 4},
        {RagdollSpec.LEG_R, 0, 16, 0, 32, 4},
        {RagdollSpec.LEG_L, 16, 48, 0, 48, 4}};
    /** Коробки: индекс в PARTS, x0 (от правого бока), y0 (от верха), ширина, высота — пиксели; глубина всегда 4. */
    private static final List<int[]> BOXES = new ArrayList<>();
    public static final int COUNT;

    static {
        for (int pi = 0; pi < PARTS.length; pi++) {
            final int w = PARTS[pi][5];
            BOXES.add(new int[] {pi, 0, 0, w, 8});
            BOXES.add(new int[] {pi, 0, 8, w, 4});
        }
        COUNT = BOXES.size();
    }

    // грани: верх, низ, правая, перед, левая, зад
    private static final int TOP = 0, BOTTOM = 1, RIGHT = 2, FRONT = 3, LEFT = 4, BACK = 5;
    /** Где грань лежит в развёртке головы 8×8×8. */
    private static final int[][] HEAD_FACE = {{8, 0}, {16, 0}, {0, 8}, {8, 8}, {16, 8}, {24, 8}};

    private SkinBoxes() {
    }

    /** Часть рэгдолла, к которой прикреплена коробка i. */
    public static int part(final int i) {
        return PARTS[BOXES.get(i)[0]][0];
    }

    /** Центр коробки i в осях части (x — вправо, y — вверх), блоки. */
    public static Vector3f offset(final int i) {
        final int[] b = BOXES.get(i);
        final int w = PARTS[b[0]][5];
        return new Vector3f((w / 2f - b[1] - b[3] / 2f) / 16f, (6 - b[2] - b[4] / 2f) / 16f, 0);
    }

    /** Масштаб головы-предмета (голова при масштабе 1 — 8 пикселей) под коробку i. */
    public static Vector3f scale(final int i) {
        final int[] b = BOXES.get(i);
        return new Vector3f(b[3] / 8f, b[4] / 8f, 0.5f);
    }

    public static boolean samePixels(final BufferedImage a, final BufferedImage b) {
        return Arrays.equals(a.getRGB(0, 0, 64, 64, null, 0, 64), b.getRGB(0, 0, 64, 64, null, 0, 64));
    }

    /** Скин 64×64, у которого голова — коробка i (растянута в 8×8 на грань), шляпа — второй слой коробки. */
    public static BufferedImage boxSkin(final BufferedImage src, final int i, final boolean slim) {
        final int[] bx = BOXES.get(i);
        final int[] p = PARTS[bx[0]];
        final boolean legacy = src.getHeight() < 64;
        int bu = p[1], bv = p[2];
        final int ou = p[3], ov = p[4];
        if (legacy && (p[0] == RagdollSpec.ARM_L || p[0] == RagdollSpec.LEG_L)) {
            bu = p[0] == RagdollSpec.ARM_L ? 40 : 0;   // старые скины 64×32: левые конечности = правые
            bv = 16;
        }
        final int nomW = p[5];
        final int w = (slim && (p[0] == RagdollSpec.ARM_R || p[0] == RagdollSpec.ARM_L)) ? 3 : nomW;
        final int x0 = bx[1], y0 = bx[2], bw = bx[3], bh = bx[4];
        final BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int f = 0; f < 6; f++) {
            final boolean exterior = switch (f) {
                case TOP -> y0 == 0;
                case BOTTOM -> y0 + bh == 12;
                case RIGHT -> x0 == 0;
                case LEFT -> x0 + bw == nomW;
                default -> true;
            };
            for (int layer = 0; layer < 2; layer++) {
                if (layer == 1 && (legacy || !exterior)) {
                    continue;
                }
                final int u = layer == 0 ? bu : ou, v = layer == 0 ? bv : ov;
                final int sf = exterior ? f : FRONT;   // внутренние грани не видны — залить лицевой
                final int[] r = faceRect(u, v, w, 12, 4, sf);
                final boolean side = sf == RIGHT || sf == LEFT, cap = sf == TOP || sf == BOTTOM;
                final int nw = side ? 4 : nomW, nh = cap ? 4 : 12;   // грань части, номинально
                final int fw = side ? 4 : bw, fh = cap ? 4 : bh;     // грань коробки
                final int fx = switch (sf) {
                    case BACK -> nomW - x0 - bw;
                    case RIGHT, LEFT -> 0;
                    default -> x0;
                };
                final int fy = cap ? 0 : y0;
                final int tx = HEAD_FACE[f][0] + (layer == 1 ? 32 : 0), ty = HEAD_FACE[f][1];
                for (int a = 0; a < 8; a++) {
                    for (int b = 0; b < 8; b++) {
                        final int sx = r[0] + (fx + a * fw / 8) * r[2] / nw;
                        final int sy = r[1] + (fy + b * fh / 8) * r[3] / nh;
                        int argb = sx < src.getWidth() && sy < src.getHeight() ? src.getRGB(sx, sy) : 0;
                        if (layer == 0) {
                            argb |= 0xFF000000;
                        }
                        out.setRGB(tx + a, ty + b, argb);
                    }
                }
            }
        }
        return out;
    }

    /** Прямоугольник грани коробки w×h×d в развёртке скина: x, y, ширина, высота. */
    static int[] faceRect(final int u, final int v, final int w, final int h, final int d, final int face) {
        return switch (face) {
            case TOP -> new int[] {u + d, v, w, d};
            case BOTTOM -> new int[] {u + d + w, v, w, d};
            case RIGHT -> new int[] {u, v + d, d, h};
            case FRONT -> new int[] {u + d, v + d, w, h};
            case LEFT -> new int[] {u + d + w, v + d, d, h};
            default -> new int[] {u + d + w + d, v + d, w, h};
        };
    }
}
