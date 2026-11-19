package core.amazake.systems.corevault;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Режим админа ({@code /rpgadmin} из core-mechanics): флаг {@code coremechanics:rpgadmin} (BYTE 1) в PDC игрока.
 * Пока он включён, монеты в инвентаре не превращаются в баланс при клике (админ держит их предметами).
 */
public final class AdminMode {

    public static final NamespacedKey FLAG = new NamespacedKey("coremechanics", "rpgadmin");

    private AdminMode() {
    }

    public static boolean isOn(final Player p) {
        if (p == null) {
            return false;
        }
        final Byte b = p.getPersistentDataContainer().get(FLAG, PersistentDataType.BYTE);
        return b != null && b != 0;
    }
}
