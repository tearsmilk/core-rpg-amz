package ink.hoka.odyssey.coreshaders;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;

/**
 * Команды на эффектах: срабатывают, когда игрок входит в блок эффекта (триггер enter; для плоскости — касается её),
 * или по ПКМ (click). Индекс по блокам — чтобы проверка движения стоила почти ничего, пока рядом нет порталов.
 */
final class Triggers {
    private static final double EPS = 1e-4;
    private static final double TOUCH = 0.01;
    private static final double STEP = 0.25;
    private static final double MAX_SWEEP = 16.0;

    /** Право «op» на эффекте — срабатывает только у операторов. */
    static final String OP = "op";

    private final CoreShadersModule module;
    private final Map<EffectWorld, Map<Long, Set<Effect>>> index = new HashMap<>();
    private final Map<EffectWorld, Map<Long, Integer>> planeCells = new HashMap<>();
    private final Map<UUID, Spot> where = new HashMap<>();
    private final Map<UUID, Long> lastFire = new HashMap<>();

    private record Spot(EffectWorld world, long key, boolean plane, double[] box, String group) {
    }

    Triggers(CoreShadersModule module) {
        this.module = module;
    }

    void register() {
        module.events().addListener(PlayerMoveEvent.class, this::onMove);
        module.events().addListener(PlayerDisconnectEvent.class, e -> lastFire.remove(e.getPlayer().getUuid()));
    }

    private static long key(int x, int y, int z) {
        return EffectWorld.key(x, y, z);
    }

    void indexWorld(EffectWorld w) {
        for (Effect e : w.all()) {
            index(w, e);
        }
    }

    void unindexWorld(EffectWorld w) {
        for (Effect e : w.all()) {
            unindex(e);
        }
        index.remove(w);
        planeCells.remove(w);
    }

    void index(EffectWorld w, Effect e) {
        unindex(e);
        if (!e.hasActions()) {
            return;
        }
        Actions.Data a = e.actions;
        if (!a.active() || !a.trigger().onEnter()) {
            return;
        }
        EffectData d = e.data;
        long k = key(d.bx(), d.by(), d.bz());
        boolean plane = d.shape() != Shape.CUBE;
        index.computeIfAbsent(w, x -> new HashMap<>()).computeIfAbsent(k, x -> new HashSet<>()).add(e);
        if (plane) {
            planeCells.computeIfAbsent(w, x -> new HashMap<>()).merge(k, 1, Integer::sum);
        }
        double[] box = Geometry.box(d.shape(), d.bx(), d.by(), d.bz(), d.face());
        where.put(e.id, new Spot(w, k, plane, box, a.group()));
    }

    void unindex(Effect e) {
        Spot sp = where.remove(e.id);
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
        Map<Long, Set<Effect>> m = index.get(sp.world());
        if (m == null) {
            return;
        }
        Set<Effect> set = m.get(sp.key());
        if (set != null && set.remove(e) && set.isEmpty()) {
            m.remove(sp.key());
        }
        if (m.isEmpty()) {
            index.remove(sp.world());
        }
    }

