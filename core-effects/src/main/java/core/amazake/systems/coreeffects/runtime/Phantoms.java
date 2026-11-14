package core.amazake.systems.coreeffects.runtime;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreeffects.Settings;
import core.amazake.systems.coreeffects.item.Keys;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Фантомы паники одного игрока: мобы и «игроки» (манекены с его же скином), которых видит только он
 * ({@code setVisibleByDefault(false)} + {@code showEntity}). Без ИИ, бессмертные, не сохраняются в мир; медленно идут к
 * игроку и растворяются в дыму рядом с ним или по истечении жизни. Весь класс — поток игрока; сущность трогается только
 * если её регион принадлежит текущему потоку, иначе убирается задачей её региона.
 */
final class Phantoms {

    private record Ghost(LivingEntity entity, long dieAt, Sound sound) {
    }

    private final SystemContext context;
    private final Player player;
    private final String instance;
    private final List<Ghost> ghosts = new ArrayList<>();
    private long nextSpawn = -1;

    Phantoms(final SystemContext context, final Player player, final String instance) {
        this.context = context;
        this.player = player;
        this.instance = instance;
    }

    int alive() {
        return this.ghosts.size();
    }

    /** Тик паники (поток игрока). */
    void tick(final long now, final Settings.Phantoms s) {
        final Iterator<Ghost> it = this.ghosts.iterator();
        final Location eye = this.player.getEyeLocation();
        while (it.hasNext()) {
            final Ghost g = it.next();
            final LivingEntity e = g.entity();
            if (!e.isValid()) {
                it.remove();
                continue;
            }
            if (!Bukkit.isOwnedByCurrentRegion(e) || e.getWorld() != eye.getWorld()) {
                it.remove();
                this.removeElsewhere(e);
                continue;
            }
            final Location at = e.getLocation();
            final Vector to = eye.toVector().subtract(at.toVector());
            to.setY(0);
            final double dist = to.length();
            if (now >= g.dieAt() || dist < 1.6) {
                it.remove();
                this.vanish(e, s.sounds() && dist < 1.6);
                continue;
            }
            final float yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            if (s.approachSpeed() > 0 && now % 2 == 0) {
                final Vector step = to.normalize().multiply(s.approachSpeed() * 2);
                final Location next = at.clone().add(step);
                next.setYaw(yaw);
                next.setPitch(0);
                if (Bukkit.isOwnedByCurrentRegion(next)) {
                    e.teleportAsync(next);
                }
            } else {
                e.setRotation(yaw, 0);
            }
            if (s.sounds() && ThreadLocalRandom.current().nextInt(50) == 0 && g.sound() != null) {
                this.player.playSound(at, g.sound(), SoundCategory.HOSTILE, 0.7f, 0.6f);
            }
        }
        if (!s.enabled() || s.maxAlive() <= 0 || s.types().isEmpty()) {
            return;
        }
        if (this.nextSpawn < 0) {
            this.nextSpawn = now + 20L * s.intervalSeconds().pick(ThreadLocalRandom.current()) / 2;
        }
        if (now >= this.nextSpawn) {
            this.nextSpawn = now + 20L * s.intervalSeconds().pick(ThreadLocalRandom.current());
            if (this.ghosts.size() < s.maxAlive()) {
                this.spawn(now, s);
            }
        }
    }

