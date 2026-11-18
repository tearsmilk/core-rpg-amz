package core.amazake.systems.coremechanics;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Настройки из config.yml (неизменяемые; /rd reload подменяет целиком). */
public record Settings(
    boolean onDeath,
    int deathLifeTicks,
    int commandLifeTicks,
    int maxDolls,
    double grabReach,
    double throwStrength,
    double holdStiffness,
    double holdMaxSpeed,
    int statueToppleTicks,
    boolean statueHitTopple,
    boolean statueExplosionTopple,
    double physicsGravity,
    int physicsSubsteps,
    int physicsThreads,
    int physicsMaxBodies,
    int physicsRequestsPerTick,
    boolean skinFetchOnJoin,
    boolean mojangLookup,
    String mineskinKey,
    String mineskinUrl,
    int mineskinTimeout,
    boolean libsAutoDownload,
    List<String> libsRepositories,
    Map<String, String> tooltipFlavors) {

    /** Приписки к подсказке-свитку по id Nexo; «*» — для остальных предметов (пусто — без приписки). */
    static final Map<String, String> DEFAULT_FLAVORS = Map.of(
        "*", "«Старая вещь. Хранит больше, чем показывает.»",
        "royal_knight_volcano_heart", "«Выкован в жерле спящего вулкана —\nего пламя всё ещё бьётся в древке.»");

    public static final Settings DEFAULTS = from(new YamlConfiguration(), w -> { });

    /** Прочитать (недостающее — по умолчанию; кривые значения — в допустимые пределы с предупреждением). */
    public static Settings from(final YamlConfiguration c, final Consumer<String> warn) {
        return new Settings(
            c.getBoolean("ragdoll.on-death", true),
            seconds(c, "ragdoll.death-lifetime-seconds", 90, 1, 3600, warn),
            seconds(c, "ragdoll.command-lifetime-seconds", 300, 1, 3600, warn),
            clampInt(c, "ragdoll.max-dolls", 12, 1, 64, warn),
            clamp(c, "ragdoll.grab-reach", 6.0, 1.0, 16.0, warn),
            clamp(c, "ragdoll.throw-strength", 14.0, 0.0, 60.0, warn),
            clamp(c, "ragdoll.hold-stiffness", 10.0, 1.0, 50.0, warn),
            clamp(c, "ragdoll.hold-max-speed", 25.0, 1.0, 60.0, warn),
            seconds(c, "statues.topple-seconds", 10, 1, 600, warn),
            c.getBoolean("statues.topple-on-hit", true),
            c.getBoolean("statues.topple-on-explosion", true),
            clamp(c, "physics.gravity", 17.0, 1.0, 80.0, warn),
            clampInt(c, "physics.substeps", 3, 1, 8, warn),
            clampInt(c, "physics.threads", 0, 0, 16, warn),
            clampInt(c, "physics.max-bodies", 4096, 256, 65536, warn),
            clampInt(c, "physics.section-requests-per-tick", 24, 1, 256, warn),
            c.getBoolean("skins.fetch-on-join", true),
            c.getBoolean("skins.mojang-lookup", true),
            c.getString("mineskin.key", "").strip(),
            c.getString("mineskin.api-url", "https://api.mineskin.org/v2/generate").strip(),
            clampInt(c, "mineskin.timeout-seconds", 60, 5, 300, warn),
            c.getBoolean("libraries.auto-download", true),
            repos(c.getConfigurationSection("libraries"), c.getStringList("libraries.repositories")),
            flavors(c.getConfigurationSection("tooltip.flavors")));
    }

    private static Map<String, String> flavors(final ConfigurationSection s) {
        if (s == null) {
            return DEFAULT_FLAVORS;
        }
        final Map<String, String> out = new java.util.HashMap<>();
        for (final String k : s.getKeys(false)) {
            out.put(k, String.valueOf(s.getString(k, "")));
        }
        return Map.copyOf(out);
    }

    private static List<String> repos(final ConfigurationSection s, final List<String> list) {
        if (s == null || !s.contains("repositories") || list.isEmpty()) {
            return List.of("https://repo1.maven.org/maven2/", "https://maven-central.storage-download.googleapis.com/maven2/");
        }
        return List.copyOf(list);
    }

    private static int seconds(final YamlConfiguration c, final String key, final int def, final int min, final int max,
                               final Consumer<String> warn) {
        return clampInt(c, key, def, min, max, warn) * 20;
    }

    private static int clampInt(final YamlConfiguration c, final String key, final int def, final int min, final int max,
                                final Consumer<String> warn) {
        final int v = c.getInt(key, def);
        if (v < min || v > max) {
            warn.accept(key + ": " + v + " вне " + min + ".." + max + " — беру " + Math.max(min, Math.min(max, v)));
            return Math.max(min, Math.min(max, v));
        }
        return v;
    }

    private static double clamp(final YamlConfiguration c, final String key, final double def, final double min, final double max,
                                final Consumer<String> warn) {
        final double v = c.getDouble(key, def);
        if (!(v >= min && v <= max)) {
            final double r = Double.isNaN(v) ? def : Math.max(min, Math.min(max, v));
            warn.accept(key + ": " + v + " вне " + min + ".." + max + " — беру " + r);
            return r;
        }
        return v;
    }
}
