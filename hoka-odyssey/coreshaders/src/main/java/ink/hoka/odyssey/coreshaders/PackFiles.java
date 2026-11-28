package ink.hoka.odyssey.coreshaders;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PackFiles {
    public static final String NAMESPACE = "coreshaders";
    public static final double PLANE_PAD = 0.02;

    private PackFiles() {
    }

    public static String texture(String name) {
        return NAMESPACE + ":item/panorama/" + name;
    }

    public static List<String> paths(String name) {
        return List.of("coreshaders/textures/item/panorama/" + name + ".png",
            "coreshaders/textures/item/panorama/" + name + "_glow.png",
            "coreshaders/models/item/cube_" + name + ".json",
            "coreshaders/models/item/plane_" + name + ".json",
            "coreshaders/items/cube_" + name + ".json",
            "coreshaders/items/plane_" + name + ".json");
    }

    public static String meta(String name) {
        return "coreshaders/textures/item/panorama/" + name + ".png.mcmeta";
    }

    public static JsonObject animation(int frameW, int frameH, int[] ticks, boolean interpolate) {
        JsonObject a = new JsonObject();
        int main = ticks[0];
        boolean uniform = true;
        for (int t : ticks) {
            uniform &= t == main;
        }
        if (!uniform) {
            int bestN = 0;
            for (int t : ticks) {
                int n = 0;
                for (int u : ticks) {
                    n += u == t ? 1 : 0;
                }
                if (n > bestN) {
                    bestN = n;
                    main = t;
                }
            }
        }
        if (interpolate) {
            a.addProperty("interpolate", true);
        }
        a.addProperty("width", frameW);
        a.addProperty("height", frameH);
        a.addProperty("frametime", main);
        if (!uniform) {
            JsonArray frames = new JsonArray();
            for (int i = 0; i < ticks.length; i++) {
                if (ticks[i] == main) {
                    frames.add(i);
                } else {
                    JsonObject f = new JsonObject();
                    f.addProperty("index", i);
                    f.addProperty("time", ticks[i]);
                    frames.add(f);
                }
            }
            a.add("frames", frames);
        }
        JsonObject root = new JsonObject();
        root.add("animation", a);
        return root;
    }

    public static List<String> clashes(String name, Collection<String> others) {
        Set<String> taken = new HashSet<>();
        for (String o : others) {
            if (o != null && !o.equals(name)) {
                taken.addAll(paths(o));
            }
        }
        List<String> out = new ArrayList<>();
        for (String p : paths(name)) {
            if (taken.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static JsonArray arr(Number... v) {
        JsonArray a = new JsonArray();
        for (Number n : v) {
            a.add(n);
        }
        return a;
    }

    private static JsonObject face(String tex, int tint) {
        JsonObject f = new JsonObject();
        f.add("uv", arr(0, 0, 16, 16));
        f.addProperty("texture", "#" + tex);
        f.addProperty("tintindex", tint);
        return f;
    }

    private static JsonObject element(JsonArray from, JsonArray to, JsonObject faces) {
        JsonObject e = new JsonObject();
        e.add("from", from);
        e.add("to", to);
        e.addProperty("shade", false);
        e.addProperty("light_emission", 15);
        e.add("faces", faces);
        return e;
    }

    public static JsonObject cubeModel(String name) {
        JsonObject root = new JsonObject();
        JsonObject tex = new JsonObject();
        tex.addProperty("p", texture(name));
        tex.addProperty("particle", texture(name));
        root.add("textures", tex);
        JsonObject faces = new JsonObject();
        for (int i = 0; i < Faces.CUBE_FACES.length; i++) {
            faces.add(Faces.CUBE_FACES[i].id(), face("p", i));
        }
        JsonArray els = new JsonArray();
        els.add(element(arr(0, 0, 0), arr(16, 16, 16), faces));
        root.add("elements", els);
        root.add("display", new JsonObject());
        return root;
    }

    public static JsonObject planeModel(String name) {
        JsonObject root = new JsonObject();
        JsonObject tex = new JsonObject();
        tex.addProperty("p", texture(name));
        tex.addProperty("g", texture(name + "_glow"));
        tex.addProperty("particle", texture(name));
        root.add("textures", tex);
        JsonArray els = new JsonArray();
        JsonObject pf = new JsonObject();
        JsonObject gf = new JsonObject();
        for (int i = 0; i < Faces.PLANE_SIDES.length; i++) {
            pf.add(Faces.PLANE_SIDES[i].id(), face("p", i));
            gf.add(Faces.PLANE_SIDES[i].id(), face("g", i));
        }
        double lo = -PLANE_PAD;
        double hi = 16 + PLANE_PAD;
        els.add(element(arr(lo, lo, 8), arr(hi, hi, 8), pf));
        els.add(element(arr(-3, -3, 8), arr(19, 19, 8), gf));
        root.add("elements", els);
        root.add("display", new JsonObject());
        return root;
    }

    public static JsonObject itemDefinition(String model, int tints) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "minecraft:model");
        m.addProperty("model", NAMESPACE + ":item/" + model);
        JsonArray ts = new JsonArray();
        for (int i = 0; i < tints; i++) {
            JsonObject t = new JsonObject();
            t.addProperty("type", "minecraft:custom_model_data");
            t.addProperty("index", i);
            t.addProperty("default", -1);
            ts.add(t);
        }
        m.add("tints", ts);
        JsonObject root = new JsonObject();
        root.add("model", m);
        return root;
    }

    public static String json(JsonObject o) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n";
    }
}
