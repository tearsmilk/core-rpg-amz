package core.amazake.systems.corenpc.npc;

import core.amazake.systems.corenpc.dialog.Action;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** npcs.yml ↔ {@link NpcDef}. Запись атомарная: во временный файл и переименование поверх. */
public final class NpcStore {

    private NpcStore() {
    }

    public static Map<String, NpcDef> read(final YamlConfiguration yaml, final Consumer<String> warn) {
        final Map<String, NpcDef> out = new LinkedHashMap<>();
        final ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) {
            return out;
        }
        for (final String id : root.getKeys(false)) {
            final ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                warn.accept("npcs." + id + ": должен быть разделом");
                continue;
            }
            final ConfigurationSection loc = s.getConfigurationSection("location");
            if (loc == null || !loc.contains("world")) {
                warn.accept("npcs." + id + ": нет location.world — пропускаю");
                continue;
            }
            NpcDef.Skin skin = null;
            final ConfigurationSection sk = s.getConfigurationSection("skin");
            if (sk != null && (sk.contains("value") || sk.contains("name"))) {
                skin = new NpcDef.Skin(sk.getString("name", ""), sk.getString("value", ""), sk.getString("signature", ""));
            }
            NpcDef.ModelSpec model = null;
            final ConfigurationSection md = s.getConfigurationSection("model");
            if (md != null && !md.getString("id", "").isBlank()) {
                model = new NpcDef.ModelSpec(md.getString("id"), md.getDouble("scale", 1.0), md.getString("idle", ""),
                    md.getString("interact", ""));
            }
            final List<Action> commands = new ArrayList<>();
            for (final String c : s.getStringList("commands")) {
                final Action a = Action.parse(c);
                if (a != null) {
                    commands.add(a);
                }
            }
            out.put(id, new NpcDef(id, s.getString("type", "mannequin"), loc.getString("world"), loc.getDouble("x"),
                loc.getDouble("y"), loc.getDouble("z"), (float) loc.getDouble("yaw"), (float) loc.getDouble("pitch"),
                s.getString("name", ""), s.getString("hologram", "name"), s.getStringList("hologram-lines"), skin, model,
                s.getString("dialog", ""), commands, s.getString("permission", ""), s.getDouble("cooldown", -1),
                s.getBoolean("left-click", false), s.getBoolean("look-at", true), s.getDouble("look-radius", -1),
                s.getBoolean("gravity", false), s.getBoolean("collidable", false), s.getDouble("scale", 1.0)));
        }
        return out;
    }

    public static YamlConfiguration write(final Collection<NpcDef> defs) {
        final YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("NPC системы core-npc. Правится командами /npc; вручную — затем /npc reload."));
        final ConfigurationSection root = y.createSection("npcs");
        for (final NpcDef d : defs) {
            final ConfigurationSection s = root.createSection(d.id());
            s.set("type", d.type());
            final ConfigurationSection loc = s.createSection("location");
            loc.set("world", d.world());
            loc.set("x", round(d.x()));
            loc.set("y", round(d.y()));
            loc.set("z", round(d.z()));
            loc.set("yaw", round(d.yaw()));
            loc.set("pitch", round(d.pitch()));
            s.set("name", d.name());
            s.set("hologram", d.hologram());
            if (!d.lines().isEmpty()) {
                s.set("hologram-lines", d.lines());
            }
            if (d.skin() != null) {
                final ConfigurationSection sk = s.createSection("skin");
                sk.set("name", d.skin().name());
                sk.set("value", d.skin().value());
                sk.set("signature", d.skin().signature());
            }
            if (d.model() != null) {
                final ConfigurationSection md = s.createSection("model");
                md.set("id", d.model().id());
                md.set("scale", d.model().scale());
                md.set("idle", d.model().idle());
                md.set("interact", d.model().interact());
            }
            s.set("dialog", d.dialog());
            final List<String> cmds = new ArrayList<>();
            for (final Action a : d.commands()) {
                cmds.add(a.serialize());
            }
            s.set("commands", cmds);
            s.set("permission", d.permission());
            s.set("cooldown", d.cooldown());
            s.set("left-click", d.leftClick());
            s.set("look-at", d.lookAt());
            s.set("look-radius", d.lookRadius());
            s.set("gravity", d.gravity());
            s.set("collidable", d.collidable());
            s.set("scale", d.scale());
        }
        return y;
    }

    private static double round(final double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public static Map<String, NpcDef> load(final Path file, final Consumer<String> warn) throws IOException {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        final YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final InvalidConfigurationException e) {
            throw new IOException("npcs.yml: ошибка YAML: " + e.getMessage(), e);
        }
        return read(y, warn);
    }

    /** Атомарная запись: tmp → move поверх (ATOMIC_MOVE, если ФС умеет). */
    public static void save(final Path file, final Collection<NpcDef> defs) throws IOException {
        final String text = write(defs).saveToString();
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
