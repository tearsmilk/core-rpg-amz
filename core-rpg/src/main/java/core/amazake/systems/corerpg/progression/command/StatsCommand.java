package core.amazake.systems.corerpg.progression.command;

import core.amazake.systems.corerpg.progression.ProgressionModule;
import core.amazake.systems.corerpg.progression.StatType;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /stats} (алиасы {@code параметры}, {@code params}): меню параметров; {@code /stats add <параметр> [кол-во]}.
 */
public final class StatsCommand extends Command {

    private static final List<String> STAT_NAMES = List.of("primary", "agility", "stamina", "luck",
        "сила", "точность", "интеллект", "ловкость", "выносливость", "удача");

    private final ProgressionModule module;

    public StatsCommand(final ProgressionModule module) {
        super("stats", "Распределение очков параметров", "/stats [add <параметр> [кол-во]]", List.of("параметры", "params"));
        this.module = module;
    }

    @Override
    public boolean execute(final @NotNull CommandSender sender, final @NotNull String label, final @NotNull String[] args) {
        if (!(sender instanceof final Player player)) {
            this.module.send(sender, "only-players");
            return true;
        }
        if (args.length == 0) {
            this.module.openStatsMenu(player);
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("add") && !sub.equals("добавить")) {
            this.usage(player);
            return true;
        }
        if (args.length < 2) {
            this.usage(player);
            return true;
        }
        final StatType type = StatType.parse(args[1]);
        if (type == null) {
            this.module.send(player, "stats-unknown", Placeholder.unparsed("input", args[1]));
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2].trim());
            } catch (final NumberFormatException e) {
                amount = -1;
            }
            if (amount < 1) {
                this.module.send(player, "bad-number", Placeholder.unparsed("input", args[2]));
                return true;
            }
        }
        this.module.addStat(player, type, amount, true);
        return true;
    }

    private void usage(final CommandSender sender) {
        sender.sendMessage(Text.mm(Text.prefix() + "<yellow>/stats</yellow> <gray>— меню параметров</gray>\n"
            + "<yellow>/stats add <параметр> [кол-во]</yellow> <gray>— вложить очки: сила/точность/интеллект, "
            + "ловкость, выносливость, удача</gray>"));
    }

    @Override
    public @NotNull List<String> tabComplete(final @NotNull CommandSender sender, final @NotNull String alias,
                                             final @NotNull String[] args) {
        if (args.length == 1) {
            return Completions.filter(List.of("add"), args[0]);
        }
        if (!args[0].equalsIgnoreCase("add") && !args[0].equalsIgnoreCase("добавить")) {
            return List.of();
        }
        if (args.length == 2) {
            return Completions.filter(STAT_NAMES, args[1]);
        }
        if (args.length == 3) {
            final List<String> amounts = new ArrayList<>(List.of("1", "5", "10"));
            if (sender instanceof final Player player && this.module.isLoaded(player)) {
                final int free = this.module.profile(player).freePoints();
                if (free > 0 && !amounts.contains(String.valueOf(free))) {
                    amounts.add(String.valueOf(free));
                }
            }
            return Completions.filter(amounts, args[2]);
        }
        return List.of();
    }
}
