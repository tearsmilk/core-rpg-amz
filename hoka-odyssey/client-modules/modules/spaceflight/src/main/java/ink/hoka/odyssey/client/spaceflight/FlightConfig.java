package ink.hoka.odyssey.client.spaceflight;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;

/** Настройки модуля: {@code <dataDir>/config.properties} (создаётся с комментариями при первом запуске). */
final class FlightConfig {
    /** Версия файла настроек: без неё (файл от старой версии) прежние умолчания заменяются новыми. */
    static final int VERSION = 2;

    private static final String DEFAULT = """
            # Hoka Odyssey — модуль spaceflight (невесомость в вакууме). Файл читается при загрузке модуля.
            # Версия файла (не меняйте): по ней модуль узнаёт файл от старой версии.
            config_version = 2

            # Тяга ранца, блоки/с² (W/S — вперёд/назад, A/D — вбок, пробел/shift — вверх/вниз по осям взгляда).
            # Сервер может ограничить её своей (physics.thrust.acceleration) — действует меньшая.
            thrust = 0.8
            # Предел скорости, блоки/с (сервер может ограничить его своим physics.thrust.max-speed)
            max_speed = 20
            # Стабилизатор: без клавиш движения скорость сама гаснет с этим замедлением, блоки/с².
            # 0 — выключен (чистый реализм: летишь по инерции, пока не врежешься; орбиты — только с 0)
            stabilizer = 1.5
            # Тормоз: пока зажата клавиша brake_key, скорость гаснет с этим замедлением, блоки/с² (0 — выкл.)
            brake = 3.0
            # Клавиша тормоза (бег/Ctrl занят: он переключает «лёжа/стоя»)
            brake_key = key.keyboard.x
            # Скорость крена, градусы в секунду
            roll_speed = 60
            # Клавиши крена (имена Minecraft: key.keyboard.z, key.keyboard.q, key.mouse.4 …).
            # По умолчанию Z/C: Q и E в игре уже заняты (выбросить предмет и инвентарь).
            roll_left = key.keyboard.z
            roll_right = key.keyboard.c
            # Сцепление с поверхностью при стоянии на блоке (0 — лёд, 1 — мгновенная остановка), доля за тик
            ground_grip = 0.35
            # Скорость шага по поверхности, блоки/с
            walk_speed = 4.3
            # Скорость прыжка от поверхности, блоки/с (прыжок — только по новому нажатию пробела)
            jump_speed = 5.0
            # Скорость возврата камеры «в горизонт» после выхода из вакуума, доля за секунду
            upright_rate = 4.0

            # «Магнитные ботинки»: Shift у блока (пол, стена, потолок — любой) прижимает к нему, по нему можно ходить
            # (W/A/S/D вдоль поверхности, пробел — оттолкнуться). Без Shift — обычная невесомость.
            # Сила прижима, блоки/с²
            grip_press = 20
            # Отлетаешь от блока быстрее этого (блоки/с) — ботинки не удерживают
            grip_break_speed = 4.0
            # Как быстро камера выравнивается по поверхности (стена/потолок становится «полом»), доля за секунду;
            # 0 — не выравнивать
            surface_align_rate = 2.0
            """;

    /** Прежние умолчания (файл без config_version): такие значения считаем «не менял» и берём новые. */
    private static final Map<String, String> OLD_DEFAULTS = Map.of("thrust", "2.0", "roll_speed", "90", "max_speed", "120");

    double thrust = 0.8;
    double maxSpeed = 20;
    double stabilizer = 1.5;
    double brake = 3.0;
    String brakeKey = "key.keyboard.x";
    double rollSpeed = 60;
    String rollLeft = "key.keyboard.z";
    String rollRight = "key.keyboard.c";
    double groundGrip = 0.35;
    double walkSpeed = 4.3;
    double jumpSpeed = 5.0;
    double uprightRate = 4.0;
    double gripPress = 20;
    double gripBreakSpeed = 4.0;
    double surfaceAlignRate = 2.0;

    static FlightConfig load(Path dir, Logger log) {
        FlightConfig c = new FlightConfig();
        Path file = dir.resolve("config.properties");
        try {
            Files.createDirectories(dir);
            if (!Files.isRegularFile(file)) {
                Files.writeString(file, DEFAULT, StandardCharsets.UTF_8);
            }
            Properties p = read(file);
            if (num(p, "config_version", 1, 0, 1000) < VERSION) {
                String text = upgrade(p);
                Files.copy(file, dir.resolve("config.properties.old"), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(file, text, StandardCharsets.UTF_8);
                log.info("spaceflight: config.properties обновлён до версии {} (новые значения по умолчанию; свои "
                        + "значения сохранены, старый файл — config.properties.old)", VERSION);
                p = read(file);
            }
            c.thrust = num(p, "thrust", c.thrust, 0, 50);
            c.maxSpeed = num(p, "max_speed", c.maxSpeed, 1, 1000);
            c.stabilizer = num(p, "stabilizer", c.stabilizer, 0, 50);
            c.brake = num(p, "brake", c.brake, 0, 50);
            c.brakeKey = p.getProperty("brake_key", c.brakeKey).trim();
            c.rollSpeed = num(p, "roll_speed", c.rollSpeed, 0, 720);
            c.rollLeft = p.getProperty("roll_left", c.rollLeft).trim();
            c.rollRight = p.getProperty("roll_right", c.rollRight).trim();
            c.groundGrip = num(p, "ground_grip", c.groundGrip, 0, 1);
            c.walkSpeed = num(p, "walk_speed", c.walkSpeed, 0, 20);
            c.jumpSpeed = num(p, "jump_speed", c.jumpSpeed, 0, 30);
            c.uprightRate = num(p, "upright_rate", c.uprightRate, 0.1, 50);
            c.gripPress = num(p, "grip_press", c.gripPress, 0, 200);
            c.gripBreakSpeed = num(p, "grip_break_speed", c.gripBreakSpeed, 0, 30);
            c.surfaceAlignRate = num(p, "surface_align_rate", c.surfaceAlignRate, 0, 50);
        } catch (IOException | RuntimeException e) {
            log.warn("spaceflight: не удалось прочитать {} — значения по умолчанию ({})", file, e.toString());
        }
        return c;
    }

    private static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    /**
     * Новый файл из старого: текст DEFAULT, но с теми значениями старого файла, которые игрок менял (отличаются от
     * прежних умолчаний). Значения, равные прежним умолчаниям, заменяются новыми.
     */
    static String upgrade(Properties old) {
        StringBuilder out = new StringBuilder();
        for (String line : DEFAULT.split("\n", -1)) {
            int eq = line.indexOf(" = ");
            String key = eq > 0 && !line.startsWith("#") ? line.substring(0, eq).trim() : null;
            String was = key == null || key.equals("config_version") ? null : old.getProperty(key);
            if (was != null && !was.trim().equals(OLD_DEFAULTS.get(key)) && !same(was, OLD_DEFAULTS.get(key))) {
                line = key + " = " + was.trim();
            }
            out.append(line).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    /** Одно и то же число («2» и «2.0»). */
    private static boolean same(String a, String b) {
        if (b == null) return false;
        try {
            return Double.parseDouble(a.trim()) == Double.parseDouble(b.trim());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double num(Properties p, String key, double def, double lo, double hi) {
        String s = p.getProperty(key);
        if (s == null) return def;
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? FreeLook.clamp(v, lo, hi) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
