package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class ChatFx {

    private final FxSpec base;
    private final boolean tags;
    private final boolean sanitize;
    private final Predicate<Effect> allowed;
    private final ChatTags.Parser parser;
    private int index;
    private boolean changed;

    private ChatFx(FxSpec base, boolean tags, boolean sanitize, Predicate<Effect> allowed) {
        this.base = base;
        this.tags = tags;
        this.sanitize = sanitize;
        this.allowed = allowed;
        this.parser = new ChatTags.Parser(base, allowed);
    }

    public static Component apply(Component message, FxSpec base, boolean tags, boolean sanitize, Predicate<Effect> allowed) {
        ChatFx fx = new ChatFx(base, tags, sanitize, allowed);
        Component out = fx.walk(message, null);
        return fx.changed ? out : message;
    }

    public static Component render(String text, FxSpec spec) {
        return apply(Component.text(text), spec, true, false, e -> true);
    }

    private TextColor clean(TextColor color) {
        if (!sanitize || color == null) {
            return color;
        }
        FxCode.Decoded d = FxCode.decode(color.value());
        if (d == null || d.shadow()) {
            return color;
        }
        if (d.effect().hidden() || !allowed.test(d.effect())) {
            return null;
        }
        return color;
    }

    private Component walk(Component c, TextColor inherited) {
        Component cur = c;
        TextColor own = clean(c.color());
        if (!Objects.equals(own, c.color())) {
            cur = cur.color(own);
            changed = true;
        }
        TextColor effective = own != null ? own : inherited;
        List<Component> kids = new ArrayList<>();
        if (cur instanceof TextComponent t && !t.content().isEmpty()) {
            List<ChatTags.Segment> segs = tags ? parser.feed(t.content())
                    : List.of(new ChatTags.Segment(t.content(), base, false));
            boolean same = segs.size() == 1 && segs.get(0).text().equals(t.content()) && !applies(segs.get(0), effective);
            if (!same) {
                for (ChatTags.Segment s : segs) {
                    kids.add(render(s, effective));
                }
                cur = t.content("");
                changed = true;
            }
        } else if (!(cur instanceof TextComponent) && base != null && !base.effect().indexed() && effective == null) {
            cur = cur.color(TextColor.color(base.code()));
            effective = cur.color();
            changed = true;
        }
        for (Component child : c.children()) {
            kids.add(walk(child, effective));
        }
        return cur.children(kids);
    }

    private static boolean applies(ChatTags.Segment s, TextColor effective) {
        return s.spec() != null && (s.tagged() || effective == null);
    }

    private Component render(ChatTags.Segment s, TextColor effective) {
        if (!applies(s, effective)) {
            countNewlines(s.text());
            return Component.text(s.text());
        }
        FxSpec spec = s.spec();
        if (!spec.effect().indexed()) {
            countNewlines(s.text());
            return Component.text(s.text(), TextColor.color(spec.code()));
        }
        TextComponent.Builder b = Component.text();
        s.text().codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            if (cp == '\n') {
                index = 0;
                b.append(Component.text(ch, TextColor.color(spec.code(0))));
                return;
            }
            b.append(Component.text(ch, TextColor.color(spec.code(index))));
            index++;
        });
        return b.build();
    }

    private void countNewlines(String text) {
        if (text.indexOf('\n') >= 0) {
            index = 0;
        }
    }
}
