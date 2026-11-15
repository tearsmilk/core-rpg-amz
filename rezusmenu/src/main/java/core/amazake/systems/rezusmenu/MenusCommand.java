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
    private final core.amazake.systems.rezusmenu.profiles.ProfileService profiles;

    MenusCommand(final Supplier<Settings> settings, final MenuManager menus, final Runnable reload,
                 final core.amazake.systems.rezusmenu.profiles.ProfileService profiles) {
        super("menuses", "Меню сервера", "/menuses", List.of("menus", "меню"));
        this.settings = settings;
        this.menus = menus;
        this.reload = reload;
        this.profiles = profiles;
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
        if (args.length >= 1 && args[0].equalsIgnoreCase("rows")) {
            this.rows(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("slots")) {
            this.slots(sender, args);
            return true;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Text.mm(Text.prefix() + "<red>Только для игрока.</red>"));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("calibrate")) {
            this.menus.openCalibration(player);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("open")) {
            this.menus.open(player, args[1].toLowerCase(Locale.ROOT));
            return true;
        }
        player.showDialog(HubDialog.build(this.settings.get()));
        return true;
    }

    /**
     * {@code /menuses rows add|set|get <ник> [n]} — купленные ряды хранилища (для доната; можно из консоли и для
     * игрока не в сети). add по умолчанию +1.
     */
    private void rows(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.mm(Text.prefix() + "<red>Нет прав.</red>"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Text.mm(Text.prefix() + "<gray>/menuses rows add|set|get <ник> [число]</gray>"));
            return;
        }
        final String op = args[1].toLowerCase(Locale.ROOT);
        final org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getPlayerExact(args[2]) != null
            ? org.bukkit.Bukkit.getPlayerExact(args[2]) : org.bukkit.Bukkit.getOfflinePlayerIfCached(args[2]);
        if (target == null) {
            sender.sendMessage(Text.mm(Text.prefix() + "<red>Игрок не найден (ещё ни разу не заходил).</red>"));
            return;
        }
        final Settings s = this.settings.get();
        final int now = this.menus.store().stashExtraRows(target.getUniqueId());
        int n = op.equals("add") ? 1 : 0;
        if (args.length >= 4) {
            try {
                n = Integer.parseInt(args[3]);
            } catch (final NumberFormatException e) {
                sender.sendMessage(Text.mm(Text.prefix() + "<red>Не число: " + Text.escape(args[3]) + "</red>"));
                return;
            }
        }
        final int result = switch (op) {
            case "add" -> this.menus.store().setStashExtraRows(target.getUniqueId(), now + n, s.stashMaxExtraRows());
            case "set" -> this.menus.store().setStashExtraRows(target.getUniqueId(), n, s.stashMaxExtraRows());
            case "get" -> now;
            default -> -1;
        };
        if (result < 0) {
            sender.sendMessage(Text.mm(Text.prefix() + "<gray>/menuses rows add|set|get <ник> [число]</gray>"));
            return;
        }
        sender.sendMessage(Text.mm(Text.prefix() + "<green>" + Text.escape(String.valueOf(target.getName()))
            + ": куплено рядов хранилища " + result + " из " + s.stashMaxExtraRows() + " (всего рядов "
            + (s.stashBaseRows() + result) + ").</green>"));
    }

    /**
     * {@code /menuses slots add|set|get <ник> [n]} — купленные слоты профилей (для доната; из консоли и для игрока
     * не в сети). add по умолчанию +1.
     */
    private void slots(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.mm(Text.prefix() + "<red>Нет прав.</red>"));
            return;
        }
        final String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (args.length < 3 || !List.of("add", "set", "get").contains(op)) {
            sender.sendMessage(Text.mm(Text.prefix() + "<gray>/menuses slots add|set|get <ник> [число]</gray>"));
            return;
        }
        final org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getPlayerExact(args[2]) != null
            ? org.bukkit.Bukkit.getPlayerExact(args[2]) : org.bukkit.Bukkit.getOfflinePlayerIfCached(args[2]);
        if (target == null) {
            sender.sendMessage(Text.mm(Text.prefix() + "<red>Игрок не найден (ещё ни разу не заходил).</red>"));
            return;
        }
        int n = op.equals("add") ? 1 : 0;
        if (args.length >= 4) {
            try {
                n = Integer.parseInt(args[3]);
            } catch (final NumberFormatException e) {
                sender.sendMessage(Text.mm(Text.prefix() + "<red>Не число: " + Text.escape(args[3]) + "</red>"));
                return;
            }
        }
        final String name = String.valueOf(target.getName());
        this.profiles.slots(target.getUniqueId(), op, n, msg -> sender.sendMessage(Text.mm(Text.prefix() + "<green>"
            + Text.escape(name) + ": " + Text.escape(msg) + "</green>")));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            final List<String> subs = sender.hasPermission(ADMIN) ? List.of("open", "calibrate", "reload", "rows", "slots") : List.of("open", "calibrate");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("rows") || args[0].equalsIgnoreCase("slots")) && sender.hasPermission(ADMIN)) {
            return List.of("add", "set", "get").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("rows") || args[0].equalsIgnoreCase("slots")) && sender.hasPermission(ADMIN)) {
            return null; // ники игроков
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            return this.settings.get().menus().stream().map(Settings.MenuEntry::id)
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
