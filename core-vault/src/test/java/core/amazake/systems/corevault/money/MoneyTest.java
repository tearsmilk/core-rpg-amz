package core.amazake.systems.corevault.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesCommonForms() throws Exception {
        assertEquals(1000, Money.parse("10", 2, false));
        assertEquals(1050, Money.parse("10.5", 2, false));
        assertEquals(1050, Money.parse("10,50", 2, false));
        assertEquals(100_000_00, Money.parse("100 000", 2, false));
        assertEquals(100_000_00, Money.parse("100_000", 2, false));
        assertEquals(150_000, Money.parse("1.5k", 2, false));
        assertEquals(150_000, Money.parse("1,5к", 2, false));
        assertEquals(200_000_000, Money.parse("2m", 2, false));
        assertEquals(5, Money.parse("5", 0, false));
        assertEquals(50, Money.parse(".5", 2, false));
        assertEquals(0, Money.parse("0", 2, true));
    }

    @Test
    void rejectsBadAmounts() {
        assertThrows(Money.ParseException.class, () -> Money.parse("-5", 2, false));
        assertThrows(Money.ParseException.class, () -> Money.parse("0", 2, false));
        assertThrows(Money.ParseException.class, () -> Money.parse("1.234", 2, false));
        assertThrows(Money.ParseException.class, () -> Money.parse("1.5", 0, false));
        assertThrows(Money.ParseException.class, () -> Money.parse("abc", 2, false));
        assertThrows(Money.ParseException.class, () -> Money.parse("1e5", 2, false));
        assertThrows(Money.ParseException.class, () -> Money.parse("NaN", 2, false));
        assertThrows(Money.ParseException.class, () -> Money.parse("", 2, false));
        assertThrows(Money.ParseException.class, () -> Money.parse("99999999999999999999", 2, false));
    }

    @Test
    void formats() {
        assertEquals("1 234 567,8", Money.format(123456780, 2, " ", ",", true));
        assertEquals("1 234 567,80", Money.format(123456780, 2, " ", ",", false));
        assertEquals("10", Money.format(1000, 2, " ", ",", true));
        assertEquals("0,05", Money.format(5, 2, " ", ",", true));
        assertEquals("-1,5", Money.format(-150, 2, " ", ",", true));
        assertEquals("999", Money.format(999, 0, " ", ",", true));
        assertEquals("1000", Money.format(1000, 0, "", ",", true));
    }

    @Test
    void russianPlurals() {
        final Currency c = Currency.DEFAULT;
        assertEquals("1 монета", c.format(100));
        assertEquals("2 монеты", c.format(200));
        assertEquals("4 монеты", c.format(400));
        assertEquals("5 монет", c.format(500));
        assertEquals("11 монет", c.format(1100));
        assertEquals("12 монет", c.format(1200));
        assertEquals("14 монет", c.format(1400));
        assertEquals("21 монета", c.format(2100));
        assertEquals("22 монеты", c.format(2200));
        assertEquals("111 монет", c.format(11100));
        assertEquals("0 монет", c.format(0));
        assertEquals("1,5 монеты", c.format(150));
        assertEquals("1 001 монета", c.format(100100));
    }

    @Test
    void plainRoundTrip() {
        assertEquals("123.45", Money.plain(12345, 2));
        assertEquals(12345, Money.fromPlain("123.45", 2));
        assertEquals(12345, Money.fromPlain("123.459", 2));
        assertEquals(12300, Money.fromPlain("123", 2));
        assertEquals(1569618, Money.fromDouble(15696.179999999999, 2));
        assertEquals(15696.18, Money.toDouble(1569618, 2));
    }
}
