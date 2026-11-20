package dev.tearsmilk.textshader;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

final class Papi extends PlaceholderExpansion {

    private final TextShaderPlugin plugin;

    Papi(TextShaderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "textshader";
    }

    @Override
    public String getAuthor() {
        return "tearsmilk";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String name = player != null && player.getName() != null ? player.getName() : "";
        String q = params.toLowerCase(Locale.ROOT);
        Player online = player != null ? player.getPlayer() : null;
        FxSpec nick = online != null ? plugin.active(online, Choices.Target.NICK) : null;
        FxSpec chat = online != null ? plugin.active(online, Choices.Target.CHAT) : null;
        switch (q) {
            case "nick" -> {
                return nick == null ? name : FxText.legacy(name, nick);
            }
            case "nick_mm" -> {
                return nick == null ? name : FxText.miniMessage(name, nick);
            }
            case "nick_color" -> {
                return nick == null ? "" : FxCode.legacyHex(nick.code());
            }
            case "nick_effect" -> {
                return nick == null ? "" : nick.effect().title();
            }
            case "chat_effect" -> {
                return chat == null ? "" : chat.effect().title();
            }
            case "chat_color" -> {
                return chat == null ? "" : FxCode.legacyHex(chat.code());
            }
            default -> {
            }
        }
        int cut = q.indexOf('_');
        if (cut < 0) {
            return null;
        }
        String kind = q.substring(0, cut);
        String[] a = q.substring(cut + 1).split("_", 2);
        FxSpec spec = TextShaderApi.spec(a[0], a.length > 1 ? a[1] : null);
        if (spec == null) {
            return null;
        }
        return switch (kind) {
            case "fx" -> FxText.legacy(name, spec);
            case "fxmm" -> FxText.miniMessage(name, spec);
            case "code" -> FxCode.legacyHex(spec.code());
            case "hex" -> FxCode.hex(spec.code());
            default -> null;
        };
    }
}
