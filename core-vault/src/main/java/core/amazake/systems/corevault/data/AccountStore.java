package core.amazake.systems.corevault.data;

import core.amazake.systems.corevault.money.Money;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Все счета в памяти (ConcurrentHashMap) и файл players.yml. Счета офлайн-игроков тоже в памяти — Vault и
 * команды работают с ними без чтения с диска. Запись — снимком во временный файл и переименованием поверх, вне
 * потоков тика (кроме выключения системы). Суммы в файле — десятичные строки, поэтому смена
 * {@code economy.currency.decimals} не портит балансы.
 */
public final class AccountStore {

    private final Path file;
    private final ConcurrentHashMap<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> names = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final ReentrantLock saveLock = new ReentrantLock();
    private volatile int decimals;

    public AccountStore(final Path file, final int decimals) {
        this.file = file;
        this.decimals = decimals;
    }

    public Path file() {
        return this.file;
    }

    public int decimals() {
        return this.decimals;
    }

    public Account get(final UUID id) {
        return this.accounts.get(id);
    }

    /** Счёт есть или создаётся функцией {@code create} (атомарно). */
    public Account getOrCreate(final UUID id, final Function<UUID, Account> create) {
        final Account[] made = new Account[1];
        final Account a = this.accounts.computeIfAbsent(id, k -> made[0] = create.apply(k));
        if (made[0] != null) {
            this.index(a);
            this.markDirty();
        }
        return a;
    }

    public UUID byName(final String name) {
        return name == null ? null : this.names.get(name.toLowerCase(Locale.ROOT));
    }

    /** Имя игрока сменилось (вход): обновить и индекс. */
    public void rename(final Account a, final String name) {
        if (name == null || name.isEmpty() || name.equals(a.name())) {
            return;
        }
        this.names.remove(a.name().toLowerCase(Locale.ROOT), a.id());
        a.name(name);
        this.index(a);
        this.markDirty();
    }

    private void index(final Account a) {
        if (!a.name().isEmpty()) {
            this.names.put(a.name().toLowerCase(Locale.ROOT), a.id());
        }
    }

    public Collection<Account> all() {
        return this.accounts.values();
    }

    public int size() {
        return this.accounts.size();
    }

    public List<String> names() {
        final List<String> out = new ArrayList<>();
        for (final Account a : this.accounts.values()) {
            if (!a.name().isEmpty()) {
                out.add(a.name());
            }
        }
        return out;
    }

    public void markDirty() {
        this.dirty.set(true);
    }

    public boolean isDirty() {
        return this.dirty.get();
    }

    /** Точность валюты сменилась (reload): пересчитать все балансы в новые минорные единицы. */
    public void rescale(final int newDecimals) {
        this.saveLock.lock();
        try {
            final int old = this.decimals;
            if (old == newDecimals) {
                return;
            }
            for (final Account a : this.accounts.values()) {
                synchronized (a) {
                    final long v = a.balance();
                    a.balance(newDecimals > old ? v * Money.pow10(newDecimals - old) : v / Money.pow10(old - newDecimals));
                }
            }
            this.decimals = newDecimals;
            this.markDirty();
        } finally {
            this.saveLock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------------ файл

    /** Прочитать players.yml (если есть). Вызывается до работы со счетами. */
    public int load(final Consumer<String> warn) throws IOException {
        if (!Files.isRegularFile(this.file)) {
            return 0;
        }
        final YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(Files.readString(this.file, StandardCharsets.UTF_8));
        } catch (final InvalidConfigurationException e) {
            throw new IOException("players.yml повреждён: " + e.getMessage(), e);
        }
        final List<Account> read = read(y, this.decimals, warn);
        this.accounts.clear();
        this.names.clear();
        for (final Account a : read) {
            this.accounts.put(a.id(), a);
            this.index(a);
        }
        return read.size();
    }

    static List<Account> read(final YamlConfiguration y, final int decimals, final Consumer<String> warn) {
        final List<Account> out = new ArrayList<>();
        final ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) {
            return out;
        }
        for (final String key : root.getKeys(false)) {
            final ConfigurationSection s = root.getConfigurationSection(key);
            final UUID id;
            try {
                id = UUID.fromString(key);
            } catch (final IllegalArgumentException e) {
                warn.accept("players.yml: «" + key + "» — не UUID, пропускаю");
                continue;
            }
            if (s == null) {
                continue;
            }
            long balance;
            try {
                balance = Money.fromPlain(String.valueOf(s.get("balance", "0")), decimals);
            } catch (final ArithmeticException | NumberFormatException e) {
                warn.accept("players.yml: " + key + ".balance «" + s.get("balance") + "» — не число, ставлю 0");
                balance = 0;
            }
            final Account a = new Account(id, s.getString("name", ""), balance, s.getInt("level", 0), s.getLong("exp", 0));
            final List<Integer> pending = s.getIntegerList("pending-rewards");
            if (!pending.isEmpty()) {
                a.addPending(pending);
            }
            out.add(a);
        }
        return out;
    }

    static YamlConfiguration write(final Collection<Account.Snapshot> snaps, final int decimals) {
        final YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Счета core-vault. Правится командами; руками — только при выключенной системе."));
        final ConfigurationSection root = y.createSection("players");
        for (final Account.Snapshot a : snaps) {
            final ConfigurationSection s = root.createSection(a.id().toString());
            s.set("name", a.name());
            s.set("balance", Money.plain(a.balance(), decimals));
            s.set("level", a.level());
            s.set("exp", a.exp());
            if (!a.pending().isEmpty()) {
                s.set("pending-rewards", a.pending());
            }
        }
        return y;
    }

    /**
     * Записать, если были изменения (или force). Любой поток; две записи одновременно не идут. Ошибка — флаг
     * изменений возвращается, следующий раз запишется снова.
     */
    public boolean save(final boolean force) throws IOException {
        this.saveLock.lock();
        try {
            if (!this.dirty.getAndSet(false) && !force) {
                return false;
            }
            try {
                final List<Account.Snapshot> snaps = new ArrayList<>(this.accounts.size());
                for (final Account a : this.accounts.values()) {
                    snaps.add(a.snapshot());
                }
                writeAtomic(this.file, write(snaps, this.decimals).saveToString());
                return true;
            } catch (final IOException | RuntimeException e) {
                this.dirty.set(true);
                throw e;
            }
        } finally {
            this.saveLock.unlock();
        }
    }

    public static void writeAtomic(final Path file, final String text) throws IOException {
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
