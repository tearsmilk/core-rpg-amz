package core.amazake.systems.rezusmenu.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Стаки до 99 в хранилище: пределы, запись/чтение счётчика и перекладывание без дюпов и потерь. */
class StackMathTest {

    private static final int COBBLE = 64;   // ванильный предел булыжника
    private static final int PEARL = 16;    // эндер-жемчуг
    private static final int SWORD = 1;     // мечи, посохи, броня, инструменты

    @Test
    void onlyStackableGrowsTo99() {
        assertEquals(99, StackMath.stashLimit(COBBLE));
        assertEquals(99, StackMath.stashLimit(PEARL));
        assertEquals(1, StackMath.stashLimit(SWORD));
        assertEquals(1, StackMath.stashLimit(0));
        assertEquals(99, StackMath.stashLimit(99));   // предмет с компонентом max_stack_size 99
    }

    @Test
    void storedAmountNeverExceedsVanillaAndCountRoundTrips() {
        assertEquals(64, StackMath.storedAmount(99, COBBLE));
        assertEquals(16, StackMath.storedAmount(70, PEARL));
        assertEquals(35, StackMath.storedAmount(35, COBBLE));
        assertEquals(1, StackMath.storedAmount(1, SWORD));
        for (int n = 1; n <= 99; n++) {
            final int amount = StackMath.storedAmount(n, COBBLE);
            final Integer counter = n > amount ? n : null;   // как пишет Stash.set
            assertEquals(n, StackMath.decodeCount(counter, amount));
        }
        // старая запись без счётчика читается как есть; мусорный счётчик не верим
        assertEquals(40, StackMath.decodeCount(null, 40));
        assertEquals(40, StackMath.decodeCount(0, 40));
        assertEquals(40, StackMath.decodeCount(500, 40));
    }

    @Test
    void ninetyNineCobbleIntoStashAndBackWithoutLoss() {
        // 64 из кармана в пустой слот хранилища — целиком
        StackMath.Plan p = StackMath.plan(64, 0, false, StackMath.stashLimit(COBBLE), 1);
        assertEquals(StackMath.Kind.MOVE, p.kind());
        assertEquals(64, p.to());
        assertEquals(0, p.from());
        // ещё 64 к ним: до 99, 29 остаётся в кармане
        p = StackMath.plan(64, 64, true, StackMath.stashLimit(COBBLE), COBBLE);
        assertEquals(StackMath.Kind.MERGE, p.kind());
        assertEquals(99, p.to());
        assertEquals(29, p.from());
        // 99 обратно в пустую клетку кармана: 64 там, 35 остаётся в хранилище
        p = StackMath.plan(99, 0, false, COBBLE, 1);
        assertEquals(StackMath.Kind.MOVE, p.kind());
        assertEquals(64, p.to());
        assertEquals(35, p.from());
    }

    @Test
    void swordStaysSingle() {
        // меч в слот хранилища, где уже такой же меч — не складываются
        StackMath.Plan p = StackMath.plan(1, 1, true, StackMath.stashLimit(SWORD), SWORD);
        assertEquals(StackMath.Kind.FULL, p.kind());
        assertFalse(p.changes());
        p = StackMath.plan(1, 0, false, StackMath.stashLimit(SWORD), 1);
        assertEquals(1, p.to());
    }

    @Test
    void swapOnlyWhenBothFit() {
        // 99 булыжника на клетку кармана с мечом: в карман влезает только 64 — отказ, ничего не меняется
        StackMath.Plan p = StackMath.plan(99, 1, false, COBBLE, StackMath.stashLimit(SWORD));
        assertEquals(StackMath.Kind.REFUSE, p.kind());
        assertFalse(p.changes());
        // меч из кармана на 99 булыжника в хранилище: булыжник в карман не влезает — отказ
        p = StackMath.plan(1, 99, false, StackMath.stashLimit(SWORD), COBBLE);
        assertEquals(StackMath.Kind.REFUSE, p.kind());
        // 40 булыжника и меч — меняются
        p = StackMath.plan(40, 1, false, StackMath.stashLimit(COBBLE), COBBLE);
        assertEquals(StackMath.Kind.SWAP, p.kind());
        assertEquals(40, p.to());
        assertEquals(1, p.from());
    }

    @Test
    void neverCreatesOrLosesItems() {
        final int[] limits = {1, 16, 64, 99};
        for (int a = 1; a <= 99; a++) {
            for (int b = 0; b <= 99; b += 7) {
                for (final int lt : limits) {
                    for (final int lf : limits) {
                        for (final boolean similar : new boolean[] {true, false}) {
                            if (b > 0 && similar && b > lt && a > lf) {
                                continue; // не бывает: в клетке не может лежать больше её предела
                            }
                            final StackMath.Plan p = StackMath.plan(a, similar || b == 0 ? b : b, similar, lt, lf);
                            if (p.kind() == StackMath.Kind.SWAP) {
                                assertEquals(a, p.to());
                                assertEquals(b, p.from());
                                assertTrue(a <= lt && b <= lf);
                            } else {
                                assertEquals(a + b, p.to() + p.from(), a + " " + b + " " + similar + " " + lt + " " + lf);
                            }
                            if (p.changes() && p.kind() != StackMath.Kind.SWAP) {
                                assertTrue(p.to() <= Math.max(lt, b), "предел клетки");
                                assertTrue(p.from() >= 0);
                            }
                        }
                    }
                }
            }
        }
    }
}
