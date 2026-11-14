package core.amazake.systems.coreskyrift;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreskyrift.event.EventManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /skyrift give [игрок] [кол-во] | start [игрок | мир x y z] | stop [игрок] | list | reload — право skyrift.admin.
 */
final class SkyriftCommand extends Command {

    static final String PERMISSION = "skyrift.admin";
    private static final List<String> SUBS = List.of("give", "start", "stop", "list", "reload");

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final EventManager events;
    private final Runnable reload;

    SkyriftCommand(final SystemContext context, final Supplier<Settings> settings, final EventManager events,
                   final Runnable reload) {
        super("skyrift", "«Разлом небес» core-skyrift",
            "/skyrift give [игрок] [кол-во] | start [игрок | мир x y z] | stop [игрок] | list | reload",
            List.of("coreskyrift", "разлом"));
        this.setPermission(PERMISSION);
        this.context = context;
        this.settings = settings;
        this.events = events;
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
            case "start" -> this.start(sender, s, args);
            case "stop" -> this.stop(sender, s, args);
            case "list" -> {
                final List<String> lines = this.events.describe();
                if (lines.isEmpty()) {
                    Text.send(sender, s, s.messages().listEmpty());
                } else {
                    Text.send(sender, s, s.messages().list(), Placeholder.unparsed("count", Integer.toString(lines.size())));
                    for (final String line : lines) {
                        sender.sendMessage(Text.parse("<gray> • </gray><white><line></white>", Placeholder.unparsed("line", line)));
                    }
                }
            }
            case "reload" -> {
                this.reload.run();
                Text.send(sender, this.settings.get(), this.settings.get().messages().reloaded());
            }
            default -> Text.send(sender, s, s.messages().usage());
        }
        return true;
    }

    private void give(final CommandSender sender, final Settings s, final String[] args) {
        final Player target;
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
        int amount = 1;
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
                for (final ItemStack rest : target.getInventory().addItem(RiftItem.create(s, n)).values()) {
                    target.getWorld().dropItem(target.getLocation(), rest);
                }
            }
            Text.send(target, s, s.messages().received(), Placeholder.unparsed("amount", Integer.toString(total)));
        });
        Text.send(sender, s, s.messages().given(), Placeholder.unparsed("amount", Integer.toString(total)),
            Placeholder.unparsed("player", target.getName()));
    }

    private void start(final CommandSender sender, final Settings s, final String[] args) {
        if (args.length >= 5) {
            final World world = Bukkit.getWorld(args[1]);
            if (world == null) {
                Text.send(sender, s, s.messages().worldNotFound(), Placeholder.unparsed("world", args[1]));
                return;
            }
            final double x;
            final double y;
            final double z;
            try {
                x = Double.parseDouble(args[2]);
                y = Double.parseDouble(args[3]);
                z = Double.parseDouble(args[4]);
            } catch (final NumberFormatException e) {
                Text.send(sender, s, s.messages().usage());
                return;
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {   // NaN/Infinity парсятся
                Text.send(sender, s, s.messages().usage());
                return;
            }
            final String refused = this.events.refuse(world, x, z, s);
            if (refused != null) {
                Text.send(sender, s, refused);
                return;
            }
            final var e = this.events.create(world, x, y, z, EventManager.yaw(s, 0f), sender.getName(), s);
            if (e == null) {
                Text.send(sender, s, s.messages().tooMany());
                return;
            }
            Text.send(sender, s, s.messages().started(), Placeholder.unparsed("where",
                world.getName() + " " + Math.round(x) + " " + Math.round(y) + " " + Math.round(z)));
            return;
        }
        final Player target;
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
        this.context.runOnceForPlayer(target, () -> {
            final String refused = this.events.startAt(target);
            if (refused != null) {
                Text.send(sender, s, refused);
            } else {
                final var l = target.getLocation();
                Text.send(sender, s, s.messages().started(), Placeholder.unparsed("where", target.getName() + ", "
                    + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
            }
        });
    }

    private void stop(final CommandSender sender, final Settings s, final String[] args) {
        final int n;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("all")) {
            final Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                Text.send(sender, s, s.messages().playerNotFound(), Placeholder.unparsed("player", args[1]));
                return;
            }
            // Место игрока читается только в его потоке.
            if (!this.context.runOnceForPlayer(target, () -> Text.send(sender, s, s.messages().stopped(),
                Placeholder.unparsed("count", Integer.toString(this.events.stopFor(target)))))) {
                Text.send(sender, s, s.messages().playerNotFound(), Placeholder.unparsed("player", args[1]));
            }
            return;
        }
        n = this.events.stopAll();
        Text.send(sender, s, s.messages().stopped(), Placeholder.unparsed("count", Integer.toString(n)));
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
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("give") || sub.equals("start") || sub.equals("stop"))) {
            final String p = args[1].toLowerCase(Locale.ROOT);
            final List<String> names = new ArrayList<>();
            if (sub.equals("stop") && "all".startsWith(p)) {
                names.add("all");
            }
            for (final Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                    names.add(pl.getName());
                }
            }
            if (sub.equals("start")) {
                for (final World w : Bukkit.getWorlds()) {
                    if (w.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                        names.add(w.getName());
                    }
                }
            }
            return names;
        }
        if (args.length == 3 && sub.equals("give")) {
            return List.of("1", "4", "16");
        }
        return List.of();
    }
}
