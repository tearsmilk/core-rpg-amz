package core.amazake.systems.corevault.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.SettingsTest;
import core.amazake.systems.corevault.data.Account;
import core.amazake.systems.corevault.data.TopCache;
import core.amazake.systems.corevault.level.LevelMath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    @Test
    void resolves() throws Exception {
        final Settings s = SettingsTest.defaults();
        final UUID id = UUID.randomUUID();
        final TopCache top = new TopCache();
        top.compute(List.of(new Account(id, "Steve", 123450, 4, 400)), x -> 123450, 10);
        final Placeholders p = new Placeholders(() -> s, x -> 123450, x -> new LevelMath.State(4, 400), top::get);
        assertEquals("1234.50", p.resolve(id, "balance"));
        assertEquals("1 234,5 монеты", p.resolve(id, "balance_formatted"));
        assertEquals("4", p.resolve(id, "level"));
        assertEquals("400", p.resolve(id, "exp"));
        assertEquals("800", p.resolve(id, "exp_needed"));
        assertEquals("50", p.resolve(id, "progress"));
        assertEquals("Steve", p.resolve(null, "top_1_name"));
        assertEquals("1 234,5 монеты", p.resolve(null, "top_1_value"));
        assertEquals("1234.50", p.resolve(null, "top_1_value_raw"));
        assertEquals("—", p.resolve(null, "top_2_name"));
        assertEquals("4", p.resolve(null, "leveltop_1_value"));
        assertNull(p.resolve(id, "nonsense"));
    }
}
