package core.amazake.systems.rezusmenu.profiles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.potion.PotionEffect;

/**
 * Состояние персонажа в данных игрока: инвентарь с бронёй и второй рукой, эндер-сундук, выбранные ключи PDC
 * (бижутерия и хранилище rezusmenu), место, здоровье, голод, эффекты, ванильный опыт. Только в потоке игрока.
 * Предметы — {@link ItemStack#serializeAsBytes()} (с версией данных: переживут обновление сервера).
 */
final class Snapshot {

    private Snapshot() {
    }

    /** Снять состояние. {@code where} — настоящее место персонажа (в меню игрок висит в небе). */
    static ConfigurationSection capture(final Player p, final Location where, final ProfileConfig cfg) {
        final MemoryConfiguration s = new MemoryConfiguration();
        s.set("inventory", items(p.getInventory().getContents()));
        s.set("held-slot", p.getInventory().getHeldItemSlot());
        if (cfg.separateEnderChest()) {
            s.set("ender-chest", items(p.getEnderChest().getContents()));
        }
        final String pdc = pdc(p.getPersistentDataContainer(), cfg.pdcPrefixes());
        if (pdc != null) {
            s.set("pdc", pdc);
        }
        if (where != null && where.getWorld() != null) {
            s.set("location.world", where.getWorld().getName());
            s.set("location.x", where.getX());
            s.set("location.y", where.getY());
            s.set("location.z", where.getZ());
            s.set("location.yaw", (double) where.getYaw());
            s.set("location.pitch", (double) where.getPitch());
        }
        s.set("health", p.getHealth());
        s.set("food", p.getFoodLevel());
        s.set("saturation", (double) p.getSaturation());
        s.set("exhaustion", (double) p.getExhaustion());
        s.set("fire-ticks", p.getFireTicks());
        s.set("air", p.getRemainingAir());
        if (cfg.separateVanillaExp()) {
            s.set("xp.level", p.getLevel());
            s.set("xp.progress", (double) p.getExp());
            s.set("xp.total", p.getTotalExperience());
        }
        if (cfg.separateEffects()) {
            final List<Map<String, Object>> fx = new ArrayList<>();
            for (final PotionEffect e : p.getActivePotionEffects()) {
                fx.add(e.serialize());
            }
            s.set("effects", fx);
        }
        return s;
    }

