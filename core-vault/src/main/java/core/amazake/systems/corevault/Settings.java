package core.amazake.systems.corevault;

import core.amazake.systems.corevault.coin.DropRule;
import core.amazake.systems.corevault.coin.MobTable;
import core.amazake.systems.corevault.level.Expr;
import core.amazake.systems.corevault.level.LevelCurve;
import core.amazake.systems.corevault.money.Currency;
import core.amazake.systems.corevault.money.Money;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** Неизменяемый снимок config.yml. Ошибки значений — предупреждение и значение по умолчанию. */
public record Settings(String prefix, Modules modules, Eco economy, Levels levels, Coins coins, int saveIntervalSeconds,
                       Map<String, String> messages) {

    public record Modules(boolean economy, boolean levels, boolean coins) {
        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append("economy ").append(this.economy ? "вкл" : "выкл");
            sb.append(", levels ").append(this.levels ? "вкл" : "выкл");
            sb.append(", coins ").append(this.coins ? (this.economy ? "вкл" : "вкл (не работает без economy)") : "выкл");
            return sb.toString();
        }
    }

    public enum Backend { AUTO, INTERNAL, VAULT }

    /** Суммы — в минорных единицах валюты. */
    public record Eco(Backend backend, Currency currency, long startBalance, long maxBalance, long payMin,
                      long payConfirmAbove, int payConfirmSeconds, boolean payOffline, int topSize, int topRefreshSeconds,
                      int topPageSize, boolean vaultRegister, String vaultPriority) {
    }

    /** Опыт за мобов: [min, max]. */
    public record Levels(LevelCurve curve, boolean vanillaBar, String upMessage, String upTitle, String upSubtitle,
                         String upSound, float upVolume, float upPitch, List<String> upCommands,
                         Map<Integer, List<String>> rewards, boolean actionbar, String actionbarFormat, boolean bossbar,
                         int bossbarSeconds, String bossbarColor, String bossbarTitle, MobTable<int[]> mobExp,
                         double spawnerMultiplier, boolean orbs, double orbRatio, boolean orbCancel) {
    }

    public record Coins(long value, String material, String itemModel, String name, List<String> lore, boolean glint,
                        boolean glow, int despawnSeconds, int pickupDelayTicks, boolean hoppers, boolean mobsPickup,
                        String sound, float volume, float pitch, String actionbar, boolean convertJoin,
                        boolean convertClick, boolean requirePlayerKill, boolean fromSpawners, MobTable<DropRule> drops) {
    }

    /** Сообщения раздела messages: из конфига (для разбора встроенного config.yml). */
    public static Map<String, String> messagesOf(final ConfigurationSection y) {
        final Map<String, String> out = new HashMap<>();
        final ConfigurationSection ms = y.getConfigurationSection("messages");
        if (ms != null) {
            for (final String k : ms.getKeys(false)) {
                out.put(k, ms.getString(k, ""));
            }
        }
        return out;
    }

    public String message(final String key) {
        return this.messages.get(key);
    }

    /** Разбор config.yml без встроенных сообщений (тесты). */
    public static Settings from(final ConfigurationSection y, final Consumer<String> warn) {
        return from(y, Map.of(), warn);
    }

    /**
     * Разбор config.yml.
     *
     * @param defaultMessages сообщения встроенного config.yml: ключи, которых нет в файле на сервере, берутся отсюда
     */
    public static Settings from(final ConfigurationSection y, final Map<String, String> defaultMessages,
                                final Consumer<String> warn) {
        final String prefix = y.getString("prefix", "");
        final Modules modules = new Modules(y.getBoolean("modules.economy", true), y.getBoolean("modules.levels", true),
            y.getBoolean("modules.coins", true));

        // ---- экономика
        Backend backend = Backend.AUTO;
        final String b = y.getString("economy.backend", "auto").trim().toUpperCase(Locale.ROOT);
        try {
            backend = Backend.valueOf(b);
        } catch (final IllegalArgumentException e) {
            warn.accept("economy.backend: «" + b + "» — нужно auto, internal или vault; беру auto");
        }
        int decimals = y.getInt("economy.currency.decimals", 2);
        if (decimals < 0 || decimals > 4) {
            warn.accept("economy.currency.decimals: " + decimals + " — нужно 0..4; беру 2");
            decimals = 2;
        }
        final Currency cur = new Currency(decimals, y.getString("economy.currency.singular", "монета"),
            y.getString("economy.currency.genitive", "монеты"), y.getString("economy.currency.plural", "монет"),
            y.getString("economy.currency.group-separator", " "), y.getString("economy.currency.decimal-separator", ","),
            y.getBoolean("economy.currency.trim-zeros", true), y.getString("economy.currency.format", "{amount} {name}"));
        final long max = amount(y, "economy.max-balance", decimals, Money.pow10(decimals) * 1_000_000_000L, warn);
        final long start = Math.min(max, amount(y, "economy.start-balance", decimals, 0, warn));
        final long payMin = amount(y, "economy.pay.min", decimals, Money.pow10(decimals), warn);
        final long confirm = amount(y, "economy.pay.confirm-above", decimals, 0, warn);
        final String prio = y.getString("economy.vault.priority", "High");
        String priority = "High";
        for (final String p : List.of("Lowest", "Low", "Normal", "High", "Highest")) {
            if (p.equalsIgnoreCase(prio.trim())) {
                priority = p;
            }
        }
        if (!priority.equalsIgnoreCase(prio.trim())) {
            warn.accept("economy.vault.priority: «" + prio + "» — нужно Lowest/Low/Normal/High/Highest; беру High");
        }
        final Eco eco = new Eco(backend, cur, start, max, payMin, confirm,
            clamp(y, "economy.pay.confirm-seconds", 30, 5, 600, warn), y.getBoolean("economy.pay.offline", true),
            clamp(y, "economy.top.size", 100, 1, 1000, warn), clamp(y, "economy.top.refresh-seconds", 60, 5, 86400, warn),
            clamp(y, "economy.top.page-size", 10, 1, 50, warn), y.getBoolean("economy.vault.register", true), priority);

        // ---- уровни
        final int startLevel = clamp(y, "levels.start-level", 1, 0, 1000, warn);
        int maxLevel = clamp(y, "levels.max-level", 100, 1, 100_000, warn);
        if (maxLevel < startLevel) {
            warn.accept("levels.max-level меньше start-level — беру " + startLevel);
            maxLevel = startLevel;
        }
        Expr formula;
        final String ftext = y.getString("levels.curve.formula", "100 * level^1.5");
        try {
            formula = Expr.parse(ftext);
            formula.eval(startLevel);
        } catch (final IllegalArgumentException e) {
            warn.accept("levels.curve.formula «" + ftext + "»: " + e.getMessage() + "; беру 100 * level^1.5");
            formula = Expr.parse("100 * level^1.5");
        }
        final Map<Integer, Long> table = new TreeMap<>();
        final ConfigurationSection ts = y.getConfigurationSection("levels.curve.table");
        if (ts != null) {
            for (final String k : ts.getKeys(false)) {
                try {
                    final long v = Long.parseLong(String.valueOf(ts.get(k)).trim());
                    table.put(Integer.parseInt(k.trim()), Math.max(1, v));
                } catch (final NumberFormatException e) {
                    warn.accept("levels.curve.table." + k + ": нужно «уровень: опыт» целыми числами");
                }
            }
        }
        final Map<Integer, List<String>> rewards = new TreeMap<>();
        final ConfigurationSection rs = y.getConfigurationSection("levels.rewards");
        if (rs != null) {
            for (final String k : rs.getKeys(false)) {
                try {
                    rewards.put(Integer.parseInt(k.trim()), List.copyOf(rs.getStringList(k)));
                } catch (final NumberFormatException e) {
                    warn.accept("levels.rewards." + k + ": ключ — номер уровня");
                }
            }
        }
        final MobTable<int[]> mobExp = mobTable(y, "levels.sources.mobs", warn, (path, v) -> DropRule.parseRange(String.valueOf(v)));
        final Levels levels = new Levels(new LevelCurve(formula, table, startLevel, maxLevel),
            y.getBoolean("levels.vanilla-bar", false), y.getString("levels.level-up.message", ""),
            y.getString("levels.level-up.title", ""), y.getString("levels.level-up.subtitle", ""),
            y.getString("levels.level-up.sound", ""), (float) y.getDouble("levels.level-up.volume", 1),
            (float) y.getDouble("levels.level-up.pitch", 1), List.copyOf(y.getStringList("levels.level-up.commands")),
            Map.copyOf(rewards), y.getBoolean("levels.display.actionbar", true),
            y.getString("levels.display.actionbar-format", ""), y.getBoolean("levels.display.bossbar.enabled", false),
            clamp(y, "levels.display.bossbar.seconds", 4, 1, 120, warn),
            y.getString("levels.display.bossbar.color", "GREEN").toUpperCase(Locale.ROOT),
            y.getString("levels.display.bossbar.title", ""), mobExp,
            Math.max(0, y.getDouble("levels.sources.spawner-multiplier", 0.5)),
            y.getBoolean("levels.sources.vanilla-orbs.enabled", false),
            Math.max(0, y.getDouble("levels.sources.vanilla-orbs.ratio", 1.0)),
            y.getBoolean("levels.sources.vanilla-orbs.cancel-vanilla", false));

        // ---- монеты
        long coinValue = amount(y, "coins.value", decimals, Money.pow10(decimals), warn);
        if (coinValue <= 0) {
            warn.accept("coins.value должна быть больше нуля — беру 1");
            coinValue = Money.pow10(decimals);
        }
        final long cv = coinValue;
        final int dec = decimals;
        final MobTable<DropRule> drops = mobTable(y, "coins.drops", warn, (path, v) -> dropRule(v, dec, path));
        final Coins coins = new Coins(cv, y.getString("coins.item.material", "SUNFLOWER"),
            y.getString("coins.item.item-model", ""), y.getString("coins.item.name", "<gold>Монета</gold>"),
            List.copyOf(y.getStringList("coins.item.lore")), y.getBoolean("coins.item.glint", false),
            y.getBoolean("coins.entity.glow", false), clamp(y, "coins.entity.despawn-seconds", 300, 0, 86400, warn),
            clamp(y, "coins.entity.pickup-delay-ticks", 10, 0, 1200, warn), y.getBoolean("coins.hoppers-pickup", false),
            y.getBoolean("coins.mobs-pickup", false), y.getString("coins.pickup.sound", ""),
            (float) y.getDouble("coins.pickup.volume", 0.6), (float) y.getDouble("coins.pickup.pitch", 1.4),
            y.getString("coins.pickup.actionbar", ""), y.getBoolean("coins.convert.on-join", true),
            y.getBoolean("coins.convert.on-click", true), y.getBoolean("coins.drops.require-player-kill", true),
            y.getBoolean("coins.drops.from-spawners", false), drops);

        final Map<String, String> messages = new HashMap<>(defaultMessages);
        final ConfigurationSection ms = y.getConfigurationSection("messages");
        if (ms != null) {
            for (final String k : ms.getKeys(false)) {
                messages.put(k, ms.getString(k, ""));
            }
        }
        return new Settings(prefix, modules, eco, levels, coins, clamp(y, "storage.save-interval-seconds", 10, 1, 3600, warn),
            Map.copyOf(messages));
    }

    @FunctionalInterface
    private interface ValueParser<T> {
        T parse(String path, Object value);
    }

    private static <T> MobTable<T> mobTable(final ConfigurationSection y, final String base, final Consumer<String> warn,
                                            final ValueParser<T> parser) {
        final Map<String, T> core = new LinkedHashMap<>();
        final Map<String, T> types = new LinkedHashMap<>();
        fill(y.getConfigurationSection(base + ".core-mobs"), base + ".core-mobs", core, warn, parser, false);
        fill(y.getConfigurationSection(base + ".types"), base + ".types", types, warn, parser, true);
        final T hostile = one(y, base + ".hostile", warn, parser);
        final T fallback = one(y, base + ".default", warn, parser);
        return new MobTable<>(core, types, hostile, fallback);
    }

    private static <T> void fill(final ConfigurationSection s, final String path, final Map<String, T> out,
                                 final Consumer<String> warn, final ValueParser<T> parser, final boolean upper) {
        if (s == null) {
            return;
        }
        for (final String k : s.getKeys(false)) {
            final Object v = s.isConfigurationSection(k) ? s.getConfigurationSection(k) : s.get(k);
            try {
                out.put(upper ? k.toUpperCase(Locale.ROOT) : k.toLowerCase(Locale.ROOT), parser.parse(path + "." + k, v));
            } catch (final IllegalArgumentException e) {
                warn.accept(path + "." + k + ": " + e.getMessage());
            }
        }
    }

    private static <T> T one(final ConfigurationSection y, final String path, final Consumer<String> warn,
                             final ValueParser<T> parser) {
        if (!y.contains(path)) {
            return null;
        }
        final Object v = y.isConfigurationSection(path) ? y.getConfigurationSection(path) : y.get(path);
        if (v == null || "none".equalsIgnoreCase(String.valueOf(v).trim())) {
            return null;
        }
        try {
            return parser.parse(path, v);
        } catch (final IllegalArgumentException e) {
            warn.accept(path + ": " + e.getMessage());
            return null;
        }
    }

    /** {chance: 50, amount: 1-2, value: 5} → правило. */
    static DropRule dropRule(final Object v, final int decimals, final String path) {
        if (!(v instanceof ConfigurationSection s)) {
            if (v != null && "none".equalsIgnoreCase(String.valueOf(v).trim())) {
                return DropRule.NONE;
            }
            throw new IllegalArgumentException("нужно {chance: 50, amount: 1-2}");
        }
        final double chance = s.getDouble("chance", 100);
        if (chance < 0 || chance > 100) {
            throw new IllegalArgumentException("chance 0..100");
        }
        final int[] range = DropRule.parseRange(String.valueOf(s.get("amount", "1")));
        long value = 0;
        if (s.contains("value")) {
            try {
                value = Money.parse(String.valueOf(s.get("value")), decimals, false);
            } catch (final Money.ParseException e) {
                throw new IllegalArgumentException("value: " + e.getMessage(), e);
            }
        }
        return new DropRule(chance, range[0], range[1], value);
    }

    private static long amount(final ConfigurationSection y, final String path, final int decimals, final long def,
                               final Consumer<String> warn) {
        if (!y.contains(path)) {
            return def;
        }
        try {
            return Money.parse(String.valueOf(y.get(path)), decimals, true);
        } catch (final Money.ParseException e) {
            warn.accept(path + ": " + e.getMessage());
            return def;
        }
    }

    private static int clamp(final ConfigurationSection y, final String path, final int def, final int min, final int max,
                             final Consumer<String> warn) {
        final int v = y.getInt(path, def);
        if (v < min || v > max) {
            warn.accept(path + ": " + v + " — нужно " + min + ".." + max + "; беру " + def);
            return def;
        }
        return v;
    }
}
