package core.amazake.systems.corerpg.combat;

/**
 * Чистая математика боя: без Bukkit, без случайности (шансы возвращаются долями 0..1, бросок делает вызывающий).
 * Все методы устойчивы к NaN и отрицательным входам.
 */
public final class CombatMath {

    private CombatMath() {
    }

    /** Множитель урона от заточки оружия: 1 + бонус%/100, не меньше 1. */
    public static double sharpenMultiplier(final double bonusPercent) {
        return 1.0 + positive(bonusPercent) / 100.0;
    }

    /**
     * Ярость: +{@code percentPerStep}% урона за каждые полные {@code stepPercent}% потерянного здоровья, не больше
     * {@code maxPercent}. Возвращает бонус в процентах.
     */
    public static double rageBonusPercent(final double health, final double maxHealth, final double percentPerStep,
                                          final double stepPercent, final double maxPercent) {
        if (!(maxHealth > 0.0) || !(stepPercent > 0.0) || !(percentPerStep > 0.0)) {
            return 0.0;
        }
        final double hp = clamp(health, 0.0, maxHealth);
        final double lostPercent = (1.0 - hp / maxHealth) * 100.0;
        // 1e-9: 25% потерянного при шаге 5 — ровно 5 шагов, а не 4.999…
        final double steps = Math.floor(lostPercent / stepPercent + 1e-9);
        return Math.min(positive(maxPercent), steps * percentPerStep);
    }

    /** Выстрел в голову: точка попадания выше уровня глаз цели минус {@code offset}. */
    public static boolean isHeadshot(final double hitY, final double eyeY, final double offset) {
        return hitY > eyeY - offset;
    }

    /**
     * Угол в градусах между взглядом цели и направлением от цели на атакующего, в горизонтальной плоскости (высота
     * не учитывается, чтобы удар сверху вниз не считался «сзади»). 0 — атакующий прямо перед глазами, 180 — ровно
     * за спиной. Нулевой вектор — 0.
     */
    public static double horizontalAngle(final double lookX, final double lookZ, final double toAttackerX, final double toAttackerZ) {
        final double la = Math.hypot(lookX, lookZ);
        final double ta = Math.hypot(toAttackerX, toAttackerZ);
        if (!(la > 1e-9) || !(ta > 1e-9)) {
            return 0.0;
        }
        final double cos = clamp((lookX * toAttackerX + lookZ * toAttackerZ) / (la * ta), -1.0, 1.0);
        return Math.toDegrees(Math.acos(cos));
    }

    /** Удар в спину: угол больше порога. */
    public static boolean isBackstab(final double lookX, final double lookZ, final double toAttackerX, final double toAttackerZ,
                                     final double thresholdDegrees) {
        return horizontalAngle(lookX, lookZ, toAttackerX, toAttackerZ) > thresholdDegrees;
    }

    /** Двойное оружие: прибавка урона = атака предмета во второй руке × процент/100. */
    public static double dualWieldBonus(final double offhandAttack, final double percent) {
        return positive(offhandAttack) * positive(percent) / 100.0;
    }

    /**
     * Доля заряда удара 0..1: ванильный урон удара ÷ полная атака основной руки. Неполный замах (спам кликов) даёт
     * ~0.2; ванильный крит прыжком или зачарование могут дать больше 1 — зажимается в 1. Нет данных — 1.
     */
    public static double attackCharge(final double hitDamage, final double mainHandAttack) {
        if (!(mainHandAttack > 0.0) || !Double.isFinite(mainHandAttack)) {
            return 1.0;
        }
        return clamp(positive(hitDamage) / mainHandAttack, 0.0, 1.0);
    }

    /**
     * Стихийный бонус: +урон% атакующего против стихии, уменьшенный на сопротивление цели этой стихии (доля 0..1).
     * Возвращает долю прибавки: 10% и сопротивление 0.04 → 0.06.
     */
    public static double elementalBonus(final double damageVsPercent, final double targetResistance) {
        return Math.max(0.0, positive(damageVsPercent) / 100.0 - positive(targetResistance));
    }

    /** Шанс крита долей 0..1: аспект crit_chance в процентах, ограниченный потолком в процентах. */
    public static double critChance(final double critChancePercent, final double capPercent) {
        return clamp(positive(critChancePercent), 0.0, clamp(capPercent, 0.0, 100.0)) / 100.0;
    }

    /** Множитель крита: 1 + max(crit_damage, минимум)/100 (минимум по умолчанию 0 — ровно контракт). */
    public static double critMultiplier(final double critDamagePercent, final double minPercent) {
        return 1.0 + Math.max(positive(critDamagePercent), positive(minPercent)) / 100.0;
    }

    /** Шанс уворота долей: clamp((уворот − попадание)/100, 0, кап). */
    public static double evasionChance(final double evasion, final double accuracy, final double cap) {
        final double diff = finite(evasion) - finite(accuracy);
        return clamp(diff / 100.0, 0.0, clamp(cap, 0.0, 1.0));
    }

    /** Делитель входящего урона от заточки брони: 1 + armor_sharpen/100, не меньше 1. */
    public static double armorDivisor(final double armorSharpenPercent) {
        return Math.max(1.0, 1.0 + positive(armorSharpenPercent) / 100.0);
    }

    /** Множитель входящего урона от сопротивления: 1 − min(кап, сумма), сумма не меньше 0. */
    public static double resistanceMultiplier(final double totalResistance, final double cap) {
        return 1.0 - Math.min(clamp(cap, 0.0, 1.0), positive(totalResistance));
    }

    /** Вампиризм: HP = урон × min(вампиризм, кап)/100. */
    public static double lifestealHeal(final double damage, final double lifestealPercent, final double capPercent) {
        return positive(damage) * Math.min(positive(lifestealPercent), positive(capPercent)) / 100.0;
    }

    /**
     * Регенерация за один запуск задачи: аспект regen в HP/с × период/20. Возвращает новое здоровье, не выше
     * максимума и не ниже текущего.
     */
    public static double regenHealth(final double health, final double maxHealth, final double regenPerSecond, final long periodTicks) {
        if (!(regenPerSecond > 0.0) || periodTicks <= 0 || !(health < maxHealth)) {
            return health;
        }
        return Math.min(maxHealth, health + regenPerSecond * periodTicks / 20.0);
    }

    /** Лечение на {@code amount}, не выше максимума и не ниже текущего. */
    public static double healed(final double health, final double maxHealth, final double amount) {
        if (!(amount > 0.0) || !(health < maxHealth)) {
            return health;
        }
        return Math.min(maxHealth, health + amount);
    }

    static double clamp(final double value, final double min, final double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double positive(final double value) {
        return value > 0.0 && Double.isFinite(value) ? value : 0.0;
    }

    private static double finite(final double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
