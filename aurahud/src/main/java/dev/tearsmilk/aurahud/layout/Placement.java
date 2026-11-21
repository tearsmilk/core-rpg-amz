package dev.tearsmilk.aurahud.layout;

import java.util.List;

public record Placement(Element element, Mode mode, Geometry geometry) {

    public enum Mode {
        VANILLA(0, "vanilla"),
        MOVED(1, "moved"),
        HIDDEN(2, "hidden");

        private final int code;
        private final String key;

        Mode(int code, String key) {
            this.code = code;
            this.key = key;
        }

        public int code() {
            return code;
        }

        public String key() {
            return key;
        }
    }

    public record Point(int x, int y) {

        public Point plus(Point o) {
            return new Point(x + o.x, y + o.y);
        }

        public Point minus(Point o) {
            return new Point(x - o.x, y - o.y);
        }
    }

    public record Geometry(Anchor anchor, Point base, int width, int height, List<Point> positions, int hiddenMask,
                           boolean slice, Point box, Point boxSize, boolean follow, boolean attack, Point attackOffset,
                           int rotate, boolean level, Point levelOffset, Point lineStep) {

        public Geometry {
            positions = List.copyOf(positions);
        }

        public Point topLeft(int screenWidth, int screenHeight) {
            return new Point(anchor.x(screenWidth), anchor.y(screenHeight)).plus(base);
        }
    }

    public Point topLeft(int screenWidth, int screenHeight) {
        return geometry.topLeft(screenWidth, screenHeight);
    }

    public boolean vanilla() {
        return mode == Mode.VANILLA;
    }
}
