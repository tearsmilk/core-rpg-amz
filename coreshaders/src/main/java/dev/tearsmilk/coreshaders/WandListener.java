package dev.tearsmilk.coreshaders;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public final class WandListener implements Listener {
    private final CoreShadersPlugin plugin;
    private final Map<UUID, Integer> lastLeft = new HashMap<>();
    private final Map<UUID, Integer> lastRight = new HashMap<>();
    private final Map<UUID, Integer> lastUse = new HashMap<>();

    public WandListener(CoreShadersPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean once(Map<UUID, Integer> map, Player p) {
        int tick = Bukkit.getCurrentTick();
        Integer prev = map.put(p.getUniqueId(), tick);
        return prev == null || prev != tick;
    }

    private ItemStack heldWand(Player p) {
        ItemStack item = p.getInventory().getItemInMainHand();
        return plugin.wand().isWand(item) ? item : null;
    }

    private static Dir dir(BlockFace f) {
        return switch (f) {
            case UP -> Dir.UP;
            case DOWN -> Dir.DOWN;
            case NORTH -> Dir.NORTH;
            case SOUTH -> Dir.SOUTH;
            case WEST -> Dir.WEST;
            case EAST -> Dir.EAST;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!p.hasPermission("coreshaders.admin")) {
            return;
        }
        Action a = event.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            left(p, wand, null);
            return;
        }
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!once(lastRight, p)) {
            return;
        }
        if (p.isSneaking()) {
            cycleImage(p, wand);
            return;
        }
        Block clicked = event.getClickedBlock();
        Dir face = dir(event.getBlockFace());
        if (a != Action.RIGHT_CLICK_BLOCK || clicked == null || face == null) {
            p.sendMessage(plugin.settings().msg("click-block"));
            return;
        }
        place(p, wand, clicked, face);
    }

    private void place(Player p, ItemStack wand, Block clicked, Dir face) {
        Settings s = plugin.settings();
        Wand.State st = plugin.wand().read(s, wand);
        Effects.PlaceResult r = plugin.effects().place(clicked, face, st);
        switch (r) {
            case OK -> p.sendActionBar(s.msg("placed", Placeholder.unparsed("shape", st.shape().id()),
                Placeholder.unparsed("image", s.imageName(st.image()))));
            case NOT_AIR -> p.sendActionBar(s.msg("not-air"));
            case NOT_FREE -> p.sendActionBar(s.msg("not-free"));
            case BLOCKED -> p.sendActionBar(s.msg("plane-blocked"));
            case NO_SUPPORT -> p.sendActionBar(s.msg("no-support"));
        }
    }

    private void left(Player p, ItemStack wand, ItemDisplay hit) {
        if (!once(lastLeft, p)) {
            return;
        }
        if (p.isSneaking()) {
            cycleShape(p, wand);
            return;
        }
        ItemDisplay d = hit != null ? hit : plugin.effects().target(p);
        Settings s = plugin.settings();
        if (d == null) {
            p.sendActionBar(s.msg("nothing-targeted", Placeholder.unparsed("distance", fmt(s.maxDistance))));
            return;
        }
        plugin.effects().remove(d);
        p.sendActionBar(s.msg("removed"));
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void cycleImage(Player p, ItemStack wand) {
        Settings s = plugin.settings();
        Wand.State st = plugin.wand().read(s, wand);
        List<String> images = s.images;
        Wand.State n = new Wand.State(st.shape(), Cycle.next(images, st.image(), 1), st.color());
        plugin.wand().write(s, wand, n);
        p.sendActionBar(s.msg("wand-image", Placeholder.unparsed("image", s.imageName(n.image()))));
    }

    private void cycleShape(Player p, ItemStack wand) {
        Settings s = plugin.settings();
        Wand.State st = plugin.wand().read(s, wand);
        Wand.State n = new Wand.State(st.shape().next(), st.image(), st.color());
        plugin.wand().write(s, wand, n);
        p.sendActionBar(s.msg("wand-shape", Placeholder.unparsed("shape", n.shape().id())));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Entity target = event.getAttacked();
        ItemDisplay d = plugin.effects().effectOf(target);
        if (d == null) {
            return;
        }
        event.setCancelled(true);
        Player p = event.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null || !p.hasPermission("coreshaders.admin")) {
            return;
        }
        left(p, wand, d);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (plugin.effects().effectOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        handleEntityRight(event, event.getClickedPosition());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        handleEntityRight(event, null);
    }

    private void handleEntityRight(PlayerInteractEntityEvent event, Vector clicked) {
        ItemDisplay d = plugin.effects().effectOf(event.getRightClicked());
        if (d == null) {
            return;
        }
        Player p = event.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null) {
            if (event.getHand() == EquipmentSlot.HAND && once(lastUse, p) && plugin.triggers().click(p, d)) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || !p.hasPermission("coreshaders.admin")) {
            return;
        }
        if (!once(lastRight, p)) {
            return;
        }
        if (p.isSneaking()) {
            cycleImage(p, wand);
            return;
        }
        EffectData e = plugin.effects().data(d);
        Block b = d.getWorld().getBlockAt(e.bx(), e.by(), e.bz());
        if (e.shape() == Shape.CUBE) {
            Dir f = clicked == null ? Dir.UP : Geometry.faceFromLocal(clicked.getX(), clicked.getY() - 0.5, clicked.getZ());
            place(p, wand, b, f);
            return;
        }
        p.sendActionBar(plugin.settings().msg("occupied"));
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<Entity> list = List.copyOf(event.getEntities());
        boolean ours = false;
        for (Entity e : list) {
            if (plugin.effects().isEffect(e) || plugin.effects().isHitbox(e)) {
                ours = true;
                break;
            }
        }
        if (ours) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.effects().verify(list, true), 2L);
        }
    }
}
