package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
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
 * <p>
 * Плазма (оружие с {@code bullet.tracer-style: plasma}): вместо длинной ленты — короткий сгусток (лента длиной
 * ~0,8 блока: цветная оболочка и белое ядро, светящийся ореол на голове), за ним гаснущие искры; вспышка у ствола —
 * голубой ореол; в блоке, куда попал, — вспышка и искры (у всех, кто видит выстрел).
 */
final class Fx {
    private static final int MAX = 256;
    /** Длина хвоста трассера: столько секунд полёта (у АКМ ~25 блоков), но не больше 40 блоков. */
    private static final double TAIL_SEC = 0.05;
    /** Свой трассер прячется первые N блоков от ствола (не мешать прицелу). */
    private static final double OWN_SKIP = 0.5;
    /** То же, когда ствол не виден (в оптике) и трассер идёт от глаз. */
    private static final double OWN_SKIP_EYE = 2.5;
    /** До скольких блоков трассер сходится и на сущность на пути (дальше — только на блок). */
    private static final double ENTITY_RANGE = 64;

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
        /** Плазма: длина сгустка (блоков), упёрся ли в блок (тогда в конце — вспышка), была ли она, след искр. */
        boolean plasma;
        double length;
        boolean hit;
        boolean impactDone;
        long nextSpark;

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

    /** Светящаяся точка (ореол плазмы, искра): летит со скоростью vel, гаснет за life с, уменьшаясь. */
    private record Glow(Vec3 at, Vec3 vel, long born, double life, float size, int rgb, float alpha, double gravity) {}

    private static final int MAX_GLOWS = 768;
    /** Длина плазменного сгустка по умолчанию, блоков. */
    private static final double PLASMA_LEN = 0.8;
    /** Свой плазменный сгусток прячется только у самого ствола. */
    private static final double OWN_SKIP_PLASMA = 0.3;
    private final List<Glow> glows = new ArrayList<>();
    /** Круглое пятно (белое, к краям прозрачное) — текстура ореолов и искр. */
    private Identifier glowTex;
    private final java.util.Random rnd = new java.util.Random();

    private static final class Flash {
        final Vec3 at;
        final long born;
        final float size;
        final float spin;
        /** Нарисована хоть раз (при низком FPS вспышка иначе исчезала бы до первого кадра). */
        boolean shown;

        /** Плазма: голубой ореол цвета rgb вместо картинки вспышки. */
        boolean plasma;
        int rgb;

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

    /** Луч ЛЦУ на этот кадр: от a до b (мир), цвет RGB, точка на конце (луч упёрся). */
    private record Beam(Vec3 a, Vec3 b, int rgb, boolean dot) {}

    private final List<Beam> beams = new ArrayList<>();

    /**
     * След пули на грани блока: центр at (уже чуть над гранью), нормаль грани n (по оси), блок pos (сломали — след
     * пропадает), полуразмер, поворот картинки, у плазмы — подпалина со свечением цвета rgb.
     */
    private record Decal(Vec3 at, Vector3f n, BlockPos pos, float size, float rot, long born, boolean plasma, int rgb) {}

    private static final int MAX_DECALS = 256;
    /** Сколько живёт след, с; последнюю секунду гаснет. */
    private static final double DECAL_LIFE = 7, DECAL_FADE = 1;
    /** Свечение плазменной подпалины гаснет за столько секунд. */
    private static final double SCORCH_GLOW = 2.5;
    private final java.util.ArrayDeque<Decal> decals = new java.util.ArrayDeque<>();
    private int decalSeq;
    /** Картинки следа: пулевое отверстие и подпалина (чёрные, форма — прозрачностью). */
    private Identifier holeTex, scorchTex;

    void clear() {
        tracers.clear();
        flashes.clear();
        beams.clear();
        glows.clear();
        decals.clear();
    }

