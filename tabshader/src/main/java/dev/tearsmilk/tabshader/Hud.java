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
    static final Component NONE = Component.empty();
    static final Component HIDDEN_HEADER = join(TabLayout.hiddenHeader());

    static final class State {
        Component header;
        Component footer;
        Board board;
        BossBar bar;
        boolean tabMoved;
        int tabLines;
        boolean pingHidden;
        boolean tabHidden;
        boolean boardTooWide;
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

        if (!s.tabVisible()) {
            hideTab(p, st);
        } else if (s.tab() && !off.contains(Part.TAB)) {
            st.tabHidden = false;
            List<Component> headerLines = new ArrayList<>(t.render(p, s.header(), v));
            List<Component> footerLines = new ArrayList<>(t.render(p, s.footer(), v));
            int hidden = plugin.limiter().hidden(p);
            if (hidden > 0) {
                footerLines.add(t.get("tab-hidden", "count", hidden));
            }
            TabDecor decor = decorateTab(p, s, headerLines, footerLines, bossbarRows(p, s, off));
            int band = decor.band();
            st.tabMoved = band >= 0 && s.tabPosition() != PositionCode.Position.CENTER;
            st.tabLines = band >= 0 ? decor.lines() : 0;
            st.pingHidden = PositionCode.tabPingHidden(band);
            Component header = join(headerLines);
            Component footer = join(footerLines);
            if (!Objects.equals(header, st.header) || !Objects.equals(footer, st.footer)) {
                p.sendPlayerListHeaderAndFooter(header, footer);
                st.header = header;
                st.footer = footer;
                packets++;
            }
        } else if (s.tab()) {
            st.tabMoved = false;
            st.pingHidden = false;
            st.tabHidden = false;
            if (st.header != BLANK || st.footer != BLANK) {
                p.sendPlayerListHeaderAndFooter(BLANK, BLANK);
                st.header = BLANK;
                st.footer = BLANK;
                packets++;
            }
        } else if (st.header != null || st.footer != null) {
            st.tabMoved = false;
            st.pingHidden = false;
            st.tabHidden = false;
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
                int avoidLines = st.tabMoved ? st.tabLines : 0;
                boolean tooWide = boardTooWide(title, lines, s, plugin.widths(), avoidLines);
                if (tooWide && !st.boardTooWide) {
                    plugin.getLogger().warning("Табло для " + p.getName() + " шире " + maxWidth(s)
                            + " px: board.position не применяется, табло стоит справа как в ванили");
                }
                st.boardTooWide = tooWide;
                title = boardTitle(title, lines, s, plugin.widths(), avoidLines);
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

    void hideTab(Player p) {
        hideTab(p, states.computeIfAbsent(p.getUniqueId(), k -> new State()));
    }

    private void hideTab(Player p, State st) {
        st.tabMoved = false;
        st.tabLines = 0;
        st.pingHidden = false;
        st.tabHidden = true;
        Component current = p.playerListHeader();
        boolean foreign = current != null && !HIDDEN_HEADER.equals(current);
        if (st.header != HIDDEN_HEADER || st.footer != NONE || foreign) {
            p.sendPlayerListHeaderAndFooter(HIDDEN_HEADER, NONE);
            st.header = HIDDEN_HEADER;
            st.footer = NONE;
            packets++;
        }
    }

    int tabHidden() {
        int n = 0;
        for (State st : states.values()) {
            if (st.tabHidden) {
                n++;
            }
        }
        return n;
    }

    int tabWidth(Settings s) {
        return tabWidth(s, 0, !s.pingBars());
    }

    int tabWidth(Settings s, int bossbarRows) {
        return tabWidth(s, bossbarRows, false);
    }

    int tabWidth(Settings s, int bossbarRows, boolean pingHidden) {
        if (s.markers() && (s.tabPosition() != PositionCode.Position.CENTER || bossbarRows > 0 || pingHidden)) {
            return PositionCode.TAB_WIDTH;
        }
        return s.tabWidth() == Settings.AUTO_WIDTH ? PositionCode.TAB_WIDTH : s.tabWidth();
    }

    int bossbarRows(Player p, Settings s, Set<Part> off) {
        if (s.bossbarRows() >= 0) {
            return s.bossbarRows();
        }
        boolean own = s.bossbar() && !off.contains(Part.BOSSBAR);
        if (s.bossbarRows() == Settings.ROWS_OWN) {
            return own ? 1 : 0;
        }
        int n = 0;
        boolean ownCounted = false;
        State st = states.get(p.getUniqueId());
        for (BossBar bar : p.activeBossBars()) {
            n++;
            if (st != null && bar == st.bar) {
                ownCounted = true;
            }
        }
        return own && !ownCounted ? n + 1 : n;
    }

    boolean listFits(Player p) {
        int name = 0;
        for (Player other : Bukkit.getOnlinePlayers()) {
            name = Math.max(name, plugin.names().width(other, plugin.widths()));
        }
        int entries = Math.max(1, plugin.limiter().shown(p));
        return TabLayout.listWidth(entries, name, true) <= PositionCode.TAB_WIDTH;
    }

    static boolean tabShifts(boolean fits, int headerLines, int lines) {
        return fits && headerLines <= PositionCode.TAB_MAX_HEADER && PositionCode.tabFits(lines);
    }

    record TabDecor(int band, int lines) {
    }

    TabDecor decorateTab(Player p, Settings s, List<Component> headerLines, List<Component> footerLines, int bossbarRows) {
        Widths w = plugin.widths();
        if (headerLines.isEmpty()) {
            headerLines.add(BLANK);
        }
        if (footerLines.isEmpty()) {
            footerLines.add(BLANK);
        }
        Component first = headerLines.getFirst();
        boolean fits = s.markers() && listFits(p);
        int bars = fits ? bossbarRows : 0;
        int rows = TabLayout.rows(Math.max(1, plugin.limiter().shown(p)));
        int lines = headerLines.size() + rows + footerLines.size();
        boolean shifts = tabShifts(fits, headerLines.size(), lines);
        boolean noPing = shifts && !s.pingBars();
        TabLayout.Padded padded = TabLayout.pad(first, w.width(first), tabWidth(s, bars, noPing), w);
        Component line = padded.line();
        int pad = 0;
        if (shifts) {
            pad = PositionCode.tabPadLines(s.tabPosition(), s.tabMargin(), lines, bars, noPing);
        }
        if (pad > 0) {
            line = Component.text()
                    .append(TabLayout.marker(PositionCode.tabMarker(headerLines.size())))
                    .append(line)
                    .build();
        }
        headerLines.set(0, line);
        if (pad > 0) {
            List<Component> all = TabLayout.tabHeader(headerLines, pad);
            headerLines.clear();
            headerLines.addAll(all);
        }
        int band = pad > 0 ? PositionCode.tabBand(PositionCode.tabCode(s.tabPosition(), s.tabMargin(), bars, noPing), noPing) : -1;
        return new TabDecor(band, lines);
    }

    static boolean boardTooWide(Component title, List<Component> lines, Settings s, Widths w, int tabLines) {
        PositionCode.Position pos = s.boardPosition();
        boolean avoid = avoidsTab(s) && PositionCode.avoids(pos, s.tabPosition(), tabLines);
        if (!s.markers() || (pos == PositionCode.Position.RIGHT && !avoid)) {
            return false;
        }
        int n = Math.min(Board.MAX_LINES, lines.size());
        int content = measure(title, w);
        for (int i = 0; i < n; i++) {
            content = Math.max(content, measure(lines.get(i), w));
        }
        return content > maxWidth(s);
    }

    static boolean avoidsTab(Settings s) {
        return s.boardAvoid() == PositionCode.Avoid.OVERLAP && PositionCode.sameSide(s.tabPosition(), s.boardPosition());
    }

    static int boardAlign(Settings s) {
        return s.boardPosition() == PositionCode.Position.RIGHT ? 0 : s.boardMargin();
    }

    static int maxWidth(Settings s) {
        return PositionCode.boardMaxWidth(boardAlign(s));
    }

    int pingHidden() {
        int n = 0;
        for (State st : states.values()) {
            if (st.pingHidden) {
                n++;
            }
        }
        return n;
    }

    int boardTooWide() {
        int n = 0;
        for (State st : states.values()) {
            if (st.board != null && st.boardTooWide) {
                n++;
            }
        }
        return n;
    }

    static int measure(Component c, Widths w) {
        int width = w.width(c);
        return width >= 0 ? width : w.width(TabLayout.plain(c), false);
    }

    static Component boardTitle(Component title, List<Component> lines, Settings s, Widths w, int tabLines) {
        PositionCode.Position pos = s.boardPosition();
        boolean avoid = s.boardAvoid() == PositionCode.Avoid.OVERLAP && PositionCode.avoids(pos, s.tabPosition(), tabLines);
        if (!s.markers() || (pos == PositionCode.Position.RIGHT && !avoid)) {
            return title;
        }
        int n = Math.min(Board.MAX_LINES, lines.size());
        int content = 0;
        for (int i = 0; i < n; i++) {
            content = Math.max(content, measure(lines.get(i), w));
        }
        int titleWidth = measure(title, w);
        if (Math.max(content, titleWidth) > maxWidth(s)) {
            return title;
        }
        int code = avoid ? PositionCode.boardCode(pos, n, s.boardMargin(), s.tabPosition(), s.tabMargin(), tabLines)
                : PositionCode.boardCode(pos, n, s.boardMargin());
        return TabLayout.boardTitle(title, titleWidth, content, code, boardAlign(s));
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
