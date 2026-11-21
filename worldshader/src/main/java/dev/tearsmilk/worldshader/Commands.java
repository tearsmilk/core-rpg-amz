package dev.tearsmilk.worldshader;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Commands implements TabExecutor {

    public static final List<String> SUBS = List.of("help", "summon", "remove", "list", "status", "reload");
    public static final String PERMISSION = "worldshader.admin";

    private final WorldShaderPlugin plugin;

    public Commands(WorldShaderPlugin plugin) {
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
        if (!sender.hasPermission(PERMISSION)) {
            t.send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "summon" -> summon(sender, t, args);
            case "remove" -> remove(sender, t, args);
            case "list" -> list(sender, t);
            case "status" -> status(sender, t);
            case "reload" -> {
                plugin.reloadAll();
                t.send(sender, "reloaded");
            }
            default -> t.send(sender, "help");
        }
        return true;
    }

    static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double coord(String s, double base) {
        try {
            double v = s.startsWith("~") ? base + (s.length() == 1 ? 0.0 : Double.parseDouble(s.substring(1))) : Double.parseDouble(s);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean insideWorld(World w, Location l) {
        return l.getY() >= w.getMinHeight() - 64 && l.getY() <= w.getMaxHeight() + 64 && w.getWorldBorder().isInside(l);
    }

    private void summon(CommandSender sender, Texts t, String[] args) {
        Settings s = plugin.settings();
        int[] values = {s.radius(), s.strength(), s.seconds()};
        int[] lo = {1, 1, 0};
        int[] hi = {ZoneCode.MAX_RADIUS, ZoneCode.MAX_STRENGTH, s.maxSeconds()};
        String[] names = {"radius", "strength", "seconds"};
        for (int i = 0; i < 3 && i + 1 < args.length; i++) {
            Integer v = parseInt(args[i + 1]);
            if (v == null || v < lo[i] || v > hi[i]) {
                t.send(sender, "bad-number", "name", names[i], "value", args[i + 1], "min", String.valueOf(lo[i]), "max", String.valueOf(hi[i]));
                return;
            }
            values[i] = v;
        }
        Location at = summonLocation(sender, t, args, values[0]);
        if (at == null) {
            return;
        }
        Zones.Spawned r = plugin.zones().spawn(at, values[0], values[1], values[2]);
        if (r.zone() == null) {
            if (Zones.SPAWN_FAILED.equals(r.error())) {
                t.send(sender, "spawn-failed");
            } else {
                t.send(sender, "no-slot", "world", at.getWorld().getName(), "max", String.valueOf(ZoneCode.SLOTS));
            }
            return;
        }
        Zone z = r.zone();
        t.send(sender, "summoned", "slot", String.valueOf(z.slot()), "world", z.world(), "x", fmt(z.x()), "y", fmt(z.y()),
                "z", fmt(z.z()), "radius", String.valueOf(z.radius()), "strength", String.valueOf(z.strength()),
                "time", time(t, z.secondsLeft(System.currentTimeMillis())));
        PackInstaller.Report p = plugin.pack();
        if (p != null && !p.active()) {
            t.send(sender, "pack-inactive", "state", p.summary());
        }
    }

    private Location summonLocation(CommandSender sender, Texts t, String[] args, int radius) {
        int extra = Math.max(0, args.length - 4);
        if (extra == 0) {
            if (!(sender instanceof Player p)) {
                t.send(sender, "need-coords");
                return null;
            }
            return target(p, radius);
        }
        if (extra != 3 && extra != 4) {
            t.send(sender, "help");
            return null;
        }
        Location base = sender instanceof Entity e ? e.getLocation()
                : sender instanceof BlockCommandSender b ? b.getBlock().getLocation().add(0.5, 0.0, 0.5) : null;
        int i = 4;
        World w;
        if (extra == 4) {
            w = Bukkit.getWorld(args[4]);
            if (w == null) {
                t.send(sender, "no-world", "world", args[4]);
                return null;
            }
            i = 5;
        } else if (base != null) {
            w = base.getWorld();
        } else {
            t.send(sender, "need-coords");
            return null;
        }
        double[] xyz = new double[3];
        for (int k = 0; k < 3; k++) {
            String a = args[i + k];
            if (a.startsWith("~") && (base == null || !base.getWorld().equals(w))) {
                t.send(sender, "need-coords");
                return null;
            }
            Double v = coord(a, base == null ? 0.0 : k == 0 ? base.getX() : k == 1 ? base.getY() : base.getZ());
            if (v == null) {
                t.send(sender, "bad-coord", "value", a);
                return null;
            }
            xyz[k] = v;
        }
        Location l = new Location(w, xyz[0], xyz[1], xyz[2]);
        if (!insideWorld(w, l)) {
            t.send(sender, "outside-world", "world", w.getName());
            return null;
        }
        return l;
    }

    private Location target(Player p, int radius) {
        int reach = plugin.settings().targetDistance();
        RayTraceResult hit = p.rayTraceBlocks(reach);
        if (hit != null && hit.getHitBlock() != null) {
            Block b = hit.getHitBlock();
            if (hit.getHitBlockFace() != null) {
                b = b.getRelative(hit.getHitBlockFace());
            }
            return b.getLocation().add(0.5, 0.5, 0.5);
        }
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize().multiply(Math.min(reach, radius * 2 + 2));
        return eye.add(dir);
    }

    private void remove(CommandSender sender, Texts t, String[] args) {
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "nearest";
        if (mode.equals("all")) {
            int n = plugin.zones().removeAll();
            t.send(sender, "removed-all", "count", String.valueOf(n));
            return;
        }
        if (!mode.equals("nearest")) {
            t.send(sender, "help");
            return;
        }
        Location from = sender instanceof Entity e ? e.getLocation() : null;
        if (from == null) {
            t.send(sender, "need-player");
            return;
        }
        Zone z = plugin.zones().nearest(from);
        if (z == null) {
            t.send(sender, "none-here");
            return;
        }
        plugin.zones().remove(z);
        t.send(sender, "removed", "slot", String.valueOf(z.slot()), "world", z.world(), "x", fmt(z.x()), "y", fmt(z.y()),
                "z", fmt(z.z()), "distance", fmt(Math.sqrt(z.distanceSquared(from.getX(), from.getY(), from.getZ()))));
    }

    private void list(CommandSender sender, Texts t) {
        List<Zone> all = plugin.zones().all();
        if (all.isEmpty()) {
            t.send(sender, "list-empty");
            return;
        }
        t.send(sender, "list-header", "count", String.valueOf(all.size()));
        long now = System.currentTimeMillis();
        for (Zone z : all) {
            t.send(sender, "list-line", "slot", String.valueOf(z.slot()), "world", z.world(), "x", fmt(z.x()), "y", fmt(z.y()),
                    "z", fmt(z.z()), "radius", String.valueOf(z.radius()), "strength", String.valueOf(z.strength()),
                    "time", time(t, z.secondsLeft(now)),
                    "loaded", Bukkit.getEntity(z.id()) != null ? t.raw("loaded") : t.raw("unloaded"));
        }
    }

    private void status(CommandSender sender, Texts t) {
        Settings s = plugin.settings();
        PackInstaller.Report p = plugin.pack();
        t.send(sender, "status-pack", "state", p == null ? "-" : p.summary());
        t.send(sender, "status-zones", "count", String.valueOf(plugin.zones().all().size()), "max", String.valueOf(ZoneCode.SLOTS));
        t.send(sender, "status-defaults", "radius", String.valueOf(s.radius()), "strength", String.valueOf(s.strength()),
                "time", time(t, s.seconds() <= 0 ? -1 : s.seconds()), "view", fmt(s.viewRange()));
        t.send(sender, "status-nexo", "state", Bukkit.getPluginManager().isPluginEnabled("Nexo") ? t.raw("on") : t.raw("off"));
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String time(Texts t, long seconds) {
        if (seconds <= 0) {
            return seconds == 0 ? "0" + t.raw("sec") : t.raw("forever");
        }
        long m = seconds / 60;
        long s = seconds % 60;
        return m > 0 ? m + t.raw("min") + " " + s + t.raw("sec") : s + t.raw("sec");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(p)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String q = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Settings s = plugin.settings();
        if (sub.equals("remove") && args.length == 2) {
            out.addAll(List.of("nearest", "all"));
        } else if (sub.equals("summon")) {
            switch (args.length) {
                case 2 -> out.addAll(List.of(String.valueOf(s.radius()), "5", "10", "16", "32"));
                case 3 -> out.addAll(List.of(String.valueOf(s.strength()), "10", "20", "31"));
                case 4 -> out.addAll(List.of(String.valueOf(s.seconds()), "0", "30", "60", "300"));
                case 5 -> {
                    out.add("~");
                    for (World w : Bukkit.getWorlds()) {
                        out.add(w.getName());
                    }
                }
                case 6, 7 -> out.add("~");
                default -> {
                    return List.of();
                }
            }
        }
        return out.stream().distinct().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(q)).toList();
    }
}
