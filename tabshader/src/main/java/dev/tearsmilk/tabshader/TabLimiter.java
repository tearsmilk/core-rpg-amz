package dev.tearsmilk.tabshader;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class TabLimiter {

    static final String PRIORITY_PREFIX = "tabshader.tab.priority.";

    record Rank(UUID id, String name, int priority) {
    }

    record Split(List<UUID> kept, List<UUID> hidden) {
    }

    static final Comparator<Rank> ORDER = Comparator.comparingInt((Rank r) -> -r.priority())
            .thenComparing(r -> r.name().toLowerCase(Locale.ROOT))
            .thenComparing(Rank::name)
            .thenComparing(Rank::id);

    private final Map<UUID, Set<UUID>> hiddenBy = new HashMap<>();
    private final Map<UUID, Integer> shown = new HashMap<>();
    private long changes;

    static int priority(Collection<String> grantedPermissions) {
        int best = 0;
        for (String perm : grantedPermissions) {
            if (perm.length() > PRIORITY_PREFIX.length() && perm.regionMatches(true, 0, PRIORITY_PREFIX, 0, PRIORITY_PREFIX.length())) {
                String n = perm.substring(PRIORITY_PREFIX.length());
                if (n.length() <= 6 && n.chars().allMatch(Character::isDigit)) {
                    best = Math.max(best, Integer.parseInt(n));
                }
            }
        }
        return best;
    }

    static List<Rank> order(Collection<Rank> ranks) {
        List<Rank> out = new ArrayList<>(ranks);
        out.sort(ORDER);
        return out;
    }

    static Split split(UUID viewer, List<Rank> ordered, Predicate<UUID> candidate, int limit) {
        List<UUID> kept = new ArrayList<>();
        List<UUID> hidden = new ArrayList<>();
        int room = Math.max(0, limit - 1);
        for (Rank r : ordered) {
            if (r.id().equals(viewer) || !candidate.test(r.id())) {
                continue;
            }
            if (kept.size() < room) {
                kept.add(r.id());
            } else {
                hidden.add(r.id());
            }
        }
        return new Split(kept, hidden);
    }

    private static int priority(Player p) {
        List<String> granted = new ArrayList<>();
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (info.getValue() && info.getPermission().regionMatches(true, 0, PRIORITY_PREFIX, 0, PRIORITY_PREFIX.length())) {
                granted.add(info.getPermission());
            }
        }
        return priority(granted);
    }

    void reconcile(int limit) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        boolean limiting = limit > 0 && online.size() > limit;
        if (!limiting && hiddenBy.isEmpty()) {
            shown.clear();
            for (Player viewer : online) {
                int n = 1;
                for (Player other : online) {
                    if (other != viewer && viewer.canSee(other) && viewer.isListed(other)) {
                        n++;
                    }
                }
                shown.put(viewer.getUniqueId(), n);
            }
            return;
        }
        Map<UUID, Player> byId = new HashMap<>();
        List<Rank> ranks = new ArrayList<>(online.size());
        for (Player p : online) {
            byId.put(p.getUniqueId(), p);
            ranks.add(new Rank(p.getUniqueId(), p.getName(), priority(p)));
        }
        List<Rank> ordered = order(ranks);
        shown.clear();
        for (Player viewer : online) {
            Set<UUID> ours = hiddenBy.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
            ours.retainAll(byId.keySet());
            Predicate<UUID> candidate = id -> {
                Player other = byId.get(id);
                return other != null && viewer.canSee(other) && (ours.contains(id) || viewer.isListed(other));
            };
            Split s = split(viewer.getUniqueId(), ordered, candidate, limiting ? limit : Integer.MAX_VALUE);
            for (UUID id : s.kept()) {
                if (ours.remove(id) && viewer.listPlayer(byId.get(id))) {
                    changes++;
                }
            }
            for (UUID id : s.hidden()) {
                Player other = byId.get(id);
                if (viewer.isListed(other)) {
                    if (viewer.unlistPlayer(other)) {
                        ours.add(id);
                        changes++;
                    }
                } else {
                    ours.add(id);
                }
            }
            if (ours.isEmpty()) {
                hiddenBy.remove(viewer.getUniqueId());
            }
            shown.put(viewer.getUniqueId(), 1 + s.kept().size());
        }
    }

    void quit(Player quitter) {
        UUID id = quitter.getUniqueId();
        hiddenBy.remove(id);
        shown.remove(id);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Set<UUID> ours = hiddenBy.get(viewer.getUniqueId());
            if (ours != null && ours.remove(id)) {
                relist(viewer, quitter);
                if (ours.isEmpty()) {
                    hiddenBy.remove(viewer.getUniqueId());
                }
            }
        }
    }

    private static void relist(Player viewer, Player other) {
        if (viewer.canSee(other)) {
            viewer.listPlayer(other);
        }
    }

    void relistAll() {
        for (Map.Entry<UUID, Set<UUID>> e : hiddenBy.entrySet()) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer == null) {
                continue;
            }
            for (UUID id : e.getValue()) {
                Player other = Bukkit.getPlayer(id);
                if (other != null) {
                    relist(viewer, other);
                }
            }
        }
        hiddenBy.clear();
        shown.clear();
    }

    int shown(Player viewer) {
        Integer n = shown.get(viewer.getUniqueId());
        return n == null ? 1 : n;
    }

    int hidden(Player viewer) {
        Set<UUID> s = hiddenBy.get(viewer.getUniqueId());
        return s == null ? 0 : s.size();
    }

    int hiddenTotal() {
        int n = 0;
        for (Set<UUID> s : hiddenBy.values()) {
            n += s.size();
        }
        return n;
    }

    long changes() {
        return changes;
    }
}
