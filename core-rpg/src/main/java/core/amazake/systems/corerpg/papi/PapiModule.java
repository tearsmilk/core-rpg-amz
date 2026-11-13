package core.amazake.systems.corerpg.papi;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.GeneralSettings;
import core.amazake.systems.corerpg.aspect.AspectContributor;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.progression.ProgressionModule;
import core.amazake.systems.corerpg.seals.SealsModule;
import core.amazake.systems.corerpg.sharpen.SharpenModule;
import core.amazake.systems.corerpg.util.Configs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Модуль E: плейсхолдеры PlaceholderAPI ({@code %core_level%}, {@code %core_class%}, {@code %core_aspect_evasion%}…).
 *
 * <p>Расширение — мост {@code papibridge/CoreRpgExpansion}, загружаемый {@link BridgeLoader} поверх загрузчика
 * PlaceholderAPI и регистрируемый рефлексией ({@link PapiHook}). Значения считает {@link Placeholders} по неизменяемым
 * снимкам: профилю, аспектам и снимку предмета в основной руке. Снимок руки делает вкладчик аспектов order 300 в
 * потоке игрока (пересчёт аспектов уже вызывается при смене слота и экипировки), поэтому плейсхолдеры можно
 * запрашивать из любого потока.</p>
 *
 * <p>Файл настроек — {@code papi.yml}; включение и идентификатор — {@code config.yml} ({@code placeholderapi}).
 * При {@code legacy-names} дополнительно регистрируется расширение со старым префиксом RoyalQuestClasses
 * {@code royalquest} (если его не держит другой плагин), чтобы готовые конфиги табов и скорбордов с
 * {@code %royalquest_level%}, {@code %royalquest_stats_available%} и т. п. продолжили работать.</p>
 */
public final class PapiModule implements CoreModule {

    private static final String PLACEHOLDER_API = "PlaceholderAPI";
    private static final String FILE = "papi.yml";
    /** Префикс RoyalQuestClasses: при legacy-names старые %royalquest_…% продолжают работать. */
    static final String LEGACY_IDENTIFIER = "royalquest";

    private final CoreRpgSystem system;
    private final AtomicReference<PapiSettings> settings = new AtomicReference<>(PapiSettings.DEFAULTS);
    private final Map<UUID, HandSnapshot> hands = new ConcurrentHashMap<>();
    private final AtomicBoolean resolverFailed = new AtomicBoolean();
    private volatile SystemContext context;
    private volatile PapiHook hook;
    /** Второе расширение под старым префиксом {@code royalquest} (при legacy-names). */
    private volatile PapiHook legacyHook;
    private volatile Placeholders.Source source;
    /** Пока false, вкладчик ничего не снимает и resolver молчит (модуль выключен). */
    private volatile boolean active;

