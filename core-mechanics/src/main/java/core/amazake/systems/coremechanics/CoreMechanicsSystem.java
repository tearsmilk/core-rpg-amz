package core.amazake.systems.coremechanics;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coremechanics.command.RdCommand;
import core.amazake.systems.coremechanics.doll.DollRenderer;
import core.amazake.systems.coremechanics.doll.RagdollManager;
import core.amazake.systems.coremechanics.natives.JoltBootstrap;
import core.amazake.systems.coremechanics.physics.PhysicsLoop;
import core.amazake.systems.coremechanics.physics.SectionSampler;
import core.amazake.systems.coremechanics.skin.SkinCubes;
import core.amazake.systems.coremechanics.skin.SkinService;
import core.amazake.systems.coremechanics.statue.StatueManager;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * core-mechanics — рэгдоллы игроков на физдвижке Jolt (кукла при смерти, /rd ник, таскать/бросать) и куклы-статуи с
 * позами, анимациями и экспортом в /summon. Перенесено из плагина MagicRuruka (/mrr rd) на систему Amazake.
 *
 * <p>enable (глобальный поток) — быстрый: команды, события, статуи; физика (jar-ы Jolt, нативка, самотест)
 * запускается в асинхронном потоке, и пока она не готова, /rd ник отвечает «Физдвижок недоступен», а статуи уже
 * работают. Устройство — README.md.</p>
 */
public final class CoreMechanicsSystem implements AmazakeSystem, Listener {

    private final Object lock = new Object();
    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
    private volatile SystemContext context;
    private volatile DollRenderer renderer;
    private volatile SkinCubes cubes;
    private volatile RagdollManager ragdolls;
    private volatile StatueManager statues;
    private volatile String physicsStatus = "запускается";
    private volatile boolean disabled;

