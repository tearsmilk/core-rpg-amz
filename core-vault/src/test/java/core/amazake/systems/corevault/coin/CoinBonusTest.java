package core.amazake.systems.corevault.coin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Без сервера и MythicLib бонуса нет: сумма не меняется, ошибка чтения стата не роняет подбор. */
class CoinBonusTest {

    private static final Logger LOG = Logger.getLogger("CoinBonusTest");

    @Test
    void noBonusWithoutMythicLib() {
        assertEquals(0.0, CoinBonus.percent(null, LOG));
        assertEquals(300, CoinBonus.apply(null, 300, LOG));
    }

    @Test
    void nonPositiveAmountUntouched() {
        assertEquals(0, CoinBonus.apply(null, 0, LOG));
        assertEquals(-5, CoinBonus.apply(null, -5, LOG));
    }
}
