package ink.hoka.odyssey.client.guns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Обвес оружия (как в TACZ) на стороне клиента: каталог из файла сервера {@code guns/attachments.json} (то же, что
 * attachments.yml серверного модуля, и у каждого оружия — его слоты, что подходит, встроенные, запертые), что стоит на
 * оружии ({@link Gear}) и пересчёт характеристик для окна обвеса (те же правила модификаторов, что на сервере:
 * (база + сумма add) × max(0, 1 + сумма percent) × произведение mul, потом op). Ставит и снимает обвес только сервер —
 * клиент шлёт просьбы и рисует то, что сервер прислал.
 */
final class Att {
    /** Слоты по номеру протокола: 0 прицел, 1 дуло, 2 приклад, 3 рукоять, 4 ЛЦУ, 5 магазин, 6 второй ЛЦУ (справа). */
    static final String[] SLOTS = {"scope", "muzzle", "stock", "grip", "laser", "extended_mag", "laser2"};
    static final String[] SLOT_RU = {"Прицел", "Дуло", "Приклад", "Рукоять", "ЛЦУ", "Магазин", "ЛЦУ (право)"};
    static final int SCOPE = 0;
    static final int MUZZLE = 1;
    static final int STOCK = 2;
    static final int GRIP = 3;
    static final int LASER = 4;
    static final int MAG = 5;
    static final int LASER2 = 6;

    private Att() {
    }

    /** Встаёт ли в слот slot обвес вида kind (во второй ЛЦУ — те же ЛЦУ, что в первый). */
    static boolean accepts(int slot, int kind) {
        return slot == kind || slot == LASER2 && kind == LASER;
    }

    /** Название слота у оружия g (с двумя ЛЦУ первый — «ЛЦУ (лево)»). */
    static String slotName(GunAtt g, int slot) {
        if (slot == LASER && g != null && g.has(LASER2)) return "ЛЦУ (лево)";
        return slot >= 0 && slot < SLOT_RU.length ? SLOT_RU[slot] : "?";
    }

