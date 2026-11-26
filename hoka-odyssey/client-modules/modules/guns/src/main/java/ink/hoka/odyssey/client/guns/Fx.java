package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Эффекты в мире: трассеры пуль (свои — сразу при выстреле, чужие — по TRACER с сервера) и вспышки у ствола других
 * игроков. Трассер — светящаяся лента вдоль полёта, всегда повёрнутая к камере; до первого блока на пути
 * (проверка лучом на клиенте), дальше не рисуется. Вспышка — картинка TACZ лицом к камере, ~50 мс.
 */
final class Fx {
    private static final int MAX = 256;
    /** Длина хвоста трассера: столько секунд полёта (у АКМ ~25 блоков), но не больше 40 блоков. */
    private static final double TAIL_SEC = 0.05;
    /** Свой трассер прячется первые N блоков (не мешать прицелу). */
    private static final double OWN_SKIP = 2.5;

    private static final class Tracer {
        final Vec3 o;
        final Vec3 dir;
        final double speed;
        final double maxDist;
        final long born;
        final int rgb;
        final double skip;
        /** Уже нарисован хоть раз: первый кадр после выстрела всегда показывает начало пути (при низком FPS пуля
         *  иначе успевает улететь за десятки блоков до первого кадра, и трассер у ствола не виден вовсе). */
        boolean shown;

        Tracer(Vec3 o, Vec3 dir, double speed, double maxDist, long born, int rgb, double skip) {
            this.o = o;
            this.dir = dir;
            this.speed = speed;
            this.maxDist = maxDist;
            this.born = born;
            this.rgb = rgb;
            this.skip = skip;
        }
    }

    private static final class Flash {
        final Vec3 at;
        final long born;
        final float size;
        final float spin;
        /** Нарисована хоть раз (при низком FPS вспышка иначе исчезала бы до первого кадра). */
        boolean shown;

        Flash(Vec3 at, long born, float size, float spin) {
            this.at = at;
            this.born = born;
            this.size = size;
            this.spin = spin;
        }
    }

    private final List<Tracer> tracers = new ArrayList<>();
    /** Белая точка 2×2 — текстура трассера (рисуется светящимся полупрозрачным типом сущностей: «молния» игры
     *  в этом проходе кадра не выводится). */
    private Identifier white;
    private final List<Flash> flashes = new ArrayList<>();

    void clear() {
        tracers.clear();
        flashes.clear();
    }

    void close() {
        clear();
        if (white != null) {
            Minecraft.getInstance().getTextureManager().release(white);
            white = null;
        }
    }

    private Identifier white() {
        if (white == null) {
            com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(2, 2, false);
            img.fillRect(0, 0, 2, 2, 0xFFFFFFFF);
            white = Identifier.fromNamespaceAndPath("hoka_guns", "white");
            Minecraft.getInstance().getTextureManager().register(white,
                    new net.minecraft.client.renderer.texture.DynamicTexture(() -> "hoka guns white", img));
        }
        return white;
    }

