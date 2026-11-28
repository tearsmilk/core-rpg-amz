package ink.hoka.odyssey.guns;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Каталог обвесов (attachments.yml: метки, повышающие планки, обвесы TACZ с модификаторами) и обвес каждого оружия
 * (раздел {@code guns.<id>.attachments} config.yml). Неизменяемый после чтения; {@code homodule reload guns} читает
 * заново. Без Minestom.
 */
final class Attachments {

    /**
     * Слоты обвеса (порядок — номер слота в протоколе). LASER2 — второй ЛЦУ справа (только у оружия, что его объявило,
     * сейчас plasma_rifle): принимает те же ЛЦУ, что LASER; в конце, чтобы номера прежних слотов не сдвинулись.
     */
    enum Slot {
        SCOPE("scope", "hoka_att_scope", "Прицел"),
        MUZZLE("muzzle", "hoka_att_muzzle", "Дуло"),
        STOCK("stock", "hoka_att_stock", "Приклад"),
        GRIP("grip", "hoka_att_grip", "Рукоять"),
        LASER("laser", "hoka_att_laser", "ЛЦУ"),
        MAG("extended_mag", "hoka_att_mag", "Магазин"),
        LASER2("laser2", "hoka_att_laser2", "ЛЦУ (право)");

        final String key;
        final String tag;
        final String ru;

        Slot(String key, String tag, String ru) {
            this.key = key;
            this.tag = tag;
            this.ru = ru;
        }

        static Slot of(String key) {
            if (key == null) {
                return null;
            }
            for (Slot s : values()) {
                if (s.key.equalsIgnoreCase(key) || s.name().equalsIgnoreCase(key) || (s == MAG && key.equalsIgnoreCase("mag"))) {
                    return s;
                }
            }
            return null;
        }

        static Slot of(int index) {
            return index >= 0 && index < values().length ? values()[index] : null;
        }

        /** Встаёт ли в этот слот обвес вида kind (во второй ЛЦУ — те же ЛЦУ, что в первый). */
        boolean accepts(Slot kind) {
            return this == kind || this == LASER2 && kind == LASER;
        }

        /** Название слота по-русски у оружия ga (с двумя ЛЦУ первый — «ЛЦУ (лево)»). */
        String ru(GunAtt ga) {
            return this == LASER && ga != null && ga.has(LASER2) ? "ЛЦУ (лево)" : ru;
        }
    }

    /**
     * Модификатор одной характеристики (как в TACZ): (база + add) × max(0, 1 + percent) × mul, потом операция op
     * (если задана).
     */
    record Mod(double add, double percent, double mul, Op op) {}

    /**
     * Операция вместо функции Lua TACZ: set — заменить числом; gt+thenAdd — «если больше gt, прибавить»;
     * gt+thenMul/elseMul — «если больше gt, умножить на thenMul, иначе на elseMul».
     */
    record Op(double set, double gt, double thenAdd, double thenMul, double elseMul) {
        double apply(double x) {
            if (!Double.isNaN(set)) {
                return set;
            }
            if (Double.isNaN(gt)) {
                return x;
            }
            if (x > gt) {
                return !Double.isNaN(thenAdd) ? x + thenAdd : x * (Double.isNaN(thenMul) ? 1 : thenMul);
            }
            return x * (Double.isNaN(elseMul) ? 1 : elseMul);
        }
    }

    /** Обвес из каталога. laserColor −1 — не ЛЦУ. */
    record Def(String id, Slot slot, String name, int sort, boolean hidden, boolean builtin, double weight,
               String item, String icon, String model, String lod, String soundInstall, String soundUninstall,
               double[] zoom, String adapterBone, int laserColor, boolean laserEditable,
               Map<String, Mod> mods, double flashHider, int xmagLevel, boolean explosion, boolean ignite,
               boolean silence, double silenceDistance, boolean silenceSound, boolean singleProjectile) {

        boolean laser() {
            return laserColor >= 0;
        }

        /** Кратность прицела уровня level (0 — первая); нет оптики — NaN. */
        double zoomAt(int level) {
            if (zoom.length == 0) {
                return Double.NaN;
            }
            return zoom[Math.max(0, Math.min(zoom.length - 1, level))];
        }
    }

    /** Повышающая планка (TACZ slot adapter): слот, какие обвесы ещё разрешает, модель, подъём. */
    record Adapter(String id, String name, Slot slot, Set<String> allow, String model, double[] mountOffset) {}

