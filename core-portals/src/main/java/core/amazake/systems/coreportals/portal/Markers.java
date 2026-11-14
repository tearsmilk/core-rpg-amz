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
 * 66 языки пламени, 67 трещина на земле, 68 горизонтальная кромка; 69 — запас.</p>
 *
 * <p>Изумрудный портал: <b>R = 255, G = 70..79, B — параметр 0..250</b> (шейдер читает его как {@code cpParam}
 * 0..1 = B / 250): 70 кольцо-воронка (B — фаза: 0 сформировано, 250 — только точка), 71 туманность (B = 0),
 * 72 точка/вспышка (B = 0), 73 осколок (B — яркость грани); 74..79 — запас.</p>
 *
 * <p>Лазуритовый разлом: <b>R = 255, G = 80..89, B — параметр 0..250</b>: 80 разлом (B — {@code tear}: вид от
 * трещины до открытого разлома), 81 дымка вокруг (B — сила), 82 свечение на экране (B — яркость), 83 трещины на
 * экране (B — доля раскрытия). 82 и 83 шейдер рисует поверх всего экрана, а не там, где стоит дисплей.
 * 84..89 — запас (любой B). 90..95 — запас (B = 0).</p>
 *
 * <p>Синий канал не выше {@link #MAX_PARAM} (250) — запас от маркера курсора rezusmenu (254, G, 254): с нестрогой
 * проверкой (одна из его версий — допуск ±1,5) наш R = 255 с B 253..255 попадал бы под него.</p>
 */
public final class Markers {

    public static final int SURFACE = 64;
    public static final int EDGE_VERTICAL = 65;
    public static final int FLAMES = 66;
    public static final int GROUND = 67;
    public static final int EDGE_HORIZONTAL = 68;
    public static final int VORTEX = 70;
    public static final int NEBULA = 71;
    public static final int SPARK = 72;
    public static final int SHARD = 73;
    /** Яркость граней осколка (синий канал маркера 73, 0..{@link #MAX_PARAM}): светлые, средние, тёмные. */
    public static final int[] SHARD_SHADES = {250, 172, 103};
    /** Лазуритовый разлом: сам разлом (B — tear). */
    public static final int RIFT = 80;
    /** Лазуритовый разлом: дымка вокруг (B — сила). */
    public static final int RIFT_HAZE = 81;
    /** Свечение на экране игрока рядом с разломом (B — яркость). Рисуется во весь экран. */
    public static final int SCREEN_GLOW = 82;
    /** Трещины на экране игрока, пока разлом раскрывается (B — доля раскрытия). Рисуется во весь экран. */
    public static final int SCREEN_CRACKS = 83;
    /** Наибольшее значение синего канала-параметра (шейдер: cpParam = B / 250). */
    public static final int MAX_PARAM = 250;

    private Markers() {
    }

    public static Color color(final int g) {
        return Color.fromRGB(255, g, 0);
    }

    /** Маркер с параметром в синем канале (только для G 70..89), {@code value} 0..1 → B 0..{@link #MAX_PARAM}. */
    public static Color param(final int g, final double value) {
        return Color.fromRGB(255, g, paramByte(value));
    }

    /** Байт параметра 0..{@link #MAX_PARAM} для {@code value} 0..1. */
    public static int paramByte(final double value) {
        final double v = Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
        return (int) Math.round(v * MAX_PARAM);
    }

    /** Маркер с готовым байтом параметра (0..{@link #MAX_PARAM}). */
    public static Color paramRaw(final int g, final int b) {
        return Color.fromRGB(255, g, Math.max(0, Math.min(MAX_PARAM, b)));
    }

    /** Предмет для ItemDisplay: модель {@code coreportals:<model>} и готовые цвета по порядку tintindex. */
    public static ItemStack item(final String model, final Color... colors) {
        final ItemStack stack = new ItemStack(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("coreportals", model));
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addColors(List.of(colors)).build());
        return stack;
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
