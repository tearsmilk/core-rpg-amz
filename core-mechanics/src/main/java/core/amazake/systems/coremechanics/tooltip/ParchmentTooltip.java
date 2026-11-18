package core.amazake.systems.coremechanics.tooltip;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Подсказка-свиток (тестовый вариант): стиль azerus:parchment (рамка и фон — пак nexo-items, tools/tooltips.py),
 * название и описание перекрашены «чернилами» ({@link InkPalette}), пустые строки-разделы — коричневым разделителем
 * с ромбом (глиф U+E421 шрифта azerus:tooltip), внизу — приписка-курсив. Оригинальные название и описание хранятся в
 * PDC предмета: повторное применение строит заново из оригинала, reset возвращает как было.
 * Вызывать в потоке владельца предмета (игрока).
 */
public final class ParchmentTooltip {

    public static final Key STYLE = Key.key("azerus", "parchment");
    static final Key FONT = Key.key("azerus", "tooltip");
    static final String DIVIDER = "";
    private static final NamespacedKey ORIG_LORE = new NamespacedKey("coremechanics", "tooltip_orig_lore");
    private static final NamespacedKey ORIG_NAME = new NamespacedKey("coremechanics", "tooltip_orig_name");
    private static final NamespacedKey NEXO_ID = new NamespacedKey("nexo", "id");
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private ParchmentTooltip() {
    }

    /** Применить к предмету (изменяет stack). flavors — приписки по id Nexo («*» — по умолчанию). */
    public static void apply(final ItemStack stack, final Map<String, String> flavors) {
        final PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        List<Component> lore;
        Component name;
        if (pdc.has(ORIG_LORE, PersistentDataType.STRING)) {
            lore = decodeList(pdc.get(ORIG_LORE, PersistentDataType.STRING));
            name = pdc.has(ORIG_NAME, PersistentDataType.STRING) ? GSON.deserialize(pdc.get(ORIG_NAME, PersistentDataType.STRING)) : null;
        } else {
            final ItemLore il = stack.getData(DataComponentTypes.LORE);
            lore = il == null ? List.of() : il.lines();
            name = stack.getData(DataComponentTypes.CUSTOM_NAME);
            if (name == null) {
                name = stack.getData(DataComponentTypes.ITEM_NAME);
            }
            final List<Component> origLore = lore;
            final Component origName = name;
            stack.editMeta(m -> {
                m.getPersistentDataContainer().set(ORIG_LORE, PersistentDataType.STRING, encodeList(origLore));
                if (origName != null) {
                    m.getPersistentDataContainer().set(ORIG_NAME, PersistentDataType.STRING, GSON.serialize(origName));
                }
            });
        }
        final String id = stack.getItemMeta().getPersistentDataContainer().get(NEXO_ID, PersistentDataType.STRING);
        final String flavor = flavors.getOrDefault(id == null ? "" : id, flavors.get("*"));
        stack.setData(DataComponentTypes.TOOLTIP_STYLE, STYLE);
        if (name != null) {
            stack.setData(DataComponentTypes.CUSTOM_NAME, inked(name).decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        }
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(build(lore, flavor)));
    }

    /** Вернуть как было (true — было что вернуть). */
    public static boolean reset(final ItemStack stack) {
        final PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(ORIG_LORE, PersistentDataType.STRING)) {
            return false;
        }
        final List<Component> lore = decodeList(pdc.get(ORIG_LORE, PersistentDataType.STRING));
        final String nm = pdc.get(ORIG_NAME, PersistentDataType.STRING);
        stack.unsetData(DataComponentTypes.TOOLTIP_STYLE);
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        if (nm != null && stack.getData(DataComponentTypes.ITEM_NAME) != null
            && GSON.serialize(stack.getData(DataComponentTypes.ITEM_NAME)).equals(nm)) {
            stack.unsetData(DataComponentTypes.CUSTOM_NAME);
        } else if (nm != null) {
            stack.setData(DataComponentTypes.CUSTOM_NAME, GSON.deserialize(nm));
        }
        stack.editMeta(m -> {
            m.getPersistentDataContainer().remove(ORIG_LORE);
            m.getPersistentDataContainer().remove(ORIG_NAME);
        });
        return true;
    }

    /** Новое описание: строки перекрашены, пустые строки → разделитель, в конце — разделитель и приписка. */
    static List<Component> build(final List<Component> lore, final String flavor) {
        final List<Component> out = new ArrayList<>();
        for (final Component line : lore) {
            if (plain(line).isBlank()) {
                if (!out.isEmpty() && !isDivider(out.get(out.size() - 1))) {
                    out.add(divider());
                }
                continue;
            }
            out.add(inked(line).decoration(TextDecoration.ITALIC, false));
        }
        if (flavor != null && !flavor.isBlank()) {
            if (!out.isEmpty() && !isDivider(out.get(out.size() - 1))) {
                out.add(divider());
            }
            for (final String part : flavor.split("\n")) {
                out.add(Component.text(part, InkPalette.NOTE).decoration(TextDecoration.ITALIC, true)
                    .shadowColor(ShadowColor.none()));
            }
        }
        return out;
    }

    static Component divider() {
        return Component.text(DIVIDER).font(FONT).decoration(TextDecoration.ITALIC, false).shadowColor(ShadowColor.none());
    }

    static boolean isDivider(final Component c) {
        return c instanceof TextComponent t && DIVIDER.equals(t.content());
    }

    /** Перекрасить всё дерево: у каждого узла свой цвет → чернила; без тени (тень на пергаменте грязнит). */
    static Component inked(final Component c) {
        Component r = c.color(InkPalette.ink(c.color())).shadowColor(ShadowColor.none());
        final List<Component> kids = new ArrayList<>();
        for (final Component k : c.children()) {
            kids.add(inkedChild(k, c.color()));
        }
        return r.children(kids);
    }

    private static Component inkedChild(final Component c, final net.kyori.adventure.text.format.TextColor parent) {
        final var own = c.color() != null ? c.color() : parent;
        Component r = c.color(InkPalette.ink(own)).shadowColor(ShadowColor.none());
        final List<Component> kids = new ArrayList<>();
        for (final Component k : c.children()) {
            kids.add(inkedChild(k, own));
        }
        return r.children(kids);
    }

    static String plain(final Component c) {
        final StringBuilder sb = new StringBuilder();
        if (c instanceof TextComponent t) {
            sb.append(t.content());
        }
        for (final Component k : c.children()) {
            sb.append(plain(k));
        }
        return sb.toString();
    }

    private static String encodeList(final List<Component> list) {
        final com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (final Component c : list) {
            arr.add(GSON.serializeToTree(c));
        }
        return arr.toString();
    }

    private static List<Component> decodeList(final String s) {
        final List<Component> out = new ArrayList<>();
        for (final var e : com.google.gson.JsonParser.parseString(s).getAsJsonArray()) {
            out.add(GSON.deserializeFromTree(e));
        }
        return out;
    }
}
