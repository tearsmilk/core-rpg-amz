package ink.hoka.odyssey.oxygen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LungsTest {
    /** Ванильные числа: 15 с воздуха, 3,75 с на восстановление, 2 урона раз в секунду. */
    static final Settings VANILLA = new Settings(Set.of("galaxy1"), Settings.perTick(15), Settings.perTick(3.75),
        2f, 20, false, "warn", 20, "&bСкафандр", List.of());

    @Test
    void vanillaRatesPerTick() {
        assertEquals(1.0, VANILLA.drainPerTick(), 1e-9);          // как под водой: 1 единица за тик
        assertEquals(4.0, VANILLA.refillPerTick(), 1e-9);         // как на поверхности: 4 за тик
        assertEquals(Lungs.MAX, Settings.perTick(0), 1e-9);       // 0 с — сразу
        assertEquals(1, Settings.ticks(0));
        assertEquals(30, Settings.ticks(1.5));
    }

    @Test
    void drainsThenHurtsOncePerInterval() {
        Lungs l = new Lungs();
        assertEquals(Lungs.MAX, l.meta());
        int ticks = 0;
        while (l.meta() > 0) {
            assertFalse(l.tick(VANILLA, true, true), "урон до того, как кончился воздух");
            ticks++;
        }
        assertEquals(300, ticks);                                  // 15 секунд
        assertEquals(Lungs.EMPTY, l.meta());
        // дальше — урон ровно раз в 20 тиков
        int hits = 0;
        int firstHit = -1;
        for (int t = 1; t <= 200; t++) {
            if (l.tick(VANILLA, true, true)) {
                hits++;
                if (firstHit < 0) {
                    firstHit = t;
                }
            }
        }
        assertEquals(10, hits);
        assertEquals(20, firstHit);
    }

    @Test
    void noDamageWhenCannotHurt() {
        Lungs l = new Lungs();
        for (int t = 0; t < 1000; t++) {
            assertFalse(l.tick(VANILLA, true, false));
        }
        assertEquals(Lungs.EMPTY, l.meta());                       // пузырьки (если показываем) пустые, но урона нет
    }

    @Test
    void refillsQuicklyAndStops() {
        Lungs l = new Lungs();
        for (int t = 0; t < 400; t++) {
            l.tick(VANILLA, true, true);
        }
        assertFalse(l.full());
        int ticks = 0;
        while (!l.full()) {
            assertFalse(l.tick(VANILLA, false, true));
            ticks++;
        }
        assertEquals(75, ticks);                                   // 3,75 секунды
        assertEquals(Lungs.MAX, l.meta());
        l.tick(VANILLA, false, true);
        assertEquals(Lungs.MAX, l.meta());                         // выше максимума не растёт
    }

    @Test
    void suffocationCounterResetsWhenBreathing() {
        Lungs l = new Lungs();
        for (int t = 0; t < 300 + 19; t++) {
            assertFalse(l.tick(VANILLA, true, true));
        }
        l.tick(VANILLA, false, true);                             // один вдох
        for (int t = 0; t < 1; t++) {
            assertFalse(l.tick(VANILLA, true, true));              // воздух снова есть (4 единицы), урона нет
        }
    }

    @Test
    void warningOncePerInterval() {
        Lungs l = new Lungs();
        int shown = 0;
        for (int t = 1; t <= 100; t++) {
            if (l.warnTick(true, 20)) {
                shown++;
                assertEquals(0, t % 20);
            }
        }
        assertEquals(5, shown);
        assertFalse(l.warnTick(false, 20));
        for (int t = 1; t < 20; t++) {
            assertFalse(l.warnTick(true, 20));                     // после перерыва — снова через интервал
        }
        assertTrue(l.warnTick(true, 20));
    }

    @Test
    void settingsFromDefaultConfig(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        try (InputStream in = LungsTest.class.getResourceAsStream("/config.yml")) {
            Files.copy(in, file);
        }
        Settings s = Settings.from(new ModuleConfig(file));
        assertEquals(Set.of("galaxy1"), s.maps());
        assertEquals(1.0, s.drainPerTick(), 1e-9);
        assertEquals(4.0, s.refillPerTick(), 1e-9);
        assertEquals(2f, s.damage());
        assertEquals(20, s.damageInterval());
        assertEquals(20, s.warningInterval());
        assertFalse(s.creativeBubbles());
        assertFalse(s.warning().isEmpty());
        assertEquals(3, s.suitLore().size());
    }
}
