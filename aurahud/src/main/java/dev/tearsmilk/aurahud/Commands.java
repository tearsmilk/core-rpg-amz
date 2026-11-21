package dev.tearsmilk.aurahud;

import dev.tearsmilk.aurahud.layout.Element;
import dev.tearsmilk.aurahud.layout.ElementSettings;
import dev.tearsmilk.aurahud.layout.LayoutModule;
import dev.tearsmilk.aurahud.layout.Placement;
import dev.tearsmilk.aurahud.layout.Presets;
import dev.tearsmilk.aurahud.layout.Setting;
import dev.tearsmilk.aurahud.sprites.SpriteMarker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Commands implements TabExecutor {

    private static final List<String> SUB = List.of("help", "status", "reload", "preset", "set", "hide", "show", "reset");
    private static final Pattern SLOT = Pattern.compile("slot\\.([1-9])\\.(x|y|hidden)");

    private final AuraHudPlugin plugin;

    public Commands(AuraHudPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Texts t = plugin.texts();
        if (!sender.hasPermission("aurahud.admin")) {
            t.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadAll();
                t.send(sender, "reloaded");
            }
            case "status" -> status(sender);
            case "preset" -> preset(sender, args);
            case "set" -> set(sender, args);
            case "hide", "show" -> toggle(sender, args, sub.equals("show"));
            case "reset" -> reset(sender, args);
            default -> t.send(sender, "help");
        }
        return true;
    }

    private void status(CommandSender sender) {
        Texts t = plugin.texts();
        ShaderHooks.Report r = plugin.pack();
        String state = r == null ? t.raw("pack-pending") : r.summary();
        if (plugin.installing()) {
            state = t.raw("pack-installing") + " " + state;
        }
        t.send(sender, "status-shaderhooks", "state", state);
        List<String> ids = new ArrayList<>();
        for (HudModule m : plugin.modules().all()) {
            ids.add(m.id());
        }
        t.send(sender, "status-modules", "list", ids.isEmpty() ? t.raw("no-modules") : String.join(", ", ids));
        LayoutModule layout = plugin.layout();
        t.send(sender, layout.active() ? "status-layout-active" : "status-layout-vanilla");
        for (Element e : Element.values()) {
            Placement p = layout.placements().get(e);
            ElementSettings s = layout.settings().get(e);
            switch (p.mode()) {
                case VANILLA -> t.send(sender, "status-element-vanilla", "element", e.id());
                case HIDDEN -> t.send(sender, "status-element-hidden", "element", e.id());
                case MOVED -> t.send(sender, "status-element-moved", "element", e.id(), "anchor",
                        e == Element.OFFHAND && s.follow() ? "hotbar" : s.anchor().key(),
                        "x", String.valueOf(s.x()), "y", String.valueOf(s.y()),
                        "layout", e.shape() == Element.Shape.ICONS ? s.layout().key() + " " + s.direction().key() + "/" + s.wrap().key()
                                + " x" + s.perLine() : e == Element.XP ? "rotate " + s.rotate() : "-");
            }
        }
        SpriteMarker.Result m = layout.markers();
        if (m != null) {
            t.send(sender, "status-markers", "changed", String.valueOf(m.changed()), "total", String.valueOf(m.sprites()),
                    "bad", m.unmarkable().isEmpty() ? t.raw("none") : String.join(", ", m.unmarkable()));
        }
        List<String> warnings = layout.status();
        if (!warnings.isEmpty()) {
            t.send(sender, "status-warnings", "list", String.join("; ", warnings));
        }
    }

    private void preset(CommandSender sender, String[] args) {
        Texts t = plugin.texts();
        Presets presets = plugin.presets();
        if (args.length < 2) {
            t.send(sender, "preset-list", "list", String.join(", ", presets.names()));
            return;
        }
        if (args[1].equalsIgnoreCase("save")) {
            if (args.length < 3 || !Presets.NAME.matcher(args[2].toLowerCase(Locale.ROOT)).matches()
                    || Presets.BUILT_IN.contains(args[2].toLowerCase(Locale.ROOT))) {
                t.send(sender, "preset-save-usage");
                return;
            }
            try {
                presets.save(args[2], plugin.getConfig().getConfigurationSection("hud"), "сохранил " + sender.getName());
                t.send(sender, "preset-saved", "name", args[2].toLowerCase(Locale.ROOT));
            } catch (IOException e) {
                t.send(sender, "preset-save-failed", "error", String.valueOf(e.getMessage()));
            }
            return;
        }
        YamlConfiguration y = presets.load(args[1]);
        if (y == null) {
            t.send(sender, "preset-unknown", "name", args[1], "list", String.join(", ", presets.names()));
            return;
        }
        Presets.copy(y.getConfigurationSection("hud"), plugin.getConfig(), "hud");
        plugin.saveAndApply();
        t.send(sender, "preset-applied", "name", args[1].toLowerCase(Locale.ROOT));
        warnings(sender);
    }

    private void set(CommandSender sender, String[] args) {
        Texts t = plugin.texts();
        if (args.length < 4) {
            t.send(sender, "set-usage");
            return;
        }
        Element e = element(sender, args[1]);
        if (e == null) {
            return;
        }
        FileConfiguration c = plugin.getConfig();
        String base = "hud." + e.id() + ".";
        String rawKey = args[2].toLowerCase(Locale.ROOT);
        String value = args[3];
        Matcher sm = SLOT.matcher(rawKey);
        if (sm.matches()) {
            if (e != Element.HOTBAR) {
                t.send(sender, "key-not-for", "key", rawKey, "element", e.id());
                return;
            }
            Setting type = sm.group(2).equals("hidden") ? Setting.ENABLED : Setting.X;
            Object v = type.value(value);
            if (v == null) {
                t.send(sender, "bad-value", "key", rawKey, "value", value, "hint", String.join(", ", type.suggestions()));
                return;
            }
            c.set(base + "slots." + sm.group(1) + "." + sm.group(2), v);
            done(sender, e, rawKey, String.valueOf(v));
            return;
        }
        Setting s = Setting.parse(rawKey);
        if (s == null) {
            t.send(sender, "unknown-key", "key", rawKey, "list", keys(e));
            return;
        }
        if (!s.appliesTo(e)) {
            t.send(sender, "key-not-for", "key", s.key(), "element", e.id());
            return;
        }
        Object v = s.value(value);
        if (v == null) {
            t.send(sender, "bad-value", "key", s.key(), "value", value, "hint", String.join(", ", s.suggestions()));
            return;
        }
        c.set(base + s.key(), v);
        if (s == Setting.ANCHOR) {
            c.set(base + "x", 0);
            c.set(base + "y", 0);
            if (e == Element.OFFHAND) {
                c.set(base + "follow", "none");
            }
            t.send(sender, "anchor-reset");
        }
        if ((s == Setting.X || s == Setting.Y) && e == Element.OFFHAND && !c.contains(base + "follow")) {
            c.set(base + "follow", "none");
        }
        if (s == Setting.LAYOUT) {
            c.set(base + "direction", null);
            c.set(base + "wrap", null);
            c.set(base + "per-line", null);
            c.set(base + "reverse", null);
        }
        done(sender, e, s.key(), String.valueOf(v));
    }

    private void done(CommandSender sender, Element e, String key, String value) {
        plugin.saveAndApply();
        plugin.texts().send(sender, "set-done", "element", e.id(), "key", key, "value", value);
        warnings(sender);
    }

    private void toggle(CommandSender sender, String[] args, boolean show) {
        if (args.length < 2) {
            plugin.texts().send(sender, "toggle-usage");
            return;
        }
        Element e = element(sender, args[1]);
        if (e == null) {
            return;
        }
        plugin.getConfig().set("hud." + e.id() + ".enabled", show);
        plugin.saveAndApply();
        plugin.texts().send(sender, show ? "shown" : "hidden", "element", e.id());
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.texts().send(sender, "reset-usage");
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            plugin.getConfig().set("hud", null);
            plugin.getConfig().createSection("hud");
            plugin.saveAndApply();
            plugin.texts().send(sender, "reset-done", "element", "all");
            return;
        }
        Element e = element(sender, args[1]);
        if (e == null) {
            return;
        }
        plugin.getConfig().set("hud." + e.id(), null);
        plugin.saveAndApply();
        plugin.texts().send(sender, "reset-done", "element", e.id());
    }

    private void warnings(CommandSender sender) {
        List<String> w = plugin.layout().status();
        if (!w.isEmpty()) {
            plugin.texts().send(sender, "status-warnings", "list", String.join("; ", w));
        }
    }

    private Element element(CommandSender sender, String raw) {
        Element e = Element.parse(raw);
        if (e == null) {
            plugin.texts().send(sender, "unknown-element", "element", raw, "list", elements());
        }
        return e;
    }

    private static String elements() {
        List<String> out = new ArrayList<>();
        for (Element e : Element.values()) {
            out.add(e.id());
        }
        return String.join(", ", out);
    }

    private static String keys(Element e) {
        List<String> out = new ArrayList<>();
        for (Setting s : Setting.of(e)) {
            out.add(s.key());
        }
        if (e == Element.HOTBAR) {
            out.add("slot.<1-9>.x|y|hidden");
        }
        return String.join(", ", out);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("aurahud.admin") || args.length == 0) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUB);
        } else if (args.length == 2 && sub.equals("preset")) {
            options.addAll(plugin.presets().names());
            options.add("save");
        } else if (args.length == 2 && List.of("set", "hide", "show", "reset").contains(sub)) {
            for (Element e : Element.values()) {
                options.add(e.id());
            }
            if (sub.equals("reset")) {
                options.add("all");
            }
        } else if (args.length == 3 && sub.equals("set")) {
            Element e = Element.parse(args[1]);
            if (e != null) {
                for (Setting s : Setting.of(e)) {
                    options.add(s.key());
                }
                if (e == Element.HOTBAR) {
                    for (int i = 1; i <= ElementSettings.SLOTS; i++) {
                        options.add("slot." + i + ".x");
                        options.add("slot." + i + ".y");
                        options.add("slot." + i + ".hidden");
                    }
                }
            }
        } else if (args.length == 4 && sub.equals("set")) {
            String key = args[2].toLowerCase(Locale.ROOT);
            Matcher sm = SLOT.matcher(key);
            Setting s = sm.matches() ? (sm.group(2).equals("hidden") ? Setting.ENABLED : Setting.X) : Setting.parse(key);
            if (s != null) {
                options.addAll(s.suggestions());
            }
        }
        String p = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(p)).toList();
    }
}
