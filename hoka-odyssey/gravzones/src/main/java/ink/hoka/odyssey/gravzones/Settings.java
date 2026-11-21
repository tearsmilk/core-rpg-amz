package ink.hoka.odyssey.gravzones;

import ink.hoka.odyssey.api.ModuleConfig;

/**
 * Настройки из config.yml.
 *
 * @param weakExponent      показатель для силы прыжка при g &lt; 1 (jump_strength × g^e)
 * @param strongExponent    то же при g &gt; 1
 * @param scaleSafeFall     увеличивать safe_fall_distance, если прыжок в зоне выше обычного
 * @param fallDamage        множитель урона от падения в зонах 0.1…10 (1 — как обычно)
 * @param items             гравитация зон и для выброшенных предметов
 * @param launch11          скорость выброса в зоне 11, блоков/сек
 * @param launch12          … 12
 * @param launch13          … 13
 * @param lingerTicks       сколько тиков после выхода из вакуумной зоны держится невесомость
 * @param jumpLaunch        прыжок с земли в вакуумной зоне снова выбрасывает
 * @param jumpCooldownTicks не чаще раза в столько тиков
 * @param particlePeriod    раз во сколько тиков рисовать рамки (у игрока с топориком)
 * @param particleMax       не больше стольких частиц за раз на игрока
 * @param particleRadius    рисовать рёбра ближе этого (блоков)
 * @param particleStep      шаг частиц на ребре не меньше этого (блоков)
 * @param particleSize      размер частицы dust
 */
record Settings(double weakExponent, double strongExponent, boolean scaleSafeFall, double fallDamage, boolean items,
                double launch11, double launch12, double launch13, int lingerTicks, boolean jumpLaunch,
                int jumpCooldownTicks, int particlePeriod, int particleMax, double particleRadius, double particleStep,
                float particleSize) {

    static Settings from(ModuleConfig c) {
        return new Settings(
            clamp(c.getDouble("jump.weak-exponent", 0.1), 0, 1),
            clamp(c.getDouble("jump.strong-exponent", 0.3), 0, 1),
            c.getBoolean("fall.scale-safe-distance", true),
            clamp(c.getDouble("fall.damage-multiplier", 1.0), 0, 10),
            c.getBoolean("items", true),
            clamp(c.getDouble("vacuum.launch-11", 12.0), 0, 80),
            clamp(c.getDouble("vacuum.launch-12", 18.0), 0, 80),
            clamp(c.getDouble("vacuum.launch-13", 24.0), 0, 80),
            (int) Math.round(clamp(c.getDouble("vacuum.linger-seconds", 3.0), 0, 600) * Physics.TPS),
            c.getBoolean("vacuum.jump-launch", true),
            (int) Math.round(clamp(c.getDouble("vacuum.jump-cooldown-seconds", 1.5), 0, 60) * Physics.TPS),
            (int) clamp(c.getInt("particles.period-ticks", 10), 1, 200),
            (int) clamp(c.getInt("particles.max-per-player", 240), 0, 5000),
            clamp(c.getDouble("particles.radius", 40), 4, 256),
            clamp(c.getDouble("particles.min-step", 0.5), 0.1, 16),
            (float) clamp(c.getDouble("particles.size", 1.0), 0.1, 4));
    }

    double launch(double g) {
        return Physics.launchSpeed(g, launch11, launch12, launch13);
    }

    private static double clamp(double v, double min, double max) {
        return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
    }
}
