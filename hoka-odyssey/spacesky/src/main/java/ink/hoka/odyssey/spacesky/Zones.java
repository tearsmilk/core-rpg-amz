package ink.hoka.odyssey.spacesky;

import ink.hoka.odyssey.api.ModuleConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Области космоса: Млечный путь, туманности, звёздные скопления видны только когда камера внутри области
 * (круг по x/z). Хранятся в config.yml (раздел zones), в шейдер попадают файлом spacesky:zones.glsl.
 */
final class Zones {
    /** Не больше стольких областей: цикл в sky.vsh — на каждой вершине неба. */
    static final int MAX = 32;
    static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    static final String PATH = "assets/spacesky/shaders/include/zones.glsl";

    enum Kind {
        MILKY_WAY("milky_way", "Млечный путь"),
        NEBULA("nebula", "туманность"),
        STARS("stars", "звёздное скопление");

        final String id;
        final String title;

        Kind(String id, String title) {
            this.id = id;
            this.title = title;
        }

        static Kind parse(String s) {
            if (s == null) {
                return null;
            }
            String v = s.trim().toLowerCase(Locale.ROOT);
            for (Kind k : values()) {
                if (k.id.equals(v)) {
                    return k;
                }
            }
            return switch (v) {
                case "milky", "млечный", "млечный_путь" -> MILKY_WAY;
                case "туманность" -> NEBULA;
                case "звёзды", "звезды", "скопление" -> STARS;
                default -> null;
            };
        }
    }

    /**
     * Область: центр x/z, радиус (блоки), доля края с плавным появлением (0..1), цвет туманности (-1 — цвет профиля,
     * nebula), профиль неба (null — во всех галактиках).
     */
    record Zone(String id, Kind kind, double x, double z, double radius, double fade, int color, String profile) {
        Zone(String id, Kind kind, double x, double z, double radius, double fade, int color) {
            this(id, kind, x, z, radius, fade, color, null);
        }
    }

    private Zones() {
    }

    /** Из config.yml; ошибки — в errors, такие области пропускаются. */
    static List<Zone> read(ModuleConfig c, List<String> errors) {
        List<Zone> out = new ArrayList<>();
        for (String id : c.keys("zones")) {
            String p = "zones." + id + ".";
            Kind kind = Kind.parse(c.getString(p + "type", ""));
            if (!ID.matcher(id).matches()) {
                errors.add(id + ": имя — только a-z, 0-9, _");
                continue;
            }
            if (kind == null) {
                errors.add(id + ": type — milky_way, nebula или stars");
                continue;
            }
            double r = c.getDouble(p + "radius", 0);
            if (!(r > 0)) {
                errors.add(id + ": radius должен быть больше 0");
                continue;
            }
            Object co = c.get(p + "color");
            Integer color = co == null ? Integer.valueOf(-1) : parseColor(String.valueOf(co));
            if (color == null) {
                errors.add(id + ": color — #RRGGBB");
                continue;
            }
            Object po = c.get(p + "profile");
            String profile = po == null ? null : String.valueOf(po).trim().toLowerCase(Locale.ROOT);
            if (profile != null && !Profiles.ID.matcher(profile).matches()) {
                errors.add(id + ": profile — имя профиля из profiles");
                continue;
            }
            if (out.size() >= MAX) {
                errors.add(id + ": больше " + MAX + " областей — лишние пропущены");
                break;
            }
            double fade = Math.max(0.0, Math.min(1.0, c.getDouble(p + "fade", 0.3)));
            out.add(new Zone(id, kind, c.getDouble(p + "x", 0), c.getDouble(p + "z", 0), r, fade, color, profile));
        }
        return out;
    }

    static void write(ModuleConfig c, Zone z) {
        String p = "zones." + z.id() + ".";
        c.set(p + "type", z.kind().id);
        c.set(p + "x", round(z.x()));
        c.set(p + "z", round(z.z()));
        c.set(p + "radius", round(z.radius()));
        c.set(p + "fade", z.fade());
        c.set(p + "color", z.kind() == Kind.NEBULA && z.color() >= 0 ? hex(z.color()) : null);
        c.set(p + "profile", z.profile());
    }

