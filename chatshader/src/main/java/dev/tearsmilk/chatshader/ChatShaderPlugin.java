package dev.tearsmilk.chatshader;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChatShaderPlugin extends JavaPlugin implements Listener {

    private Settings settings;
    private Texts texts;
    private PacketMarker marker;
    private ShaderHooks.Report pack;
    private Position effective = Position.BOTTOM_LEFT;
    private int effectiveMargin;
    private Settings.Shader shader;
    private boolean shaderMoves;
    private final Set<UUID> legacy = new HashSet<>();
    private String overlap;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        texts = new Texts(getConfig());
        marker = new PacketMarker(getLogger(), () -> Bukkit.getScheduler().runTask(this, this::fallback));
        getServer().getPluginManager().registerEvents(this, this);
        apply(true);
        Commands commands = new Commands(this);
        PluginCommand cmd = getCommand("chatshader");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        }
    }

    @Override
    public void onDisable() {
        if (marker != null) {
            marker.active(false);
            marker.uninstall();
        }
    }

    public void reloadAll() {
        reloadConfig();
        texts.reload(getConfig());
        apply(false);
    }

    private void apply(boolean startup) {
        settings = Settings.from(getConfig());
        if (!settings.positionValid()) {
            getLogger().warning("chat.position: неизвестное значение \"" + getConfig().getString("chat.position")
                    + "\", взято bottom-left (можно: bottom-left, top-left, top-right, bottom-right).");
        }
        effective = settings.position();
        effectiveMargin = settings.margin();
        if (settings.moves() && (marker.failed() || !marker.install())) {
            effective = Position.BOTTOM_LEFT;
            effectiveMargin = 0;
        }
        overlap = boardOverlap();
        if (overlap != null) {
            getLogger().warning(overlap);
        }
        writePack(startup);
    }

    private void writePack(boolean startup) {
        byte[] glsl = Settings.glsl(effective, effectiveMargin).getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> generated = Map.of(Settings.CONFIG_PATH, glsl);
        if (startup) {
            afterPack(ShaderHooks.run(this, settings.packInstall(), settings.packDir(), settings.reloadNexo(), generated));
        } else {
            ShaderHooks.runAsync(this, settings.packInstall(), settings.packDir(), settings.reloadNexo(), generated, this::afterPack);
        }
    }

    private void afterPack(ShaderHooks.Report report) {
        pack = report;
        Settings.Shader now = readShader(report);
        boolean moves = now != null && now.moves();
        if (shaderMoves && (!moves || !now.equals(shader))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                legacy.add(p.getUniqueId());
            }
        }
        shader = now;
        shaderMoves = moves;
        updateMarker();
        getLogger().info("Чат: позиция " + effective.key() + ", отступ " + effectiveMargin + "; в паке "
                + (now == null ? "нет cs_config.glsl" : now.position().key() + ", отступ " + now.margin())
                + "; метка сообщений " + (marker.active() ? "включена" : "выключена") + ".");
        if (settings.moves() && !moves && !marker.failed() && marker.installed()) {
            getLogger().warning("chat.position " + settings.position().key() + " не попал в пак ("
                    + (report.skipped() == null ? "cs_config.glsl не найден" : report.skipped())
                    + "): метка сообщений выключена, иначе текст ушел бы мимо панели.");
        }
    }

    private void updateMarker() {
        marker.active(marker.installed() && (shaderMoves || !legacy.isEmpty()));
    }

    private Settings.Shader readShader(ShaderHooks.Report report) {
        Path dir = report.dir();
        if (dir == null) {
            Path nexo = getDataFolder().toPath().toAbsolutePath().getParent().resolve("Nexo").resolve("pack").resolve("assets");
            dir = settings.packDir().isBlank() ? nexo : Path.of(settings.packDir()).toAbsolutePath();
        }
        Path file = dir.resolve(Settings.CONFIG_PATH);
        try {
            return Files.isRegularFile(file) ? Settings.parseGlsl(Files.readString(file, StandardCharsets.UTF_8)) : null;
        } catch (IOException e) {
            getLogger().warning("Не удалось прочитать " + file + ": " + e);
            return null;
        }
    }

    private void fallback() {
        legacy.clear();
        effective = Position.BOTTOM_LEFT;
        effectiveMargin = 0;
        writePack(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (legacy.remove(e.getPlayer().getUniqueId()) && legacy.isEmpty()) {
            updateMarker();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED
                && legacy.remove(e.getPlayer().getUniqueId()) && legacy.isEmpty()) {
            updateMarker();
        }
    }

    private String boardOverlap() {
        if (Bukkit.getPluginManager().getPlugin("tabshader") == null) {
            return null;
        }
        File file = new File(getDataFolder().getParentFile(), "tabshader/config.yml");
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration tab = YamlConfiguration.loadConfiguration(file);
        if (!tab.getBoolean("board.enabled", true)) {
            return null;
        }
        String board = tab.getString("board.position", "right").trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Settings.overlaps(effective, board)
                ? "Чат (" + effective.key() + ") и табло tabshader (board.position: " + board
                + ") стоят с одной стороны экрана и перекрываются. Поменяйте chat.position или board.position."
                : null;
    }

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    PacketMarker marker() {
        return marker;
    }

    ShaderHooks.Report pack() {
        return pack;
    }

    Position effective() {
        return effective;
    }

    int effectiveMargin() {
        return effectiveMargin;
    }

    Settings.Shader shader() {
        return shader;
    }

    int legacyPlayers() {
        return legacy.size();
    }

    String overlap() {
        return overlap;
    }
}
