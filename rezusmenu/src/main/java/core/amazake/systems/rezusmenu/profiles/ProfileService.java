package core.amazake.systems.rezusmenu.profiles;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.rezusmenu.util.Papi;
import core.amazake.systems.rezusmenu.util.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Профили (персонажи) игрока: первое открытие, смена, создание, покупка слота, сверка при входе и меню при входе.
 * Всё про игрока — в его потоке; файлы — в потоке {@link AccountStore}.
 *
 * <p>Смена профиля A → B: снять A (данные игрока + класс + доп. значения) → записать в аккаунт, активным сделать B,
 * поколение +1 → <b>дождаться записи файла</b> → в потоке игрока закрыть меню и надеть B → метка «B, поколение»
 * в PDC игрока и сразу {@code saveData()} (метка и инвентарь ложатся на диск вместе). Если сервер упадёт между
 * записью файла и сохранением игрока, при входе метка покажет A — A и станет активным ({@link Account#reconcile}),
 * у B останется его сохранённое состояние: ничего не теряется и не двоится.</p>
 */
public final class ProfileService implements Listener {

    /** Что делает экран профилей вокруг смены (меню закрывается перед тем, как надеть профиль). */
    public interface Host {
        void close();

        void busy(boolean busy);

        void message(String miniMessage);
    }

    /** Карточка профиля для экрана. */
    public record Card(int id, boolean active, Account.Summary summary, String classKey) {
    }

    private static final NamespacedKey MARKER = new NamespacedKey("rezusmenu", "profile_marker");

    private final SystemContext context;
    private final Supplier<ProfileConfig> cfg;
    private final AccountStore store;
    /** Открыть меню профилей (MenuManager): игрок, обязательный выбор. */
    private final BiConsumer<Player, Boolean> opener;
    /** Настоящее место игрока (в меню он висит в небе — тогда место до меню). */
    private final Function<Player, Location> realLocation;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, JoinWait> joinWaits = new ConcurrentHashMap<>();

    /** Ожидание пакета ресурсов перед меню при входе. */
    private static final class JoinWait {
        final Set<UUID> pending = new HashSet<>();
        boolean sawPack;
        boolean done;
    }

    public ProfileService(final SystemContext context, final Supplier<ProfileConfig> cfg, final AccountStore store,
                          final BiConsumer<Player, Boolean> opener, final Function<Player, Location> realLocation) {
        this.context = context;
        this.cfg = cfg;
        this.store = store;
        this.opener = opener;
        this.realLocation = realLocation;
        ClassBridge.owner(context.plugin());
    }

    public ProfileConfig config() {
        return this.cfg.get();
    }

    private ClassBridge bridge() {
        return new ClassBridge(this.cfg.get(), this.context.logger());
    }

    public boolean isBusy(final Player p) {
        return this.busy.contains(p.getUniqueId());
    }

    // --- аккаунт ---

    /** Аккаунт игрока; при первом открытии — профиль №1 из текущего состояния. Поток игрока. */
    public Account ensure(final Player p) {
        Account a = this.store.cached(p.getUniqueId());
        if (a == null) {
            a = this.store.loadNow(p.getUniqueId());
        }
        if (a.isEmpty()) {
            final Location where = this.realLocation.apply(p);
            final ClassBridge b = this.bridge();
            final int id = a.adopt(b.classKey(p), this.summary(p, where, b), System.currentTimeMillis());
            this.marker(p, id, a.generation());
            this.store.save(a, null);
            this.context.logger().info("profiles: " + p.getName() + " — профиль №" + id + " из текущего персонажа");
        }
        return a;
    }

    /** Карточки: профили по порядку; у активного — живые значения. */
    public List<Card> cards(final Player p) {
        final Account a = this.ensure(p);
        final List<Card> out = new ArrayList<>();
        for (final Account.Profile pr : a.profiles()) {
            final boolean act = pr.id() == a.active();
            if (act) {
                final ClassBridge b = this.bridge();
                final Account.Summary live = this.summary(p, this.realLocation.apply(p), b);
                // мост не знает класса (нет RQ и плейсхолдеров) — класс, с которым профиль создан
                out.add(new Card(pr.id(), true, live, live.classKey().isBlank() ? pr.classKey() : live.classKey()));
            } else {
                out.add(new Card(pr.id(), false, pr.summary(), pr.classKey()));
            }
        }
        return out;
    }

    /** Что показать на карточке сейчас. */
    Account.Summary summary(final Player p, final Location where, final ClassBridge b) {
        final ProfileConfig c = this.cfg.get();
        final String key = b.classKey(p);
        final ProfileConfig.ClassChoice choice = c.classChoice(key);
        final String name = choice != null ? choice.name() : key;
        final List<Account.Stat> stats = new ArrayList<>();
        for (final ProfileConfig.CardStat s : c.stats()) {
            final String n = s.name().contains("%") ? Papi.value(p, s.name()) : s.name();
            final String v = Papi.value(p, s.value());
            stats.add(new Account.Stat(n == null ? s.fallbackName() : n, v == null ? "0" : v));
        }
        return new Account.Summary(b.level(p), key, name, this.placeName(where), List.copyOf(stats));
    }

    /** Название места: зона из config.yml → название мира из config.yml → имя мира. */
    String placeName(final Location l) {
        if (l == null || l.getWorld() == null) {
            return "";
        }
        final ProfileConfig c = this.cfg.get();
        final String w = l.getWorld().getName();
        for (final ProfileConfig.Area a : c.areas()) {
            if (a.contains(w, l.getX(), l.getZ())) {
                return a.name();
            }
        }
        return c.worldNames().getOrDefault(w, w);
    }

    // --- метка в данных игрока ---

    private void marker(final Player p, final int id, final long gen) {
        p.getPersistentDataContainer().set(MARKER, PersistentDataType.STRING, id + ":" + gen);
    }

    static long[] parseMarker(final String raw) {
        if (raw == null) {
            return null;
        }
        final int i = raw.indexOf(':');
        try {
            return i < 0 ? null : new long[] {Integer.parseInt(raw.substring(0, i)), Long.parseLong(raw.substring(i + 1))};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // --- смена профиля ---

    /** Перейти в профиль {@code target}. Поток игрока, из меню. */
    public void switchTo(final Player p, final int target, final Host host) {
        final Account a = this.ensure(p);
        final int from = a.active();
        if (target == from) {
            host.close();
            return;
        }
        if (a.profile(target) == null || !this.busy.add(p.getUniqueId())) {
            return;
        }
        final ProfileConfig c = this.cfg.get();
        final Location where = this.realLocation.apply(p);
        final ConfigurationSection snap;
        final Account.Summary sum;
        try {
            final ClassBridge b = this.bridge();
            snap = Snapshot.capture(p, where, c);
            final ConfigurationSection cls = b.capture(p);
            for (final Map.Entry<String, Object> e : cls.getValues(true).entrySet()) {
                if (!(e.getValue() instanceof ConfigurationSection)) {
                    snap.set("class." + e.getKey(), e.getValue());
                }
            }
            for (final ProfileConfig.Extra x : c.extras()) {
                final String v = Papi.value(p, x.read());
                if (v != null) {
                    snap.set("extra." + x.name(), v);
                }
            }
            sum = this.summary(p, where, b);
        } catch (final RuntimeException e) {
            this.busy.remove(p.getUniqueId());
            this.context.logger().log(Level.SEVERE, "profiles: не снять профиль №" + from + " игрока " + p.getName(), e);
            host.message("<red>Не удалось сохранить текущий профиль — смена отменена.</red>");
            return;
        }
        a.store(from, snap, sum);
        final long gen = a.activate(target);
        host.busy(true);
        this.store.save(a, ok -> this.context.runOnceForPlayer(p, () -> this.finish(p, a, from, target, gen, ok, host)));
    }

    /** Файл записан: надеть новый профиль. Поток игрока. */
    private void finish(final Player p, final Account a, final int from, final int target, final long gen,
                        final boolean written, final Host host) {
        this.busy.remove(p.getUniqueId());
        if (!p.isOnline()) {
            return; // при входе метка покажет старый профиль — он и останется активным
        }
        if (!written) {
            a.activate(from);
            this.marker(p, from, a.generation());
            this.store.save(a, null);
            host.busy(false);
            host.message("<red>Не удалось записать профили на диск — смена отменена.</red>");
            return;
        }
        final ProfileConfig c = this.cfg.get();
        final Account.Profile tp = a.profile(target);
        final ConfigurationSection data = tp.data();
        host.close();
        final ClassBridge b = this.bridge();
        Location dest;
        try {
            if (data == null) {
                b.fresh(p, tp.classKey());
                Snapshot.fresh(p, c);
                this.extras(p, null);
                dest = this.newLocation(p);
            } else {
                b.apply(p, data.getConfigurationSection("class"));
                Snapshot.apply(p, data, c, this.context.logger());
                this.extras(p, data);
                dest = Snapshot.location(data);
                if (dest == null) {
                    dest = this.newLocation(p);
                }
            }
        } catch (final RuntimeException e) {
            this.context.logger().log(Level.SEVERE, "profiles: профиль №" + target + " игрока " + p.getName()
                + " не надет — возвращаю №" + from, e);
            this.rollback(p, a, from);
            p.sendMessage(Text.mm(Text.prefix() + "<red>Профиль не загрузился, вы остались в прежнем. Сообщите администрации.</red>"));
            return;
        }
        this.marker(p, target, gen);
        p.saveData();
        b.flush(p);
        if (data == null) {
            this.dispatch(c.newCommands(), p);
        }
        a.summary(target, this.summary(p, dest, b));
        this.store.save(a, null);
        p.teleportAsync(dest);
        if (data == null && a.profile(target) != null && a.profile(target).classKey() != null
            && !a.profile(target).classKey().isBlank()) {
            this.dispatch(c.classCommands(), p);   // новый профиль с выбранным классом
        }
        this.context.logger().info("profiles: " + p.getName() + " — профиль №" + from + " → №" + target
            + (data == null ? " (новый)" : ""));
        p.sendMessage(Text.mm(Text.prefix() + "<green>Профиль загружен.</green>"));
    }

    /** Надеть обратно профиль {@code from} (его снимок только что сохранён). */
    private void rollback(final Player p, final Account a, final int from) {
        try {
            final ConfigurationSection back = a.profile(from).data();
            if (back != null) {
                this.bridge().apply(p, back.getConfigurationSection("class"));
                Snapshot.apply(p, back, this.cfg.get(), this.context.logger());
                this.extras(p, back);
            }
        } catch (final RuntimeException e2) {
            this.context.logger().log(Level.SEVERE, "profiles: и возврат профиля №" + from + " не удался", e2);
        }
        a.activate(from);
        this.marker(p, from, a.generation());
        p.saveData();
        this.store.save(a, null);
    }

    private void extras(final Player p, final ConfigurationSection data) {
        final List<String> cmds = new ArrayList<>();
        for (final ProfileConfig.Extra x : this.cfg.get().extras()) {
            final String v = data == null ? null : data.getString("extra." + x.name());
            final String cmd = v == null ? x.fresh() : x.restore().replace("{value}", v);
            if (cmd != null && !cmd.isBlank()) {
                cmds.add(cmd);
            }
        }
        this.dispatch(cmds, p);
    }

    private Location newLocation(final Player p) {
        final String raw = this.cfg.get().newLocation();
        if (raw != null && !raw.isBlank()) {
            final String[] s = raw.split(",");
            try {
                final World w = Bukkit.getWorld(s[0].strip());
                if (w != null && s.length >= 4) {
                    return new Location(w, Double.parseDouble(s[1].strip()), Double.parseDouble(s[2].strip()),
                        Double.parseDouble(s[3].strip()), s.length > 4 ? Float.parseFloat(s[4].strip()) : 0f,
                        s.length > 5 ? Float.parseFloat(s[5].strip()) : 0f);
                }
            } catch (final RuntimeException e) {
                this.context.logger().warning("profiles.new-profile.location: «" + raw + "» — нужно мир,x,y,z[,yaw,pitch]");
            }
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    // --- создание, класс, слоты, лобби ---

    /** Создать профиль с классом и сразу перейти в него. */
    public void create(final Player p, final String classKey, final Host host) {
        final ProfileConfig c = this.cfg.get();
        final Account a = this.ensure(p);
        final ProfileConfig.ClassChoice choice = c.classChoice(classKey);
        final Account.Profile np = a.create(classKey, choice == null ? classKey : choice.name(), c.freeSlots(),
            c.maxSlots(), System.currentTimeMillis());
        if (np == null) {
            host.message("<red>Свободных слотов нет.</red>");
            return;
        }
        this.switchTo(p, np.id(), host);
    }

    /** Выбрать класс текущему профилю без класса. */
    public boolean chooseClass(final Player p, final String classKey) {
        final Account a = this.ensure(p);
        try {
            this.bridge().chooseClass(p, classKey);
        } catch (final RuntimeException e) {
            this.context.logger().log(Level.SEVERE, "profiles: класс " + classKey + " игроку " + p.getName() + " не выдан", e);
            return false;
        }
        a.setClassKey(a.active(), classKey);
        a.summary(a.active(), this.summary(p, this.realLocation.apply(p), this.bridge()));
        this.store.save(a, null);
        this.dispatch(this.cfg.get().classCommands(), p);
        return true;
    }

    /** Купить слот. Ответ — null или текст ошибки. */
    public String buySlot(final Player p) {
        final ProfileConfig c = this.cfg.get();
        final Account a = this.ensure(p);
        if (!a.canBuy(c.freeSlots(), c.maxSlots())) {
            return "Все слоты уже открыты.";
        }
        final Wallet.Result r = Wallet.charge(p, c, this.context.plugin());
        if (!r.ok()) {
            return r.error();
        }
        final int n = a.setExtraSlots(a.extraSlots() + 1, c.freeSlots(), c.maxSlots());
        this.store.save(a, null);
        this.context.logger().info("profiles: " + p.getName() + " купил слот профиля (" + c.priceLabel() + "), куплено " + n);
        return null;
    }

    /** «В лобби»: команды из config.yml, без них — точка появления первого мира. Меню уже закрыто. */
    public void lobby(final Player p) {
        final List<String> cmds = this.cfg.get().lobbyCommands();
        if (cmds.isEmpty()) {
            p.teleportAsync(Bukkit.getWorlds().get(0).getSpawnLocation());
        } else {
            this.dispatch(cmds, p);
        }
    }

    /** Админ: слоты игрока (и офлайн). Ответ — в {@code reply} из потока ввода-вывода. */
    public void slots(final UUID id, final String op, final int n, final java.util.function.Consumer<String> reply) {
        final ProfileConfig c = this.cfg.get();
        this.store.update(id, a -> {
            final int now = a.extraSlots();
            final int res = switch (op) {
                case "add" -> a.setExtraSlots(now + n, c.freeSlots(), c.maxSlots());
                case "set" -> a.setExtraSlots(n, c.freeSlots(), c.maxSlots());
                default -> now;
            };
            reply.accept("куплено слотов " + res + " (всего " + a.slots(c.freeSlots(), c.maxSlots()) + " из "
                + c.maxSlots() + ", профилей " + a.profiles().size() + ")");
        }, null);
    }

    private void dispatch(final List<String> cmds, final Player p) {
        if (cmds.isEmpty()) {
            return;
        }
        final String name = p.getName();
        final List<String> all = cmds.stream().map(x -> x.replace("{player}", name)
            .replace("{uuid}", p.getUniqueId().toString())).toList();
        // «player: команда» — от имени игрока (его права), в его потоке; остальные — от консоли
        final List<String> own = all.stream().filter(x -> x.regionMatches(true, 0, "player:", 0, 7))
            .map(x -> x.substring(7).strip()).map(x -> x.startsWith("/") ? x.substring(1) : x).toList();
        final List<String> ready = all.stream().filter(x -> !x.regionMatches(true, 0, "player:", 0, 7)).toList();
        if (!own.isEmpty()) {
            // через ~10 тиков: меню успевает закрыться и вернуть игрока, телепорт профиля — завершиться
            final int[] runs = {0};
            this.context.runForPlayer(p, t -> {
                if (runs[0]++ == 0) {
                    return;
                }
                t.cancel();
                for (final String cmd : own) {
                    // право ровно на эту команду — только на время её выполнения (у игроков может не быть /warp)
                    final org.bukkit.command.Command command = Bukkit.getCommandMap().getCommand(cmd.split(" ", 2)[0]);
                    final String perm = command == null ? null : command.getPermission();
                    final org.bukkit.permissions.PermissionAttachment grant = perm == null || perm.isBlank()
                        ? null : p.addAttachment(this.context.plugin(), perm, true);
                    try {
                        p.performCommand(cmd);
                    } catch (final RuntimeException e) {
                        this.context.logger().warning("profiles: команда игрока «" + cmd + "» упала: " + e);
                    } finally {
                        if (grant != null) {
                            p.removeAttachment(grant);
                        }
                    }
                }
            }, 10L);
        }
        if (ready.isEmpty()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
            for (final String cmd : ready) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (final RuntimeException e) {
                    this.context.logger().warning("profiles: команда «" + cmd + "» упала: " + e);
                }
            }
        });
    }

    // --- вход и выход ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !this.cfg.get().enabled()) {
            return;
        }
        try {
            this.store.load(event.getUniqueId()).get(5, TimeUnit.SECONDS);
        } catch (final Exception e) {
            this.context.logger().warning("profiles: файл профилей " + event.getUniqueId() + " не загрузился заранее: " + e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(final PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        final ProfileConfig c = this.cfg.get();
        if (!c.enabled()) {
            return;
        }
        final Account a = this.store.cached(p.getUniqueId()) != null ? this.store.cached(p.getUniqueId())
            : this.store.loadNow(p.getUniqueId());
        if (!a.isEmpty()) {
            final long[] m = parseMarker(p.getPersistentDataContainer().get(MARKER, PersistentDataType.STRING));
            final int was = a.active();
            if (m != null && a.reconcile((int) m[0], m[1])) {
                this.context.logger().warning("profiles: " + p.getName() + " не успел сохраниться после смены профиля №"
                    + was + " → на нём профиль №" + a.active() + ", он и оставлен активным");
                this.store.save(a, null);
            }
            if (m == null || m[0] != a.active() || m[1] != a.generation()) {
                this.marker(p, a.active(), a.generation());
            }
        }
        final boolean open = switch (c.openOnJoin()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case NEW -> this.bridge().classKey(p).isEmpty();
            case MULTIPLE -> a.profiles().size() > 1 || this.bridge().classKey(p).isEmpty();
        };
        if (open) {
            this.waitForPack(p);
        }
    }

    /** Меню рисуется паком: ждём, пока пак загрузится (или его нет), и ещё join-delay-ticks. */
    private void waitForPack(final Player p) {
        final JoinWait w = new JoinWait();
        this.joinWaits.put(p.getUniqueId(), w);
        // пакет пака не пришёл за 2 с — пака нет; в любом случае не дольше 30 с
        p.getScheduler().runDelayed(this.context.plugin(), t -> {
            if (!w.sawPack) {
                this.openFromJoin(p, w);
            }
        }, null, 40L);
        p.getScheduler().runDelayed(this.context.plugin(), t -> this.openFromJoin(p, w), null, 600L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPack(final PlayerResourcePackStatusEvent event) {
        final JoinWait w = this.joinWaits.get(event.getPlayer().getUniqueId());
        if (w == null) {
            return;
        }
        w.sawPack = true;
        switch (event.getStatus()) {
            case ACCEPTED, DOWNLOADED -> w.pending.add(event.getID());
            default -> w.pending.remove(event.getID());
        }
        if (w.pending.isEmpty()) {
            final Player p = event.getPlayer();
            p.getScheduler().runDelayed(this.context.plugin(), t -> this.openFromJoin(p, w), null,
                Math.max(1, this.cfg.get().joinDelayTicks()));
        }
    }

    private void openFromJoin(final Player p, final JoinWait w) {
        if (w.done || !p.isOnline() || this.joinWaits.get(p.getUniqueId()) != w) {
            return;
        }
        w.done = true;
        this.joinWaits.remove(p.getUniqueId());
        this.opener.accept(p, this.cfg.get().requireChoice());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final Player p = event.getPlayer();
        this.joinWaits.remove(p.getUniqueId());
        final Account a = this.store.cached(p.getUniqueId());
        if (a != null && !a.isEmpty() && !this.busy.contains(p.getUniqueId())) {
            try {
                a.summary(a.active(), this.summary(p, this.realLocation.apply(p), this.bridge()));
            } catch (final RuntimeException e) {
                // подписи карточки — не главное
            }
            this.store.save(a, null);
        }
        this.store.unload(p.getUniqueId());
    }
}
