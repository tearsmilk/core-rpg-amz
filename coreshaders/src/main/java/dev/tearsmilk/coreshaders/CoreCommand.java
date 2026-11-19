package dev.tearsmilk.coreshaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;

public final class CoreCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("give", "color", "image", "shape", "remove", "info", "list",
        "removeall", "cmd", "trigger", "cooldown", "rim", "upload", "import", "delete-image", "reload", "help");

    private final CoreShadersPlugin plugin;
    private final EffectCommands fx;

    public CoreCommand(CoreShadersPlugin plugin) {
        this.plugin = plugin;
        this.fx = new EffectCommands(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Settings s = plugin.settings();
        if (!sender.hasPermission("coreshaders.admin")) {
            sender.sendMessage(s.msg("no-permission"));
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> give(sender, args);
            case "color", "colour", "цвет" -> onTarget(sender, args, "color");
            case "image", "картинка" -> onTarget(sender, args, "image");
            case "shape", "форма" -> onTarget(sender, args, "shape");
            case "remove" -> onTarget(sender, args, "remove");
            case "info" -> onTarget(sender, args, "info");
            case "list" -> list(sender);
            case "removeall" -> removeAll(sender, args);
            case "cmd", "command", "команда" -> fx.cmd(sender, args);
            case "trigger", "триггер" -> fx.trigger(sender, args);
            case "cooldown", "кд" -> fx.cooldown(sender, args);
            case "rim", "обод" -> fx.rim(sender, args);
            case "upload" -> {
                if (args.length < 3) {
                    sender.sendMessage(s.msg("upload-usage"));
                } else {
                    plugin.images().upload(sender, args[1], args[2]);
                }
            }
            case "import" -> {
                if (args.length < 2) {
                    sender.sendMessage(s.msg("import-usage", Placeholder.unparsed("list",
                        String.join(", ", plugin.images().importable()))));
                } else {
                    plugin.images().importFile(sender, args[1]);
                }
            }
            case "delete-image", "deleteimage" -> {
                if (args.length < 2) {
                    sender.sendMessage(s.msg("delete-usage", Placeholder.unparsed("list",
                        String.join(", ", s.customKinds.keySet()))));
                } else {
                    plugin.images().delete(sender, args[1]);
                }
            }
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(plugin.settings().msg("reloaded"));
            }
            default -> {
                for (Component c : s.lines("help")) {
                    sender.sendMessage(c);
                }
            }
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        Settings s = plugin.settings();
        int i = 1;
        Player target = null;
        if (args.length > i) {
            Player p = Bukkit.getPlayerExact(args[i]);
            if (p != null) {
                target = p;
                i++;
            } else if (Shape.parse(args[i]) == null) {
                sender.sendMessage(s.msg("player-not-found", Placeholder.unparsed("name", args[i])));
                return;
            }
        }
        if (target == null) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(s.msg("players-only"));
                return;
            }
            target = p;
        }
        Shape shape = s.defaultShape;
        String image = s.defaultImage;
        int color = s.defaultColor;
        if (args.length > i) {
            shape = Shape.parse(args[i]);
            if (shape == null) {
                sender.sendMessage(s.msg("unknown-shape", Placeholder.unparsed("value", args[i])));
                return;
            }
            i++;
        }
        if (args.length > i) {
            image = image(sender, args[i]);
            if (image == null) {
                return;
            }
            i++;
        }
        if (args.length > i) {
            Integer c = color(sender, args[i]);
            if (c == null) {
                return;
            }
            color = c;
        }
        Wand.State st = new Wand.State(shape, image, color);
        Player to = target;
        to.getInventory().addItem(plugin.wand().create(s, st)).values()
            .forEach(left -> to.getWorld().dropItem(to.getLocation(), left));
        sender.sendMessage(s.msg("given", resolver(st.shape(), st.image(), st.color()),
            Placeholder.unparsed("name", target.getName())));
    }

    private TagResolver resolver(Shape shape, String image, int color) {
        Settings s = plugin.settings();
        return TagResolver.resolver(Placeholder.unparsed("shape", shape.id()),
            Placeholder.unparsed("image", s.imageName(image)), Placeholder.unparsed("color", Colors.hex(color)),
            Settings.colorTag(color));
    }

    private String image(CommandSender sender, String raw) {
        Settings s = plugin.settings();
        String v = raw.toLowerCase(Locale.ROOT);
        if (s.images.contains(v)) {
            return v;
        }
        sender.sendMessage(s.msg("unknown-image", Placeholder.unparsed("value", raw),
            Placeholder.unparsed("list", String.join(", ", s.images))));
        return null;
    }

    private Integer color(CommandSender sender, String raw) {
        Settings s = plugin.settings();
        Integer c = s.parseColor(raw);
        if (c == null) {
            sender.sendMessage(s.msg("unknown-color", Placeholder.unparsed("value", raw),
                Placeholder.unparsed("list", s.colorList())));
        }
        return c;
    }

    private void onTarget(CommandSender sender, String[] args, String what) {
        Settings s = plugin.settings();
        if (!(sender instanceof Player p)) {
            sender.sendMessage(s.msg("players-only"));
            return;
        }
        boolean needsArg = what.equals("color") || what.equals("image") || what.equals("shape");
        if (needsArg && args.length < 2) {
            for (Component c : s.lines("help")) {
                sender.sendMessage(c);
            }
            return;
        }
        ItemDisplay d = plugin.effects().target(p);
        if (d == null) {
            p.sendMessage(s.msg("nothing-targeted", Placeholder.unparsed("distance", WandListener.fmt(s.maxDistance))));
            return;
        }
        Effects fxs = plugin.effects();
        EffectData e = fxs.data(d);
        switch (what) {
            case "color" -> {
                Integer c = color(p, args[1]);
                if (c == null) {
                    return;
                }
                fxs.update(d, e.withColor(c));
                p.sendMessage(s.msg("color-set", Placeholder.unparsed("color", Colors.hex(c)), Settings.colorTag(c)));
            }
            case "image" -> {
                String img = image(p, args[1]);
                if (img == null) {
                    return;
                }
                fxs.update(d, e.withImage(img));
                p.sendMessage(s.msg("image-set", Placeholder.unparsed("image", s.imageName(img))));
            }
            case "shape" -> {
                Shape sh = Shape.parse(args[1]);
                if (sh == null) {
                    p.sendMessage(s.msg("unknown-shape", Placeholder.unparsed("value", args[1])));
                    return;
                }
                EffectData n = e.withShape(sh);
                Effects.PlaceResult r = fxs.check(d.getWorld(), n, d);
                if (r != Effects.PlaceResult.OK) {
                    p.sendMessage(s.msg(r == Effects.PlaceResult.NO_SUPPORT ? "no-support" : "shape-blocked"));
                    return;
                }
                fxs.update(d, n);
                p.sendMessage(s.msg("shape-set", Placeholder.unparsed("shape", sh.id())));
            }
            case "remove" -> {
                fxs.remove(d);
                p.sendMessage(s.msg("removed"));
            }
            default -> {
                p.sendMessage(s.msg("info", resolver(e.shape(), e.image(), e.color()),
                    Placeholder.unparsed("x", String.valueOf(e.bx())), Placeholder.unparsed("y", String.valueOf(e.by())),
                    Placeholder.unparsed("z", String.valueOf(e.bz())), Placeholder.unparsed("face", e.face().id()),
                    Placeholder.unparsed("rim", e.rim() ? "on" : "off")));
                if (fxs.hasActions(d)) {
                    this.fx.list(p, d);
                }
            }
        }
    }

    private void list(CommandSender sender) {
        Settings s = plugin.settings();
        List<String> names = new ArrayList<>();
        for (String img : s.images) {
            names.add(img + " (" + s.imageName(img) + ")");
        }
        sender.sendMessage(s.msg("list-header", Placeholder.unparsed("images", String.join(", ", names)),
            Placeholder.unparsed("colors", s.colorList())));
        List<ItemDisplay> found = new ArrayList<>();
        String radius;
        if (sender instanceof Player p) {
            found.addAll(plugin.effects().near(p.getLocation(), 32.0));
            radius = "32";
        } else {
            for (World w : Bukkit.getWorlds()) {
                found.addAll(plugin.effects().all(w));
            }
            radius = "*";
        }
        sender.sendMessage(s.msg("list-count", Placeholder.unparsed("radius", radius),
            Placeholder.unparsed("count", String.valueOf(found.size()))));
        int n = 0;
        for (ItemDisplay d : found) {
            if (n++ >= 15) {
                break;
            }
            EffectData e = plugin.effects().data(d);
            sender.sendMessage(s.plain("list-entry", resolver(e.shape(), e.image(), e.color()),
                Placeholder.unparsed("x", String.valueOf(e.bx())), Placeholder.unparsed("y", String.valueOf(e.by())),
                Placeholder.unparsed("z", String.valueOf(e.bz()))));
        }
    }

    private void removeAll(CommandSender sender, String[] args) {
        Settings s = plugin.settings();
        List<ItemDisplay> found = new ArrayList<>();
        boolean all = args.length >= 2 && args[1].equalsIgnoreCase("all");
        if (args.length >= 2 && !all && sender instanceof Player p) {
            Double r = Args.parseRadius(args[1]);
            if (r == null) {
                sender.sendMessage(s.msg("bad-radius", Placeholder.unparsed("value", args[1])));
                return;
            }
            found.addAll(plugin.effects().near(p.getLocation(), r));
        } else if (sender instanceof Player p) {
            if (!all) {
                sender.sendMessage(s.msg("removeall-confirm"));
                return;
            }
            found.addAll(plugin.effects().all(p.getWorld()));
        } else {
            for (World w : Bukkit.getWorlds()) {
                found.addAll(plugin.effects().all(w));
            }
        }
        for (ItemDisplay d : found) {
            plugin.effects().remove(d);
        }
        plugin.effects().verifyAll();
        sender.sendMessage(s.msg("removed-all", Placeholder.unparsed("count", String.valueOf(found.size()))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("coreshaders.admin")) {
            return List.of();
        }
        Settings s = plugin.settings();
        List<String> opts = new ArrayList<>();
        if (args.length == 1) {
            opts.addAll(SUBS);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            int n = args.length;
            switch (sub) {
                case "give" -> {
                    if (n == 2) {
                        Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
                        opts.addAll(List.of("cube", "plane"));
                    } else {
                        boolean hasPlayer = Bukkit.getPlayerExact(args[1]) != null;
                        int pos = hasPlayer ? n - 2 : n - 1;
                        if (pos == 1) {
                            opts.addAll(List.of("cube", "plane"));
                        } else if (pos == 2) {
                            opts.addAll(s.images);
                        } else if (pos == 3) {
                            opts.addAll(s.colors.keySet());
                        }
                    }
                }
                case "color" -> {
                    if (n == 2) {
                        opts.addAll(s.colors.keySet());
                        opts.add("#FFFFFF");
                    }
                }
                case "image" -> {
                    if (n == 2) {
                        opts.addAll(s.images);
                    }
                }
                case "shape" -> {
                    if (n == 2) {
                        opts.addAll(List.of("cube", "plane"));
                    }
                }
                case "removeall" -> {
                    if (n == 2) {
                        opts.addAll(List.of("8", "16", "32", "all"));
                    }
                }
                case "cmd", "trigger", "cooldown", "rim" -> {
                    int max = 1;
                    if (sender instanceof Player p && n == 3 && sub.equals("cmd")) {
                        ItemDisplay d = plugin.effects().target(p);
                        if (d != null) {
                            max = plugin.effects().actions(d).actions().size();
                        }
                    }
                    opts.addAll(EffectCommands.complete(sub, args, max,
                        sender.hasPermission(EffectCommands.CONSOLE_PERMISSION)));
                }
                case "upload" -> {
                    if (n == 2) {
                        opts.add("<name>");
                    } else if (n == 3) {
                        opts.add("https://");
                    }
                }
                case "import" -> {
                    if (n == 2) {
                        opts.addAll(plugin.images().importable());
                    }
                }
                case "delete-image" -> {
                    if (n == 2) {
                        opts.addAll(s.customKinds.keySet());
                    }
                }
                default -> {
                }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) {
            if (o.toLowerCase(Locale.ROOT).startsWith(last)) {
                out.add(o);
            }
        }
        return out;
    }
}
