package dev.tearsmilk.tabshader;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

public final class Toggles {

    private final NamespacedKey key;

    public Toggles(String namespace) {
        this.key = new NamespacedKey(namespace.toLowerCase(Locale.ROOT), "off");
    }

    public static Set<Part> parse(String s) {
        Set<Part> out = EnumSet.noneOf(Part.class);
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String k : s.split(",")) {
            Part p = Part.parse(k.trim());
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    public static String format(Set<Part> off) {
        StringJoiner j = new StringJoiner(",");
        for (Part p : Part.values()) {
            if (off.contains(p)) {
                j.add(p.key());
            }
        }
        return j.toString();
    }

    public Set<Part> off(Player p) {
        return parse(p.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    public boolean on(Player p, Part part) {
        return !off(p).contains(part);
    }

    public boolean set(Player p, Part part, boolean on) {
        Set<Part> off = off(p);
        if (on) {
            off.remove(part);
        } else {
            off.add(part);
        }
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (off.isEmpty()) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.STRING, format(off));
        }
        return on;
    }
}
