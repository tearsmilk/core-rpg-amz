package ink.hoka.odyssey.client.guns;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoTest {
    /** CONFIG без оружия, с хвостами: громкость, анимации тела, предметы hoitems (как шлёт сервер). */
    private static byte[] config(boolean items) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeByte(Proto.VERSION);
        o.writeShort(0);
        o.writeFloat(0.5f);
        o.writeShort(1);
        o.writeUTF("ak47");
        o.writeUTF("guns/rifle_default.player_animation.json");
        if (items) {
            o.writeShort(2);
            o.writeUTF("ak47");
            o.writeUTF("ak47");
            o.writeUTF("pm");
            o.writeUTF("");
        }
        return bo.toByteArray();
    }

    @Test
    void configReadsGunItemsTrailer() throws IOException {
        Map<String, String> anims = new HashMap<>();
        Map<String, String> items = new HashMap<>();
        float[] vv = {0};
        Proto.readConfig(new DataInputStream(new ByteArrayInputStream(config(true))), vv, anims, items);
        assertEquals(0.5f, vv[0]);
        assertEquals("guns/rifle_default.player_animation.json", anims.get("ak47"));
        assertEquals(Map.of("ak47", "ak47"), items);
    }

    @Test
    void configWithoutItemsTrailer() throws IOException {
        Map<String, String> items = new HashMap<>();
        Proto.readConfig(new DataInputStream(new ByteArrayInputStream(config(false))), new float[1], new HashMap<>(), items);
        assertTrue(items.isEmpty());
    }
}
