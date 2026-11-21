package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.api.ModuleConfig;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Настройки из config.yml, уже в единицах тика (блоки/тик, блоки/тик²) — как их считает клиент.
 *
 * @param maps         карты с вакуумом
 * @param vThrust      разгон вверх/вниз за тик
 * @param vMax         предел скорости вверх/вниз за тик
 * @param vKeep        какая доля вертикальной скорости остаётся за тик без клавиш
 * @param hThrust      добавочный разгон WASD за тик
 * @param hMax         выше этой скорости (блоков/тик) добавочный разгон не даётся
 * @param hKeep        какая доля горизонтальной скорости остаётся за тик в воздухе без клавиш (0 — не трогать)
 * @param fallDamage   урон от падения в вакууме
 * @param groundJump   обычный прыжок с земли
 * @param enterHint    подсказка в action bar при входе на карту ("" — нет)
 */
record Settings(Set<String> maps, double vThrust, double vMax, double vKeep, double hThrust, double hMax, double hKeep,
                boolean fallDamage, boolean groundJump, String enterHint) {

    static final int TPS = 20;

    static Settings from(ModuleConfig c) {
        Set<String> maps = new LinkedHashSet<>(c.getStringList("maps"));
        return new Settings(Collections.unmodifiableSet(maps),
            perTick2(c.getDouble("vertical.thrust", 8.0)),
            perTick(c.getDouble("vertical.max-speed", 4.0)),
            Motion.keepPerTick(c.getDouble("vertical.stop-half-life", 0.35)),
            perTick2(c.getDouble("horizontal.thrust", 2.4)),
            perTick(c.getDouble("horizontal.max-speed", 7.0)),
            Motion.keepPerTick(c.getDouble("horizontal.glide-half-life", 0.8)),
            c.getBoolean("fall-damage", false),
            c.getBoolean("ground-jump", false),
            c.getString("enter-hint", ""));
    }

    /** блоков/сек → блоков/тик. */
    static double perTick(double perSecond) {
        return Math.max(0, perSecond) / TPS;
    }

    /** блоков/сек² → блоков/тик². */
    static double perTick2(double perSecond2) {
        return Math.max(0, perSecond2) / (TPS * TPS);
    }
}