    /** Надеть сохранённое состояние (без места: его ставит {@link #location}). */
    static void apply(final Player p, final ConfigurationSection s, final ProfileConfig cfg, final java.util.logging.Logger log) {
        p.getInventory().setContents(items(s.getStringList("inventory"), p.getInventory().getSize(), log));
        p.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, s.getInt("held-slot", 0))));
        if (cfg.separateEnderChest()) {
            p.getEnderChest().setContents(items(s.getStringList("ender-chest"), p.getEnderChest().getSize(), log));
        }
        restorePdc(p.getPersistentDataContainer(), cfg.pdcPrefixes(), s.getString("pdc"), log);
        body(p, s.getDouble("health", 20), s.getInt("food", 20), (float) s.getDouble("saturation", 5),
            (float) s.getDouble("exhaustion", 0));
        p.setFireTicks(Math.max(0, s.getInt("fire-ticks", 0)));
        p.setRemainingAir(Math.max(0, Math.min(p.getMaximumAir(), s.getInt("air", p.getMaximumAir()))));
        if (cfg.separateVanillaExp()) {
            p.setTotalExperience(Math.max(0, s.getInt("xp.total", 0)));
            p.setLevel(Math.max(0, s.getInt("xp.level", 0)));
            p.setExp((float) Math.max(0, Math.min(0.9999, s.getDouble("xp.progress", 0))));
        }
        if (cfg.separateEffects()) {
            clearEffects(p);
            for (final Map<?, ?> m : s.getMapList("effects")) {
                try {
                    @SuppressWarnings("unchecked")
                    final PotionEffect e = new PotionEffect((Map<String, Object>) m);
                    p.addPotionEffect(e);
                } catch (final RuntimeException e) {
                    log.warning("profiles: эффект " + m + " не восстановлен: " + e);
                }
            }
        }
    }

    /** Новый персонаж «с нуля»: пустые инвентари и ключи, полное здоровье и сытость, без опыта и эффектов. */
    static void fresh(final Player p, final ProfileConfig cfg) {
        p.getInventory().clear();
        p.getInventory().setHeldItemSlot(0);
        if (cfg.separateEnderChest()) {
            p.getEnderChest().clear();
        }
        restorePdc(p.getPersistentDataContainer(), cfg.pdcPrefixes(), null, null);
        body(p, Double.MAX_VALUE, 20, 5f, 0f);
        p.setFireTicks(0);
        p.setRemainingAir(p.getMaximumAir());
        if (cfg.separateVanillaExp()) {
            p.setTotalExperience(0);
            p.setLevel(0);
            p.setExp(0f);
        }
        if (cfg.separateEffects()) {
            clearEffects(p);
        }
    }

    /** Место из снимка; null — нет или мир не загружен. */
    static Location location(final ConfigurationSection s) {
        if (s == null || !s.isConfigurationSection("location")) {
            return null;
        }
        final World w = Bukkit.getWorld(s.getString("location.world", ""));
        if (w == null) {
            return null;
        }
        return new Location(w, s.getDouble("location.x"), s.getDouble("location.y"), s.getDouble("location.z"),
            (float) s.getDouble("location.yaw"), (float) s.getDouble("location.pitch"));
    }

    private static void body(final Player p, final double health, final int food, final float saturation,
                             final float exhaustion) {
        final AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        final double cap = max == null ? 20 : max.getValue();
        p.setHealth(Math.max(1, Math.min(cap, health)));
        p.setFoodLevel(Math.max(0, Math.min(20, food)));
        p.setSaturation(Math.max(0, Math.min(20, saturation)));
        p.setExhaustion(Math.max(0, exhaustion));
    }

    private static void clearEffects(final Player p) {
        for (final PotionEffect e : List.copyOf(p.getActivePotionEffects())) {
            p.removePotionEffect(e.getType());
        }
    }

    // --- предметы ---

    static List<String> items(final ItemStack[] items) {
        final List<String> out = new ArrayList<>(items.length);
        for (final ItemStack it : items) {
            out.add(it == null || it.isEmpty() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        }
        return out;
    }

    static ItemStack[] items(final List<String> raw, final int size, final java.util.logging.Logger log) {
        final ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < Math.min(size, raw.size()); i++) {
            final String b = raw.get(i);
            if (b == null || b.isEmpty()) {
                continue;
            }
            try {
                out[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(b));
            } catch (final RuntimeException e) {
                log.severe("profiles: предмет в слоте " + i + " не читается (" + e + ") — пропущен");
            }
        }
        return out;
    }

    // --- PDC: только ключи с нужными префиксами ---

    static boolean matches(final NamespacedKey key, final List<String> prefixes) {
        final String k = key.toString();
        for (final String p : prefixes) {
            if (!p.isBlank() && k.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Ключи с префиксами — в байты (base64); null — таких ключей нет. */
    private static String pdc(final PersistentDataContainer src, final List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return null;
        }
        final PersistentDataContainer tmp = src.getAdapterContext().newPersistentDataContainer();
        src.copyTo(tmp, true);
        for (final NamespacedKey k : List.copyOf(tmp.getKeys())) {
            if (!matches(k, prefixes)) {
                tmp.remove(k);
            }
        }
        if (tmp.isEmpty()) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(tmp.serializeToBytes());
        } catch (final IOException e) {
            throw new IllegalStateException("PDC не сериализуется", e);
        }
    }

    /** Убрать у игрока ключи с префиксами и положить сохранённые. */
    private static void restorePdc(final PersistentDataContainer dst, final List<String> prefixes, final String raw,
                                   final java.util.logging.Logger log) {
        if (prefixes.isEmpty()) {
            return;
        }
        for (final NamespacedKey k : List.copyOf(dst.getKeys())) {
            if (matches(k, prefixes)) {
                dst.remove(k);
            }
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            final PersistentDataContainer tmp = dst.getAdapterContext().newPersistentDataContainer();
            tmp.readFromBytes(Base64.getDecoder().decode(raw), true);
            tmp.copyTo(dst, true);
        } catch (final IOException | RuntimeException e) {
            if (log != null) {
                log.severe("profiles: ключи PDC не восстановлены: " + e);
            }
        }
    }
}
