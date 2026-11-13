package core.amazake.systems.corerpg.seals;

import core.amazake.systems.corerpg.util.Gear;

/**
 * Правила меню мастера (как в MagicSeals): что делает набор расходников в слотах, какой камень нужен грейду, бросок
 * цвета. Чистая логика без Bukkit — проверяется тестами.
 */
public final class SealRules {

    /** Действие меню. HEART — Сердце Аркона, добавленное к APPLY / CHANGE / ARKONITE (цвет не ниже синего). */
    public enum Action { APPLY, CHANGE, WAX, ACCOUNT_WAX, ARKONITE, HEART }

    /** Содержимое слотов меню (null — пусто или не расходник). */
    public record Slots(Consumable stone, Consumable crystal, Consumable wax, Consumable heart) {
        public static final Slots EMPTY = new Slots(null, null, null, null);
    }

    /**
     * Итог разбора: действие и признак Сердца Аркона, либо сообщение об ошибке (MiniMessage, без подстановок).
     */
    public record Decision(Action action, boolean heart, String error) {
        static Decision fail(final String error) {
            return new Decision(null, false, error);
        }

        public boolean ok() {
            return this.action != null;
        }
    }

    private SealRules() {
    }

    /** Камень для первого наложения по грейду: C — изумрудный, B — сапфировый, A — морионовый. */
    public static Consumable stoneFor(final Gear.Grade grade) {
        return switch (grade) {
            case A -> Consumable.STONE_A;
            case B -> Consumable.STONE_B;
            case C -> Consumable.STONE_C;
        };
    }

    /** Название нужного камня в винительном падеже для сообщения. */
    public static String stoneAccusative(final Consumable stone) {
        return switch (stone) {
            case STONE_A -> "морионовый камень (A)";
            case STONE_B -> "сапфировый камень (B)";
            default -> "изумрудный камень (C)";
        };
    }

    /**
     * Что сделать по слотам меню.
     *
     * @param hasSeal есть ли печать на предмете
     * @param current текущий цвет (null без печати)
     * @param grade   грейд предмета
     */
    public static Decision decide(final boolean hasSeal, final SealColor current, final Gear.Grade grade, final Slots slots) {
        final boolean heart = slots.heart() == Consumable.HEART;
        if (!hasSeal) {
            if (slots.stone() == null) {
                return Decision.fail("<red>Положите камень C/B/A в слот камня.</red>");
            }
            final Consumable required = stoneFor(grade);
            if (slots.stone() != required) {
                return Decision.fail("<red>Для этого предмета нужен " + stoneAccusative(required) + ".</red>");
            }
            if (slots.crystal() != null || (slots.wax() != null && slots.wax() != Consumable.ARKONITE_WAX)) {
                return Decision.fail("<red>Для первого наложения нужен только камень (и опционально Сердце Аркона).</red>");
            }
            return new Decision(Action.APPLY, heart, null);
        }
        if ((slots.wax() == Consumable.WAX || slots.wax() == Consumable.ACCOUNT_WAX)
            && slots.crystal() == null && slots.stone() == null) {
            if (current == SealColor.ORANGE) {
                return Decision.fail("<yellow>Печать уже оранжевая.</yellow>");
            }
            return new Decision(slots.wax() == Consumable.ACCOUNT_WAX ? Action.ACCOUNT_WAX : Action.WAX, false, null);
        }
        if (slots.crystal() != null) {
            final Action action = slots.wax() == Consumable.ARKONITE_WAX ? Action.ARKONITE : Action.CHANGE;
            return new Decision(action, heart, null);
        }
        if (heart) {
            return Decision.fail("<red>Сердце Аркона кладётся вместе с камнем или кристаллом крови.</red>");
        }
        return Decision.fail("<red>Положите нужные расходники: камень (новое), кристалл крови (смена) или сургуч (оранжевый).</red>");
    }

    /** Сумма весов (отрицательные считаются нулём). */
    public static int totalWeight(final int[] weights) {
        int total = 0;
        for (final int w : weights) {
            total += Math.max(0, w);
        }
        return total;
    }

    /**
     * Цвет по весам: {@code roll} — случайное число 0..total-1 (см. {@link #totalWeight}). Нулевая сумма — зелёный.
     */
    public static SealColor rollColor(final int[] weights, final int roll) {
        int left = roll;
        final SealColor[] colors = SealColor.values();
        for (int i = 0; i < colors.length && i < weights.length; i++) {
            left -= Math.max(0, weights[i]);
            if (left < 0) {
                return colors[i];
            }
        }
        return SealColor.GREEN;
    }

    /** Сердце Аркона: цвет не ниже синего. */
    public static SealColor withHeart(final SealColor color) {
        return color.index() < SealColor.BLUE.index() ? SealColor.BLUE : color;
    }

    /** Сработал ли бросок арконита: {@code roll} — случайное число [0, 100). */
    public static boolean arkoniteRerolls(final double chancePercent, final double roll) {
        return roll < chancePercent;
    }
}
