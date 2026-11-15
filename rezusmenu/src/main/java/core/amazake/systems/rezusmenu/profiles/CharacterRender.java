package core.amazake.systems.rezusmenu.profiles;

/**
 * Персонаж игрока «в три четверти» из его скина — программный рендер в картинку ARGB (0 — прозрачно), которую
 * экран рисует «пикселями» текста. Модель — классическая (руки 4 или 3 px), второй слой (шляпа, куртка, рукава,
 * штаны) чуть больше основного, ортографическая камера сверху-сбоку, свет сверху-слева, тёмный контур.
 * Чистая логика: без сервера (то же в tools/profiles_preview.py).
 *
 * <p>Почему не манекен в мире: в меню мышь поворачивает голову наблюдателя (на этом стоит курсор), и всё, что
 * стоит в мире, уезжает по экрану при каждом движении мыши. Картинка из дисплеев привязана к камере.</p>
 */
public final class CharacterRender {

    /** Картинка {@code w × h}; (feetX, feetY) — пиксель точки между ступнями (для выравнивания на холсте). */
    public record Sprite(int w, int h, int[] argb, int feetX, int feetY) {
    }

    /** Часть модели: прямоугольник развёртки скина (u, v), размер (w, h, d), угол коробки, раздувание. */
    private record Part(int u, int v, int w, int h, int d, double x0, double y0, double z0, double inflate,
                        boolean overlay) {
    }

    private static final int OUTLINE = 0xFF141218;
    private static final int SUB = 3;

    private CharacterRender() {
    }

    /** Скин 64 × 32 (старый формат) → 64 × 64: левые рука и нога — зеркало правых, второго слоя нет. */
    public static int[] normalize(final int[] skin, final int width, final int height) {
        if (width != 64 || (height != 64 && height != 32) || skin.length < width * height) {
            return null;
        }
        if (height == 64) {
            return skin.clone();
        }
        final int[] out = new int[64 * 64];
        System.arraycopy(skin, 0, out, 0, 64 * 32);
        mirrorBox(out, 40, 16, 4, 12, 4, 32, 48);
        mirrorBox(out, 0, 16, 4, 12, 4, 16, 48);
        return out;
    }

