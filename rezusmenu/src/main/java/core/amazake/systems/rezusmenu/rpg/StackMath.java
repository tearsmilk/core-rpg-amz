package core.amazake.systems.rezusmenu.rpg;

/**
 * Стаки инвентаря меню — чистая математика (без сервера, покрыта тестами).
 *
 * <p>В хранилище стакается до {@link #STASH_MAX} (99) — но только то, что стакается и в ванили (размер стака &gt; 1);
 * мечи, посохи, броня, инструменты (стак 1) так и лежат по одному. В кармане (ванильный инвентарь), в броне и во второй
 * руке — ванильные пределы: предметы с компонентом стака не меняются, лишние штуки остаются там, откуда их брали.</p>
 *
 * <p>Перекладывание никогда не создаёт и не теряет штук: что не влезло — остаётся на месте; обмен разных предметов
 * делается, только если обе стопки помещаются целиком (иначе — отказ, ничего не меняется).</p>
 */
final class StackMath {

    /** Предел стака в хранилище. */
    static final int STASH_MAX = 99;

    private StackMath() {
    }

    /** Предел стопки в слоте хранилища для предмета с ванильным пределом {@code vanillaMax}. */
    static int stashLimit(final int vanillaMax) {
        return vanillaMax <= 1 ? 1 : Math.max(vanillaMax, STASH_MAX);
    }

    /** Сколько штук записать в сам предмет (не больше его ванильного предела): остальное — счётчиком рядом. */
    static int storedAmount(final int count, final int vanillaMax) {
        return Math.max(1, Math.min(count, Math.max(1, vanillaMax)));
    }

    /**
     * Сколько штук в слоте хранилища: счётчик {@code stored} (если есть и в пределах 1..{@link #STASH_MAX}), иначе
     * число в самом предмете.
     */
    static int decodeCount(final Integer stored, final int amount) {
        if (stored != null && stored >= 1 && stored <= STASH_MAX) {
            return Math.max(stored, amount);
        }
        return amount;
    }

    enum Kind {
        /** В пустую клетку: {@code to} штук легло, {@code from} осталось. */
        MOVE,
        /** Одинаковые сложились: {@code to} — стало в клетке, {@code from} — осталось. */
        MERGE,
        /** Разные поменялись местами. */
        SWAP,
        /** Одинаковые, но в клетке уже полный стак — ничего не меняется. */
        FULL,
        /** Нельзя (не помещается целиком при обмене) — ничего не меняется. */
        REFUSE
    }

    /** Итог: сколько станет в клетке, куда кладём ({@code to}), и в клетке, откуда брали ({@code from}; 0 — пусто). */
    record Plan(Kind kind, int to, int from) {

        boolean changes() {
            return this.kind == Kind.MOVE || this.kind == Kind.MERGE || this.kind == Kind.SWAP;
        }
    }

    /**
     * Переложить стопку {@code a} штук в клетку, где лежит {@code b} штук (0 — пусто). {@code similar} — одинаковые
     * предметы; {@code limitTo} — предел стопки {@code a} в клетке назначения, {@code limitFrom} — предел стопки
     * {@code b} в исходной клетке (для обмена).
     */
    static Plan plan(final int a, final int b, final boolean similar, final int limitTo, final int limitFrom) {
        if (a <= 0 || limitTo <= 0) {
            return new Plan(Kind.REFUSE, b, a);
        }
        if (b <= 0) {
            final int put = Math.min(a, limitTo);
            return new Plan(Kind.MOVE, put, a - put);
        }
        if (similar) {
            final int space = limitTo - b;
            if (space <= 0) {
                return new Plan(Kind.FULL, b, a);
            }
            final int moved = Math.min(a, space);
            return new Plan(Kind.MERGE, b + moved, a - moved);
        }
        if (a <= limitTo && b <= limitFrom) {
            return new Plan(Kind.SWAP, a, b);
        }
        return new Plan(Kind.REFUSE, b, a);
    }
}