    static int slotOf(String key) {
        if (key == null) return -1;
        String k = key.toLowerCase(Locale.ROOT);
        if (k.equals("mag")) return MAG;
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i].equals(k)) return i;
        return -1;
    }

    /** Модификатор характеристики; op — {set, gt, then-add, then-mul, else-mul} (NaN — нет), null — без op. */
    record Mod(double add, double percent, double mul, double[] op) {
        boolean hasOp() {
            return op != null;
        }

        double applyOp(double x) {
            if (op == null) return x;
            if (!Double.isNaN(op[0])) return op[0];
            if (Double.isNaN(op[1])) return x;
            if (x > op[1]) return !Double.isNaN(op[2]) ? x + op[2] : x * (Double.isNaN(op[3]) ? 1 : op[3]);
            return x * (Double.isNaN(op[4]) ? 1 : op[4]);
        }
    }

    /** Луч ЛЦУ: цвет RGB по умолчанию, можно ли менять, длина (блоков), ширина. */
    record Laser(int color, boolean editable, float length, float width) {}

    /** Обвес из каталога (поля — как в attachments.yml). */
    record Def(String id, int slot, String name, int sort, boolean hidden, boolean builtin, double weight, String icon,
               String model, String soundInstall, String soundUninstall, float[] zoom, int[] views, float[] viewsFov,
               float fov, boolean scope, boolean sight, float heightAdjust, String adapter, boolean showMuzzle,
               boolean showMount, float flashOffset, Laser laser, Map<String, Mod> mods, int xmagLevel) {
        /** Кратность номер level (по кругу); нет кратностей — 1. */
        float zoomAt(int level) {
            return zoom.length == 0 ? 1 : zoom[Math.floorMod(level, zoom.length)];
        }

        /** Какой глаз прицела (scope_view, scope_view_2…, с 1) у кратности level. */
        int viewAt(int level) {
            return views.length == 0 ? 1 : Math.max(1, views[Math.floorMod(level, views.length)]);
        }

        /** Угол обзора модели в прицеле у кратности level (0 — нет). */
        float fovAt(int level) {
            if (viewsFov.length > 0) return viewsFov[Math.floorMod(level, viewsFov.length)];
            return fov;
        }

        /** Путь к иконке в файлах clientmods (tacz/att/x.png → guns/att/x.png). */
        String iconFile() {
            if (icon == null || icon.isEmpty()) return "";
            return icon.startsWith("tacz/") ? "guns/" + icon.substring(5) : icon;
        }
    }

    /** Планка (переходник слота прицела): что на неё ставится, модель, сдвиг прицела вверх (пиксели). */
    record Adapter(String id, String name, int slot, int sort, List<String> allow, String model, float[] mountOffset) {}

    /** Обвес одного оружия: слоты, что подходит (метки раскрыты), планки, встроенные, по умолчанию, запертые. */
    record GunAtt(List<Integer> slots, Set<String> allow, List<String> adapters, Map<Integer, List<String>> builtin,
                  Map<Integer, String> defaults, Set<Integer> locked, int[] extendedMag, int baseMagazine, boolean paint) {
        static final GunAtt NONE = new GunAtt(List.of(), Set.of(), List.of(), Map.of(), Map.of(), Set.of(), new int[0], 0,
                false);

        boolean has(int slot) {
            return slots.contains(slot);
        }

        /** Магазин при уровне удлинения level (0 — штатный). */
        int magazine(int level) {
            if (level <= 0 || extendedMag.length == 0) return baseMagazine;
            return extendedMag[Math.min(extendedMag.length, level) - 1];
        }
    }

    /** Каталог обвесов и обвес каждого оружия (guns/attachments.json). */
    static final class Catalog {
        final Map<String, Def> defs = new HashMap<>();
        final Map<String, Adapter> adapters = new HashMap<>();
        final Map<String, List<String>> tags = new HashMap<>();
        final Map<String, GunAtt> guns = new HashMap<>();
        /** Имена костей оружия, которые показывает обвес (display.adapter): без него они спрятаны. */
        final Set<String> adapterBones = new HashSet<>();

        static final Catalog EMPTY = new Catalog();

        Def def(String id) {
            return id == null ? null : defs.get(id);
        }

        GunAtt gun(String id) {
            GunAtt g = id == null ? null : guns.get(id);
            return g == null ? GunAtt.NONE : g;
        }

        /** Раскрыть метки (#имя, вложенные) в id. */
        Set<String> expand(Collection<String> list) {
            Set<String> out = new LinkedHashSet<>();
            for (String s : list) expand(s, out, 0);
            return out;
        }

        private void expand(String s, Set<String> out, int depth) {
            if (s == null || depth > 16) return;
            if (s.startsWith("#")) {
                List<String> t = tags.get(s.substring(1));
                if (t != null) for (String x : t) expand(x, out, depth + 1);
            } else {
                out.add(s);
            }
        }

        /** Подходит ли обвес id оружию gun (список allow оружия или планка adapter в слоте прицела). */
        boolean allowed(String gun, Def d, String adapter) {
            return d != null && allowed(gun, d, adapter, d.slot());
        }

        /** То же для слота slot (второй ЛЦУ принимает ЛЦУ). */
        boolean allowed(String gun, Def d, String adapter, int slot) {
            if (d == null || !accepts(slot, d.slot())) return false;
            GunAtt g = gun(gun);
            if (!g.has(slot)) return false;
            if (g.allow().contains(d.id())) return true;
            if (d.slot() == SCOPE && adapter != null && !adapter.isEmpty()) {
                Adapter a = adapters.get(adapter);
                return a != null && expand(a.allow()).contains(d.id());
            }
            return false;
        }

        static Catalog parse(byte[] bytes) {
            Catalog c = new Catalog();
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("tags") instanceof JsonObject t) {
                for (var e : t.entrySet()) c.tags.put(e.getKey(), strings(e.getValue()));
            }
            if (root.get("adapters") instanceof JsonObject a) {
                for (var e : a.entrySet()) {
                    if (!(e.getValue() instanceof JsonObject o)) continue;
                    c.adapters.put(e.getKey(), new Adapter(e.getKey(), str(o, "name", e.getKey()), slotOf(str(o, "slot", "scope")),
                            (int) num(o, "sort", 0), strings(o.get("allow")), str(o, "model", ""), floats(o.get("mount-offset"))));
                }
            }
            if (root.get("attachments") instanceof JsonObject a) {
                for (var e : a.entrySet()) {
                    if (!(e.getValue() instanceof JsonObject o)) continue;
                    Def d = def(e.getKey(), o);
                    if (d.slot() < 0) continue;
                    c.defs.put(d.id(), d);
                    if (!d.adapter().isEmpty()) c.adapterBones.add(d.adapter());
                }
            }
            if (root.get("guns") instanceof JsonObject gs) {
                for (var e : gs.entrySet()) {
                    if (!(e.getValue() instanceof JsonObject o)) continue;
                    List<Integer> slots = new ArrayList<>();
                    for (String s : strings(o.get("slots"))) {
                        int i = slotOf(s);
                        if (i >= 0) slots.add(i);
                    }
                    Map<Integer, List<String>> bi = new HashMap<>();
                    if (o.get("builtin") instanceof JsonObject b) {
                        for (var x : b.entrySet()) {
                            int i = slotOf(x.getKey());
                            if (i >= 0) bi.put(i, strings(x.getValue()));
                        }
                    }
                    Map<Integer, String> df = new HashMap<>();
                    if (o.get("default") instanceof JsonObject b) {
                        for (var x : b.entrySet()) {
                            int i = slotOf(x.getKey());
                            if (i >= 0 && x.getValue().isJsonPrimitive()) df.put(i, x.getValue().getAsString());
                        }
                    }
                    Set<Integer> locked = new HashSet<>();
                    for (String s : strings(o.get("locked"))) {
                        int i = slotOf(s);
                        if (i >= 0) locked.add(i);
                    }
                    float[] xm = floats(o.get("extended-mag"));
                    int[] xmi = new int[xm.length];
                    for (int i = 0; i < xm.length; i++) xmi[i] = Math.round(xm[i]);
                    c.guns.put(e.getKey(), new GunAtt(slots, new HashSet<>(strings(o.get("allow"))),
                            strings(o.get("adapters")), bi, df, locked, xmi, (int) num(o, "base-magazine", 0),
                            bool(o, "paint", false)));
                }
            }
            return c;
        }

        private static Def def(String id, JsonObject o) {
            JsonObject d = o.get("display") instanceof JsonObject x ? x : new JsonObject();
            JsonObject snd = o.get("sounds") instanceof JsonObject x ? x : new JsonObject();
            Laser laser = null;
            if (d.get("laser") instanceof JsonObject l) {
                laser = new Laser(color(str(l, "color", "#FF0000")), bool(l, "editable", false),
                        (float) num(l, "length", 50), (float) num(l, "width", 0.008));
            }
            Map<String, Mod> mods = new HashMap<>();
            int xmag = 0;
            if (o.get("mods") instanceof JsonObject m) {
                for (var e : m.entrySet()) {
                    JsonElement v = e.getValue();
                    if (e.getKey().equals("extended-mag-level")) {
                        try {
                            xmag = v.getAsInt();
                        } catch (RuntimeException ignored) {
                            // не число
                        }
                        continue;
                    }
                    if (!(v instanceof JsonObject mo)) continue;
                    if (!mo.has("add") && !mo.has("percent") && !mo.has("mul") && !mo.has("op")) continue;
                    double[] op = null;
                    if (mo.get("op") instanceof JsonObject p) {
                        op = new double[] {num(p, "set", Double.NaN), num(p, "gt", Double.NaN), num(p, "then-add", Double.NaN),
                                num(p, "then-mul", Double.NaN), num(p, "else-mul", Double.NaN)};
                    }
                    mods.put(e.getKey(), new Mod(num(mo, "add", 0), num(mo, "percent", 0), num(mo, "mul", 1), op));
                }
            }
            float[] views = floats(d.get("views"));
            int[] vi = new int[views.length];
            for (int i = 0; i < views.length; i++) vi[i] = Math.round(views[i]);
            return new Def(id, slotOf(str(o, "type", "")), str(o, "name", id), (int) num(o, "sort", 0),
                    bool(o, "hidden", false), bool(o, "builtin", false), num(o, "weight", 0), str(o, "icon", ""),
                    str(o, "model", ""), str(snd, "install", ""), str(snd, "uninstall", ""), floats(d.get("zoom")), vi,
                    floats(d.get("views-fov")), (float) num(d, "fov", 0), bool(d, "scope", false), bool(d, "sight", false),
                    (float) num(d, "height-adjust", 0), str(d, "adapter", ""), bool(d, "show-muzzle", false),
                    bool(d, "show-mount", true), (float) num(d, "flash-offset", 0), laser, mods, xmag);
        }
    }

    // ---------------------------------------------------------------- что стоит на оружии

    /** Обвес на оружии: id по слоту (null — пусто), встроенный ли, свой цвет луча (−1 — по умолчанию), планка, кратность. */
    static final class Gear {
        final String[] ids = new String[SLOTS.length];
        final boolean[] builtin = new boolean[SLOTS.length];
        final int[] laser = new int[SLOTS.length];
        String adapter = "";
        int zoom;
        /** Уровень удлинения магазина от сервера (−1 — считать по каталогу). */
        int xmag = -1;
        /** Окраска: 0 — стандарт, 1 — чёрный металл. */
        int paint;

        Gear() {
            Arrays.fill(laser, -1);
        }

        Gear copy() {
            Gear g = new Gear();
            System.arraycopy(ids, 0, g.ids, 0, ids.length);
            System.arraycopy(builtin, 0, g.builtin, 0, builtin.length);
            System.arraycopy(laser, 0, g.laser, 0, laser.length);
            g.adapter = adapter;
            g.zoom = zoom;
            g.xmag = xmag;
            g.paint = paint;
            return g;
        }

        boolean has(int slot) {
            return ids[slot] != null;
        }

        /** Уровень удлинения магазина: от сервера, иначе — у обвеса в слоте магазина. */
        int xmagLevel(Catalog c) {
            if (xmag >= 0) return xmag;
            Def d = c.def(ids[MAG]);
            return d == null ? 0 : d.xmagLevel();
        }

        /** Цвет луча в слоте (свой или по умолчанию), −1 — не ЛЦУ. */
        int laserColor(Catalog c, int slot) {
            Def d = c.def(ids[slot]);
            if (d == null || d.laser() == null) return -1;
            return laser[slot] >= 0 ? laser[slot] : d.laser().color();
        }

        /** Ключ для кэша видимости костей. */
        String key() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < ids.length; i++) b.append(ids[i]).append(',');
            return b.append(adapter).append('|').append(xmag).toString();
        }

        /** GEAR: «слот=id[:rrggbb]» через запятую, «adapter=id» и «paint=n». */
        static Gear parseCsv(String csv) {
            Gear g = new Gear();
            if (csv == null || csv.isEmpty()) return g;
            for (String part : csv.split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String k = part.substring(0, eq).trim(), v = part.substring(eq + 1).trim();
                if (k.equals("adapter")) {
                    g.adapter = v;
                    continue;
                }
                if (k.equals("paint")) {
                    try {
                        g.paint = Math.max(0, Integer.parseInt(v));
                    } catch (NumberFormatException ignored) {
                        // стандарт
                    }
                    continue;
                }
                int s = slotOf(k);
                if (s < 0 || v.isEmpty()) continue;
                int c = v.indexOf(':');
                if (c > 0) {
                    try {
                        g.laser[s] = Integer.parseInt(v.substring(c + 1), 16) & 0xFFFFFF;
                    } catch (NumberFormatException ignored) {
                        // как по умолчанию
                    }
                    v = v.substring(0, c);
                }
                g.ids[s] = v;
            }
            return g;
        }
    }

    // ---------------------------------------------------------------- характеристики (окно обвеса)

    /** Модификаторы key всех обвесов gear. */
    static List<Mod> mods(Catalog c, Gear g, String key) {
        List<Mod> out = new ArrayList<>();
        for (String id : g.ids) {
            Def d = c.def(id);
            if (d == null) continue;
            Mod m = d.mods().get(key);
            if (m != null) out.add(m);
        }
        return out;
    }

    static double apply(double base, List<Mod> mods) {
        double add = 0, pct = 0, mul = 1;
        for (Mod m : mods) {
            add += m.add();
            pct += m.percent();
            mul *= Math.max(0, m.mul());
        }
        double v = (base + add) * Math.max(0, 1 + pct) * mul;
        for (Mod m : mods) v = m.applyOp(v);
        return v;
    }

    /** База по значению с модификаторами (обратно к {@link #apply}); NaN — не обратить (op или множитель 0). */
    static double invert(double eff, List<Mod> mods) {
        double add = 0, pct = 0, mul = 1;
        for (Mod m : mods) {
            if (m.hasOp()) return Double.NaN;
            add += m.add();
            pct += m.percent();
            mul *= Math.max(0, m.mul());
        }
        double k = Math.max(0, 1 + pct) * mul;
        if (k < 1e-9) return Double.NaN;
        return eff / k - add;
    }

    /** Вес обвеса. */
    static double weight(Catalog c, Gear g) {
        double w = 0;
        for (String id : g.ids) {
            Def d = c.def(id);
            if (d != null) w += d.weight();
        }
        return w;
    }

    // ---------------------------------------------------------------- json

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e instanceof JsonArray a) {
            for (JsonElement x : a) if (x.isJsonPrimitive()) out.add(x.getAsString());
        } else if (e != null && e.isJsonPrimitive()) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static float[] floats(JsonElement e) {
        if (e instanceof JsonArray a) {
            float[] out = new float[a.size()];
            for (int i = 0; i < out.length; i++) {
                try {
                    out[i] = a.get(i).getAsFloat();
                } catch (RuntimeException ex) {
                    out[i] = 0;
                }
            }
            return out;
        }
        if (e != null && e.isJsonPrimitive()) {
            try {
                return new float[] {e.getAsFloat()};
            } catch (RuntimeException ex) {
                return new float[0];
            }
        }
        return new float[0];
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static double num(JsonObject o, String k, double def) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def;
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /** «#RRGGBB» / «0xRRGGBB» / «RRGGBB» → RGB. */
    static int color(String s) {
        if (s == null) return 0xFF0000;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        try {
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFF0000;
        }
    }
}
