package core.amazake.systems.corerpg.progression.command;

import core.amazake.systems.corerpg.progression.ProgressionModule;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /myclass} (алиас {@code мойкласс}): меню персонажа, как было в плагине; {@code /myclass text} — текстом. */
public final class MyClassCommand extends Command {

    private final ProgressionModule module;

    public MyClassCommand(final ProgressionModule module) {
        super("myclass", "Класс, профессия, уровень и опыт", "/myclass [text]", List.of("мойкласс"));
        this.module = module;
    }

    @Override
    public boolean execute(final @NotNull CommandSender sender, final @NotNull String label, final @NotNull String[] args) {
        if (!(sender instanceof final Player player)) {
            this.module.send(sender, "only-players");
            return true;
        }
        if (!this.module.isLoaded(player)) {
            this.module.send(player, "profile-loading");
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("text") || args[0].equalsIgnoreCase("текст"))) {
            player.sendMessage(this.module.selfInfo(player));
            return true;
        }
        this.module.openCharacterMenu(player);
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(final @NotNull CommandSender sender, final @NotNull String alias,
                                             final @NotNull String[] args) {
        return args.length == 1 ? Completions.filter(List.of("text"), args[0]) : List.of();
    }
}
