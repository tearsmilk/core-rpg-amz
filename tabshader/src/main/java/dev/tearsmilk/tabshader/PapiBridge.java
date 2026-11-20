package dev.tearsmilk.tabshader;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

final class PapiBridge {

    private PapiBridge() {
    }

    static String apply(Player p, String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        return PlaceholderAPI.setPlaceholders(p, s);
    }
}
