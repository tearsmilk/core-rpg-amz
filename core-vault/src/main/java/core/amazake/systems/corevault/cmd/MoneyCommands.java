package core.amazake.systems.corevault.cmd;

import core.amazake.systems.corevault.Text;
import core.amazake.systems.corevault.data.TopCache;
import core.amazake.systems.corevault.eco.Ledger;
import core.amazake.systems.corevault.money.Money;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Команды игроков: баланс, перевод, топ. Имена выбраны так, чтобы не пересекаться с плагинами (см. README). */
public final class MoneyCommands {

    private MoneyCommands() {
    }

    /** /money [игрок] — алиасы /balance, /bal (если свободны), /деньги, /баланс. */
    public static final class Balance extends Command {
        private final Env env;

        public Balance(final Env env) {
            super("money", "Баланс (core-vault)", "/money [игрок]", List.of("balance", "bal", "деньги", "баланс"));
            this.env = env;
        }

        @Override
        public boolean execute(final CommandSender sender, final String label, final String[] args) {
            if (!this.env.need(sender, this.env.s().modules().economy(), "economy")) {
                return true;
            }
            if (args.length == 0) {
                if (!(sender instanceof Player p)) {
                    this.env.msg(sender, "player-only");
                    return true;
                }
                this.env.msg(sender, "balance-self", Text.p("balance", this.env.money(this.env.treasury().balance(p.getUniqueId()))));
                return true;
            }
            final Targets.Target t = Targets.resolve(this.env.store(), args[0]);
            if (t == null) {
                this.env.msg(sender, "unknown-player", Text.p("player", args[0]));
                return true;
            }
            this.env.msg(sender, "balance-other", Text.p("player", t.name()),
                Text.p("balance", this.env.money(this.env.treasury().balance(t.id()))));
            return true;
        }

