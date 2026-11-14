package core.amazake.systems.coremobs.util;

import org.bukkit.NamespacedKey;

/** Метки в PersistentDataContainer. Пространство имён постоянное: метки переживают перезапуск системы. */
public final class Keys {

    /** На мобе: id из mobs.yml. */
    public static final NamespacedKey MOB = new NamespacedKey("core-mobs", "mob");
    /** На мобе: регион, который его заспавнил. */
    public static final NamespacedKey REGION = new NamespacedKey("core-mobs", "region");
    /** На яйце призыва: id моба. */
    public static final NamespacedKey EGG = new NamespacedKey("core-mobs", "egg");
    /** На топорике региона. */
    public static final NamespacedKey WAND = new NamespacedKey("core-mobs", "wand");

    /** На линии контура региона: «регион#номер». */
    public static final NamespacedKey DISPLAY = new NamespacedKey("core-mobs", "display");
    /** На линии контура: экземпляр системы, который её поставил. */
    public static final NamespacedKey INSTANCE = new NamespacedKey("core-mobs", "instance");

    private Keys() {
    }
}
