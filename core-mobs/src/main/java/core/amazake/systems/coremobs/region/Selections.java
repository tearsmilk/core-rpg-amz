package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.geo.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Выделения топориком и регионы, которые игрок включил подсветкой. Всё — по игроку, неизменяемые снимки.
 */
public final class Selections {

    /** Точки выделения игрока в одном мире. */
    public record Selection(String world, List<BlockPos> points) {

        public Selection {
            points = List.copyOf(points);
        }
    }

    /** Результат добавления точки. */
    public enum AddResult { ADDED, SAME_AS_LAST, LIMIT, NEW_WORLD }

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> shown = new ConcurrentHashMap<>();

    public Selection get(final UUID player) {
        return this.selections.get(player);
    }

    /** Добавить вершину. Точка в другом мире начинает выделение заново. */
    public AddResult add(final UUID player, final String world, final BlockPos pos, final int maxPoints) {
        final AddResult[] result = {AddResult.ADDED};
        this.selections.compute(player, (id, old) -> {
            if (old == null || !old.world().equals(world)) {
                result[0] = old == null ? AddResult.ADDED : AddResult.NEW_WORLD;
                return new Selection(world, List.of(pos));
            }
            final List<BlockPos> points = old.points();
            if (!points.isEmpty() && points.get(points.size() - 1).equals(pos)) {
                result[0] = AddResult.SAME_AS_LAST;
                return old;
            }
            if (points.size() >= maxPoints) {
                result[0] = AddResult.LIMIT;
                return old;
            }
            final List<BlockPos> next = new ArrayList<>(points);
            next.add(pos);
            return new Selection(world, next);
        });
        return result[0];
    }

    /** Убрать последнюю точку; возвращает убранную или null. */
    public BlockPos undo(final UUID player) {
        final BlockPos[] removed = new BlockPos[1];
        this.selections.computeIfPresent(player, (id, old) -> {
            if (old.points().isEmpty()) {
                return null;
            }
            removed[0] = old.points().get(old.points().size() - 1);
            final List<BlockPos> next = old.points().subList(0, old.points().size() - 1);
            return next.isEmpty() ? null : new Selection(old.world(), next);
        });
        return removed[0];
    }

    public void clear(final UUID player) {
        this.selections.remove(player);
    }

    /** Включить или выключить подсветку региона игроку; true — теперь включена. */
    public boolean toggleShown(final UUID player, final String region) {
        final boolean[] on = new boolean[1];
        this.shown.compute(player, (id, old) -> {
            final Set<String> next = old == null ? ConcurrentHashMap.newKeySet() : old;
            if (!next.remove(region)) {
                next.add(region);
                on[0] = true;
            }
            return next.isEmpty() ? null : next;
        });
        return on[0];
    }

    public Set<String> shown(final UUID player) {
        final Set<String> set = this.shown.get(player);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    public void forget(final UUID player) {
        this.selections.remove(player);
        this.shown.remove(player);
    }
}
