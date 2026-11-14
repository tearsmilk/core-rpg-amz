package core.amazake.systems.coreportals;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreportals.portal.PortalManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /portals give [игрок] [кол-во] | reload | close | list — право coreportals.admin. */
final class PortalsCommand extends Command {

    private static final List<String> SUBS = List.of("give", "reload", "close", "list");

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final PortalManager portals;
    private final Runnable reload;

    PortalsCommand(final SystemContext context, final Supplier<Settings> settings, final PortalManager portals,
                   final Runnable reload) {
        super("portals", "Тестовые порталы core-portals", "/portals give [игрок] [кол-во] | reload | close | list",
            List.of("coreportals"));
        this.setPermission("coreportals.admin");
        this.context = context;
        this.settings = settings;
        this.portals = portals;
        this.reload = reload;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final Settings s = this.settings.get();
        final String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> this.give(sender, s, args);
            case "reload" -> {
                this.reload.run();
                Text.send(sender, this.settings.get(), this.settings.get().messages().reloaded());
            }
            case "close" -> Text.send(sender, s, s.messages().closed(),
                Placeholder.unparsed("count", Integer.toString(this.portals.closeAll())));
            case "list" -> {
                final List<String> lines = this.portals.describe();
                if (lines.isEmpty()) {
                    Text.send(sender, s, s.messages().listEmpty());
                } else {
                    Text.send(sender, s, s.messages().list(), Placeholder.unparsed("count", Integer.toString(lines.size())));
                    for (final String line : lines) {
                        sender.sendMessage(Text.parse("<gray> • </gray><white><line></white>", Placeholder.unparsed("line", line)));
                    }
                }
            }
            default -> Text.send(sender, s, s.messages().usage());
        }
        return true;
    }

    private void give(final CommandSender sender, final Settings s, final String[] args) {
        final Player target;
        int amount = 1;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                Text.send(sender, s, s.messages().playerNotFound(), Placeholder.unparsed("player", args[1]));
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            Text.send(sender, s, s.messages().usage());
            return;
        }
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64 * 36, Integer.parseInt(args[2])));
            } catch (final NumberFormatException e) {
                Text.send(sender, s, s.messages().usage());
                return;
            }
        }
        final int total = amount;
        // Инвентарь игрока меняет только его поток.
        this.context.runOnceForPlayer(target, () -> {
            int left = total;
            while (left > 0) {
                final int n = Math.min(64, left);
                left -= n;
                final ItemStack stack = PortalItem.create(s, n);
                for (final ItemStack rest : target.getInventory().addItem(stack).values()) {
                    target.getWorld().dropItem(target.getLocation(), rest);
                }
            }
            Text.send(target, s, s.messages().received(), Placeholder.unparsed("amount", Integer.toString(total)));
        });
        Text.send(sender, s, s.messages().given(), Placeholder.unparsed("amount", Integer.toString(total)),
            Placeholder.unparsed("player", target.getName()));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!this.testPermissionSilent(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            final String p = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(x -> x.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            final String p = args[1].toLowerCase(Locale.ROOT);
            final List<String> names = new ArrayList<>();
            for (final Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                    names.add(pl.getName());
                }
            }
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return List.of("1", "16", "64");
        }
        return List.of();
    }
}
