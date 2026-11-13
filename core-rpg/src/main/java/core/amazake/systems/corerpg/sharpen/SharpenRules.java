package core.amazake.systems.corerpg.sharpen;

import core.amazake.systems.corerpg.util.Gear;
import core.amazake.systems.corerpg.util.Text;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Чистая логика заточки без Bukkit-состояния: расчёт попытки по настройкам, бросок, бонусы и строка лора. Всё
 * проверяется юнит-тестами.
 */
public final class SharpenRules {

    /** Вид попытки: обычная (камнем) или +11 через жертву (режим RQ). */
    public enum Type { NORMAL, LEGENDARY }

    /** Итог попытки. */
    public enum Outcome { SUCCESS, FAIL_KEEP, FAIL_DOWN, DESTROYED }

    /**
     * Готовая к броску попытка.
     *
     * @param current   текущий уровень
     * @param target    уровень при успехе
     * @param success   шанс успеха, %
     * @param destroy   шанс уничтожения при провале (уже с учётом кубика и руны), %
     * @param noDestroy предмет не может быть уничтожен (кубик, руна, +11)
     * @param noDown    при провале уровень не меняется (руна, +11)
     * @param stone     камень (null для +11)
     * @param rune      руна или null
     * @param sacrificeLevel заточка жертвы для +11, иначе −1
     */
    public record Plan(Type type, int current, int target, double success, double destroy, boolean noDestroy,
                       boolean noDown, Consumable stone, Consumable rune, int sacrificeLevel) {

        /** Уровень после провала, если предмет не уничтожен. */
        public int levelAfterFail() {
            return this.noDown ? this.current : Math.max(0, this.current - 1);
        }
    }

    /** Попытка или причина, почему её нельзя сделать (MiniMessage). */
    public record Check(Plan plan, String error) {
        static Check fail(final String error) {
            return new Check(null, error);
        }

        public boolean ok() {
            return this.plan != null;
        }
    }

    private static final List<Pattern> OLD_LORE = List.of(
        Pattern.compile("^(текущая )?заточка\\b.*", Pattern.UNICODE_CHARACTER_CLASS),
        Pattern.compile("^уровень заточки\\b.*", Pattern.UNICODE_CHARACTER_CLASS),
        Pattern.compile("^(атака|защита) \\+[\\d.,]+%.*"),
        Pattern.compile("^[×x][\\d.,]+\\)?$"));

    private SharpenRules() {
    }

    /** Нужна ли жертва для следующего уровня (режим RQ, +10 → +11). */
    public static boolean needsSacrifice(final SharpenSettings s, final Gear.Grade grade, final int current) {
        return s.mode() == SharpenSettings.Mode.ROYAL_QUEST && current >= 10 && current < s.maxLevel(grade);
    }

    /**
     * Проверить и посчитать попытку.
     *
     * @param category       armor / weapon_1h / weapon_2h
     * @param current        текущая заточка
     * @param stone          камень из слота или null
     * @param rune           руна из слота или null
     * @param sacrificeLevel заточка подходящей жертвы или −1, если жертвы нет
     */
    public static Check plan(final SharpenSettings s, final String category, final Gear.Grade grade, final int current,
                             final Consumable stone, final Consumable rune, final int sacrificeLevel) {
        final int max = s.maxLevel(grade);
        if (max <= 0) {
            return Check.fail("<red>Предметы грейда " + grade + " нельзя точить.</red>");
        }
        if (current >= max) {
            return Check.fail("<yellow>Предмет уже имеет максимальный уровень заточки (+" + max + ").</yellow>");
        }
        final int target = current + 1;
        if (needsSacrifice(s, grade, current)) {
            if (sacrificeLevel < 0) {
                return Check.fail("<red>Для +" + target + " положите в слот жертвы второй предмет того же материала.</red>");
            }
            final double chance = clamp(s.legendaryChance(sacrificeLevel));
            return new Check(new Plan(Type.LEGENDARY, current, target, chance, 0.0, true, true, null, null, sacrificeLevel), null);
        }
        if (stone == null || !stone.isStone()) {
            return Check.fail("<red>Положите камень заточки в слот.</red>");
        }
        if (stone == Consumable.CRUMB && grade != Gear.Grade.C) {
            return Check.fail("<red>Крошка философского камня подходит только для грейда C.</red>");
        }
        final double success;
        final double destroy;
        if (s.mode() == SharpenSettings.Mode.ROYAL_QUEST) {
            success = s.rqSuccess().get(category, grade, target, stone.column());
            destroy = s.rqDestroy().get(category, grade, target, stone.column());
        } else {
            final List<Double> succ = s.simpleSuccess().get(category);
            final List<Double> dest = s.simpleDestroy().get(category);
            if (succ == null || dest == null) {
                return Check.fail("<red>Для этого предмета нет таблицы шансов.</red>");
            }
            final int i = Math.min(target, succ.size()) - 1;
            success = succ.get(i) * s.multiplier(stone);
            destroy = dest.get(Math.min(target, dest.size()) - 1);
        }
        final boolean withRune = rune != null && rune.isRune();
        final boolean noDestroy = withRune || stone == Consumable.CUBE;
        return new Check(new Plan(Type.NORMAL, current, target, clamp(success), noDestroy ? 0.0 : clamp(destroy),
            noDestroy, withRune, stone, withRune ? rune : null, -1), null);
    }

    /**
     * Бросок. {@code roll1} и {@code roll2} — случайные числа в [0, 100): первый решает успех, второй — уничтожение.
     */
    public static Outcome roll(final Plan plan, final double roll1, final double roll2) {
        if (roll1 < plan.success()) {
            return Outcome.SUCCESS;
        }
        if (plan.noDown()) {
            return Outcome.FAIL_KEEP;
        }
        if (!plan.noDestroy() && roll2 < plan.destroy()) {
            return Outcome.DESTROYED;
        }
        return plan.levelAfterFail() == plan.current() ? Outcome.FAIL_KEEP : Outcome.FAIL_DOWN;
    }

    /** Средний бонус по заточенным частям брони (как CombatListener плагина): 4 × +10 дают не 600%, а 200%. */
    public static double averageBonus(final List<Double> bonuses) {
        double sum = 0;
        int n = 0;
        for (final Double b : bonuses) {
            if (b != null && b > 0) {
                sum += b;
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    /** «Заточка: +N (Атака +X% ×M)» — как в плагине. */
    public static String loreLine(final int level, final boolean attacks, final double bonus) {
        final double mult = 1.0 + bonus / 100.0;
        return "<gold>Заточка: <yellow>+" + level + "</yellow> <gray>(" + (attacks ? "Атака" : "Защита")
            + " <green>+" + Text.num(bonus) + "%</green> <dark_gray>×" + Text.num(mult) + "</dark_gray>)</gray>";
    }

    /** Строка лора — заточка (наша или старых версий плагина). */
    public static boolean isSharpenLore(final String plain) {
        final String p = plain.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) {
            return false;
        }
        for (final Pattern pattern : OLD_LORE) {
            if (pattern.matcher(p).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Процент для показа в MiniMessage: 0.5 из таблиц RQ — «<1» (экранировано для MiniMessage). */
    public static String percent(final double value) {
        if (value > 0 && value < 1) {
            return "\\<1";
        }
        return Text.num(Math.round(value * 10.0) / 10.0);
    }

    private static double clamp(final double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }
}
