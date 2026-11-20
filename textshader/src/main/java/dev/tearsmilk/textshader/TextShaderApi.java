package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class TextShaderApi {

    private static volatile Choices choices;

    private TextShaderApi() {
    }

    static void bind(Choices c) {
        choices = c;
    }

    public static TagResolver tags() {
        return FxTag.resolver();
    }

    public static List<String> effects() {
        return Effect.visible().stream().map(Effect::key).toList();
    }

    public static List<String> colors() {
        return Arrays.stream(Palette.values()).map(Palette::key).toList();
    }

    public static FxSpec spec(String effect, String color) {
        FxSpec s = FxSpec.parse(effect);
        if (s == null || color == null || color.isBlank()) {
            return s;
        }
        Palette p = Palette.byKey(color);
        return p == null ? null : s.withPalette(p);
    }

    public static Component render(String text, String effect, String color) {
        FxSpec s = spec(effect, color);
        return s == null ? Component.text(text) : FxText.render(text, s);
    }

    public static Component render(String text, FxSpec spec) {
        return FxText.render(text, spec);
    }

    public static String legacy(String text, String effect, String color) {
        FxSpec s = spec(effect, color);
        return s == null ? text : FxText.legacy(text, s);
    }

    public static String miniMessage(String text, String effect, String color) {
        FxSpec s = spec(effect, color);
        return s == null ? text : FxText.miniMessage(text, s);
    }

    public static int code(String effect, String color) {
        FxSpec s = spec(effect, color);
        return s == null ? -1 : s.code();
    }

    public static FxSpec chatEffect(UUID player) {
        Choices c = choices;
        return c == null ? null : c.get(player, Choices.Target.CHAT);
    }

    public static FxSpec nickEffect(UUID player) {
        Choices c = choices;
        return c == null ? null : c.get(player, Choices.Target.NICK);
    }

    public static Component nick(Player player) {
        FxSpec s = nickEffect(player.getUniqueId());
        return s == null ? Component.text(player.getName()) : FxText.render(player.getName(), s);
    }
}
