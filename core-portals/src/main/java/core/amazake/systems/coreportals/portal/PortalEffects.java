package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.Phase;
import core.amazake.systems.coreportals.anim.Timeline;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Ванильные частицы и звуки портала по стадиям. Видны и слышны всем рядом. Поток региона портала. */
final class PortalEffects {

    private static final Particle.DustOptions EMBER = new Particle.DustOptions(Color.fromRGB(255, 92, 20), 1.1f);
    private static final Particle.DustOptions DEEP_RED = new Particle.DustOptions(Color.fromRGB(170, 20, 8), 1.6f);
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(220, 24, 10), 1.3f);
    private static final Particle.DustOptions ORANGE = new Particle.DustOptions(Color.fromRGB(255, 96, 36), 0.9f);
    private static final Particle.DustTransition RED_TO_ORANGE =
        new Particle.DustTransition(Color.fromRGB(190, 12, 6), Color.fromRGB(255, 120, 40), 1.2f);
    /** Стойки рамы (по x от середины). */
    private static final double SIDE = 1.2;

    private final World world;
    private final Spot spot;

    PortalEffects(final World world, final Spot spot) {
        this.world = world;
        this.spot = spot;
    }

    /** Эффекты тика {@code t}. */
    void tick(final Timeline tl, final long t, final Settings s) {
        final Phase phase = tl.phase(t);
        if (s.sounds()) {
            this.sounds(tl, phase, t);
        }
        if (s.particles()) {
            this.particles(tl, phase, t);
        }
    }

    private void sounds(final Timeline tl, final Phase phase, final long t) {
        final long sinceClose = t - tl.closeStart();
        switch (phase) {
            case CRACK -> {
                if (t == 0) {
                    this.play(Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.6f);
                    this.play(Sound.ENTITY_BLAZE_SHOOT, 0.6f, 0.5f);
                }
            }
            case RISE -> {
                if (t == Timeline.CRACK) {
                    this.play(Sound.BLOCK_END_PORTAL_SPAWN, 0.55f, 1.25f);
                    this.play(Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.8f);
                }
                if (t % 6 == 0) {
                    this.play(Sound.BLOCK_FIRE_AMBIENT, 0.8f, 0.7f);
                }
            }
            case OPEN -> {
                if (t == Timeline.CRACK + Timeline.RISE) {
                    this.play(Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.3f);
                    this.play(Sound.BLOCK_PORTAL_TRIGGER, 0.25f, 1.6f);
                    this.play(Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.8f, 1.2f);
                }
            }
            case IDLE -> {
                if (t % 31 == 0) {
                    this.play(Sound.BLOCK_FIRE_AMBIENT, 0.7f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.3f);
                }
                if (t % 90 == 0) {
                    this.play(Sound.BLOCK_PORTAL_AMBIENT, 0.18f, 1.4f);
                }
            }
            case CLOSE_SURFACE -> {
                if (sinceClose == 0) {
                    this.play(Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.1f);
                }
                if (sinceClose == Timeline.COLLAPSE) {
                    this.play(Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.9f);
                }
                if (sinceClose == Timeline.CLOSE_SURFACE - 1) {
                    this.play(Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.8f, 0.7f);
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.9f, 0.6f);
                }
            }
            case CLOSE_FRAME -> {
                if (sinceClose == Timeline.CLOSE_SURFACE) {
                    this.play(Sound.BLOCK_GRINDSTONE_USE, 0.35f, 0.5f);
                }
                if (sinceClose == Timeline.CLOSING - 1) {
                    this.play(Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 0.8f);
                }
            }
            default -> {
            }
        }
    }

    private void particles(final Timeline tl, final Phase phase, final long t) {
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (phase) {
            case CRACK -> {
                // Трещина: угли и огонь вдоль основания, тёмно-красная пыль.
                for (int i = 0; i < 3; i++) {
                    this.spawn(Particle.DUST, this.at(r.nextDouble(-1.6, 1.6), 0.05, r.nextDouble(-0.6, 0.6)), 1, 0.05, 0, 0.05, 0, DEEP_RED);
                }
                this.spawn(Particle.LAVA, this.at(r.nextDouble(-1.0, 1.0), 0.1, r.nextDouble(-0.3, 0.3)), 1, 0, 0, 0, 0, null);
                this.spawn(Particle.SMALL_FLAME, this.at(r.nextDouble(-1.2, 1.2), 0.05, r.nextDouble(-0.4, 0.4)), 2, 0.05, 0.02, 0.05, 0.01, null);
                this.spawn(Particle.SMOKE, this.at(r.nextDouble(-1.0, 1.0), 0.1, 0), 2, 0.2, 0.05, 0.2, 0.01, null);
            }
            case RISE -> {
                // Огонь лижет стойки по мере подъёма.
                final double h = tl.rise(t) * 3.0;
                for (final double side : new double[] {-SIDE, SIDE}) {
                    this.spawn(Particle.FLAME, this.at(side, r.nextDouble(0, Math.max(0.1, h)), r.nextDouble(-0.2, 0.2)), 1, 0.04, 0.05, 0.04, 0.01, null);
                    this.spawn(Particle.SMALL_FLAME, this.at(side + r.nextDouble(-0.15, 0.15), 0.05, r.nextDouble(-0.3, 0.3)), 1, 0, 0, 0, 0.02, null);
                }
                this.spawn(Particle.LARGE_SMOKE, this.at(r.nextDouble(-1.0, 1.0), 0.1, r.nextDouble(-0.3, 0.3)), 1, 0.05, 0, 0.05, 0.01, null);
                this.spawn(Particle.DUST, this.at(r.nextDouble(-1.0, 1.0), h, 0), 2, 0.1, 0.05, 0.1, 0, EMBER);
                if (t % 3 == 0) {
                    this.spawn(Particle.LAVA, this.at(r.nextDouble(-1.0, 1.0), 0.1, r.nextDouble(-0.3, 0.3)), 1, 0, 0, 0, 0, null);
                }
            }
            case OPEN -> {
                // Раскрытие: красная пыль от средней линии.
                final double w = tl.surface(t) * Spot.SURFACE_HALF_WIDTH;
                for (int i = 0; i < 4; i++) {
                    final double y = r.nextDouble(0.1, Spot.SURFACE_HEIGHT);
                    this.spawn(Particle.DUST_COLOR_TRANSITION, this.at(r.nextBoolean() ? w : -w, y, 0), 1, 0.02, 0.05, 0.02, 0, RED_TO_ORANGE);
                }
                if (t == Timeline.CRACK + Timeline.RISE) {
                    this.spawn(Particle.DUST, this.at(0, 1.45, 0), 30, 0.08, 1.2, 0.08, 0, RED);
                }
                this.ambientFire(r, t);
            }
            case IDLE -> {
                this.ambientFire(r, t);
                // Красные и оранжевые искры над поверхностью.
                if (t % 4 == 0) {
                    this.spawn(Particle.DUST, this.at(r.nextDouble(-1.05, 1.05), r.nextDouble(0.1, 2.8), r.nextDouble(-0.2, 0.2)), 1, 0, 0, 0, 0, r.nextBoolean() ? RED : ORANGE);
                }
            }
            case CLOSE_SURFACE -> {
                final long d = t - tl.closeStart();
                final double w = tl.surface(t) * Spot.SURFACE_HALF_WIDTH;
                final double hh = tl.surfaceHeight(t) * Spot.SURFACE_HEIGHT / 2;
                final double mid = Spot.SURFACE_HEIGHT / 2;
                if (d < Timeline.COLLAPSE) {
                    // Края поверхности сходятся к середине, за ними тянется красная пыль.
                    for (int i = 0; i < 2; i++) {
                        this.spawn(Particle.DUST_COLOR_TRANSITION, this.at(r.nextBoolean() ? w : -w, r.nextDouble(0.1, Spot.SURFACE_HEIGHT), 0), 1, 0.02, 0.04, 0.02, 0, RED_TO_ORANGE);
                    }
                } else {
                    // Светящаяся щель стягивается в точку.
                    this.spawn(Particle.DUST, this.at(0, mid + r.nextDouble(-hh, hh), 0), 2, 0.02, 0.02, 0.02, 0, RED);
                    if (d == Timeline.CLOSE_SURFACE - 1) {
                        // Схлопнулась: вспышка красной пыли и искр во все стороны.
                        this.spawn(Particle.DUST, this.at(0, mid, 0), 40, 0.35, 0.35, 0.2, 0, RED);
                        this.spawn(Particle.DUST, this.at(0, mid, 0), 20, 0.25, 0.25, 0.15, 0, ORANGE);
                        this.spawn(Particle.LAVA, this.at(0, mid, 0), 4, 0.1, 0.1, 0.1, 0, null);
                    }
                }
                this.ambientFire(r, t);
            }
            case CLOSE_FRAME -> {
                final double h = tl.rise(t) * 3.0;
                this.spawn(Particle.LARGE_SMOKE, this.at(r.nextDouble(-1.35, 1.35), 0.1, r.nextDouble(-0.3, 0.3)), 1, 0.05, 0.02, 0.05, 0.01, null);
                this.spawn(Particle.DUST, this.at(r.nextDouble(-1.0, 1.0), h, 0), 1, 0.1, 0.05, 0.1, 0, EMBER);
                this.spawn(Particle.SMALL_FLAME, this.at(r.nextDouble(-1.1, 1.1), 0.05, r.nextDouble(-0.3, 0.3)), 1, 0, 0, 0, 0.01, null);
            }
            default -> {
            }
        }
    }

    /** Огонь и угли у основания открытого портала. */
    private void ambientFire(final ThreadLocalRandom r, final long t) {
        if (t % 2 == 0) {
            this.spawn(Particle.SMALL_FLAME, this.at(r.nextDouble(-1.2, 1.2), 0.05, r.nextDouble(-0.45, 0.45)), 1, 0.02, 0.02, 0.02, 0.012, null);
        }
        if (t % 4 == 0) {
            final double side = r.nextBoolean() ? -SIDE : SIDE;
            this.spawn(Particle.FLAME, this.at(side, r.nextDouble(0.0, 0.9), r.nextDouble(-0.22, 0.22)), 1, 0.02, 0.04, 0.02, 0.008, null);
            this.spawn(Particle.DUST, this.at(r.nextDouble(-1.1, 1.1), r.nextDouble(0.05, 0.6), r.nextDouble(-0.4, 0.4)), 1, 0, 0, 0, 0, EMBER);
        }
        if (t % 17 == 0) {
            this.spawn(Particle.LAVA, this.at(r.nextDouble(-0.9, 0.9), 0.1, r.nextDouble(-0.3, 0.3)), 1, 0, 0, 0, 0, null);
        }
    }

    /** Вспышка у игрока, вошедшего в портал (его место — регион портала). */
    void enter(final Player player, final Settings s) {
        final Location eye = player.getEyeLocation();
        if (s.particles()) {
            if (Particle.FLASH.getDataType() == Color.class) {
                this.world.spawnParticle(Particle.FLASH, eye, 1, 0, 0, 0, 0, Color.fromRGB(255, 150, 90));
            } else if (Particle.FLASH.getDataType() == Void.class) {
                this.world.spawnParticle(Particle.FLASH, eye, 1, 0, 0, 0, 0);
            }
            this.world.spawnParticle(Particle.DUST, eye, 30, 0.3, 0.6, 0.3, 0, RED);
            this.world.spawnParticle(Particle.FLAME, player.getLocation(), 20, 0.3, 0.2, 0.3, 0.05);
            this.world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eye, 25, 0.4, 0.7, 0.4, 0, RED_TO_ORANGE);
        }
        if (s.sounds()) {
            this.world.playSound(eye, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 1.0f, 0.7f);
            this.world.playSound(eye, Sound.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 0.8f, 0.6f);
        }
    }

    private double[] at(final double side, final double up, final double depth) {
        return this.spot.toWorld(side, up, depth);
    }

    private <T> void spawn(final Particle particle, final double[] p, final int count, final double ox, final double oy,
                           final double oz, final double speed, final T data) {
        if (data == null) {
            this.world.spawnParticle(particle, p[0], p[1], p[2], count, ox, oy, oz, speed);
        } else {
            this.world.spawnParticle(particle, p[0], p[1], p[2], count, ox, oy, oz, speed, data);
        }
    }

    /** Одна частица с направлением: {@code count = 0} — смещения задают скорость. Глубина — по нормали портала. */
    private void spawnDirected(final Particle particle, final double[] p, final double side, final double up,
                               final double depth, final double speed) {
        final double[] o = this.spot.toWorld(side, up, depth);
        final double[] base = this.spot.toWorld(0, 0, 0);
        this.world.spawnParticle(particle, p[0], p[1], p[2], 0, o[0] - base[0], o[1] - base[1], o[2] - base[2], speed);
    }

    private void play(final Sound sound, final float volume, final float pitch) {
        final double[] p = this.at(0, 1.5, 0);
        this.world.playSound(new Location(this.world, p[0], p[1], p[2]), sound, SoundCategory.BLOCKS, volume, pitch);
    }
}
