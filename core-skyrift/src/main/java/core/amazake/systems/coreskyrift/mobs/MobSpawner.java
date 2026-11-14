package core.amazake.systems.coreskyrift.mobs;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreskyrift.Settings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Мобы из разлома через core-mobs. У core-mobs нет API для других систем (отдельный .azs, общего реестра служб
 * нет), поэтому спавн — его консольной командой {@code mobs spawn <id> 1 <мир> <x> <y> <z>} от «тихого»
 * отправителя ({@link Bukkit#createCommandSender}: права консоли, ответ «Призвано…» никуда не идёт) в глобальном
 * потоке. Сам спавн core-mobs делает задачей региона точки ({@code world.spawn(..., CUSTOM, ...)}, метка
 * {@code core-mobs:mob} ставится до события спавна).
 *
 * <p>Своих мобов система узнаёт в {@link CreatureSpawnEvent} (поток региона моба): причина CUSTOM, есть метка
 * {@code core-mobs:mob}, а место совпадает с одной из ждущих точек ({@link #expect}). Такому мобу — метка
 * {@code coreskyrift:event}, медленное падение, (по желанию) подсветка и огненный след падения.</p>
 */
public final class MobSpawner implements Listener {

    /** Метка core-mobs на его мобах (id из mobs.yml). */
    public static final NamespacedKey CORE_MOBS_KEY = new NamespacedKey("core-mobs", "mob");
    /** Наша метка: id события. */
    public static final NamespacedKey EVENT_KEY = new NamespacedKey("coreskyrift", "event");
    /** Точка ждёт моба столько миллисекунд. */
    static final long PENDING_MS = 5000;
    private static final double MATCH = 0.05;

    /** Кто ждёт мобов: id события → что сделать с пришедшим мобом. */
    public interface Owner {
        /** Моб события пришёл (поток региона моба). */
        void arrived(UUID eventId, Entity mob);

        /** Точка не дождалась моба (любой поток). */
        void expired(UUID eventId);

        /** Событие ещё идёт (не закончилось и не убрано). Любой поток. */
        boolean live(UUID eventId);
    }

    private record Pending(UUID eventId, UUID world, double x, double y, double z, long createdMs) {
    }

    private final SystemContext context;
    private final java.util.function.Supplier<Settings> settings;
    private final Owner owner;
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final Set<ScheduledTask> trails = ConcurrentHashMap.newKeySet();
    private final CommandSender silent;

    public MobSpawner(final SystemContext context, final java.util.function.Supplier<Settings> settings,
                      final Owner owner) {
        this.context = context;
        this.settings = settings;
        this.owner = owner;
        CommandSender s;
        try {
            s = Bukkit.createCommandSender(c -> { });
        } catch (final RuntimeException | LinkageError e) {
            s = Bukkit.getConsoleSender();
        }
        this.silent = s;
    }

    /**
     * Ждать моба события в точке и послать команду core-mobs. Любой поток (команда уходит в глобальный).
     * Координаты — до сотых: так же их прочитает core-mobs.
     */
    public void spawn(final UUID eventId, final World world, final String mobId, final double x, final double y,
                      final double z) {
        final double rx = round2(x);
        final double ry = round2(y);
        final double rz = round2(z);
        this.expect(eventId, world, rx, ry, rz);
        final String cmd = this.settings.get().mobs().command()
            .replace("{id}", mobId)
            .replace("{amount}", "1")
            .replace("{world}", world.getName())
            .replace("{x}", fmt(rx))
            .replace("{y}", fmt(ry))
            .replace("{z}", fmt(rz));
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
            try {
                if (!Bukkit.dispatchCommand(this.silent, cmd)) {
                    this.context.logger().warning("Команда спавна не найдена (core-mobs включён?): /" + cmd);
                }
            } catch (final RuntimeException e) {
                this.context.logger().log(Level.WARNING, "Команда спавна /" + cmd + " упала", e);
            }
        });
    }

    void expect(final UUID eventId, final World world, final double x, final double y, final double z) {
        this.pending.add(new Pending(eventId, world.getUID(), x, y, z, System.currentTimeMillis()));
    }

    /** Убрать просроченные точки (их моб не пришёл: core-mobs выключен, нет такого id, чанк выгружен). Любой поток. */
    public void expire() {
        final long now = System.currentTimeMillis();
        for (final Iterator<Pending> it = this.pending.iterator(); it.hasNext(); ) {
            final Pending p = it.next();
            if (now - p.createdMs() > PENDING_MS) {
                it.remove();
                this.owner.expired(p.eventId());
            }
        }
    }

    /**
     * Ждущая точка для этого спавна: сначала точка идущего события, иначе любая (события, которое уже кончилось:
     * команда была в пути). {@code null} — не наш моб.
     */
    private Pending match(final Location at) {
        final UUID world = at.getWorld().getUID();
        Pending dead = null;
        for (final Pending p : this.pending) {
            if (p.world().equals(world) && Math.abs(p.x() - at.getX()) < MATCH && Math.abs(p.y() - at.getY()) < MATCH
                && Math.abs(p.z() - at.getZ()) < MATCH) {
                if (this.owner.live(p.eventId())) {
                    return p;
                }
                if (dead == null) {
                    dead = p;
                }
            }
        }
        return dead;
    }

    private static boolean ours(final CreatureSpawnEvent event) {
        return event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
            && event.getEntity().getPersistentDataContainer().has(CORE_MOBS_KEY, PersistentDataType.STRING);
    }

    /**
     * Моб пришёл к событию, которое уже кончилось (или убрано), а мобы событий убираются в конце: не дать ему
     * появиться вовсе (отмена до MONITOR). Поток региона моба.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLateSpawn(final CreatureSpawnEvent event) {
        if (this.pending.isEmpty() || !ours(event) || !this.settings.get().mobs().despawnOnEnd()) {
            return;
        }
        final Pending match = this.match(event.getLocation());
        if (match == null || this.owner.live(match.eventId()) || !this.pending.remove(match)) {
            return;
        }
        event.setCancelled(true);
        this.owner.expired(match.eventId());
    }

    /** Поток региона моба. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (this.pending.isEmpty() || !ours(event)) {
            return;
        }
        final LivingEntity mob = event.getEntity();
        final Location at = event.getLocation();
        final Pending match = this.match(at);
        if (match == null || !this.pending.remove(match)) {
            return;
        }
        final Settings.Mobs m = this.settings.get().mobs();
        mob.getPersistentDataContainer().set(EVENT_KEY, PersistentDataType.STRING, match.eventId().toString());
        if (m.despawnOnEnd()) {
            mob.setPersistent(false);   // не сохраняется в чанк: после конца и рестарта не останется
        }
        if (m.slowFallingSeconds() > 0) {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, m.slowFallingSeconds() * 20, 0, false,
                true, false));
        }
        if (m.glowing()) {
            mob.setGlowing(true);
        }
        this.owner.arrived(match.eventId(), mob);
        this.trail(mob, at.clone());
    }

    /** Огненный след падения: столб частиц от моба до точки появления + 30. Задача региона точки. */
    private void trail(final LivingEntity mob, final Location from) {
        final Settings.Effects fx = this.settings.get().effects();
        if (fx.sounds()) {
            from.getWorld().playSound(from, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 1.6f, 0.5f);
            from.getWorld().playSound(from, Sound.AMBIENT_BASALT_DELTAS_MOOD, SoundCategory.HOSTILE, 1.0f, 0.7f);
        }
        if (!fx.particles() || fx.trailTicks() <= 0) {
            return;
        }
        final int life = fx.trailTicks();
        final double top = from.getY() + 30;
        final int[] age = {0};
        final boolean[] fell = {false};
        final Particle.DustOptions blood = new Particle.DustOptions(Color.fromRGB(176, 0, 16), 2.0f);
        final Particle.DustOptions ember = new Particle.DustOptions(Color.fromRGB(255, 64, 0), 1.2f);
        final Consumer<ScheduledTask> step = task -> {
            age[0] += 2;
            if (age[0] > life || !mob.isValid() || !Bukkit.isOwnedByCurrentRegion(mob)) {
                task.cancel();
                this.trails.remove(task);
                return;
            }
            final Location at = mob.getLocation();
            final World w = at.getWorld();
            final double h = Math.max(0, top - at.getY());
            // Несколько пакетов на шаг, а не по частице на пакет; force = false — только тем, кто рядом (обычная
            // дальность частиц), а не всем в 512 блоках.
            final int n = (int) Math.min(18, 4 + h / 2);
            w.spawnParticle(Particle.DUST, at.getX(), at.getY() + 1 + h / 2, at.getZ(), n, 0.25, Math.max(0.25, h / 4),
                0.25, 0, blood, false);
            w.spawnParticle(Particle.DUST, at.getX(), at.getY() + 1, at.getZ(), 4, 0.4, 0.6, 0.4, 0, ember, false);
            w.spawnParticle(Particle.FALLING_LAVA, at.getX(), at.getY() + 2, at.getZ(), 2, 0.4, 1.5, 0.4, 0, null,
                false);
            if (age[0] % 4 == 0) {
                w.spawnParticle(Particle.SMALL_FLAME, at.getX(), at.getY() + 0.8, at.getZ(), 3, 0.3, 0.5, 0.3, 0.01,
                    null, false);
            }
            if (age[0] % 8 == 0) {
                w.spawnParticle(Particle.LARGE_SMOKE, at.getX(), at.getY() + 1.5, at.getZ(), 2, 0.3, 0.6, 0.3, 0.01,
                    null, false);
            }
            if (!fell[0] && age[0] > 4 && isOnGround(mob)) {
                fell[0] = true;
                w.spawnParticle(Particle.LAVA, at.getX(), at.getY() + 0.2, at.getZ(), 10, 0.6, 0.1, 0.6, 0, null, false);
                w.spawnParticle(Particle.DUST, at.getX(), at.getY() + 0.1, at.getZ(), 30, 1.2, 0.05, 1.2, 0, blood, false);
                if (fx.sounds()) {
                    w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 0.4f, 0.7f);
                }
                task.cancel();
                this.trails.remove(task);
            }
        };
        final ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(this.context.plugin(), from, step, 2L, 2L);
        this.trails.add(task);
    }

    @SuppressWarnings("deprecation")
    private static boolean isOnGround(final Entity e) {
        return e.isOnGround();
    }

    /**
     * Найти место спавна над землёй в столбце (x, z): верхний блок + {@code drop}, не выше верха мира − 3. Над
     * верхним блоком по определению открытое небо (не под крышей и не в пещере). Жидкость сверху (море, лава) —
     * не годится. Поток региона этого чанка; чанк чужой или не загружен — {@code null}.
     */
    public static Location column(final World world, final double x, final double z, final int drop) {
        final int bx = (int) Math.floor(x);
        final int bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4) || !Bukkit.isOwnedByCurrentRegion(world, bx >> 4, bz >> 4)) {
            return null;
        }
        final int top = world.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (top <= world.getMinHeight()) {
            return null;
        }
        final Block ground = world.getBlockAt(bx, top, bz);
        final Material type = ground.getType();
        if (ground.isLiquid() || type == Material.LAVA || type == Material.WATER || type == Material.MAGMA_BLOCK
            || type == Material.FIRE || type == Material.CACTUS) {
            return null;
        }
        final double y = Math.min(top + 1 + drop, world.getMaxHeight() - 3);
        return new Location(world, bx + 0.5, Math.max(y, top + 1), bz + 0.5);
    }

    /** Отменить следы. Любой поток. */
    public void shutdown() {
        for (final ScheduledTask t : this.trails) {
            t.cancel();
        }
        this.trails.clear();
        this.pending.clear();
    }

    /**
     * Мобы события на уборку: моб своего региона — сразу, иначе задачей региона, где он сейчас (как и дисплеи при
     * выключении: задачи региона после {@code disable} могут уже не выполниться). Любой поток.
     */
    public void despawn(final Map<UUID, Entity> mobs) {
        for (final Entity e : mobs.values()) {
            if (Bukkit.isOwnedByCurrentRegion(e)) {
                if (e.isValid()) {
                    this.poof(e);
                    e.remove();
                }
                continue;
            }
            final Location at;
            try {
                at = e.getLocation();
            } catch (final RuntimeException ex) {
                continue;
            }
            Bukkit.getRegionScheduler().execute(this.context.plugin(), at, () -> {
                if (e.isValid() && Bukkit.isOwnedByCurrentRegion(e)) {
                    this.poof(e);
                    e.remove();
                }
            });
        }
    }

    /** Дым и пламя на месте убранного моба. Поток его региона. */
    private void poof(final Entity e) {
        if (!this.settings.get().effects().particles()) {
            return;
        }
        final Location l = e.getLocation();
        l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l.getX(), l.getY() + 1, l.getZ(), 8, 0.3, 0.5, 0.3, 0.02, null,
            true);
        l.getWorld().spawnParticle(Particle.FLAME, l.getX(), l.getY() + 1, l.getZ(), 6, 0.3, 0.5, 0.3, 0.02, null, true);
    }

    static double round2(final double v) {
        return Math.round(v * 100) / 100.0;
    }

    static String fmt(final double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
