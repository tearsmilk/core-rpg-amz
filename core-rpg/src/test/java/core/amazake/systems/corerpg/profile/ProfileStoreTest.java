package core.amazake.systems.corerpg.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.api.SystemContext;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileStoreTest {

    @TempDir
    Path dir;

    /** Исполнитель «вне тика», который выполняет задачи только по команде теста. */
    private final Deque<Runnable> io = new ArrayDeque<>();

    private void runIo() {
        while (!this.io.isEmpty()) {
            this.io.poll().run();
        }
    }

    private ProfileStore store() {
        final SystemContext context = (SystemContext) Proxy.newProxyInstance(SystemContext.class.getClassLoader(),
            new Class<?>[] {SystemContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "dataFolder" -> this.dir;
                case "logger" -> Logger.getAnonymousLogger();
                case "runOnceForPlayer" -> {
                    ((Runnable) args[1]).run();
                    yield true;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return new ProfileStore(context, this.io::add);
    }

    private static Player player(final UUID uuid) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static PlayerProfile level(final PlayerProfile p, final int level) {
        return new PlayerProfile(p.uuid(), p.classId(), p.professionId(), level, p.exp(), p.freePoints(), p.primary(),
            p.agility(), p.stamina(), p.luck());
    }

    private int levelOnDisk(final UUID uuid) throws Exception {
        final String text = Files.readString(this.dir.resolve("players").resolve(uuid + ".yml"));
        for (final String line : text.split("\n")) {
            if (line.startsWith("level:")) {
                return Integer.parseInt(line.substring("level:".length()).trim());
            }
        }
        throw new AssertionError("нет level в файле");
    }

    @Test
    void quitEvictsProfileImmediatelyAndWritesLatestSnapshot() throws Exception {
        final ProfileStore store = this.store();
        final UUID uuid = UUID.randomUUID();
        store.load(player(uuid), p -> { });
        this.runIo();
        assertTrue(store.isLoaded(uuid));
        store.update(uuid, p -> level(p, 5));

        store.unload(uuid);
        // Выгружен сразу, а не «когда-нибудь, если Bukkit.getPlayer уже вернёт null».
        assertFalse(store.isLoaded(uuid));
        this.runIo();
        assertEquals(5, this.levelOnDisk(uuid));
    }

    @Test
    void cleanQuitAlsoEvicts() {
        final ProfileStore store = this.store();
        final UUID uuid = UUID.randomUUID();
        store.load(player(uuid), p -> { });
        this.runIo();
        store.unload(uuid);
        this.runIo();
        assertFalse(store.isLoaded(uuid));
    }

    @Test
    void loadFinishingAfterQuitDoesNotResurrectProfile() {
        final ProfileStore store = this.store();
        final UUID uuid = UUID.randomUUID();
        final List<PlayerProfile> delivered = new ArrayList<>();
        store.load(player(uuid), delivered::add);
        store.unload(uuid); // вышел до того, как чтение закончилось
        this.runIo();
        assertFalse(store.isLoaded(uuid));
        assertTrue(delivered.isEmpty());
    }

    @Test
    void rejoinBeforeWriteLandsUsesUnwrittenSnapshot() throws Exception {
        final ProfileStore store = this.store();
        final UUID uuid = UUID.randomUUID();
        store.load(player(uuid), p -> { });
        this.runIo();
        store.update(uuid, p -> level(p, 7));
        store.unload(uuid);
        // запись ещё в очереди; игрок уже зашёл снова
        final List<PlayerProfile> delivered = new ArrayList<>();
        store.load(player(uuid), delivered::add);
        final Runnable write = this.io.poll();
        this.runIo(); // чтение нового входа раньше записи
        assertEquals(7, delivered.get(0).level());
        store.update(uuid, p -> level(p, 8));
        write.run(); // запоздавшая запись выхода пишет самый свежий снимок, не старый
        assertEquals(8, this.levelOnDisk(uuid));
    }

    @Test
    void lateWriteNeverOverwritesNewerSnapshot() throws Exception {
        final ProfileStore store = this.store();
        final UUID uuid = UUID.randomUUID();
        store.load(player(uuid), p -> { });
        this.runIo();
        store.update(uuid, p -> level(p, 3));
        store.saveDirty();
        store.update(uuid, p -> level(p, 4));
        store.unload(uuid);
        store.saveAll(); // выключение системы до того, как выполнилась асинхронная запись выхода
        assertEquals(4, this.levelOnDisk(uuid));
        this.runIo();
        assertEquals(4, this.levelOnDisk(uuid));
        assertEquals(4, store.readOffline(uuid).level());
    }
}
