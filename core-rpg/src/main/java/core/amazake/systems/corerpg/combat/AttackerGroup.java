package core.amazake.systems.corerpg.combat;

import java.util.Locale;
import org.bukkit.Tag;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Illager;
import org.bukkit.entity.Monster;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Player;
import org.bukkit.entity.Witch;
import org.bukkit.entity.Wolf;

/**
 * Группы атакующих для целей сопротивления: в combat.yml пишутся с решёткой ({@code #undead}). Проверка — только в
 * потоке региона атакующего/цели (обработчик урона).
 */
public enum AttackerGroup {
    /** Нежить: тег ванили «чувствителен к Небесной каре». */
    UNDEAD,
    /** Членистоногие: тег «чувствителен к Бичу членистоногих». */
    ARTHROPOD,
    /** Человекоподобные: игроки, жители и торговцы, иллагеры, ведьмы, пиглины. */
    HUMANOID,
    /** Животные (и волки). */
    ANIMAL,
    /** Любой монстр. */
    MONSTER,
    /** Только игроки. */
    PLAYER;

    /** «#undead» → UNDEAD; null, если не группа или неизвестна. */
    public static AttackerGroup parse(final String raw) {
        if (raw == null || !raw.startsWith("#")) {
            return null;
        }
        final String name = raw.substring(1).trim().toUpperCase(Locale.ROOT);
        for (final AttackerGroup group : values()) {
            if (group.name().equals(name)) {
                return group;
            }
        }
        return null;
    }

    public boolean matches(final Entity entity) {
        if (entity == null) {
            return false;
        }
        return switch (this) {
            case UNDEAD -> Tag.ENTITY_TYPES_SENSITIVE_TO_SMITE.isTagged(entity.getType());
            case ARTHROPOD -> Tag.ENTITY_TYPES_SENSITIVE_TO_BANE_OF_ARTHROPODS.isTagged(entity.getType());
            case HUMANOID -> entity instanceof Player || entity instanceof AbstractVillager || entity instanceof Illager
                || entity instanceof Witch || entity instanceof PiglinAbstract;
            case ANIMAL -> entity instanceof Animals || entity instanceof Wolf;
            case MONSTER -> entity instanceof Monster;
            case PLAYER -> entity instanceof Player;
        };
    }
}
