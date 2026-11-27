package ink.hoka.odyssey.client.guns;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ink.hoka.odyssey.client.model.BbModel;
import ink.hoka.odyssey.client.model.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Модель обвеса TACZ (.bbmodel, конвертированная без правок): рисуется в кости {@code <слот>_pos} оружия (как TACZ —
 * в точке кости, её поворотом). Кости, как у TACZ: {@code scope_view}, {@code scope_view_2}… — глаз в прицеле
 * (кратность выбирает свой, display.views); окуляры {@code ocular}, {@code ocular_sight}, {@code ocular_scope}
 * (с номером) — стекло, в цвете не рисуются (у TACZ — трафарет), N-й окуляр показывает N-ю сетку
 * ({@code division}, {@code division_2}…): из пары делается картинка {@link Reticle}; {@code *_illuminated} — светится
 * сам; {@code laser_beam} — откуда идёт луч ЛЦУ.
 */
final class AttModel {
    private static final Pattern OCULAR = Pattern.compile("^(ocular|ocular_sight|ocular_scope)(_(\\d+))?$");

    /** Вид в прицел через один окуляр: сетка, оптика ли (чёрное вокруг круга) или коллиматор (сетка поверх мира). */
    record Optic(Reticle reticle, boolean scope) {}

    final String path;
    final BbModel model;
    private final Identifier[] tex;
    final Matrix4f[] rest;
    private final Set<Integer> hidden = new HashSet<>();
    private final Set<Integer> illum = new HashSet<>();
    private final Set<Integer> adsHide = new HashSet<>();
    private final Set<Integer> lens = new HashSet<>();
    /** Глаза прицела: scope_view, scope_view_2… */
    private final List<BbModel.Bone> views = new ArrayList<>();
    /** Вид через окуляр номер i (с 0, по номеру окуляра); null — сетки нет. */
    private final List<Optic> optics = new ArrayList<>();
    final List<BbModel.Bone> beams = new ArrayList<>();

    private AttModel(String path, BbModel model, Identifier[] tex, Logger log, NativeImage first, Att.Def def) {
        this.path = path;
        this.model = model;
        this.tex = tex;
        rest = new Matrix4f[model.bones.size()];
        Pose.compute(model, null, -1, rest);
        for (BbModel.Bone b : model.bones) {
            String n = b.name.toLowerCase(Locale.ROOT);
            if (n.endsWith("_illuminated") || n.equals("illuminated")) mark(b, illum);
            if (n.equals("ads_hide")) mark(b, adsHide);
            if (n.equals("views") || n.equals("divisions") || n.matches("division(_\\d+)?") || n.matches("scope_view(_\\d+)?")) {
                mark(b, hidden);
            }
            if (n.matches("ocular(_sight)?(_\\d+)?")) mark(b, hidden);
            if (n.matches("ocular_scope(_\\d+)?")) mark(b, lens);
            if (n.startsWith("laser_beam")) beams.add(b);
        }
        BbModel.Bone v = model.bone("scope_view");
        for (int i = 2; v != null; i++) {
            views.add(v);
            v = model.bone("scope_view_" + i);
        }
        // окуляры по номеру и сетки по порядку — пары, как у TACZ
        TreeMap<Integer, BbModel.Bone> oc = new TreeMap<>();
        Map<Integer, Boolean> scopeOc = new TreeMap<>();
        for (BbModel.Bone b : model.bones) {
            Matcher m = OCULAR.matcher(b.name);
            if (!m.matches()) continue;
            int num = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
            oc.put(num, b);
            scopeOc.put(num, m.group(1).equals("ocular_scope"));
        }
        List<BbModel.Bone> divisions = new ArrayList<>();
        BbModel.Bone d = model.bone("division");
        for (int i = 2; d != null; i++) {
            divisions.add(d);
            d = model.bone("division_" + i);
        }
        if (first != null && def != null && (def.scope() || def.sight())) {
            int k = 0;
            for (var e : oc.entrySet()) {
                if (k >= divisions.size()) break;
                BbModel.Bone eye = views.isEmpty() ? null : views.get(Math.min(views.size() - 1, k));
                Reticle r = Reticle.build(path, model, 0, first, eye, divisions.get(k), e.getValue(), log);
                // гибрид (прицел и коллиматор): оптика — только окуляр ocular_scope, остальные — коллиматор
                boolean scope = def.scope() && (!def.sight() || scopeOc.get(e.getKey()));
                optics.add(r == null ? null : new Optic(r, scope));
                k++;
            }
            if (oc.isEmpty() && !divisions.isEmpty() && !views.isEmpty()) {
                Reticle r = Reticle.build(path, model, 0, first, views.get(0), divisions.get(0), null, log);
                if (r != null) optics.add(new Optic(r, def.scope() && !def.sight()));
            }
        }
    }

