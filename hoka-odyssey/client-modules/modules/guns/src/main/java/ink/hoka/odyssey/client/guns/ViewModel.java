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
 * {@link AdsBlend}. Анимации — одна за раз (как умеет ядро): shoot, bolt, reload_*, draw, switch_*.
 * <p>
 * Оружие с прицелом (AWP: обвес TACZ в кости scope_pos, вторая текстура) — в прицеле глаз в кости scope_view
 * прицела; сетка прицела (division) в модели не рисуется — из неё {@link Reticle} делает картинку для круга оптики.
 */
final class ViewModel {
    /** Звук в анимации: время (с) и id звука hoka:… */
    record Cue(float time, String sound) {}

    final String path;
    final BbModel model;
    /** Текстуры модели (у оружия с прицелом — вторая, прицела); null — нет картинки. */
    private final Identifier[] tex;
    /** Сетка прицела (кости division модели прицела TACZ) — для круга оптики; null — у модели нет прицела. */
    final Reticle reticle;
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

    /** Какие кости двигает каждая анимация (из файла): слои {@link #update} берут кость у верхнего слоя, что её двигает. */
    private final Map<String, Set<Integer>> animated = new HashMap<>();
    private final Matrix4f[] layerIdle;
    private final Matrix4f[] layerSafety;
    private final Matrix4f[] layerAnim;
    private final BbModel.Bone handRight;
    private final BbModel.Bone handLeft;
    private final BbModel.Bone extraMag;
    private final BbModel.Bone magazine;
    private final Set<Integer> magBones = new HashSet<>();
    private final BbAnimation idleAnim;
    private BbAnimation safetyAnim;
    private String safetyName;

    private BbAnimation anim;
    private String animName;
    private long animStart;
    private int nextCue;

    private ViewModel(String path, BbModel model, Identifier[] tex, JsonObject json, Reticle reticle) {
        this.reticle = reticle;
        this.path = path;
        this.model = model;
        this.tex = tex;
        pose = new Matrix4f[model.bones.size()];
        rest = new Matrix4f[model.bones.size()];
        layerIdle = new Matrix4f[model.bones.size()];
        layerSafety = new Matrix4f[model.bones.size()];
        layerAnim = new Matrix4f[model.bones.size()];
        handRight = model.bone("righthand_pos");
        handLeft = model.bone("lefthand_pos");
        extraMag = model.bone("additional_magazine");
        magazine = model.bone("magazine");
        mark(magazine, magBones);
        idleAnim = model.animations.get("static_idle");
        Pose.compute(model, null, -1, rest);
        Pose.compute(model, null, -1, pose);
        idle = model.bone("idle_view");
        // прицел на оружии (обвес TACZ в кости scope_pos): в прицеле глаз — в его кости scope_view, как у TACZ
        BbModel.Bone sv = model.bone("scope_view");
        iron = sv != null ? sv : model.bone("iron_view");
        camera = model.bone("camera");
        BbModel.Bone mf = model.bone("muzzle_flash");
        muzzle = mf != null ? mf : model.bone("muzzle_pos");
        for (String n : new String[] {"hitbox", "additional_magazine"}) mark(model.bone(n), hidden);
        // у прицела TACZ рисует сетку (division) только в круге оптики, а точки взгляда (views) и место под
        // надпись (ammo_count_text) не рисует вовсе
        for (String n : new String[] {"divisions", "division", "division_2", "ammo_count_text", "scope_view",
                "scope_view_2"}) {
            mark(model.bone(n), hidden);
        }
        // окуляры TACZ рисует только трафаретом (в цвете — лишь чёрная «линза» оптики ocular_scope до прицеливания):
        // остальные (коллиматор сбоку у прицела 8x) не видны
        for (BbModel.Bone b : model.bones) {
            if (b.name.matches("ocular(_sight)?(_\\d+)?")) mark(b, hidden);
        }
        if (sv != null) {                                         // кость views прицела (у оружия своя — с тем же именем)
            for (BbModel.Bone b : model.bones) {
                if (b.children.contains(sv)) mark(b, hidden);
            }
        }
        // запасной магазин TACZ рисует только пока анимация его двигает (перезарядка) — сеткой основного магазина
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
                animated.put(a.get("name").getAsString(), animatedBones(model, a));
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

    /** Кости, у которых в анимации есть ключи (аниматоры .bbmodel — по uuid кости или по имени). */
    static Set<Integer> animatedBones(BbModel model, JsonObject a) {
        Set<Integer> out = new HashSet<>();
        if (!(a.get("animators") instanceof JsonObject an)) return out;
        for (var e : an.entrySet()) {
            if (!(e.getValue() instanceof JsonObject o) || !(o.get("keyframes") instanceof JsonArray k) || k.isEmpty()) continue;
            BbModel.Bone b = null;
            for (BbModel.Bone x : model.bones) {
                if (x.uuid.equals(e.getKey())) {
                    b = x;
                    break;
                }
            }
            if (b == null && o.has("name")) b = model.bone(o.get("name").getAsString());
            if (b != null) out.add(b.index);
        }
        return out;
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
            Identifier[] ids = new Identifier[m.texturePngs.size()];
            Reticle reticle = null;
            for (int i = 0; i < ids.length; i++) {
                byte[] png = m.texturePngs.get(i);
                if (png.length == 0) continue;
                NativeImage img = NativeImage.read(png);
                if (reticle == null) reticle = Reticle.build(path, m, i, img, log);   // сетка — на текстуре прицела
                ids[i] = Identifier.fromNamespaceAndPath("hoka_guns", "viewmodel/" + sanitize(path) + (i == 0 ? "" : "_" + i));
                Minecraft.getInstance().getTextureManager().register(ids[i], new DynamicTexture(() -> "hoka guns " + path, img));
            }
            m.texturePngs.clear();
            log.info("guns: модель {} — костей {}, анимаций {}, текстур {}{}", path, m.bones.size(), m.animations.size(),
                    ids.length, reticle != null ? ", прицел (сетка " + reticle.faces + " граней)" : "");
            return new ViewModel(path, m, ids, json, reticle);
        } catch (Exception e) {
            log.error("guns: модель {} не разобрана: {}", path, e.toString());
            return null;
        }
    }

