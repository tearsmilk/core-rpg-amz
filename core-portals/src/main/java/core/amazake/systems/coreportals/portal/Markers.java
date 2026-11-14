package core.amazake.systems.coreportals.portal;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Маркерные цвета для core-шейдера. Каждый кусок портала — предмет с моделью {@code coreportals:<часть>}, чьи
 * грани окрашены цветом из {@code custom_model_data.colors} (tints в {@code assets/coreportals/items/*.json}).
 * Шейдер узнаёт свои вершины по цвету и рисует их сам; всё остальное в игре рисуется как в ванили.
 *
 * <p>Диапазон core-portals: <b>R = 255, G = 64..95, B = 0</b>. Занято: 64 поверхность, 65 вертикальная кромка,
 * 66 языки пламени, 67 трещина на земле, 68 горизонтальная кромка; 69..95 — запас.</p>
 */
public final class Markers {

    public static final int SURFACE = 64;
    public static final int EDGE_VERTICAL = 65;
    public static final int FLAMES = 66;
    public static final int GROUND = 67;
    public static final int EDGE_HORIZONTAL = 68;

    private Markers() {
    }

    public static Color color(final int g) {
        return Color.fromRGB(255, g, 0);
    }

    /** Предмет для ItemDisplay: модель {@code coreportals:<model>} и маркерные цвета по порядку tintindex. */
    public static ItemStack item(final String model, final int... greens) {
        final ItemStack stack = new ItemStack(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("coreportals", model));
        final List<Color> colors = java.util.Arrays.stream(greens).mapToObj(Markers::color).toList();
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addColors(colors).build());
        return stack;
    }
}
