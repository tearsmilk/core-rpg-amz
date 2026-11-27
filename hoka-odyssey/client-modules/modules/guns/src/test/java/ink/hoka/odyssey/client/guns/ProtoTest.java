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

    @Test
    void configReadsKindsTrailer() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.write(config(true));
        o.writeShort(2);
        o.writeUTF("ai_awp");
        o.writeUTF("sniper");
        o.writeFloat(0.9f);
        o.writeByte(1);
        o.writeFloat(1f);
        o.writeUTF("glock_17");
        o.writeUTF("pistol");
        o.writeFloat(0f);
        o.writeByte(0);
        o.writeFloat(0.4f);
        Map<String, Proto.Kind> kinds = new HashMap<>();
        Proto.readConfig(new DataInputStream(new ByteArrayInputStream(bo.toByteArray())), new float[1], new HashMap<>(),
                new HashMap<>(), kinds);
        assertEquals(new Proto.Kind("sniper", 0.9f, true, 1f), kinds.get("ai_awp"));
        assertEquals(0.4f, kinds.get("glock_17").flashScale());
        assertTrue(kinds.get("glock_17").pistol());
        assertTrue(!kinds.get("ai_awp").pistol());
        // старый сервер (без третьего хвоста) — видов нет, оружие считается винтовкой
        Map<String, Proto.Kind> none = new HashMap<>();
        Proto.readConfig(new DataInputStream(new ByteArrayInputStream(config(true))), new float[1], new HashMap<>(),
                new HashMap<>(), none);
        assertTrue(none.isEmpty());
    }

    @Test
    void configReadsVacuumRecoilTrailer() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.write(config(true));
        o.writeShort(0);                                           // третий хвост — пустой
        o.writeShort(3);
        o.writeUTF("glock_17");
        o.writeFloat(0.5f);
        o.writeUTF("ak47");
        o.writeFloat(1f);
        o.writeUTF("ai_awp");
        o.writeFloat(3f);
        Map<String, Float> recoil = new HashMap<>();
        Proto.readConfig(new DataInputStream(new ByteArrayInputStream(bo.toByteArray())), new float[1], new HashMap<>(),
                new HashMap<>(), new HashMap<>(), recoil);
        assertEquals(Map.of("glock_17", 0.5f, "ak47", 1f, "ai_awp", 3f), recoil);
        Map<String, Float> none = new HashMap<>();                 // старый сервер — хвоста нет
        Proto.readConfig(new DataInputStream(new ByteArrayInputStream(config(true))), new float[1], new HashMap<>(),
                new HashMap<>(), new HashMap<>(), none);
        assertTrue(none.isEmpty());
    }
}
