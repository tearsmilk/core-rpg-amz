package ink.hoka.odyssey.coreshaders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

/**
 * /hocs — всё управление эффектами (только операторы и консоль). Разбор аргументов — вручную, как в исходном
 * плагине (/cs): у подкоманд много необязательных хвостов (group, ник), а подсказки считаются по уже введённому.
 */
final class HoCsCommand extends Command {
    static final List<String> SUBS = List.of("give", "color", "image", "shape", "remove", "info", "list",
        "removeall", "cmd", "trigger", "cooldown", "rim", "upload", "import", "delete-image", "reload", "help");
    static final List<String> GROUP_WORDS = List.of("group", "-g", "группа");
    static final List<String> CMD_SUBS = List.of("add", "list", "remove", "clear", "perm", "msg");

    private final CoreShadersModule m;

    HoCsCommand(CoreShadersModule m) {
        super("hocs");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));
        setDefaultExecutor((s, c) -> help(s));
        ArgumentStringArray args = ArgumentType.StringArray("аргументы");
        args.setSuggestionCallback((sender, ctx, sug) -> {
            String input = sug.getInput().replace("\0", "");
            String rest = input.length() > getName().length() ? input.substring(input.indexOf(' ') + 1) : "";
            List<String> parts = new ArrayList<>(Arrays.asList(rest.split(" ", -1)));
            String last = parts.isEmpty() ? "" : parts.get(parts.size() - 1).toLowerCase(Locale.ROOT);
            for (String o : complete(sender, parts.toArray(String[]::new))) {
                if (o.toLowerCase(Locale.ROOT).startsWith(last)) {
                    sug.addEntry(new SuggestionEntry(o));
                }
            }
        });
        addSyntax((s, c) -> run(s, c.get(args)), args);
    }

    private Settings s() {
        return m.settings();
    }

    private void help(CommandSender sender) {
        for (Component c : s().lines("help", false)) {
            sender.sendMessage(c);
        }
    }

    private void run(CommandSender sender, String[] args) {
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
            case "cmd", "command", "команда" -> cmd(sender, args);
            case "trigger", "триггер" -> trigger(sender, args);
            case "cooldown", "кд" -> cooldown(sender, args);
            case "rim", "обод" -> rim(sender, args);
            case "upload" -> {
                if (args.length < 3) {
                    sender.sendMessage(s().msg("upload-usage"));
                } else {
                    m.images().upload(sender, args[1], args[2]);
                }
            }
            case "import" -> {
                if (args.length < 2) {
                    sender.sendMessage(s().msg("import-usage", "folder", m.images().folderName(),
                        "list", String.join(", ", m.images().importable())));
                } else {
                    m.images().importFile(sender, args[1]);
                }
            }
            case "delete-image", "deleteimage" -> {
                if (args.length < 2) {
                    sender.sendMessage(s().msg("delete-usage", "list", String.join(", ", s().customKinds.keySet())));
                } else {
                    m.images().delete(sender, args[1]);
                }
            }
            case "reload" -> {
                m.reloadSettings();
                sender.sendMessage(s().msg("reloaded"));
            }
            default -> help(sender);
        }
    }

    // ───────────────────────────── жезл ─────────────────────────────

    private static Player online(String name) {
        return MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
    }

    private void give(CommandSender sender, String[] args) {
        Settings s = s();
        int i = 1;
        Player target = null;
        if (args.length > i) {
            Player p = online(args[i]);
            if (p != null) {
                target = p;
                i++;
            } else if (Shape.parse(args[i]) == null) {
                sender.sendMessage(s.msg("player-not-found", "name", args[i]));
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
                sender.sendMessage(s.msg("unknown-shape", "value", args[i]));
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
        if (!target.getInventory().addItemStack(Wand.create(s, st))) {
            sender.sendMessage(s.msg("inventory-full", "name", target.getUsername()));
            return;
        }
        sender.sendMessage(s.msg("given", "shape", st.shape().id(), "image", s.imageName(st.image()),
            "color", Settings.color(st.color()), "name", target.getUsername()));
    }

    private String image(CommandSender sender, String raw) {
        String v = raw.toLowerCase(Locale.ROOT);
        if (s().images.contains(v)) {
            return v;
        }
        sender.sendMessage(s().msg("unknown-image", "value", raw, "list", String.join(", ", s().images)));
        return null;
    }

    private Integer color(CommandSender sender, String raw) {
        Integer c = s().parseColor(raw);
        if (c == null) {
            sender.sendMessage(s().msg("unknown-color", "value", raw, "list", s().colorList()));
        }
        return c;
    }

    // ───────────────────────────── эффект под прицелом ─────────────────────────────

    private record Target(EffectWorld world, Effect effect, List<Effect> all, boolean group) {
    }

    private Target target(CommandSender sender, boolean group) {
        Settings s = s();
        if (!(sender instanceof Player p)) {
            sender.sendMessage(s.msg("players-only"));
            return null;
        }
        Effect e = m.effects().target(p);
        EffectWorld w = e == null ? null : m.effects().world(p.getInstance());
        if (e == null || w == null) {
            p.sendMessage(s.msg("nothing-targeted", "distance", Interactions.fmt(s.maxDistance)));
            return null;
        }
        return new Target(w, e, group ? m.effects().group(w, e, s.maxGroup) : List.of(e), group);
    }

    private void onTarget(CommandSender sender, String[] args, String what) {
        Settings s = s();
        boolean needsArg = what.equals("color") || what.equals("image") || what.equals("shape");
        if (needsArg && args.length < 2) {
            help(sender);
            return;
        }
        Target t = target(sender, false);
        if (t == null) {
            return;
        }
        Effects fx = m.effects();
        EffectData e = t.effect().data;
        switch (what) {
            case "color" -> {
                Integer c = color(sender, args[1]);
                if (c == null) {
                    return;
                }
                fx.update(t.world(), t.effect(), e.withColor(c));
                sender.sendMessage(s.msg("color-set", "color", Settings.color(c)));
            }
            case "image" -> {
                String img = image(sender, args[1]);
                if (img == null) {
                    return;
                }
                fx.update(t.world(), t.effect(), e.withImage(img));
                sender.sendMessage(s.msg("image-set", "image", s.imageName(img)));
            }
            case "shape" -> {
                Shape sh = Shape.parse(args[1]);
                if (sh == null) {
                    sender.sendMessage(s.msg("unknown-shape", "value", args[1]));
                    return;
                }
                EffectData n = e.withShape(sh);
                Effects.PlaceResult r = fx.check(t.world(), n, t.effect());
                if (r != Effects.PlaceResult.OK) {
                    sender.sendMessage(s.msg(r == Effects.PlaceResult.NO_SUPPORT ? "no-support" : "shape-blocked"));
                    return;
                }
                fx.update(t.world(), t.effect(), n);
                sender.sendMessage(s.msg("shape-set", "shape", sh.id()));
            }
            case "remove" -> {
                fx.remove(t.world(), t.effect());
                sender.sendMessage(s.msg("removed"));
            }
            default -> {
                sender.sendMessage(s.msg("info", "shape", e.shape().id(), "image", s.imageName(e.image()),
                    "color", Settings.color(e.color()), "x", e.bx(), "y", e.by(), "z", e.bz(), "face", e.face().id(),
                    "rim", e.rim() ? "on" : "off"));
                if (t.effect().hasActions()) {
                    listActions(sender, t.world(), t.effect());
                }
            }
        }
    }

    private void list(CommandSender sender) {
        Settings s = s();
        List<String> names = new ArrayList<>();
        for (String img : s.images) {
            names.add(img + " (" + s.imageName(img) + ")");
        }
        sender.sendMessage(s.msg("list-header", "images", String.join(", ", names), "colors", s.colorList()));
        List<Effect> found = new ArrayList<>();
        List<String> maps = new ArrayList<>();
        String radius;
        if (sender instanceof Player p) {
            EffectWorld w = m.effects().world(p.getInstance());
            if (w != null) {
                var pos = p.getPosition();
                for (Effect e : w.near(pos.x(), pos.y(), pos.z(), 32.0)) {
                    found.add(e);
                    maps.add(w.map);
                }
            }
            radius = "32";
        } else {
            for (EffectWorld w : m.effects().worlds()) {
                for (Effect e : w.all()) {
                    found.add(e);
                    maps.add(w.map);
                }
            }
            radius = "*";
        }
        sender.sendMessage(s.msg("list-count", "radius", radius, "count", found.size()));
        for (int i = 0; i < Math.min(15, found.size()); i++) {
            EffectData e = found.get(i).data;
            sender.sendMessage(s.plain("list-entry", "shape", e.shape().id(), "image", s.imageName(e.image()),
                "color", Settings.color(e.color()), "map", maps.get(i), "x", e.bx(), "y", e.by(), "z", e.bz()));
        }
    }

    private void removeAll(CommandSender sender, String[] args) {
        Settings s = s();
        boolean all = args.length >= 2 && args[1].equalsIgnoreCase("all");
        int count = 0;
        if (sender instanceof Player p) {
            EffectWorld w = m.effects().world(p.getInstance());
            List<Effect> found = new ArrayList<>();
            if (args.length >= 2 && !all) {
                Double r = Args.parseRadius(args[1]);
                if (r == null) {
                    sender.sendMessage(s.msg("bad-radius", "value", args[1]));
                    return;
                }
                if (w != null) {
                    var pos = p.getPosition();
                    found.addAll(w.near(pos.x(), pos.y(), pos.z(), r));
                }
            } else if (!all) {
                sender.sendMessage(s.msg("removeall-confirm"));
                return;
            } else if (w != null) {
                found.addAll(w.all());
            }
            for (Effect e : found) {
                m.effects().remove(w, e);
            }
            count = found.size();
        } else {
            if (!all) {
                sender.sendMessage(s.msg("removeall-confirm"));
                return;
            }
            for (EffectWorld w : m.effects().worlds()) {
                for (Effect e : List.copyOf(w.all())) {
                    m.effects().remove(w, e);
                    count++;
                }
            }
        }
        sender.sendMessage(s.msg("removed-all", "count", count));
    }

    // ───────────────────────────── команды на эффектах ─────────────────────────────

    static boolean isGroupWord(String s) {
        return s != null && GROUP_WORDS.contains(s.toLowerCase(Locale.ROOT));
    }

    static String[] stripGroup(String[] args) {
        if (args.length > 0 && isGroupWord(args[args.length - 1])) {
            return Arrays.copyOf(args, args.length - 1);
        }
        return args;
    }

    private int apply(Target t, UnaryOperator<Actions.Data> op) {
        String gid = t.group() ? t.effect().id.toString() : null;
        for (Effect e : t.all()) {
            Actions.Data a = m.effects().actions(e);
            Actions.Data n = op.apply(a).withGroup(gid == null ? a.group() : gid);
            m.effects().setActions(t.world(), e, n);
        }
        return t.all().size();
    }

    private void sendScope(CommandSender sender, String key, Target t, String value) {
        sender.sendMessage(s().msg(key, "value", value, "count", t.all().size(),
            "scope", s().raw(t.group() ? "scope-group" : "scope-one")));
    }

    private void cmd(CommandSender sender, String[] full) {
        Settings s = s();
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
                List<Actions.Action> own = m.effects().actions(t.effect()).actions();
                if (idx < 1 || idx > own.size()) {
                    sender.sendMessage(s.msg("cmd-bad-index", "value", args[2], "count", own.size()));
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
                String v = args[2].toLowerCase(Locale.ROOT);
                String perm;
                if (v.equals("off") || v.equals("none") || v.equals("all") || v.equals("все")) {
                    perm = null;
                } else if (v.equals(Triggers.OP)) {
                    perm = Triggers.OP;
                } else {
                    sender.sendMessage(s.msg("cmd-usage"));
                    return;
                }
                Target t = target(sender, group);
                if (t == null) {
                    return;
                }
                apply(t, a -> a.withPermission(perm));
                sendScope(sender, "cmd-perm-set", t, perm == null ? "все" : "операторы");
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
                if (t != null) {
                    listActions(sender, t.world(), t.effect());
                }
            }
        }
    }

    private void listActions(CommandSender sender, EffectWorld w, Effect e) {
        Settings s = s();
        Actions.Data a = m.effects().actions(e);
        int size = m.effects().group(w, e, s.maxGroup).size();
        sender.sendMessage(s.msg("cmd-header", "trigger", a.trigger().id(), "cooldown", Interactions.fmt(a.cooldown()),
            "permission", a.permission() == null ? "все" : "операторы", "message", a.message() == null ? "-" : a.message(),
            "group", size, "count", a.actions().size()));
        int i = 1;
        for (Actions.Action act : a.actions()) {
            sender.sendMessage(s.plain("cmd-entry", "index", i++, "executor", act.executor().id(), "command", act.command()));
        }
    }

    private void trigger(CommandSender sender, String[] full) {
        boolean group = full.length > 2 && isGroupWord(full[full.length - 1]);
        String[] args = stripGroup(full);
        Actions.Trigger tr = args.length >= 2 ? Actions.Trigger.parse(args[1]) : null;
        if (tr == null) {
            sender.sendMessage(s().msg("trigger-usage"));
            return;
        }
        Target t = target(sender, group);
        if (t == null) {
            return;
        }
        apply(t, a -> a.withTrigger(tr));
        sendScope(sender, "trigger-set", t, tr.id());
    }

    private void cooldown(CommandSender sender, String[] full) {
        boolean group = full.length > 2 && isGroupWord(full[full.length - 1]);
        String[] args = stripGroup(full);
        Double cd = args.length >= 2 ? Actions.parseCooldown(args[1]) : null;
        if (cd == null) {
            sender.sendMessage(s().msg("cooldown-usage", "value", args.length >= 2 ? args[1] : ""));
            return;
        }
        Target t = target(sender, group);
        if (t == null) {
            return;
        }
        apply(t, a -> a.withCooldown(cd));
        sendScope(sender, "cooldown-set", t, Interactions.fmt(cd));
    }

    private void rim(CommandSender sender, String[] full) {
        boolean group = full.length > 2 && isGroupWord(full[full.length - 1]);
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
            sender.sendMessage(s().msg("rim-usage"));
            return;
        }
        Target t = target(sender, group);
        if (t == null) {
            return;
        }
        boolean v = on;
        for (Effect e : t.all()) {
            if (e.data.rim() != v) {
                m.effects().update(t.world(), e, e.data.withRim(v));
            }
        }
        sendScope(sender, "rim-set", t, v ? "on" : "off");
    }

    // ───────────────────────────── подсказки ─────────────────────────────

    List<String> complete(CommandSender sender, String[] args) {
        Settings s = s();
        List<String> opts = new ArrayList<>();
        int n = args.length;
        if (n <= 1) {
            opts.addAll(SUBS);
            return opts;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> {
                if (n == 2) {
                    MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(p -> opts.add(p.getUsername()));
                    opts.addAll(List.of("cube", "plane"));
                } else {
                    int pos = online(args[1]) != null ? n - 2 : n - 1;
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
                    Effect e = m.effects().target(p);
                    if (e != null) {
                        max = m.effects().actions(e).actions().size();
                    }
                }
                opts.addAll(completeEffect(sub, args, max));
            }
            case "upload" -> {
                if (n == 2) {
                    opts.add("<имя>");
                } else if (n == 3) {
                    opts.add("https://");
                }
            }
            case "import" -> {
                if (n == 2) {
                    opts.addAll(m.images().importable());
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
        return opts;
    }

    static List<String> completeEffect(String sub, String[] args, int maxIndex) {
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
                            opts.addAll(List.of("player", "console"));
                        } else if (n == 4) {
                            opts.addAll(List.of("homap", "hoship", "homap tp {world} {player}"));
                        } else {
                            opts.addAll(List.of("{player}", "{world}", "{x}", "{y}", "{z}", "group"));
                        }
                    } else if (c.equals("remove") && n == 3) {
                        for (int i = 1; i <= Math.max(1, maxIndex); i++) {
                            opts.add(String.valueOf(i));
                        }
                    } else if (c.equals("perm") && n == 3) {
                        opts.addAll(List.of("op", "off"));
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
