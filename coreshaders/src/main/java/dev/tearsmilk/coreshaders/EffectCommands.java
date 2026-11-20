package dev.tearsmilk.coreshaders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;

public final class EffectCommands {
    public static final List<String> GROUP_WORDS = List.of("group", "-g", "группа");
    public static final String CONSOLE_PERMISSION = "coreshaders.commands.console";
    public static final List<String> CMD_SUBS = List.of("add", "list", "remove", "clear", "perm", "msg");

    private final CoreShadersPlugin plugin;

    public EffectCommands(CoreShadersPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isGroupWord(String s) {
        return s != null && GROUP_WORDS.contains(s.toLowerCase(Locale.ROOT));
    }

    public static String[] stripGroup(String[] args) {
        if (args.length > 0 && isGroupWord(args[args.length - 1])) {
            return Arrays.copyOf(args, args.length - 1);
        }
        return args;
    }

    private record Target(ItemDisplay display, List<ItemDisplay> all, boolean group) {
    }

    private Target target(CommandSender sender, boolean group) {
        Settings s = plugin.settings();
        if (!(sender instanceof Player p)) {
            sender.sendMessage(s.msg("players-only"));
            return null;
        }
        ItemDisplay d = plugin.effects().target(p);
        if (d == null) {
            p.sendMessage(s.msg("nothing-targeted", Placeholder.unparsed("distance", WandListener.fmt(s.maxDistance))));
            return null;
        }
        List<ItemDisplay> all = group ? plugin.effects().group(d, s.maxGroup) : List.of(d);
        return new Target(d, all, group);
    }

    private int apply(Target t, UnaryOperator<Actions.Data> op) {
        String gid = t.group() ? t.display().getUniqueId().toString() : null;
        for (ItemDisplay d : t.all()) {
            Actions.Data a = plugin.effects().actions(d);
            Actions.Data n = op.apply(a).withGroup(gid == null ? a.group() : gid);
            plugin.effects().setActions(d, n);
        }
        return t.all().size();
    }

    private void sendScope(CommandSender sender, String key, Target t, String value) {
        sender.sendMessage(plugin.settings().msg(key, Placeholder.unparsed("value", value),
            Placeholder.unparsed("count", String.valueOf(t.all().size())),
            Placeholder.parsed("scope", plugin.settings().rawText(t.group() ? "scope-group" : "scope-one"))));
    }

    public void cmd(CommandSender sender, String[] full) {
        Settings s = plugin.settings();
        boolean group = full.length > 2 && isGroupWord(full[full.length - 1]);
        String[] args = stripGroup(full);
        String sub = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add" -> {
                Actions.Executor ex = args.length >= 3 ? Actions.Executor.parse(args[2]) : null;
                String text = args.length >= 4 ? Actions.clean(String.join(" ", Arrays.copyOfRange(args, 3, args.length)))
                    : "";
                if (ex == null || text.isEmpty()) {
                    sender.sendMessage(s.msg("cmd-usage"));
                    return;
                }
                if (ex == Actions.Executor.CONSOLE && !sender.hasPermission(CONSOLE_PERMISSION)) {
                    sender.sendMessage(s.msg("cmd-console-denied", Placeholder.unparsed("permission", CONSOLE_PERMISSION)));
                    return;
                }
                Target t = target(sender, group);
                if (t == null) {
                    return;
                }
                Actions.Action act = new Actions.Action(ex, text);
                apply(t, a -> {
                    List<Actions.Action> l = new ArrayList<>(a.actions());
                    if (!l.contains(act)) {
                        l.add(act);
                    }
                    Actions.Data n = a.withActions(l);
                    return n.trigger() == Actions.Trigger.OFF ? n.withTrigger(s.defaultTrigger) : n;
                });
                sendScope(sender, "cmd-added", t, ex.id() + ": " + text);
            }
            case "remove" -> {
                Integer idx = null;
                if (args.length >= 3) {
                    try {
                        idx = Integer.parseInt(args[2].trim());
                    } catch (NumberFormatException e) {
                        idx = null;
                    }
                }
                if (idx == null) {
                    sender.sendMessage(s.msg("cmd-usage"));
                    return;
                }
                Target t = target(sender, group);
                if (t == null) {
                    return;
                }
                List<Actions.Action> own = plugin.effects().actions(t.display()).actions();
                if (idx < 1 || idx > own.size()) {
                    sender.sendMessage(s.msg("cmd-bad-index", Placeholder.unparsed("value", args[2]),
                        Placeholder.unparsed("count", String.valueOf(own.size()))));
                    return;
                }
                Actions.Action gone = own.get(idx - 1);
                apply(t, a -> {
                    List<Actions.Action> l = new ArrayList<>(a.actions());
                    l.remove(gone);
                    return a.withActions(l);
                });
                sendScope(sender, "cmd-removed", t, gone.executor().id() + ": " + gone.command());
            }
            case "clear" -> {
                Target t = target(sender, group);
                if (t == null) {
                    return;
                }
                apply(t, a -> a.withActions(List.of()));
                sendScope(sender, "cmd-cleared", t, "");
            }
            case "perm", "permission" -> {
                if (args.length < 3) {
                    sender.sendMessage(s.msg("cmd-usage"));
                    return;
                }
                String perm = args[2].equalsIgnoreCase("off") || args[2].equalsIgnoreCase("none") ? null : args[2];
                Target t = target(sender, group);
                if (t == null) {
                    return;
                }
                apply(t, a -> a.withPermission(perm));
                sendScope(sender, "cmd-perm-set", t, perm == null ? "-" : perm);
            }
            case "msg", "message" -> {
                if (args.length < 3) {
                    sender.sendMessage(s.msg("cmd-usage"));
                    return;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
                String msg = text.equalsIgnoreCase("off") || text.equalsIgnoreCase("none") ? null : text;
                Target t = target(sender, group);
                if (t == null) {
                    return;
                }
                apply(t, a -> a.withMessage(msg));
                sendScope(sender, "cmd-msg-set", t, msg == null ? "-" : msg);
            }
            default -> {
                Target t = target(sender, false);
                if (t == null) {
                    return;
                }
                list(sender, t.display());
            }
        }
    }

