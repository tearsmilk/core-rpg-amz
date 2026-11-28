package ink.hoka.odyssey.coreshaders;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;

/**
 * Клики жезлом и по эффектам. ПКМ по блоку — поставить, Shift+ПКМ — сменить картинку, ЛКМ — убрать эффект под прицелом,
 * Shift+ЛКМ — сменить форму. ПКМ по эффекту без жезла — команды эффекта (триггер click). Жезл — только у операторов.
 * <p>
 * У Minestom нет события «ЛКМ в воздух» — это взмах руки (PlayerHandAnimationEvent). Взмах приходит и после ПКМ по
 * блоку, поэтому щелчки считаются не чаще раза за тик на игрока и сторону (как в оригинальном плагине).
 */
final class Interactions {
    private final CoreShadersModule module;
    private final Map<UUID, Long> lastLeft = new HashMap<>();
    private final Map<UUID, Long> lastRight = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    Interactions(CoreShadersModule module) {
        this.module = module;
    }

    void register() {
        var ev = module.events();
        ev.addListener(PlayerBlockInteractEvent.class, this::onBlockInteract);
        ev.addListener(PlayerUseItemEvent.class, this::onUseItem);
        ev.addListener(PlayerEntityInteractEvent.class, this::onEntityInteract);
        ev.addListener(EntityAttackEvent.class, this::onAttack);
        ev.addListener(PlayerBlockBreakEvent.class, this::onBreak);
        ev.addListener(PlayerStartDiggingEvent.class, this::onStartDigging);
        ev.addListener(PlayerHandAnimationEvent.class, this::onSwing);
        ev.addListener(PlayerDisconnectEvent.class, e -> {
            UUID id = e.getPlayer().getUuid();
            lastLeft.remove(id);
            lastRight.remove(id);
            lastUse.remove(id);
        });
    }

    private boolean once(Map<UUID, Long> map, Player p) {
        long tick = module.tick();
        Long prev = map.put(p.getUuid(), tick);
        return prev == null || prev != tick;
    }

    /** Не чаще раза в ticks тиков (зажатая ЛКМ по блоку в выживании шлёт взмахи подряд). */
    private boolean every(Map<UUID, Long> map, Player p, int ticks) {
        long tick = module.tick();
        Long prev = map.get(p.getUuid());
        if (prev != null && tick - prev < ticks) {
            return false;
        }
        map.put(p.getUuid(), tick);
        return true;
    }

    private boolean sameTick(Map<UUID, Long> map, Player p) {
        Long prev = map.get(p.getUuid());
        return prev != null && prev == module.tick();
    }

    private ItemStack heldWand(Player p) {
        ItemStack item = p.getItemInMainHand();
        return Wand.isWand(item) ? item : null;
    }

    private boolean allowed(Player p) {
        return module.server().isOp(p);
    }

    static Dir dir(BlockFace f) {
        return switch (f) {
            case TOP -> Dir.UP;
            case BOTTOM -> Dir.DOWN;
            case NORTH -> Dir.NORTH;
            case SOUTH -> Dir.SOUTH;
            case WEST -> Dir.WEST;
            case EAST -> Dir.EAST;
        };
    }

    // ───────────────────────────── ПКМ ─────────────────────────────

