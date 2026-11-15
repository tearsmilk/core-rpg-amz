package core.amazake.systems.coreweapons.runtime;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreweapons.Settings;
import core.amazake.systems.coreweapons.logic.Geometry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Дрожь земли от цели крита: кольцо расходится на {@code radius} за {@code duration} тиков. На каждом шаге — частицы
 * верхнего блока каждого столбца кольца, копии этих блоков (BlockDisplay, не сохраняются в чанк) подпрыгивают на
 * несколько пикселей и исчезают; мир не меняется. Всё — в потоке региона точки удара (событие удара, потом задача
 * регионального планировщика); столбцы чужих регионов и незагруженных чанков пропускаются.
 */
final class Tremor implements Consumer<ScheduledTask> {

    private record Shake(BlockDisplay display, long born, Quaternionf tilt) {
    }

    private final SystemContext context;
    private final Settings.Tremor cfg;
    private final World world;
    private final double cx;
    private final double cz;
    private final int baseY;
    private final UUID attacker;
    private final Set<BlockDisplay> registry;
    private final String instance;
    private final List<Shake> shakes = new ArrayList<>();
    private final Set<UUID> touched = new HashSet<>();
    private int step;
    private long tick;
    private int spawned;
    private double lastRadius;

    Tremor(final SystemContext context, final Settings.Tremor cfg, final Location center, final UUID attacker,
           final Set<BlockDisplay> registry, final String instance) {
        this.context = context;
        this.cfg = cfg;
        this.world = center.getWorld();
        this.cx = center.getBlockX() + 0.5;
        this.cz = center.getBlockZ() + 0.5;
        this.baseY = center.getBlockY();
        this.attacker = attacker;
        this.registry = registry;
        this.instance = instance;
    }

