package ink.hoka.odyssey.client.starmap;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Настройки модуля: {@code <dataDir>/config.properties} (создаётся с комментариями при первом запуске). */
final class MapConfig {
    private static final String DEFAULT = """
            # Hoka Odyssey — модуль starmap (карта-радар). Файл читается при загрузке модуля (вход на сервер,
            # /hoclient reload). Клавиши — имена Minecraft: key.keyboard.m, key.keyboard.tab, key.mouse.4 …
            config_version = 1

            # Открыть / закрыть карту (не срабатывает, пока открыт чат или другое окно)
            open_key = key.keyboard.m
            # Вернуть карту к себе (после перетаскивания мышью)
            recenter_key = key.keyboard.r

            # Радиус обзора при открытии, блоки (колёсико мыши — от 32 до 1024)
            default_range = 256
            # Рисовать блоки вокруг (вид сверху: самый верхний блок столбца, цвет — по высоте относительно тебя)
            terrain = true
            # Сколько миллисекунд за кадр можно тратить на просмотр прогруженных чанков (больше — карта набирается
            # быстрее, но может проседать FPS)
            scan_budget_ms = 3
            # Оборот развёртки радара, секунды
            sweep_seconds = 4
            # Лёгкое мерцание люминофора (false — ровная картинка)
            flicker = true
            # Моноширинный шрифт у цифр (false — обычный шрифт игры)
            mono_digits = true
            # Если сервер не прислал игроков (нет серверного модуля starmap) — показывать тех, кого видит сам клиент
            client_players = true

            # Метки точек на экране без карты: ромбик и расстояние вверху экрана, когда точка впереди
            hud_waypoints = false
            # …только точки ближе этого расстояния, блоки
            hud_waypoints_distance = 5000
            """;

    String openKey = "key.keyboard.m";
    String recenterKey = "key.keyboard.r";
    double defaultRange = 256;
    boolean terrain = true;
    double scanBudgetMs = 3;
    double sweepSeconds = 4;
    boolean flicker = true;
    boolean monoDigits = true;
    boolean clientPlayers = true;
    boolean hudWaypoints = false;
    double hudWaypointsDistance = 5000;

    static MapConfig load(Path dir, Logger log) {
        MapConfig c = new MapConfig();
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
            c.openKey = p.getProperty("open_key", c.openKey).trim();
            c.recenterKey = p.getProperty("recenter_key", c.recenterKey).trim();
            c.defaultRange = num(p, "default_range", c.defaultRange, RadarScreen.MIN_RANGE, RadarScreen.MAX_RANGE);
            c.terrain = bool(p, "terrain", c.terrain);
            c.scanBudgetMs = num(p, "scan_budget_ms", c.scanBudgetMs, 0.5, 20);
            c.sweepSeconds = num(p, "sweep_seconds", c.sweepSeconds, 1, 30);
            c.flicker = bool(p, "flicker", c.flicker);
            c.monoDigits = bool(p, "mono_digits", c.monoDigits);
            c.clientPlayers = bool(p, "client_players", c.clientPlayers);
            c.hudWaypoints = bool(p, "hud_waypoints", c.hudWaypoints);
            c.hudWaypointsDistance = num(p, "hud_waypoints_distance", c.hudWaypointsDistance, 16, 1_000_000);
        } catch (IOException | RuntimeException e) {
            log.warn("starmap: не удалось прочитать {} — значения по умолчанию ({})", file, e.toString());
        }
        return c;
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String s = p.getProperty(key);
        if (s == null) return def;
        s = s.trim().toLowerCase(java.util.Locale.ROOT);
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
