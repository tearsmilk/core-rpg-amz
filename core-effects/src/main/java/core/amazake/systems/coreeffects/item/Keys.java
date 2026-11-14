package core.amazake.systems.coreeffects.item;

import org.bukkit.NamespacedKey;

/** Ключи PDC и модификаторов атрибутов (пространство имён coreeffects — не зависит от объекта плагина). */
public final class Keys {

    /** Эффекты игрока: строка {@code EffectBook.encode()}. */
    public static final NamespacedKey STATE = new NamespacedKey("coreeffects", "state");
    /** Метка бинта на предмете. */
    public static final NamespacedKey BANDAGE = new NamespacedKey("coreeffects", "bandage");
    /** Метка фантома паники (значение — id экземпляра системы). */
    public static final NamespacedKey PHANTOM = new NamespacedKey("coreeffects", "phantom");

    public static final NamespacedKey PANIC_DAMAGE = new NamespacedKey("coreeffects", "panic_damage");
    public static final NamespacedKey PANIC_HEALTH = new NamespacedKey("coreeffects", "panic_health");
    public static final NamespacedKey PANIC_SPEED = new NamespacedKey("coreeffects", "panic_speed");
    public static final NamespacedKey FAINT_SPEED = new NamespacedKey("coreeffects", "faint_speed");
    public static final NamespacedKey FAINT_JUMP = new NamespacedKey("coreeffects", "faint_jump");

    private Keys() {
    }
}