    /** Взрыв оружия (с фугасными патронами): урон в центре, радиус, отбрасывает ли. */
    record Explosion(double damage, double radius, boolean knockback) {}

    /** Обвес одного оружия: слоты, что разрешено (метки раскрыты), встроенные, по умолчанию, запертые слоты и т.п. */
    record GunAtt(List<Slot> slots, Set<String> allow, Set<String> adapters, Map<Slot, List<String>> builtin,
                  Map<Slot, String> defaults, Set<Slot> locked, int[] extendedMag, GunDef.Reload xmagTactical,
                  GunDef.Reload xmagEmpty, Explosion explosion, double armorIgnore, List<String> allowRaw,
                  boolean paint) {

        static final GunAtt NONE = new GunAtt(List.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of(), new int[0],
                null, null, null, 0, List.of(), false);

        boolean has(Slot s) {
            return slots.contains(s);
        }

        boolean locked(Slot s) {
            return locked.contains(s);
        }

        boolean builtinIn(Slot s, String id) {
            List<String> l = builtin.get(s);
            return l != null && l.contains(id);
        }
    }

    private final Map<String, List<String>> tags;
    private final Map<String, Def> defs;
    private final Map<String, Adapter> adapters;
    /** Как прочитан файл (для клиентского модуля — JSON с тем же содержимым). */
    private final Map<?, ?> raw;

    private Attachments(Map<String, List<String>> tags, Map<String, Def> defs, Map<String, Adapter> adapters, Map<?, ?> raw) {
        this.tags = tags;
        this.defs = defs;
        this.adapters = adapters;
        this.raw = raw;
    }

    static final Attachments EMPTY = new Attachments(Map.of(), Map.of(), Map.of(), Map.of());

    Def get(String id) {
        return id == null ? null : defs.get(id);
    }

    Collection<Def> all() {
        return defs.values();
    }

    Adapter adapter(String id) {
        return id == null ? null : adapters.get(id);
    }

    Collection<Adapter> adapters() {
        return adapters.values();
    }

    Map<?, ?> raw() {
        return raw;
    }

    /** Раскрыть список id и #меток в id (повторы и неизвестные метки — пропускаются). */
    Set<String> expand(List<String> list) {
        Set<String> out = new LinkedHashSet<>();
        expand(list, out, new LinkedHashSet<>());
        return out;
    }

    private void expand(List<String> list, Set<String> out, Set<String> seen) {
        for (String e : list) {
            if (e.startsWith("#")) {
                String t = e.substring(1);
                if (seen.add(t)) {
                    expand(tags.getOrDefault(t, List.of()), out, seen);
                }
            } else {
                out.add(e);
            }
        }
    }

    // ---------------------------------------------------------------- чтение

