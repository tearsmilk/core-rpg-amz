package ink.hoka.odyssey.client.spaceflight;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;

/**
 * «Плыть» в вакууме: тело игрока ложится на живот, как в воде, и руки гребут — это ванильная анимация плавания,
 * её включает доля плавания {@code swimAmount} (а не поза SWIMMING: та меняет хитбокс и сбрасывается вне воды).
 * Поле приватное — берём рефлексией по имени из маппингов Fabric (в игре — intermediary, в разработке — Mojang).
 * Значение только для картинки: на физику и хитбокс не влияет, а vanilla каждый тик сама плавно ведёт его к 0,
 * так что после «встать» тело само поднимается.
 */
final class SwimLook {
    private static final Field AMOUNT = field("field_6243", "swimAmount");
    private static final Field AMOUNT_O = field("field_6264", "swimAmountO");

    private SwimLook() {}

    static boolean available() { return AMOUNT != null && AMOUNT_O != null; }

    /** Поставить долю плавания (0..1) на этот кадр. */
    static void apply(LivingEntity e, float amount) {
        if (!available() || e == null) return;
        try {
            float cur = AMOUNT.getFloat(e);
            if (amount <= 0 && cur <= 0) return;
            float v = Math.max(amount, 0);
            AMOUNT.setFloat(e, v);
            AMOUNT_O.setFloat(e, v);
        } catch (IllegalAccessException ignored) {
            // не вышло — просто без позы
        }
    }

    private static Field field(String intermediary, String named) {
        try {
            String name = FabricLoader.getInstance().getMappingResolver()
                    .mapFieldName("intermediary", "net.minecraft.class_1309", intermediary, "F");
            Field f;
            try {
                f = LivingEntity.class.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                f = LivingEntity.class.getDeclaredField(named);
            }
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }
}
