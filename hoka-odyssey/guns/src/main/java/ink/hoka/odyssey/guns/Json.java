package ink.hoka.odyssey.guns;

import java.util.Collection;
import java.util.Map;

/** Простая запись JSON (карты, списки, числа, строки, true/false/null) — для guns/attachments.json. Без Minestom. */
final class Json {
    private Json() {
    }

    static void write(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                str(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Collection<?> c) {
            b.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(b, x);
            }
            b.append(']');
        } else if (o instanceof Boolean bo) {
            b.append(bo ? "true" : "false");
        } else if (o instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                b.append("null");
            } else if (n instanceof Integer || n instanceof Long || d == Math.rint(d) && Math.abs(d) < 1e15) {
                b.append((long) d);
            } else {
                b.append(d);
            }
        } else {
            str(b, String.valueOf(o));
        }
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
