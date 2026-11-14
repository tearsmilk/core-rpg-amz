package core.amazake.systems.rezusmenu;

import core.amazake.systems.rezusmenu.util.Text;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /menuses} — диалог со списком меню (для всех). {@code /menuses open <id>} — сразу открыть меню,
 * {@code /menuses reload} — перечитать config.yml (право rezusmenu.admin).
 */
final class MenusCommand extends Command {

    static final String ADMIN = "rezusmenu.admin";

    private final Supplier<Settings> settings;
    private final MenuManager menus;
    private final Runnable reload;

    MenusCommand(final Supplier<Settings> settings, final MenuManager menus, final Runnable reload) {
        super("menuses", "Меню сервера", "/menuses", List.of("menus", "меню"));
        this.settings = settings;
        this.menus = menus;
        this.reload = reload;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(ADMIN)) {
                sender.sendMessage(Text.mm(Text.prefix() + "<red>Нет прав.</red>"));
                return true;
            }
            this.reload.run();
            sender.sendMessage(Text.mm(Text.prefix() + "<green>Настройки перечитаны.</green>"));
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Text.mm(Text.prefix() + "<red>Только для игрока.</red>"));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("open")) {
            this.menus.open(player, args[1].toLowerCase(Locale.ROOT));
            return true;
        }
        player.showDialog(HubDialog.build(this.settings.get()));
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            final List<String> subs = sender.hasPermission(ADMIN) ? List.of("open", "reload") : List.of("open");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            return this.settings.get().menus().stream().map(Settings.MenuEntry::id)
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
