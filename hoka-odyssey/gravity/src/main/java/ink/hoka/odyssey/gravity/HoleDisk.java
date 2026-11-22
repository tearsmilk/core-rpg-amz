package ink.hoka.odyssey.gravity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Как дыра выглядит в небе (рисует модуль spacesky шейдером неба; на физику не влияет). Поля дыры в config.yml —
 * необязательные, пропущенное — по умолчанию ({@link #DEFAULT}).
 *
 * @param tilt       наклон диска к горизонтали, градусы (−90…90; 0 — диск горизонтальный, 90 — стоит ребром вверх)
 * @param yaw        в какую сторону опущен край диска, градусы по горизонту (0 — к востоку, +x; 90 — к югу, +z)
 * @param size       внешний радиус диска в горизонтах (0 — без диска, иначе 1,5…12)
 * @param spin       скорость вращения диска (1 — обычно, 0 — стоит, минус — в другую сторону; −5…5)
 * @param brightness яркость диска и кольца (1 — обычно; 0…5)
 * @param temp       «температура»: меньше 1 — краснее, больше — белее и голубее (0,2…3)
 */
record HoleDisk(double tilt, double yaw, double size, double spin, double brightness, double temp) {
    static final HoleDisk DEFAULT = new HoleDisk(12, 0, 6, 1, 1, 1);

    HoleDisk {
        // не через DEFAULT: он сам создаётся этим конструктором
        tilt = clamp(tilt, -90, 90, 12);
        yaw = Double.isFinite(yaw) ? yaw : 0;
        size = Double.isFinite(size) && size > 0 ? clamp(size, 1.5, 12, 6) : 0;
        spin = clamp(spin, -5, 5, 1);
        brightness = clamp(brightness, 0, 5, 1);
        temp = clamp(temp, 0.2, 3, 1);
    }

    /** Из записи дыры в config.yml (ключи disk-tilt, disk-yaw, disk-size, disk-spin, disk-brightness, disk-temp). */
    static HoleDisk read(Map<?, ?> h) {
        HoleDisk d = DEFAULT;
        return new HoleDisk(num(h, "disk-tilt", d.tilt), num(h, "disk-yaw", d.yaw), num(h, "disk-size", d.size),
            num(h, "disk-spin", d.spin), num(h, "disk-brightness", d.brightness), num(h, "disk-temp", d.temp));
    }

    /** Поля для строки дыры в config.yml — только отличные от умолчания (", disk-tilt: 30, …" или ""). */
    String yaml() {
        List<String> out = new ArrayList<>();
        add(out, "disk-tilt", tilt, DEFAULT.tilt);
        add(out, "disk-yaw", yaw, DEFAULT.yaw);
        add(out, "disk-size", size, DEFAULT.size);
        add(out, "disk-spin", spin, DEFAULT.spin);
        add(out, "disk-brightness", brightness, DEFAULT.brightness);
        add(out, "disk-temp", temp, DEFAULT.temp);
        return out.isEmpty() ? "" : ", " + String.join(", ", out);
    }

    private static void add(List<String> out, String key, double v, double def) {
        if (!HoleYaml.num(v).equals(HoleYaml.num(def))) {
            out.add(key + ": " + HoleYaml.num(v));
        }
    }

    private static double num(Map<?, ?> m, String key, double def) {
        return m.get(key) instanceof Number n ? n.doubleValue() : def;
    }

    private static double clamp(double v, double lo, double hi, double def) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : def;
    }
}