    private void spawn(final long now, final Settings.Phantoms s) {
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        final Location base = this.player.getLocation();
        final World world = base.getWorld();
        final String typeName = s.types().get(r.nextInt(s.types().size()));
        for (int attempt = 0; attempt < 6; attempt++) {
            final double yaw = Math.toRadians(base.getYaw() + r.nextDouble(-40, 40));
            final double d = s.distance().pick(r) + r.nextDouble();
            final double x = base.getX() - Math.sin(yaw) * d;
            final double z = base.getZ() + Math.cos(yaw) * d;
            final Location at = this.ground(world, x, base.getY(), z);
            if (at == null || !Bukkit.isOwnedByCurrentRegion(at)) {
                continue;
            }
            final Vector look = base.toVector().subtract(at.toVector());
            at.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));
            final LivingEntity e = this.create(world, at, typeName);
            if (e == null) {
                return;
            }
            this.player.showEntity(this.context.plugin(), e);
            final long life = 20L * s.lifetimeSeconds().pick(r);
            final Sound sound = sound(e.getType());
            this.ghosts.add(new Ghost(e, now + life, sound));
            if (s.sounds() && sound != null) {
                this.player.playSound(at, sound, SoundCategory.HOSTILE, 0.9f, 0.5f);
            }
            return;
        }
    }

    private LivingEntity create(final World world, final Location at, final String typeName) {
        final Class<? extends Entity> cls;
        if (typeName.equals("PLAYER") || typeName.equals("MANNEQUIN")) {
            cls = Mannequin.class;
        } else {
            final EntityType t;
            try {
                t = EntityType.valueOf(typeName);
            } catch (final IllegalArgumentException ex) {
                return null;
            }
            if (t.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(t.getEntityClass()) || !t.isSpawnable()) {
                return null;
            }
            cls = t.getEntityClass();
        }
        final Entity spawned = world.spawn(at, cls, CreatureSpawnEvent.SpawnReason.CUSTOM, raw -> {
            raw.setVisibleByDefault(false);
            raw.setPersistent(false);
            raw.setSilent(true);
            raw.setInvulnerable(true);
            raw.getPersistentDataContainer().set(Keys.PHANTOM, PersistentDataType.STRING, this.instance);
            if (raw instanceof LivingEntity le) {
                le.setAI(false);
                le.setCollidable(false);
                le.setRemoveWhenFarAway(true);
                le.setCanPickupItems(false);
            }
            if (raw instanceof Mannequin m) {
                m.setProfile(ResolvableProfile.resolvableProfile(this.player.getPlayerProfile()));
                m.setDescription(Component.empty());
                m.setImmovable(true);
            }
        });
        return spawned instanceof LivingEntity le ? le : null;
    }

    /** Пол под точкой: от y+3 вниз до y−6 первый твёрдый блок с двумя проходимыми над ним. */
    private Location ground(final World world, final double x, final double y, final double z) {
        final int bx = (int) Math.floor(x);
        final int bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return null;
        }
        final Location probe = new Location(world, x, y, z);
        if (!Bukkit.isOwnedByCurrentRegion(probe)) {
            return null;
        }
        for (int by = (int) Math.floor(y) + 3; by >= (int) Math.floor(y) - 6; by--) {
            final Block floor = world.getBlockAt(bx, by, bz);
            if (floor.getType().isSolid() && floor.getRelative(0, 1, 0).isPassable() && !floor.getRelative(0, 1, 0).isLiquid()
                && floor.getRelative(0, 2, 0).isPassable()) {
                return new Location(world, bx + 0.5, by + 1, bz + 0.5);
            }
        }
        return null;
    }

    private void vanish(final LivingEntity e, final boolean scare) {
        final Location at = e.getLocation().add(0, 1, 0);
        this.player.spawnParticle(Particle.LARGE_SMOKE, at, 18, 0.25, 0.5, 0.25, 0.02);
        this.player.spawnParticle(Particle.SQUID_INK, at, 8, 0.2, 0.4, 0.2, 0.02);
        if (scare) {
            this.player.playSound(at, Sound.ENTITY_WARDEN_NEARBY_CLOSE, SoundCategory.HOSTILE, 0.8f, 1.4f);
        }
        e.remove();
    }

    /** Растворить сразу одного (ударили его). Поток игрока. */
    boolean hit(final Entity e) {
        for (final Iterator<Ghost> it = this.ghosts.iterator(); it.hasNext(); ) {
            final Ghost g = it.next();
            if (g.entity().equals(e)) {
                it.remove();
                if (Bukkit.isOwnedByCurrentRegion(e)) {
                    this.vanish(g.entity(), false);
                } else {
                    this.removeElsewhere(g.entity());
                }
                return true;
            }
        }
        return false;
    }

    /** Убрать всех (конец паники, выход, выключение). Любой поток игрока. */
    void clear() {
        for (final Ghost g : this.ghosts) {
            final LivingEntity e = g.entity();
            if (Bukkit.isOwnedByCurrentRegion(e)) {
                e.remove();
            } else {
                this.removeElsewhere(e);
            }
        }
        this.ghosts.clear();
        this.nextSpawn = -1;
    }

    private void removeElsewhere(final Entity e) {
        final Location at = e.getLocation();
        try {
            Bukkit.getRegionScheduler().execute(this.context.plugin(), at, () -> {
                if (e.isValid()) {
                    e.remove();
                }
            });
        } catch (final RuntimeException ex) {
            // система выключается: сущность и так не сохраняется в мир (setPersistent(false))
        }
    }

    private static Sound sound(final EntityType t) {
        return switch (t) {
            case ZOMBIE, HUSK, DROWNED -> Sound.ENTITY_ZOMBIE_AMBIENT;
            case SKELETON, STRAY -> Sound.ENTITY_SKELETON_AMBIENT;
            case WITHER_SKELETON -> Sound.ENTITY_WITHER_SKELETON_AMBIENT;
            case ENDERMAN -> Sound.ENTITY_ENDERMAN_STARE;
            case CREEPER -> Sound.ENTITY_CREEPER_PRIMED;
            case SPIDER, CAVE_SPIDER -> Sound.ENTITY_SPIDER_AMBIENT;
            default -> Sound.AMBIENT_CAVE;
        };
    }
}
