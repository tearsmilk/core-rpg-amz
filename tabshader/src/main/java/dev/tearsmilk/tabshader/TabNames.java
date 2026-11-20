package dev.tearsmilk.tabshader;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class TabNames {

    private record Applied(Component source, Component shown) {
    }

    private final Map<UUID, Applied> applied = new HashMap<>();
    private final Map<UUID, Integer> widths = new HashMap<>();

    void tick(int max, Widths w) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p, max, w);
        }
    }

    void update(Player p, int max, Widths w) {
        UUID id = p.getUniqueId();
        Component current = p.playerListName();
        Applied a = applied.get(id);
        if (a != null && Objects.equals(current, a.shown())) {
            return;
        }
        Component cut = TabLayout.truncate(current, max, w);
        if (cut != null && !cut.equals(current)) {
            p.playerListName(cut);
            applied.put(id, new Applied(current, cut));
            current = cut;
        } else {
            applied.remove(id);
        }
        widths.put(id, measure(current, p.getName(), w));
    }

    static int measure(Component name, String fallback, Widths w) {
        int width = name == null ? -1 : w.width(name);
        if (width >= 0) {
            return width;
        }
        String plain = name == null ? fallback : TabLayout.plain(name);
        return w.width(plain.isEmpty() ? fallback : plain, false);
    }

    int width(Player p, Widths w) {
        Integer v = widths.get(p.getUniqueId());
        return v != null ? v : measure(p.playerListName(), p.getName(), w);
    }

    void forget(Player p) {
        applied.remove(p.getUniqueId());
        widths.remove(p.getUniqueId());
    }

    void restoreAll() {
        for (Map.Entry<UUID, Applied> e : applied.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !Objects.equals(p.playerListName(), e.getValue().shown())) {
                continue;
            }
            Component src = e.getValue().source();
            p.playerListName(src.equals(p.displayName()) || Component.text(p.getName()).equals(src) ? null : src);
        }
        applied.clear();
        widths.clear();
    }

    int truncated() {
        return applied.size();
    }
}
