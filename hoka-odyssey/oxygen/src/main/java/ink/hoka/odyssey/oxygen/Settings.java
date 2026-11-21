package ink.hoka.odyssey.oxygen;

import ink.hoka.odyssey.api.ModuleConfig;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Настройки из config.yml, уже в единицах тика. Воздух — в единицах ванильной шкалы (0…{@link Lungs#MAX}).
 *
 * @param maps             карты без воздуха
 * @param drainPerTick     на сколько единиц шкалы убывает воздух за тик без скафандра
 * @param refillPerTick    на сколько восстанавливается за тик
 * @param damage           урон, когда воздух кончился (единицы здоровья)
 * @param damageInterval   раз во сколько тиков бьёт урон
 * @param creativeBubbles  показывать ли убывающие пузырьки в креативе
 * @param warning          предупреждение в action bar ("" — нет)
 * @param warningInterval  раз во сколько тиков повторять предупреждение
 * @param suitName         название скафандра
 * @param suitLore         описание скафандра
 */
record Settings(Set<String> maps, double drainPerTick, double refillPerTick, float damage, int damageInterval,
                boolean creativeBubbles, String warning, int warningInterval, String suitName, List<String> suitLore) {

    static final int TPS = 20;

    static Settings from(ModuleConfig c) {
        Set<String> maps = new LinkedHashSet<>(c.getStringList("maps"));
        return new Settings(Collections.unmodifiableSet(maps),
            perTick(c.getDouble("air.supply-seconds", 15)),
            perTick(c.getDouble("air.refill-seconds", 3.75)),
            (float) Math.max(0, c.getDouble("damage.amount", 4.0)),
            ticks(c.getDouble("damage.interval-seconds", 1.0)),
            c.getBoolean("creative-bubbles", false),
            c.getString("warning.text", ""),
            ticks(c.getDouble("warning.interval-seconds", 1.0)),
            c.getString("suit.name", "&bСкафандр"),
            List.copyOf(c.getStringList("suit.lore")));
    }

    /** «Шкала целиком за столько секунд» → единиц шкалы за тик. 0 и меньше — мгновенно. */
    static double perTick(double seconds) {
        double t = seconds * TPS;
        return t <= 1 ? Lungs.MAX : Lungs.MAX / t;
    }

    /** Секунды → тики, не меньше одного. */
    static int ticks(double seconds) {
        return (int) Math.max(1, Math.round(seconds * TPS));
    }
}
