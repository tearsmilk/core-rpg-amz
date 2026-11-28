package ink.hoka.odyssey.spacesky;

import ink.hoka.odyssey.api.ModuleConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Профили неба: у каждого профиля свой тип мира «hoka:&lt;id&gt;» (см. {@link SpaceDimension}) и свой вид неба.
 * Карта выбирает профиль ключом {@code dimension: "hoka:<id>"} в maps/&lt;карта&gt;/map.yml (/hosky world).
 *
 * <ul>
 *   <li>{@code galaxy} — космос: фон, звёзды, Млечный путь, туманности (в областях zones), чёрные дыры.</li>
 *   <li>{@code planet} — атмосфера: цвета зенита и горизонта днём и ночью, закат, своё солнце (цвет, размер),
 *       облака, звёзды ночью, соседняя планета/луна с фазой.</li>
 * </ul>
 *
 * <p><b>Как шейдер узнаёт профиль — МЕТКА.</b> Небо и туман мира профиля i — цвет {@code (0, 24, i)}, туман под водой —
 * {@code (0, 24, 16 + i)} (байты). Красного в метке нет: у ванильного тумана и неба красный всегда не меньше 12% от
 * зелёного (проверено по данным client.jar 1.21.11: биомы, сутки, закат, дождь, гроза, смешение с небом, вода, лава,
 * снег, Незер, Энд), поэтому метку с ванильным цветом не спутать. Номер — отношение синего к зелёному: его не меняет
 * ни затемнение у дна мира, ни ночное зрение (умножение на число), ни затемнение экрана (там зелёный и синий
 * умножаются одинаково). Клиент смешивает туман с небом по дальности прорисовки — у профиля они равны, метка цела.
 *
 * <p><b>Время суток планеты</b> шейдер берёт из начала тумана окружения: таймлайн планеты ведёт
 * {@code fog_start_distance} от 30000 до 31000 за сутки (туман окружения с такого расстояния не виден), под водой —
 * {@code water_fog_start_distance} от −8 до −9. Числа с плавающей точкой клиент не округляет — фаза точная.
 */
final class Profiles {
    /** Не больше стольких профилей: номер — 4 бита в метке (и в шейдере — константные массивы). */
    static final int MAX = 16;
    static final String DEFAULT_ID = "space";
    static final String PREFIX = "hoka:";
    static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    static final String PATH = "assets/spacesky/shaders/include/profiles.glsl";
    /** Зелёный канал меток (байт). Синий — номер: 0..15 небо, 16..31 вода. */
    static final int MARK_G = 24;
    /** Фаза суток планеты: fog_start_distance = PHASE_BASE + PHASE_SPAN * фаза. */
    static final float PHASE_BASE = 30000f;
    static final float PHASE_SPAN = 1000f;
    /** Конец тумана окружения планеты (дальше начала, его не видно; свою дымку планете рисует fog.glsl — haze). */
    static final float FOG_END = 40000f;
    /** Начало тумана под водой (как в ванили); у планеты от него отнимается фаза (0..1 блока). */
    static final float WATER_START = -8f;

    enum Kind {
        GALAXY("galaxy", "галактика"),
        PLANET("planet", "планета");

        final String id;
        final String title;

        Kind(String id, String title) {
            this.id = id;
            this.title = title;
        }