    @Override
    public void enable(final SystemContext context) {
        final long t0 = System.nanoTime();
        this.context = context;
        this.disabled = false;
        context.saveDefaultResource("config.yml");
        this.loadSettings();
        this.renderer = new DollRenderer(context.plugin());
        this.cubes = new SkinCubes(context.logger(), context.dataFolder(), () -> {
            final Settings s = this.settings.get();
            return new SkinCubes.Config(s.mineskinKey(), s.mineskinUrl(), s.mineskinTimeout(), "core-mechanics/1.0");
        }, this::cubesReady);
        final SkinService skins = new SkinService(context.plugin(), this.cubes, () -> this.disabled);
        this.ragdolls = new RagdollManager(context, this.settings::get, this.renderer, skins);
        this.statues = new StatueManager(context, this.settings::get, this.ragdolls, this.renderer, skins);
        context.registerListener(this.ragdolls);
        context.registerListener(this.statues);
        context.registerListener(this);
        context.registerCommand(new RdCommand(this.ragdolls, this.statues, this::status));
        context.registerCommand(new core.amazake.systems.coremechanics.tooltip.TooltipCommand(
            () -> this.settings.get().tooltipFlavors()));
        context.registerListener(new core.amazake.systems.coremechanics.admin.RpgAdmin(context.plugin()));
        context.registerCommand(new core.amazake.systems.coremechanics.admin.RpgAdminCommand(context));
        this.statues.start();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            context.runOnceForPlayer(p, () -> this.cleanupNear(p));
        }
        Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> this.startPhysics(context));
        context.logger().info("core-mechanics: включён за " + (System.nanoTime() - t0) / 1_000_000 + " мс; физика (Jolt) запускается в фоне");
    }

    /** Асинхронный поток: jar-ы и нативка Jolt, самотест, поток физики. */
    private void startPhysics(final SystemContext context) {
        final Settings s = this.settings.get();
        try {
            final JoltBootstrap.Started st = JoltBootstrap.start(context.dataFolder(), s.libsAutoDownload(), s.libsRepositories(),
                s.physicsThreads(), m -> context.logger().info("core-mechanics: " + m));
            final PhysicsLoop loop = new PhysicsLoop(st.host(), new SectionSampler(context.plugin()), context.logger(),
                new PhysicsLoop.Params(s.physicsMaxBodies(), (float) s.physicsGravity(), s.physicsSubsteps(), s.physicsRequestsPerTick()));
            synchronized (this.lock) {
                if (this.disabled) {
                    st.host().shutdown();
                    return;
                }
                this.ragdolls.attach(loop);
            }
            this.physicsStatus = "работает: " + st.info();
            context.logger().info("core-mechanics: физика готова — " + st.info());
        } catch (final Throwable e) {
            if (e instanceof VirtualMachineError error && !(e instanceof StackOverflowError)) {
                throw error;
            }
            this.physicsStatus = "недоступна: " + e.getMessage();
            context.logger().log(Level.SEVERE, "core-mechanics: физика (Jolt) не запустилась — рэгдоллы выключены, статуи работают", e);
        }
    }

    @Override
    public void disable(final SystemContext context) {
        synchronized (this.lock) {
            this.disabled = true;
        }
        int left = 0;
        if (this.statues != null) {
            left += this.statues.shutdown();
        }
        if (this.ragdolls != null) {
            left += this.ragdolls.shutdown();
        }
        if (this.cubes != null) {
            this.cubes.shutdown();
        }
        context.logger().info("core-mechanics: выключен" + (left > 0 ? "; дисплеев в регионах других потоков не убрано сразу: " + left
            + " (несохраняемые — пропадут с выгрузкой чанка, остальное уберёт следующее включение)" : ""));
    }

    /** amazake system reload: перечитать config.yml (параметры физ-систем — после disable/enable). */
    @Override
    public void reload(final SystemContext context) {
        this.loadSettings();
        context.logger().info("core-mechanics: config.yml перечитан (physics.* применяются после выключения/включения системы)");
    }

    private void loadSettings() {
        this.settings.set(Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w)));
    }

    private void cubesReady(final String hash) {
        final String[][] tex = this.cubes.get(hash);
        if (tex == null || this.disabled) {
            return;
        }
        this.ragdolls.upgrade(hash, tex);
        this.statues.upgrade(hash, tex);
    }

    private String status() {
        final RagdollManager r = this.ragdolls;
        final PhysicsLoop l = r == null ? null : r.loop();
        return "core-mechanics: физика " + this.physicsStatus + "\nрэгдоллов " + (r == null ? 0 : r.count()) + ", статуй "
            + (this.statues == null ? 0 : this.statues.count()) + ", дисплеев " + (this.renderer == null ? 0 : this.renderer.liveCount())
            + ", MineSkin " + (this.cubes == null || !this.cubes.enabled() ? "выключен (нет ключа)" : this.cubes.available() ? "включён" : "недоступен (пауза)")
            + (l == null ? "" : "\n" + l.stats()) + (r == null || r.count() == 0 ? "" : "\n" + String.join("\n", r.describe()));
    }

    // ---- хвосты после аварийного выключения (дисплеи с нашей меткой, которых нет среди живых)

    /** Поток региона чанка. */
    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent e) {
        final DollRenderer r = this.renderer;
        for (final Entity en : e.getEntities()) {
            if (r.isOurs(en) && !r.isLive(en)) {
                en.remove();
            }
        }
    }

    /** Поток игрока (enable): убрать наши хвосты рядом. */
    private void cleanupNear(final Player p) {
        try {
            int n = 0;
            for (final Entity en : p.getNearbyEntities(128, 128, 128)) {
                if (this.renderer.isOurs(en) && !this.renderer.isLive(en) && Bukkit.isOwnedByCurrentRegion(en)) {
                    en.remove();
                    n++;
                }
            }
            if (n > 0) {
                this.context.logger().info("core-mechanics: убрано старых дисплеев кукол рядом с " + p.getName() + ": " + n);
            }
        } catch (final RuntimeException e) {
            this.context.logger().fine("чистка хвостов: " + e);
        }
    }
}
