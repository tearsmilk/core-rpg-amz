package core.amazake.systems.corerpg.profile;

import core.amazake.systems.api.SystemContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Профили игроков: {@code systems/core-rpg/players/<uuid>.yml}. Снимки неизменяемые и лежат в
 * {@link ConcurrentHashMap}: читать можно из любого потока. Файлы читаются и пишутся вне тика.
 */
public final class ProfileStore {

    private final SystemContext context;
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    /** Снимки вышедших игроков, ещё не записанные на диск. */
    private final Map<UUID, PlayerProfile> pendingWrites = new ConcurrentHashMap<>();
    /** Текущая сессия каждого игрока онлайн (токен загрузки). */
    private final Map<UUID, Object> sessions = new ConcurrentHashMap<>();
    private final Object sessionLock = new Object();
    /** Все записи файлов и выбор записываемого снимка — под этим замком. */
    private final Object ioLock = new Object();

    /** Где выполняется чтение и запись файлов (вне тика). */
    private final Executor io;

    public ProfileStore(final SystemContext context) {
        this(context, task -> Bukkit.getAsyncScheduler().runNow(context.plugin(), t -> task.run()));
    }

    /** Для тестов: свой исполнитель ввода-вывода вместо асинхронного планировщика сервера. */
    ProfileStore(final SystemContext context, final Executor io) {
        this.context = context;
        this.io = io;
    }

    /** Снимок игрока; если профиль ещё не загружен — пустой профиль (уровень 1, без класса). */
    public PlayerProfile get(final UUID uuid) {
        final PlayerProfile profile = this.profiles.get(uuid);
        return profile != null ? profile : PlayerProfile.fresh(uuid);
    }

    public Optional<PlayerProfile> loaded(final UUID uuid) {
        return Optional.ofNullable(this.profiles.get(uuid));
    }

    public boolean isLoaded(final UUID uuid) {
        return this.profiles.containsKey(uuid);
    }

    /**
     * Атомарно меняет снимок загруженного игрока и помечает его к записи. Для незагруженного возвращает null и
     * ничего не меняет (данные с диска ещё не пришли — их нельзя затирать).
     */
    public PlayerProfile update(final UUID uuid, final UnaryOperator<PlayerProfile> change) {
        final PlayerProfile result = this.profiles.computeIfPresent(uuid, (id, old) -> {
            final PlayerProfile next = change.apply(old);
            return next == null ? old : next;
        });
        if (result != null) {
            this.dirty.add(uuid);
        }
        return result;
    }

    /**
     * Читает профиль игрока вне тика и отдаёт его в поток игрока. Если профиль уже в памяти (система включена при
     * игроке онлайн и он уже загружен), колбэк вызывается сразу в потоке игрока.
     *
     * <p>Каждая загрузка открывает «сессию» игрока: прочитанный с диска профиль попадает в память, только если эта
     * сессия ещё текущая (игрок не вышел, пока шло чтение). Если после выхода игрока его запись ещё не дошла до
     * диска, берётся снимок из очереди записи — он новее файла.</p>
     */
    public void load(final Player player, final Consumer<PlayerProfile> onLoaded) {
        final UUID uuid = player.getUniqueId();
        final Object session = new Object();
        final PlayerProfile cached;
        synchronized (this.sessionLock) {
            this.sessions.put(uuid, session);
            cached = this.profiles.get(uuid);
        }
        if (cached != null) {
            this.context.runOnceForPlayer(player, () -> onLoaded.accept(this.get(uuid)));
            return;
        }
        this.io.execute(() -> {
            final PlayerProfile unwritten = this.pendingWrites.get(uuid);
            final PlayerProfile read = unwritten != null ? unwritten : this.read(uuid);
            synchronized (this.sessionLock) {
                if (this.sessions.get(uuid) != session) {
                    return; // игрок вышел (или зашёл заново) — эта загрузка устарела
                }
                this.profiles.putIfAbsent(uuid, read);
            }
            this.context.runOnceForPlayer(player, () -> onLoaded.accept(this.get(uuid)));
        });
    }

    /**
     * Выход игрока (поток игрока, PlayerQuitEvent): профиль сразу убирается из памяти, его последний снимок
     * ставится в очередь записи и пишется вне тика.
     */
    public void unload(final UUID uuid) {
        final PlayerProfile profile;
        synchronized (this.sessionLock) {
            this.sessions.remove(uuid);
            profile = this.profiles.get(uuid);
            if (profile == null) {
                return;
            }
            // сначала в очередь записи, потом из памяти: запись в любой момент найдёт последний снимок
            this.pendingWrites.put(uuid, profile);
            this.profiles.remove(uuid, profile);
        }
        this.io.execute(() -> this.flush(uuid));
    }

