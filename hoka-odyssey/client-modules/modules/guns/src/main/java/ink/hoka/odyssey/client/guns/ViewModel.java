package ink.hoka.odyssey.client.guns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ink.hoka.odyssey.client.model.BbAnimation;
import ink.hoka.odyssey.client.model.BbModel;
import ink.hoka.odyssey.client.model.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Оружие «в руке» от первого лица: модель .bbmodel (из TACZ, конвертированная) разбирается классами модели ядра мода
 * ({@link BbModel#parse}, поза — {@link Pose#compute}), а рисуется здесь, в onWorld, из итоговой камеры того же кадра
 * (без отставания на кадр, как было бы с ModelHandle). Положение — как в TACZ: модель ставится так, чтобы кость «глаза»
 * idle_view (от бедра) или iron_view (в прицеле — целик и мушка ровно по центру) оказалась в камере; между ними —
 * {@link AdsBlend}. Анимации — одна за раз (как умеет ядро): shoot, reload_*, draw, switch_*.
 */
final class ViewModel {
    /** Звук в анимации: время (с) и id звука hoka:… */
    record Cue(float time, String sound) {}

    final String path;
    final BbModel model;
    private final Identifier tex;
    private final Map<String, List<Cue>> cues = new HashMap<>();
    private final Matrix4f[] pose;
    private final Matrix4f[] rest;
    private final BbModel.Bone idle;
    private final BbModel.Bone iron;
    private final BbModel.Bone camera;
    private final BbModel.Bone muzzle;
    private final Set<Integer> hidden = new HashSet<>();
    private final Set<Integer> barrelRound = new HashSet<>();
    private final Set<Integer> magRounds = new HashSet<>();

    private BbAnimation anim;
    private String animName;
    private long animStart;
    private int nextCue;

    private ViewModel(String path, BbModel model, Identifier tex, JsonObject json) {
        this.path = path;
        this.model = model;
        this.tex = tex;
        pose = new Matrix4f[model.bones.size()];
        rest = new Matrix4f[model.bones.size()];
        Pose.compute(model, null, -1, rest);
        Pose.compute(model, null, -1, pose);
        idle = model.bone("idle_view");
        iron = model.bone("iron_view");
        camera = model.bone("camera");
        BbModel.Bone mf = model.bone("muzzle_flash");
        muzzle = mf != null ? mf : model.bone("muzzle_pos");
        for (String n : new String[] {"hitbox", "additional_magazine"}) mark(model.bone(n), hidden);
        mark(model.bone("bullet_in_barrel"), barrelRound);
        mark(model.bone("bullet_in_mag"), magRounds);
        if (json.get("animations") instanceof JsonArray arr) {
            for (JsonElement e : arr) {
                if (!(e instanceof JsonObject a) || !a.has("name")) continue;
                List<Cue> l = new ArrayList<>();
                if (a.get("hoka_sounds") instanceof JsonArray s) {
                    for (JsonElement x : s) {
                        if (!(x instanceof JsonObject o) || !o.has("effect") || o.get("effect").isJsonNull()) continue;
                        String id = soundId(o.get("effect").getAsString());
                        if (id != null) l.add(new Cue(o.has("time") ? o.get("time").getAsFloat() : 0, id));
                    }
                }
                l.sort((p, q) -> Float.compare(p.time(), q.time()));
                cues.put(a.get("name").getAsString(), l);
            }
        }
    }

    /** «tacz:ak47/ak47_reload_magout» → «ak47_reload_magout» (так звук назван в hosounds). Чужие (без tacz:) — null. */
    static String soundId(String effect) {
        if (effect == null || !effect.startsWith("tacz:")) return null;
        String s = effect.substring(effect.lastIndexOf('/') + 1);
        if (s.startsWith("tacz:")) s = s.substring(5);
        return s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }

    private static void mark(BbModel.Bone b, Set<Integer> out) {
        if (b == null) return;
        out.add(b.index);
        for (BbModel.Bone c : b.children) mark(c, out);
    }

    /** Разобрать модель (bytes — файл .bbmodel). Текстура — из модели (вшита), регистрируется в игре. */
    static ViewModel load(String path, byte[] bytes, Logger log) {
        try {
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            BbModel m = BbModel.parse(path, json);
            if (m.texturePngs.isEmpty() || m.texturePngs.get(0).length == 0) throw new IllegalStateException("нет текстуры");
            NativeImage img = NativeImage.read(m.texturePngs.get(0));
            Identifier id = Identifier.fromNamespaceAndPath("hoka_guns", "viewmodel/" + sanitize(path));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "hoka guns " + path, img));
            m.texturePngs.clear();
            log.info("guns: модель {} — костей {}, анимаций {}", path, m.bones.size(), m.animations.size());
            return new ViewModel(path, m, id, json);
        } catch (Exception e) {
            log.error("guns: модель {} не разобрана: {}", path, e.toString());
            return null;
        }
    }

    void close() {
        Minecraft.getInstance().getTextureManager().release(tex);
    }

    private static String sanitize(String p) {
        StringBuilder sb = new StringBuilder();
        for (char c : p.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- анимации

    /** Играть анимацию name с начала (нет такой — поза по умолчанию). */
    void play(String name) {
        BbAnimation a = name == null ? null : model.animations.get(name);
        anim = a;
        animName = a == null ? null : name;
        animStart = System.nanoTime();
        nextCue = 0;
    }

    String playing() {
        return animName;
    }

    /** Время анимации (с) или −1. Анимация once кончилась — снимается. */
    private float animTime() {
        if (anim == null) return -1;
        float t = (System.nanoTime() - animStart) / 1e9f;
        if (anim.loop == BbAnimation.Loop.ONCE && t > anim.length) {
            anim = null;
            animName = null;
            return -1;
        }
        return anim.localTime(t);
    }

    /** Звуки анимации, время которых пришло (вызывать каждый тик). */
    List<String> dueSounds() {
        if (animName == null) return List.of();
        List<Cue> l = cues.get(animName);
        if (l == null || nextCue >= l.size()) return List.of();
        float t = (System.nanoTime() - animStart) / 1e9f;
        List<String> out = new ArrayList<>();
        while (nextCue < l.size() && l.get(nextCue).time() <= t) out.add(l.get(nextCue++).sound());
        return out;
    }

    /** Пересчитать позу на сейчас. */
    void update() {
        Pose.compute(model, anim, animTime(), pose);
    }

    // ---------------------------------------------------------------- матрицы

    /** Матрица «глаза»: поза кости, сдвинутая в её точку. Камера должна оказаться в ней. */
    private Matrix4f eye(BbModel.Bone b) {
        if (b == null) return new Matrix4f();
        return new Matrix4f(pose[b.index]).translate(b.origin);
    }

    /** Из координат модели (пиксели) в координаты камеры (блоки): масштаб и смесь «от бедра» ↔ «в прицеле». */
    Matrix4f modelToCamera(float scale, float ads) {
        Matrix4f invIdle = eye(idle).invert();
        Matrix4f invIron = eye(iron != null ? iron : idle).invert();
        return new Matrix4f().scale(scale / 16f).mul(AdsBlend.blend(invIdle, invIron, ads));
    }

    /** Точка ствола (кость muzzle_flash) в координатах камеры, блоки. null — нет кости. */
    Vector3f muzzle(Matrix4f modelToCamera) {
        if (muzzle == null) return null;
        Vector3f o = new Vector3f(muzzle.origin);
        pose[muzzle.index].transformPosition(o);
        return modelToCamera.transformPosition(o);
    }

    /**
     * Тряска камеры от анимации (поворот кости camera относительно покоя), градусы {x, y, z}: x — вверх, y — влево,
     * z — крен.
     */
    Vector3f cameraShake() {
        Vector3f out = new Vector3f();
        if (camera == null || anim == null) return out;
        Quaternionf a = pose[camera.index].getNormalizedRotation(new Quaternionf());
        Quaternionf r = rest[camera.index].getNormalizedRotation(new Quaternionf());
        Quaternionf d = r.conjugate().mul(a);
        d.getEulerAnglesZYX(out);
        out.mul((float) Math.toDegrees(1));
        return out;
    }

    /**
     * Вспышка у ствола: картинка TACZ в плоскости экрана (лицом к камере) в точке mz (координаты камеры, блоки),
     * повёрнутая на spin, размер size (блоки), светится сама. camToBuf — из координат камеры в буфер мира.
     */
    void renderFlash(MultiBufferSource buffers, Matrix4f camToBuf, Vector3f mz, Identifier flash, float size, float spin) {
        VertexConsumer vc = buffers.getBuffer(RenderTypes.entityTranslucentEmissive(flash));
        float s = size * 0.5f;
        float c = (float) Math.cos(spin) * s, n = (float) Math.sin(spin) * s;
        Fx.quad(vc, camToBuf, mz.x, mz.y, mz.z, new Vector3f(c, n, 0), new Vector3f(-n, c, 0), new Vector3f(0, 0, 1),
                0xFFFFFFFF);
    }

    /** Нарисовать: base — из координат камеры в координаты буфера мира. */
    void render(MultiBufferSource buffers, Matrix4f base, int light, int ammo) {
        VertexConsumer vc = buffers.getBuffer(RenderTypes.entityCutoutNoCull(tex));
        Matrix4f m = new Matrix4f();
        Vector3f p0 = new Vector3f(), p1 = new Vector3f(), p2 = new Vector3f(), p3 = new Vector3f();
        Vector3f n = new Vector3f(), e = new Vector3f();
        Vector3f[] pts = {p0, p1, p2, p3};
        for (BbModel.Bone b : model.bones) {
            if (b.faces.isEmpty() || hidden.contains(b.index)) continue;
            if (ammo <= 0 && (barrelRound.contains(b.index) || magRounds.contains(b.index))) continue;
            m.set(base).mul(pose[b.index]);
            for (BbModel.Face f : b.faces) {
                float[] xyz = f.xyz();
                float[] uv = f.uv();
                m.transformPosition(xyz[0], xyz[1], xyz[2], p0);
                m.transformPosition(xyz[3], xyz[4], xyz[5], p1);
                m.transformPosition(xyz[6], xyz[7], xyz[8], p2);
                m.transformPosition(xyz[9], xyz[10], xyz[11], p3);
                n.set(p1).sub(p0).cross(e.set(p3).sub(p0)).normalize();
                if (!Float.isFinite(n.x)) n.set(0, 1, 0);
                for (int i = 0; i < 4; i++) {
                    vc.addVertex(pts[i].x, pts[i].y, pts[i].z).setColor(-1).setUv(uv[i * 2], uv[i * 2 + 1])
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(n.x, n.y, n.z);
                }
            }
        }
    }
}
