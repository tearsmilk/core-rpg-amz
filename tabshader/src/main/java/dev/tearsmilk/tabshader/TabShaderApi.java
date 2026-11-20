package dev.tearsmilk.tabshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;

public final class TabShaderApi {

    private static volatile TabShaderPlugin plugin;
    private static final Widths FALLBACK = Widths.load();

    private TabShaderApi() {
    }

    static void bind(TabShaderPlugin p) {
        plugin = p;
    }

    private static Widths widths() {
        TabShaderPlugin p = plugin;
        return p == null ? FALLBACK : p.widths();
    }

    public static Component actionbar(Component text) {
        return Backing.wrap(text, Backing.Kind.ACTIONBAR, widths());
    }

    public static Component title(Component text) {
        return Backing.wrap(text, Backing.Kind.TITLE, widths());
    }

    public static Component subtitle(Component text) {
        return Backing.wrap(text, Backing.Kind.SUBTITLE, widths());
    }

    private static boolean on(Player p, Part part) {
        TabShaderPlugin pl = plugin;
        return pl == null || pl.toggles().on(p, part);
    }

    public static void sendActionBar(Player p, Component text) {
        p.sendActionBar(on(p, Part.ACTIONBAR) ? actionbar(text) : text);
    }

    public static void showTitle(Player p, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        boolean on = on(p, Part.TITLE);
        Component t = title == null ? Component.empty() : title;
        Component s = subtitle == null ? Component.empty() : subtitle;
        Title.Times times = Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L));
        p.showTitle(Title.title(on ? title(t) : t, on ? subtitle(s) : s, times));
    }
}
