package ink.hoka.odyssey.gravzones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.color.Color;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.tag.Tag;

/**
 * Топорик выделения (деревянный топор с меткой в custom_data): ЛКМ по блоку — точка 1, ПКМ — точка 2; блок не
 * ломается и не ставится. Пока топорик в руке, раз в particles.period-ticks рисуются рамки выделения и зон рядом —
 * только этому игроку и не больше particles.max-per-player частиц.
 */
final class AxeListener {
    static final Tag<Boolean> AXE = Tag.Boolean("gravzones_axe");
    static final int SELECTION_COLOR = 0xFFD400;
    static final int WEAK_COLOR = 0x40C8FF;
    static final int STRONG_COLOR = 0xFF5030;
    static final int VACUUM_COLOR = 0xB040FF;

    private final GravZonesModule m;
    private final Map<UUID, Long> lastLeft = new HashMap<>();
    private final Map<UUID, Long> lastRight = new HashMap<>();

    AxeListener(GravZonesModule m) {
        this.m = m;
    }

    static ItemStack create() {
        return ItemStack.of(Material.WOODEN_AXE).withTag(AXE, true)
            .with(DataComponents.CUSTOM_NAME, Component.text("Топорик зон гравитации", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false))
            .with(DataComponents.LORE, List.of(
                line("ЛКМ по блоку — точка 1"),
                line("ПКМ по блоку — точка 2"),
                line("/hozone create <имя> <сила> — зона из выделения")))
            .with(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    private static Component line(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    static boolean isAxe(ItemStack item) {
        return item != null && item.material() == Material.WOODEN_AXE && Boolean.TRUE.equals(item.getTag(AXE));
    }

    void register() {
        var ev = m.events();
        ev.addListener(PlayerStartDiggingEvent.class, e -> {
            if (isAxe(e.getPlayer().getItemInMainHand())) {
                e.setCancelled(true);
                left(e.getPlayer(), e.getBlockPosition());
            }
        });
        ev.addListener(PlayerBlockBreakEvent.class, e -> {   // креатив ломает сразу, без StartDigging
            if (isAxe(e.getPlayer().getItemInMainHand())) {
                e.setCancelled(true);
                left(e.getPlayer(), e.getBlockPosition());
            }
        });
        ev.addListener(PlayerBlockInteractEvent.class, e -> {
            Player p = e.getPlayer();
            if (!isAxe(p.getItemInMainHand())) {
                return;
            }
            e.setCancelled(true);
            e.setBlockingItemUse(true);                   // и блок из второй руки не ставится
            if (e.getHand() == PlayerHand.MAIN) {
                right(p, e.getBlockPosition());
            }
        });
        ev.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (isAxe(e.getPlayer().getItemInMainHand())) {
                e.setCancelled(true);
            }
        });
        ev.addListener(PlayerUseItemEvent.class, e -> {
            if (e.getHand() == PlayerHand.MAIN && isAxe(e.getItemStack())) {
                e.setCancelled(true);
            }
        });
        ev.addListener(PlayerDisconnectEvent.class, e -> {
            lastLeft.remove(e.getPlayer().getUuid());
            lastRight.remove(e.getPlayer().getUuid());
        });
    }

    /** Не чаще раза в ticks тиков (зажатая кнопка шлёт события подряд). */
    private boolean every(Map<UUID, Long> map, Player p, int ticks) {
        Long prev = map.get(p.getUuid());
        if (prev != null && m.now() - prev < ticks) {
            return false;
        }
        map.put(p.getUuid(), m.now());
        return true;
    }

    private void left(Player p, Point b) {
        if (m.server().isOp(p) && every(lastLeft, p, 4)) {
            point(p, 1, b);
        }
    }

    private void right(Player p, Point b) {
        if (m.server().isOp(p) && every(lastRight, p, 2)) {
            point(p, 2, b);
        }
    }

    /** Поставить точку which (1/2) выделения в блок b и показать её в action bar. Только OP (проверяет вызывающий). */
    void point(Player p, int which, Point b) {
        String map = m.mapOf(p);
        if (map == null) {
            p.sendActionBar(Component.text("Этот мир не карта из maps/ — зоны здесь не ставятся", NamedTextColor.RED));
            return;
        }
        Selection sel = m.selection(p);
        sel.set(which, map, b.blockX(), b.blockY(), b.blockZ());
        String text = "Точка " + which + ": " + b.blockX() + " " + b.blockY() + " " + b.blockZ();
        if (sel.complete()) {
            text += " · объём " + sel.volume() + " бл. (" + sel.size() + ")";
        }
        p.sendActionBar(Component.text(text, which == 1 ? NamedTextColor.GOLD : NamedTextColor.YELLOW));
    }

    // ───────────────────────────── частицы ─────────────────────────────

    void drawAll() {
        Settings s = m.settings();
        if (s.particleMax() <= 0) {
            return;
        }
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (isAxe(p.getItemInMainHand())) {
                draw(p, s);
            }
        }
    }

    private void draw(Player p, Settings s) {
        String map = m.mapOf(p);
        if (map == null) {
            return;
        }
        List<Outline.Box> boxes = new ArrayList<>();
        Selection sel = m.selection(p);
        if (map.equals(sel.map)) {
            int[] a = sel.p1 != null ? sel.p1 : sel.p2;
            int[] b = sel.p2 != null ? sel.p2 : sel.p1;
            if (a != null) {
                boxes.add(Outline.Box.blocks(a[0], a[1], a[2], b[0], b[1], b[2], SELECTION_COLOR));
            }
        }
        for (Zone z : m.zones().on(map)) {
            boxes.add(Outline.Box.blocks(z.minX(), z.minY(), z.minZ(), z.maxX(), z.maxY(), z.maxZ(), color(z.g())));
        }
        if (boxes.isEmpty()) {
            return;
        }
        Pos pos = p.getPosition();
        List<Outline.Point> pts = Outline.points(boxes, pos.x(), pos.y() + 1, pos.z(), s.particleRadius(),
            s.particleMax(), s.particleStep());
        Map<Integer, Particle> byColor = new HashMap<>();
        for (Outline.Point pt : pts) {
            Particle particle = byColor.computeIfAbsent(pt.color(),
                c -> Particle.DUST.withProperties(new Color(c), s.particleSize()));
            p.sendPacket(new ParticlePacket(particle, pt.x(), pt.y(), pt.z(), 0f, 0f, 0f, 0f, 1));
        }
    }

    static int color(double g) {
        return Physics.isVacuum(g) ? VACUUM_COLOR : g < 1 ? WEAK_COLOR : STRONG_COLOR;
    }
}
