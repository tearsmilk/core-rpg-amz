package core.amazake.systems.rezusmenu.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Account.Summary sum(final int level, final String cls) {
        return new Account.Summary(level, cls, cls, "Деревня", List.of(new Account.Stat("Сила", "22")));
    }

    @Test
    void slotsFreePlusBoughtCappedByMax() {
        final Account a = new Account(ID);
        assertEquals(2, a.slots(2, 4));
        assertEquals(2, a.setExtraSlots(5, 2, 4));
        assertEquals(4, a.slots(2, 4));
        assertFalse(a.canBuy(2, 4));
        assertEquals(0, a.setExtraSlots(-3, 2, 4));
        assertTrue(a.canBuy(2, 4));
    }

    @Test
    void adoptCreateAndLimit() {
        final Account a = new Account(ID);
        assertEquals(1, a.adopt("archer", sum(19, "archer"), 1));
        assertEquals(1, a.active());
        assertEquals(1, a.generation());
        assertEquals(1, a.adopt("mage", sum(1, "mage"), 2), "второй adopt ничего не делает");
        final Account.Profile p2 = a.create("mage", "Маг", 2, 4, 3);
        assertNotNull(p2);
        assertEquals(2, p2.id());
        assertNull(p2.data(), "новый профиль — с нуля");
        assertNull(a.create("thief", "Вор", 2, 4, 4), "бесплатных слотов два");
        a.setExtraSlots(1, 2, 4);
        assertNotNull(a.create("thief", "Вор", 2, 4, 4));
        assertFalse(a.delete(1), "активный не удаляется");
    }

    @Test
    void activateBumpsGeneration() {
        final Account a = new Account(ID);
        a.adopt("archer", sum(1, "archer"), 1);
        a.create("mage", "Маг", 2, 4, 2);
        final long g = a.generation();
        assertEquals(g + 1, a.activate(2));
        assertEquals(2, a.active());
        assertEquals(-1, a.activate(99));
    }

    /** Сервер упал после записи файла, но до сохранения игрока: на игроке — старый профиль, он и остаётся. */
    @Test
    void reconcileKeepsWhatThePlayerWears() {
        final Account a = new Account(ID);
        a.adopt("archer", sum(1, "archer"), 1);
        a.create("mage", "Маг", 2, 4, 2);
        final long g1 = a.generation();
        a.activate(2);
        assertTrue(a.reconcile(1, g1));
        assertEquals(1, a.active());
        assertTrue(a.generation() > g1 + 1);
        assertFalse(a.reconcile(1, g1), "метка совпадает — ничего не делать");
        assertFalse(a.reconcile(0, 0), "нет метки — ничего не делать");
        assertFalse(a.reconcile(7, 3), "неизвестный профиль — не трогать");
    }

    @Test
    void yamlRoundTripKeepsDataAndSummary() throws Exception {
        final Account a = new Account(ID);
        a.adopt("archer", sum(19, "archer"), 100);
        a.setExtraSlots(1, 2, 4);
        final Account.Profile p2 = a.create("mage", "Маг", 2, 4, 200);
        final MemoryConfiguration data = new MemoryConfiguration();
        data.set("inventory", List.of("", "AAAA"));
        data.set("class.rq.level", 19);
        data.set("class.mmocore.skill.FIREBALL", 3);
        data.set("location.world", "world");
        a.store(1, data, sum(19, "archer"));
        a.activate(p2.id());
        final String text = a.toYaml().saveToString();
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        final Account b = Account.fromYaml(ID, y);
        assertEquals(2, b.active());
        assertEquals(a.generation(), b.generation());
        assertEquals(1, b.extraSlots());
        assertEquals(2, b.profiles().size());
        final Account.Profile q1 = b.profile(1);
        assertEquals("archer", q1.classKey());
        assertEquals(19, q1.summary().level());
        assertEquals("22", q1.summary().stats().get(0).value());
        assertEquals(List.of("", "AAAA"), q1.data().getStringList("inventory"));
        assertEquals(3, q1.data().getInt("class.mmocore.skill.FIREBALL"));
        assertNull(b.profile(2).data());
        // следующий id не повторяет существующие
        b.setExtraSlots(2, 2, 4);
        assertEquals(3, b.create("thief", "Вор", 2, 4, 300).id());
    }

    @Test
    void brokenActiveFallsBackToFirstProfile() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("active: 9\nprofiles:\n  3:\n    class: mage\n  5:\n    class: thief\n");
        final Account a = Account.fromYaml(ID, y);
        assertEquals(3, a.active());
        assertEquals(6, a.create("archer", "Стрелок", 4, 4, 1).id());
    }

    @Test
    void markerParsing() {
        final long[] m = ProfileService.parseMarker("2:17");
        assertEquals(2, m[0]);
        assertEquals(17, m[1]);
        assertNull(ProfileService.parseMarker("x"));
        assertNull(ProfileService.parseMarker(null));
    }
}
