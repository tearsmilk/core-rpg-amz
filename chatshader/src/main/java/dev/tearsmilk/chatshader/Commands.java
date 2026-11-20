package dev.tearsmilk.chatshader;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Commands implements TabExecutor {

    public static final List<String> SUBS = List.of("help", "reload", "status", "test");
    public static final String TEXTSHADER_WAVE = "<#D60400>";

    private final ChatShaderPlugin plugin;

    public Commands(ChatShaderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Texts t = plugin.texts();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBS.contains(sub) || sub.equals("help")) {
            t.send(sender, "help");
            return true;
        }
        if (!sender.hasPermission("chatshader.admin")) {
            t.send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "reload" -> {
                plugin.reloadAll();
                t.send(sender, "reloaded", "position", plugin.effective().key(), "margin", String.valueOf(plugin.effectiveMargin()));
                if (plugin.settings().moves() && plugin.effective() == Position.BOTTOM_LEFT && plugin.effectiveMargin() == 0) {
                    t.send(sender, "marker-failed", "error", String.valueOf(plugin.marker().error()));
                }
                if (plugin.overlap() != null) {
                    t.send(sender, "overlap", "text", plugin.overlap());
                }
            }
            case "status" -> status(sender, t);
            case "test" -> test(sender, t, args);
            default -> t.send(sender, "help");
        }
        return true;
    }

    private void status(CommandSender sender, Texts t) {
        Settings s = plugin.settings();
        PacketMarker m = plugin.marker();
        t.send(sender, "status-position", "position", s.position().key(), "margin", String.valueOf(s.margin()),
                "effective", plugin.effective().key(), "effective-margin", String.valueOf(plugin.effectiveMargin()));
        String marker = m.active() ? t.raw("on") : t.raw("off");
        t.send(sender, "status-marker", "state", marker, "count", String.valueOf(m.marked()),
                "error", m.error() == null ? "-" : m.error());
        Settings.Shader sh = plugin.shader();
        t.send(sender, "status-pack-config", "state", sh == null ? t.raw("no-config") : sh.position().key() + ", " + sh.margin());
        if (plugin.legacyPlayers() > 0) {
            t.send(sender, "status-legacy", "count", String.valueOf(plugin.legacyPlayers()));
        }
        t.send(sender, "status-shaderhooks", "state", plugin.pack() == null ? "-" : plugin.pack().summary());
        if (plugin.overlap() != null) {
            t.send(sender, "overlap", "text", plugin.overlap());
        }
        List<String> out = new ArrayList<>();
        for (String name : List.of("textshader", "tabshader", "nameshader", "Nexo")) {
            out.add(name + (Bukkit.getPluginManager().isPluginEnabled(name) ? " +" : " -"));
        }
        t.send(sender, "status-plugins", "list", String.join(", ", out));
    }

    private void test(CommandSender sender, Texts t, String[] args) {
        CommandSender to = sender;
        if (args.length > 1) {
            Player p = Bukkit.getPlayerExact(args[1]);
            if (p == null) {
                t.send(sender, "no-player", "name", args[1]);
                return;
            }
            to = p;
        }
        boolean fx = Bukkit.getPluginManager().isPluginEnabled("textshader");
        int sent = 0;
        for (String line : plugin.settings().testLines()) {
            if (line.contains(TEXTSHADER_WAVE) && !fx) {
                continue;
            }
            to.sendMessage(t.parse(line));
            sent++;
        }
        if (to != sender) {
            t.send(sender, "test-sent", "count", String.valueOf(sent), "name", to.getName());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("test") && sender.hasPermission("chatshader.admin")) {
            String q = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(q)).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String p = args[0].toLowerCase(Locale.ROOT);
        return SUBS.stream().filter(s -> s.startsWith(p)).toList();
    }
}