    /** Записать изменённые профили (из асинхронного таймера). */
    public void saveDirty() {
        for (final UUID uuid : Set.copyOf(this.dirty)) {
            this.flush(uuid);
        }
        for (final UUID uuid : Set.copyOf(this.pendingWrites.keySet())) {
            this.flush(uuid);
        }
    }

    /**
     * Записать на диск последний снимок игрока, если он изменён или ждёт записи после выхода. Записи одного игрока
     * идут строго по очереди, и каждая берёт самый свежий снимок уже под замком — старый снимок не может лечь
     * поверх нового.
     */
    private void flush(final UUID uuid) {
        synchronized (this.ioLock) {
            final boolean wasDirty = this.dirty.remove(uuid);
            final PlayerProfile unwritten = this.pendingWrites.get(uuid);
            if (!wasDirty && unwritten == null) {
                return;
            }
            final PlayerProfile live = this.profiles.get(uuid);
            final PlayerProfile latest = live != null ? live : unwritten;
            if (latest != null && this.write(latest) && unwritten != null) {
                this.pendingWrites.remove(uuid, unwritten);
            }
        }
    }

    /**
     * Записать всё сразу: выключение системы и остановка сервера. Держит замок записи всё время, поэтому дожидается
     * записи, уже начатой таймером или выходом игрока: новый экземпляр системы прочитает файлы только после неё.
     */
    public void saveAll() {
        synchronized (this.ioLock) {
            this.saveDirty();
        }
    }

    /** Профиль игрока, которого нет онлайн: читается с диска (админские команды). Вызывать вне тика. */
    public PlayerProfile readOffline(final UUID uuid) {
        final PlayerProfile online = this.profiles.get(uuid);
        if (online != null) {
            return online;
        }
        final PlayerProfile unwritten = this.pendingWrites.get(uuid);
        return unwritten != null ? unwritten : this.read(uuid);
    }

    /** Записать профиль игрока, которого нет онлайн. Вызывать вне тика. */
    public void writeOffline(final PlayerProfile profile) {
        final boolean queued;
        synchronized (this.sessionLock) {
            if (this.profiles.containsKey(profile.uuid())) {
                this.profiles.put(profile.uuid(), profile);
                this.dirty.add(profile.uuid());
                return;
            }
            queued = this.pendingWrites.containsKey(profile.uuid());
            if (queued) {
                this.pendingWrites.put(profile.uuid(), profile);
            }
        }
        if (queued) {
            this.flush(profile.uuid());
            return;
        }
        synchronized (this.ioLock) {
            this.write(profile);
        }
    }

    private Path file(final UUID uuid) {
        return this.context.dataFolder().resolve("players").resolve(uuid + ".yml");
    }

    private PlayerProfile read(final UUID uuid) {
        final Path file = this.file(uuid);
        if (!Files.isRegularFile(file)) {
            return PlayerProfile.fresh(uuid);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            synchronized (this.ioLock) {
                yaml.loadFromString(Files.readString(file));
            }
        } catch (final IOException | InvalidConfigurationException e) {
            this.context.logger().log(Level.WARNING, "Профиль " + uuid + " повреждён, начинаю с пустого: " + e.getMessage());
            return PlayerProfile.fresh(uuid);
        }
        return new PlayerProfile(uuid,
            blankToNull(yaml.getString("class")),
            blankToNull(yaml.getString("profession")),
            Math.max(1, yaml.getInt("level", 1)),
            Math.max(0L, yaml.getLong("exp", 0L)),
            Math.max(0, yaml.getInt("points.free", 0)),
            Math.max(0, yaml.getInt("points.primary", 0)),
            Math.max(0, yaml.getInt("points.agility", 0)),
            Math.max(0, yaml.getInt("points.stamina", 0)),
            Math.max(0, yaml.getInt("points.luck", 0)));
    }

    /** Запись файла; вызывается под {@link #ioLock}. false — не удалось (профиль снова помечен изменённым). */
    private boolean write(final PlayerProfile profile) {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("class", profile.classId());
        yaml.set("profession", profile.professionId());
        yaml.set("level", profile.level());
        yaml.set("exp", profile.exp());
        yaml.set("points.free", profile.freePoints());
        yaml.set("points.primary", profile.primary());
        yaml.set("points.agility", profile.agility());
        yaml.set("points.stamina", profile.stamina());
        yaml.set("points.luck", profile.luck());
        final Path file = this.file(profile.uuid());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, yaml.saveToString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (final IOException e) {
            if (this.profiles.containsKey(profile.uuid())) {
                this.dirty.add(profile.uuid());
            }
            this.context.logger().log(Level.WARNING, "Не удалось сохранить профиль " + profile.uuid(), e);
            return false;
        }
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
