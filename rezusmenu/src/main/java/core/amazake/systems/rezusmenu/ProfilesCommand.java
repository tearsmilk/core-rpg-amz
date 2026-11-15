package core.amazake.systems.rezusmenu;

import core.amazake.systems.rezusmenu.util.Text;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /profiles} ({@code /профили}) — меню выбора профилей (то же, что {@code /menuses open profiles}). */
final class ProfilesCommand extends Command {

    private final MenuManager menus;

    ProfilesCommand(final MenuManager menus) {
        super("profiles", "Профили (персонажи)", "/profiles", List.of("профили", "characters"));
        this.menus = menus;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (sender instanceof final Player p) {
            this.menus.open(p, "profiles");
        } else {
            sender.sendMessage(Text.mm(Text.prefix() + "<red>Только для игрока.</red>"));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        return List.of();
    }
}
