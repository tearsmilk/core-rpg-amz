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
    public static final int BOARD_CODES = 6 * 16 * 17 * 16;
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

    public static int boardCode(Position position, int lines, int tabMargin, int margin) {
        int slot = position.boardSlot();
        if (slot < 0) {
            return -1;
        }
        int n = clamp(lines, BOARD_MAX_LINES);
        int t = tabMargin < 0 ? 0 : 1 + clamp(tabMargin, TAB_MAX_MARGIN);
        return ((slot * 16 + n) * 17 + t) * 16 + clamp(margin, BOARD_MAX_MARGIN);
    }

    public static int[] boardFields(int code) {
        int margin = code % 16;
        int r = code / 16;
        int tab = r % 17;
        r /= 17;
        return new int[]{r / 16, r % 16, tab, margin};
    }

    public static boolean boardFromRight(int slot) {
        return slot == 2 || slot == 4 || slot == 5;
    }

    public static long boardTitleWidth(int code, int contentWidth) {
        long k = (long) code + BOARD_BASE;
        int slot = boardFields(code)[0];
        if (boardFromRight(slot)) {
            return BOARD_CELL * (k - 1) + 2 + BOARD_SLACK + clamp(contentWidth, BOARD_MAX_WIDTH);
        }
        return BOARD_CELL * k - BOARD_SLACK;
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
        int w = clampValue(width);
        return (BOARD_MARKER_R << 16) | ((w >> 8) << 8) | (w & 255);
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
