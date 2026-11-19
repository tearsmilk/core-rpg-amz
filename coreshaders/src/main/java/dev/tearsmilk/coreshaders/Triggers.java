package dev.tearsmilk.coreshaders;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.util.BoundingBox;

public final class Triggers implements Listener {
    private static final double EPS = 1e-4;
    private static final double TOUCH = 0.01;
    private static final double STEP = 0.25;
    private static final double MAX_SWEEP = 16.0;

    private final CoreShadersPlugin plugin;
    private final Map<UUID, Map<Long, Set<UUID>>> index = new HashMap<>();
    private final Map<UUID, Map<Long, Integer>> planeCells = new HashMap<>();
    private final Map<UUID, Spot> where = new HashMap<>();
    private final Map<UUID, Long> lastFire = new HashMap<>();

    private record Spot(UUID world, long key, boolean plane, double[] box, String group) {
    }

    public Triggers(CoreShadersPlugin plugin) {
        this.plugin = plugin;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public void index(ItemDisplay d) {
        unindex(d);
        if (!d.isValid() || !plugin.effects().hasActions(d)) {
            return;
        }
        Actions.Data a = plugin.effects().actions(d);
        if (!a.active() || !a.trigger().onEnter()) {
            return;
        }
        EffectData e = plugin.effects().data(d);
        long k = key(e.bx(), e.by(), e.bz());
        UUID world = d.getWorld().getUID();
        boolean plane = e.shape() != Shape.CUBE;
        index.computeIfAbsent(world, w -> new HashMap<>()).computeIfAbsent(k, x -> new HashSet<>()).add(d.getUniqueId());
        if (plane) {
            planeCells.computeIfAbsent(world, w -> new HashMap<>()).merge(k, 1, Integer::sum);
        }
        double[] box = Geometry.box(e.shape(), e.bx(), e.by(), e.bz(), e.face());
        where.put(d.getUniqueId(), new Spot(world, k, plane, box, a.group()));
    }

    public void unindex(Entity d) {
        Spot sp = where.remove(d.getUniqueId());
        if (sp == null) {
            return;
        }
        if (sp.plane()) {
            Map<Long, Integer> pc = planeCells.get(sp.world());
            if (pc != null) {
                pc.computeIfPresent(sp.key(), (k, v) -> v <= 1 ? null : v - 1);
                if (pc.isEmpty()) {
                    planeCells.remove(sp.world());
                }
            }
        }
        Map<Long, Set<UUID>> m = index.get(sp.world());
        if (m == null) {
            return;
        }
        Set<UUID> set = m.get(sp.key());
        if (set != null && set.remove(d.getUniqueId()) && set.isEmpty()) {
            m.remove(sp.key());
        }
        if (m.isEmpty()) {
            index.remove(sp.world());
        }
    }

    public int size() {
        return where.size();
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            if (where.containsKey(e.getUniqueId())) {
                unindex(e);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastFire.remove(event.getPlayer().getUniqueId());
    }

    static double[] playerBox(double x, double y, double z, double hw, double h) {
        return new double[] {x - hw, y, z - hw, x + hw, y + h, z + hw};
    }

    static boolean touches(double[] p, double[] b, boolean plane) {
        double t = plane ? TOUCH : -EPS;
        for (int a = 0; a < 3; a++) {
            if (p[a] >= b[a + 3] + t || p[a + 3] <= b[a] - t) {
                return false;
            }
        }
        return true;
    }

    private void inside(double[] pb, Map<Long, Set<UUID>> m, Map<String, ItemDisplay> out) {
        int x0 = (int) Math.floor(pb[0] - TOUCH);
        int x1 = (int) Math.floor(pb[3] + TOUCH);
        int y0 = (int) Math.floor(pb[1] - TOUCH);
        int y1 = (int) Math.floor(pb[4] + TOUCH);
        int z0 = (int) Math.floor(pb[2] - TOUCH);
        int z1 = (int) Math.floor(pb[5] + TOUCH);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Set<UUID> set = m.get(key(x, y, z));
                    if (set == null) {
                        continue;
                    }
                    for (UUID id : set) {
                        Spot sp = where.get(id);
                        if (sp == null || out.containsKey(sp.group()) || !touches(pb, sp.box(), sp.plane())) {
                            continue;
                        }
                        Entity en = Bukkit.getEntity(id);
                        if (en instanceof ItemDisplay d && d.isValid()) {
                            out.put(sp.group(), d);
                        }
                    }
                }
            }
        }
    }

    private static boolean nearPlane(double[] pb, Map<Long, Integer> pc) {
        if (pc == null || pc.isEmpty()) {
            return false;
        }
        int x0 = (int) Math.floor(pb[0] - TOUCH);
        int x1 = (int) Math.floor(pb[3] + TOUCH);
        int y0 = (int) Math.floor(pb[1] - TOUCH);
        int y1 = (int) Math.floor(pb[4] + TOUCH);
        int z0 = (int) Math.floor(pb[2] - TOUCH);
        int z1 = (int) Math.floor(pb[5] + TOUCH);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (pc.containsKey(key(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean sameCells(Location a, Location b, double hw, double h) {
        return Math.floor(a.getX() - hw + EPS) == Math.floor(b.getX() - hw + EPS)
            && Math.floor(a.getX() + hw - EPS) == Math.floor(b.getX() + hw - EPS)
            && Math.floor(a.getZ() - hw + EPS) == Math.floor(b.getZ() - hw + EPS)
            && Math.floor(a.getZ() + hw - EPS) == Math.floor(b.getZ() + hw - EPS)
            && Math.floor(a.getY() + EPS) == Math.floor(b.getY() + EPS)
            && Math.floor(a.getY() + h - EPS) == Math.floor(b.getY() + h - EPS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (index.isEmpty()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        World w = to.getWorld();
        if (w == null || from.getWorld() != w) {
            return;
        }
        Map<Long, Set<UUID>> m = index.get(w.getUID());
        if (m == null || m.isEmpty()) {
            return;
        }
        Player p = event.getPlayer();
        BoundingBox bb = p.getBoundingBox();
        double hw = bb.getWidthX() / 2.0;
        double h = bb.getHeight();
        double[] toBox = playerBox(to.getX(), to.getY(), to.getZ(), hw, h);
        if (sameCells(from, to, hw, h) && !nearPlane(toBox, planeCells.get(w.getUID()))) {
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Map<String, ItemDisplay> after = new LinkedHashMap<>();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > STEP && dist <= MAX_SWEEP) {
            int steps = (int) Math.ceil(dist / STEP);
            for (int i = 1; i < steps; i++) {
                double f = (double) i / steps;
                inside(playerBox(from.getX() + dx * f, from.getY() + dy * f, from.getZ() + dz * f, hw, h), m, after);
            }
        }
        inside(toBox, m, after);
        if (after.isEmpty()) {
            return;
        }
        Map<String, ItemDisplay> before = new LinkedHashMap<>();
        inside(playerBox(from.getX(), from.getY(), from.getZ(), hw, h), m, before);
        for (Map.Entry<String, ItemDisplay> en : after.entrySet()) {
            if (!before.containsKey(en.getKey())) {
                fire(p, en.getValue(), false);
                return;
            }
        }
    }

    public boolean click(Player p, ItemDisplay d) {
        if (!plugin.effects().hasActions(d)) {
            return false;
        }
        return fire(p, d, true);
    }

    public boolean fire(Player p, ItemDisplay d, boolean click) {
        Actions.Data a = plugin.effects().actions(d);
        if (!a.active() || (click ? !a.trigger().onClick() : !a.trigger().onEnter())) {
            return false;
        }
        Settings s = plugin.settings();
        if (a.permission() != null && !a.permission().isEmpty() && !p.hasPermission(a.permission())) {
            if (click) {
                p.sendActionBar(s.msg("trigger-no-permission"));
            }
            return true;
        }
        long now = System.currentTimeMillis();
        if (!Actions.ready(lastFire.get(p.getUniqueId()), now, a.cooldown())) {
            if (click) {
                p.sendActionBar(s.msg("trigger-cooldown"));
            }
            return true;
        }
        lastFire.put(p.getUniqueId(), now);
        EffectData e = plugin.effects().data(d);
        Location l = p.getLocation();
        Map<String, String> values = new HashMap<>();
        values.put("player", p.getName());
        values.put("uuid", p.getUniqueId().toString());
        values.put("world", p.getWorld().getName());
        values.put("x", String.valueOf(l.getBlockX()));
        values.put("y", String.valueOf(l.getBlockY()));
        values.put("z", String.valueOf(l.getBlockZ()));
        values.put("ex", String.valueOf(e.bx()));
        values.put("ey", String.valueOf(e.by()));
        values.put("ez", String.valueOf(e.bz()));
        List<Actions.Action> list = a.actions();
        if (!Actions.safeName(p.getName())) {
            boolean console = false;
            for (Actions.Action act : list) {
                console |= act.executor() == Actions.Executor.CONSOLE;
            }
            if (console) {
                plugin.getLogger().warning("Trigger skipped: unsafe player name for console commands: " + p.getName());
                return true;
            }
        }
        String message = a.message();
        UUID pid = p.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> run(pid, list, message, values));
        return true;
    }

    private void run(UUID pid, List<Actions.Action> list, String message, Map<String, String> values) {
        Player p = Bukkit.getPlayer(pid);
        if (p == null || !p.isOnline()) {
            return;
        }
        if (message != null && !message.isEmpty()) {
            try {
                MiniMessage mm = MiniMessage.miniMessage();
                p.sendMessage(mm.deserialize(Actions.fill(message, values, mm::escapeTags),
                    Placeholder.unparsed("player", p.getName())));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Bad trigger message: " + ex.getMessage());
            }
        }
        for (Actions.Action act : list) {
            String cmd = Actions.fill(act.command(), values);
            try {
                if (act.executor() == Actions.Executor.CONSOLE) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } else if (p.isOnline()) {
                    p.performCommand(cmd);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Trigger command failed (" + cmd + "): " + ex.getMessage());
            }
        }
    }
}
