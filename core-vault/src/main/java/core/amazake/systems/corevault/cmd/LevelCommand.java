package core.amazake.systems.corevault.cmd;

import core.amazake.systems.corevault.Text;
import core.amazake.systems.corevault.data.TopCache;
import core.amazake.systems.corevault.level.LevelCurve;
import core.amazake.systems.corevault.level.LevelMath;
import core.amazake.systems.corevault.level.LevelService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /level [игрок] — свой или чужой уровень; /level top [стр.]; /level give|take|set <игрок> exp|levels <кол-во> —
 * право corevault.admin, и для игроков не в сети. Алиасы /lvl, /уровень.
 *
 * <p>exp: give/take — добавить/снять опыт (с переходом через уровни), set — задать весь опыт с начала.
 * levels: give/take — сдвинуть уровень (опыт внутри уровня сохраняется), set — уровень с нулём опыта.</p>
 */
public final class LevelCommand extends Command {

    private final Env env;

    public LevelCommand(final Env env) {
        super("level", "Уровень и опыт (core-vault)", "/level [игрок] | top | give|take|set <игрок> exp|levels <кол-во>",
            List.of("lvl", "уровень"));
        this.env = env;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.env.need(sender, this.env.s().modules().levels(), "levels")) {
            return true;
        }
        final LevelService ls = this.env.levels();
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                this.env.msg(sender, "level-usage");
                return true;
            }
            this.show(sender, p.getUniqueId(), null);
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("top")) {
            final TopCache.Snapshot snap = this.env.top().get();
            MoneyCommands.showTop(this.env, sender, snap.levels(), args.length > 1 ? args[1] : "1", snap.computedAt(), true);
            return true;
        }
        if (List.of("give", "take", "set").contains(sub) && sender.hasPermission(Env.ADMIN)) {
            this.admin(sender, sub, args, ls);
            return true;
        }
        if (args.length == 1) {
            final Targets.Target t = Targets.resolve(this.env.store(), args[0]);
            if (t == null) {
                this.env.msg(sender, "unknown-player", Text.p("player", args[0]));
                return true;
            }
            this.show(sender, t.id(), t.name());
            return true;
        }
        this.env.msg(sender, "level-usage");
        return true;
    }

    private void show(final CommandSender sender, final java.util.UUID id, final String name) {
        final LevelService ls = this.env.levels();
        final LevelMath.State st = ls.state(id);
        if (st.level() >= ls.curve().maxLevel() && name == null) {
            this.env.msg(sender, "level-max", ls.tags(st, 0, name));
            return;
        }
        this.env.msg(sender, name == null ? "level-self" : "level-other", ls.tags(st, 0, name));
    }

    private void admin(final CommandSender sender, final String sub, final String[] args, final LevelService ls) {
        if (args.length < 4) {
            this.env.msg(sender, "level-usage");
            return;
        }
        final Targets.Target t = Targets.resolve(this.env.store(), args[1]);
        if (t == null) {
            this.env.msg(sender, "unknown-player", Text.p("player", args[1]));
            return;
        }
        final String kind = args[2].toLowerCase(Locale.ROOT);
        final long n;
        try {
            n = Long.parseLong(args[3].replace("_", ""));
            if (n < 0) {
                throw new NumberFormatException();
            }
        } catch (final NumberFormatException e) {
            this.env.msg(sender, "bad-number", Text.p("value", args[3]));
            return;
        }
        final LevelCurve c = ls.curve();
        final UnaryOperator<LevelMath.State> op;
        long shown = 0;
        switch (kind) {
            case "exp", "xp", "опыт" -> {
                switch (sub) {
                    case "give" -> {
                        op = s -> LevelMath.addExp(c, s, n);
                        shown = n;
                    }
                    case "take" -> op = s -> LevelMath.addExp(c, s, -n);
                    default -> op = s -> LevelMath.fromTotal(c, n);
                }
            }
            case "levels", "level", "lvl", "уровни" -> {
                if (n > Integer.MAX_VALUE) {
                    this.env.msg(sender, "bad-number", Text.p("value", args[3]));
                    return;
                }
                switch (sub) {
                    case "give" -> op = s -> LevelMath.addLevels(c, s, n);
                    case "take" -> op = s -> LevelMath.addLevels(c, s, -n);
                    default -> op = s -> LevelMath.setLevel(c, (int) n, 0);
                }
            }
            default -> {
                this.env.msg(sender, "level-usage");
                return;
            }
        }
        final LevelService.Change ch = ls.apply(t.id(), t.name(), op, shown);
        this.env.msg(sender, "level-done", ls.tags(ch.after(), 0, t.name()));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        final boolean admin = sender.hasPermission(Env.ADMIN);
        if (args.length == 1) {
            final List<String> out = new ArrayList<>(Targets.complete(this.env.store(), args[0], true));
            for (final String s : admin ? List.of("top", "give", "take", "set") : List.of("top")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(0, s);
                }
            }
            return out;
        }
        if (!admin || !List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        if (args.length == 2) {
            return Targets.complete(this.env.store(), args[1], true);
        }
        if (args.length == 3) {
            return List.of("exp", "levels");
        }
        return List.of();
    }
}
