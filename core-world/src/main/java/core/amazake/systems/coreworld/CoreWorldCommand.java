package core.amazake.systems.coreworld;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreworld.module.WorldModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;

/** {@code /coreworld status} и {@code /coreworld reload} (право coreworld.admin). */
final class CoreWorldCommand extends Command {

    static final String ADMIN = "coreworld.admin";
    private static final String PREFIX = "<dark_gray>[<gold>CoreWorld</gold>]</dark_gray> ";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SystemContext context;
    private final Supplier<List<WorldModule>> modules;
    private final Supplier<String> reload;

    CoreWorldCommand(final SystemContext context, final Supplier<List<WorldModule>> modules, final Supplier<String> reload) {
        super("coreworld", "Правила мира core-world", "/coreworld <status|reload>", List.of("cworld"));
        this.context = context;
        this.modules = modules;
        this.reload = reload;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        final String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> this.status(sender);
            case "reload" -> {
                if (!sender.hasPermission(ADMIN)) {
                    send(sender, "<red>Нет права " + ADMIN + ".</red>");
                    return true;
                }
                // Перечитывание — в глобальном потоке (команда игрока идёт в его потоке).
                Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
                    final String error = this.reload.get();
                    this.reply(sender, error == null
                        ? "<green>config.yml перечитан.</green>"
                        : "<red>config.yml не прочитан, настройки прежние: " + MM.escapeTags(error) + "</red>");
                });
            }
            default -> send(sender, "<gray>Использование: /coreworld status · /coreworld reload</gray>");
        }
        return true;
    }

    private void status(final CommandSender sender) {
        send(sender, "<gold>core-world</gold> <gray>" + this.context.info().version() + " — модули:</gray>");
        for (final WorldModule module : this.modules.get()) {
            for (final String line : module.status()) {
                sender.sendMessage(MM.deserialize(line));
            }
        }
    }

    /** Ответ из глобального потока: игроку — в его поток, консоли — сразу. */
    private void reply(final CommandSender sender, final String message) {
        if (sender instanceof Player player) {
            this.context.runOnceForPlayer(player, () -> send(player, message));
        } else {
            send(sender, message);
        }
    }

    private static void send(final CommandSender sender, final String message) {
        sender.sendMessage(MM.deserialize(PREFIX + message));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        final String typed = args[0].toLowerCase(Locale.ROOT);
        if ("status".startsWith(typed)) {
            out.add("status");
        }
        if (sender.hasPermission(ADMIN) && "reload".startsWith(typed)) {
            out.add("reload");
        }
        return out;
    }
}
