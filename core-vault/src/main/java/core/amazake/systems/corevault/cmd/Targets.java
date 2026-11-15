package core.amazake.systems.corevault.cmd;

import core.amazake.systems.corevault.data.Account;
import core.amazake.systems.corevault.data.AccountStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Игрок по имени: в сети, известный core-vault (есть счёт) или из кэша сервера. Без запросов в сеть. */
public final class Targets {

    private Targets() {
    }

    public record Target(UUID id, String name, Player online) {
    }

    public static Target resolve(final AccountStore store, final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        final Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            return new Target(p.getUniqueId(), p.getName(), p);
        }
        final UUID known = store.byName(name);
        if (known != null) {
            final Account a = store.get(known);
            return new Target(known, a == null ? name : a.name(), null);
        }
        final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            return new Target(cached.getUniqueId(), cached.getName() == null ? name : cached.getName(), null);
        }
        return null;
    }

    /** Подсказки имён: онлайн + известные счета. */
    public static List<String> complete(final AccountStore store, final String prefix, final boolean offline) {
        final String p = prefix.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(pl.getName());
            }
        }
        if (offline && out.size() < 50) {
            for (final String n : store.names()) {
                if (n.toLowerCase(Locale.ROOT).startsWith(p) && !out.contains(n)) {
                    out.add(n);
                    if (out.size() >= 50) {
                        break;
                    }
                }
            }
        }
        return out;
    }
}
