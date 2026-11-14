package core.amazake.systems.rezusmenu;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.rezusmenu.screen.Screen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * players.yml: калибровка экрана каждого игрока и «что вернуть» тем, кто сейчас в меню. Второе нужно, если сервер
 * упадёт, пока игрок в меню: при входе ему вернут режим игры, скорость и место, а не оставят наблюдателем.
 */
public final class PlayerStore {

    private final SystemContext context;
    private final Path file;
    private final Map<UUID, Screen.Calibration> calibrations = new ConcurrentHashMap<>();
    private final Map<UUID, Screen.Saved> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Object ioLock = new Object();

    public PlayerStore(final SystemContext context) {
        this.context = context;
        this.file = context.dataFolder().resolve("players.yml");
    }

    public void load() {
        synchronized (this.ioLock) {
            try {
                final YamlConfiguration y = new YamlConfiguration();
                if (Files.isRegularFile(this.file)) {
                    y.loadFromString(Files.readString(this.file, StandardCharsets.UTF_8));
                }
                final ConfigurationSection cal = y.getConfigurationSection("calibration");
                if (cal != null) {
                    for (final String id : cal.getKeys(false)) {
                        final double w = cal.getDouble(id + ".half-w", -1);
                        final double h = cal.getDouble(id + ".half-h", -1);
                        if (w > 0.2 && h > 0.2) {
                            this.calibrations.put(UUID.fromString(id), new Screen.Calibration(w, h));
                        }
                    }
                }
                final ConfigurationSection res = y.getConfigurationSection("restore");
                if (res != null) {
                    for (final String id : res.getKeys(false)) {
                        final ConfigurationSection r = res.getConfigurationSection(id);
                        final World world = r == null ? null : Bukkit.getWorld(r.getString("world", ""));
                        if (r == null) {
                            continue;
                        }
                        final Location loc = new Location(world, r.getDouble("x"), r.getDouble("y"), r.getDouble("z"));
                        GameMode mode;
                        try {
                            mode = GameMode.valueOf(r.getString("mode", "SURVIVAL"));
                        } catch (final IllegalArgumentException e) {
                            mode = GameMode.SURVIVAL;
                        }
                        this.pending.put(UUID.fromString(id), new Screen.Saved(mode, loc, (float) r.getDouble("walk", 0.2),
                            (float) r.getDouble("fly", 0.1), r.getBoolean("allow-flight"), r.getBoolean("flying")));
                    }
                }
            } catch (final Exception e) {
                this.context.logger().log(Level.WARNING, "players.yml не прочитан", e);
            }
        }
    }

    public Screen.Calibration calibration(final UUID player) {
        return this.calibrations.get(player);
    }

    public void calibrate(final UUID player, final Screen.Calibration value) {
        this.calibrations.put(player, value);
        this.changed();
    }

    /** Игрок вошёл в меню: запомнить, что вернуть. */
    public void remember(final UUID player, final Screen.Saved saved) {
        this.pending.put(player, saved);
        this.changed();
    }

    /** Игрок вышел из меню как надо. */
    public void forget(final UUID player) {
        if (this.pending.remove(player) != null) {
            this.changed();
        }
    }

    /** Осталось от падения сервера: вернуть и забыть. */
    public Screen.Saved takePending(final UUID player) {
        final Screen.Saved s = this.pending.remove(player);
        if (s != null) {
            this.changed();
        }
        return s;
    }

    private void changed() {
        this.dirty.set(true);
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), t -> this.saveNow());
    }

    public void saveNow() {
        synchronized (this.ioLock) {
            if (!this.dirty.getAndSet(false)) {
                return;
            }
            final YamlConfiguration y = new YamlConfiguration();
            y.options().setHeader(java.util.List.of("rezusmenu: калибровка экрана игроков и незакрытые меню. Пишет система."));
            this.calibrations.forEach((id, c) -> {
                y.set("calibration." + id + ".half-w", c.halfW());
                y.set("calibration." + id + ".half-h", c.halfH());
            });
            this.pending.forEach((id, s) -> {
                final String p = "restore." + id + ".";
                y.set(p + "mode", s.mode().name());
                y.set(p + "world", s.location().getWorld() == null ? "" : s.location().getWorld().getName());
                y.set(p + "x", s.location().getX());
                y.set(p + "y", s.location().getY());
                y.set(p + "z", s.location().getZ());
                y.set(p + "walk", s.walkSpeed());
                y.set(p + "fly", s.flySpeed());
                y.set(p + "allow-flight", s.allowFlight());
                y.set(p + "flying", s.flying());
            });
            try {
                Files.createDirectories(this.file.getParent());
                final Path tmp = this.file.resolveSibling("players.yml.tmp");
                Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException e) {
                this.dirty.set(true);
                this.context.logger().log(Level.WARNING, "Не удалось сохранить players.yml", e);
            }
        }
    }
}
