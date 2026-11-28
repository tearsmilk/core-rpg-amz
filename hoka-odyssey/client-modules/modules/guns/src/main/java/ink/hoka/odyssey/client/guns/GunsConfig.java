package ink.hoka.odyssey.client.guns;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Настройки модуля: {@code <dataDir>/config.properties} (создаётся с комментариями при первом запуске). */
final class GunsConfig {
    private static final String DEFAULT = """
            # Hoka Odyssey — модуль guns (огнестрел). Файл читается при загрузке модуля (вход на сервер,
            # /hoclient reload). Клавиши — имена Minecraft: key.keyboard.r, key.keyboard.v, key.mouse.left, key.mouse.4 …
            config_version = 3

            # Выстрел — кнопка «атаковать» игры (ЛКМ; зажать — очередь в режиме «авто»), прицел — «использовать» (ПКМ).
            # Перезарядка (работает, только пока в руке оружие — иначе R остаётся у spaceflight: «лечь» в невесомости)
            reload = key.keyboard.r
            # Режим огня: очередь / одиночный
            firemode = key.keyboard.v
            # Окно обвеса (как в TACZ): с оружием в руке — прицелы, глушители, приклады, рукояти, ЛЦУ, магазины
            refit = key.keyboard.y
            # Кратность прицела (у прицелов с несколькими кратностями), пока целишься
            zoom = key.keyboard.g

            # Размер оружия в руке (1 — как в TACZ по пикселям модели; меньше — меньше «влезает» в стены)
            viewmodel_scale = 0.3
            # Угол обзора оружия и рук в руке, градусы (не зависит от настройки «угол обзора» — иначе при большом угле
            # оружие и руки растягиваются и вылезают снизу). model — как в TACZ: свой у каждого оружия (model_fov от
            # бедра, zoom_model_fov в прицеле; у АКМ, AWP и Glock — 60, в прицеле 45/20/45); число — один для всего
            # оружия (у руки в игре — 70); 0 — угол обзора мира
            viewmodel_fov = model
            # Наименьшая яркость оружия в руке, 0…15 (в космосе темно — иначе его не видно)
            min_light = 8
            # Трассеры пуль и вспышка у ствола
            tracers = true
            # Следы пуль на блоках (~7 с; у плазмы — подпалина со свечением)
            bullet_holes = true
            # Тряска камеры от анимаций выстрела и перезарядки (0 — нет, 1 — как в модели)
            camera_shake = 0.5
            # Сила отдачи камеры (1 — как у оружия на сервере, 0 — без отдачи)
            recoil = 1.0
            # Громкость своих звуков оружия, 0…1
            volume = 1.0
            # HUD патронов справа внизу
            hud = true
            # Свой гладкий моноширинный шрифт HUD (false — шрифт игры)
            mono_digits = true
            # Руки игрока (его скин) на оружии от первого лица, как в TACZ
            arms = true
            # Поза тела с оружием от третьего лица (свой в F5 и другие игроки): нужен крючок ядра onModelParts (0.56+)
            third_person = true
            # Оружие за спиной: оружие в быстрой панели, но не в руке, висит на спине (у себя в F5 и у других игроков);
            # пистолет — в кобуре на правом бедре
            back_gun = true
            # Оптика (AWP): чувствительность мыши в прицеле (1 — как без прицела, делённая на приближение, как у
            # подзорной трубы) и размер круга прицела (доля высоты экрана)
            scope_sensitivity = 1.0
            scope_size = 0.94
            # Сетка коллиматоров (красная точка и т.п.) поверх мира в прицеле
            sight_reticle = true
            # Лучи ЛЦУ (свой и других игроков)
            laser = true
            """;

    String reloadKey = "key.keyboard.r";
    String fireModeKey = "key.keyboard.v";
    String refitKey = "key.keyboard.y";
    String zoomKey = "key.keyboard.g";
    boolean sightReticle = true;
    boolean laser = true;
    float viewmodelScale = 0.3f;
    /** viewmodel_fov = model: угол обзора модели у каждого оружия свой (из TACZ). */
    static final float FOV_MODEL = -1f;
    float viewmodelFov = FOV_MODEL;
    int minLight = 8;
    boolean tracers = true;
    boolean bulletHoles = true;
    float cameraShake = 0.5f;
    float recoil = 1f;
    float volume = 1f;
    boolean hud = true;
    boolean monoDigits = true;
    boolean arms = true;
    boolean thirdPerson = true;
    boolean backGun = true;
    float scopeSensitivity = 1f;
    float scopeSize = 0.94f;
    /** Подстройка положения оружия за спиной (для проверки, в файле по умолчанию нет): угол°, x, y, z, масштаб, x°, y°, z°. */
    float[] backTune = {45, 0, 0, 0, 1, 0, 90, 180};
    /** Подстройка кобуры (в файле по умолчанию нет): x, y, z (пиксели от правой ноги), масштаб, x°, y°, z°. */
    float[] holsterTune = {-2.4f, 5f, 0f, 1f, 90f, 180f, 0f};

