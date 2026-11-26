package ink.hoka.odyssey.client.guns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Анимации тела игрока в формате «player_animation» (Bedrock 1.8.0, как у PlayerAnimator/emotecraft; TACZ кладёт
 * такие в player_animator/*.json): animations → имя → {loop, animation_length, bones → кость → rotation/position}.
 * Кости: head, torso (тело), right_arm, left_arm, right_leg, left_leg. Ключ — время (с) → [x, y, z] или
 * {post: [..] | {vector: [..]}, pre, lerp_mode}; вместо карты может быть сразу [x, y, z] (постоянное значение).
 *
 * <p>Смысл чисел (так их читает PlayerAnimator): поворот — градусы, прямо в xRot/yRot/zRot части модели;
 * положение — пиксели, x и z как у модели, y с обратным знаком (в файле вверх — плюс), прибавляется к месту части
 * по умолчанию. Здесь — только чтение и выборка; что с этим делать — {@link ThirdPerson}.
 */
final class PlayerAnim {
    static final String[] BONES = {"head", "torso", "right_arm", "left_arm", "right_leg", "left_leg"};

    record Key(float time, float x, float y, float z, boolean smooth) {}

    /** Каналы кости: null — канала нет. */
    record Track(Key[] rot, Key[] pos) {}

    record Clip(String name, float length, boolean loop, Track[] tracks) {
        /** Время внутри клипа: петля — по кругу, иначе — до конца и стоп. */
        float local(float t) {
            if (length <= 0) return 0;
            return loop ? t % length : Math.min(t, length);
        }
    }

    final Map<String, Clip> clips;

    private PlayerAnim(Map<String, Clip> clips) {
        this.clips = clips;
    }

    Clip clip(String name) {
        return clips.get(name);
    }

    static int boneIndex(String name) {
        for (int i = 0; i < BONES.length; i++) if (BONES[i].equals(name)) return i;
        return -1;
    }

    static PlayerAnim parse(byte[] bytes) {
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Clip> out = new HashMap<>();
        if (!(root.get("animations") instanceof JsonObject anims)) return new PlayerAnim(out);
        for (var e : anims.entrySet()) {
            if (!(e.getValue() instanceof JsonObject a)) continue;
            float len = a.has("animation_length") ? a.get("animation_length").getAsFloat() : 0;
            boolean loop = a.has("loop") && a.get("loop").isJsonPrimitive() && a.get("loop").getAsJsonPrimitive().isBoolean()
                    && a.get("loop").getAsBoolean();
            Track[] tracks = new Track[BONES.length];
            if (a.get("bones") instanceof JsonObject bones) {
                for (var b : bones.entrySet()) {
                    int i = boneIndex(b.getKey());
                    if (i < 0 || !(b.getValue() instanceof JsonObject bo)) continue;
                    tracks[i] = new Track(keys(bo.get("rotation")), keys(bo.get("position")));
                }
            }
            out.put(e.getKey(), new Clip(e.getKey(), len, loop, tracks));
        }
        return new PlayerAnim(out);
    }

    private static Key[] keys(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        List<Key> l = new ArrayList<>();
        if (el.isJsonArray()) {
            float[] v = vec(el);
            if (v != null) l.add(new Key(0, v[0], v[1], v[2], false));
        } else if (el instanceof JsonObject o) {
            for (var k : o.entrySet()) {
                float t;
                try {
                    t = Float.parseFloat(k.getKey());
                } catch (NumberFormatException ex) {
                    continue;
                }
                JsonElement v = k.getValue();
                boolean smooth = false;
                float[] pre = null;
                float[] post;
                if (v instanceof JsonObject kv) {
                    smooth = kv.has("lerp_mode") && "catmullrom".equals(kv.get("lerp_mode").getAsString());
                    post = vec(kv.get("post"));
                    pre = vec(kv.get("pre"));
                    if (post == null) post = vec(kv.get("vector"));
                } else {
                    post = vec(v);
                }
                if (post == null) continue;
                if (pre != null && t > 0) l.add(new Key(t - 1e-4f, pre[0], pre[1], pre[2], smooth));
                l.add(new Key(t, post[0], post[1], post[2], smooth));
            }
        }
        if (l.isEmpty()) return null;
        l.sort((p, q) -> Float.compare(p.time(), q.time()));
        return l.toArray(Key[]::new);
    }

    private static float[] vec(JsonElement e) {
        if (e instanceof JsonObject o && o.has("vector")) e = o.get("vector");
        if (!(e instanceof JsonArray a) || a.size() < 3) return null;
        try {
            return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        } catch (RuntimeException ex) {
            return null;                                        // Molang — не поддерживаем
        }
    }

    /** Значение канала в момент t (с) в out; false — канала нет. Catmull-Rom там, где у ключа lerp_mode catmullrom. */
    static boolean sample(Key[] keys, float t, float[] out) {
        if (keys == null) return false;
        if (t <= keys[0].time()) return set(out, keys[0]);
        Key last = keys[keys.length - 1];
        if (t >= last.time()) return set(out, last);
        int i = 0;
        while (keys[i + 1].time() < t) i++;
        Key a = keys[i], b = keys[i + 1];
        float f = (t - a.time()) / Math.max(1e-6f, b.time() - a.time());
        if (a.smooth() || b.smooth()) {
            Key p = keys[Math.max(0, i - 1)], n = keys[Math.min(keys.length - 1, i + 2)];
            out[0] = cr(p.x(), a.x(), b.x(), n.x(), f);
            out[1] = cr(p.y(), a.y(), b.y(), n.y(), f);
            out[2] = cr(p.z(), a.z(), b.z(), n.z(), f);
        } else {
            out[0] = a.x() + (b.x() - a.x()) * f;
            out[1] = a.y() + (b.y() - a.y()) * f;
            out[2] = a.z() + (b.z() - a.z()) * f;
        }
        return true;
    }

    private static boolean set(float[] out, Key k) {
        out[0] = k.x();
        out[1] = k.y();
        out[2] = k.z();
        return true;
    }

    private static float cr(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