        @Override
        public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
            return args.length == 1 ? Targets.complete(this.env.store(), args[0], true) : List.of();
        }
    }

    /** /mpay <игрок> <сумма> — алиас /pay (если свободен), /перевести; /mpay confirm — подтвердить крупный перевод. */
    public static final class Pay extends Command {
        private final Env env;
        private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

        private record Pending(UUID to, String name, long amount, long expires) {
        }

        public Pay(final Env env) {
            super("mpay", "Перевести деньги игроку (core-vault)", "/mpay <игрок> <сумма>", List.of("pay", "перевести"));
            this.env = env;
        }

        @Override
        public boolean execute(final CommandSender sender, final String label, final String[] args) {
            if (!this.env.need(sender, this.env.s().modules().economy(), "economy")) {
                return true;
            }
            if (!(sender instanceof Player p)) {
                this.env.msg(sender, "player-only");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
                final Pending pd = this.pending.remove(p.getUniqueId());
                if (pd == null || pd.expires() < System.currentTimeMillis()) {
                    this.env.msg(sender, "pay-no-pending");
                    return true;
                }
                this.send(p, pd.to(), pd.name(), pd.amount());
                return true;
            }
            if (args.length < 2) {
                this.env.msg(sender, "pay-usage");
                return true;
            }
            final var eco = this.env.s().economy();
            final long amount;
            try {
                amount = Money.parse(args[1], eco.currency().decimals(), false);
            } catch (final Money.ParseException e) {
                this.env.msg(sender, "bad-amount", Text.p("value", args[1]), Text.p("reason", e.getMessage()));
                return true;
            }
            final Targets.Target t = Targets.resolve(this.env.store(), args[0]);
            if (t == null) {
                this.env.msg(sender, "unknown-player", Text.p("player", args[0]));
                return true;
            }
            if (t.id().equals(p.getUniqueId())) {
                this.env.msg(sender, "pay-self");
                return true;
            }
            if (t.online() == null && !eco.payOffline()) {
                this.env.msg(sender, "pay-offline", Text.p("player", t.name()));
                return true;
            }
            if (amount < eco.payMin() && !p.hasPermission(Env.ADMIN)) {
                this.env.msg(sender, "pay-min", Text.p("min", this.env.money(eco.payMin())));
                return true;
            }
            final boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
            if (eco.payConfirmAbove() > 0 && amount >= eco.payConfirmAbove() && !confirmed) {
                this.pending.put(p.getUniqueId(), new Pending(t.id(), t.name(), amount,
                    System.currentTimeMillis() + eco.payConfirmSeconds() * 1000L));
                this.env.msg(sender, "pay-confirm", Text.p("amount", this.env.money(amount)), Text.p("player", t.name()),
                    Text.p("seconds", eco.payConfirmSeconds()));
                return true;
            }
            this.send(p, t.id(), t.name(), amount);
            return true;
        }

        private void send(final Player p, final UUID to, final String toName, final long amount) {
            final Ledger.Result r = this.env.treasury().transfer(p.getUniqueId(), p.getName(), to, toName, amount);
            if (!r.ok()) {
                if (r.error().contains("недостаточно")) {
                    this.env.msg(p, "pay-no-money", Text.p("balance", this.env.money(r.balance())));
                } else if (r.error().contains("потолок")) {
                    this.env.msg(p, "pay-target-full", Text.p("player", toName));
                } else {
                    this.env.msg(p, "eco-failed", Text.p("reason", r.error()));
                }
                return;
            }
            this.env.msg(p, "pay-sent", Text.p("amount", this.env.money(amount)), Text.p("player", toName),
                Text.p("balance", this.env.money(r.balance())));
            final Player target = org.bukkit.Bukkit.getPlayer(to);
            if (target != null) {
                this.env.msg(target, "pay-received", Text.p("amount", this.env.money(amount)), Text.p("player", p.getName()));
            }
        }

        @Override
        public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
            if (args.length == 1) {
                final List<String> out = new java.util.ArrayList<>(Targets.complete(this.env.store(), args[0],
                    this.env.s().economy().payOffline()));
                if ("confirm".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add("confirm");
                }
                out.remove(sender.getName());
                return out;
            }
            return List.of();
        }
    }

    /** /moneytop [стр.] — алиасы /baltop, /mtop. Топ из кэша (считается вне потоков тика). */
    public static final class Top extends Command {
        private final Env env;

        public Top(final Env env) {
            super("moneytop", "Самые богатые игроки (core-vault)", "/moneytop [страница]", List.of("baltop", "mtop"));
            this.env = env;
        }

        @Override
        public boolean execute(final CommandSender sender, final String label, final String[] args) {
            if (!this.env.need(sender, this.env.s().modules().economy(), "economy")) {
                return true;
            }
            final TopCache.Snapshot snap = this.env.top().get();
            showTop(this.env, sender, snap.money(), args.length > 0 ? args[0] : "1", snap.computedAt(), false);
            return true;
        }
    }

    /** Страница топа: деньги или уровни. */
    static void showTop(final Env env, final CommandSender sender, final List<TopCache.Entry> list, final String pageArg,
                        final long computedAt, final boolean levels) {
        if (list.isEmpty()) {
            env.msg(sender, "top-empty");
            return;
        }
        final int size = env.s().economy().topPageSize();
        final int pages = (list.size() + size - 1) / size;
        int page;
        try {
            page = Math.max(1, Math.min(pages, Integer.parseInt(pageArg)));
        } catch (final NumberFormatException e) {
            page = 1;
        }
        env.msg(sender, "top-header", Text.p("page", page), Text.p("pages", pages));
        for (int i = (page - 1) * size; i < Math.min(list.size(), page * size); i++) {
            final TopCache.Entry e = list.get(i);
            final String value = levels ? "ур. " + TopCache.levelOf(e) : env.money(e.value());
            env.msg(sender, "top-line", Text.p("rank", i + 1), Text.p("player", e.name()), Text.p("balance", value));
        }
        final long ago = (System.currentTimeMillis() - computedAt) / 1000;
        if (ago > 5) {
            env.msg(sender, "top-stale", Text.p("seconds", ago));
        }
    }
}
