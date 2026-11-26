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
            config_version = 1

            # Выстрел — кнопка «атаковать» игры (ЛКМ; зажать — очередь в режиме «авто»), прицел — «использовать» (ПКМ).
            # Перезарядка (работает, только пока в руке оружие — иначе R остаётся у spaceflight: «лечь» в невесомости)
            reload = key.keyboard.r
            # Режим огня: очередь / одиночный
            firemode = key.keyboard.v

            # Размер оружия в руке (1 — как в TACZ по пикселям модели; меньше — меньше «влезает» в стены)
            viewmodel_scale = 0.3
            # Наименьшая яркость оружия в руке, 0…15 (в космосе темно — иначе его не видно)
            min_light = 8
            # Трассеры пуль и вспышка у ствола
            tracers = true
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
            """;

    String reloadKey = "key.keyboard.r";
    String fireModeKey = "key.keyboard.v";
    float viewmodelScale = 0.3f;
    int minLight = 8;
    boolean tracers = true;
    float cameraShake = 0.5f;
    float recoil = 1f;
    float volume = 1f;
    boolean hud = true;
    boolean monoDigits = true;

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
            c.viewmodelScale = (float) num(p, "viewmodel_scale", c.viewmodelScale, 0.05, 2);
            c.minLight = (int) num(p, "min_light", c.minLight, 0, 15);
            c.tracers = bool(p, "tracers", c.tracers);
            c.cameraShake = (float) num(p, "camera_shake", c.cameraShake, 0, 2);
            c.recoil = (float) num(p, "recoil", c.recoil, 0, 3);
            c.volume = (float) num(p, "volume", c.volume, 0, 1);
            c.hud = bool(p, "hud", c.hud);
            c.monoDigits = bool(p, "mono_digits", c.monoDigits);
        } catch (IOException | RuntimeException e) {
            log.warn("guns: не удалось прочитать {} — значения по умолчанию ({})", file, e.toString());
        }
        return c;
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
