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
    private final Object ioLock = new Object();

    public ProfileStore(final SystemContext context) {
        this.context = context;
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
     */
    public void load(final Player player, final Consumer<PlayerProfile> onLoaded) {
        final UUID uuid = player.getUniqueId();
        final PlayerProfile cached = this.profiles.get(uuid);
        if (cached != null) {
            this.context.runOnceForPlayer(player, () -> onLoaded.accept(this.get(uuid)));
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), task -> {
            final PlayerProfile read = this.read(uuid);
            this.profiles.putIfAbsent(uuid, read);
            this.context.runOnceForPlayer(player, () -> onLoaded.accept(this.get(uuid)));
        });
    }

    /** Выход игрока: записать вне тика и убрать из памяти. */
    public void unload(final UUID uuid) {
        final PlayerProfile profile = this.profiles.get(uuid);
        if (profile == null) {
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), task -> {
            if (this.dirty.remove(uuid)) {
                this.write(profile);
            }
            // Игрок мог перезайти, пока шла запись: тогда профиль остаётся в памяти.
            if (Bukkit.getPlayer(uuid) == null) {
                this.profiles.remove(uuid, profile);
            }
        });
    }

    /** Записать изменённые профили (из асинхронного таймера). */
    public void saveDirty() {
        for (final UUID uuid : Set.copyOf(this.dirty)) {
            final PlayerProfile profile = this.profiles.get(uuid);
            if (profile != null && this.dirty.remove(uuid)) {
                this.write(profile);
            }
        }
    }

    /** Записать всё сразу: выключение системы и остановка сервера. */
    public void saveAll() {
        this.saveDirty();
    }

    /** Профиль игрока, которого нет онлайн: читается с диска (админские команды). Вызывать вне тика. */
    public PlayerProfile readOffline(final UUID uuid) {
        final PlayerProfile online = this.profiles.get(uuid);
        return online != null ? online : this.read(uuid);
    }

    /** Записать профиль игрока, которого нет онлайн. Вызывать вне тика. */
    public void writeOffline(final PlayerProfile profile) {
        if (this.profiles.containsKey(profile.uuid())) {
            this.profiles.put(profile.uuid(), profile);
            this.dirty.add(profile.uuid());
        } else {
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

    private void write(final PlayerProfile profile) {
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
            synchronized (this.ioLock) {
                Files.createDirectories(file.getParent());
                Files.writeString(tmp, yaml.saveToString());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (final IOException e) {
            this.dirty.add(profile.uuid());
            this.context.logger().log(Level.WARNING, "Не удалось сохранить профиль " + profile.uuid(), e);
        }
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
