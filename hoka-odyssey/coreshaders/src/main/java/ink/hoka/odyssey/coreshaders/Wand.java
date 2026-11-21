package ink.hoka.odyssey.coreshaders;

import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

/** Шейдерный жезл: огненный стержень с настройками в custom_data (форма, картинка, цвет). */
final class Wand {
    record State(Shape shape, String image, int color) {
    }

    static final Tag<Boolean> WAND = Tag.Boolean("coreshaders_wand");
    static final Tag<String> SHAPE = Tag.String("coreshaders_shape");
    static final Tag<String> IMAGE = Tag.String("coreshaders_image");
    static final Tag<Integer> COLOR = Tag.Integer("coreshaders_color");

    private Wand() {
    }

    static ItemStack create(Settings s, State st) {
        return write(s, ItemStack.of(Material.BLAZE_ROD), st);
    }

    static boolean isWand(ItemStack item) {
        return item != null && item.material() == Material.BLAZE_ROD && Boolean.TRUE.equals(item.getTag(WAND));
    }

    static State read(Settings s, ItemStack item) {
        Shape shape = Shape.parse(item.getTag(SHAPE));
        String image = item.getTag(IMAGE);
        Integer color = item.getTag(COLOR);
        if (shape == null) {
            shape = s.defaultShape;
        }
        if (image == null || !s.images.contains(image)) {
            image = s.defaultImage;
        }
        return new State(shape, image, color == null ? s.defaultColor : color);
    }

    static ItemStack write(Settings s, ItemStack item, State st) {
        Object[] vars = {"shape", st.shape().id(), "image", s.imageName(st.image()), "color", Settings.color(st.color())};
        return item.withTag(WAND, true)
            .withTag(SHAPE, st.shape().id())
            .withTag(IMAGE, st.image())
            .withTag(COLOR, st.color())
            .with(DataComponents.CUSTOM_NAME, s.itemLine("wand-name", vars))
            .with(DataComponents.LORE, s.lines("wand-lore", true, vars))
            .with(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }
}
