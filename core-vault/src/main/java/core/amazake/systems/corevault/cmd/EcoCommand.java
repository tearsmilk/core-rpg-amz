package core.amazake.systems.corevault.cmd;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.Text;
import core.amazake.systems.corevault.coin.Coins;
import core.amazake.systems.corevault.eco.Ledger;
import core.amazake.systems.corevault.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /ceco … — право corevault.admin. Алиасы /eco (если свободен — на проде его занимает Vault 2.x), /corevault.
 * give/take/set/reset работают и для игроков не в сети.
 */
public final class EcoCommand extends Command {

    private static final List<String> SUBS = List.of("give", "take", "set", "reset", "drop", "coin", "import", "info", "reload");

    private final Env env;
    /** Выбросить монеты в точке (поток региона точки): (точка, количество). */
    private final BiConsumer<Location, Integer> dropper;

    public EcoCommand(final Env env, final BiConsumer<Location, Integer> dropper) {
        super("ceco", "Управление экономикой core-vault", "/ceco give|take|set|reset|drop|coin|import|info|reload",
            List.of("eco", "corevault"));
        this.setPermission(Env.ADMIN);
        this.env = env;
        this.dropper = dropper;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give", "add", "take", "remove", "set", "reset" -> this.balance(sender, sub, args);
            case "drop" -> this.drop(sender, args);
            case "coin", "coins" -> this.coin(sender, args);
            case "import" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("vault")) {
                    this.env.msg(sender, "eco-usage");
                } else {
                    this.env.importer().accept(sender, args.length > 2 && args[2].equalsIgnoreCase("replace"));
                }
            }
            case "info" -> {
                final Settings s = this.env.s();
                final var vh = this.env.treasury().vault();
                final Class<?> eco = core.amazake.systems.corevault.vault.VaultHook.economyClass();
                this.env.msg(sender, "info", Text.p("modules", s.modules().describe()),
                    Text.p("backend", this.env.treasury().ledger().describe()),
                    Text.p("provider", eco == null ? "Vault не установлен" : vh.active(eco)),
                    Text.p("accounts", this.env.store().size()));
            }
            case "reload" -> this.env.reload().accept(sender);
            default -> this.env.msg(sender, "eco-usage");
        }
        return true;
    }

    private void balance(final CommandSender sender, final String sub, final String[] args) {
        if (!this.env.need(sender, this.env.s().modules().economy(), "economy")) {
            return;
        }
        final boolean reset = sub.equals("reset");
        if (args.length < (reset ? 2 : 3)) {
            this.env.msg(sender, "eco-usage");
            return;
        }
        final Settings.Eco eco = this.env.s().economy();
        final Targets.Target t = Targets.resolve(this.env.store(), args[1]);
        if (t == null) {
            this.env.msg(sender, "unknown-player", Text.p("player", args[1]));
            return;
        }
        long amount = eco.startBalance();
        if (!reset) {
            try {
                amount = Money.parse(args[2], eco.currency().decimals(), sub.equals("set"));
            } catch (final Money.ParseException e) {
                this.env.msg(sender, "bad-amount", Text.p("value", args[2]), Text.p("reason", e.getMessage()));
                return;
            }
        }
        final Ledger.Result r;
        final String action;
        switch (sub) {
            case "give", "add" -> {
                r = this.env.treasury().deposit(t.id(), t.name(), amount);
                action = "Начислено";
            }
            case "take", "remove" -> {
                r = this.env.treasury().withdraw(t.id(), t.name(), amount);
                action = "Списано";
            }
            case "set" -> {
                r = this.env.treasury().set(t.id(), t.name(), amount);
                action = "Баланс задан";
            }
            default -> {
                r = this.env.treasury().set(t.id(), t.name(), amount);
                action = "Сброшено";
            }
        }
        if (!r.ok()) {
            this.env.msg(sender, "eco-failed", Text.p("reason", r.error() + " (баланс " + this.env.money(r.balance()) + ")"));
            return;
        }
        this.env.msg(sender, "eco-done", Text.p("action", action), Text.p("player", t.name()),
            Text.p("amount", this.env.money(amount)), Text.p("balance", this.env.money(r.balance())));
        final Player online = Bukkit.getPlayer(t.id());
        if (online != null && online != sender) {
            if (sub.equals("give") || sub.equals("add")) {
                this.env.msg(online, "eco-notify-give", Text.p("amount", this.env.money(amount)));
            } else if (sub.equals("take") || sub.equals("remove")) {
                this.env.msg(online, "eco-notify-take", Text.p("amount", this.env.money(amount)));
            }
        }
    }

    /** /ceco drop <кол-во> [x y z [мир]] — монеты на землю. */
    private void drop(final CommandSender sender, final String[] args) {
        if (!this.env.need(sender, this.env.s().modules().coins() && this.env.s().modules().economy(), "coins")) {
            return;
        }
        if (args.length < 2) {
            this.env.msg(sender, "eco-usage");
            return;
        }
        final int count;
        try {
            count = Integer.parseInt(args[1]);
            if (count < 1 || count > 6400) {
                throw new NumberFormatException();
            }
        } catch (final NumberFormatException e) {
            this.env.msg(sender, "bad-number", Text.p("value", args[1]));
            return;
        }
        final Location at;
        if (args.length >= 5) {
            World w = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
            if (args.length >= 6) {
                w = Bukkit.getWorld(args[5]);
                if (w == null) {
                    this.env.msg(sender, "eco-failed", Text.p("reason", "нет мира " + args[5]));
                    return;
                }
            }
            try {
                at = new Location(w, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
            } catch (final NumberFormatException e) {
                this.env.msg(sender, "bad-number", Text.p("value", args[2] + " " + args[3] + " " + args[4]));
                return;
            }
        } else if (sender instanceof Player p) {
            at = p.getLocation();
        } else {
            this.env.msg(sender, "player-only");
            return;
        }
        this.dropper.accept(at, count);
        final long value = this.env.s().coins().value();
        this.env.msg(sender, "coins-dropped", Text.p("count", count), Text.p("amount", this.env.money(value * count)),
            Text.p("where", at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()));
    }

    /** /ceco coin <игрок> <кол-во> [стоимость] — монеты предметом в инвентарь (не влезло — на землю). */
    private void coin(final CommandSender sender, final String[] args) {
        if (!this.env.need(sender, this.env.s().modules().coins() && this.env.s().modules().economy(), "coins")) {
            return;
        }
        if (args.length < 3) {
            this.env.msg(sender, "eco-usage");
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            this.env.msg(sender, "unknown-player", Text.p("player", args[1]));
            return;
        }
        final int count;
        try {
            count = Integer.parseInt(args[2]);
            if (count < 1 || count > 6400) {
                throw new NumberFormatException();
            }
        } catch (final NumberFormatException e) {
            this.env.msg(sender, "bad-number", Text.p("value", args[2]));
            return;
        }
        final Settings s = this.env.s();
        long value = s.coins().value();
        if (args.length >= 4) {
            try {
                value = Money.parse(args[3], s.economy().currency().decimals(), false);
            } catch (final Money.ParseException e) {
                this.env.msg(sender, "bad-amount", Text.p("value", args[3]), Text.p("reason", e.getMessage()));
                return;
            }
        }
        final long v = value;
        this.env.context().runOnceForPlayer(target, () -> {
            final ItemStack proto = Coins.create(s.coins(), v, 1);
            int left = count;
            while (left > 0) {
                final int n = Math.min(left, proto.getMaxStackSize());
                left -= n;
                final ItemStack st = proto.clone();
                st.setAmount(n);
                final Map<Integer, ItemStack> rest = target.getInventory().addItem(st);
                for (final ItemStack r : rest.values()) {
                    target.getWorld().dropItem(target.getLocation(), r);
                }
            }
        });
        this.env.msg(sender, "coins-given", Text.p("player", target.getName()), Text.p("count", count));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!sender.hasPermission(Env.ADMIN)) {
            return List.of();
        }
        if (args.length == 1) {
            final List<String> out = new ArrayList<>();
            for (final String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("give", "add", "take", "remove", "set", "reset", "coin").contains(sub)) {
            return Targets.complete(this.env.store(), args[1], !sub.equals("coin"));
        }
        if (args.length == 2 && sub.equals("import")) {
            return List.of("vault");
        }
        if (args.length == 3 && sub.equals("import")) {
            return List.of("replace");
        }
        return List.of();
    }
}
