package core.amazake.systems.corevault.papi;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.data.TopCache;
import core.amazake.systems.corevault.level.LevelMath;
import core.amazake.systems.corevault.money.Currency;
import core.amazake.systems.corevault.money.Money;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * %corevault_…%: balance, balance_formatted, balance_short (число без названия), currency_singular/plural,
 * level, exp, exp_needed, exp_total, progress (процент), top_&lt;n&gt;_name / _value / _value_raw,
 * leveltop_&lt;n&gt;_name / _value. Любой поток (PlaceholderAPI может спрашивать и асинхронно): данные — из
 * потокобезопасных счетов и неизменяемого снимка топа.
 */
public final class Placeholders {

    private final Supplier<Settings> settings;
    private final ToLongFunction<UUID> balance;
    private final Function<UUID, LevelMath.State> level;
    private final Supplier<TopCache.Snapshot> top;

    public Placeholders(final Supplier<Settings> settings, final ToLongFunction<UUID> balance,
                        final Function<UUID, LevelMath.State> level, final Supplier<TopCache.Snapshot> top) {
        this.settings = settings;
        this.balance = balance;
        this.level = level;
        this.top = top;
    }

    public String resolve(final UUID player, final String rawParams) {
        if (rawParams == null) {
            return null;
        }
        final String params = rawParams.toLowerCase(Locale.ROOT);
        final Settings s = this.settings.get();
        final Currency cur = s.economy().currency();
        if (params.startsWith("top_") || params.startsWith("leveltop_")) {
            return this.top(params, s, cur);
        }
        switch (params) {
            case "currency_singular":
                return cur.singular();
            case "currency_plural":
                return cur.plural();
            case "currency_genitive":
                return cur.genitive();
            default:
                break;
        }
        if (player == null) {
            return "";
        }
        switch (params) {
            case "balance":
                return s.modules().economy() ? Money.plain(this.balance.applyAsLong(player), cur.decimals()) : "";
            case "balance_formatted":
                return s.modules().economy() ? cur.format(this.balance.applyAsLong(player)) : "";
            case "balance_short":
                return s.modules().economy() ? cur.number(this.balance.applyAsLong(player)) : "";
            default:
                break;
        }
        if (!s.modules().levels()) {
            return switch (params) {
                case "level", "exp", "exp_needed", "exp_total", "progress" -> "";
                default -> null;
            };
        }
        final LevelMath.State st = this.level.apply(player);
        final var curve = s.levels().curve();
        return switch (params) {
            case "level" -> Integer.toString(st.level());
            case "exp" -> Long.toString(st.exp());
            case "exp_needed" -> Long.toString(curve.need(st.level()));
            case "exp_total" -> Long.toString(LevelMath.total(curve, st));
            case "progress" -> Integer.toString((int) Math.floor(LevelMath.progress(curve, st) * 100));
            case "max_level" -> Integer.toString(curve.maxLevel());
            default -> null;
        };
    }

    /** top_3_name, top_3_value, top_3_value_raw, leveltop_1_name, leveltop_1_value. */
    private String top(final String params, final Settings s, final Currency cur) {
        final boolean levels = params.startsWith("leveltop_");
        final String rest = params.substring(levels ? "leveltop_".length() : "top_".length());
        final int us = rest.indexOf('_');
        if (us <= 0) {
            return null;
        }
        final int rank;
        try {
            rank = Integer.parseInt(rest.substring(0, us));
        } catch (final NumberFormatException e) {
            return null;
        }
        final String field = rest.substring(us + 1);
        final TopCache.Snapshot snap = this.top.get();
        final List<TopCache.Entry> list = levels ? snap.levels() : snap.money();
        if (rank < 1 || rank > list.size()) {
            return field.equals("name") ? "—" : "";
        }
        final TopCache.Entry e = list.get(rank - 1);
        return switch (field) {
            case "name" -> e.name();
            case "value" -> levels ? Integer.toString(TopCache.levelOf(e)) : cur.format(e.value());
            case "value_raw" -> levels ? Integer.toString(TopCache.levelOf(e)) : Money.plain(e.value(), cur.decimals());
            default -> null;
        };
    }
}
