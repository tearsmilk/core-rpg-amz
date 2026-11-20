package dev.tearsmilk.nameshader;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class DisplayStyler implements Listener {

    public static final String SKIP_TAG = "nameshader_skip";

    private final NameShaderPlugin plugin;
    private final NamespacedKey originalKey;

    public DisplayStyler(NameShaderPlugin plugin) {
        this.plugin = plugin;
        this.originalKey = new NamespacedKey(plugin, "original");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof TextDisplay display)) {
            return;
        }
        display.getScheduler().run(plugin, t -> apply(display, plugin.settings()), null);
    }

    public boolean styled(TextDisplay d) {
        return d.getPersistentDataContainer().has(originalKey, PersistentDataType.STRING);
    }

    public int apply(TextDisplay d, Settings s) {
        if (!d.isValid()) {
            return 0;
        }
        boolean mine = styled(d);
        if (!s.styleTextDisplays()) {
            return mine && restore(d) ? -1 : 0;
        }
        if (mine) {
            if (d.getScoreboardTags().contains(SKIP_TAG) || !s.tagAllowed(d.getScoreboardTags())) {
                return restore(d) ? -1 : 0;
            }
            if (d.isDefaultBackground() || background(d) != Marker.MARK_ARGB) {
                d.getPersistentDataContainer().remove(originalKey);
            }
            return 0;
        }
        boolean eligible = Marker.eligible(d.isDefaultBackground(), background(d),
                d.getScoreboardTags().contains(SKIP_TAG), s.tagAllowed(d.getScoreboardTags()));
        if (!eligible) {
            return 0;
        }
        mark(d);
        return 1;
    }

    public void mark(TextDisplay d) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        pdc.set(originalKey, PersistentDataType.STRING, Marker.encode(d.isDefaultBackground(), background(d)));
        d.setDefaultBackground(false);
        d.setBackgroundColor(Color.fromARGB(Marker.MARK_ARGB));
    }

    public boolean restore(TextDisplay d) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        Marker.Original o = Marker.decode(pdc.get(originalKey, PersistentDataType.STRING));
        pdc.remove(originalKey);
        if (o == null || d.isDefaultBackground() || background(d) != Marker.MARK_ARGB) {
            return false;
        }
        d.setBackgroundColor(Color.fromARGB(o.argb()));
        d.setDefaultBackground(o.defaultFlag());
        return true;
    }

    public static int background(TextDisplay d) {
        Color c = d.getBackgroundColor();
        return c == null ? Marker.VANILLA_DISPLAY_BACKGROUND : c.asARGB();
    }

    public void applyLoaded() {
        Settings s = plugin.settings();
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                d.getScheduler().run(plugin, t -> apply(d, s), null);
            }
        }
    }

    public void restoreLoaded() {
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (styled(d)) {
                    restore(d);
                }
            }
        }
    }

    public int[] countLoaded() {
        int styled = 0;
        int total = 0;
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                total++;
                if (styled(d)) {
                    styled++;
                }
            }
        }
        return new int[]{styled, total};
    }
}
