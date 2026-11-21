package dev.tearsmilk.worldshader;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class Zones implements Listener {

    public static final String NAMESPACE = "worldshader";
    public static final String NO_SLOT = "no-slot";
    public static final String SPAWN_FAILED = "spawn-failed";

    private final WorldShaderPlugin plugin;
    private final Logger log;
    private final File file;
    private final NamespacedKey keyZone;
    private final NamespacedKey keySlot;
    private final NamespacedKey keyRadius;
    private final NamespacedKey keyStrength;
    private final NamespacedKey keyCreated;
    private final NamespacedKey keyExpires;
    private final Map<UUID, Zone> zones = new LinkedHashMap<>();
    private final Set<UUID> removed = new LinkedHashSet<>();

    public Zones(WorldShaderPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        this.keyZone = new NamespacedKey(plugin, "zone");
        this.keySlot = new NamespacedKey(plugin, "slot");
        this.keyRadius = new NamespacedKey(plugin, "radius");
        this.keyStrength = new NamespacedKey(plugin, "strength");
        this.keyCreated = new NamespacedKey(plugin, "created");
        this.keyExpires = new NamespacedKey(plugin, "expires");
    }

    public record Spawned(Zone zone, String error) {
    }

    public List<Zone> all() {
        return List.copyOf(zones.values());
    }

    public List<Zone> in(String world) {
        return zones.values().stream().filter(z -> z.world().equals(world)).toList();
    }

    public Zone nearest(Location l) {
        return in(l.getWorld().getName()).stream()
                .min(Comparator.comparingDouble(z -> z.distanceSquared(l.getX(), l.getY(), l.getZ()))).orElse(null);
    }

    public Spawned spawn(Location at, int radius, int strength, int seconds) {
        World w = at.getWorld();
        Set<Integer> used = new HashSet<>();
        for (Zone z : in(w.getName())) {
            used.add(z.slot());
        }
        int slot = Slots.free(used);
        if (slot < 0) {
            return new Spawned(null, NO_SLOT);
        }
        long now = System.currentTimeMillis();
        long expires = seconds <= 0 ? 0 : now + seconds * 1000L;
        int rgb = ZoneCode.rgb(slot, radius, strength);
        Location loc = at.clone();
        loc.setYaw(0);
        loc.setPitch(0);
        w.getChunkAt(loc);
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, disp -> {
            disp.setItemStack(markerItem());
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            disp.setPersistent(true);
            disp.setViewRange(plugin.settings().viewRange());
            disp.setGlowing(true);
            disp.setGlowColorOverride(Color.fromRGB(rgb));
            PersistentDataContainer pdc = disp.getPersistentDataContainer();
            pdc.set(keyZone, PersistentDataType.BYTE, (byte) 1);
            pdc.set(keySlot, PersistentDataType.INTEGER, slot);
            pdc.set(keyRadius, PersistentDataType.INTEGER, radius);
            pdc.set(keyStrength, PersistentDataType.INTEGER, strength);
            pdc.set(keyCreated, PersistentDataType.LONG, now);
            pdc.set(keyExpires, PersistentDataType.LONG, expires);
        });
        if (d.isDead() || !d.isInWorld()) {
            return new Spawned(null, SPAWN_FAILED);
        }
        Zone z = new Zone(d.getUniqueId(), w.getName(), loc.getX(), loc.getY(), loc.getZ(), slot, radius, strength, now, expires);
        zones.put(z.id(), z);
        save();
        return new Spawned(z, null);
    }

    public static ItemStack markerItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key(NAMESPACE, "marker"));
        return item;
    }

    public void remove(Zone z) {
        zones.remove(z.id());
        Entity e = Bukkit.getEntity(z.id());
        if (e != null) {
            e.remove();
        } else {
            tombstone(z.id());
        }
        save();
    }

    public int removeAll() {
        int n = zones.size();
        for (Zone z : List.copyOf(zones.values())) {
            zones.remove(z.id());
            Entity e = Bukkit.getEntity(z.id());
            if (e != null) {
                e.remove();
            } else {
                tombstone(z.id());
            }
        }
        save();
        return n;
    }

    private void tombstone(UUID id) {
        removed.add(id);
    }

    public void tickExpiry() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Zone z : List.copyOf(zones.values())) {
            if (z.expired(now)) {
                zones.remove(z.id());
                Entity e = Bukkit.getEntity(z.id());
                if (e != null) {
                    e.remove();
                } else {
                    tombstone(z.id());
                }
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    public void scan() {
        boolean changed = false;
        for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                changed |= check(d);
            }
        }
        for (Zone z : List.copyOf(zones.values())) {
            World w = Bukkit.getWorld(z.world());
            if (w == null) {
                continue;
            }
            int cx = (int) Math.floor(z.x()) >> 4;
            int cz = (int) Math.floor(z.z()) >> 4;
            if (!w.isChunkLoaded(cx, cz) || !w.getChunkAt(cx, cz).isEntitiesLoaded()) {
                continue;
            }
            Entity e = Bukkit.getEntity(z.id());
            if (e == null || e.isDead()) {
                zones.remove(z.id());
                log.info("Зона #" + z.slot() + " в " + z.world() + " пропала (маркер убит не через /wsh) - слот свободен.");
                changed = true;
            }
        }
        changed |= repairSlots();
        if (changed) {
            save();
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        boolean changed = false;
        for (Entity en : e.getEntities()) {
            if (en instanceof ItemDisplay d) {
                changed |= check(d);
            }
        }
        changed |= repairSlots();
        if (changed) {
            save();
        }
    }

    public boolean isMarker(Entity e) {
        return e instanceof ItemDisplay && e.getPersistentDataContainer().has(keyZone, PersistentDataType.BYTE);
    }

    private boolean check(ItemDisplay d) {
        if (d.isDead() || !isMarker(d)) {
            return false;
        }
        UUID id = d.getUniqueId();
        long now = System.currentTimeMillis();
        if (removed.contains(id)) {
            d.remove();
            removed.remove(id);
            return true;
        }
        Zone z = zones.get(id);
        if (z == null) {
            PersistentDataContainer pdc = d.getPersistentDataContainer();
            int slot = pdc.getOrDefault(keySlot, PersistentDataType.INTEGER, -1);
            int radius = pdc.getOrDefault(keyRadius, PersistentDataType.INTEGER, 0);
            int strength = pdc.getOrDefault(keyStrength, PersistentDataType.INTEGER, 0);
            long created = pdc.getOrDefault(keyCreated, PersistentDataType.LONG, now);
            long expires = pdc.getOrDefault(keyExpires, PersistentDataType.LONG, 0L);
            if (radius < 1 || radius > ZoneCode.MAX_RADIUS || strength < 1 || strength > ZoneCode.MAX_STRENGTH) {
                d.remove();
                return false;
            }
            Location l = d.getLocation();
            z = new Zone(id, d.getWorld().getName(), l.getX(), l.getY(), l.getZ(), slot, radius, strength, created, expires);
            if (z.expired(now)) {
                d.remove();
                return false;
            }
            zones.put(id, z);
            log.info("Найден маркер зоны вне списка: " + z.world() + " " + (int) z.x() + " " + (int) z.y() + " " + (int) z.z() + " - взят в список.");
            return true;
        }
        if (z.expired(now)) {
            zones.remove(id);
            d.remove();
            return true;
        }
        Location l = d.getLocation();
        boolean changed = false;
        if (!z.world().equals(d.getWorld().getName()) || z.distanceSquared(l.getX(), l.getY(), l.getZ()) > 1e-6) {
            z = z.at(d.getWorld().getName(), l.getX(), l.getY(), l.getZ());
            zones.put(id, z);
            changed = true;
        }
        apply(d, z);
        return changed;
    }

    private void apply(ItemDisplay d, Zone z) {
        if (z.slot() < 0 || z.slot() >= ZoneCode.SLOTS) {
            return;
        }
        Color want = Color.fromRGB(z.rgb());
        if (!want.equals(d.getGlowColorOverride())) {
            d.setGlowColorOverride(want);
        }
        if (!d.isGlowing()) {
            d.setGlowing(true);
        }
        if (d.getViewRange() != plugin.settings().viewRange()) {
            d.setViewRange(plugin.settings().viewRange());
        }
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        if (pdc.getOrDefault(keySlot, PersistentDataType.INTEGER, -1) != z.slot()) {
            pdc.set(keySlot, PersistentDataType.INTEGER, z.slot());
        }
    }

    private boolean repairSlots() {
        Map<String, List<Slots.Claim>> byWorld = new LinkedHashMap<>();
        for (Zone z : zones.values()) {
            byWorld.computeIfAbsent(z.world(), k -> new ArrayList<>()).add(new Slots.Claim(z.id(), z.slot(), z.created()));
        }
        boolean changed = false;
        for (List<Slots.Claim> claims : byWorld.values()) {
            for (Map.Entry<UUID, Integer> e : Slots.repair(claims).entrySet()) {
                Zone z = zones.get(e.getKey());
                int slot = e.getValue();
                if (z.slot() == slot) {
                    continue;
                }
                changed = true;
                Entity en = Bukkit.getEntity(z.id());
                if (slot < 0) {
                    zones.remove(z.id());
                    if (en != null) {
                        en.remove();
                    } else {
                        tombstone(z.id());
                    }
                    log.warning("Зона в " + z.world() + " " + (int) z.x() + " " + (int) z.y() + " " + (int) z.z()
                            + " убрана: в мире уже " + ZoneCode.SLOTS + " зон.");
                    continue;
                }
                z = z.withSlot(slot);
                zones.put(z.id(), z);
                if (en instanceof ItemDisplay d) {
                    apply(d, z);
                }
                log.info("Зоне в " + z.world() + " " + (int) z.x() + " " + (int) z.y() + " " + (int) z.z() + " выдан слот " + slot + ".");
            }
        }
        return changed;
    }

    public void refreshLoaded() {
        for (Zone z : zones.values()) {
            if (Bukkit.getEntity(z.id()) instanceof ItemDisplay d) {
                apply(d, z);
            }
        }
    }

    public void load() {
        zones.clear();
        removed.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("zones");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(key);
                Zone z = c == null ? null : read(key, c);
                if (z != null) {
                    zones.put(z.id(), z);
                }
            }
        }
        for (String id : y.getStringList("removed")) {
            try {
                removed.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        repairSlots();
    }

    static Zone read(String key, ConfigurationSection c) {
        try {
            UUID id = UUID.fromString(key);
            int radius = c.getInt("radius");
            int strength = c.getInt("strength");
            if (radius < 1 || radius > ZoneCode.MAX_RADIUS || strength < 1 || strength > ZoneCode.MAX_STRENGTH) {
                return null;
            }
            return new Zone(id, c.getString("world", "world"), c.getDouble("x"), c.getDouble("y"), c.getDouble("z"),
                    c.getInt("slot", -1), radius, strength, c.getLong("created"), c.getLong("expires"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void write(ConfigurationSection c, Zone z) {
        c.set("world", z.world());
        c.set("x", z.x());
        c.set("y", z.y());
        c.set("z", z.z());
        c.set("slot", z.slot());
        c.set("radius", z.radius());
        c.set("strength", z.strength());
        c.set("created", z.created());
        c.set("expires", z.expires());
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zone z : zones.values()) {
            write(y.createSection("zones." + z.id()), z);
        }
        y.set("removed", removed.stream().map(UUID::toString).toList());
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            log.warning("Не удалось сохранить " + file + ": " + e);
        }
    }
}
