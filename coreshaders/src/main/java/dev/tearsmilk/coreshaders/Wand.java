package dev.tearsmilk.coreshaders;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class Wand {
    public record State(Shape shape, String image, int color) {
    }

    private final Keys keys;

    public Wand(Keys keys) {
        this.keys = keys;
    }

    public ItemStack create(Settings s, State st) {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        write(s, item, st);
        return item;
    }

    public boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(keys.wand, PersistentDataType.BYTE);
    }

    public State read(Settings s, ItemStack item) {
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        Shape shape = Shape.parse(pdc.get(keys.shape, PersistentDataType.STRING));
        String image = pdc.get(keys.image, PersistentDataType.STRING);
        Integer color = pdc.get(keys.color, PersistentDataType.INTEGER);
        if (shape == null) {
            shape = s.defaultShape;
        }
        if (image == null || !s.images.contains(image)) {
            image = s.defaultImage;
        }
        return new State(shape, image, color == null ? s.defaultColor : color);
    }

    public void write(Settings s, ItemStack item, State st) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.wand, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keys.shape, PersistentDataType.STRING, st.shape().id());
        pdc.set(keys.image, PersistentDataType.STRING, st.image());
        pdc.set(keys.color, PersistentDataType.INTEGER, st.color());
        TagResolver r = TagResolver.resolver(
            Placeholder.unparsed("shape", st.shape().id()),
            Placeholder.unparsed("image", s.imageName(st.image())),
            Placeholder.unparsed("color", Colors.hex(st.color())),
            Settings.colorTag(st.color()));
        meta.displayName(s.plain("wand-name", r));
        meta.lore(s.lines("wand-lore", r));
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }
}
