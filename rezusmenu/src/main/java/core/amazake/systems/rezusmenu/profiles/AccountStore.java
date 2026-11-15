package core.amazake.systems.rezusmenu.profiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Файлы профилей {@code profiles/<uuid>.yml}. Весь ввод-вывод — в одном своём потоке (по очереди, поэтому запись не
 * обгонит чтение и старая запись не перетрёт новую), запись атомарная: временный файл и переименование. YAML для
 * записи строится в том же потоке из аккаунта (его методы синхронизированы). Кэш — аккаунты игроков в сети и тех,
 * кого трогали командой.
 */
public final class AccountStore {

    private final Path dir;
    private final Logger logger;
    private final Map<UUID, Account> cache = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "rezusmenu-profiles-io");
        t.setDaemon(true);
        return t;
    });

    public AccountStore(final Path dir, final Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    /** Аккаунт из кэша; null — ещё не загружен. */
    public Account cached(final UUID id) {
        return this.cache.get(id);
    }

    /** Загрузить (или взять из кэша) — в потоке ввода-вывода; ответ в {@code done} в том же потоке. */
    public CompletableFuture<Account> load(final UUID id) {
        // всегда через очередь: иначе отложенный unload() после выхода мог бы выкинуть только что взятый аккаунт
        return CompletableFuture.supplyAsync(() -> this.cache.computeIfAbsent(id, this::read), this.io);
    }

    /** Прочитать сразу (вызывающий поток). Для редких случаев, когда файл не успел загрузиться заранее. */
    public Account loadNow(final UUID id) {
        return this.cache.computeIfAbsent(id, this::read);
    }

    private Account read(final UUID id) {
        final Path file = this.file(id);
        if (!Files.isRegularFile(file)) {
            return new Account(id);
        }
        final YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return Account.fromYaml(id, y);
        } catch (final IOException | InvalidConfigurationException e) {
            // Битый файл не затираем пустым аккаунтом: откладываем копию и начинаем с чистого листа.
            this.logger.log(Level.SEVERE, "profiles/" + id + ".yml не читается — копия в .broken, профили игрока пусты", e);
            try {
                Files.copy(file, file.resolveSibling(id + ".yml.broken-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException ignored) {
                // копия не удалась — файл всё равно останется до первой записи
            }
            return new Account(id);
        }
    }

    /** Записать на диск в фоне; {@code done} (может быть null) — после записи, в потоке ввода-вывода, true — успешно. */
    public void save(final Account account, final Consumer<Boolean> done) {
        try {
            this.io.execute(() -> {
                final boolean ok = this.write(account);
                if (done != null) {
                    done.accept(ok);
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            // система выключается: пишем сразу
            final boolean ok = this.write(account);
            if (done != null) {
                done.accept(ok);
            }
        }
    }

    /** Сделать что-то с аккаунтом (и офлайн-игрока) в потоке ввода-вывода и записать. */
    public void update(final UUID id, final Consumer<Account> change, final Runnable after) {
        this.io.execute(() -> {
            final Account a = this.cache.computeIfAbsent(id, this::read);
            change.accept(a);
            this.write(a);
            if (after != null) {
                after.run();
            }
        });
    }

    private boolean write(final Account account) {
        final Path file = this.file(account.owner());
        try {
            final String text = account.toYaml().saveToString();
            Files.createDirectories(this.dir);
            final Path tmp = file.resolveSibling(account.owner() + ".yml.tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (final IOException | RuntimeException e) {
            this.logger.log(Level.WARNING, "Не удалось сохранить profiles/" + account.owner() + ".yml", e);
            return false;
        }
    }

    /** Игрок вышел: забыть аккаунт после уже поставленных записей. */
    public void unload(final UUID id) {
        try {
            this.io.execute(() -> this.cache.remove(id));
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            this.cache.remove(id);
        }
    }

    private Path file(final UUID id) {
        return this.dir.resolve(id + ".yml");
    }

    /** Выключение: дописать очередь (до 10 с). */
    public void shutdown() {
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("profiles: запись на диск не закончилась за 10 с");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
