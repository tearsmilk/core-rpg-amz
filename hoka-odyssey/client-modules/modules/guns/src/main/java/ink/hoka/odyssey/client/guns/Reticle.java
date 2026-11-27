package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.platform.NativeImage;
import ink.hoka.odyssey.client.model.BbModel;
import ink.hoka.odyssey.client.model.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Круг оптики: то, что видно в прицел TACZ, — картинка, снятая с самой модели прицела. У прицела TACZ сетка — это
 * кубы кости {@code division} (далеко перед глазом: маска-круг из его текстуры, чёрная рамка вокруг, штрихи дальномера,
 * точка), а глаз в прицеле — кость {@code scope_view}, окуляр — {@code ocular_scope}. TACZ рисует сетку в мире через
 * трафарет окуляра; здесь те же грани (только смотрящие на глаз) один раз проецируются из точки scope_view на плоскость
 * взгляда и закрашиваются текстурой прицела в картинку размером с круг на экране. Её край — край окуляра (видимый из глаза радиус
 * ocular_scope), поэтому в HUD картинка ставится квадратом, вписанным в круг оптики, а снаружи — чёрное.
 * Сама сетка, текстура и их пропорции — как в модели TACZ, без правок.
 */
final class Reticle {
    private final Identifier id;
    private final List<float[]> quads;
    private final int[] src;
    private final int w;
    private final int h;
    private final float aperture;
    /** Размер нарисованной картинки (пиксели экрана), 0 — ещё нет. */
    private int size;
    /** Сколько граней сетки (для лога). */
    final int faces;

    private Reticle(Identifier id, List<float[]> quads, int[] src, int w, int h, float aperture) {
        this.id = id;
        this.quads = quads;
        this.src = src;
        this.w = w;
        this.h = h;
        this.aperture = aperture;
        this.faces = quads.size();
    }

