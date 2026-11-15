package core.amazake.systems.rezusmenu.profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Профили одного игрока (файл {@code profiles/<uuid>.yml}). Активный профиль — тот, что сейчас «надет» на игрока:
 * его живое состояние главное, а {@link Profile#data} у него — копия с момента входа в профиль (запас на случай
 * падения сервера). У неактивных {@code data} — всё, что нужно, чтобы снова надеть профиль.
 *
 * <p>Поколение {@link #generation} растёт при каждой смене профиля и пишется ещё и в данные игрока (PDC, вместе с
 * инвентарём): по нему при входе видно, успел ли игрок сохраниться после смены (см. {@link #reconcile}).</p>
 *
 * <p>Методы синхронизированы: аккаунт меняет поток игрока, а читает при записи на диск поток ввода-вывода.
 * Чистая логика: без сервера (YAML — классы Bukkit API).</p>
 */
public final class Account {

    /** Строка характеристики на карточке. */
    public record Stat(String name, String value) {
    }

    /** Что показать на карточке профиля (снимается при уходе из профиля). */
    public record Summary(int level, String classKey, String className, String location, List<Stat> stats) {

        public static final Summary EMPTY = new Summary(1, "", "", "", List.of());
    }

    /** Один профиль (персонаж). */
    public static final class Profile {
        private final int id;
        private final long created;
        private String classKey;
        private Summary summary;
        private ConfigurationSection data;

        Profile(final int id, final long created, final String classKey, final Summary summary,
                final ConfigurationSection data) {
            this.id = id;
            this.created = created;
            this.classKey = classKey == null ? "" : classKey;
            this.summary = summary == null ? Summary.EMPTY : summary;
            this.data = data;
        }

        public int id() {
            return this.id;
        }

        public long created() {
            return this.created;
        }

        public String classKey() {
            return this.classKey;
        }

        public Summary summary() {
            return this.summary;
        }

        /** Сохранённое состояние; null — профиль ещё ни разу не надевали (новый, «с нуля»). */
        public ConfigurationSection data() {
            return this.data;
        }
    }

    private final UUID owner;
    private int extraSlots;
    private int active;
    private long generation;
    private int nextId = 1;
    private final TreeMap<Integer, Profile> profiles = new TreeMap<>();

    public Account(final UUID owner) {
        this.owner = owner;
    }

    public UUID owner() {
        return this.owner;
    }

    public synchronized int extraSlots() {
        return this.extraSlots;
    }

    public synchronized int active() {
        return this.active;
    }

    public synchronized long generation() {
        return this.generation;
    }

    public synchronized boolean isEmpty() {
        return this.profiles.isEmpty();
    }

    public synchronized List<Profile> profiles() {
        return List.copyOf(this.profiles.values());
    }

    public synchronized Profile profile(final int id) {
        return this.profiles.get(id);
    }

    public synchronized Profile activeProfile() {
        return this.profiles.get(this.active);
    }

    /** Сколько профилей можно иметь: бесплатные + купленные, не больше {@code max}. */
    public synchronized int slots(final int free, final int max) {
        return slots(free, this.extraSlots, max);
    }

    static int slots(final int free, final int extra, final int max) {
        return Math.max(0, Math.min(max, free + Math.max(0, extra)));
    }

    /** Можно ли создать ещё профиль. */
    public synchronized boolean canCreate(final int free, final int max) {
        return this.profiles.size() < this.slots(free, max);
    }

    /** Можно ли купить ещё слот. */
    public synchronized boolean canBuy(final int free, final int max) {
        return this.slots(free, max) < max;
    }

    /** Задать купленные слоты; ответ — сколько стало (не меньше 0, не больше {@code max - free}). */
    public synchronized int setExtraSlots(final int n, final int free, final int max) {
        this.extraSlots = Math.max(0, Math.min(Math.max(0, max - free), n));
        return this.extraSlots;
    }

    /** Первый профиль из текущего состояния игрока (первое открытие). Ответ — его id. */
    public synchronized int adopt(final String classKey, final Summary summary, final long now) {
        if (!this.profiles.isEmpty()) {
            return this.active;
        }
        final int id = this.nextId++;
        this.profiles.put(id, new Profile(id, now, classKey, summary, null));
        this.active = id;
        this.generation = Math.max(1, this.generation + 1);
        return id;
    }

    /** Новый пустой профиль с классом; null — слотов нет. */
    public synchronized Profile create(final String classKey, final String className, final int free, final int max,
                                       final long now) {
        if (!this.canCreate(free, max)) {
            return null;
        }
        final int id = this.nextId++;
        final Profile p = new Profile(id, now, classKey, new Summary(1, classKey, className, "", List.of()), null);
        this.profiles.put(id, p);
        return p;
    }

    /** Удалить профиль (только неактивный). */
    public synchronized boolean delete(final int id) {
        if (id == this.active || !this.profiles.containsKey(id)) {
            return false;
        }
        this.profiles.remove(id);
        return true;
    }

    /** Запомнить состояние профиля (уход из него или копия при входе). */
    public synchronized void store(final int id, final ConfigurationSection data, final Summary summary) {
        final Profile p = this.profiles.get(id);
        if (p != null) {
            p.data = data;
            if (summary != null) {
                p.summary = summary;
                if (summary.classKey() != null && !summary.classKey().isBlank()) {
                    p.classKey = summary.classKey();
                }
            }
        }
    }

    /** Новые подписи карточки (живые значения активного профиля). */
    public synchronized void summary(final int id, final Summary summary) {
        this.store(id, this.profiles.containsKey(id) ? this.profiles.get(id).data : null, summary);
    }

    public synchronized void setClassKey(final int id, final String key) {
        final Profile p = this.profiles.get(id);
        if (p != null) {
            p.classKey = key == null ? "" : key;
        }
    }

    /** Сделать профиль активным; ответ — новое поколение (или −1, если профиля нет). */
    public synchronized long activate(final int id) {
        if (!this.profiles.containsKey(id)) {
            return -1;
        }
        this.active = id;
        this.generation++;
        return this.generation;
    }

    /**
     * Сверка при входе. {@code markerId}/{@code markerGen} — что записано в данных игрока (0 — ничего). Если игрок
     * не успел сохраниться после смены профиля (вылет, падение сервера), на нём всё ещё старый профиль: он и
     * становится активным, ничего не теряется (у нового профиля остаётся его сохранённое состояние). Ответ — true,
     * если аккаунт пришлось поправить.
     */
    public synchronized boolean reconcile(final int markerId, final long markerGen) {
        if (markerId <= 0 || markerId == this.active || !this.profiles.containsKey(markerId)) {
            return false;
        }
        this.active = markerId;
        this.generation = Math.max(this.generation, markerGen) + 1;
        return true;
    }

    // --- YAML ---

    public synchronized YamlConfiguration toYaml() {
        final YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("rezusmenu: профили игрока " + this.owner + ". Пишет система, руками не править."));
        y.set("extra-slots", this.extraSlots);
        y.set("active", this.active);
        y.set("generation", this.generation);
        y.set("next-id", this.nextId);
        for (final Profile p : this.profiles.values()) {
            final String k = "profiles." + p.id + ".";
            y.set(k + "created", p.created);
            y.set(k + "class", p.classKey);
            y.set(k + "summary.level", p.summary.level());
            y.set(k + "summary.class-key", p.summary.classKey());
            y.set(k + "summary.class-name", p.summary.className());
            y.set(k + "summary.location", p.summary.location());
            final List<Map<String, String>> stats = new ArrayList<>();
            for (final Stat s : p.summary.stats()) {
                stats.add(Map.of("name", s.name(), "value", s.value()));
            }
            y.set(k + "summary.stats", stats);
            if (p.data != null) {
                for (final Map.Entry<String, Object> e : p.data.getValues(true).entrySet()) {
                    if (!(e.getValue() instanceof ConfigurationSection)) {
                        y.set(k + "data." + e.getKey(), e.getValue());
                    }
                }
            }
        }
        return y;
    }

    public static Account fromYaml(final UUID owner, final ConfigurationSection y) {
        final Account a = new Account(owner);
        a.extraSlots = Math.max(0, y.getInt("extra-slots", 0));
        a.active = y.getInt("active", 0);
        a.generation = y.getLong("generation", 0);
        int maxId = 0;
        final ConfigurationSection ps = y.getConfigurationSection("profiles");
        if (ps != null) {
            for (final String key : ps.getKeys(false)) {
                final int id;
                try {
                    id = Integer.parseInt(key);
                } catch (final NumberFormatException e) {
                    continue;
                }
                final ConfigurationSection s = ps.getConfigurationSection(key);
                if (s == null || id <= 0) {
                    continue;
                }
                final List<Stat> stats = new ArrayList<>();
                for (final Map<?, ?> m : s.getMapList("summary.stats")) {
                    stats.add(new Stat(String.valueOf(m.get("name")), String.valueOf(m.get("value"))));
                }
                final Summary sum = new Summary(s.getInt("summary.level", 1), s.getString("summary.class-key", ""),
                    s.getString("summary.class-name", ""), s.getString("summary.location", ""), List.copyOf(stats));
                a.profiles.put(id, new Profile(id, s.getLong("created", 0), s.getString("class", ""), sum,
                    s.getConfigurationSection("data")));
                maxId = Math.max(maxId, id);
            }
        }
        a.nextId = Math.max(maxId + 1, y.getInt("next-id", 1));
        if (!a.profiles.containsKey(a.active)) {
            a.active = a.profiles.isEmpty() ? 0 : a.profiles.firstKey();
        }
        return a;
    }

    /** Глубокая копия раздела (чтобы запись на диск не видела дальнейших правок). */
    static ConfigurationSection copy(final ConfigurationSection src) {
        final MemoryConfiguration out = new MemoryConfiguration();
        for (final Map.Entry<String, Object> e : src.getValues(true).entrySet()) {
            if (!(e.getValue() instanceof ConfigurationSection)) {
                out.set(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