    /**
     * Изменить раздел zones файла config.yml, сохранив остальной текст и комментарии (ModuleConfig.save() их стирает):
     * файл читается заново (битый YAML — исключение, файл не трогается), change правит разделы, раздел zones
     * записывается текстом на место старого. Работа с диском — вызывать из async.
     */
    static void saveYaml(java.nio.file.Path file, java.util.function.Consumer<ModuleConfig> change) {
        ModuleConfig fresh = new ModuleConfig(file);
        change.accept(fresh);
        Object zs = fresh.get("zones");
        try {
            String text = java.nio.file.Files.exists(file)
                ? java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8) : "";
            java.nio.file.Files.writeString(file, replaceYaml(text, zs instanceof java.util.Map<?, ?> m ? m : java.util.Map.of()),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("не записать " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Текст config.yml с новым разделом zones (как HoleYaml в gravity): раздел — от строки {@code zones:} до следующей
     * строки с начала (ключ или комментарий в колонке 0), пустые строки перед ней остаются. Каждая область — одной
     * строкой {@code "имя": {поля}}; комментарии внутри раздела не сохраняются (описание полей — над {@code zones:}).
     */
    static String replaceYaml(String text, java.util.Map<?, ?> zones) {
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(text.split("\r?\n", -1)));
        List<String> block = new ArrayList<>();
        if (zones.isEmpty()) {
            block.add("zones: {}");
        } else {
            org.yaml.snakeyaml.DumperOptions o = new org.yaml.snakeyaml.DumperOptions();
            o.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.FLOW);
            o.setAllowUnicode(true);
            o.setWidth(1 << 16);
            org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml(o);
            block.add("zones:");
            zones.forEach((k, v) -> block.add("  \"" + String.valueOf(k).replace("\\", "\\\\").replace("\"", "\\\"")
                + "\": " + y.dump(v).strip()));
        }
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("zones:")) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            int end = lines.size();
            while (end > 0 && lines.get(end - 1).isEmpty()) {
                end--;
            }
            lines.addAll(end, block);
            String out = String.join("\n", lines);
            return out.endsWith("\n") ? out : out + "\n";
        }
        int end = at + 1;
        while (end < lines.size() && (lines.get(end).isEmpty() || Character.isWhitespace(lines.get(end).charAt(0)))) {
            end++;
        }
        while (end > at + 1 && lines.get(end - 1).isBlank()) {
            end--;
        }
        lines.subList(at, end).clear();
        lines.addAll(at, block);
        String out = String.join("\n", lines);
        return out.endsWith("\n") ? out : out + "\n";
    }

    private static Object round(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? (Object) (long) v : (Object) v;
    }

    static Integer parseColor(String s) {
        if (s == null) {
            return null;
        }
        String h = s.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (!h.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return Integer.parseInt(h, 16);
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /**
     * Текст spacesky:zones.glsl. profiles — номера профилей по id: область с профилем, которого нет, пропускается
     * (ошибка в errors). SS_ZONE_KIND.x = вид + 4 * (номер профиля + 1) (0 — во всех галактиках), цвет -1 — цвет
     * туманностей профиля.
     */
    static String glsl(List<Zone> all, java.util.Map<String, Integer> profiles, List<String> errors) {
        List<Zone> zones = new ArrayList<>();
        List<Integer> prof = new ArrayList<>();
        for (Zone z : all) {
            if (z.profile() == null) {
                zones.add(z);
                prof.add(-1);
            } else if (profiles.containsKey(z.profile())) {
                zones.add(z);
                prof.add(profiles.get(z.profile()));
            } else {
                errors.add(z.id() + ": нет профиля неба «" + z.profile() + "» (или он появится после перезапуска) — область пропущена");
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("// spacesky: области космоса — файл пишет модуль spacesky из config.yml, руками не менять.\n");
        b.append("#define SS_ZONES ").append(zones.size()).append('\n');
        if (zones.isEmpty()) {
            return b.toString();
        }
        b.append("const vec4 SS_ZONE_POS[SS_ZONES] = vec4[SS_ZONES](\n");
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            b.append("    vec4(").append(f(z.x())).append(", ").append(f(z.z())).append(", ").append(f(z.radius()))
                .append(", ").append(f(z.fade())).append(i + 1 < zones.size() ? "),  // " : ")   // ").append(z.id())
                .append(z.profile() != null ? " (" + z.profile() + ")" : "").append('\n');
        }
        b.append(");\n");
        b.append("const vec4 SS_ZONE_KIND[SS_ZONES] = vec4[SS_ZONES](\n");
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            int c = z.color();
            String rgb = c < 0 ? "-1.0, -1.0, -1.0" : f(((c >> 16) & 255) / 255.0) + ", " + f(((c >> 8) & 255) / 255.0)
                + ", " + f((c & 255) / 255.0);
            b.append("    vec4(").append(z.kind().ordinal() + 4 * (prof.get(i) + 1)).append(".0, ").append(rgb)
                .append(i + 1 < zones.size() ? ")," : ")").append('\n');
        }
        b.append(");\n");
        // целый центр: смещение камеры от центра (параллакс туманности) шейдер считает в int — точно на любом удалении
        b.append("const ivec2 SS_ZONE_IPOS[SS_ZONES] = ivec2[SS_ZONES](\n");
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            b.append("    ivec2(").append(iround(z.x())).append(", ").append(iround(z.z()))
                .append(i + 1 < zones.size() ? ")," : ")").append('\n');
        }
        b.append(");\n");
        return b.toString();
    }

    /** Округление координаты центра до целого блока (в пределах int). */
    static long iround(double v) {
        return Math.max(-(1L << 30), Math.min(1L << 30, Math.round(v)));
    }

    /** Без профилей (все области — во всех галактиках). */
    static String glsl(List<Zone> zones) {
        return glsl(zones, java.util.Map.of(), new ArrayList<>());
    }
}
