package dev.tearsmilk.aurahud.layout;

import dev.tearsmilk.aurahud.AuraHudPlugin;
import dev.tearsmilk.aurahud.HudModule;
import dev.tearsmilk.aurahud.PackTarget;
import dev.tearsmilk.aurahud.sprites.SpriteMarker;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class LayoutModule implements HudModule {

    private HudSettings settings = HudSettings.vanilla();
    private Map<Element, Placement> placements = LayoutEngine.place(settings);
    private volatile SpriteMarker.Result markers;

    @Override
    public String id() {
        return "layout";
    }

    @Override
    public void enable(AuraHudPlugin plugin) {
        reload(plugin);
    }

    @Override
    public void reload(AuraHudPlugin plugin) {
        load(plugin.getConfig().getConfigurationSection("hud"), plugin.getLogger());
    }

    public void load(ConfigurationSection hud, Logger log) {
        settings = HudSettings.read(hud);
        placements = LayoutEngine.place(settings);
        if (log != null) {
            for (String w : settings.warnings()) {
                log.warning("config.yml " + w);
            }
        }
    }

    @Override
    public Map<String, byte[]> packFiles(PackTarget target) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(GlslConfig.PATH, GlslConfig.generate(placements).getBytes(StandardCharsets.UTF_8));
        markers = null;
        if (target.installing() && GlslConfig.active(placements)) {
            Path backup = target.work() == null ? null : target.work().resolve("pack-backup")
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + "-sprites");
            SpriteMarker.Result r = SpriteMarker.prepare(target.assets(), backup, externalPacks(target.assets()));
            markers = r;
            out.putAll(r.files());
            if (!r.unmarkable().isEmpty() && target.log() != null) {
                target.log().warning("Не удалось пометить текстуры HUD (эти иконки не сдвинутся): " + String.join(", ", r.unmarkable()));
            }
            if (!r.backups().isEmpty() && target.log() != null) {
                target.log().warning("Свои текстуры HUD сохранены в " + backup + " и помечены: " + String.join(", ", r.backups()));
            }
        }
        return out;
    }

    static List<Path> externalPacks(Path assets) {
        Path dir = assets == null || assets.getParent() == null ? null : assets.getParent().resolve("external_packs");
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public List<String> status() {
        return settings.warnings();
    }

    public HudSettings settings() {
        return settings;
    }

    public Map<Element, Placement> placements() {
        return placements;
    }

    public SpriteMarker.Result markers() {
        return markers;
    }

    public boolean active() {
        return GlslConfig.active(placements);
    }
}
