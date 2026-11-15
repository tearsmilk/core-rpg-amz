package core.amazake.systems.corevault.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.SettingsTest;
import core.amazake.systems.corevault.eco.InternalLedger;
import core.amazake.systems.corevault.eco.Ledger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {

    @TempDir
    Path dir;

    @Test
    void saveLoadRoundTrip() throws Exception {
        final Path f = this.dir.resolve("players.yml");
        final AccountStore st = new AccountStore(f, 2);
        final UUID a = UUID.randomUUID();
        final Account acc = st.getOrCreate(a, k -> new Account(k, "Steve", 12345, 3, 40));
        acc.addPending(List.of(4, 5));
        assertTrue(st.isDirty());
        assertTrue(st.save(false));
        assertFalse(st.isDirty());
        assertFalse(st.save(false));
        assertFalse(Files.exists(this.dir.resolve("players.yml.tmp")));
        assertTrue(Files.readString(f).contains("balance: '123.45'"));

        final AccountStore again = new AccountStore(f, 2);
        assertEquals(1, again.load(w -> { }));
        final Account b = again.get(a);
        assertEquals("Steve", b.name());
        assertEquals(12345, b.balance());
        assertEquals(3, b.level());
        assertEquals(40, b.exp());
        assertEquals(List.of(4, 5), b.pendingRewards());
        assertEquals(a, again.byName("steve"));

        // смена точности при чтении: десятичная строка не портится
        final AccountStore whole = new AccountStore(f, 0);
        whole.load(w -> { });
        assertEquals(123, whole.get(a).balance());
        again.rescale(3);
        assertEquals(123450, again.get(a).balance());
    }

    @Test
    void renameUpdatesIndex() {
        final AccountStore st = new AccountStore(this.dir.resolve("p.yml"), 2);
        final UUID a = UUID.randomUUID();
        final Account acc = st.getOrCreate(a, k -> new Account(k, "Old", 0, 1, 0));
        st.rename(acc, "New");
        assertEquals(a, st.byName("new"));
        assertEquals(null, st.byName("old"));
    }

    @Test
    void ledgerRulesAndConcurrency() throws Exception {
        final Settings s = SettingsTest.defaults();
        final AccountStore st = new AccountStore(this.dir.resolve("p.yml"), 2);
        final InternalLedger l = new InternalLedger(st, () -> s);
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        assertEquals(0, l.balance(a));
        assertTrue(l.deposit(a, "A", 10_000).ok());
        assertFalse(l.withdraw(a, "A", 10_001).ok());
        assertEquals(10_000, l.balance(a));
        assertFalse(l.transfer(a, "A", a, "A", 1).ok());
        assertFalse(l.transfer(a, "A", b, "B", 0).ok());
        final Ledger.Result t = l.transfer(a, "A", b, "B", 2_500);
        assertTrue(t.ok());
        assertEquals(7_500, t.balance());
        assertEquals(2_500, l.balance(b));
        assertFalse(l.deposit(a, "A", s.economy().maxBalance()).ok());
        assertEquals(s.economy().maxBalance(), l.set(a, "A", Long.MAX_VALUE / 8).balance());
        l.set(a, "A", 1_000_000);
        l.set(b, "B", 1_000_000);

        // перекрёстные переводы и списания из многих потоков: сумма денег сохраняется, минуса нет
        final ExecutorService ex = Executors.newFixedThreadPool(8);
        final List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final boolean dir = i % 2 == 0;
            fs.add(ex.submit(() -> {
                for (int k = 0; k < 20_000; k++) {
                    if (dir) {
                        l.transfer(a, "A", b, "B", 7);
                    } else {
                        l.transfer(b, "B", a, "A", 5);
                    }
                }
            }));
        }
        for (final var f : fs) {
            f.get();
        }
        ex.shutdown();
        assertTrue(ex.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(2_000_000, l.balance(a) + l.balance(b));
        assertTrue(l.balance(a) >= 0 && l.balance(b) >= 0);
    }

    @Test
    void topSorts() {
        final AccountStore st = new AccountStore(this.dir.resolve("p.yml"), 2);
        for (int i = 0; i < 5; i++) {
            final int n = i;
            st.getOrCreate(UUID.randomUUID(), k -> new Account(k, "P" + n, n * 100L, 10 - n, n));
        }
        final TopCache top = new TopCache();
        final TopCache.Snapshot s = top.compute(st.all(), id -> st.get(id).balance(), 3);
        assertEquals(3, s.money().size());
        assertEquals("P4", s.money().get(0).name());
        assertEquals("P0", s.levels().get(0).name());
        assertEquals(10, TopCache.levelOf(s.levels().get(0)));
    }
}