    int size() {
        return where.size();
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

    private void inside(double[] pb, Map<Long, Set<Effect>> m, Map<String, Effect> out) {
        int x0 = (int) Math.floor(pb[0] - TOUCH);
        int x1 = (int) Math.floor(pb[3] + TOUCH);
        int y0 = (int) Math.floor(pb[1] - TOUCH);
        int y1 = (int) Math.floor(pb[4] + TOUCH);
        int z0 = (int) Math.floor(pb[2] - TOUCH);
        int z1 = (int) Math.floor(pb[5] + TOUCH);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Set<Effect> set = m.get(key(x, y, z));
                    if (set == null) {
                        continue;
                    }
                    for (Effect e : set) {
                        Spot sp = where.get(e.id);
                        if (sp == null || out.containsKey(sp.group()) || !touches(pb, sp.box(), sp.plane())) {
                            continue;
                        }
                        out.put(sp.group(), e);
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

    private static boolean sameCells(Pos a, Pos b, double hw, double h) {
        return Math.floor(a.x() - hw + EPS) == Math.floor(b.x() - hw + EPS)
            && Math.floor(a.x() + hw - EPS) == Math.floor(b.x() + hw - EPS)
            && Math.floor(a.z() - hw + EPS) == Math.floor(b.z() - hw + EPS)
            && Math.floor(a.z() + hw - EPS) == Math.floor(b.z() + hw - EPS)
            && Math.floor(a.y() + EPS) == Math.floor(b.y() + EPS)
            && Math.floor(a.y() + h - EPS) == Math.floor(b.y() + h - EPS);
    }

    /** Каждый тик у каждого игрока — поэтому сначала дешёвые выходы. */
    private void onMove(PlayerMoveEvent event) {
        if (index.isEmpty() || event.isCancelled()) {
            return;
        }
        Player p = event.getPlayer();
        EffectWorld w = module.effects().world(p.getInstance());
        if (w == null) {
            return;
        }
        Map<Long, Set<Effect>> m = index.get(w);
        if (m == null || m.isEmpty()) {
            return;
        }
        Pos from = p.getPosition();
        Pos to = event.getNewPosition();
        if (from.x() == to.x() && from.y() == to.y() && from.z() == to.z()) {
            return;
        }
        BoundingBox bb = p.getBoundingBox();
        double hw = bb.width() / 2.0;
        double h = bb.height();
        double[] toBox = playerBox(to.x(), to.y(), to.z(), hw, h);
        if (sameCells(from, to, hw, h) && !nearPlane(toBox, planeCells.get(w))) {
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Map<String, Effect> after = new LinkedHashMap<>();
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > STEP && dist <= MAX_SWEEP) {
            int steps = (int) Math.ceil(dist / STEP);
            for (int i = 1; i < steps; i++) {
                double f = (double) i / steps;
                inside(playerBox(from.x() + dx * f, from.y() + dy * f, from.z() + dz * f, hw, h), m, after);
            }
        }
        inside(toBox, m, after);
        if (after.isEmpty()) {
            return;
        }
        Map<String, Effect> before = new LinkedHashMap<>();
        inside(playerBox(from.x(), from.y(), from.z(), hw, h), m, before);
        for (Map.Entry<String, Effect> en : after.entrySet()) {
            if (!before.containsKey(en.getKey())) {
                fire(p, en.getValue(), false);
                return;
            }
        }
    }

    boolean click(Player p, Effect e) {
        return e.hasActions() && fire(p, e, true);
    }

    boolean fire(Player p, Effect e, boolean click) {
        Actions.Data a = e.actions;
        if (a == null || !a.active() || (click ? !a.trigger().onClick() : !a.trigger().onEnter())) {
            return false;
        }
        Settings s = module.settings();
        if (OP.equalsIgnoreCase(a.permission()) && !module.server().isOp(p)) {
            if (click) {
                p.sendActionBar(s.msg("trigger-no-permission"));
            }
            return true;
        }
        long now = System.currentTimeMillis();
        if (!Actions.ready(lastFire.get(p.getUuid()), now, a.cooldown())) {
            if (click) {
                p.sendActionBar(s.msg("trigger-cooldown"));
            }
            return true;
        }
        lastFire.put(p.getUuid(), now);
        EffectData d = e.data;
        Pos l = p.getPosition();
        EffectWorld w = module.effects().world(p.getInstance());
        Map<String, String> values = new HashMap<>();
        values.put("player", p.getUsername());
        values.put("uuid", p.getUuid().toString());
        values.put("world", w == null ? "" : w.map);
        values.put("x", String.valueOf(l.blockX()));
        values.put("y", String.valueOf(l.blockY()));
        values.put("z", String.valueOf(l.blockZ()));
        values.put("ex", String.valueOf(d.bx()));
        values.put("ey", String.valueOf(d.by()));
        values.put("ez", String.valueOf(d.bz()));
        List<Actions.Action> list = a.actions();
        if (!Actions.safeName(p.getUsername())) {
            for (Actions.Action act : list) {
                if (act.executor() == Actions.Executor.CONSOLE) {
                    module.logger().warn("триггер пропущен: ник {} не годится для команд от консоли", p.getUsername());
                    return true;
                }
            }
        }
        String message = a.message();
        module.scheduler().run(() -> run(p, list, message, values));
        return true;
    }

    /** В следующем тике (не внутри обработки движения): сообщение и команды. */
    private void run(Player p, List<Actions.Action> list, String message, Map<String, String> values) {
        if (!p.isOnline()) {
            return;
        }
        if (message != null && !message.isEmpty()) {
            p.sendMessage(Text.of(message, values));
        }
        var cm = MinecraftServer.getCommandManager();
        for (Actions.Action act : list) {
            String cmd = Actions.fill(act.command(), values);
            try {
                if (act.executor() == Actions.Executor.CONSOLE) {
                    cm.execute(cm.getConsoleSender(), cmd);
                } else if (p.isOnline()) {
                    cm.execute(p, cmd);
                }
            } catch (RuntimeException ex) {
                module.logger().warn("команда триггера не выполнилась ({}): {}", cmd, ex.toString());
            }
        }
    }
}