    /** Поток региона центра: звук, первый шаг, задача на остальные. */
    void start() {
        final Location c = new Location(this.world, this.cx, this.baseY, this.cz);
        for (final Settings.SoundSpec s : this.cfg.sounds()) {
            this.world.playSound(c, s.key(), SoundCategory.PLAYERS, s.volume(), s.pitch());
        }
        this.world.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 0.2, 0), 1);
        this.waveStep();
        Bukkit.getRegionScheduler().runAtFixedRate(this.context.plugin(), this.world, ((int) Math.floor(this.cx)) >> 4,
            ((int) Math.floor(this.cz)) >> 4, this, 1L, 1L);
    }

    @Override
    public void accept(final ScheduledTask task) {
        this.tick++;
        if (this.step < this.cfg.duration()) {
            this.waveStep();
        }
        this.animate();
        if (this.step >= this.cfg.duration() && this.shakes.isEmpty()) {
            task.cancel();
        }
    }

    private void waveStep() {
        final double r = Geometry.waveRadius(this.step, this.cfg.duration(), this.cfg.radius());
        final double from = this.step == 0 ? 0 : this.lastRadius;
        this.lastRadius = r;
        this.step++;
        final int ox = (int) Math.floor(this.cx);
        final int oz = (int) Math.floor(this.cz);
        for (final int[] col : Geometry.band(from, r)) {
            final int x = ox + col[0];
            final int z = oz + col[1];
            if (!this.world.isChunkLoaded(x >> 4, z >> 4) || !Bukkit.isOwnedByCurrentRegion(this.world, x >> 4, z >> 4)) {
                continue;
            }
            final Block top = surface(this.world, x, this.baseY, z);
            if (top == null) {
                continue;
            }
            final double px = x + 0.5;
            final double py = top.getY() + 1.02;
            final double pz = z + 0.5;
            if (this.cfg.particles() > 0) {
                this.world.spawnParticle(Particle.BLOCK, px, py, pz, this.cfg.particles(), 0.3, 0.05, 0.3, 0.08, top.getBlockData());
            }
            if (this.cfg.dustPillar() && ThreadLocalRandom.current().nextInt(3) == 0) {
                this.world.spawnParticle(Particle.DUST_PILLAR, px, py, pz, 2, 0.25, 0.0, 0.25, 0.1, top.getBlockData());
            }
            if (this.cfg.shake() && this.spawned < this.cfg.maxDisplays()) {
                this.shake(top);
            }
        }
        this.touch(from, r);
    }

    /** Верхний твёрдый блок столбца у высоты удара (на 1 выше … 4 ниже), над которым не твёрдый. */
    static Block surface(final World w, final int x, final int y, final int z) {
        for (int yy = y + 1; yy >= y - 4; yy--) {
            final Block b = w.getBlockAt(x, yy, z);
            if (b.getType().isSolid() && b.getType().isOccluding() && !b.getRelative(BlockFace.UP).getType().isOccluding()) {
                return b;
            }
        }
        return null;
    }

    private void shake(final Block top) {
        final Block above = top.getRelative(BlockFace.UP);
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        final Quaternionf tilt = new Quaternionf(new AxisAngle4f((float) Math.toRadians(rnd.nextDouble(-5, 5)),
            rnd.nextBoolean() ? 1 : 0, 0, rnd.nextBoolean() ? 0 : 1));
        final BlockDisplay d = this.world.spawn(top.getLocation(), BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setBlock(top.getBlockData());
            // свет блока над поверхностью: внутри твёрдого блока копия была бы чёрной
            e.setBrightness(new Display.Brightness(above.getLightFromBlocks(), above.getLightFromSky()));
            e.setTransformation(new Transformation(new Vector3f(-0.005f, 0.0f, -0.005f), new Quaternionf(),
                new Vector3f(1.01f, 1.01f, 1.01f), new Quaternionf()));
            e.setViewRange(0.6f);
            e.getPersistentDataContainer().set(WeaponManager.MARK, PersistentDataType.STRING, this.instance);
        });
        this.registry.add(d);
        this.shakes.add(new Shake(d, this.tick, tilt));
        this.spawned++;
    }

    /** Подскок копий: вверх за 2 тика, вниз за 3, удалить на 6-м. */
    private void animate() {
        for (int i = this.shakes.size() - 1; i >= 0; i--) {
            final Shake s = this.shakes.get(i);
            final int age = (int) (this.tick - s.born());
            final BlockDisplay d = s.display();
            if (!d.isValid()) {
                this.shakes.remove(i);
                this.registry.remove(d);
                continue;
            }
            if (age == 1 || age == 3) {
                final double h = age == 1 ? this.cfg.shakeHeight() : 0.0;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(age == 1 ? 2 : 3);
                d.setTransformation(new Transformation(new Vector3f(-0.005f, (float) h, -0.005f),
                    age == 1 ? s.tilt() : new Quaternionf(), new Vector3f(1.01f, 1.01f, 1.01f), new Quaternionf()));
            } else if (age >= 6) {
                d.remove();
                this.registry.remove(d);
                this.shakes.remove(i);
            }
        }
    }

    /** Толчок игрокам и (если задан) урон существам на кольце — каждому один раз за дрожь. */
    private void touch(final double from, final double to) {
        final boolean kick = this.cfg.kick() > 0;
        final boolean hurt = this.cfg.damage() > 0;
        if (!kick && !hurt) {
            return;
        }
        final Location c = new Location(this.world, this.cx, this.baseY, this.cz);
        for (final LivingEntity e : this.world.getNearbyLivingEntities(c, to + 1.0, 3.0)) {
            if (!Bukkit.isOwnedByCurrentRegion(e) || this.touched.contains(e.getUniqueId())) {
                continue;
            }
            final double dx = e.getX() - this.cx;
            final double dz = e.getZ() - this.cz;
            final double d = Math.sqrt(dx * dx + dz * dz);
            if (d < from - 0.7 || d > to + 0.7) {
                continue;
            }
            final boolean self = e.getUniqueId().equals(this.attacker);
            if (self && !this.cfg.kickAttacker()) {
                continue;
            }
            this.touched.add(e.getUniqueId());
            if (hurt && !self) {
                e.damage(this.cfg.damage());
            }
            if (kick && e instanceof Player p && e.isOnGround()) {
                final double k = this.cfg.kick();
                this.context.runOnceForPlayer(p, () -> {
                    final Vector v = p.getVelocity();
                    p.setVelocity(v.setY(Math.max(v.getY(), k)));
                });
            }
        }
    }

    /** Загрузить класс заранее (при включении): файл .azs могут заменить на месте до первого крита. */
    static void preload() {
        // пусто: вызов только загружает класс
    }

    /** Выключение: убрать свои копии (поток, которому они принадлежат). */
    static void removeAll(final SystemContext context, final Set<BlockDisplay> registry) {
        for (final BlockDisplay d : List.copyOf(registry)) {
            if (Bukkit.isOwnedByCurrentRegion(d)) {
                d.remove();
            } else {
                Bukkit.getRegionScheduler().execute(context.plugin(), d.getLocation(), d::remove);
            }
        }
        registry.clear();
    }
}
