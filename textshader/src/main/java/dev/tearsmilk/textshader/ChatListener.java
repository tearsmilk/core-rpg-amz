package dev.tearsmilk.textshader;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
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
        plugin.forgetNick(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
            return;
        }
        Player p = e.getPlayer();
        boolean sanitize = plugin.getConfig().getBoolean("chat.sanitize", true);
        boolean use = p.hasPermission("textshader.use");
        if (!use && !sanitize) {
            return;
        }
        FxSpec base = use ? plugin.choices().get(p.getUniqueId(), Choices.Target.CHAT) : null;
        if (base != null && !p.hasPermission(base.effect().permission())) {
            base = null;
        }
        boolean tags = use && plugin.getConfig().getBoolean("chat.tags", true);
        e.message(ChatFx.apply(e.message(), base, tags, sanitize, ef -> use && p.hasPermission(ef.permission())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChatName(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat.nick", true)) {
            return;
        }
        FxSpec nick = plugin.active(e.getPlayer(), Choices.Target.NICK);
        if (nick == null) {
            return;
        }
        ChatRenderer old = e.renderer();
        e.renderer((source, name, message, viewer) -> old.render(source, nickName(name, nick), message, viewer));
    }

    static Component nickName(Component name, FxSpec nick) {
        return ChatFx.apply(name, nick, false, false, ef -> true);
    }
}