    void close() {
        for (Identifier t : tex) if (t != null) Minecraft.getInstance().getTextureManager().release(t);
        if (reticle != null) reticle.close();
    }

    private static String sanitize(String p) {
        StringBuilder sb = new StringBuilder();
        for (char c : p.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- анимации

    /** Есть ли у модели анимация name. */
    boolean has(String name) {
        return name != null && model.animations.containsKey(name);
    }

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
        // выстрел TACZ у пистолета помечен петлёй, но играется один раз
        if ((anim.loop == BbAnimation.Loop.ONCE || anim.loop == BbAnimation.Loop.LOOP && "shoot".equals(animName))
                && t > anim.length) {
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

    /** Положение предохранителя (static_auto / static_semi): нижний слой под анимацией. */
    void safety(String name) {
        if (java.util.Objects.equals(name, safetyName)) return;
        safetyName = name;
        safetyAnim = name == null ? null : model.animations.get(name);
    }

    /**
     * Пересчитать позу на сейчас. Слои, как в TACZ: внизу static_idle (руки на оружии), над ним предохранитель, сверху
     * текущая анимация (выстрел, перезарядка, доставание). Кость берёт своё движение (относительно родителя) у
     * верхнего слоя, который её двигает, — так руки из static_idle идут за отдачей корпуса в shoot.
     */
    void update() {
        float t = animTime();
        Pose.compute(model, anim, t, layerAnim);
        Pose.compute(model, idleAnim, 0, layerIdle);
        Pose.compute(model, safetyAnim, 0, layerSafety);
        Set<Integer> top = anim == null || animName == null ? Set.of() : animated.getOrDefault(animName, Set.of());
        Set<Integer> mid = safetyAnim == null ? Set.of() : animated.getOrDefault(safetyName, Set.of());
        Set<Integer> low = idleAnim == null ? Set.of() : animated.getOrDefault("static_idle", Set.of());
        Matrix4f local = new Matrix4f(), inv = new Matrix4f();
        for (BbModel.Bone r : model.roots) compose(r, null, top, mid, low, local, inv);
    }

    private void compose(BbModel.Bone b, BbModel.Bone parent, Set<Integer> top, Set<Integer> mid, Set<Integer> low,
                         Matrix4f local, Matrix4f inv) {
        Matrix4f[] src = top.contains(b.index) ? layerAnim : mid.contains(b.index) ? layerSafety
                : low.contains(b.index) ? layerIdle : layerAnim;
        if (parent == null) {
            local.set(src[b.index]);
            pose[b.index].set(local);
        } else {
            inv.set(src[parent.index]).invert();
            local.set(inv).mul(src[b.index]);
            pose[b.index].set(pose[parent.index]).mul(local);
        }
        for (BbModel.Bone c : b.children) compose(c, b, top, mid, low, local, inv);
    }

    /** Двигает ли текущая анимация кость. */
    private boolean animates(BbModel.Bone b) {
        return b != null && anim != null && animName != null && animated.getOrDefault(animName, Set.of()).contains(b.index);
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

    /**
     * Множитель координат x, y камеры, с которым модель, нарисованная с углом обзора мира worldFov, выглядит как
     * нарисованная с углом viewFov (градусы, по вертикали): tan(worldFov/2) / tan(viewFov/2). Центр экрана на месте —
     * прицел в ADS остаётся по центру. viewFov ≤ 0 — 1 (угол мира).
     */
    static float fovScale(double worldFov, double viewFov) {
        if (!(viewFov > 0) || !(worldFov > 0)) return 1f;
        double w = Math.toRadians(Math.min(179, worldFov)) / 2, v = Math.toRadians(Math.min(179, viewFov)) / 2;
        return (float) (Math.tan(w) / Math.tan(v));
    }

    /** Из координат камеры в «экранный» угол обзора оружия: x, y ×k (см. {@link #fovScale}). */
    static Matrix4f fovMatrix(float k) {
        return new Matrix4f().scaling(k, k, 1);
    }

    /**
     * Нарисовать: base — из координат камеры в координаты буфера мира, fov — сжатие x, y камеры под угол обзора
     * оружия ({@link #fovMatrix}; вершины — через base·fov, нормали — через base: свет как без сжатия). arms — руки
     * игрока (его скин) на костях righthand_pos / lefthand_pos, как в TACZ.
     */
    void render(MultiBufferSource buffers, Matrix4f base, Matrix4f fov, Matrix4f m2c, int light, int ammo, boolean arms) {
        Matrix4f pos = new Matrix4f(base).mul(fov).mul(m2c);
        Matrix4f nrm = new Matrix4f(base).mul(m2c);
        Matrix4f m = new Matrix4f(), n = new Matrix4f();
        for (int t = 0; t < tex.length; t++) {                     // по текстуре за раз: буфер одного вида до конца
            if (tex[t] == null) continue;
            VertexConsumer vc = buffers.getBuffer(RenderTypes.entityCutoutNoCull(tex[t]));
            for (BbModel.Bone b : model.bones) {
                if (b.faces.isEmpty() || hidden.contains(b.index)) continue;
                if (ammo <= 0 && (barrelRound.contains(b.index) || magRounds.contains(b.index))) continue;
                m.set(pos).mul(pose[b.index]);
                n.set(nrm).mul(pose[b.index]);
                faces(vc, m, n, b, light, t);
            }
            // запасной магазин (перезарядка): сетка магазина в точке additional_magazine, патроны в нём есть всегда
            if (extraMag != null && magazine != null && animates(extraMag)) {
                Matrix4f toExtra = new Matrix4f(pose[extraMag.index]).mul(new Matrix4f(pose[magazine.index]).invert());
                for (BbModel.Bone b : model.bones) {
                    if (b.faces.isEmpty() || !magBones.contains(b.index)) continue;
                    m.set(pos).mul(toExtra).mul(pose[b.index]);
                    n.set(nrm).mul(toExtra).mul(pose[b.index]);
                    faces(vc, m, n, b, light, t);
                }
            }
        }
        if (!arms) return;
        if (handRight != null) {
            FirstPersonArms.render(buffers, armMatrix(pos, handRight), FirstPersonArms.normal(armMatrix(nrm, handRight)),
                    true, light);
        }
        if (handLeft != null) {
            FirstPersonArms.render(buffers, armMatrix(pos, handLeft), FirstPersonArms.normal(armMatrix(nrm, handLeft)),
                    false, light);
        }
    }

    /**
     * Матрица руки: кость *_pos в её точке, единицы — блоки модели (×16 к пикселям). Ось и знаки — как у TACZ (кость
     * в его системе + поворот на 180° по Z — это ровно наша система .bbmodel): рука ModelPart игрока (плечо в
     * (±5, 2, 0)) ложится туда же, где в исходной модели TACZ лежит заглушка руки 3×12×4.
     */
    private Matrix4f armMatrix(Matrix4f base, BbModel.Bone b) {
        return new Matrix4f(base).mul(pose[b.index]).translate(b.origin).scale(16f);
    }

    /** Грани кости: вершины — матрицей m, нормаль — по тем же точкам матрицей nm (без сжатия под угол обзора). */
    private static void faces(VertexConsumer vc, Matrix4f m, Matrix4f nm, BbModel.Bone b, int light, int texture) {
        Vector3f p0 = new Vector3f(), p1 = new Vector3f(), p2 = new Vector3f(), p3 = new Vector3f();
        Vector3f n = new Vector3f(), e = new Vector3f();
        Vector3f[] pts = {p0, p1, p2, p3};
        for (BbModel.Face f : b.faces) {
            if (f.texture() != texture) continue;
            float[] xyz = f.xyz();
            float[] uv = f.uv();
            nm.transformPosition(xyz[0], xyz[1], xyz[2], p0);
            nm.transformPosition(xyz[3], xyz[4], xyz[5], p1);
            nm.transformPosition(xyz[9], xyz[10], xyz[11], p3);
            n.set(p1).sub(p0).cross(e.set(p3).sub(p0)).normalize();
            if (!Float.isFinite(n.x)) n.set(0, 1, 0);
            m.transformPosition(xyz[0], xyz[1], xyz[2], p0);
            m.transformPosition(xyz[3], xyz[4], xyz[5], p1);
            m.transformPosition(xyz[6], xyz[7], xyz[8], p2);
            m.transformPosition(xyz[9], xyz[10], xyz[11], p3);
            for (int i = 0; i < 4; i++) {
                vc.addVertex(pts[i].x, pts[i].y, pts[i].z).setColor(-1).setUv(uv[i * 2], uv[i * 2 + 1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(n.x, n.y, n.z);
            }
        }
    }
}
