package core.amazake.systems.corevault.coin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Бонус к поднятым монетам из стата MythicLib {@code CUSTOM_COIN_GAIN} (в процентах, 0–100): например, 25 —
 * поднял монет на 100, на баланс пришло 125. Стат дают предметы/классы MMOItems/MMOCore через MythicLib.
 *
 * <p>MythicLib — плагин, а загрузчик системы видит классы сервера, но не плагинов, поэтому
 * {@code MMOPlayerData.get(player).getStatMap().getStat("CUSTOM_COIN_GAIN")} вызывается отражением через загрузчик
 * MythicLib. Нет MythicLib, нет данных игрока или вызов не удался — бонуса нет (0), а предупреждение в лог пишется
 * один раз за работу системы.
 */
public final class CoinBonus {

    /** Имя стата MythicLib. */
    public static final String STAT = "CUSTOM_COIN_GAIN";
    /** Потолок бонуса, %: больше стат не даст, даже если на предметах набрано больше. */
    public static final double MAX_PERCENT = 100.0;

    /** Найденные методы MythicLib; пересоздаются, если MythicLib перезагрузили (другой экземпляр плагина). */
    private static volatile Api api;
    /** Предупреждение о сбое чтения стата уже было. */
    private static volatile boolean warned;

    private CoinBonus() {
    }

    /**
     * Сумма с бонусом: {@code amount + amount * percent / 100}, бонус округляется до целых минорных единиц
     * (половина — вверх). Нулевая/отрицательная сумма, нулевой бонус или переполнение {@code long} — сумма как есть.
     */
    public static long apply(final Player p, final long amount, final Logger log) {
        if (amount <= 0) {
            return amount;
        }
        final double percent = percent(p, log);
        if (percent <= 0) {
            return amount;
        }
        final BigDecimal bonus = BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(percent))
                .movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP);
        final long extra = bonus.longValue();
        return extra > 0 && Long.MAX_VALUE - amount > extra ? amount + extra : amount;
    }

    /** Значение стата {@code CUSTOM_COIN_GAIN} игрока, обрезанное до 0–{@link #MAX_PERCENT}; при любой ошибке — 0. */
    public static double percent(final Player p, final Logger log) {
        try {
            final Api a = api();
            if (a == null) {
                return 0;
            }
            final Object data = a.get().invoke(null, p);
            if (data == null) {
                return 0;
            }
            final Object stats = a.statMap().invoke(data);
            final double v = ((Number) a.getStat().invoke(stats, STAT)).doubleValue();
            return Double.isFinite(v) ? Math.max(0, Math.min(MAX_PERCENT, v)) : 0;
        } catch (final ReflectiveOperationException | RuntimeException ex) {
            if (!warned) {
                warned = true;
                log.warning("CUSTOM_COIN_GAIN не прочитан (MythicLib): " + ex + " — бонус к монетам 0.");
            }
            return 0;
        }
    }

    /** Методы MythicLib для текущего экземпляра плагина; {@code null} — MythicLib нет или он выключен. */
    private static Api api() throws ReflectiveOperationException {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicLib");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        Api a = api;
        if (a != null && a.plugin() == plugin) {
            return a;
        }
        final ClassLoader loader = plugin.getClass().getClassLoader();
        final Class<?> data = Class.forName("io.lumine.mythic.lib.api.player.MMOPlayerData", true, loader);
        final Method get = data.getMethod("get", OfflinePlayer.class);
        final Method statMap = data.getMethod("getStatMap");
        final Method getStat = statMap.getReturnType().getMethod("getStat", String.class);
        a = new Api(plugin, get, statMap, getStat);
        api = a;
        return a;
    }

    /** {@code MMOPlayerData.get(OfflinePlayer)}, {@code MMOPlayerData#getStatMap()}, {@code StatMap#getStat(String)}. */
    private record Api(Plugin plugin, Method get, Method statMap, Method getStat) {
    }
}
