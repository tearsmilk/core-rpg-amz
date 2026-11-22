package ink.hoka.odyssey.client.spaceflight;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Настройки модуля: {@code <dataDir>/config.properties} (создаётся с комментариями при первом запуске). */
final class FlightConfig {
    private static final String DEFAULT = """
            # Hoka Odyssey — модуль spaceflight (невесомость в вакууме). Файл читается при загрузке модуля.

            # Тяга ранца, блоки/с² (W/S — вперёд/назад, A/D — вбок, пробел/shift — вверх/вниз по осям взгляда)
            thrust = 2.0
            # Скорость крена, градусы в секунду
            roll_speed = 90
            # Клавиши крена (имена Minecraft: key.keyboard.z, key.keyboard.q, key.mouse.4 …).
            # По умолчанию Z/C: Q и E в игре уже заняты (выбросить предмет и инвентарь).
            roll_left = key.keyboard.z
            roll_right = key.keyboard.c
            # Сцепление с поверхностью при стоянии на блоке (0 — лёд, 1 — мгновенная остановка), доля за тик
            ground_grip = 0.35
            # Скорость шага по поверхности, блоки/с
            walk_speed = 4.3
            # Скорость прыжка от поверхности, блоки/с
            jump_speed = 5.0
            # Предел скорости (защита от разгона до бесконечности), блоки/с
            max_speed = 120
            # Скорость возврата камеры «в горизонт» после выхода из вакуума, доля за секунду
            upright_rate = 4.0
            """;

    double thrust = 2.0;
    double rollSpeed = 90;
    String rollLeft = "key.keyboard.z";
    String rollRight = "key.keyboard.c";
    double groundGrip = 0.35;
    double walkSpeed = 4.3;
    double jumpSpeed = 5.0;
    double maxSpeed = 120;
    double uprightRate = 4.0;

    static FlightConfig load(Path dir, Logger log) {
        FlightConfig c = new FlightConfig();
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
            c.thrust = num(p, "thrust", c.thrust, 0, 50);
            c.rollSpeed = num(p, "roll_speed", c.rollSpeed, 0, 720);
            c.rollLeft = p.getProperty("roll_left", c.rollLeft).trim();
            c.rollRight = p.getProperty("roll_right", c.rollRight).trim();
            c.groundGrip = num(p, "ground_grip", c.groundGrip, 0, 1);
            c.walkSpeed = num(p, "walk_speed", c.walkSpeed, 0, 20);
            c.jumpSpeed = num(p, "jump_speed", c.jumpSpeed, 0, 30);
            c.maxSpeed = num(p, "max_speed", c.maxSpeed, 1, 1000);
            c.uprightRate = num(p, "upright_rate", c.uprightRate, 0.1, 50);
        } catch (IOException | RuntimeException e) {
            log.warn("spaceflight: не удалось прочитать {} — значения по умолчанию ({})", file, e.toString());
        }
        return c;
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
