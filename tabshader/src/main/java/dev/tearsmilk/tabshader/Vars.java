package dev.tearsmilk.tabshader;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class Vars {

    private final String server;
    private final ZoneId zone;
    private final DateTimeFormatter time;
    private final DateTimeFormatter date;

    Vars(String server, String zone, String time, String date) {
        this.server = server;
        ZoneId z;
        try {
            z = ZoneId.of(zone);
        } catch (RuntimeException e) {
            z = ZoneId.systemDefault();
        }
        this.zone = z;
        this.time = DateTimeFormatter.ofPattern(time, Locale.ROOT);
        this.date = DateTimeFormatter.ofPattern(date, Locale.ROOT);
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(zone);
    }

    static String tps(double tps) {
        return String.format(Locale.ROOT, "%.1f", Math.min(20.0, Math.max(0.0, tps)));
    }

    static String clock(long ticks) {
        long t = Math.floorMod(ticks + 6000L, 24000L);
        long minutes = t * 1440L / 24000L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    Map<String, String> global() {
        Map<String, String> v = new HashMap<>();
        ZonedDateTime n = now();
        v.put("server", server);
        v.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
        v.put("max", String.valueOf(Bukkit.getMaxPlayers()));
        v.put("tps", tps(Bukkit.getTPS()[0]));
        v.put("time", time.format(n));
        v.put("date", date.format(n));
        return v;
    }

    Map<String, String> of(Player p, Map<String, String> global) {
        Map<String, String> v = new HashMap<>(global);
        Location l = p.getLocation();
        v.put("player", p.getName());
        v.put("ping", String.valueOf(p.getPing()));
        v.put("world", p.getWorld().getName());
        v.put("x", String.valueOf(l.getBlockX()));
        v.put("y", String.valueOf(l.getBlockY()));
        v.put("z", String.valueOf(l.getBlockZ()));
        v.put("world-time", clock(p.getWorld().getTime()));
        v.put("health", String.valueOf((int) Math.ceil(p.getHealth())));
        v.put("level", String.valueOf(p.getLevel()));
        return v;
    }
}
