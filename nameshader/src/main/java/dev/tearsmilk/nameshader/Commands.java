package dev.tearsmilk.nameshader;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Commands implements TabExecutor {

    public static final List<String> SUBS = List.of("help", "reload", "test", "status");
    public static final List<String[]> PACK_HOOKS = List.of(
            new String[]{"rendertype_text_see_through.fsh", "ns_tag("},
            new String[]{"rendertype_text_background.vsh", "ns_display_vertex("},
            new String[]{"rendertype_text_background.fsh", "ns_display_fragment("},
            new String[]{"rendertype_text_background_see_through.vsh", "ns_display_vertex("},
            new String[]{"rendertype_text_background_see_through.fsh", "ns_display_fragment("});
    public static final List<String[]> PACK_INCLUDES = List.of(
            new String[]{"ns_common.glsl", "ns_plate("},
            new String[]{"ns_tag.glsl", "vec4 ns_tag("},
            new String[]{"ns_display_vertex.glsl", "ns_display_vertex("},
            new String[]{"ns_display_fragment.glsl", "vec4 ns_display_fragment("});

    private final NameShaderPlugin plugin;

    public Commands(NameShaderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Texts t = plugin.texts();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("nameshader.admin")) {
                    t.send(sender, "no-permission");
                    return true;
                }
                plugin.reloadAll();
                t.send(sender, "reloaded", "displays", plugin.settings().styleTextDisplays() ? t.raw("on") : t.raw("off"));
            }
            case "test" -> {
                if (!sender.hasPermission("nameshader.test")) {
                    t.send(sender, "no-permission");
                    return true;
                }
                Player p = sender instanceof Player self ? self : null;
                if (args.length > 1) {
                    if (!sender.hasPermission("nameshader.admin")) {
                        t.send(sender, "no-permission");
                        return true;
                    }
                    p = Bukkit.getPlayerExact(args[1]);
                    if (p == null) {
                        t.send(sender, "no-player", "name", args[1]);
                        return true;
                    }
                }
                if (p == null) {
                    t.send(sender, "players-only");
                    return true;
                }
                int seconds = plugin.tests().spawn(p);
                t.send(sender, "test-spawned", "seconds", String.valueOf(seconds));
            }
            case "status" -> {
                if (!sender.hasPermission("nameshader.admin")) {
                    t.send(sender, "no-permission");
                    return true;
                }
                status(sender, t);
            }
            default -> t.send(sender, "help");
        }
        return true;
    }

    private void status(CommandSender sender, Texts t) {
        Settings s = plugin.settings();
        int[] c = plugin.styler().countLoaded();
        t.send(sender, "status-displays", "state", s.styleTextDisplays() ? t.raw("on") : t.raw("off"),
                "tag", s.displayTag().isEmpty() ? t.raw("any") : s.displayTag(),
                "styled", String.valueOf(c[0]), "total", String.valueOf(c[1]));
        t.send(sender, "status-tests", "count", String.valueOf(plugin.tests().active()));
        t.send(sender, "status-plugins", "list", others());
        t.send(sender, "status-shaderhooks", "state", plugin.pack() == null ? "-" : plugin.pack().summary());
        Path assets = plugin.getDataFolder().toPath().getParent().resolve("Nexo/pack/assets");
        Path core = assets.resolve("minecraft/shaders/core");
        Path include = assets.resolve("nameshader/shaders/include");
        int ok = 0;
        List<String> bad = new ArrayList<>();
        for (String[] h : PACK_HOOKS) {
            if (hasHook(core.resolve(h[0]), h[1])) {
                ok++;
            } else {
                bad.add(h[0]);
            }
        }
        if (bad.isEmpty()) {
            t.send(sender, "status-pack-ok", "count", String.valueOf(ok));
        } else {
            t.send(sender, "status-pack-missing", "count", String.valueOf(ok), "total", String.valueOf(PACK_HOOKS.size()),
                    "files", String.join(", ", bad));
        }
        List<String> missing = missingIncludes(include);
        if (missing.isEmpty()) {
            t.send(sender, "status-include-ok", "count", String.valueOf(PACK_INCLUDES.size()));
        } else if (ok > 0) {
            t.send(sender, "status-include-broken", "files", String.join(", ", missing));
        } else {
            t.send(sender, "status-include-missing", "count", String.valueOf(PACK_INCLUDES.size() - missing.size()),
                    "total", String.valueOf(PACK_INCLUDES.size()), "files", String.join(", ", missing));
        }
    }

    static List<String> missingIncludes(Path include) {
        List<String> missing = new ArrayList<>();
        for (String[] h : PACK_INCLUDES) {
            if (!hasHook(include.resolve(h[0]), h[1])) {
                missing.add(h[0]);
            }
        }
        return missing;
    }

    static boolean hasHook(Path file, String hook) {
        try {
            return Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).contains(hook);
        } catch (IOException e) {
            return false;
        }
    }

    private static String others() {
        List<String> out = new ArrayList<>();
        for (String name : List.of("textshader", "tabshader", "Nexo", "SkinsRestorer")) {
            out.add(name + (Bukkit.getPluginManager().isPluginEnabled(name) ? " +" : " -"));
        }
        return String.join(", ", out);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("test") && sender.hasPermission("nameshader.admin")) {
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
