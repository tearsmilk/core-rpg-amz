package dev.tearsmilk.worldshader;

import org.bukkit.configuration.ConfigurationSection;

public record Settings(boolean packInstall, String packDir, boolean reloadNexo, int radius, int strength, int seconds,
                       int maxSeconds, float viewRange, int targetDistance) {

    public static final int MAX_SECONDS = 7 * 24 * 3600;

    public static Settings from(ConfigurationSection c) {
        int maxSeconds = clamp(c.getInt("zone.max-seconds", 86400), 1, MAX_SECONDS);
        return new Settings(c.getBoolean("pack.install", true), c.getString("pack.dir", ""), c.getBoolean("pack.reload-nexo", true),
                clamp(c.getInt("zone.radius", 10), 1, ZoneCode.MAX_RADIUS),
                clamp(c.getInt("zone.strength", 20), 1, ZoneCode.MAX_STRENGTH),
                clamp(c.getInt("zone.seconds", 60), 0, maxSeconds),
                maxSeconds,
                (float) Math.max(1.0, Math.min(16.0, c.getDouble("zone.view-range", 4.0))),
                clamp(c.getInt("zone.target-distance", 64), 1, 256));
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