    static GunsConfig load(Path dir, Logger log) {
        GunsConfig c = new GunsConfig();
        Path file = dir.resolve("config.properties");
        try {
            Files.createDirectories(dir);
            if (!Files.isRegularFile(file)) {
                Files.writeString(file, DEFAULT, StandardCharsets.UTF_8);
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            c.reloadKey = p.getProperty("reload", c.reloadKey).trim();
            c.fireModeKey = p.getProperty("firemode", c.fireModeKey).trim();
            c.refitKey = p.getProperty("refit", c.refitKey).trim();
            c.zoomKey = p.getProperty("zoom", c.zoomKey).trim();
            c.sightReticle = bool(p, "sight_reticle", c.sightReticle);
            c.laser = bool(p, "laser", c.laser);
            c.viewmodelScale = (float) num(p, "viewmodel_scale", c.viewmodelScale, 0.05, 2);
            c.viewmodelFov = viewFov(p);
            c.minLight = (int) num(p, "min_light", c.minLight, 0, 15);
            c.tracers = bool(p, "tracers", c.tracers);
            c.bulletHoles = bool(p, "bullet_holes", c.bulletHoles);
            c.cameraShake = (float) num(p, "camera_shake", c.cameraShake, 0, 2);
            c.recoil = (float) num(p, "recoil", c.recoil, 0, 3);
            c.volume = (float) num(p, "volume", c.volume, 0, 1);
            c.hud = bool(p, "hud", c.hud);
            c.monoDigits = bool(p, "mono_digits", c.monoDigits);
            c.arms = bool(p, "arms", c.arms);
            c.thirdPerson = bool(p, "third_person", c.thirdPerson);
            c.backGun = bool(p, "back_gun", c.backGun);
            c.scopeSensitivity = (float) num(p, "scope_sensitivity", c.scopeSensitivity, 0.05, 8);
            c.scopeSize = (float) num(p, "scope_size", c.scopeSize, 0.3, 1.5);
            tune(p.getProperty("back_gun_tune"), c.backTune);
            tune(p.getProperty("holster_tune"), c.holsterTune);
        } catch (IOException | RuntimeException e) {
            log.warn("guns: не удалось прочитать {} — значения по умолчанию ({})", file, e.toString());
        }
        return c;
    }

    /**
     * viewmodel_fov: «model» (или нет ключа) — свой у каждого оружия; число — один для всего; 0 — угол мира. В файлах
     * версии 1 стояло 70 по умолчанию (тогда считали, что у TACZ так) — это значение там читается как model.
     */
    static float viewFov(Properties p) {
        String s = p.getProperty("viewmodel_fov");
        if (s == null || s.trim().equalsIgnoreCase("model")) return FOV_MODEL;
        double v;
        try {
            v = Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return FOV_MODEL;
        }
        if (!Double.isFinite(v)) return FOV_MODEL;
        String ver = p.getProperty("config_version", "1").trim();
        if (ver.equals("1") && v == 70) return FOV_MODEL;
        return v < 1 ? 0 : (float) Math.max(30, Math.min(150, v));
    }

    private static void tune(String tune, float[] out) {
        if (tune == null) return;
        String[] parts = tune.trim().split("[,\\s]+");
        for (int i = 0; i < Math.min(parts.length, out.length); i++) {
            try {
                float v = Float.parseFloat(parts[i]);
                if (Float.isFinite(v)) out[i] = v;
            } catch (NumberFormatException ignored) {
                // как было
            }
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String s = p.getProperty(key);
        if (s == null) return def;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("да")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("нет")) return false;
        return def;
    }

    private static double num(Properties p, String key, double def, double lo, double hi) {
        String s = p.getProperty(key);
        if (s == null) return def;
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