        static Kind parse(String s) {
            String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "galaxy", "space", "галактика", "космос" -> GALAXY;
                case "planet", "планета" -> PLANET;
                default -> null;
            };
        }
    }

    /** Цвет 0..1 (для шейдера; в config.yml — #RRGGBB). */
    record Rgb(double r, double g, double b) {
        static Rgb of(int rgb) {
            return new Rgb(((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0);
        }

        int rgb() {
            return ch(r) << 16 | ch(g) << 8 | ch(b);
        }

        private static int ch(double v) {
            return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
        }

        String hex() {
            return Zones.hex(rgb());
        }

        String glsl() {
            return "vec3(" + f(r) + ", " + f(g) + ", " + f(b) + ")";
        }
    }

    /**
     * Профиль. Поля галактики и планеты — в одной записи (вид решает, какие действуют).
     *
     * @param index номер (метка); -1 — ещё не назначен (тип мира не зарегистрирован)
     */
    record Profile(String id, int index, Kind kind,
                   // галактика
                   Rgb background, Rgb backgroundTop, Rgb nebula, boolean milkyWay,
                   // звёзды (у планеты — ночью)
                   double stars, double starBrightness, Rgb starTint,
                   // планета
                   Rgb zenith, Rgb horizon, Rgb zenithNight, Rgb horizonNight, Rgb sunset, Rgb sun, double sunSize,
                   boolean clouds, Rgb cloudColor, Rgb fog, Rgb fogNight, double haze,
                   double moonSize, Rgb moonColor, double moonYaw, double moonPitch,
                   // вода (оба вида)
                   Rgb waterFog,
                   // параллакс галактики (у планеты — 0): размер плиток, доля ближних звёзд, пыль, даль туманностей
                   double parallax, double nearStars, double dust, double nebulaDistance) {

        String key() {
            return PREFIX + id;
        }

        boolean planet() {
            return kind == Kind.PLANET;
        }

        Profile withIndex(int i) {
            return new Profile(id, i, kind, background, backgroundTop, nebula, milkyWay, stars, starBrightness, starTint,
                zenith, horizon, zenithNight, horizonNight, sunset, sun, sunSize, clouds, cloudColor, fog, fogNight, haze,
                moonSize, moonColor, moonYaw, moonPitch, waterFog, parallax, nearStars, dust, nebulaDistance);
        }

        Profile withKind(Kind k) {
            return new Profile(id, index, k, background, backgroundTop, nebula, milkyWay, stars, starBrightness, starTint,
                zenith, horizon, zenithNight, horizonNight, sunset, sun, sunSize, clouds, cloudColor, fog, fogNight, haze,
                moonSize, moonColor, moonYaw, moonPitch, waterFog, parallax, nearStars, dust, nebulaDistance);
        }

        /** Есть ли частицы параллакса (ближние звёзды или пыль) — у галактики с parallax &gt; 0. */
        boolean particles() {
            return !planet() && parallax > 0 && (nearStars > 0 || dust > 0);
        }

        /** Размер плитки ближних звёзд, блоки (повторяется вокруг камеры; больше parallax — ближе звёзды). */
        int nearTile() {
            return tile(NEAR_TILE);
        }

        /** Размер плитки космической пыли, блоки. */
        int dustTile() {
            return tile(DUST_TILE);
        }

        private int tile(double base) {
            return parallax > 0 ? (int) Math.max(1, Math.round(base / parallax)) : 0;
        }

        /** Цвет тумана днём: у галактики — фон, у планеты — fog. */
        Rgb dayFog() {
            return planet() ? fog : background;
        }
    }

    // Вид космоса по умолчанию — как был до профилей (SPACESKY_BASE и градиент к зениту в sky.fsh).
    static final Rgb SPACE_BG = new Rgb(0.006, 0.007, 0.018);
    static final Rgb SPACE_BG_TOP = new Rgb(0.012, 0.011, 0.034);
    static final Rgb WHITE = new Rgb(1, 1, 1);
    static final Rgb VANILLA_WATER = Rgb.of(0x050533);
    /** Плитки параллакса при parallax 1, блоки: ближние звёзды и пыль (размер = база / parallax). */
    static final double NEAR_TILE = 256;
    static final double DUST_TILE = 24;
    /** Пределы parallax (кроме 0 — выключено): плитки от 64/6 до 1024/96 блоков. */
    static final double PARALLAX_MIN = 0.25;
    static final double PARALLAX_MAX = 4;

    private Profiles() {
    }

    /** Профиль со значениями по умолчанию (галактика — нынешний космос, планета — земное небо). */
    static Profile defaults(String id, Kind kind) {
        Rgb horizon = Rgb.of(0xA9C8F0);
        Rgb horizonNight = Rgb.of(0x0B1020);
        return new Profile(id, -1, kind, SPACE_BG, SPACE_BG_TOP, Rgb.of(0xB060FF), true, 1.0, 1.0, WHITE,
            Rgb.of(0x4A7FD8), horizon, Rgb.of(0x02030A), horizonNight, Rgb.of(0xFF8A40), Rgb.of(0xFFF4D6), 3.0,
            true, WHITE, horizon, horizonNight, 1024, 0, Rgb.of(0xC8C8D0), 200, 30, VANILLA_WATER,
            kind == Kind.GALAXY ? 1 : 0, kind == Kind.GALAXY ? 1 : 0, kind == Kind.GALAXY ? 1 : 0,
            kind == Kind.GALAXY ? 3000 : 0);
    }

    /**
     * Профили из config.yml (раздел profiles) по порядку; «space» всегда есть и всегда первый. Номера не назначены
     * (index -1). Ошибки — в errors, такие профили пропускаются (или поле берётся по умолчанию).
     */
    static List<Profile> read(ModuleConfig c, List<String> errors) {
        List<Profile> out = new ArrayList<>();
        boolean space = false;
        for (String id : c.keys("profiles")) {
            if (!ID.matcher(id).matches()) {
                errors.add("profiles." + id + ": имя — только a-z, 0-9, _ (до 32)");
                continue;
            }
            String p = "profiles." + id + ".";
            Kind kind = Kind.parse(c.getString(p + "type", "galaxy"));
            if (kind == null) {
                errors.add("profiles." + id + ": type — galaxy или planet");
                continue;
            }
            if (id.equals(DEFAULT_ID) && kind != Kind.GALAXY) {
                errors.add("profiles.space: профиль по умолчанию — только galaxy");
                kind = Kind.GALAXY;
            }
            if (out.size() >= MAX) {
                errors.add("profiles." + id + ": больше " + MAX + " профилей — лишние пропущены");
                break;
            }
            Profile pr = parse(c, p, defaults(id, kind), errors);
            if (id.equals(DEFAULT_ID)) {
                out.addFirst(pr);
                space = true;
            } else {
                out.add(pr);
            }
        }
        if (!space) {
            out.addFirst(defaults(DEFAULT_ID, Kind.GALAXY));
            if (out.size() > MAX) {
                errors.add("profiles." + out.removeLast().id() + ": больше " + MAX + " профилей (со space) — пропущен");
            }
        }
        return out;
    }

    private static Profile parse(ModuleConfig c, String p, Profile d, List<String> errors) {
        Reader r = new Reader(c, p, errors);
        Rgb horizon = r.color("horizon", d.horizon());
        Rgb horizonNight = r.color("horizon-night", d.horizonNight());
        boolean gal = d.kind() == Kind.GALAXY;                         // у планеты параллакса нет — поля не читаются
        double parallax = gal ? r.num("parallax", d.parallax(), 0, PARALLAX_MAX) : 0;
        if (parallax > 0 && parallax < PARALLAX_MIN) {
            parallax = PARALLAX_MIN;
        }
        return new Profile(d.id(), -1, d.kind(),
            r.color("background", d.background()), r.color("background-top", d.backgroundTop()),
            r.color("nebula", d.nebula()), r.bool("milky-way", d.milkyWay()),
            r.num("stars", d.stars(), 0, 3), r.num("star-brightness", d.starBrightness(), 0, 4),
            r.color("star-tint", d.starTint()),
            r.color("zenith", d.zenith()), horizon, r.color("zenith-night", d.zenithNight()), horizonNight,
            r.color("sunset", d.sunset()), r.color("sun", d.sun()), r.num("sun-size", d.sunSize(), 0, 30),
            r.bool("clouds", d.clouds()), r.color("cloud-color", d.cloudColor()),
            r.color("fog", horizon), r.color("fog-night", horizonNight), r.num("haze", d.haze(), 0, 100000),
            r.num("moon.size", d.moonSize(), 0, 60), r.color("moon.color", d.moonColor()),
            r.num("moon.yaw", d.moonYaw(), -360, 360), r.num("moon.pitch", d.moonPitch(), -90, 90),
            r.color("water-fog", d.waterFog()),
            parallax, gal ? r.num("near-stars", d.nearStars(), 0, 2) : 0, gal ? r.num("dust", d.dust(), 0, 2) : 0,
            gal ? r.num("nebula-distance", d.nebulaDistance(), 0, 1e6) : 0);
    }

    /** Чтение полей одного профиля с проверкой (неверное — по умолчанию и ошибка в список). */
    private record Reader(ModuleConfig c, String p, List<String> errors) {
        Rgb color(String k, Rgb def) {
            Object o = c.get(p + k);
            if (o == null) {
                return def;
            }
            Integer v = Zones.parseColor(String.valueOf(o));
            if (v == null) {
                errors.add(p + k + ": цвет — \"#RRGGBB\"");
                return def;
            }
            return Rgb.of(v);
        }

        double num(String k, double def, double lo, double hi) {
            Object o = c.get(p + k);
            if (o == null) {
                return def;
            }
            if (!(o instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                errors.add(p + k + ": число");
                return def;
            }
            return Math.max(lo, Math.min(hi, n.doubleValue()));
        }

        boolean bool(String k, boolean def) {
            Object o = c.get(p + k);
            if (o == null) {
                return def;
            }
            if (!(o instanceof Boolean b)) {
                errors.add(p + k + ": true или false");
                return def;
            }
            return b;
        }
    }

    // ===== метки =====

    /** МЕТКА профиля (цвет неба и тумана), RGB. */
    static int skyMark(int index) {
        return MARK_G << 8 | index;
    }

    /** МЕТКА ВОДЫ профиля (туман под водой), RGB. */
    static int waterMark(int index) {
        return MARK_G << 8 | (16 + index);
    }

    /** Номер профиля по МЕТКЕ неба (точный цвет), -1 — не метка. */
    static int skyIndex(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return r == 0 && g == MARK_G && b < 16 ? b : -1;
    }

    /** Карта map.yml → профиль: dimension «hoka:&lt;id&gt;» → id, иначе null. */
    static String profileOf(String dimension) {
        if (dimension == null || !dimension.startsWith(PREFIX)) {
            return null;
        }
        String id = dimension.substring(PREFIX.length());
        return ID.matcher(id).matches() ? id : null;
    }

    // ===== spacesky:profiles.glsl =====

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }

    private static String vec4(Rgb c, double w) {
        return "vec4(" + f(c.r()) + ", " + f(c.g()) + ", " + f(c.b()) + ", " + f(w) + ")";
    }

    /** Направление на луну: yaw — по горизонту (0 — +x, 90 — +z), pitch — над горизонтом. */
    static double[] moonDir(Profile p) {
        double y = Math.toRadians(p.moonYaw());
        double t = Math.toRadians(p.moonPitch());
        return new double[] {Math.cos(t) * Math.cos(y), Math.sin(t), Math.cos(t) * Math.sin(y)};
    }

    /**
     * Текст spacesky:profiles.glsl: разбор меток, фаза суток, параметры профилей для sky.vsh (константные массивы —
     * индекс один на кадр, берётся на вершинах) и туман профиля для fog.glsl (развёрнутая цепочка — без массивов на
     * пикселе). Номера без профиля (дыры в нумерации) заполняются космосом по умолчанию.
     */
    static String glsl(List<Profile> profiles) {
        List<Profile> ps = new ArrayList<>(profiles.stream().filter(p -> p.index() >= 0 && p.index() < MAX)
            .sorted(Comparator.comparingInt(Profile::index)).toList());
        int n = ps.isEmpty() ? 1 : ps.getLast().index() + 1;
        Profile[] byIndex = new Profile[n];
        for (Profile p : ps) {
            byIndex[p.index()] = p;
        }
        for (int i = 0; i < n; i++) {
            if (byIndex[i] == null) {
                byIndex[i] = defaults(i == 0 ? DEFAULT_ID : "_" + i, Kind.GALAXY).withIndex(i);
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("// spacesky: профили неба — файл пишет модуль spacesky из config.yml, руками не менять.\n");
        b.append("#ifndef SPACESKY_PROFILES_GLSL\n#define SPACESKY_PROFILES_GLSL\n");
        b.append("// профили:");
        for (Profile p : byIndex) {
            b.append(' ').append(p.index()).append('=').append(p.id()).append('(').append(p.kind().id).append(')');
        }
        b.append('\n');
        b.append("#define SS_PROFILES ").append(n).append('\n');
        long planets = java.util.Arrays.stream(byIndex).filter(Profile::planet).count();
        b.append("// планет среди профилей (0 — кода неба планеты в sky.fsh нет вовсе)\n");
        b.append("#define SS_PLANETS ").append(planets).append('\n');
        boolean plain = java.util.Arrays.stream(byIndex).filter(p -> !p.planet()).allMatch(p -> p.stars() == 1.0
            && p.starBrightness() == 1.0 && p.starTint().equals(WHITE));
        b.append("// 1 — у всех галактик звёзды по умолчанию (плотность, яркость, оттенок): sky.fsh считает их константами\n");
        b.append("#define SS_STARS_PLAIN ").append(plain ? 1 : 0).append('\n');
        b.append(DECODE);
        // массивы для sky.vsh
        String[] names = {"0 (галактика: фон у горизонта | планета: горизонт днём), w — вид (0 галактика, 1 планета)",
            "1 (фон к зениту | зенит днём), w — плотность звёзд",
            "2 оттенок звёзд, w — яркость звёзд",
            "3 (цвет туманностей | горизонт ночью), w — (Млечный путь 0/1 | угловой радиус солнца, рад)",
            "4 (- | зенит ночью), w — угловой радиус луны, рад (0 — нет)",
            "5 цвет заката, w — 0", "6 цвет солнца, w — 0", "7 направление на луну, w — 0", "8 цвет луны, w — 0",
            "9 параллакс (stars.vsh): доля ближних звёзд, пыль, плитка звёзд, плитка пыли (блоки; 0 — частиц нет)",
            "10 x — даль туманностей областей, блоки (0 — на бесконечности), yzw — 0"};
        for (int k = 0; k < names.length; k++) {
            b.append("// SS_PROF_").append(names[k]).append('\n');
            b.append("const vec4 SS_PROF_").append(k).append("[SS_PROFILES] = vec4[SS_PROFILES](\n");
            for (int i = 0; i < n; i++) {
                b.append("    ").append(row(byIndex[i], k)).append(i + 1 < n ? "," : "").append('\n');
            }
            b.append(");\n");
        }
        // туман для fog.glsl: развёрнутая цепочка по номеру (одна ветка на кадр)
        b.append("\n// Туман мира профиля p (fog.glsl): цвет (у планеты — по времени суток) и дымка окружения планеты.\n");
        b.append("// water — камера под водой (МЕТКА ВОДЫ); es/ee — начало и конец тумана окружения (из Fog).\n");
        b.append("vec3 spacesky_profile_fog(int p, bool water, inout float es, inout float ee) {\n");
        for (int i = 0; i < n; i++) {
            Profile p = byIndex[i];
            b.append(i == 0 ? "    if" : "    else if").append(" (p == ").append(i).append(") {  // ").append(p.id()).append('\n');
            if (p.planet()) {
                b.append("        if (water) return ").append(p.waterFog().glsl()).append(";\n");
                // слепота и тьма подменяют начало тумана своим (фазы в нём нет): ночной цвет, дальности — эффекта
                b.append("        if (es < 29999.0 || es > 31001.0) return ").append(p.fogNight().glsl()).append(";\n");
                b.append("        float day = spacesky_daylight(spacesky_sun_dir(spacesky_phase(es, false)).y);\n");
                if (p.haze() > 0) {
                    b.append("        es = 0.0; ee = ").append(f(p.haze())).append(";\n");
                } else {
                    b.append("        es = 1e9; ee = 2e9;\n");
                }
                b.append("        return mix(").append(p.fogNight().glsl()).append(", ").append(p.fog().glsl())
                    .append(", day);\n");
            } else {
                b.append("        return water ? ").append(p.waterFog().glsl()).append(" : ").append(p.background().glsl())
                    .append(";\n");
            }
            b.append("    }\n");
        }
        b.append("    return ").append(byIndex[0].background().glsl()).append(";\n}\n");
        b.append("#endif\n");
        return b.toString();
    }

    private static String row(Profile p, int k) {
        Rgb z = new Rgb(0, 0, 0);
        boolean pl = p.planet();
        return switch (k) {
            case 0 -> vec4(pl ? p.horizon() : p.background(), pl ? 1 : 0);
            case 1 -> vec4(pl ? p.zenith() : p.backgroundTop(), p.stars());
            case 2 -> vec4(p.starTint(), p.starBrightness());
            case 3 -> vec4(pl ? p.horizonNight() : p.nebula(), pl ? Math.toRadians(p.sunSize()) : (p.milkyWay() ? 1 : 0));
            case 4 -> vec4(pl ? p.zenithNight() : z, pl ? Math.toRadians(p.moonSize()) : 0);
            case 5 -> vec4(pl ? p.sunset() : z, 0);
            case 6 -> vec4(pl ? p.sun() : z, 0);
            case 7 -> {
                double[] d = pl ? moonDir(p) : new double[3];
                yield "vec4(" + f(d[0]) + ", " + f(d[1]) + ", " + f(d[2]) + ", 0.0)";
            }
            case 8 -> vec4(pl ? p.moonColor() : z, 0);
            case 9 -> {
                boolean on = p.particles();
                yield "vec4(" + f(on ? p.nearStars() : 0) + ", " + f(on ? p.dust() : 0) + ", " + (on ? p.nearTile() : 0)
                    + ".0, " + (on ? p.dustTile() : 0) + ".0)";
            }
            default -> "vec4(" + f(pl ? 0 : p.nebulaDistance()) + ", 0.0, 0.0, 0.0)";
        };
    }

    /** Разбор меток и времени суток — общая часть profiles.glsl (не зависит от профилей). */
    static final String DECODE = """
        // МЕТКА профиля i — цвет неба и тумана (0, 24, i) / 255, МЕТКА ВОДЫ — (0, 24, 16 + i) / 255. У ванильных цветов
        // тумана и неба красный не меньше 12% зелёного — с меткой не спутать. Номер — синий / зелёный: не меняется от
        // затемнения у дна мира, ночного зрения (умножение на число) и затемнения экрана (зелёный и синий — одинаково);
        // само затемнение — в яркости метки (spacesky_mark_level).
        // Код: 0..15 — небо профиля, 16..31 — вода профиля, -1 — не метка.
        int spacesky_code(vec3 c) {
            if (c.g < 1e-6 || c.r > c.g * 0.02) return -1;
            float k = c.b / c.g * 24.0;
            float i = floor(k + 0.5);
            if (abs(k - i) > 0.3 || i > 31.5) return -1;
            return int(i);
        }

        // Яркость метки (0..1): клиент умножает цвет тумана на число (слепота, тьма, затемнение у дна мира, босс) —
        // во столько же раз затемняется туман профиля. Ночное зрение (больше 1) цвет профиля не осветляет.
        float spacesky_mark_level(vec3 c) {
            return clamp(c.g * (255.0 / 24.0), 0.0, 1.0);
        }

        // Номер профиля по коду метки (-1 — не метка или такого профиля нет).
        int spacesky_code_profile(int code) {
            int p = code & 15;
            return code < 0 || p >= SS_PROFILES ? -1 : p;
        }

        // Цвет неба или тумана — МЕТКА неба какого-нибудь профиля?
        bool spacesky_marked(vec3 c) {
            int k = spacesky_code(c);
            return k >= 0 && k < 16 && k < SS_PROFILES;
        }

        // Цвет тумана (FogColor) — мира какого-нибудь профиля (МЕТКА или, под водой, МЕТКА ВОДЫ)?
        bool spacesky_fog_marked(vec3 c) {
            return spacesky_code_profile(spacesky_code(c)) >= 0;
        }

        // Фаза суток планеты (0 — восход, 0.25 — полдень, 0.5 — закат, 0.75 — полночь) из начала тумана окружения:
        // таймлайн ведёт его от 30000 до 31000 за сутки, под водой — от -8 до -9.
        float spacesky_phase(float envStart, bool water) {
            return fract(water ? -(envStart + 8.0) : (envStart - 30000.0) / 1000.0);
        }

        // Направление на солнце: восход на востоке (+x), полдень в зените, закат на западе (-x) — как в ванили.
        // Фаза равномерная, а ванильное солнце (sun_angle суток minecraft:day, кривая от полудня) у горизонта идёт быстрее:
        // та же кривая здесь — иначе на восходе солнце планеты на ~12° ниже ванильного, а свет мира уже дневной.
        vec3 spacesky_sun_dir(float phase) {
            float d = fract(phase - 0.25);                                  // доля суток от полудня
            float a = 6.2831853 * ((2.0 * d + 0.5 - 0.5 * cos(3.1415927 * d)) / 3.0 + 0.25);
            return vec3(cos(a), sin(a), 0.0);
        }

        // День (1) / ночь (0) по высоте солнца.
        float spacesky_daylight(float sunY) {
            return smoothstep(-0.12, 0.2, sunY);
        }
        """;

    /** Номер → профиль (для поиска по id). */
    static Profile byId(List<Profile> ps, String id) {
        for (Profile p : ps) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** id → номер (только профили с номером). */
    static Map<String, Integer> indexes(List<Profile> ps) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (Profile p : ps) {
            if (p.index() >= 0) {
                m.put(p.id(), p.index());
            }
        }
        return m;
    }
}
