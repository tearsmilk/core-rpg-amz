package dev.tearsmilk.tabshader;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class PositionCode {

    public static final int TAB_MARKER_R = 232;
    public static final int BOARD_MARKER_R = 233;
    public static final int TAB_WIDTH = 205;
    public static final int TAB_BAND = 32;
    public static final int TAB_BASE = 9;
    public static final int TAB_MAX_MARGIN = 15;
    public static final int TAB_MAX_LINES = TAB_BAND - 4;
    public static final int TAB_MAX_HEADER = 15;
    public static final int TAB_CENTER = 64;
    public static final int TAB_CODES = 80;
    public static final int TAB_NOPING = TAB_CODES;
    public static final int TAB_BANDS = 2 * TAB_CODES;
    public static final int MAX_BOSSBAR_ROWS = TAB_CODES - TAB_CENTER - 1;
    public static final int BOARD_CELL = 256;
    public static final int BOARD_BASE = 64;
    public static final int BOARD_MAX_MARGIN = 15;
    public static final int BOARD_MAX_LINES = 15;
    public static final int BOARD_SLACK = 8;
    public static final int BOARD_MAX_WIDTH = BOARD_CELL - 2 * BOARD_SLACK - 4;
    public static final int BOARD_PLAIN = 6 * 16 * 16;
    public static final int TAB_MARGIN_STEP = 3;
    public static final int TAB_MARGIN_LEVELS = TAB_MAX_MARGIN / TAB_MARGIN_STEP + 1;
    public static final int BOARD_EXACT = TAB_MAX_LINES * TAB_MARGIN_LEVELS;
    public static final int BOARD_CENTRED = 2 * 2 * 16 * BOARD_EXACT;
    public static final int BOARD_SAME = 2 * 16 * BOARD_EXACT;
    public static final int BOARD_COARSE_STEP = 32;
    public static final int BOARD_COARSE = 9;
    public static final int BOARD_OPPOSITE = 6 * 16 * 16 * BOARD_COARSE;
    public static final int BOARD_CODES = BOARD_PLAIN + BOARD_CENTRED + BOARD_SAME + BOARD_OPPOSITE;
    public static final int TAB_PANEL_EXTRA = 6;
    public static final int BOARD_GAP = 3;
    public static final int MAX_VALUE = 4095;

    public enum Element {
        TAB,
        BOARD
    }

    public enum Side {
        NONE,
        LEFT,
        RIGHT
    }

    public enum Position {
        CENTER("center", -1, -1),
        TOP_LEFT("top-left", 0, 1),
        TOP_RIGHT("top-right", 1, 2),
        BOTTOM_LEFT("bottom-left", 2, 3),
        BOTTOM_RIGHT("bottom-right", 3, 4),
        LEFT("left", -1, 0),
        RIGHT("right", -1, 5);

        private final String key;
        private final int tabSlot;
        private final int boardSlot;

        Position(String key, int tabSlot, int boardSlot) {
            this.key = key;
            this.tabSlot = tabSlot;
            this.boardSlot = boardSlot;
        }

        public String key() {
            return key;
        }

        public int tabSlot() {
            return tabSlot;
        }

        public int boardSlot() {
            return boardSlot;
        }

        public Side side() {
            return switch (this) {
                case TOP_LEFT, BOTTOM_LEFT, LEFT -> Side.LEFT;
                case TOP_RIGHT, BOTTOM_RIGHT, RIGHT -> Side.RIGHT;
                case CENTER -> Side.NONE;
            };
        }

        public boolean bottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }
    }

    public static final Set<Position> TAB_POSITIONS = EnumSet.of(Position.CENTER, Position.TOP_LEFT,
            Position.TOP_RIGHT, Position.BOTTOM_LEFT, Position.BOTTOM_RIGHT);
    public static final Set<Position> BOARD_POSITIONS = EnumSet.of(Position.TOP_LEFT, Position.TOP_RIGHT,
            Position.BOTTOM_LEFT, Position.BOTTOM_RIGHT, Position.LEFT, Position.RIGHT);

    private PositionCode() {
    }

    public static Position parse(String s, Element element, Position def) {
        if (s == null) {
            return def;
        }
        String k = s.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        Set<Position> allowed = element == Element.TAB ? TAB_POSITIONS : BOARD_POSITIONS;
        for (Position p : allowed) {
            if (p.key.equals(k)) {
                return p;
            }
        }
        return def;
    }

    public static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    public static int clampValue(int value) {
        return clamp(value, MAX_VALUE);
    }

    public static int tabCode(Position position, int margin) {
        if (position.tabSlot() < 0) {
            return -1;
        }
        return clamp(margin, TAB_MAX_MARGIN) * 4 + position.tabSlot();
    }

    public static int centerCode(int bossbarRows) {
        return centerCode(bossbarRows, false);
    }

    public static int centerCode(int bossbarRows, boolean pingHidden) {
        if (bossbarRows <= 0 && !pingHidden) {
            return -1;
        }
        return TAB_CENTER + clamp(bossbarRows, MAX_BOSSBAR_ROWS);
    }

    public static int tabCode(Position position, int margin, int bossbarRows) {
        return tabCode(position, margin, bossbarRows, false);
    }

    public static int tabCode(Position position, int margin, int bossbarRows, boolean pingHidden) {
        return position == Position.CENTER ? centerCode(bossbarRows, pingHidden) : tabCode(position, margin);
    }

    public static int tabBand(int code, boolean pingHidden) {
        return code < 0 ? -1 : code + (pingHidden ? TAB_NOPING : 0);
    }

    public static int tabLayout(int band) {
        return band < 0 ? -1 : band % TAB_CODES;
    }

    public static boolean tabPingHidden(int band) {
        return band >= TAB_NOPING;
    }

    public static boolean tabFits(int lines) {
        return lines >= 1 && lines <= TAB_MAX_LINES;
    }

    public static int tabPadLines(Position position, int margin, int lines) {
        return tabPadLines(position, margin, lines, 0);
    }

    public static int tabPadLines(Position position, int margin, int lines, int bossbarRows) {
        return tabPadLines(position, margin, lines, bossbarRows, false);
    }

    public static int tabPadLines(Position position, int margin, int lines, int bossbarRows, boolean pingHidden) {
        int band = tabBand(tabCode(position, margin, bossbarRows, pingHidden), pingHidden);
        if (band < 0 || !tabFits(lines)) {
            return 0;
        }
        int start = TAB_BAND * (band + TAB_BASE);
        return position.bottom() ? start + TAB_BAND - 2 - lines : start + 2;
    }

    public static int tabCodeAt(double y) {
        double c = Math.floor((y - 9.0) / (9.0 * TAB_BAND)) - TAB_BASE;
        return c < 0 || c >= TAB_BANDS ? -1 : (int) c;
    }

    public enum Avoid {
        NEVER("never"),
        OVERLAP("overlap");

        private final String key;

        Avoid(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Avoid parse(String s, Avoid def) {
            if (s == null) {
                return def;
            }
            String k = s.trim().toLowerCase(Locale.ROOT);
            for (Avoid a : values()) {
                if (a.key.equals(k)) {
                    return a;
                }
            }
            return def;
        }
    }

    public record BoardFields(int slot, int lines, int margin, boolean tab, boolean tabBottom, int tabExtent,
                              int tabMargin) {
    }

    public static int tabExtent(int tabMargin, int tabLines) {
        return clamp(tabMargin, TAB_MAX_MARGIN) + 9 * tabLines + TAB_PANEL_EXTRA;
    }

    public static int tabMarginLevel(int tabMargin) {
        return (clamp(tabMargin, TAB_MAX_MARGIN) + TAB_MARGIN_STEP - 1) / TAB_MARGIN_STEP;
    }

    public static int boardCode(Position position, int lines, int margin) {
        int slot = position.boardSlot();
        if (slot < 0) {
            return -1;
        }
        return (slot * 16 + clamp(lines, BOARD_MAX_LINES)) * 16 + clamp(margin, BOARD_MAX_MARGIN);
    }

    public static boolean avoids(Position board, Position tab, int tabLines) {
        return board.boardSlot() >= 0 && tab != null && tab.tabSlot() >= 0 && sameSide(tab, board) && tabFits(tabLines);
    }

    public static int boardCode(Position position, int lines, int margin, Position tab, int tabMargin, int tabLines) {
        if (!avoids(position, tab, tabLines)) {
            return boardCode(position, lines, margin);
        }
        int n = clamp(lines, BOARD_MAX_LINES);
        int exact = (tabLines - 1) * TAB_MARGIN_LEVELS + tabMarginLevel(tabMargin);
        if (position == Position.LEFT || position == Position.RIGHT) {
            int s = position == Position.LEFT ? 0 : 1;
            int end = tab.bottom() ? 1 : 0;
            return BOARD_PLAIN + ((s * 2 + end) * 16 + n) * BOARD_EXACT + exact;
        }
        int corner = position.boardSlot() - 1;
        if (!tab.bottom() && !position.bottom()) {
            return BOARD_PLAIN + BOARD_CENTRED + (corner * 16 + n) * BOARD_EXACT + exact;
        }
        int pair = corner < 2 ? corner : (tab.bottom() ? corner + 2 : corner);
        int extent = tabExtent(tabMargin, tabLines);
        int coarse = clamp((extent + BOARD_COARSE_STEP - 1) / BOARD_COARSE_STEP - 1, BOARD_COARSE - 1);
        return BOARD_PLAIN + BOARD_CENTRED + BOARD_SAME
                + ((pair * 16 + n) * 16 + clamp(margin, BOARD_MAX_MARGIN)) * BOARD_COARSE + coarse;
    }

    private static BoardFields exactTab(int slot, int lines, boolean bottom, int exact, boolean marginFromTab, int margin) {
        int m = TAB_MARGIN_STEP * (exact % TAB_MARGIN_LEVELS);
        int tabLines = exact / TAB_MARGIN_LEVELS + 1;
        return new BoardFields(slot, lines, marginFromTab ? m : margin, true, bottom, m + 9 * tabLines + TAB_PANEL_EXTRA, m);
    }

    public static BoardFields boardFields(int code) {
        if (code < BOARD_PLAIN) {
            return new BoardFields(code / 256, (code / 16) % 16, code % 16, false, false, 0, 0);
        }
        int r = code - BOARD_PLAIN;
        if (r < BOARD_CENTRED) {
            int exact = r % BOARD_EXACT;
            r /= BOARD_EXACT;
            int n = r % 16;
            r /= 16;
            boolean bottom = r % 2 == 1;
            return exactTab(r / 2 == 0 ? 0 : 5, n, bottom, exact, false, 0);
        }
        r -= BOARD_CENTRED;
        if (r < BOARD_SAME) {
            int exact = r % BOARD_EXACT;
            r /= BOARD_EXACT;
            return exactTab(r / 16 + 1, r % 16, false, exact, true, 0);
        }
        r -= BOARD_SAME;
        int coarse = r % BOARD_COARSE;
        r /= BOARD_COARSE;
        int margin = r % 16;
        r /= 16;
        int pair = r / 16;
        int slot = pair < 4 ? pair + 1 : pair - 1;
        return new BoardFields(slot, r % 16, margin, true, pair < 2 || pair >= 4, BOARD_COARSE_STEP * (coarse + 1), TAB_MAX_MARGIN);
    }

    public static boolean boardFromRight(int slot) {
        return slot == 2 || slot == 4 || slot == 5;
    }

    public static int boardMaxWidth(int align) {
        return BOARD_MAX_WIDTH - clamp(align, BOARD_MAX_MARGIN);
    }

    public static long boardTitleWidth(int code, int contentWidth) {
        return boardTitleWidth(code, contentWidth, 0);
    }

    public static long boardTitleWidth(int code, int contentWidth, int align) {
        long k = (long) code + BOARD_BASE;
        int slot = boardFields(code).slot();
        int a = clamp(align, BOARD_MAX_MARGIN);
        if (boardFromRight(slot)) {
            return BOARD_CELL * (k - 1) + 2 + BOARD_SLACK + clamp(contentWidth, boardMaxWidth(a)) + a;
        }
        return BOARD_CELL * k - BOARD_SLACK - a;
    }

    public static int boardCodeAt(double x, int screenWidth) {
        if (x > -8192) {
            return -1;
        }
        double k = Math.ceil((screenWidth - 3.0 - x) / BOARD_CELL) - BOARD_BASE;
        return k < 0 || k >= BOARD_CODES ? -1 : (int) k;
    }

    public static boolean sameSide(Position tab, Position board) {
        return tab.side() != Side.NONE && tab.side() == board.side();
    }

    public static int tabMarker(int headerLines) {
        return (TAB_MARKER_R << 16) | (clamp(headerLines, TAB_MAX_HEADER) << 8);
    }

    public static int boardMarker(int width) {
        return boardMarker(width, 0);
    }

    public static int boardMarker(int width, int align) {
        int w = clampValue(width);
        return (BOARD_MARKER_R << 16) | ((clamp(align, BOARD_MAX_MARGIN) << 4 | (w >> 8)) << 8) | (w & 255);
    }

    public static int markerAlign(int rgb) {
        return red(rgb) == BOARD_MARKER_R ? (rgb >> 12) & 15 : 0;
    }

    public static int red(int rgb) {
        return (rgb >> 16) & 255;
    }

    public static int markerValue(int rgb) {
        if (red(rgb) == TAB_MARKER_R) {
            return (rgb >> 8) & 15;
        }
        return (((rgb >> 8) & 15) << 8) | (rgb & 255);
    }
}
