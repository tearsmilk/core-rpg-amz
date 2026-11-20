package dev.tearsmilk.tabshader;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Commands implements TabExecutor {

    static final String ADMIN = "tabshader.admin";
    static final String TOGGLE = "tabshader.toggle";
    static final String TEST = "tabshader.test";
    static final List<String> SUBS = List.of("help", "toggle", "test-title", "test-actionbar", "title", "actionbar",
            "reload", "status");

    private final TabShaderPlugin plugin;

    public Commands(TabShaderPlugin plugin) {
        this.plugin = plugin;
    }

    private Texts texts() {
        return plugin.texts();
    }

    static String permission(String sub) {
        return switch (sub) {
            case "toggle" -> TOGGLE;
            case "test-title", "test-actionbar" -> TEST;
            case "title", "actionbar", "reload", "status" -> ADMIN;
            default -> null;
        };
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        String perm = permission(sub);
        if (perm != null && !s.hasPermission(perm)) {
            texts().send(s, "no-permission");
            return true;
        }
        switch (sub) {
            case "toggle" -> toggle(s, a);
            case "test-title" -> testTitle(s, a);
            case "test-actionbar" -> testActionbar(s, a);
            case "title" -> title(s, a);
            case "actionbar" -> actionbar(s, a);
            case "reload" -> {
                plugin.reload();
                texts().send(s, "reloaded");
            }
            case "status" -> {
                texts().send(s, "status", "players", plugin.hud().tracked(), "updates",
                        plugin.hud().packets(), "widths", plugin.widths().size());
                Settings st = plugin.settings();
                texts().send(s, "status-tab", "limit", TabLayout.maxEntries(st.maxColumns()), "hidden",
                        plugin.limiter().hiddenTotal(), "cut", plugin.names().truncated(), "width", plugin.hud().tabWidth(st),
                        "tab", st.tabPosition().key(), "board", st.boardPosition().key(),
                        "wide", plugin.hud().boardTooWide());
                texts().send(s, "status-ping", "state", texts().get(st.pingBars() ? "ping-on" : "ping-off"),
                        "hidden", plugin.hud().pingHidden(), "players", plugin.hud().tracked());
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        texts().send(s, "help");
    }

    private List<Player> targets(CommandSender s, String[] a, int idx) {
        if (a.length > idx) {
            String name = a[idx];
            if (name.equals("*") || name.equalsIgnoreCase("all")) {
                if (!s.hasPermission(ADMIN)) {
                    texts().send(s, "no-permission");
                    return List.of();
                }
                return new ArrayList<>(Bukkit.getOnlinePlayers());
            }
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) {
                texts().send(s, "player-not-found", "player", name);
                return List.of();
            }
            if (s instanceof Player self && p != self && !s.hasPermission(ADMIN)) {
                texts().send(s, "no-permission");
                return List.of();
            }
            return List.of(p);
        }
        if (s instanceof Player p) {
            return List.of(p);
        }
        texts().send(s, "only-players");
        return List.of();
    }

    private void toggle(CommandSender s, String[] a) {
        if (a.length < 2) {
            texts().send(s, "usage-toggle");
            return;
        }
        Part part = Part.parse(a[1]);
        if (part == null) {
            texts().send(s, "unknown-part", "part", a[1]);
            return;
        }
        for (Player p : targets(s, a, 2)) {
            boolean on = plugin.toggles().set(p, part, !plugin.toggles().on(p, part));
            plugin.hud().update(p);
            String key = on ? "toggled-on" : "toggled-off";
            texts().send(s, key, "part", texts().raw("part-" + part.key()), "player", p.getName());
        }
    }

    private void testTitle(CommandSender s, String[] a) {
        Settings st = plugin.settings();
        for (Player p : targets(s, a, 1)) {
            Map<String, String> v = plugin.vars().of(p, plugin.vars().global());
            Component t = texts().render(p, st.titleTest(), v);
            Component sub = texts().render(p, st.subtitleTest(), v);
            TabShaderApi.showTitle(p, t, sub, st.fadeIn(), st.stay(), st.fadeOut());
            texts().send(s, "sent-title", "player", p.getName());
        }
    }

    private void testActionbar(CommandSender s, String[] a) {
        Settings st = plugin.settings();
        for (Player p : targets(s, a, 1)) {
            Map<String, String> v = plugin.vars().of(p, plugin.vars().global());
            TabShaderApi.sendActionBar(p, texts().render(p, st.actionbarTest(), v));
            texts().send(s, "sent-actionbar", "player", p.getName());
        }
    }

    static String[] splitTitle(String text) {
        int i = text.indexOf(" | ");
        if (i < 0) {
            return new String[]{text, ""};
        }
        return new String[]{text.substring(0, i), text.substring(i + 3)};
    }

    private void title(CommandSender s, String[] a) {
        if (a.length < 3) {
            texts().send(s, "usage-title");
            return;
        }
        String[] parts = splitTitle(String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
        Settings st = plugin.settings();
        for (Player p : targets(s, a, 1)) {
            Map<String, String> v = plugin.vars().of(p, plugin.vars().global());
            TabShaderApi.showTitle(p, texts().render(p, parts[0], v), texts().render(p, parts[1], v),
                    st.fadeIn(), st.stay(), st.fadeOut());
            texts().send(s, "sent-title", "player", p.getName());
        }
    }

    private void actionbar(CommandSender s, String[] a) {
        if (a.length < 3) {
            texts().send(s, "usage-actionbar");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
        for (Player p : targets(s, a, 1)) {
            Map<String, String> v = plugin.vars().of(p, plugin.vars().global());
            TabShaderApi.sendActionBar(p, texts().render(p, text, v));
            texts().send(s, "sent-actionbar", "player", p.getName());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String sub : SUBS) {
                String perm = permission(sub);
                if ((perm == null || s.hasPermission(perm)) && sub.startsWith(a[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("toggle") && a.length == 2) {
            for (Part p : Part.values()) {
                if (p.key().startsWith(a[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.key());
                }
            }
            return out;
        }
        boolean playerArg = (sub.equals("toggle") && a.length == 3 && s.hasPermission(ADMIN))
                || (List.of("test-title", "test-actionbar", "title", "actionbar").contains(sub) && a.length == 2);
        if (playerArg) {
            if ("*".startsWith(a[a.length - 1])) {
                out.add("*");
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[a.length - 1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
