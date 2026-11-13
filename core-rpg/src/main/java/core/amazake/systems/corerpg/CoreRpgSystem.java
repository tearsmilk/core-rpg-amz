package core.amazake.systems.corerpg;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.aspect.AspectService;
import core.amazake.systems.corerpg.combat.CombatModule;
import core.amazake.systems.corerpg.papi.PapiModule;
import core.amazake.systems.corerpg.profile.ProfileStore;
import core.amazake.systems.corerpg.progression.ProgressionModule;
import core.amazake.systems.corerpg.seals.SealsModule;
import core.amazake.systems.corerpg.sharpen.SharpenModule;
import core.amazake.systems.corerpg.util.Configs;
import core.amazake.systems.corerpg.util.Keys;
import core.amazake.systems.corerpg.util.Text;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * CoreRpg — RPG-ядро сервера одной системой Amazake: классы и профессии с уровнями 1–71 и параметрами, аспекты,
 * заточка +0…+11, магические печати, боевой расчёт и плейсхолдеры PlaceholderAPI.
 *
 * <p>Модули включаются по порядку и выключаются в обратном. Общие сервисы — хранилище профилей и аспекты — живут
 * здесь; модули обращаются друг к другу только через свои фасады (см. CONTRACT.md).</p>
 */
public final class CoreRpgSystem implements AmazakeSystem, Listener {

    private final AtomicReference<GeneralSettings> settings = new AtomicReference<>(GeneralSettings.DEFAULTS);
    private volatile SystemContext context;
    private volatile Keys keys;
    private volatile ProfileStore profiles;
    private volatile AspectService aspects;
    private volatile ProgressionModule progression;
    private volatile SharpenModule sharpen;
    private volatile SealsModule seals;
    private volatile CombatModule combat;
    private volatile PapiModule papi;
    private volatile List<CoreModule> modules = List.of();

    @Override
    public void enable(final SystemContext context) throws Exception {
        this.context = context;
        this.keys = new Keys(context.plugin());
        this.loadGeneral(context);
        this.profiles = new ProfileStore(context);
        this.aspects = new AspectService(this);
        this.progression = new ProgressionModule(this);
        this.sharpen = new SharpenModule(this);
        this.seals = new SealsModule(this);
        this.combat = new CombatModule(this);
        this.papi = new PapiModule(this);
        this.modules = List.of(this.progression, this.sharpen, this.seals, this.combat, this.papi);

        context.registerListener(this.aspects);
        context.registerListener(this);
        for (final CoreModule module : this.modules) {
            module.enable(context);
        }
        context.runAsyncTimer(task -> this.profiles.saveDirty(),
            Duration.ofMinutes(this.settings.get().saveIntervalMinutes()));

        // Систему могли включить при игроках онлайн.
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.loadPlayer(player);
        }
        context.logger().info("CoreRpg включён: " + this.modules.size() + " модулей, игроков онлайн: "
            + Bukkit.getOnlinePlayers().size() + ".");
    }

    @Override
    public void disable(final SystemContext context) {
        final List<CoreModule> list = this.modules;
        for (int i = list.size() - 1; i >= 0; i--) {
            try {
                list.get(i).disable(context);
            } catch (final RuntimeException e) {
                context.logger().log(Level.WARNING, "Модуль " + list.get(i).getClass().getSimpleName() + " выключился с ошибкой", e);
            }
        }
        final AspectService service = this.aspects;
        if (service != null) {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                context.runOnceForPlayer(player, () -> service.clear(player));
            }
        }
        if (this.profiles != null) {
            this.profiles.saveAll();
        }
        this.modules = List.of();
    }

    @Override
    public void reload(final SystemContext context) throws Exception {
        this.loadGeneral(context);
        for (final CoreModule module : this.modules) {
            module.reload(context);
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.aspects.refreshLater(player);
        }
        context.logger().info("CoreRpg: настройки перечитаны.");
    }

    private void loadGeneral(final SystemContext context) {
        final GeneralSettings parsed = GeneralSettings.from(Configs.load(context, "config.yml"), w -> context.logger().warning(w));
        this.settings.set(parsed);
        Text.setPrefix(parsed.prefix());
    }

    private void loadPlayer(final Player player) {
        this.profiles.load(player, profile -> {
            // поток игрока
            this.aspects.refresh(player);
            this.progression.onProfileLoaded(player, profile);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        this.loadPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.profiles.unload(event.getPlayer().getUniqueId());
    }

    // --- доступ для модулей ---

    public SystemContext context() {
        return this.context;
    }

    public GeneralSettings settings() {
        return this.settings.get();
    }

    public Keys keys() {
        return this.keys;
    }

    public ProfileStore profiles() {
        return this.profiles;
    }

    public AspectService aspects() {
        return this.aspects;
    }

    public ProgressionModule progression() {
        return this.progression;
    }

    public SharpenModule sharpen() {
        return this.sharpen;
    }

    public SealsModule seals() {
        return this.seals;
    }

    public CombatModule combat() {
        return this.combat;
    }
}
