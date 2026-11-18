package core.amazake.systems.coremechanics.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;

/**
 * Поток физики {@code core-mechanics-physics}: 20 шагов в секунду, ВСЕ вызовы Jolt — только из него (поэтому Jolt не
 * синхронизируется). Другие потоки общаются с ним очередью {@link #submit} (создать/бросить/убрать куклу, блок
 * изменился, пришёл снимок секции). Мир Bukkit отсюда не читается: снимки секций приходят из потоков регионов
 * ({@link SectionSampler}), позы уходят в потоки регионов (DollRenderer). Тики сервера поток не ждёт и не блокирует.
 */
public final class PhysicsLoop {

    /** Кто считает свои тела этим шагом (рэгдоллы). Методы — в потоке физики. */
    public interface Participant {
        /** До шага: подготовить меш мира, задать скорости; вызвать {@link WorldState#use} у своих миров. */
        void prepare(long tick);

        /** После шага: прочитать позы, отдать на отрисовку. */
        void afterStep(long tick);
    }

    /** Физика одного мира Bukkit. */
    public final class WorldState {
        public final World world;
        public final PhysicsWorld physics;
        public final WorldMeshes meshes;
        long usedAt = -1;
        int lastError;

        WorldState(final World world, final PhysicsWorld physics) {
            this.world = world;
            this.physics = physics;
            this.meshes = new WorldMeshes(physics, this::request, world.getMinHeight(), world.getMaxHeight());
        }

        /** Поток физики: снять секцию в потоке её региона, ответ — обратно в поток физики. */
        private void request(final int sx, final int sy, final int sz) {
            PhysicsLoop.this.sampler.request(this.world, sx, sy, sz,
                sample -> PhysicsLoop.this.submit(() -> this.meshes.accept(sample, PhysicsLoop.this.tick)));
        }

        /** Шагнуть этот мир в этом тике. */
        public void use() {
            this.usedAt = PhysicsLoop.this.tick;
        }
    }

    /** Параметры физ-систем (из config.yml). */
    public record Params(int maxBodies, float gravity, int substeps, int requestsPerTick) {
    }

    private static final long SECTION_IDLE_TICKS = 200;

    private final PhysicsHost host;
    private final SectionSampler sampler;
    private final Logger log;
    private final Params params;
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final Map<UUID, WorldState> worlds = new HashMap<>();
    private final ScheduledExecutorService exec;
    private Participant participant;
    private volatile long tick;
    private volatile String stats = "";
    private volatile long lastTickNanos;
    private long lastErrorLog;

    public PhysicsLoop(final PhysicsHost host, final SectionSampler sampler, final Logger log, final Params params) {
        this.host = host;
        this.sampler = sampler;
        this.log = log;
        this.params = params;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "core-mechanics-physics");
            t.setDaemon(true);
            return t;
        });
    }

    public void start(final Participant p) {
        this.participant = p;
        this.exec.scheduleAtFixedRate(this::tickSafe, 50, 50, TimeUnit.MILLISECONDS);
    }

    /** Любой поток: выполнить в потоке физики в начале следующего шага. */
    public void submit(final Runnable r) {
        this.inbox.add(r);
    }

    public long tick() {
        return this.tick;
    }

    public PhysicsHost host() {
        return this.host;
    }

    public Params params() {
        return this.params;
    }

    /** Поток физики: физ-система мира (создаётся при первом обращении). */
    public WorldState world(final World w) {
        return this.worlds.computeIfAbsent(w.getUID(), id -> new WorldState(w,
            this.host.newWorld(this.params.maxBodies(), this.params.gravity(), this.params.substeps())));
    }

    /** Поток физики: мир выгружен — уничтожить его физ-систему. */
    public void dropWorld(final UUID id) {
        final WorldState ws = this.worlds.remove(id);
        if (ws != null) {
            ws.meshes.destroyAll();
            ws.physics.destroy();
        }
    }

    /** Любой поток: блок изменился — пересобрать меш его секции. */
    public void blockChanged(final World w, final int x, final int y, final int z) {
        this.submit(() -> {
            final WorldState ws = this.worlds.get(w.getUID());
            if (ws != null) {
                ws.meshes.markDirtyBlock(x, y, z);
            }
        });
    }

    /** Строка для /rd info (обновляется раз в секунду). */
    public String stats() {
        return this.stats;
    }

    private void tickSafe() {
        try {
            this.tickOnce();
        } catch (final Throwable t) {
            if (t instanceof VirtualMachineError e) {
                throw e;
            }
            final long now = System.currentTimeMillis();
            if (now - this.lastErrorLog > 30_000) {
                this.lastErrorLog = now;
                this.log.log(Level.WARNING, "ошибка в шаге физики (следующие такие — не чаще раза в 30 с)", t);
            }
        }
    }

    private void tickOnce() {
        final long t0 = System.nanoTime();
        this.tick++;
        final long tk = this.tick;
        for (Runnable r; (r = this.inbox.poll()) != null; ) {
            try {
                r.run();
            } catch (final RuntimeException e) {
                this.log.log(Level.WARNING, "ошибка команды физики", e);
            }
        }
        for (final WorldState ws : this.worlds.values()) {
            ws.meshes.beginTick();
        }
        this.participant.prepare(tk);
        for (final WorldState ws : this.worlds.values()) {
            if (ws.usedAt == tk) {
                final int err = ws.physics.step();
                if (err != 0 && err != ws.lastError) {
                    this.log.warning("Jolt: ошибка шага " + err + " в мире " + ws.world.getName()
                        + " (переполнены пары/контакты — меньше кукол)");
                }
                ws.lastError = err;
                ws.meshes.verify(tk, 2, 40, this.params.requestsPerTick());
            }
            if (tk % 100 == 0) {
                ws.meshes.evict(tk, SECTION_IDLE_TICKS);
            }
        }
        this.participant.afterStep(tk);
        this.lastTickNanos = System.nanoTime() - t0;
        if (tk % 20 == 0) {
            final List<String> parts = new ArrayList<>();
            for (final WorldState ws : this.worlds.values()) {
                parts.add(String.format(Locale.ROOT, "%s: тел %d (активных %d), секций %d, сборок %d, шаг %.2f мс%s",
                    ws.world.getName(), ws.physics.bodyCount(), ws.physics.activeBodyCount(), ws.meshes.sectionCount(),
                    ws.meshes.builds(), ws.physics.lastStepNanos() / 1e6,
                    ws.meshes.lastError().isEmpty() ? "" : " (" + ws.meshes.lastError() + ")"));
            }
            this.stats = String.format(Locale.ROOT, "тик физики %.2f мс; ", this.lastTickNanos / 1e6)
                + (parts.isEmpty() ? "миров нет" : String.join("; ", parts));
        }
    }

    /**
     * Остановить (любой поток, ждёт до timeoutMs): в потоке физики выполнить last (убрать тела кукол), уничтожить миры
     * и пул Jolt. true — успели.
     */
    public boolean stop(final Runnable last, final long timeoutMs) {
        this.exec.execute(() -> {
            try {
                for (Runnable r; (r = this.inbox.poll()) != null; ) {
                    try {
                        r.run();
                    } catch (final RuntimeException ignored) {
                        // выключаемся
                    }
                }
                last.run();
            } finally {
                for (final UUID id : new ArrayList<>(this.worlds.keySet())) {
                    try {
                        this.dropWorld(id);
                    } catch (final RuntimeException e) {
                        this.log.log(Level.WARNING, "ошибка при уничтожении физ-системы мира", e);
                    }
                }
                this.host.shutdown();
            }
        });
        this.exec.shutdown();
        try {
            return this.exec.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
