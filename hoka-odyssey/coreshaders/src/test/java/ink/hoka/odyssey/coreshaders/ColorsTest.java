package ink.hoka.odyssey.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ColorsTest {
    private final Map<String, Integer> names = Map.of("red", 0xFF5050, "красный", 0xFF5050);

    @Test
    void hexForms() {
        assertEquals(0x12AB9F, Colors.parse("#12ab9f", names));
        assertEquals(0x12AB9F, Colors.parse("12AB9F", names));
        assertEquals(0x12AB9F, Colors.parse("0x12AB9F", names));
        assertEquals(0xFFAA00, Colors.parse("#fa0", names));
    }

    @Test
    void names() {
        assertEquals(0xFF5050, Colors.parse("RED", names));
        assertEquals(0xFF5050, Colors.parse("Красный", names));
    }

    @Test
    void rgbTriplet() {
        assertEquals(0x0A141E, Colors.parse("10,20,30", names));
        assertEquals(0x0A141E, Colors.parse("10 20 30", names));
        assertNull(Colors.parse("10,20,300", names));
    }

    @Test
    void invalid() {
        assertNull(Colors.parse("", names));
        assertNull(Colors.parse(null, names));
        assertNull(Colors.parse("#12345", names));
        assertNull(Colors.parse("#GGGGGG", names));
        assertNull(Colors.parse("blue", names));
    }

    @Test
    void hexOutput() {
        assertEquals("#00FF7F", Colors.hex(0x00FF7F));
        assertEquals("#FFFFFF", Colors.hex(0xFFFFFFFF));
    }
}
