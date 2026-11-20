package dev.tearsmilk.textshader;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ChatListener implements Listener {

    private final TextShaderPlugin plugin;

    public ChatListener(TextShaderPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        plugin.choices().load(e.getPlayer());
        plugin.applyNick(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.choices().forget(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
            return;
        }
        Player p = e.getPlayer();
        if (!p.hasPermission("textshader.use")) {
            return;
        }
        FxSpec base = plugin.choices().get(p.getUniqueId(), Choices.Target.CHAT);
        if (base != null && !p.hasPermission(base.effect().permission())) {
            base = null;
        }
        Component message = e.message();
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        if (plugin.getConfig().getBoolean("chat.tags", true) && ChatTags.hasTags(plain)) {
            e.message(tagged(p, plain, base));
            return;
        }
        if (base == null) {
            return;
        }
        if (base.effect().indexed()) {
            e.message(FxText.render(plain, base));
        } else {
            e.message(message.color(TextColor.color(base.code())));
        }
    }

    static Component tagged(Player p, String plain, FxSpec base) {
        TextComponent.Builder b = Component.text();
        for (ChatTags.Segment s : ChatTags.parse(plain, base)) {
            FxSpec spec = s.spec();
            if (spec != null && p != null && !p.hasPermission(spec.effect().permission())) {
                spec = base;
            }
            b.append(spec == null ? Component.text(s.text()) : FxText.render(s.text(), spec));
        }
        return b.build();
    }
}
