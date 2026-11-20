package dev.tearsmilk.textshader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatTags {

    public record Segment(String text, FxSpec spec) {
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
        List<Segment> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = TAG.matcher(text);
        StringBuilder buf = new StringBuilder();
        List<FxSpec> stack = new ArrayList<>();
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
            flush(out, buf, current(stack, base));
            if (closing) {
                stack.remove(stack.size() - 1);
            } else {
                stack.add(spec);
            }
            pos = m.end();
        }
        buf.append(text, pos, text.length());
        flush(out, buf, current(stack, base));
        return out;
    }

    private static boolean closes(FxSpec open, String name) {
        Effect e = Effect.byKey(name);
        return e != null && e == open.effect();
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

    private static FxSpec current(List<FxSpec> stack, FxSpec base) {
        return stack.isEmpty() ? base : stack.get(stack.size() - 1);
    }

    private static void flush(List<Segment> out, StringBuilder buf, FxSpec spec) {
        if (buf.length() == 0) {
            return;
        }
        out.add(new Segment(buf.toString(), spec));
        buf.setLength(0);
    }
}
