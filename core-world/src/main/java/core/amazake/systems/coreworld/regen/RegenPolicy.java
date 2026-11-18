package core.amazake.systems.coreworld.regen;

/**
 * Чистые правила модуля {@code regeneration}: какое восстановление HP считается пассивным. Причины сравниваются по
 * именам констант, чтобы класс не зависел ни от Bukkit, ни от MMOCore и проверялся юнит-тестом.
 */
public final class RegenPolicy {

    private RegenPolicy() {
    }

    /**
     * Ванильная естественная регенерация ({@code EntityRegainHealthEvent.RegainReason}): {@code SATIATED} — от сытости,
     * {@code REGEN} — мирный режим. Магия, зелья, эффект «Регенерация» и прочее лечение сюда не входят.
     */
    public static boolean natural(final String reason) {
        return "SATIATED".equals(reason) || "REGEN".equals(reason);
    }

    /**
     * Пассивная регенерация здоровья MMOCore ({@code PlayerResourceUpdateEvent}): ресурс {@code HEALTH}, причина
     * {@code REGENERATION} и прибавка больше нуля. Лечение навыками и предметами MMOCore идёт с другими причинами.
     */
    public static boolean passiveHealth(final String resource, final String reason, final double difference) {
        return "HEALTH".equals(resource) && "REGENERATION".equals(reason) && difference > 0.0;
    }
}
