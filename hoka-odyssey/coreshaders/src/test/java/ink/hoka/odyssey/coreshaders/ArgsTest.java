package ink.hoka.odyssey.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ArgsTest {
    @Test
    void validRadius() {
        assertEquals(16.0, Args.parseRadius("16"));
        assertEquals(2.5, Args.parseRadius("2,5"));
        assertEquals(0.5, Args.parseRadius("0.1"));
    }

    @Test
    void invalidRadius() {
        assertNull(Args.parseRadius("abc"));
        assertNull(Args.parseRadius("-3"));
        assertNull(Args.parseRadius("0"));
        assertNull(Args.parseRadius("NaN"));
        assertNull(Args.parseRadius("100000"));
        assertNull(Args.parseRadius(null));
    }
}
