package core.amazake.systems.corevault.money;

/**
 * Валюта: точность (знаков после запятой), названия в трёх русских формах и формат вывода.
 *
 * @param decimals   знаков после запятой (0..4); внутри суммы хранятся в «копейках» (long, ×10^decimals)
 * @param singular   1, 21, 101 … монета
 * @param genitive   2–4, 22–24 … и дробные (1,5) монеты — родительный падеж единственного числа
 * @param plural     0, 5–20, 25 … монет — родительный падеж множественного числа
 * @param group      разделитель тысяч ("" — без разделения)
 * @param decimal    разделитель дробной части
 * @param trimZeros  не писать нулевую дробную часть (10 вместо 10,00)
 * @param pattern    шаблон суммы с названием: {amount} и {name}
 */
public record Currency(int decimals, String singular, String genitive, String plural, String group, String decimal,
                       boolean trimZeros, String pattern) {

    public static final Currency DEFAULT = new Currency(2, "монета", "монеты", "монет", " ", ",", true, "{amount} {name}");

    public Currency {
        if (decimals < 0 || decimals > 4) {
            throw new IllegalArgumentException("decimals 0..4: " + decimals);
        }
    }

    /** Минорных единиц в одной целой (10^decimals). */
    public long unit() {
        return Money.pow10(this.decimals);
    }

    /** Название для суммы в минорных единицах: дробная сумма — родительный ед. ч. («1,5 монеты»). */
    public String nameFor(final long minor) {
        final long unit = this.unit();
        if (minor % unit != 0) {
            return this.genitive;
        }
        return Money.plural(Math.abs(minor / unit), this.singular, this.genitive, this.plural);
    }

    /** Число без названия: «1 234,5». */
    public String number(final long minor) {
        return Money.format(minor, this.decimals, this.group, this.decimal, this.trimZeros);
    }

    /** Сумма с названием по шаблону: «1 234 монеты». */
    public String format(final long minor) {
        return this.pattern.replace("{amount}", this.number(minor)).replace("{name}", this.nameFor(minor));
    }
}