    public PapiModule(final CoreRpgSystem system) {
        this.system = system;
    }

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.hook = new PapiHook(context.logger(), "Смените placeholderapi.identifier в config.yml.");
        this.legacyHook = new PapiHook(context.logger(), "Старые %" + LEGACY_IDENTIFIER
            + "_…% обслуживает другой плагин (RoyalQuestClasses ещё установлен?). Удалите его или выключите legacy-names в papi.yml.");
        this.source = new ProgressionSource(this.system.progression());
        this.loadSettings(context);
        this.active = true;
        this.system.aspects().register(new HandContributor());
        context.registerListener(new Events());
        // Снимки руки игроков, которые уже онлайн, появятся при их пересчёте аспектов: система вызывает его сразу
        // после включения модулей (загрузка профилей онлайн-игроков) и после reload.
        this.syncRegistration();
    }

    @Override
    public void reload(final SystemContext context) {
        this.loadSettings(context);
        this.syncRegistration();
    }

    @Override
    public void disable(final SystemContext context) {
        this.active = false;
        this.unregisterAll();
        this.hands.clear();
    }

    // --- публичное (дополнительно к контракту) ---

    /** Значение плейсхолдера без префикса ({@code level}, {@code aspect_evasion}) для игрока; null — неизвестный. Любой поток. */
    public String resolve(final OfflinePlayer player, final String params) {
        final Placeholders.Source src = this.source;
        if (!this.active || params == null || src == null) {
            return null;
        }
        try {
            return Placeholders.resolve(params, this.view(player), src, this.settings.get());
        } catch (final RuntimeException e) {
            if (this.resolverFailed.compareAndSet(false, true)) {
                final SystemContext ctx = this.context;
                if (ctx != null) {
                    ctx.logger().log(Level.WARNING, "Ошибка в плейсхолдере «" + params + "» (дальше молчу)", e);
                }
            }
            return "";
        }
    }

    /** Зарегистрированы ли плейсхолдеры в PlaceholderAPI сейчас. */
    public boolean isRegistered() {
        final PapiHook current = this.hook;
        return current != null && current.isRegistered();
    }

    // --- внутреннее ---

    private Placeholders.View view(final OfflinePlayer player) {
        if (player == null) {
            return new Placeholders.View(null, null, null);
        }
        final UUID uuid = player.getUniqueId();
        final PlayerProfile profile = this.system.profiles().loaded(uuid).orElse(null);
        if (profile == null) {
            return new Placeholders.View(null, null, null);
        }
        return new Placeholders.View(profile, this.system.aspects().get(uuid), this.hands.get(uuid));
    }

    /** Привести регистрацию к настройкам: включено/выключено, смена идентификатора, наличие PlaceholderAPI. */
    private void syncRegistration() {
        final PapiHook current = this.hook;
        final SystemContext ctx = this.context;
        if (current == null || ctx == null || !this.active) {
            return;
        }
        final GeneralSettings general = this.system.settings();
        final Plugin papi = Bukkit.getPluginManager().getPlugin(PLACEHOLDER_API);
        final PapiHook legacy = this.legacyHook;
        if (!general.papiEnabled() || papi == null || !papi.isEnabled()) {
            final boolean was = current.isRegistered() || (legacy != null && legacy.isRegistered());
            this.unregisterAll();
            if (was) {
                ctx.logger().info("Плейсхолдеры PlaceholderAPI сняты.");
            }
            return;
        }
        final String identifier = general.papiIdentifier();
        final boolean wantLegacy = legacy != null && wantsLegacy(this.settings.get(), identifier);
        if (legacy != null && !wantLegacy && legacy.hasExpansion()) {
            // снять до основного: основной идентификатор мог смениться как раз на royalquest
            legacy.unregister();
        }
        this.ensure(current, papi, identifier, ctx);
        if (wantLegacy) {
            this.ensure(legacy, papi, LEGACY_IDENTIFIER, ctx);
        }
    }

    /** Нужен ли второй префикс royalquest: legacy-names включено и основной префикс не royalquest. */
    static boolean wantsLegacy(final PapiSettings settings, final String identifier) {
        return settings.legacyNames() && !LEGACY_IDENTIFIER.equalsIgnoreCase(identifier);
    }

    /** Зарегистрировать хук под идентификатором, если он не стоит там (или его вытеснило чужое расширение). */
    private void ensure(final PapiHook target, final Plugin papi, final String identifier, final SystemContext ctx) {
        if (target.isRegistered() && identifier.equals(target.identifier())) {
            return;
        }
        target.register(papi, identifier, author(ctx), ctx.info().version(), this::resolve);
    }

    /** Снять оба расширения (основное и royalquest). */
    private void unregisterAll() {
        final PapiHook current = this.hook;
        if (current != null) {
            current.unregister();
        }
        final PapiHook legacy = this.legacyHook;
        if (legacy != null) {
            legacy.unregister();
        }
    }

    private static String author(final SystemContext ctx) {
        final List<String> authors = ctx.info().authors();
        return authors == null || authors.isEmpty() ? "CoreRpg" : String.join(", ", authors);
    }

    private void loadSettings(final SystemContext context) {
        writeDefaultFile(context);
        this.settings.set(PapiSettings.from(Configs.load(context, FILE), w -> context.logger().warning(w)));
    }

    /** papi.yml с комментариями: из .azs, если он там есть, иначе из текста по умолчанию. Существующий не трогаем. */
    private static void writeDefaultFile(final SystemContext context) {
        final Path file = context.dataFolder().resolve(FILE);
        if (Files.exists(file)) {
            return;
        }
        try {
            context.saveDefaultResource(FILE);
        } catch (final RuntimeException ignored) {
            // ресурса нет в .azs — запишем текст по умолчанию ниже
        }
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PapiSettings.DEFAULT_YAML);
        } catch (final IOException e) {
            context.logger().log(Level.WARNING, "Не удалось записать " + FILE + ", беру настройки по умолчанию", e);
        }
    }

    /** Снимок предмета в основной руке. Поток игрока. */
    private HandSnapshot snapshotHand(final Player player) {
        final ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            return HandSnapshot.EMPTY;
        }
        final SharpenModule sharpen = this.system.sharpen();
        final SealsModule seals = this.system.seals();
        return new HandSnapshot(sharpen.level(item), sharpen.maxLevel(item), sharpen.bonusPercent(item),
            seals.sealName(item), seals.sealColorName(item));
    }

    /** Вкладчик order 300: аспектов не добавляет, только запоминает предмет в руке для плейсхолдеров. */
    private final class HandContributor implements AspectContributor {
        @Override
        public void contribute(final Player player, final PlayerProfile profile, final AspectSnapshot.Builder out) {
            if (!PapiModule.this.active || !player.isOnline()) {
                return;
            }
            final HandSnapshot snapshot = PapiModule.this.snapshotHand(player);
            if (snapshot.equals(HandSnapshot.EMPTY)) {
                PapiModule.this.hands.remove(player.getUniqueId());
            } else {
                PapiModule.this.hands.put(player.getUniqueId(), snapshot);
            }
        }

        @Override
        public int order() {
            return 300;
        }
    }

    /** Слушатель: выход игрока и включение/выключение PlaceholderAPI. */
    public final class Events implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(final PlayerQuitEvent event) {
            PapiModule.this.hands.remove(event.getPlayer().getUniqueId());
        }

        /** PlaceholderAPI включился после системы (или перезапущен) — зарегистрироваться заново. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(final PluginEnableEvent event) {
            if (PLACEHOLDER_API.equals(event.getPlugin().getName())) {
                // расширения прошлого экземпляра PlaceholderAPI (если остались) уже недействительны
                PapiModule.this.unregisterAll();
                PapiModule.this.syncRegistration();
            }
        }

        /** PlaceholderAPI выключается — снять расширение и отпустить загрузчик моста. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginDisable(final PluginDisableEvent event) {
            if (PLACEHOLDER_API.equals(event.getPlugin().getName())) {
                PapiModule.this.unregisterAll();
            }
        }
    }

    /** Источник сведений о классах и уровнях — фасад progression (его методы потокобезопасны). */
    private record ProgressionSource(ProgressionModule progression) implements Placeholders.Source {
        @Override
        public int maxLevel() {
            return this.progression.maxLevel();
        }

        @Override
        public int professionLevel() {
            return this.progression.professionLevel();
        }

        @Override
        public long expToNext(final PlayerProfile profile) {
            return this.progression.expToNext(profile);
        }

        @Override
        public String className(final String classId) {
            return this.progression.className(classId);
        }

        @Override
        public String professionName(final String professionId) {
            return this.progression.professionName(professionId);
        }

        @Override
        public String primaryStatName(final String classId) {
            return this.progression.primaryStatName(classId);
        }
    }
}