    /** Развёртку коробки (u, v, w, h, d) — зеркально в (tu, tv). */
    private static void mirrorBox(final int[] img, final int u, final int v, final int w, final int h, final int d,
                                  final int tu, final int tv) {
        final int tw = 2 * d + 2 * w;
        final int th = d + h;
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                img[(tv + y) * 64 + tu + x] = img[(v + y) * 64 + u + (tw - 1 - x)];
            }
        }
    }

    private static Part[] parts(final boolean slim) {
        final int a = slim ? 3 : 4;
        return new Part[] {
            new Part(0, 0, 8, 8, 8, -4, 24, -4, 0, false),
            new Part(16, 16, 8, 12, 4, -4, 12, -2, 0, false),
            new Part(40, 16, a, 12, 4, -4 - a, 12, -2, 0, false),
            new Part(32, 48, a, 12, 4, 4, 12, -2, 0, false),
            new Part(0, 16, 4, 12, 4, -4, 0, -2, 0, false),
            new Part(16, 48, 4, 12, 4, 0, 0, -2, 0, false),
            new Part(32, 0, 8, 8, 8, -4, 24, -4, 0.5, true),
            new Part(16, 32, 8, 12, 4, -4, 12, -2, 0.25, true),
            new Part(40, 32, a, 12, 4, -4 - a, 12, -2, 0.25, true),
            new Part(48, 48, a, 12, 4, 4, 12, -2, 0.25, true),
            new Part(0, 32, 4, 12, 4, -4, 0, -2, 0.25, true),
            new Part(0, 48, 4, 12, 4, 0, 0, -2, 0.25, true),
        };
    }

    /**
     * Нарисовать персонажа. {@code skin} — 64 × 64 ARGB ({@link #normalize}); {@code yaw} — поворот модели, градусы
     * (положительный — лицом левее); {@code pitch} — насколько камера смотрит сверху; {@code scale} — пикселей
     * картинки на пиксель скина.
     */
    public static Sprite render(final int[] skin, final boolean slim, final double yaw, final double pitch,
                                final double scale) {
        final double cy = Math.cos(Math.toRadians(yaw));
        final double sy = Math.sin(Math.toRadians(yaw));
        final double cp = Math.cos(Math.toRadians(pitch));
        final double sp = Math.sin(Math.toRadians(pitch));
        final Part[] parts = parts(slim);
        // границы: все углы всех коробок
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (final Part p : parts) {
            for (int c = 0; c < 8; c++) {
                final double x = p.x0() - p.inflate() + ((c & 1) == 0 ? 0 : p.w() + 2 * p.inflate());
                final double y = p.y0() - p.inflate() + ((c & 2) == 0 ? 0 : p.h() + 2 * p.inflate());
                final double z = p.z0() - p.inflate() + ((c & 4) == 0 ? 0 : p.d() + 2 * p.inflate());
                final double[] v = view(x, y, z, cy, sy, cp, sp);
                minX = Math.min(minX, v[0]);
                maxX = Math.max(maxX, v[0]);
                minY = Math.min(minY, v[1]);
                maxY = Math.max(maxY, v[1]);
            }
        }
        final int pad = 1;
        final int w = (int) Math.ceil((maxX - minX) * scale - 1e-9) + 2 * pad;
        final int h = (int) Math.ceil((maxY - minY) * scale - 1e-9) + 2 * pad;
        final int[] out = new int[w * h];
        final double[] depth = new double[w * h];
        java.util.Arrays.fill(depth, -Double.MAX_VALUE);
        final double ox = -minX * scale + pad;
        final double oy = maxY * scale + pad;
        for (final Part p : parts) {
            for (int face = 0; face < 5; face++) {
                drawFace(skin, p, face, out, depth, w, h, ox, oy, scale, cy, sy, cp, sp);
            }
        }
        outline(out, w, h);
        final double[] feet = view(0, 0, 0, cy, sy, cp, sp);
        return new Sprite(w, h, out, (int) Math.round(ox + feet[0] * scale), (int) Math.round(oy - feet[1] * scale));
    }

    /** Модель → вид: поворот вокруг вертикали на yaw, затем наклон камеры; ответ — (x, y вверх, глубина к камере). */
    private static double[] view(final double x, final double y, final double z, final double cy, final double sy,
                                 final double cp, final double sp) {
        final double rx = x * cy + z * sy;
        final double rz = -x * sy + z * cy;
        return new double[] {rx, y * cp - rz * sp, rz * cp + y * sp};
    }

    /** Грань: 0 перед (+z), 1 зад, 2 правый бок персонажа (−x), 3 левый (+x), 4 верх. */
    private static void drawFace(final int[] skin, final Part p, final int face, final int[] out, final double[] depth,
                                 final int w, final int h, final double ox, final double oy, final double scale,
                                 final double cy, final double sy, final double cp, final double sp) {
        final double[] n = switch (face) {
            case 0 -> new double[] {0, 0, 1};
            case 1 -> new double[] {0, 0, -1};
            case 2 -> new double[] {-1, 0, 0};
            case 3 -> new double[] {1, 0, 0};
            default -> new double[] {0, 1, 0};
        };
        final double[] nv = view(n[0], n[1], n[2], cy, sy, cp, sp);
        if (nv[2] <= 1e-6) {
            return; // грань отвёрнута от камеры
        }
        // как свет сущностей в игре: верх светлее всего, перед чуть темнее, бока заметно темнее
        final double lit = switch (face) {
            case 4 -> 1.0;
            case 0 -> 0.9;
            case 1 -> 0.62;
            default -> 0.72;
        };
        final double in = p.inflate();
        final double bx = p.x0() - in;
        final double by = p.y0() - in;
        final double bz = p.z0() - in;
        final double bw = p.w() + 2 * in;
        final double bh = p.h() + 2 * in;
        final double bd = p.d() + 2 * in;
        final int u0;
        final int v0;
        final int tw;
        final int th;
        switch (face) {
            case 0 -> { u0 = p.u() + p.d(); v0 = p.v() + p.d(); tw = p.w(); th = p.h(); }
            case 1 -> { u0 = p.u() + 2 * p.d() + p.w(); v0 = p.v() + p.d(); tw = p.w(); th = p.h(); }
            case 2 -> { u0 = p.u(); v0 = p.v() + p.d(); tw = p.d(); th = p.h(); }
            case 3 -> { u0 = p.u() + p.d() + p.w(); v0 = p.v() + p.d(); tw = p.d(); th = p.h(); }
            default -> { u0 = p.u() + p.d(); v0 = p.v(); tw = p.w(); th = p.d(); }
        }
        // чем ближе к камере грань, тем чуть дальше её точки вперёд: оверлей всегда поверх основы
        final double bias = p.overlay() ? 0.01 : 0;
        for (int j = 0; j < th; j++) {
            for (int i = 0; i < tw; i++) {
                final int tex = skin[(v0 + j) * 64 + u0 + i];
                final int a = tex >>> 24;
                if (p.overlay() && a < 128) {
                    continue;
                }
                final int color = shade(tex, lit);
                for (int sj = 0; sj < SUB; sj++) {
                    for (int si = 0; si < SUB; si++) {
                        final double fa = (i + (si + 0.5) / SUB) / tw;
                        final double fb = (j + (sj + 0.5) / SUB) / th;
                        final double x;
                        final double y;
                        final double z;
                        switch (face) {
                            case 0 -> { x = bx + fa * bw; y = by + bh - fb * bh; z = bz + bd; }
                            case 1 -> { x = bx + bw - fa * bw; y = by + bh - fb * bh; z = bz; }
                            case 2 -> { x = bx; y = by + bh - fb * bh; z = bz + fa * bd; }
                            case 3 -> { x = bx + bw; y = by + bh - fb * bh; z = bz + bd - fa * bd; }
                            default -> { x = bx + fa * bw; y = by + bh; z = bz + fb * bd; }
                        }
                        final double[] v = view(x, y, z, cy, sy, cp, sp);
                        final int px = (int) Math.floor(ox + v[0] * scale);
                        final int py = (int) Math.floor(oy - v[1] * scale);
                        if (px < 0 || py < 0 || px >= w || py >= h) {
                            continue;
                        }
                        final int k = py * w + px;
                        final double dz = v[2] + bias;
                        if (dz > depth[k]) {
                            depth[k] = dz;
                            out[k] = color;
                        }
                    }
                }
            }
        }
    }

    private static int shade(final int argb, final double k) {
        final int r = (int) Math.round(((argb >> 16) & 0xFF) * k);
        final int g = (int) Math.round(((argb >> 8) & 0xFF) * k);
        final int b = (int) Math.round((argb & 0xFF) * k);
        return 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
    }

    /** Тёмный контур в 1 пиксель вокруг силуэта (по 4 соседям). */
    private static void outline(final int[] img, final int w, final int h) {
        final boolean[] ring = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (img[y * w + x] != 0) {
                    continue;
                }
                if ((x > 0 && img[y * w + x - 1] != 0 && img[y * w + x - 1] != OUTLINE)
                    || (x < w - 1 && img[y * w + x + 1] != 0 && img[y * w + x + 1] != OUTLINE)
                    || (y > 0 && img[(y - 1) * w + x] != 0 && img[(y - 1) * w + x] != OUTLINE)
                    || (y < h - 1 && img[(y + 1) * w + x] != 0 && img[(y + 1) * w + x] != OUTLINE)) {
                    ring[y * w + x] = true;
                }
            }
        }
        for (int i = 0; i < ring.length; i++) {
            if (ring[i]) {
                img[i] = OUTLINE;
            }
        }
    }
}
