package core.amazake.systems.corerpg.profile;

import java.util.UUID;

/**
 * Неизменяемый снимок персонажа. Меняется только через {@link ProfileStore#update}, который подменяет снимок целиком.
 *
 * @param classId      ключ базового класса (swordsman, archer, mage, thief) или null
 * @param professionId ключ профессии или null
 * @param level        1..max-level
 * @param exp          опыт внутри текущего уровня
 * @param freePoints   нераспределённые очки параметров
 * @param primary      вложено в главный параметр (Сила / Точность / Интеллект)
 */
public record PlayerProfile(UUID uuid, String classId, String professionId, int level, long exp, int freePoints,
                            int primary, int agility, int stamina, int luck) {

    public static PlayerProfile fresh(final UUID uuid) {
        return new PlayerProfile(uuid, null, null, 1, 0L, 0, 0, 0, 0, 0);
    }

    public boolean hasClass() {
        return this.classId != null;
    }

    public boolean hasProfession() {
        return this.professionId != null;
    }

    public int spentPoints() {
        return this.primary + this.agility + this.stamina + this.luck;
    }

    public PlayerProfile withClass(final String id) {
        return new PlayerProfile(this.uuid, id, this.professionId, this.level, this.exp, this.freePoints, this.primary, this.agility, this.stamina, this.luck);
    }

    public PlayerProfile withProfession(final String id) {
        return new PlayerProfile(this.uuid, this.classId, id, this.level, this.exp, this.freePoints, this.primary, this.agility, this.stamina, this.luck);
    }

    public PlayerProfile withLevel(final int newLevel, final long newExp) {
        return new PlayerProfile(this.uuid, this.classId, this.professionId, newLevel, newExp, this.freePoints, this.primary, this.agility, this.stamina, this.luck);
    }

    public PlayerProfile withExp(final long newExp) {
        return new PlayerProfile(this.uuid, this.classId, this.professionId, this.level, newExp, this.freePoints, this.primary, this.agility, this.stamina, this.luck);
    }

    public PlayerProfile withFreePoints(final int points) {
        return new PlayerProfile(this.uuid, this.classId, this.professionId, this.level, this.exp, points, this.primary, this.agility, this.stamina, this.luck);
    }

    public PlayerProfile withStats(final int newPrimary, final int newAgility, final int newStamina, final int newLuck) {
        return new PlayerProfile(this.uuid, this.classId, this.professionId, this.level, this.exp, this.freePoints, newPrimary, newAgility, newStamina, newLuck);
    }
}