    /**
     * Картинка круга оптики размером px×px (пиксели экрана: штрихи сетки тоньше пикселя не пропадают при
     * уменьшении) — перерисовывается, только когда размер круга на экране меняется.
     */
    Identifier texture(int px) {
        px = Math.max(16, Math.min(4096, px));
        if (px != size) {
            int[] out = rasterize(quads, src, w, h, px, aperture);
            NativeImage dst = new NativeImage(px, px, false);
            for (int y = 0; y < px; y++) for (int x = 0; x < px; x++) dst.setPixel(x, y, out[y * px + x]);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "hoka guns reticle", dst));
            size = px;
        }
        return id;
    }

    void close() {
        if (size > 0) Minecraft.getInstance().getTextureManager().release(id);
        size = 0;
    }

    /**
     * Сетка из модели m (её текстура номер texIndex — img): null, если у модели нет прицела (костей scope_view и
     * division) или грани сетки нарисованы другой текстурой.
     */
    static Reticle build(String path, BbModel m, int texIndex, NativeImage img, Logger log) {
        BbModel.Bone view = m.bone("scope_view");
        // первая сетка (division) — у окуляра оптики ocular_scope; division_2 и дальше — у других окуляров (коллиматор
        // сбоку у прицела 8x): их TACZ видит только через свой окуляр, в круг оптики они не попадают
        BbModel.Bone root = m.bone("division");
        if (view == null || root == null) return null;
        Matrix4f[] rest = new Matrix4f[m.bones.size()];
        Pose.compute(m, null, -1, rest);
        Vector3f eye = rest[view.index].transformPosition(new Vector3f(view.origin));
        List<float[]> quads = new ArrayList<>();
        collect(root, rest, eye, texIndex, quads);
        if (quads.isEmpty()) return null;
        // радиус окуляра из глаза (тангенс угла); нет окуляра — полразмера самой сетки
        float aperture = 0;
        BbModel.Bone ocular = m.bone("ocular_scope");
        if (ocular != null) {
            List<float[]> oq = new ArrayList<>();
            collect(ocular, rest, eye, -1, oq);
            for (float[] q : oq) for (int i = 0; i < 4; i++) aperture = Math.max(aperture, (float) Math.hypot(q[i * 4], q[i * 4 + 1]));
        }
        if (!(aperture > 1e-4f)) {
            for (float[] q : quads) for (int i = 0; i < 4; i++) aperture = Math.max(aperture, Math.abs(q[i * 4]));
        }
        int w = img.getWidth(), h = img.getHeight();
        int[] src = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) src[y * w + x] = img.getPixel(x, y);
        Identifier id = Identifier.fromNamespaceAndPath("hoka_guns", "reticle/" + sanitize(path));
        log.info("guns: сетка прицела {} — граней {}, окуляр ±{}", path, quads.size(), String.format(Locale.ROOT, "%.4f", aperture));
        return new Reticle(id, quads, src, w, h, aperture);
    }

    /**
     * Грани кости b и её детей (texIndex &lt; 0 — любые), обращённые к глазу: 4 вершины по {tx, ty, u, v} — тангенсы
     * угла от оси взгляда (x — вправо, y — вверх; глаз смотрит в −z модели) и uv.
     */
    private static void collect(BbModel.Bone b, Matrix4f[] rest, Vector3f eye, int texIndex, List<float[]> out) {
        Vector3f[] p = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        for (BbModel.Face f : b.faces) {
            if (texIndex >= 0 && f.texture() != texIndex) continue;
            float[] xyz = f.xyz();
            boolean ok = true;
            for (int i = 0; i < 4; i++) {
                rest[b.index].transformPosition(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], p[i]).sub(eye);
                if (p[i].z > -1e-3f) ok = false;                  // за глазом
            }
            if (!ok) continue;
            Vector3f n = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[3]).sub(p[0]));
            if (n.dot(p[0]) >= 0) continue;                        // смотрит от глаза (изнанка)
            float[] q = new float[16];
            float[] uv = f.uv();
            for (int i = 0; i < 4; i++) {
                q[i * 4] = p[i].x / -p[i].z;
                q[i * 4 + 1] = p[i].y / -p[i].z;
                q[i * 4 + 2] = uv[i * 2];
                q[i * 4 + 3] = uv[i * 2 + 1];
            }
            out.add(q);
        }
        for (BbModel.Bone c : b.children) collect(c, rest, eye, texIndex, out);
    }

    /**
     * Нарисовать грани (по порядку, поверх, с альфой) в картинку n×n ARGB: она охватывает тангенсы ±half по обеим
     * осям (y вверх). Текстура src w×h ARGB, uv 0..1, выборка по ближайшему текселю (пиксель-арт TACZ).
     */
    static int[] rasterize(List<float[]> quads, int[] src, int w, int h, int n, float half) {
        int[] dst = new int[n * n];
        float k = n / (2 * half);
        for (float[] q : quads) {
            float[] sx = new float[4], sy = new float[4];
            for (int i = 0; i < 4; i++) {
                sx[i] = (q[i * 4] + half) * k;
                sy[i] = (half - q[i * 4 + 1]) * k;
            }
            tri(dst, n, sx, sy, q, 0, 1, 2, src, w, h);
            tri(dst, n, sx, sy, q, 0, 2, 3, src, w, h);
        }
        return dst;
    }

    private static void tri(int[] dst, int n, float[] sx, float[] sy, float[] q, int a, int b, int c,
                            int[] src, int w, int h) {
        float minX = Math.min(sx[a], Math.min(sx[b], sx[c])), maxX = Math.max(sx[a], Math.max(sx[b], sx[c]));
        float minY = Math.min(sy[a], Math.min(sy[b], sy[c])), maxY = Math.max(sy[a], Math.max(sy[b], sy[c]));
        int x0 = Math.max(0, (int) Math.floor(minX)), x1 = Math.min(n - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY)), y1 = Math.min(n - 1, (int) Math.ceil(maxY));
        float den = (sy[b] - sy[c]) * (sx[a] - sx[c]) + (sx[c] - sx[b]) * (sy[a] - sy[c]);
        if (Math.abs(den) < 1e-12f) return;
        // тонкий штрих уже пикселя всё равно должен быть виден: такой треугольник берёт ближайший к центру пиксель
        boolean thin = maxX - minX < 1 || maxY - minY < 1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float l1 = ((sy[b] - sy[c]) * (px - sx[c]) + (sx[c] - sx[b]) * (py - sy[c])) / den;
                float l2 = ((sy[c] - sy[a]) * (px - sx[c]) + (sx[a] - sx[c]) * (py - sy[c])) / den;
                float l3 = 1 - l1 - l2;
                if (l1 < 0 || l2 < 0 || l3 < 0) {
                    if (!thin || !nearSegment(px, py, sx, sy, a, b, c)) continue;
                    l1 = Math.max(0, l1);
                    l2 = Math.max(0, l2);
                    l3 = Math.max(0, 1 - l1 - l2);
                }
                float u = l1 * q[a * 4 + 2] + l2 * q[b * 4 + 2] + l3 * q[c * 4 + 2];
                float v = l1 * q[a * 4 + 3] + l2 * q[b * 4 + 3] + l3 * q[c * 4 + 3];
                int tx = Math.min(w - 1, Math.max(0, (int) Math.floor(u * w)));
                int ty = Math.min(h - 1, Math.max(0, (int) Math.floor(v * h)));
                dst[y * n + x] = over(src[ty * w + tx], dst[y * n + x]);
            }
        }
    }

    /** Центр пикселя ближе полупикселя к треугольнику (для штрихов тоньше пикселя). */
    private static boolean nearSegment(float px, float py, float[] sx, float[] sy, int a, int b, int c) {
        int[] idx = {a, b, c};
        for (int i = 0; i < 3; i++) {
            int p = idx[i], r = idx[(i + 1) % 3];
            float dx = sx[r] - sx[p], dy = sy[r] - sy[p];
            float len2 = dx * dx + dy * dy;
            float t = len2 < 1e-12f ? 0 : Math.max(0, Math.min(1, ((px - sx[p]) * dx + (py - sy[p]) * dy) / len2));
            float qx = sx[p] + t * dx - px, qy = sy[p] + t * dy - py;
            if (qx * qx + qy * qy <= 0.25f) return true;
        }
        return false;
    }

    /** ARGB: src поверх dst. */
    static int over(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 0) return dst;
        if (sa == 255) return src;
        int da = dst >>> 24;
        float a = sa / 255f, b = da / 255f * (1 - a), oa = a + b;
        if (oa <= 0) return 0;
        int r = Math.round((((src >> 16) & 255) * a + ((dst >> 16) & 255) * b) / oa);
        int g = Math.round((((src >> 8) & 255) * a + ((dst >> 8) & 255) * b) / oa);
        int bl = Math.round(((src & 255) * a + (dst & 255) * b) / oa);
        return (Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | bl;
    }

    private static String sanitize(String p) {
        StringBuilder sb = new StringBuilder();
        for (char c : p.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }
}
