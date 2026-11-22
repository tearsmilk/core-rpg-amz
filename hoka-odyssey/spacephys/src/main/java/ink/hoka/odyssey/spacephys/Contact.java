package ink.hoka.odyssey.spacephys;

/**
 * Контакт с поверхностью в вакууме: отскок по нормали (коэффициент восстановления e) и трение только в момент
 * контакта (кулоново, импульсное). Вне контакта никакого торможения нет.
 */
public final class Contact {
    private Contact() {}

    /**
     * Итог удара.
     *
     * @param velocity     скорость после удара, блоки/с
     * @param impactSpeed  скорость сближения по нормали до удара |v_n|, блоки/с (0 — не было удара)
     * @param deltaVNormal изменение нормальной скорости (1 + e)·|v_n|, блоки/с
     */
    public record Result(Vec3d velocity, double impactSpeed, double deltaVNormal) {
        /** Удар опасен: |v_n| больше порога (по умолчанию {@link Units#IMPACT_DAMAGE_THRESHOLD}). */
        public boolean damaging(double threshold) { return impactSpeed > threshold; }

        public boolean damaging() { return damaging(Units.IMPACT_DAMAGE_THRESHOLD); }
    }

    /**
     * Удар о поверхность с внешней нормалью normal (нормируется).
     *
     * @param velocity    скорость до удара, блоки/с
     * @param restitution e, 0…1 (по умолчанию {@link Units#RESTITUTION})
     * @param friction    коэффициент трения μ_k: касательная скорость падает на μ_k·Δv_n, но не меньше нуля
     */
    public static Result collide(Vec3d velocity, Vec3d normal, double restitution, double friction) {
        Vec3d n = normal.normalize();
        double vn = velocity.dot(n);
        if (n.lengthSquared() == 0 || vn >= 0) {
            return new Result(velocity, 0, 0); // удаляемся от поверхности или нормали нет
        }
        double e = Math.max(0, Math.min(1, restitution));
        double dvn = -(1 + e) * vn;
        Vec3d vt = velocity.sub(n.scale(vn));
        double vtl = vt.length();
        double keep = vtl == 0 ? 0 : Math.max(0, 1 - Math.max(0, friction) * dvn / vtl);
        Vec3d out = vt.scale(keep).add(n.scale(-e * vn));
        return new Result(out, -vn, dvn);
    }

    /** Удар с e = {@link Units#RESTITUTION} и без трения. */
    public static Result collide(Vec3d velocity, Vec3d normal) {
        return collide(velocity, normal, Units.RESTITUTION, 0);
    }

    /** Урон от удара: (|v_n| − порог)·perUnit, не меньше 0. */
    public static double impactDamage(double impactSpeed, double threshold, double perUnit) {
        return impactSpeed > threshold ? (impactSpeed - threshold) * perUnit : 0;
    }
}
