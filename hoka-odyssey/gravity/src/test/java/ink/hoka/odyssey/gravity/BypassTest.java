package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.gravity.VacuumService.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Админ-обход вакуума: приоритет над режимами других модулей и сохранение в bypass.yml. */
class BypassTest {
    private static final UUID A = UUID.fromString("0f8e3c2a-1b2c-4d5e-8f90-123456789abc");
    private static final UUID B = UUID.fromString("ffffffff-0000-4000-8000-000000000001");

    @Test
    void bypassWinsOverEveryMode() {
        assertNull(VacuumOverrides.decide(false, null));               // решает карта
        assertNull(VacuumOverrides.decide(false, Mode.MAP));
        assertEquals(Boolean.TRUE, VacuumOverrides.decide(false, Mode.VACUUM));
        assertEquals(Boolean.FALSE, VacuumOverrides.decide(false, Mode.NORMAL));
        for (Mode m : new Mode[] {null, Mode.MAP, Mode.VACUUM, Mode.NORMAL}) {
            assertEquals(Boolean.FALSE, VacuumOverrides.decide(true, m), "обход главнее " + m);
        }
    }

    @Test
    void bypassAndModulesModesAreIndependent() {
        VacuumOverrides o = new VacuumOverrides(new ConcurrentHashMap<>());
        o.override(A, Mode.NORMAL);                                     // модуль (корабль/зона) поставил NORMAL
        o.bypass.set(A, "admin", true);                                // админ включил обход
        o.override(A, Mode.MAP);                                       // модуль вернул «по карте» — обход цел
        assertTrue(o.bypass.contains(A));
        o.override(A, Mode.VACUUM);
        assertTrue(o.bypass.contains(A));
        o.bypass.set(A, "admin", false);                               // обход сняли — режим модуля цел
        assertEquals(Mode.VACUUM, o.override(A));
        o.bypass.set(A, "admin", true);
        o.forget(A);                                                   // выход игрока: режим модуля сброшен, обход — нет
        assertEquals(Mode.MAP, o.override(A));
        assertTrue(o.bypass.contains(A));
        o.clear();                                                     // выгрузка gravity обход не стирает (он в файле)
        assertTrue(o.bypass.contains(A));
    }

    @Test
    void setReportsChangesAndFindsByName() {
        Bypass b = new Bypass();
        assertTrue(b.set(A, "Admin", true));
        assertFalse(b.set(A, "Admin", true));
        assertEquals(A, b.byName("admin"));
        assertNull(b.byName("other"));
        assertTrue(b.rename(A, "Admin2"));
        assertFalse(b.rename(A, "Admin2"));
        assertFalse(b.rename(B, "x"));                                 // не в списке — не добавляется
        assertTrue(b.set(A, null, false));
        assertFalse(b.set(A, null, false));
        assertFalse(b.contains(A));
    }

    @Test
    void renderParseRoundTrip() {
        Map<UUID, String> all = new LinkedHashMap<>();
        all.put(A, "Admin");
        all.put(B, "bad\"name");
        Map<UUID, String> back = Bypass.parse(Bypass.render(all));
        assertEquals(2, back.size());
        assertEquals("Admin", back.get(A));
        assertEquals("badname", back.get(B));
        assertTrue(Bypass.parse(Bypass.render(Map.of())).isEmpty());
    }

    @Test
    void parseToleratesHandEdits() {
        String text = """
            # комментарий
            players:
              0f8e3c2a-1b2c-4d5e-8f90-123456789abc: Admin   # без кавычек
              "not-a-uuid": x
              ffffffff-0000-4000-8000-000000000001: ""
            other:
              ffffffff-0000-4000-8000-000000000002: chужой раздел
            """;
        Map<UUID, String> m = Bypass.parse(text);
        assertEquals(2, m.size());
        assertEquals("Admin", m.get(A));
        assertEquals("", m.get(B));
    }

    @Test
    void survivesSaveAndLoad(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("gravity").resolve(Bypass.FILE);
        Bypass b = new Bypass();
        b.load(f);                                                     // файла нет — пусто
        assertTrue(b.snapshot().isEmpty());
        b.set(A, "Admin", true);
        b.set(B, "Mod", true);
        b.save(f);
        assertTrue(Files.isRegularFile(f));
        assertFalse(Files.exists(f.resolveSibling(Bypass.FILE + ".tmp")));
        Bypass again = new Bypass();                                   // «перезапуск»
        again.load(f);
        assertEquals(b.snapshot(), again.snapshot());
        again.set(B, null, false);
        again.save(f);
        Bypass third = new Bypass();
        third.load(f);
        assertTrue(third.contains(A));
        assertFalse(third.contains(B));
    }
}
