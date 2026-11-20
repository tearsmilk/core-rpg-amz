package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Modifying;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;
import java.util.Set;

public final class FxTag {

    public static final Set<String> NAMES = Set.of("fx", "textshader");

    private FxTag() {
    }

    public static TagResolver resolver() {
        return TagResolver.resolver(NAMES, FxTag::create);
    }

    static Tag create(ArgumentQueue args, Context ctx) throws ParsingException {
        String effectName = args.popOr("<fx:effect[:color]> - укажи эффект").value();
        Effect effect = Effect.byKey(effectName);
        if (effect == null) {
            throw ctx.newException("Нет эффекта " + effectName, args);
        }
        Palette palette = Palette.WHITE;
        if (args.hasNext()) {
            String colorName = args.pop().value();
            palette = Palette.byKey(colorName);
            if (palette == null) {
                throw ctx.newException("Нет цвета " + colorName, args);
            }
        }
        FxSpec spec = new FxSpec(effect, palette);
        if (!effect.indexed()) {
            return Tag.styling(TextColor.color(spec.code()));
        }
        return new Numbered(spec);
    }

    static final class Numbered implements Modifying {
        private final FxSpec spec;
        private int index;

        Numbered(FxSpec spec) {
            this.spec = spec;
        }

        @Override
        public Component apply(Component current, int depth) {
            if (!(current instanceof TextComponent text) || text.content().isEmpty()) {
                return current.children(List.of()).colorIfAbsent(TextColor.color(spec.code()));
            }
            TextComponent.Builder b = Component.text().style(text.style());
            text.content().codePoints().forEach(cp -> {
                String ch = new String(Character.toChars(cp));
                if (cp == '\n') {
                    index = 0;
                    b.append(Component.text(ch));
                    return;
                }
                b.append(Component.text(ch, TextColor.color(spec.code(index))));
                index++;
            });
            return b.build();
        }
    }
}
