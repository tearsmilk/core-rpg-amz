package core.amazake.systems.coreeffects;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreeffects.effect.Active;
import core.amazake.systems.coreeffects.effect.EffectType;
import core.amazake.systems.coreeffects.item.Items;
import core.amazake.systems.coreeffects.logic.Durations;
import core.amazake.systems.coreeffects.runtime.EffectManager;
import core.amazake.systems.coreeffects.runtime.PlayerFx;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /effects give &lt;игрок&gt; &lt;эффект&gt; [время] | remove &lt;игрок&gt; &lt;эффект&gt; | clear &lt;игрок|all&gt; |
 * list [игрок] | bandage [игрок] [кол-во] | reload — право coreeffects.admin.
 */
final class EffectsCommand extends Command {

    static final String PERMISSION = "coreeffects.admin";
    private static final List<String> SUBS = List.of("give", "remove", "clear", "list", "bandage", "reload");

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final EffectManager manager;
    private final Runnable reload;

    EffectsCommand(final SystemContext context, final Supplier<Settings> settings, final EffectManager manager,
                   final Runnable reload) {
        super("effects", "Кастомные эффекты core-effects",
            "/effects give <игрок> <эффект> [время] | remove <игрок> <эффект> | clear <игрок|all> | list [игрок] | bandage [игрок] [кол-во] | reload",
            List.of("coreeffects", "эффекты"));
        this.setPermission(PERMISSION);
        this.context = context;
        this.settings = settings;
        this.manager = manager;
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
            case "remove" -> this.remove(sender, s, args);
            case "clear" -> this.clear(sender, s, args);
            case "list" -> this.list(sender, s, args);
            case "bandage" -> this.bandage(sender, s, args);
            case "reload" -> {
                this.reload.run();
                Text.send(sender, this.settings.get(), "reloaded");
            }
            default -> Text.send(sender, s, "usage");
        }
        return true;
    }

    private Player player(final CommandSender sender, final Settings s, final String name) {
        final Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            Text.send(sender, s, "no-player", Placeholder.unparsed("player", name));
        }
        return p;
    }

    private EffectType effect(final CommandSender sender, final Settings s, final String raw) {
        final EffectType t = EffectType.parse(raw);
        if (t == null) {
            Text.send(sender, s, "no-effect", Placeholder.unparsed("effect", raw));
        }
        return t;
    }

    private void give(final CommandSender sender, final Settings s, final String[] args) {
        if (args.length < 3) {
            Text.send(sender, s, "usage");
            return;
        }
        final Player target = this.player(sender, s, args[1]);
        final EffectType type = target == null ? null : this.effect(sender, s, args[2]);
        if (type == null) {
            return;
        }
        long seconds = s.of(type).defaultSeconds();
        if (args.length >= 4) {
            final Long v = Durations.parseSeconds(args[3]);
            if (v == null) {
                Text.send(sender, s, "bad-time", Placeholder.unparsed("time", args[3]));
                return;
            }
            seconds = v;
        }
        final long ticks = seconds < 0 ? Durations.INFINITE : seconds * 20;
        if (!this.manager.give(target, type, ticks)) {
            Text.send(sender, s, "no-player", Placeholder.unparsed("player", args[1]));
            return;
        }
        Text.send(sender, s, "given", Placeholder.unparsed("player", target.getName()),
            Placeholder.parsed("effect", s.of(type).name()), Placeholder.unparsed("time", Durations.format(ticks)));
    }

    private void remove(final CommandSender sender, final Settings s, final String[] args) {
        if (args.length < 3) {
            Text.send(sender, s, "usage");
            return;
        }
        final Player target = this.player(sender, s, args[1]);
        final EffectType type = target == null ? null : this.effect(sender, s, args[2]);
        if (type == null) {
            return;
        }
        final PlayerFx fx = this.manager.fx(target.getUniqueId());
        if (fx == null || !fx.book().has(type)) {
            Text.send(sender, s, "not-active", Placeholder.unparsed("player", target.getName()),
                Placeholder.parsed("effect", s.of(type).name()));
            return;
        }
        this.manager.remove(target, type);
        Text.send(sender, s, "removed", Placeholder.unparsed("player", target.getName()),
            Placeholder.parsed("effect", s.of(type).name()));
    }

    private void clear(final CommandSender sender, final Settings s, final String[] args) {
        if (args.length < 2) {
            Text.send(sender, s, "usage");
            return;
        }
        if (args[1].equalsIgnoreCase("all") || args[1].equals("*")) {
            int n = 0;
            for (final Player p : Bukkit.getOnlinePlayers()) {
                final PlayerFx fx = this.manager.fx(p.getUniqueId());
                if (fx != null && !fx.book().isEmpty() && this.manager.clear(p)) {
                    n++;
                }
            }
            Text.send(sender, s, "cleared-all", Placeholder.unparsed("count", Integer.toString(n)));
            return;
        }
        final Player target = this.player(sender, s, args[1]);
        if (target == null) {
            return;
        }
        final PlayerFx fx = this.manager.fx(target.getUniqueId());
        final int count = fx == null ? 0 : fx.book().snapshot().size();
        this.manager.clear(target);
        Text.send(sender, s, "cleared", Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("count", Integer.toString(count)));
    }

    private void list(final CommandSender sender, final Settings s, final String[] args) {
        final Player target;
        if (args.length >= 2) {
            target = this.player(sender, s, args[1]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            Text.send(sender, s, "usage");
            return;
        }
        final PlayerFx fx = this.manager.fx(target.getUniqueId());
        final Map<EffectType, Active> snap = fx == null ? Map.of() : fx.book().snapshot();
        if (snap.isEmpty()) {
            Text.send(sender, s, "list-empty", Placeholder.unparsed("player", target.getName()));
            return;
        }
        final List<String> parts = new ArrayList<>();
        for (final var e : snap.entrySet()) {
            String extra = "";
            if (e.getKey() == EffectType.BLEEDING) {
                extra = e.getValue().b() > 0 ? " без сознания " + Durations.format(e.getValue().b())
                    : " без перевязки " + Durations.format(e.getValue().a());
            } else if (e.getKey() == EffectType.WARMTH) {
                extra = " без питья " + Durations.format(e.getValue().a());
            } else if (e.getKey() == EffectType.CURSE) {
                extra = " потеря через " + Durations.format(e.getValue().a());
            }
            parts.add(s.of(e.getKey()).name() + " <white>" + Durations.format(e.getValue().remaining()) + "</white><dark_gray>"
                + extra + "</dark_gray>");
        }
        Text.send(sender, s, "list", Placeholder.unparsed("player", target.getName()),
            Placeholder.parsed("list", String.join("<gray>, </gray>", parts)));
    }

    private void bandage(final CommandSender sender, final Settings s, final String[] args) {
        final Player target;
        if (args.length >= 2) {
            target = this.player(sender, s, args[1]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            Text.send(sender, s, "usage");
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64 * 36, Integer.parseInt(args[2])));
            } catch (final NumberFormatException e) {
                Text.send(sender, s, "usage");
                return;
            }
        }
        final int total = amount;
        this.context.runOnceForPlayer(target, () -> {
            int left = total;
            while (left > 0) {
                final ItemStack one = Items.bandage(s, 1);
                final int n = Math.min(one.getMaxStackSize(), left);
                left -= n;
                for (final ItemStack rest : target.getInventory().addItem(Items.bandage(s, n)).values()) {
                    target.getWorld().dropItem(target.getLocation(), rest);
                }
            }
        });
        Text.send(sender, s, "bandage-given", Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("amount", Integer.toString(total)));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(SUBS);
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            for (final Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
            if (args[0].equalsIgnoreCase("clear")) {
                out.add("all");
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove"))) {
            for (final EffectType t : EffectType.values()) {
                out.add(t.id());
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            out.addAll(List.of("30", "60", "2m", "5m", "inf"));
        }
        final String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
