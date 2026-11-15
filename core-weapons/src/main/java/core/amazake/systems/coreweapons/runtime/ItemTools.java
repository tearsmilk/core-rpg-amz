package core.amazake.systems.coreweapons.runtime;

import core.amazake.systems.coreweapons.logic.CmdStrings;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/** Узнать оружие по предмету и ставить/снимать кадр замаха (строка custom_model_data). */
public final class ItemTools {

    /** Id предмета Nexo (так Nexo метит свои предметы). */
    public static final NamespacedKey NEXO_ID = new NamespacedKey("nexo", "id");
    /** Своя метка — для оружия не из Nexo. */
    public static final NamespacedKey OWN_ID = new NamespacedKey("coreweapons", "id");

    private ItemTools() {
    }

    /** Id оружия или null. Читает только PDC (без копии ItemMeta). */
    public static String id(final ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        final var pdc = item.getPersistentDataContainer();
        final String nexo = pdc.get(NEXO_ID, PersistentDataType.STRING);
        return nexo != null ? nexo : pdc.get(OWN_ID, PersistentDataType.STRING);
    }

    /** Копия предмета с кадром {@code frame} (null — без кадра); null, если менять нечего. */
    public static ItemStack withFrame(final ItemStack item, final String frame) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        final CustomModelData cmd = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        final List<String> strings = cmd == null ? List.of() : cmd.strings();
        if (frame == null && !CmdStrings.hasFrame(strings)) {
            return null;
        }
        if (frame != null && !strings.isEmpty() && frame.equals(strings.get(0))) {
            return null;
        }
        final List<String> next = frame == null ? CmdStrings.without(strings) : CmdStrings.with(strings, frame);
        final ItemStack copy = item.clone();
        if (next.isEmpty() && (cmd == null || cmd.floats().isEmpty() && cmd.flags().isEmpty() && cmd.colors().isEmpty())) {
            copy.resetData(DataComponentTypes.CUSTOM_MODEL_DATA); // как было: компонента не было
        } else {
            final CustomModelData.Builder b = CustomModelData.customModelData().addStrings(next);
            if (cmd != null) {
                b.addFloats(cmd.floats()).addFlags(cmd.flags()).addColors(cmd.colors());
            }
            copy.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build());
        }
        return copy;
    }

    /** Снять кадры со всех предметов инвентаря (поток игрока). Возвращает, сколько предметов поправлено. */
    public static int stripAll(final PlayerInventory inv) {
        int n = 0;
        final ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) {
            final ItemStack fixed = withFrame(all[i], null);
            if (fixed != null) {
                inv.setItem(i, fixed);
                n++;
            }
        }
        return n;
    }
}
