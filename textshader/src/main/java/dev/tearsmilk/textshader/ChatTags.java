package dev.tearsmilk.textshader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatTags {

    public record Segment(String text, FxSpec spec, boolean tagged) {
    }

    private static final Pattern TAG = Pattern.compile("<(/)?(?:(?:fx|textshader):)?([a-zA-Z_-]*)(?::([a-zA-Z_]+))?>");

    private ChatTags() {
    }

    public static boolean hasTags(String text) {
        if (text == null || text.indexOf('<') < 0) {
            return false;
        }
        Matcher m = TAG.matcher(text);
        while (m.find()) {
            if (m.group(1) == null && specOf(m) != null) {
                return true;
            }
        }
        return false;
    }

    public static List<Segment> parse(String text, FxSpec base) {
        return new Parser(base, e -> true).feed(text);
    }

    public static final class Parser {
        private final FxSpec base;
        private final Predicate<Effect> allowed;
        private final List<FxSpec> stack = new ArrayList<>();

        public Parser(FxSpec base, Predicate<Effect> allowed) {
            this.base = base;
            this.allowed = allowed;
        }

        public List<Segment> feed(String text) {
            List<Segment> out = new ArrayList<>();
            if (text == null) {
                return out;
            }
            Matcher m = TAG.matcher(text);
            StringBuilder buf = new StringBuilder();
            int pos = 0;
            while (m.find()) {
                boolean closing = m.group(1) != null;
                FxSpec spec = closing ? null : specOf(m);
                if (!closing && spec == null) {
                    continue;
                }
                if (closing && stack.isEmpty()) {
                    continue;
                }
                if (closing && !m.group(2).isEmpty() && !closes(stack.get(stack.size() - 1), m.group(2))) {
                    continue;
                }
                buf.append(text, pos, m.start());
                flush(out, buf);
                if (closing) {
                    stack.remove(stack.size() - 1);
                } else {
                    stack.add(allowed.test(spec.effect()) ? spec : null);
                }
                pos = m.end();
            }
            buf.append(text, pos, text.length());
            flush(out, buf);
            return out;
        }

        private void flush(List<Segment> out, StringBuilder buf) {
            if (buf.length() == 0) {
                return;
            }
            FxSpec top = null;
            for (int i = stack.size() - 1; i >= 0 && top == null; i--) {
                top = stack.get(i);
            }
            out.add(new Segment(buf.toString(), top != null ? top : base, top != null));
            buf.setLength(0);
        }
    }

    private static boolean closes(FxSpec open, String name) {
        Effect e = Effect.byKey(name);
        return e != null && (open == null || e == open.effect());
    }

    private static FxSpec specOf(Matcher m) {
        String name = m.group(2);
        if (name == null || name.isEmpty()) {
            return null;
        }
        Effect e = Effect.byKey(name);
        if (e == null) {
            return null;
        }
        if (m.group(3) == null) {
            return FxSpec.of(e);
        }
        Palette p = Palette.byKey(m.group(3));
        return p == null ? null : new FxSpec(e, p);
    }
}
