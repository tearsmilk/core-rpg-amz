package ink.hoka.odyssey.starmap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StarmapProtocolTest {
    @Test
    void decodeOpen() {
        assertEquals(-1, StarmapProtocol.decodeOpen(null));
        assertEquals(-1, StarmapProtocol.decodeOpen(new byte[] {1}));
        assertEquals(-1, StarmapProtocol.decodeOpen(new byte[] {2, 1}));
        assertEquals(1, StarmapProtocol.decodeOpen(new byte[] {1, 1}));
        assertEquals(0, StarmapProtocol.decodeOpen(new byte[] {1, 0, 9}));
    }

    @Test
    void contactsRoundTrip() throws IOException {
        UUID u = UUID.randomUUID();
        byte[] b = StarmapProtocol.contacts("galaxy1",
                List.of(new StarmapProtocol.ShipContact("s1", "Шаттл", "Игрок", 3, 1.5, 64, -2, 90f)),
                List.of(new StarmapProtocol.PlayerContact(u, "Друг", 10, Double.NaN, 20, 180f, 1)));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        assertEquals(1, in.readUnsignedByte());
        assertEquals(1, in.readUnsignedByte());
        assertEquals("galaxy1", in.readUTF());
        assertEquals(1, in.readInt());
        assertEquals("s1", in.readUTF());
        assertEquals("Шаттл", in.readUTF());
        assertEquals("Игрок", in.readUTF());
        assertEquals(3, in.readUnsignedByte());
        assertEquals(1.5, in.readDouble());
        assertEquals(64, in.readDouble());
        assertEquals(-2, in.readDouble());
        assertEquals(90f, in.readFloat());
        assertEquals(1, in.readInt());
        assertEquals(u, new UUID(in.readLong(), in.readLong()));
        assertEquals("Друг", in.readUTF());
        assertEquals(10, in.readDouble());
        assertEquals(0, in.readDouble());                     // NaN → 0
        assertEquals(20, in.readDouble());
        assertEquals(180f, in.readFloat());
        assertEquals(1, in.readUnsignedByte());
        assertEquals(-1, in.read());
    }

    @Test
    void where() throws IOException {
        assertEquals(true, StarmapProtocol.isWhere(new byte[] {2}));
        assertEquals(false, StarmapProtocol.isWhere(new byte[] {1, 1}));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(StarmapProtocol.where("galaxy1")));
        assertEquals(2, in.readUnsignedByte());
        assertEquals("galaxy1", in.readUTF());
    }

    @Test
    void longStringsAreCut() {
        assertEquals(StarmapProtocol.MAX_STRING, StarmapProtocol.cut("x".repeat(500)).length());
        assertEquals("", StarmapProtocol.cut(null));
    }
}