    /**
     * Луч ЛЦУ из from по dir длиной до length блоков (до первого блока на пути; там — точка). Рисуется в этом кадре
     * ({@link #renderBeams}), как у TACZ — без влияния на стрельбу.
     */
    void laser(Vec3 from, Vec3 dir, double length, int rgb, net.minecraft.world.entity.Entity owner) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || beams.size() >= 64 || dir.lengthSqr() < 1e-9) return;
        Vec3 d = dir.normalize();
        Vec3 to = from.add(d.scale(Math.max(0.5, Math.min(length, 200))));
        boolean hit = false;
        try {
            HitResult h = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    owner == null ? mc.player : owner));
            if (h.getType() != HitResult.Type.MISS) {
                to = h.getLocation();
                hit = true;
            }
        } catch (RuntimeException ignored) {
            // нет мира рядом — во всю длину
        }
        beams.add(new Beam(from, to, rgb & 0xFFFFFF, hit));
    }

    /** Нарисовать лучи кадра и забыть их (то же, что {@link #render}, — для лучей, посчитанных после него). */
    void renderBeams(MultiBufferSource buffers, Matrix4f camToBuf, Vec3 cam, Quaternionf rot) {
        if (beams.isEmpty()) return;
        Matrix4f pose = new Matrix4f(camToBuf).rotate(new Quaternionf(rot).conjugate());
        VertexConsumer vc = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(white()));
        Vector3f right = new Vector3f(1, 0, 0).rotate(rot);
        Vector3f up = new Vector3f(0, 1, 0).rotate(rot);
        Vector3f n = new Vector3f(0, 0, 1).rotate(rot);
        for (Beam b : beams) {
            Vec3 a = b.a().subtract(cam), e = b.b().subtract(cam);
            ribbon(vc, pose, a, e, width(a, 0.006f), width(e, 0.006f), b.rgb(), 0.75f, 0.05f);
            ribbon(vc, pose, a, e, width(a, 0.0025f), width(e, 0.0025f), 0xFFFFFF, 0.5f, 0.02f);
            if (b.dot()) {
                float s = (float) Math.max(0.025, e.length() * 0.004);
                int c = 0xE0000000 | b.rgb();
                quad(vc, pose, (float) e.x, (float) e.y, (float) e.z, new Vector3f(right).mul(s), new Vector3f(up).mul(s), n, c);
            }
        }
        beams.clear();
    }

    void close() {
        clear();
        if (white != null) {
            Minecraft.getInstance().getTextureManager().release(white);
            white = null;
        }
        if (glowTex != null) {
            Minecraft.getInstance().getTextureManager().release(glowTex);
            glowTex = null;
        }
        if (holeTex != null) {
            Minecraft.getInstance().getTextureManager().release(holeTex);
            holeTex = null;
        }
        if (scorchTex != null) {
            Minecraft.getInstance().getTextureManager().release(scorchTex);
            scorchTex = null;
        }
    }

    private Identifier glowTex() {
        if (glowTex == null) {
            int n = 32;
            com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(n, n, false);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
                    double r = Math.min(1, Math.sqrt(dx * dx + dy * dy));
                    double a = Math.pow(1 - r, 1.8) * 0.85 + Math.pow(Math.max(0, 1 - r * 2.2), 2) * 0.15;
                    int al = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
                    img.setPixel(x, y, (al << 24) | 0xFFFFFF);
                }
            }
            glowTex = Identifier.fromNamespaceAndPath("hoka_guns", "glow");
            Minecraft.getInstance().getTextureManager().register(glowTex,
                    new net.minecraft.client.renderer.texture.DynamicTexture(() -> "hoka guns glow", img));
        }
        return glowTex;
    }

    private void glow(Vec3 at, Vec3 vel, double life, float size, int rgb, float alpha, double gravity) {
        if (glows.size() >= MAX_GLOWS) glows.remove(0);
        glows.add(new Glow(at, vel, System.nanoTime(), life, size, rgb & 0xFFFFFF, alpha, gravity));
    }

    /** Плазма попала в блок: вспышка и искры. */
    private void plasmaImpact(Vec3 at, Vec3 dir, int rgb) {
        glow(at, Vec3.ZERO, 0.16, 0.9f, rgb, 0.9f, 0);
        glow(at, Vec3.ZERO, 0.1, 0.35f, 0xFFFFFF, 1f, 0);
        for (int i = 0; i < 9; i++) {
            Vec3 v = new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize().scale(2 + rnd.nextDouble() * 3)
                    .subtract(dir.scale(2.5));
            glow(at, v, 0.18 + rnd.nextDouble() * 0.2, 0.07f, i % 3 == 0 ? 0xFFFFFF : rgb, 1f, 9.8);
        }
    }

    /**
     * След попадания в блок: at — точка попадания, normal — нормаль грани (с сервера). plasma — подпалина со свечением
     * цвета rgb вместо отверстия. Следов не больше {@link #MAX_DECALS} (старые уходят), живут {@link #DECAL_LIFE} с.
     */
    void decal(Vec3 at, Vec3 normal, boolean plasma, int rgb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || normal.lengthSqr() < 1e-6) return;
        // нормаль — к ближайшей оси (грань блока)
        double ax = Math.abs(normal.x), ay = Math.abs(normal.y), az = Math.abs(normal.z);
        Vector3f n = ax >= ay && ax >= az ? new Vector3f(Math.signum((float) normal.x), 0, 0)
                : ay >= az ? new Vector3f(0, Math.signum((float) normal.y), 0) : new Vector3f(0, 0, Math.signum((float) normal.z));
        BlockPos pos = BlockPos.containing(at.x - n.x * 0.01, at.y - n.y * 0.01, at.z - n.z * 0.01);
        try {
            if (mc.level.getBlockState(pos).isAir()) return;
        } catch (RuntimeException e) {
            return;
        }
        float size = plasma ? 0.11f + rnd.nextFloat() * 0.05f : 0.05f + rnd.nextFloat() * 0.025f;
        // в пределах грани блока (не свисать за край), чуть над ней — без мерцания с гранью и соседними следами
        double x = n.x != 0 ? at.x : clamp(at.x, pos.getX() + size, pos.getX() + 1 - size);
        double y = n.y != 0 ? at.y : clamp(at.y, pos.getY() + size, pos.getY() + 1 - size);
        double z = n.z != 0 ? at.z : clamp(at.z, pos.getZ() + size, pos.getZ() + 1 - size);
        double lift = 0.004 + (decalSeq++ & 7) * 0.0006;
        Vec3 c = new Vec3(x + n.x * lift, y + n.y * lift, z + n.z * lift);
        while (decals.size() >= MAX_DECALS) decals.pollFirst();
        decals.addLast(new Decal(c, n, pos, size, rnd.nextFloat() * (float) Math.PI * 2, System.nanoTime(), plasma,
                rgb & 0xFFFFFF));
    }

    private static double clamp(double v, double lo, double hi) {
        return lo > hi ? (lo + hi) / 2 : Math.max(lo, Math.min(hi, v));
    }

    /** Следы: чёрные картинки поверх грани (не светятся, но и не зависят от света — тёмное на любом), у плазмы ещё
     *  гаснущее свечение. Сломанный блок — след убирается. */
    private void renderDecals(MultiBufferSource buffers, Matrix4f pose, Vec3 cam, long now) {
        if (decals.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        VertexConsumer hole = null, scorch = null, glow = null;
        for (Iterator<Decal> it = decals.iterator(); it.hasNext(); ) {
            Decal d = it.next();
            double el = (now - d.born()) / 1e9;
            boolean gone;
            try {
                gone = el > DECAL_LIFE || mc.level == null || mc.level.getBlockState(d.pos()).isAir();
            } catch (RuntimeException e) {
                gone = true;
            }
            if (gone) {
                it.remove();
                continue;
            }
            Vec3 c = d.at().subtract(cam);
            if (c.lengthSqr() > 128 * 128) continue;
            float k = (float) Math.min(1, (DECAL_LIFE - el) / DECAL_FADE);
            // оси в плоскости грани, повёрнутые на d.rot
            Vector3f n = d.n();
            Vector3f t = Math.abs(n.y) > 0.5f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
            Vector3f b = new Vector3f(n).cross(t);
            float cs = (float) Math.cos(d.rot()), sn = (float) Math.sin(d.rot());
            Vector3f ax = new Vector3f(t).mul(cs).add(new Vector3f(b).mul(sn)).mul(d.size());
            Vector3f ay = new Vector3f(b).mul(cs).sub(new Vector3f(t).mul(sn)).mul(d.size());
            int al = Math.round(Math.max(0, Math.min(1, k)) * 255);
            if (d.plasma()) {
                if (scorch == null) scorch = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(scorchTex()));
                quad(scorch, pose, (float) c.x, (float) c.y, (float) c.z, ax, ay, n, (al << 24) | 0xFFFFFF);
            } else {
                if (hole == null) hole = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(holeTex()));
                quad(hole, pose, (float) c.x, (float) c.y, (float) c.z, ax, ay, n, (al << 24) | 0xFFFFFF);
            }
        }
        // свечение плазменных подпалин — отдельным проходом (другая картинка)
        for (Decal d : decals) {
            if (!d.plasma()) continue;
            double el = (now - d.born()) / 1e9;
            if (el >= SCORCH_GLOW) continue;
            Vec3 c = d.at().subtract(cam);
            if (c.lengthSqr() > 128 * 128) continue;
            double f = 1 - el / SCORCH_GLOW;
            float a = (float) (0.75 * f * f);
            Vector3f n = d.n();
            Vector3f t = Math.abs(n.y) > 0.5f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
            Vector3f b = new Vector3f(n).cross(t);
            float s = d.size() * (0.55f + 0.25f * (float) f);
            float lift = 0.002f;
            float x = (float) c.x + n.x * lift, y = (float) c.y + n.y * lift, z = (float) c.z + n.z * lift;
            if (glow == null) glow = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(glowTex()));
            quad(glow, pose, x, y, z, new Vector3f(t).mul(s), new Vector3f(b).mul(s), n,
                    (Math.round(a * 255) << 24) | d.rgb());
            if (el < 0.5) {                                     // раскалённая середина первые полсекунды
                float w = (float) (1 - el / 0.5);
                quad(glow, pose, x, y, z, new Vector3f(t).mul(s * 0.35f), new Vector3f(b).mul(s * 0.35f), n,
                        (Math.round(w * 0.9f * 255) << 24) | 0xFFFFFF);
            }
        }
    }

    /** Пулевое отверстие: чёрная середина, тёмное кольцо с трещинками, мягкий край. */
    private Identifier holeTex() {
        if (holeTex == null) {
            int n = 32;
            java.util.Random r = new java.util.Random(7);
            double[] crack = new double[64];
            for (int i = 0; i < crack.length; i++) crack[i] = r.nextDouble() < 0.18 ? 0.5 + r.nextDouble() * 0.5 : 0;
            com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(n, n, false);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
                    double rr = Math.sqrt(dx * dx + dy * dy);
                    int ang = (int) Math.floor((Math.atan2(dy, dx) / (Math.PI * 2) + 0.5) * crack.length) & (crack.length - 1);
                    double a;
                    if (rr < 0.26) a = 1;                                       // отверстие
                    else if (rr < 0.36) a = 0.95 - (rr - 0.26) * 2;             // край отверстия
                    else a = Math.max(0, 0.55 * Math.pow(Math.max(0, 1 - (rr - 0.36) / 0.64), 1.6));   // копоть вокруг
                    if (crack[ang] > 0 && rr < 0.36 + 0.6 * crack[ang] && rr > 0.3) a = Math.max(a, 0.7 * (1 - rr));
                    int c = rr < 0.26 ? 0x050404 : 0x15120f;
                    int al = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
                    img.setPixel(x, y, (al << 24) | c);
                }
            }
            holeTex = Identifier.fromNamespaceAndPath("hoka_guns", "bullet_hole");
            Minecraft.getInstance().getTextureManager().register(holeTex,
                    new net.minecraft.client.renderer.texture.DynamicTexture(() -> "hoka guns bullet hole", img));
        }
        return holeTex;
    }

    /** Подпалина плазмы: тёмное пятно с неровным краем, к краю светлее. */
    private Identifier scorchTex() {
        if (scorchTex == null) {
            int n = 32;
            java.util.Random r = new java.util.Random(11);
            double[] edge = new double[32];
            for (int i = 0; i < edge.length; i++) edge[i] = 0.75 + r.nextDouble() * 0.25;
            com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(n, n, false);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
                    double rr = Math.sqrt(dx * dx + dy * dy);
                    double u = (Math.atan2(dy, dx) / (Math.PI * 2) + 0.5) * edge.length;
                    int i0 = (int) Math.floor(u) % edge.length, i1 = (i0 + 1) % edge.length;
                    double f = u - Math.floor(u);
                    double lim = edge[i0] + (edge[i1] - edge[i0]) * f;
                    double q = rr / lim;
                    double a = q >= 1 ? 0 : Math.pow(1 - q, 0.7) * 0.92;
                    if (rr < 0.2) a = Math.max(a, 0.95);
                    a *= 0.85 + 0.15 * r.nextDouble();
                    int c = rr < 0.25 ? 0x060606 : 0x171513;
                    int al = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
                    img.setPixel(x, y, (al << 24) | c);
                }
            }
            scorchTex = Identifier.fromNamespaceAndPath("hoka_guns", "plasma_scorch");
            Minecraft.getInstance().getTextureManager().register(scorchTex,
                    new net.minecraft.client.renderer.texture.DynamicTexture(() -> "hoka guns plasma scorch", img));
        }
        return scorchTex;
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

    /**
     * Трассер выстрела: пуля летит из глаз eye со скоростью v (блоков/с), живёт life с — там, куда она попадёт (первый
     * блок или сущность на пути, проверка лучом на клиенте; без попадания — конец пути), трассер и сходится, но
     * начинается у ствола muzzle (null — у глаз). own — свой (начало ленты прячется у ствола); plasma — короткий
     * плазменный сгусток длиной length блоков (0 — по умолчанию); shooter — стрелок (его луч не задевает), null — нет.
     */
    void tracer(Vec3 eye, Vec3 muzzle, Vec3 v, double life, int rgb, boolean own, boolean plasma, double length,
                Entity shooter) {
        Minecraft mc = Minecraft.getInstance();
        double speed = v.length();
        if (speed < 1e-3 || mc.level == null) return;
        Vec3 dir = v.scale(1 / speed);
        double max = Math.min(speed * life, 300);
        Vec3 end = eye.add(dir.scale(max));
        boolean blocked = false;
        Entity self = shooter != null ? shooter : mc.player;
        try {
            HitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
            if (hit.getType() != HitResult.Type.MISS) {
                end = hit.getLocation();
                blocked = true;
            }
            // сущности — только вблизи (дальше разница со стволом незаметна, а поиск по большой области дорог)
            Vec3 near = eye.distanceToSqr(end) > ENTITY_RANGE * ENTITY_RANGE ? eye.add(dir.scale(ENTITY_RANGE)) : end;
            EntityHitResult eh = ProjectileUtil.getEntityHitResult(mc.level, self, eye, near,
                    new AABB(eye, near).inflate(1), e -> e != self && e.isPickable() && !e.isSpectator() && e.isAlive(), 0.1f);
            if (eh != null) {
                end = eh.getLocation();
                blocked = true;
            }
        } catch (RuntimeException ignored) {
            // нет мира рядом — до конца жизни
        }
        Vec3 o = muzzle == null ? eye : muzzle;
        Vec3 d = end.subtract(o);
        double dist = d.length();
        if (dist < 0.75) {                                     // в упор — от глаз, как пуля
            o = eye;
            d = dir;
            dist = end.distanceTo(eye);
        } else {
            d = d.scale(1 / dist);
        }
        if (tracers.size() >= MAX) tracers.remove(0);
        double skip = !own ? 0 : o == eye ? OWN_SKIP_EYE : plasma ? OWN_SKIP_PLASMA : OWN_SKIP;
        Tracer t = new Tracer(o, d, speed, dist, System.nanoTime(), rgb, skip);
        t.plasma = plasma;
        t.length = length > 0 ? length : PLASMA_LEN;
        t.hit = blocked;
        tracers.add(t);
    }

    /** Вспышка у ствола другого игрока. */
    void flash(Vec3 at, float size) {
        flash(at, size, false, 0);
    }

    /** То же; plasma — голубой ореол цвета rgb (не картинка вспышки). */
    void flash(Vec3 at, float size, boolean plasma, int rgb) {
        if (flashes.size() >= MAX) flashes.remove(0);
        Flash f = new Flash(at, System.nanoTime(), size, (float) (Math.random() * Math.PI * 2));
        f.plasma = plasma;
        f.rgb = rgb & 0xFFFFFF;
        flashes.add(f);
    }

    /**
     * Нарисовать. camToBuf — из системы камеры (x вправо, y вверх, −z вперёд) в буфер кадра; cam — положение камеры,
     * rot — её поворот (камера → мир). Точки мира переводятся в систему камеры здесь.
     */
    void render(MultiBufferSource buffers, Matrix4f camToBuf, Vec3 cam, Quaternionf rot, Identifier flashTex) {
        long now = System.nanoTime();
        Matrix4f pose = new Matrix4f(camToBuf).rotate(new Quaternionf(rot).conjugate());
        renderDecals(buffers, pose, cam, now);
        if (!tracers.isEmpty()) {
            VertexConsumer vc = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(white()));
            for (Iterator<Tracer> it = tracers.iterator(); it.hasNext(); ) {
                Tracer t = it.next();
                double el = (now - t.born) / 1e9;
                if (t.plasma) {
                    if (!plasma(vc, pose, cam, t, el, now)) it.remove();
                    continue;
                }
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
        Vector3f right = new Vector3f(1, 0, 0).rotate(rot);
        Vector3f up = new Vector3f(0, 1, 0).rotate(rot);
        Vector3f n = new Vector3f(0, 0, 1).rotate(rot);
        // плазменные вспышки у ствола — ореолами (как искры)
        for (Iterator<Flash> it = flashes.iterator(); it.hasNext(); ) {
            Flash f = it.next();
            if (!f.plasma) continue;
            it.remove();
            glow(f.at, Vec3.ZERO, 0.07, f.size * 1.3f, f.rgb, 0.95f, 0);
            glow(f.at, Vec3.ZERO, 0.05, f.size * 0.45f, 0xFFFFFF, 1f, 0);
        }
        renderGlows(buffers, pose, cam, now, right, up, n);
        if (!flashes.isEmpty() && flashTex != null) {
            VertexConsumer vc = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(flashTex));
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

    /**
     * Плазменный сгусток: короткая лента (оболочка цвета rgb, белое ядро) от головы назад на length, ореол на голове,
     * за ним искры; в конце пути у блока — вспышка. false — сгусток долетел (убрать).
     */
    private boolean plasma(VertexConsumer vc, Matrix4f pose, Vec3 cam, Tracer t, double el, long now) {
        double head = t.speed * el;
        if (!t.shown) {
            head = Math.min(head, t.skip + t.length);
            t.shown = true;
        }
        if (head >= t.maxDist && t.hit && !t.impactDone) {
            t.impactDone = true;
            plasmaImpact(t.o.add(t.dir.scale(t.maxDist - 0.05)), t.dir, t.rgb);
        }
        double tail = Math.max(t.skip, head - t.length);
        if (tail >= t.maxDist) return false;
        head = Math.min(head, t.maxDist);
        if (head <= tail) return true;
        Vec3 hw = t.o.add(t.dir.scale(head));
        Vec3 a = t.o.add(t.dir.scale(tail)).subtract(cam);
        Vec3 b = hw.subtract(cam);
        ribbon(vc, pose, a, b, width(a, 0.05f), width(b, 0.11f), t.rgb, 0.05f, 0.55f);
        ribbon(vc, pose, a, b, width(a, 0.025f), width(b, 0.055f), t.rgb, 0.3f, 1f);
        ribbon(vc, pose, a, b, width(a, 0.008f), width(b, 0.022f), 0xFFFFFF, 0.4f, 1f);
        if (now >= t.nextSpark) {                              // след: ореол на голове и искры
            t.nextSpark = now + 16_000_000L;
            glow(hw, t.dir.scale(t.speed * 0.9), 0.03, 0.55f, t.rgb, 0.45f, 0);
            Vec3 v = new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).scale(0.6);
            glow(hw.subtract(t.dir.scale(t.length * rnd.nextDouble())), v, 0.22, 0.05f, rnd.nextInt(3) == 0 ? 0xFFFFFF : t.rgb,
                    0.9f, 0);
        }
        return true;
    }

    /** Ореолы и искры: квадраты с текстурой пятна лицом к камере, гаснут и уменьшаются к концу жизни. */
    private void renderGlows(MultiBufferSource buffers, Matrix4f pose, Vec3 cam, long now, Vector3f right, Vector3f up,
                             Vector3f n) {
        if (glows.isEmpty()) return;
        VertexConsumer vc = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(glowTex()));
        for (Iterator<Glow> it = glows.iterator(); it.hasNext(); ) {
            Glow g = it.next();
            double el = (now - g.born()) / 1e9;
            if (el > g.life()) {
                it.remove();
                continue;
            }
            double k = 1 - el / g.life();
            Vec3 p = g.at().add(g.vel().scale(el)).subtract(0, 0.5 * g.gravity() * el * el, 0).subtract(cam);
            float s = (float) (g.size() * (0.5 + 0.5 * k));
            int al = (int) Math.round(Math.max(0, Math.min(1, g.alpha() * k)) * 255);
            quad(vc, pose, (float) p.x, (float) p.y, (float) p.z, new Vector3f(right).mul(s), new Vector3f(up).mul(s), n,
                    (al << 24) | g.rgb());
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