    /** Трассер из o со скоростью v (блоков/с), живёт life с; own — свой (хвост начинается дальше от глаз). */
    void tracer(Vec3 o, Vec3 v, double life, int rgb, boolean own) {
        Minecraft mc = Minecraft.getInstance();
        double speed = v.length();
        if (speed < 1e-3 || mc.level == null) return;
        Vec3 dir = v.scale(1 / speed);
        double max = Math.min(speed * life, 300);
        try {
            HitResult hit = mc.level.clip(new ClipContext(o, o.add(dir.scale(max)), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) max = hit.getLocation().distanceTo(o);
        } catch (RuntimeException ignored) {
            // нет мира рядом — до конца жизни
        }
        if (tracers.size() >= MAX) tracers.remove(0);
        tracers.add(new Tracer(o, dir, speed, max, System.nanoTime(), rgb, own ? OWN_SKIP : 0));
    }

    /** Вспышка у ствола другого игрока. */
    void flash(Vec3 at, float size) {
        if (flashes.size() >= MAX) flashes.remove(0);
        flashes.add(new Flash(at, System.nanoTime(), size, (float) (Math.random() * Math.PI * 2)));
    }

    /**
     * Нарисовать. camToBuf — из системы камеры (x вправо, y вверх, −z вперёд) в буфер кадра; cam — положение камеры,
     * rot — её поворот (камера → мир). Точки мира переводятся в систему камеры здесь.
     */
    void render(MultiBufferSource buffers, Matrix4f camToBuf, Vec3 cam, Quaternionf rot, Identifier flashTex) {
        long now = System.nanoTime();
        Matrix4f pose = new Matrix4f(camToBuf).rotate(new Quaternionf(rot).conjugate());
        if (!tracers.isEmpty()) {
            VertexConsumer vc = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(white()));
            for (Iterator<Tracer> it = tracers.iterator(); it.hasNext(); ) {
                Tracer t = it.next();
                double el = (now - t.born) / 1e9;
                double len = Math.min(40, t.speed * TAIL_SEC);
                double head = t.speed * el;
                if (!t.shown) {
                    head = Math.min(head, t.skip + len);
                    t.shown = true;
                }
                double tail = Math.max(t.skip, head - len);
                if (tail >= t.maxDist) {
                    it.remove();
                    continue;
                }
                head = Math.min(head, t.maxDist);
                if (head <= tail) continue;
                Vec3 a = t.o.add(t.dir.scale(tail)).subtract(cam);
                Vec3 b = t.o.add(t.dir.scale(head)).subtract(cam);
                // ширина растёт с расстоянием до камеры — вдали трассер не тоньше пикселя
                ribbon(vc, pose, a, b, width(a, 0.035f), width(b, 0.035f), t.rgb, 0.15f, 0.9f);
                ribbon(vc, pose, a, b, width(a, 0.012f), width(b, 0.012f), 0xFFFFFF, 0.1f, 0.95f);
            }
        }
        if (!flashes.isEmpty() && flashTex != null) {
            VertexConsumer vc = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(flashTex));
            Vector3f right = new Vector3f(1, 0, 0).rotate(rot);
            Vector3f up = new Vector3f(0, 1, 0).rotate(rot);
            Vector3f n = new Vector3f(0, 0, 1).rotate(rot);
            for (Iterator<Flash> it = flashes.iterator(); it.hasNext(); ) {
                Flash f = it.next();
                double el = (now - f.born) / 1e9;
                if (el > 0.06 && f.shown) {
                    it.remove();
                    continue;
                }
                f.shown = true;
                Vec3 c = f.at.subtract(cam);
                float s = f.size * 0.5f;
                float cs = (float) Math.cos(f.spin) * s, sn = (float) Math.sin(f.spin) * s;
                Vector3f ax = new Vector3f(right).mul(cs).add(new Vector3f(up).mul(sn));
                Vector3f ay = new Vector3f(up).mul(cs).sub(new Vector3f(right).mul(sn));
                quad(vc, pose, (float) c.x, (float) c.y, (float) c.z, ax, ay, n, 0xFFFFFFFF);
            }
        }
    }

    /** Квадрат с центром c и полуосями ax, ay — картинка целиком, светится (свет не нужен). */
    static void quad(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, Vector3f ax, Vector3f ay,
                     Vector3f n, int argb) {
        float[][] p = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
        for (float[] q : p) {
            vc.addVertex(m, cx + ax.x * q[0] + ay.x * q[1], cy + ax.y * q[0] + ay.y * q[1], cz + ax.z * q[0] + ay.z * q[1])
                    .setColor(argb).setUv(q[2], q[3]).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(n.x, n.y, n.z);
        }
    }

    private static float width(Vec3 p, float min) {
        return (float) Math.max(min, p.length() * min * 0.12);
    }

    /** Лента a→b шириной wa…wb (камера в 0), повёрнутая к камере; прозрачность хвоста alphaA, головы alphaB. */
    private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, float wa, float wb, int rgb, float alphaA,
                               float alphaB) {
        Vec3 d = b.subtract(a);
        Vec3 mid = a.add(b).scale(0.5);
        Vec3 side = d.cross(mid);
        double l = side.length();
        if (l < 1e-9) return;
        Vec3 sa = side.scale(wa / l), sb = side.scale(wb / l);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, bl = rgb & 255;
        int aa = Math.round(alphaA * 255), ab = Math.round(alphaB * 255);
        int c0 = (aa << 24) | (r << 16) | (g << 8) | bl, c1 = (ab << 24) | (r << 16) | (g << 8) | bl;
        float[][] v = {{(float) (a.x - sa.x), (float) (a.y - sa.y), (float) (a.z - sa.z)},
                {(float) (a.x + sa.x), (float) (a.y + sa.y), (float) (a.z + sa.z)},
                {(float) (b.x + sb.x), (float) (b.y + sb.y), (float) (b.z + sb.z)},
                {(float) (b.x - sb.x), (float) (b.y - sb.y), (float) (b.z - sb.z)}};
        int[] col = {c0, c0, c1, c1};
        for (int i = 0; i < 4; i++) {
            vc.addVertex(m, v[i][0], v[i][1], v[i][2]).setColor(col[i]).setUv(0.5f, 0.5f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 1, 0);
        }
    }
}
