package core.amazake.systems.corerpg.progression;

/**
 * Формулы параметров: очки → аспекты. Чистая математика, любой поток. Очки — эффективные (вложенные + бонусные
 * stat_* от печатей и заточки +11).
 */
public final class StatFormulas {

    /** Что дают параметры в сумме (значения аспектов). */
    public record Bonuses(double attackDamage, double accuracy, double maxHealth, double attackSpeed, double evasion,
                          double critChance, double critDamage) {

        public Bonuses plus(final Bonuses o) {
            return new Bonuses(this.attackDamage + o.attackDamage, this.accuracy + o.accuracy, this.maxHealth + o.maxHealth,
                this.attackSpeed + o.attackSpeed, this.evasion + o.evasion, this.critChance + o.critChance,
                this.critDamage + o.critDamage);
        }
    }

    public static final Bonuses NONE = new Bonuses(0, 0, 0, 0, 0, 0, 0);

    private StatFormulas() {
    }

    static int tens(final double points) {
        return points <= 0 ? 0 : (int) Math.floor(points / 10.0);
    }

    static double floorPositive(final double points) {
        return points <= 0 ? 0 : Math.floor(points);
    }

    /** Главный параметр: урон и попадание. */
    public static Bonuses primary(final StatSettings s, final double points) {
        final double p = floorPositive(points);
        final double damage = p * s.primaryDamagePerPoint() + tens(p) * s.primaryDamagePer10();
        final double accuracy = Math.floor(p / s.primaryAccuracyDivisor());
        return new Bonuses(damage, accuracy, 0, 0, 0, 0, 0);
    }

    /** Выносливость: здоровье. */
    public static Bonuses stamina(final StatSettings s, final double points) {
        final double p = floorPositive(points);
        return new Bonuses(0, 0, p * s.staminaHealthPerPoint(), 0, 0, 0, 0);
    }

    /** Ловкость: скорость атаки и уворот. */
    public static Bonuses agility(final StatSettings s, final double points) {
        final double p = floorPositive(points);
        final int t = tens(p);
        return new Bonuses(0, 0, 0, p * s.agilitySpeedPerPoint() + t * s.agilitySpeedPer10(), t * s.agilityEvasionPer10(), 0, 0);
    }

    /** Удача: шанс крита по формуле RQ, попадание и сила крита (без базы). */
    public static Bonuses luck(final StatSettings s, final double points, final int level) {
        final double p = floorPositive(points);
        final int t = tens(p);
        return new Bonuses(0, t * s.luckAccuracyPer10(), 0, 0, 0, critChance(s, critRating(s, p), level),
            t * s.critDamagePer10());
    }

    /** Рейтинг крита от удачи. */
    public static double critRating(final StatSettings s, final double luck) {
        final double p = floorPositive(luck);
        return p * s.luckRatingPerPoint() + tens(p) * s.luckRatingPer10();
    }

    /** Шанс крита, % (0..cap): {@code 100 × scale × (1 − base^(рейтинг × (a − уровень × b)))}. */
    public static double critChance(final StatSettings s, final double rating, final int level) {
        final double exponent = Math.max(0.0, rating * (s.critA() - level * s.critB()));
        final double chance = 100.0 * s.critScale() * (1.0 - Math.pow(s.critBase(), exponent));
        return Math.max(0.0, Math.min(s.critChanceCap(), chance));
    }

    /** Всё вместе, включая базовую силу крита (её получают все). */
    public static Bonuses total(final StatSettings s, final int level, final double primary, final double agility,
                                final double stamina, final double luck) {
        return primary(s, primary).plus(agility(s, agility)).plus(stamina(s, stamina)).plus(luck(s, luck, level))
            .plus(new Bonuses(0, 0, 0, 0, 0, 0, s.critDamageBase()));
    }

    /** Вклад одного параметра (для лора меню). */
    public static Bonuses of(final StatSettings s, final StatType type, final double points, final int level) {
        return switch (type) {
            case PRIMARY -> primary(s, points);
            case AGILITY -> agility(s, points);
            case STAMINA -> stamina(s, points);
            case LUCK -> luck(s, points, level);
        };
    }
}