    public void list(CommandSender sender, ItemDisplay d) {
        Settings s = plugin.settings();
        Actions.Data a = plugin.effects().actions(d);
        int size = plugin.effects().group(d, s.maxGroup).size();
        sender.sendMessage(s.msg("cmd-header", Placeholder.unparsed("trigger", a.trigger().id()),
            Placeholder.unparsed("cooldown", WandListener.fmt(a.cooldown())),
            Placeholder.unparsed("permission", a.permission() == null ? "-" : a.permission()),
            Placeholder.unparsed("message", a.message() == null ? "-" : a.message()),
            Placeholder.unparsed("group", String.valueOf(size)),
            Placeholder.unparsed("count", String.valueOf(a.actions().size()))));
        int i = 1;
        for (Actions.Action act : a.actions()) {
            sender.sendMessage(s.plain("cmd-entry", Placeholder.unparsed("index", String.valueOf(i++)),
                Placeholder.unparsed("executor", act.executor().id()), Placeholder.unparsed("command", act.command())));
        }
    }

    public void trigger(CommandSender sender, String[] full) {
        Settings s = plugin.settings();
        boolean group = isGroupWord(full[full.length - 1]) && full.length > 2;
        String[] args = stripGroup(full);
        Actions.Trigger tr = args.length >= 2 ? Actions.Trigger.parse(args[1]) : null;
        if (tr == null) {
            sender.sendMessage(s.msg("trigger-usage"));
            return;
        }
        Target t = target(sender, group);
        if (t == null) {
            return;
        }
        apply(t, a -> a.withTrigger(tr));
        sendScope(sender, "trigger-set", t, tr.id());
    }

    public void cooldown(CommandSender sender, String[] full) {
        Settings s = plugin.settings();
        boolean group = isGroupWord(full[full.length - 1]) && full.length > 2;
        String[] args = stripGroup(full);
        Double cd = args.length >= 2 ? Actions.parseCooldown(args[1]) : null;
        if (cd == null) {
            sender.sendMessage(s.msg("cooldown-usage", Placeholder.unparsed("value", args.length >= 2 ? args[1] : "")));
            return;
        }
        Target t = target(sender, group);
        if (t == null) {
            return;
        }
        apply(t, a -> a.withCooldown(cd));
        sendScope(sender, "cooldown-set", t, WandListener.fmt(cd));
    }

    public void rim(CommandSender sender, String[] full) {
        Settings s = plugin.settings();
        boolean group = isGroupWord(full[full.length - 1]) && full.length > 2;
        String[] args = stripGroup(full);
        Boolean on = null;
        if (args.length >= 2) {
            String v = args[1].toLowerCase(Locale.ROOT);
            if (v.equals("on") || v.equals("вкл") || v.equals("true")) {
                on = true;
            } else if (v.equals("off") || v.equals("выкл") || v.equals("false")) {
                on = false;
            }
        }
        if (on == null) {
            sender.sendMessage(s.msg("rim-usage"));
            return;
        }
        Target t = target(sender, group);
        if (t == null) {
            return;
        }
        boolean v = on;
        for (ItemDisplay d : t.all()) {
            EffectData e = plugin.effects().data(d);
            if (e.rim() != v) {
                plugin.effects().update(d, e.withRim(v));
            }
        }
        sendScope(sender, "rim-set", t, v ? "on" : "off");
    }

    public static List<String> complete(String sub, String[] args, int maxIndex, boolean console) {
        List<String> opts = new ArrayList<>();
        int n = args.length;
        switch (sub) {
            case "cmd" -> {
                if (n == 2) {
                    opts.addAll(CMD_SUBS);
                } else {
                    String c = args[1].toLowerCase(Locale.ROOT);
                    if (c.equals("add")) {
                        if (n == 3) {
                            opts.add("player");
                            if (console) {
                                opts.add("console");
                            }
                        } else if (n == 4) {
                            opts.addAll(List.of("warp", "spawn", "tp", "spawn {player}", "warp spawn {player}"));
                        } else {
                            opts.addAll(List.of("{player}", "{world}", "{x}", "{y}", "{z}", "group"));
                        }
                    } else if (c.equals("remove") && n == 3) {
                        for (int i = 1; i <= Math.max(1, maxIndex); i++) {
                            opts.add(String.valueOf(i));
                        }
                    } else if (c.equals("perm") && n == 3) {
                        opts.addAll(List.of("off", "coreshaders.portal"));
                    } else if (c.equals("msg") && n == 3) {
                        opts.add("off");
                    } else if ((c.equals("remove") && n == 4) || (c.equals("clear") && n == 3)
                        || ((c.equals("perm") || c.equals("msg")) && n >= 4)) {
                        opts.add("group");
                    }
                }
            }
            case "trigger" -> {
                if (n == 2) {
                    opts.addAll(List.of("enter", "click", "both", "off"));
                } else if (n == 3) {
                    opts.add("group");
                }
            }
            case "cooldown" -> {
                if (n == 2) {
                    opts.addAll(List.of("0", "1", "2", "5", "10"));
                } else if (n == 3) {
                    opts.add("group");
                }
            }
            case "rim" -> {
                if (n == 2) {
                    opts.addAll(List.of("on", "off"));
                } else if (n == 3) {
                    opts.add("group");
                }
            }
            default -> {
            }
        }
        return opts;
    }
}
