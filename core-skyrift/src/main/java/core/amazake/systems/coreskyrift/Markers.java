package core.amazake.systems.coreskyrift;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Маркерные цвета core-skyrift для core-шейдера: <b>R = 255, G = 96..111, B = параметр 0..250</b> (шейдер:
 * {@code srParam = B / 250}), и только на дисплеях с яркостью 15/15 (UV2 = 240, 240). Цвет приходит через tint
 * модели {@code coreskyrift:sky} ({@code custom_model_data.colors}, по tintindex).
 *
 * <p>Занято: 96 — чёрный туман на всё небо (B — плотность), 97 — разлом и ад внутри (B — раскрытость: 0..50
 * трещина растёт, 50..250 края рвутся), 98 — красная вспышка/кровавые потёки по краям экрана (B — сила).
 * 99 (луч падения) и 100 (кровавая печать) — запас под v2, 101..111 — запас.</p>
 *
 * <p>Соседи: core-portals — R = 255, G 64..95 (его {@code cp_kind} отвергает G > 95,5); rezusmenu — R = B = 254 и
 * UV2 = (240, 224). Белый (255, 255, 255) не наш: G = 255.</p>
 */
public final class Markers {

    public static final int FOG = 96;
    public static final int RIFT = 97;
    public static final int FLASH = 98;
    public static final int MIN_G = 96;
    public static final int MAX_G = 111;
    /** Наибольший синий канал-параметр. */
    public static final int MAX_PARAM = 250;
    /** Модель неба: три квада (tintindex 0 туман, 1 разлом, 2 вспышка). */
    public static final Key SKY_MODEL = Key.key("coreskyrift", "sky");

    private Markers() {
    }

    /** Байт параметра 0..{@link #MAX_PARAM} для {@code value} 0..1. */
    public static int paramByte(final double value) {
        final double v = Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
        return (int) Math.round(v * MAX_PARAM);
    }

    /** Маркер с готовым байтом (обрезается до 0..{@link #MAX_PARAM}); G вне 96..111 — ошибка. */
    public static Color paramRaw(final int g, final int b) {
        if (g < MIN_G || g > MAX_G) {
            throw new IllegalArgumentException("G вне 96..111: " + g);
        }
        return Color.fromRGB(255, g, Math.max(0, Math.min(MAX_PARAM, b)));
    }

    /** Предмет дисплея неба: туман, разлом, вспышка (байты 0..250). */
    public static ItemStack skyItem(final int fog, final int rift, final int flash) {
        final ItemStack stack = new ItemStack(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, SKY_MODEL);
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
            .addColors(List.of(paramRaw(FOG, fog), paramRaw(RIFT, rift), paramRaw(FLASH, flash))).build());
        return stack;
    }
}
