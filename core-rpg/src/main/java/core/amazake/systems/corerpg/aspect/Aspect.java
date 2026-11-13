package core.amazake.systems.corerpg.aspect;

import java.util.Locale;

/**
 * Аспекты — итоговые числовые характеристики персонажа (раздел «Аспекты» артефакта). Ключи совпадают с ключами
 * бонусов печатей и бонусов классов в конфигах. Целевые аспекты ({@code resistance:fire}, {@code damage_vs:monster})
 * лежат в {@link AspectSnapshot#targeted()} отдельно.
 */
public enum Aspect {
    /** + к атрибуту ATTACK_DAMAGE. */
    ATTACK_DAMAGE("attack_damage", "Урон"),
    /** + к атрибуту ARMOR. */
    ARMOR("armor", "Броня"),
    /** + к атрибуту MAX_HEALTH (2 = одно сердце). */
    MAX_HEALTH("max_health", "Здоровье"),
    /** + к атрибуту MOVEMENT_SPEED (база игрока 0.1). */
    MOVEMENT_SPEED("movement_speed", "Скорость"),
    /** + к атрибуту ATTACK_SPEED (база игрока 4.0). */
    ATTACK_SPEED("attack_speed", "Скорость атаки"),
    /** + к атрибуту ATTACK_KNOCKBACK. */
    KNOCKBACK("knockback", "Отталкивание"),
    /** + к атрибуту KNOCKBACK_RESISTANCE (0..1). */
    KNOCKBACK_RESISTANCE("knockback_resistance", "Устойчивость"),
    /** + к атрибутам ENTITY_INTERACTION_RANGE (блоки). */
    RANGE("range", "Дальность"),
    /** Доля снижения любого входящего урона, 0..1; потолок — combat.yml. */
    RESISTANCE("resistance", "Сопротивление"),
    /** Доля снижения урона от игроков, 0..1. */
    RESISTANCE_PVP("resistance_pvp", "Устойчивость к PvP"),
    /** Шанс крита, % (итоговый: формула Удачи + печати). */
    CRIT_CHANCE("crit_chance", "Шанс крита"),
    /** Сила крита, % сверх обычного удара (база 50%). */
    CRIT_DAMAGE("crit_damage", "Сила крита"),
    /** % нанесённого урона, возвращаемый здоровьем. */
    LIFESTEAL("lifesteal", "Вампиризм"),
    /** HP в секунду. */
    REGEN("regen", "Регенерация"),
    /** HP за убийство. */
    HEAL_ON_KILL("heal_on_kill", "Здоровье при убийстве"),
    /** Очки параметров сверх вложенных (печати, заточка +11). Превращаются в аспекты формулами параметров. */
    STAT_PRIMARY("stat_primary", "Главный параметр"),
    STAT_AGILITY("stat_agility", "Ловкость"),
    STAT_STAMINA("stat_stamina", "Выносливость"),
    STAT_LUCK("stat_luck", "Удача"),
    /** Попадание (против уворота). */
    ACCURACY("accuracy", "Попадание"),
    /** Уворот. */
    EVASION("evasion", "Уворот"),
    /** % к опыту. */
    EXP_BONUS("exp_bonus", "Опыт"),
    /** % к золоту: зарезервировано до появления экономики. */
    GOLD_BONUS("gold_bonus", "Золото"),
    /** Шанс при ударе, %. */
    STATUS_BLEED("status_bleed", "Кровотечение"),
    STATUS_POISON("status_poison", "Яд"),
    STATUS_BURN("status_burn", "Ожог"),
    /** Средний % бонуса заточки надетой брони (делитель входящего урона). Считает модуль заточки. */
    ARMOR_SHARPEN("armor_sharpen", "Заточка брони"),
    /** План: мана и энергия, проки. В расчётах не участвуют. */
    RESOURCE("resource", "Мана / энергия"),
    PROC("proc", "Прок");

    private final String key;
    private final String label;

    Aspect(final String key, final String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return this.key;
    }

    public String label() {
        return this.label;
    }

    /** По ключу конфига ({@code attack_damage}, регистр не важен) или null. */
    public static Aspect byKey(final String key) {
        if (key == null) {
            return null;
        }
        final String k = key.toLowerCase(Locale.ROOT).trim();
        for (final Aspect aspect : values()) {
            if (aspect.key.equals(k)) {
                return aspect;
            }
        }
        return null;
    }

    /** Ключ целевого аспекта: «resistance:fire», «damage_vs:monster». */
    public static boolean isTargetedKey(final String key) {
        return key != null && (key.startsWith("resistance:") || key.startsWith("damage_vs:"));
    }
}
