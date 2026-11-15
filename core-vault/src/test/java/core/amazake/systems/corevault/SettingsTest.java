package core.amazake.systems.corevault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.corevault.coin.DropRule;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

public class SettingsTest {

    public static YamlConfiguration bundled() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        try (var in = new InputStreamReader(SettingsTest.class.getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8)) {
            y.load(in);
        }
        return y;
    }

    public static Settings defaults() throws Exception {
        final YamlConfiguration y = bundled();
        return Settings.from(y, Settings.messagesOf(y), w -> { });
    }

    @Test
    void bundledConfigParsesWithoutWarnings() throws Exception {
        final List<String> warns = new ArrayList<>();
        final Settings s = Settings.from(bundled(), warns::add);
        assertTrue(warns.isEmpty(), warns.toString());
        assertTrue(s.modules().economy() && s.modules().levels() && s.modules().coins());
        assertEquals(Settings.Backend.AUTO, s.economy().backend());
        assertEquals(2, s.economy().currency().decimals());
        assertEquals(100, s.economy().payMin());
        assertEquals(1_000_000, s.economy().payConfirmAbove());
        assertEquals(100_000_000_000L, s.economy().maxBalance());
        assertEquals("High", s.economy().vaultPriority());
        assertEquals(100, s.coins().value());
        assertEquals("SUNFLOWER", s.coins().material());
        assertFalse(s.coins().hoppers());
        assertFalse(s.levels().vanillaBar());
        assertEquals(100, s.levels().curve().need(1));
        assertEquals(100, s.levels().curve().maxLevel());
        final DropRule zombie = s.coins().drops().find(null, "ZOMBIE", true);
        assertEquals(50, zombie.chance());
        assertEquals(1, zombie.min());
        assertEquals(2, zombie.max());
        assertEquals(30, s.coins().drops().find(null, "PHANTOM", true).chance());
        assertEquals(0, s.coins().drops().find(null, "COW", false).roll(new java.util.SplittableRandom()));
        assertEquals(5, s.levels().mobExp().find(null, "ZOMBIE", true)[0]);
        assertEquals(0, s.levels().mobExp().find(null, "COW", false)[1]);
        assertNotNull(s.message("balance-self"));
    }

    @Test
    void badValuesFallBack() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
            modules: {economy: false, levels: true, coins: true}
            economy:
              backend: weird
              currency: {decimals: 9}
              pay: {min: "-5"}
              vault: {priority: Top}
            levels:
              curve: {formula: "100 * foo", table: {"1": 50, "x": 3}}
              rewards: {"5": ["say hi %player%"], "bad": []}
              sources:
                mobs:
                  types: {ZOMBIE: "3-1", SKELETON: "2-4"}
            coins:
              value: 0
              drops:
                types:
                  ZOMBIE: {chance: 150}
                  HUSK: {chance: 10, amount: 2-3, value: 5}
                hostile: none
            """);
        final List<String> warns = new ArrayList<>();
        final Settings s = Settings.from(y, warns::add);
        assertEquals(10, warns.size(), String.join("\n", warns));
        assertFalse(s.modules().economy());
        assertTrue(s.modules().describe().contains("не работает без economy"));
        assertEquals(Settings.Backend.AUTO, s.economy().backend());
        assertEquals(2, s.economy().currency().decimals());
        assertEquals("High", s.economy().vaultPriority());
        assertEquals(1, s.levels().curve().need(5) > 1 ? 1 : 0);
        assertEquals(50, s.levels().curve().need(1));
        assertEquals(List.of("say hi %player%"), s.levels().rewards().get(5));
        assertNull(s.levels().mobExp().find(null, "ZOMBIE", true));
        assertEquals(4, s.levels().mobExp().find(null, "SKELETON", true)[1]);
        assertEquals(100, s.coins().value());
        assertNull(s.coins().drops().find(null, "ZOMBIE", true));
        assertEquals(500, s.coins().drops().find(null, "HUSK", true).value());
        assertNull(s.coins().drops().find(null, "SPIDER", true));
    }
}
