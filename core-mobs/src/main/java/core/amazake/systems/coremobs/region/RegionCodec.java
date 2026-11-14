package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.geo.BlockPos;
import core.amazake.systems.coremobs.util.IntRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Регионы ⇄ regions.yml. Без Bukkit-логики, проверяется юнит-тестами. */
public final class RegionCodec {

    static final List<String> HEADER = List.of(
        "Регионы спавна core-mobs. Файл пишет система (команды /mobs region ...), но его можно править руками,",
        "потом /mobs reload. points — вершины полигона (блоки x,y,z), min-y/max-y — высота региона.",
        "despawn-radius — на сколько блоков моб может отойти за границу региона, дальше он пропадает (-1 — выключено).",
        "spawn.mobs — какие мобы появляются и их веса (чем больше вес, тем чаще).",
        "show — контур региона виден всем игрокам (/mobs region show <имя>).");

    private RegionCodec() {
    }

    public static Map<String, Region> read(final ConfigurationSection root, final Consumer<String> warn) {
        final Map<String, Region> out = new LinkedHashMap<>();
        final ConfigurationSection regions = root.getConfigurationSection("regions");
        if (regions == null) {
            return out;
        }
        for (final String raw : regions.getKeys(false)) {
            final String name = raw.toLowerCase(Locale.ROOT);
            final ConfigurationSection r = regions.getConfigurationSection(raw);
            if (r == null) {
                continue;
            }
            try {
                out.put(name, readRegion(name, r, w -> warn.accept("regions." + raw + ": " + w)));
            } catch (final IllegalArgumentException e) {
                warn.accept("regions." + raw + " пропущен: " + e.getMessage());
            }
        }
        return out;
    }

    static Region readRegion(final String name, final ConfigurationSection r, final Consumer<String> warn) {
        if (!Region.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("имя региона — латиница в нижнем регистре, цифры, _ и -");
        }
        final String world = r.getString("world");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("не указан world");
        }
        final List<BlockPos> points = new ArrayList<>();
        for (final String p : r.getStringList("points")) {
            points.add(BlockPos.parse(p));
        }
        if (points.size() < 3) {
            throw new IllegalArgumentException("в points нужно минимум 3 точки");
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (final BlockPos p : points) {
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        minY = r.getInt("min-y", minY);
        maxY = r.getInt("max-y", maxY);
        final int despawn = Math.max(-1, r.getInt("despawn-radius", 5));
        return Region.fromPoints(name, world, points, 0, despawn).withHeight(minY, maxY)
            .withSpawn(readSpawn(r.getConfigurationSection("spawn"), warn))
            .withShow(r.getBoolean("show", false));
    }

    static SpawnSettings readSpawn(final ConfigurationSection s, final Consumer<String> warn) {
        final SpawnSettings d = SpawnSettings.DEFAULTS;
        if (s == null) {
            return d;
        }
        final Map<String, Integer> mobs = new LinkedHashMap<>();
        final ConfigurationSection m = s.getConfigurationSection("mobs");
        if (m != null) {
            for (final String id : m.getKeys(false)) {
                final int w = m.getInt(id, 0);
                if (w <= 0) {
                    warn.accept("spawn.mobs." + id + ": вес должен быть больше 0");
                    continue;
                }
                mobs.put(id.toLowerCase(Locale.ROOT), w);
            }
        }
        IntRange amount = IntRange.parse(s.getString("amount", d.amount().format()));
        if (amount == null || amount.min() < 1 || amount.max() > 64) {
            warn.accept("spawn.amount «" + s.getString("amount") + "» — нужно число или диапазон в пределах 1..64");
            amount = d.amount();
        }
        return new SpawnSettings(
            s.getBoolean("enabled", d.enabled()),
            mobs,
            bounded(s.getInt("interval-seconds", d.intervalSeconds()), 1, 86400, "spawn.interval-seconds", warn),
            bounded(s.getInt("max-alive", d.maxAlive()), 0, 1000, "spawn.max-alive", warn),
            amount,
            bounded(s.getInt("player-radius", d.playerRadius()), 0, 512, "spawn.player-radius", warn),
            bounded(s.getInt("min-player-distance", d.minPlayerDistance()), 0, 128, "spawn.min-player-distance", warn));
    }

    public static YamlConfiguration write(final Iterable<Region> regions) {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(HEADER);
        yaml.createSection("regions");
        for (final Region region : regions) {
            final String p = "regions." + region.name() + ".";
            yaml.set(p + "world", region.world());
            yaml.set(p + "points", region.polygon().vertices().stream().map(BlockPos::format).toList());
            yaml.set(p + "min-y", region.minY());
            yaml.set(p + "max-y", region.maxY());
            yaml.set(p + "despawn-radius", region.despawnRadius());
            yaml.set(p + "show", region.show());
            final SpawnSettings s = region.spawn();
            yaml.set(p + "spawn.enabled", s.enabled());
            yaml.set(p + "spawn.interval-seconds", s.intervalSeconds());
            yaml.set(p + "spawn.max-alive", s.maxAlive());
            yaml.set(p + "spawn.amount", s.amount().format());
            yaml.set(p + "spawn.player-radius", s.playerRadius());
            yaml.set(p + "spawn.min-player-distance", s.minPlayerDistance());
            yaml.createSection(p + "spawn.mobs");
            s.mobs().forEach((id, w) -> yaml.set(p + "spawn.mobs." + id, w));
        }
        return yaml;
    }

    private static int bounded(final int v, final int min, final int max, final String key, final Consumer<String> warn) {
        if (v < min || v > max) {
            final int fixed = Math.max(min, Math.min(max, v));
            warn.accept(key + ": " + v + " вне " + min + ".." + max + ", беру " + fixed);
            return fixed;
        }
        return v;
    }
}
