package dev.tearsmilk.tabshader;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class Hud {

    static final Component BLANK = Component.text(" ");

    static final class State {
        Component header;
        Component footer;
        Board board;
        BossBar bar;
    }

    private final TabShaderPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private long packets;

    Hud(TabShaderPlugin plugin) {
        this.plugin = plugin;
    }

    long packets() {
        return packets;
    }

    void tick() {
        Map<String, String> global = plugin.vars().global();
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p, global);
        }
    }

    void update(Player p) {
        update(p, plugin.vars().global());
    }

    static float progress(String mode, ZonedDateTime now, long worldTicks, int online, int max) {
        float v = switch (mode) {
            case "day" -> Math.floorMod(worldTicks + 6000L, 24000L) / 24000f;
            case "online" -> max <= 0 ? 0f : online / (float) max;
            default -> now.toLocalTime().toSecondOfDay() / 86400f;
        };
        return Math.max(0f, Math.min(1f, v));
    }

    void update(Player p, Map<String, String> global) {
        Settings s = plugin.settings();
        Texts t = plugin.texts();
        State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        Set<Part> off = plugin.toggles().off(p);
        Map<String, String> v = plugin.vars().of(p, global);

        if (s.tab() && !off.contains(Part.TAB)) {
            List<Component> headerLines = new ArrayList<>(t.render(p, s.header(), v));
            List<Component> footerLines = new ArrayList<>(t.render(p, s.footer(), v));
            int hidden = plugin.limiter().hidden(p);
            if (hidden > 0) {
                footerLines.add(t.get("tab-hidden", "count", hidden));
            }
            decorateTab(p, s, headerLines, footerLines);
            Component header = join(headerLines);
            Component footer = join(footerLines);
            if (!Objects.equals(header, st.header) || !Objects.equals(footer, st.footer)) {
                p.sendPlayerListHeaderAndFooter(header, footer);
                st.header = header;
                st.footer = footer;
                packets++;
            }
        } else if (s.tab()) {
            if (st.header != BLANK || st.footer != BLANK) {
                p.sendPlayerListHeaderAndFooter(BLANK, BLANK);
                st.header = BLANK;
                st.footer = BLANK;
                packets++;
            }
        } else if (st.header != null || st.footer != null) {
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            st.header = null;
            st.footer = null;
            packets++;
        }

        if (s.board() && !off.contains(Part.BOARD)) {
            boolean foreign = p.getScoreboard() != Bukkit.getScoreboardManager().getMainScoreboard()
                    && (st.board == null || !st.board.owns(p));
            if (!(foreign && s.respectOther())) {
                if (st.board == null) {
                    st.board = new Board();
                }
                Component title = t.render(p, s.boardTitle(), v);
                List<Component> lines = t.render(p, s.boardLines(), v);
                if (s.markers()) {
                    title = boardTitle(title, lines, s, plugin.widths());
                }
                packets += st.board.update(title, lines);
                st.board.show(p);
            }
        } else if (st.board != null) {
            st.board.hide(p);
            st.board = null;
        }

        if (s.bossbar() && !off.contains(Part.BOSSBAR)) {
            Component name = t.render(p, s.bossbarText(), v);
            float progress = progress(s.bossbarProgress(), plugin.vars().now(), p.getWorld().getTime(),
                    Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers());
            if (st.bar == null) {
                st.bar = BossBar.bossBar(name, progress, s.bossbarColor(), s.bossbarOverlay());
                p.showBossBar(st.bar);
                packets++;
            } else {
                if (!Objects.equals(st.bar.name(), name)) {
                    st.bar.name(name);
                    packets++;
                }
                if (Math.abs(st.bar.progress() - progress) > 0.0005f) {
                    st.bar.progress(progress);
                    packets++;
                }
                if (st.bar.color() != s.bossbarColor()) {
                    st.bar.color(s.bossbarColor());
                }
                if (st.bar.overlay() != s.bossbarOverlay()) {
                    st.bar.overlay(s.bossbarOverlay());
                }
            }
        } else if (st.bar != null) {
            p.hideBossBar(st.bar);
            st.bar = null;
            packets++;
        }
    }

    int tabWidth(Settings s) {
        if (s.tabWidth() != Settings.AUTO_WIDTH) {
            return s.tabWidth();
        }
        int chars = s.maxNameLength() > 0 ? s.maxNameLength() : 16;
        int cols = s.maxColumns() > 0 ? s.maxColumns() : 2;
        return TabLayout.defaultWidth(plugin.widths(), chars, cols);
    }

    private int maxNameWidth(Player viewer) {
        Widths w = plugin.widths();
        int best = plugin.names().width(viewer, w);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o != viewer && viewer.canSee(o) && viewer.isListed(o)) {
                best = Math.max(best, plugin.names().width(o, w));
            }
        }
        return best;
    }

    void decorateTab(Player p, Settings s, List<Component> headerLines, List<Component> footerLines) {
        Widths w = plugin.widths();
        if (headerLines.isEmpty()) {
            headerLines.add(BLANK);
        }
        Component first = headerLines.getFirst();
        TabLayout.Padded padded = TabLayout.pad(first, w.width(first), tabWidth(s), w);
        int shown = plugin.limiter().shown(p);
        int content = Math.max(padded.width(), TabLayout.listWidth(shown, maxNameWidth(p), true));
        for (int i = 1; i < headerLines.size(); i++) {
            content = Math.max(content, w.width(headerLines.get(i)));
        }
        for (Component c : footerLines) {
            content = Math.max(content, w.width(c));
        }
        Component line = padded.line();
        if (s.markers()) {
            int height = TabLayout.panelHeight(headerLines.size(), TabLayout.rows(shown), footerLines.size());
            line = Component.text()
                    .append(TabLayout.markers(PositionCode.Element.TAB, s.tabPosition(), s.tabMargin(), content, height))
                    .append(line)
                    .build();
        }
        headerLines.set(0, line);
    }

    static Component boardTitle(Component title, List<Component> lines, Settings s, Widths w) {
        int width = Math.max(0, w.width(title));
        int n = Math.min(Board.MAX_LINES, lines.size());
        for (int i = 0; i < n; i++) {
            width = Math.max(width, w.width(lines.get(i)));
        }
        int height = n * TabLayout.ROW + TabLayout.ROW + 1;
        return Component.text()
                .append(TabLayout.markers(PositionCode.Element.BOARD, s.boardPosition(), s.boardMargin(), width, height))
                .append(title)
                .build();
    }

    private static Component join(List<Component> lines) {
        if (lines.isEmpty()) {
            return BLANK;
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    void forget(Player p) {
        states.remove(p.getUniqueId());
    }

    void clear(Player p) {
        State st = states.remove(p.getUniqueId());
        if (st == null) {
            return;
        }
        if (st.header != null || st.footer != null) {
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        if (st.board != null) {
            st.board.hide(p);
        }
        if (st.bar != null) {
            p.hideBossBar(st.bar);
        }
    }

    void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            clear(p);
        }
        states.clear();
    }

    int tracked() {
        return states.size();
    }
}
