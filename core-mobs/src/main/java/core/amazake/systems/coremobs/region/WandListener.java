package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.Settings;
import core.amazake.systems.coremobs.geo.BlockPos;
import core.amazake.systems.coremobs.geo.Polygon;
import core.amazake.systems.coremobs.util.Text;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Топорик региона: ЛКМ по блоку — точка, ПКМ — убрать последнюю, Shift+ПКМ — сбросить. Поток игрока.
 * Работает и там, где другой плагин запретил взаимодействие (WorldGuard): выделять можно где угодно.
 */
public final class WandListener implements Listener {

    public static final String PERMISSION = "core.mobs.admin";

    private final Supplier<Settings> settings;
    private final Selections selections;

    public WandListener(final Supplier<Settings> settings, final Selections selections) {
        this.settings = settings;
        this.selections = selections;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !RegionView.isWand(event.getItem())) {
            return;
        }
        final Player player = event.getPlayer();
        if (!player.hasPermission(PERMISSION)) {
            return;
        }
        final Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null) {
            event.setCancelled(true);
            this.addPoint(player, event.getClickedBlock());
        } else if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
            event.setCancelled(true);
            if (player.isSneaking()) {
                this.selections.clear(player.getUniqueId());
                player.sendActionBar(Text.mm("<yellow>Выделение сброшено</yellow>"));
                return;
            }
            final BlockPos removed = this.selections.undo(player.getUniqueId());
            player.sendActionBar(removed == null ? Text.mm("<gray>Точек нет</gray>")
                : Text.mm("<yellow>Убрана точка <white><pos></white>, осталось <white><n></white></yellow>",
                    Placeholder.unparsed("pos", removed.format()), Placeholder.unparsed("n", Integer.toString(this.count(player)))));
        }
    }

    /** Топориком не ломают блоки, даже в творческом режиме. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(final BlockBreakEvent event) {
        if (RegionView.isWand(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    private void addPoint(final Player player, final Block block) {
        final BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        final Selections.AddResult result = this.selections.add(player.getUniqueId(), block.getWorld().getName(), pos,
            this.settings.get().maxPoints());
        switch (result) {
            case SAME_AS_LAST -> {
                return;
            }
            case LIMIT -> {
                player.sendActionBar(Text.mm("<red>Больше <n> точек нельзя</red>",
                    Placeholder.unparsed("n", Integer.toString(this.settings.get().maxPoints()))));
                return;
            }
            case NEW_WORLD -> player.sendMessage(Text.mm(Text.prefix() + "<yellow>Другой мир — выделение начато заново.</yellow>"));
            default -> {
            }
        }
        final Selections.Selection sel = this.selections.get(player.getUniqueId());
        final int n = sel == null ? 0 : sel.points().size();
        String area = "";
        if (n >= 3) {
            area = " · площадь ~" + new Polygon(sel.points()).blockArea() + " бл.";
        }
        player.sendActionBar(Text.mm("<green>Точка <white><n></white>: <white><pos></white><gray><area></gray></green>",
            Placeholder.unparsed("n", Integer.toString(n)), Placeholder.unparsed("pos", pos.format()),
            Placeholder.unparsed("area", area)));
    }

    private int count(final Player player) {
        final Selections.Selection sel = this.selections.get(player.getUniqueId());
        return sel == null ? 0 : sel.points().size();
    }
}