    private static void mark(BbModel.Bone b, Set<Integer> out) {
        if (b == null) return;
        out.add(b.index);
        for (BbModel.Bone c : b.children) mark(c, out);
    }

    /** Разобрать модель обвеса (bytes — .bbmodel), текстура из файла. def — его запись в каталоге (оптика), может быть null. */
    static AttModel load(String path, byte[] bytes, Att.Def def, Logger log) {
        try {
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            BbModel m = BbModel.parse(path, json);
            Identifier[] ids = new Identifier[m.texturePngs.size()];
            NativeImage[] imgs = new NativeImage[ids.length];
            NativeImage first = null;
            for (int i = 0; i < ids.length; i++) {
                byte[] png = m.texturePngs.get(i);
                if (png.length == 0) continue;
                imgs[i] = NativeImage.read(png);
                if (first == null) first = imgs[i];
                ids[i] = Identifier.fromNamespaceAndPath("hoka_guns", "att/" + sanitize(path) + (i == 0 ? "" : "_" + i));
            }
            m.texturePngs.clear();
            AttModel am = new AttModel(path, m, ids, log, first, def);   // сетки читают текстуру до передачи игре
            for (int i = 0; i < ids.length; i++) {
                if (imgs[i] == null) continue;
                Minecraft.getInstance().getTextureManager().register(ids[i], new DynamicTexture(() -> "hoka guns " + path, imgs[i]));
            }
            log.info("guns: модель обвеса {} — костей {}, окуляров с сеткой {}, лучей {}", path, m.bones.size(),
                    am.optics.size(), am.beams.size());
            return am;
        } catch (Exception e) {
            log.error("guns: модель обвеса {} не разобрана: {}", path, e.toString());
            return null;
        }
    }

    void close() {
        for (Identifier t : tex) if (t != null) Minecraft.getInstance().getTextureManager().release(t);
        for (Optic o : optics) if (o != null) o.reticle().close();
    }

    private static String sanitize(String p) {
        StringBuilder sb = new StringBuilder();
        for (char c : p.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }

    /** Глаз в прицеле номер view (с 1; нет такого — последний): матрица в координатах модели обвеса, null — нет глаз. */
    Matrix4f eye(int view) {
        if (views.isEmpty()) return null;
        BbModel.Bone b = views.get(Math.max(0, Math.min(views.size() - 1, view - 1)));
        return new Matrix4f(rest[b.index]).translate(b.origin);
    }

    /** Вид в прицел у глаза view (с 1): окуляр того же номера, нет — первый; null — сетки нет. */
    Optic optic(int view) {
        if (optics.isEmpty()) return null;
        Optic o = optics.get(Math.max(0, Math.min(optics.size() - 1, view - 1)));
        return o != null ? o : optics.get(0);
    }

    /** Начало и направление луча: точка и −z кости laser_beam в координатах модели обвеса. */
    void beam(BbModel.Bone b, Vector3f at, Vector3f dir) {
        at.set(b.origin);
        rest[b.index].transformPosition(at);
        dir.set(0, 0, -1);
        rest[b.index].transformDirection(dir);
    }

    /**
     * Нарисовать: pos — из координат модели обвеса в буфер (со сжатием под угол обзора), nrm — то же без сжатия
     * (свет). scoped — в прицеле (линза ocular_scope и ads_hide не видны).
     */
    void render(MultiBufferSource buffers, Matrix4f pos, Matrix4f nrm, int light, boolean aiming) {
        Matrix4f m = new Matrix4f(), n = new Matrix4f();
        for (int t = 0; t < tex.length; t++) {
            if (tex[t] == null) continue;
            VertexConsumer vc = buffers.getBuffer(RenderTypes.entityCutoutNoCull(tex[t]));
            for (BbModel.Bone b : model.bones) {
                if (b.faces.isEmpty() || hidden.contains(b.index)) continue;
                if (aiming && (adsHide.contains(b.index) || lens.contains(b.index))) continue;
                m.set(pos).mul(rest[b.index]);
                n.set(nrm).mul(rest[b.index]);
                ViewModel.faces(vc, m, n, b, illum.contains(b.index) ? LightTexture.FULL_BRIGHT : light, t);
            }
        }
    }
}