    private void onBlockInteract(PlayerBlockInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null) {
            return;
        }
        e.setCancelled(true);
        e.setBlockingItemUse(true);
        if (e.getHand() != PlayerHand.MAIN || !allowed(p) || !once(lastRight, p)) {
            return;
        }
        if (p.isSneaking()) {
            cycleImage(p, wand);
            return;
        }
        Point b = e.getBlockPosition();
        place(p, wand, b.blockX(), b.blockY(), b.blockZ(), dir(e.getBlockFace()));
    }

    private void onUseItem(PlayerUseItemEvent e) {
        Player p = e.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null || e.getHand() != PlayerHand.MAIN) {
            return;
        }
        e.setCancelled(true);
        if (!allowed(p) || !once(lastRight, p)) {
            return;
        }
        if (p.isSneaking()) {
            cycleImage(p, wand);
        } else {
            p.sendActionBar(module.settings().msg("click-block"));
        }
    }

    private void onEntityInteract(PlayerEntityInteractEvent e) {
        Effect fx = module.effects().effectOf(e.getTarget());
        if (fx == null) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null) {
            if (e.getHand() == PlayerHand.MAIN && once(lastUse, p)) {
                module.triggers().click(p, fx);
            }
            return;
        }
        if (e.getHand() != PlayerHand.MAIN || !allowed(p) || !once(lastRight, p)) {
            return;
        }
        if (p.isSneaking()) {
            cycleImage(p, wand);
            return;
        }
        EffectData d = fx.data;
        if (d.shape() == Shape.CUBE) {
            Point c = e.getInteractPosition();
            Dir f = c == null ? Dir.UP : Geometry.faceFromLocal(c.x(), c.y() - 0.5, c.z());
            place(p, wand, d.bx(), d.by(), d.bz(), f);
            return;
        }
        p.sendActionBar(module.settings().msg("occupied"));
    }

    private void place(Player p, ItemStack wand, int x, int y, int z, Dir face) {
        Settings s = module.settings();
        EffectWorld w = module.effects().world(p.getInstance());
        if (w == null) {
            p.sendActionBar(s.msg("not-map"));
            return;
        }
        Wand.State st = Wand.read(s, wand);
        Effects.PlaceResult r = module.effects().place(w, x, y, z, face, st);
        switch (r) {
            case OK -> p.sendActionBar(s.msg("placed", "shape", st.shape().id(), "image", s.imageName(st.image())));
            case NOT_AIR -> p.sendActionBar(s.msg("not-air"));
            case NOT_FREE -> p.sendActionBar(s.msg("not-free"));
            case BLOCKED -> p.sendActionBar(s.msg("plane-blocked"));
            case NO_SUPPORT -> p.sendActionBar(s.msg("no-support"));
        }
    }

    // ───────────────────────────── ЛКМ ─────────────────────────────

    private void onAttack(EntityAttackEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        Effect fx = module.effects().effectOf(e.getTarget());
        if (fx == null) {
            return;
        }
        ItemStack wand = heldWand(p);
        if (wand != null && allowed(p)) {
            left(p, wand, fx);
        }
    }

    private void onBreak(PlayerBlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null) {
            return;
        }
        e.setCancelled(true);
        if (allowed(p)) {
            left(p, wand, null);
        }
    }

    private void onStartDigging(PlayerStartDiggingEvent e) {
        Player p = e.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null) {
            return;
        }
        e.setCancelled(true);
        if (allowed(p)) {
            left(p, wand, null);
        }
    }

    /** ЛКМ в воздух — только взмах. Взмах после ПКМ в том же тике — не ЛКМ. */
    private void onSwing(PlayerHandAnimationEvent e) {
        if (e.getHand() != PlayerHand.MAIN) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack wand = heldWand(p);
        if (wand == null || !allowed(p) || sameTick(lastRight, p) || sameTick(lastUse, p)) {
            return;
        }
        left(p, wand, null);
    }

    private void left(Player p, ItemStack wand, Effect hit) {
        if (!every(lastLeft, p, 4)) {
            return;
        }
        Settings s = module.settings();
        if (p.isSneaking()) {
            Wand.State st = Wand.read(s, wand);
            Wand.State n = new Wand.State(st.shape().next(), st.image(), st.color());
            p.setItemInMainHand(Wand.write(s, wand, n));
            p.sendActionBar(s.msg("wand-shape", "shape", n.shape().id()));
            return;
        }
        Effect fx = hit != null ? hit : module.effects().target(p);
        EffectWorld w = fx == null ? null : module.effects().worldOf(fx);
        if (fx == null || w == null) {
            p.sendActionBar(s.msg("nothing-targeted", "distance", fmt(s.maxDistance)));
            return;
        }
        module.effects().remove(w, fx);
        p.sendActionBar(s.msg("removed"));
    }

    private void cycleImage(Player p, ItemStack wand) {
        Settings s = module.settings();
        Wand.State st = Wand.read(s, wand);
        Wand.State n = new Wand.State(st.shape(), Cycle.next(s.images, st.image(), 1), st.color());
        p.setItemInMainHand(Wand.write(s, wand, n));
        p.sendActionBar(s.msg("wand-image", "image", s.imageName(n.image())));
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