    /** module/guns/attachments.yml, иначе из jar. */
    static Attachments load(Path dataFolder) throws IOException {
        String text = null;
        Path own = dataFolder == null ? null : dataFolder.resolve("attachments.yml");
        if (own != null && Files.isRegularFile(own)) {
            text = Files.readString(own, StandardCharsets.UTF_8);
        } else {
            try (InputStream in = Attachments.class.getResourceAsStream("/attachments.yml")) {
                if (in != null) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        if (text == null) {
            return EMPTY;
        }
        LoaderOptions lo = new LoaderOptions();
        lo.setCodePointLimit(16 * 1024 * 1024);
        Object o = new Yaml(new SafeConstructor(lo)).load(text);
        return parse(o instanceof Map<?, ?> m ? m : Map.of());
    }

    static Attachments parse(Map<?, ?> root) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        if (root.get("tags") instanceof Map<?, ?> t) {
            for (Map.Entry<?, ?> e : t.entrySet()) {
                tags.put(String.valueOf(e.getKey()), strings(e.getValue()));
            }
        }
        Map<String, Adapter> adapters = new LinkedHashMap<>();
        if (root.get("adapters") instanceof Map<?, ?> am) {
            for (Map.Entry<?, ?> e : am.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> a)) {
                    continue;
                }
                String id = String.valueOf(e.getKey());
                Slot slot = Slot.of(str(a.get("slot"), "scope"));
                adapters.put(id, new Adapter(id, str(a.get("name"), id), slot == null ? Slot.SCOPE : slot,
                        Set.copyOf(strings(a.get("allow"))), str(a.get("model"), ""), nums(a.get("mount-offset"))));
            }
        }
        Attachments tmp = new Attachments(tags, Map.of(), adapters, root);
        Map<String, Adapter> expandedAdapters = new LinkedHashMap<>();
        for (Adapter a : adapters.values()) {
            expandedAdapters.put(a.id(), new Adapter(a.id(), a.name(), a.slot(), tmp.expand(List.copyOf(a.allow())),
                    a.model(), a.mountOffset()));
        }
        Map<String, Def> defs = new LinkedHashMap<>();
        if (root.get("attachments") instanceof Map<?, ?> am) {
            for (Map.Entry<?, ?> e : am.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> a)) {
                    continue;
                }
                String id = String.valueOf(e.getKey());
                Slot slot = Slot.of(str(a.get("type"), ""));
                if (slot == null) {
                    continue;
                }
                defs.put(id, def(id, slot, a));
            }
        }
        return new Attachments(tags, defs, expandedAdapters, root);
    }

    private static Def def(String id, Slot slot, Map<?, ?> a) {
        Map<?, ?> dis = a.get("display") instanceof Map<?, ?> d ? d : Map.of();
        Map<?, ?> snd = a.get("sounds") instanceof Map<?, ?> s ? s : Map.of();
        Map<?, ?> ms = a.get("mods") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Mod> mods = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ms.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> m && !"silence".equals(e.getKey()) && !"melee".equals(e.getKey())) {
                mods.put(String.valueOf(e.getKey()), mod(m));
            }
        }
        int laser = -1;
        boolean editable = false;
        if (dis.get("laser") instanceof Map<?, ?> l) {
            laser = GunDef.color(str(l.get("color"), "#FF0000"), 0xFF0000);
            editable = Boolean.TRUE.equals(l.get("editable"));
        }
        Map<?, ?> sil = ms.get("silence") instanceof Map<?, ?> s ? s : null;
        return new Def(id, slot, str(a.get("name"), id), (int) GunDef.num(a.get("sort"), 0),
                Boolean.TRUE.equals(a.get("hidden")), Boolean.TRUE.equals(a.get("builtin")),
                GunDef.num(a.get("weight"), 0), str(a.get("item"), ""), str(a.get("icon"), ""), str(a.get("model"), ""),
                str(a.get("lod"), ""), str(snd.get("install"), ""), str(snd.get("uninstall"), ""),
                nums(dis.get("zoom")), str(dis.get("adapter"), ""), laser, editable, Map.copyOf(mods),
                GunDef.num(ms.get("flash-hider"), Double.NaN), (int) GunDef.num(ms.get("extended-mag-level"), 0),
                Boolean.TRUE.equals(ms.get("explosion")), Boolean.TRUE.equals(ms.get("ignite")),
                sil != null, sil == null ? 0 : GunDef.num(sil.get("distance"), 0),
                sil != null && !Boolean.FALSE.equals(sil.get("sound")), Boolean.TRUE.equals(ms.get("single-projectile")));
    }

    private static Mod mod(Map<?, ?> m) {
        Op op = null;
        if (m.get("op") instanceof Map<?, ?> o) {
            op = new Op(GunDef.num(o.get("set"), Double.NaN), GunDef.num(o.get("gt"), Double.NaN),
                    GunDef.num(o.get("then-add"), Double.NaN), GunDef.num(o.get("then-mul"), Double.NaN),
                    GunDef.num(o.get("else-mul"), Double.NaN));
        }
        return new Mod(GunDef.num(m.get("add"), 0), GunDef.num(m.get("percent"), 0), GunDef.num(m.get("mul"), 1), op);
    }

    /** Обвес оружия из config.yml (раздел guns.<id>.attachments); нет раздела — без обвеса. */
    GunAtt gun(String id, GunDef.Cfg c) {
        String b = "guns." + id + ".attachments";
        Object sec = c.get(b);
        Explosion ex = null;
        if (c.get("guns." + id + ".explosion") instanceof Map<?, ?> em) {
            ex = new Explosion(Math.max(0, GunDef.num(em.get("damage"), 0)), GunDef.clamp(GunDef.num(em.get("radius"), 1), 0.1, 16),
                    Boolean.TRUE.equals(em.get("knockback")));
        }
        double armor = Math.max(0, c.dbl("guns." + id + ".armor-ignore", 0));
        if (!(sec instanceof Map<?, ?>)) {
            return new GunAtt(List.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of(), new int[0], null, null, ex,
                    armor, List.of(), false);
        }
        List<Slot> slots = new ArrayList<>();
        for (String s : c.list(b + ".slots")) {
            Slot sl = Slot.of(s);
            if (sl != null && !slots.contains(sl)) {
                slots.add(sl);
            }
        }
        List<String> rawAllow = c.list(b + ".allow");
        Set<String> all = expand(rawAllow);
        Set<String> allow = new LinkedHashSet<>();
        Set<String> adapterIds = new LinkedHashSet<>();
        for (String x : all) {
            if (adapters.containsKey(x)) {
                adapterIds.add(x);
            } else {
                allow.add(x);
            }
        }
        Map<Slot, List<String>> builtin = new LinkedHashMap<>();
        for (String k : c.keys(b + ".builtin")) {
            Slot sl = Slot.of(k);
            if (sl != null) {
                builtin.put(sl, List.copyOf(c.list(b + ".builtin." + k)));
            }
        }
        Map<Slot, String> defaults = new LinkedHashMap<>();
        for (String k : c.keys(b + ".default")) {
            Slot sl = Slot.of(k);
            String v = c.str(b + ".default." + k, "");
            if (sl != null && !v.isEmpty()) {
                defaults.put(sl, v);
            }
        }
        Set<Slot> locked = new LinkedHashSet<>();
        for (String s : c.list(b + ".locked")) {
            Slot sl = Slot.of(s);
            if (sl != null) {
                locked.add(sl);
            }
        }
        List<String> xm = c.list(b + ".extended-mag");
        int[] xmag = new int[xm.size()];
        for (int i = 0; i < xmag.length; i++) {
            xmag[i] = GunDef.clamp((int) GunDef.num(xm.get(i), 1), 1, 1000);
        }
        GunDef.Reload xt = null;
        GunDef.Reload xe = null;
        if (c.get(b + ".reload-xmag") instanceof Map<?, ?>) {
            xt = reload(c, b + ".reload-xmag.tactical");
            xe = reload(c, b + ".reload-xmag.empty");
        }
        return new GunAtt(List.copyOf(slots), Set.copyOf(allow), Set.copyOf(adapterIds), Map.copyOf(builtin),
                Map.copyOf(defaults), Set.copyOf(locked), xmag, xt, xe, ex, armor, List.copyOf(rawAllow),
                Boolean.TRUE.equals(c.get(b + ".paint")));
    }

    private static GunDef.Reload reload(GunDef.Cfg c, String path) {
        if (!(c.get(path) instanceof Map<?, ?>)) {
            return null;
        }
        double f = GunDef.clamp(c.dbl(path + ".feed", 1), 0, 30);
        return new GunDef.Reload(f, GunDef.clamp(c.dbl(path + ".total", f), f, 30));
    }

    /**
     * Можно ли обвес def на оружие ga при планке adapter (id, «» — без неё): слот есть и не заперт, обвес в списке
     * оружия, встроенный этого оружия или разрешён планкой в этом слоте.
     */
    boolean allowed(GunAtt ga, Def def, String adapter) {
        return def != null && allowed(ga, def, adapter, def.slot());
    }

    /** То же для слота slot (второй ЛЦУ принимает ЛЦУ вида laser). */
    boolean allowed(GunAtt ga, Def def, String adapter, Slot slot) {
        if (def == null || slot == null || !slot.accepts(def.slot()) || !ga.has(slot)) {
            return false;
        }
        if (def.builtin()) {
            return ga.builtinIn(slot, def.id());
        }
        if (ga.allow().contains(def.id())) {
            return true;
        }
        Adapter a = adapter(adapter);
        return a != null && a.slot() == slot && ga.adapters().contains(a.id()) && a.allow().contains(def.id());
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }

    private static double[] nums(Object o) {
        if (!(o instanceof List<?> l)) {
            return new double[0];
        }
        double[] d = new double[l.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = GunDef.num(l.get(i), 0);
        }
        return d;
    }

    /** Название вида обвеса по-русски (для подсказок). */
    static String slotRu(Slot s) {
        return s == null ? "" : s.ru.toLowerCase(Locale.ROOT);
    }
}
