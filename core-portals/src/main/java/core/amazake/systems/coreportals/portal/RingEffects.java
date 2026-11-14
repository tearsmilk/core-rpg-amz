package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.RingPhase;
import core.amazake.systems.coreportals.anim.RingTimeline;
import core.amazake.systems.coreportals.geo.RingPlacement;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Ванильные частицы и звуки изумрудного портала: только зелёные (DUST, DUST_COLOR_TRANSITION, GLOW, зелёная
 * вспышка FLASH). Поток региона портала.
 */
final class RingEffects {

    private static final Particle.DustOptions EMERALD = new Particle.DustOptions(Color.fromRGB(40, 255, 125), 1.0f);
    private static final Particle.DustOptions DEEP = new Particle.DustOptions(Color.fromRGB(8, 140, 62), 1.4f);
    private static final Particle.DustOptions MINT = new Particle.DustOptions(Color.fromRGB(160, 255, 195), 0.6f);
    private static final Particle.DustOptions SPARK = new Particle.DustOptions(Color.fromRGB(90, 255, 150), 0.45f);
    private static final Particle.DustTransition DARK_TO_EMERALD =
        new Particle.DustTransition(Color.fromRGB(4, 80, 36), Color.fromRGB(90, 255, 150), 1.1f);
    private static final Color FLASH_COLOR = Color.fromRGB(80, 255, 150);
    private static final double UP = RingPlacement.CENTER_UP;

    private final World world;
    private final Spot spot;

    RingEffects(final World world, final Spot spot) {
        this.world = world;
        this.spot = spot;
    }

    void tick(final RingTimeline tl, final long t, final Settings s) {
        final RingPhase phase = tl.phase(t);
        if (s.sounds()) {
            this.sounds(tl, phase, t);
        }
        if (s.particles()) {
            this.particles(tl, phase, t);
        }
    }

    private void sounds(final RingTimeline tl, final RingPhase phase, final long t) {
        final long d = t - tl.closeStart();
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (phase) {
            case SPARK -> {
                if (t == 0) {
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.6f);
                    this.play(Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.9f);
                }
            }
            case FORM -> {
                if (t == RingTimeline.SPARK) {
                    this.play(Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 0.9f);
                    this.play(Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 0.6f);
                }
                if (t == RingTimeline.OPENED - 6) {
                    this.play(Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.8f);
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.7f);
                }
            }
            case IDLE -> {
                if (t % 80 == 0) {
                    this.play(Sound.BLOCK_BEACON_AMBIENT, 0.8f, 0.7f);
                }
                if (t % 53 == 0) {
                    this.play(Sound.BLOCK_CONDUIT_AMBIENT_SHORT, 0.5f, 1.3f);
                }
                if (r.nextInt(45) == 0) {
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, 0.6f + r.nextFloat() * 0.8f);
                }
            }
            case COLLAPSE -> {
                if (d == 0) {
                    this.play(Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.9f);
                    this.play(Sound.BLOCK_CONDUIT_DEACTIVATE, 1.0f, 1.2f);
                }
                if (d == RingTimeline.CLOSING - RingTimeline.FLASH - 4) {
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.5f);
                    this.play(Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.7f, 1.6f);
                }
                if (d == RingTimeline.CLOSING - RingTimeline.FLASH + 3) {
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 0.6f);
                    this.play(Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.3f);
                }
            }
            default -> {
            }
        }
    }

    private void particles(final RingTimeline tl, final RingPhase phase, final long t) {
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (phase) {
            case SPARK -> {
                // Свет стягивается в точку: кольцо пыли сжимается к центру.
                final double rad = 1.1 * (1 - t / (double) RingTimeline.SPARK);
                for (int i = 0; i < 4; i++) {
                    final double a = r.nextDouble(Math.PI * 2);
                    this.spawn(Particle.DUST, this.ring(a, rad, r.nextDouble(-0.1, 0.1)), 1, 0, 0, 0, 0, EMERALD);
                }
                this.spawn(Particle.DUST, this.at(0, UP, 0), 1, 0.03, 0.03, 0.03, 0, MINT);
            }
            case FORM -> {
                if (t == RingTimeline.SPARK) {
                    this.spawn(Particle.DUST_COLOR_TRANSITION, this.at(0, UP, 0), 20, 0.15, 0.15, 0.1, 0, DARK_TO_EMERALD);
                }
                // Пыль бежит по растущему кольцу.
                final double rad = tl.ring(t) * RingPlacement.RADIUS * 0.9;
                for (int i = 0; i < 5; i++) {
                    final double a = t * 0.45 + i * (Math.PI * 2 / 5);
                    this.spawn(Particle.DUST, this.ring(a, rad, 0), 1, 0.02, 0.02, 0.02, 0, i % 2 == 0 ? EMERALD : DEEP);
                }
                if (t % 3 == 0) {
                    this.spawn(Particle.GLOW, this.ring(r.nextDouble(Math.PI * 2), rad, r.nextDouble(-0.2, 0.2)), 1, 0.05, 0.05, 0.05, 0, null);
                }
            }
            case IDLE -> {
                if (t % 2 == 0) {
                    for (int i = 0; i < 2; i++) {
                        this.spawn(Particle.DUST, this.ring(r.nextDouble(Math.PI * 2), r.nextDouble(1.3, 1.6), r.nextDouble(-0.12, 0.12)), 1, 0, 0, 0, 0, EMERALD);
                    }
                }
                if (t % 3 == 0) {
                    // Мелкие зелёные искры в туманности.
                    this.spawn(Particle.DUST, this.ring(r.nextDouble(Math.PI * 2), r.nextDouble(1.8, 2.9), r.nextDouble(-0.5, 0.5)), 1, 0, 0, 0, 0, SPARK);
                }
                if (t % 7 == 0) {
                    this.spawn(Particle.GLOW, this.ring(r.nextDouble(Math.PI * 2), r.nextDouble(1.4, 2.2), r.nextDouble(-0.3, 0.3)), 1, 0.05, 0.05, 0.05, 0, null);
                }
                if (t % 11 == 0) {
                    this.spawn(Particle.DUST_COLOR_TRANSITION, this.ring(r.nextDouble(Math.PI * 2), r.nextDouble(1.6, 2.4), r.nextDouble(-0.3, 0.3)), 1, 0.05, 0.05, 0.05, 0, DARK_TO_EMERALD);
                }
            }
            case COLLAPSE -> {
                final long d = t - tl.closeStart();
                // Пыль по спирали внутрь вслед за кольцом.
                final double rad = Math.max(0.05, tl.ring(t)) * RingPlacement.RADIUS;
                for (int i = 0; i < 6; i++) {
                    final double a = d * 0.9 + i * (Math.PI * 2 / 6);
                    this.spawn(Particle.DUST, this.ring(a, rad, 0), 1, 0.02, 0.02, 0.02, 0, i % 2 == 0 ? EMERALD : MINT);
                }
                if (d == RingTimeline.CLOSING - RingTimeline.FLASH + 3) {
                    this.flash(this.at(0, UP, 0));
                    this.spawn(Particle.DUST, this.at(0, UP, 0), 50, 0.6, 0.6, 0.3, 0, EMERALD);
                    this.spawn(Particle.DUST, this.at(0, UP, 0), 25, 1.0, 1.0, 0.5, 0, SPARK);
                    this.spawn(Particle.DUST_COLOR_TRANSITION, this.at(0, UP, 0), 30, 0.9, 0.9, 0.4, 0, DARK_TO_EMERALD);
                    this.spawn(Particle.GLOW, this.at(0, UP, 0), 14, 0.3, 0.3, 0.2, 0.15, null);
                }
            }
            default -> {
            }
        }
    }

    /** Вспышка у игрока, вошедшего в портал (его место — регион портала). */
    void enter(final Player player, final Settings s) {
        final Location eye = player.getEyeLocation();
        if (s.particles()) {
            this.flash(new double[] {eye.getX(), eye.getY(), eye.getZ()});
            this.world.spawnParticle(Particle.DUST, eye, 30, 0.3, 0.6, 0.3, 0, EMERALD);
            this.world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eye, 25, 0.4, 0.7, 0.4, 0, DARK_TO_EMERALD);
            this.world.spawnParticle(Particle.GLOW, player.getLocation(), 12, 0.3, 0.5, 0.3, 0.05);
        }
        if (s.sounds()) {
            this.world.playSound(eye, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 1.0f, 0.8f);
            this.world.playSound(eye, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0f, 1.2f);
            this.world.playSound(eye, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 0.6f, 1.6f);
        }
    }

    private void flash(final double[] p) {
        if (Particle.FLASH.getDataType() == Color.class) {
            this.world.spawnParticle(Particle.FLASH, p[0], p[1], p[2], 1, 0, 0, 0, 0, FLASH_COLOR);
        } else if (Particle.FLASH.getDataType() == Void.class) {
            this.world.spawnParticle(Particle.FLASH, p[0], p[1], p[2], 1, 0, 0, 0, 0);
        }
    }

    /** Точка в плоскости кольца: угол {@code a}, радиус {@code rad} от центра, {@code depth} по нормали. */
    private double[] ring(final double a, final double rad, final double depth) {
        return this.at(Math.cos(a) * rad, UP + Math.sin(a) * rad, depth);
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

    private void play(final Sound sound, final float volume, final float pitch) {
        final double[] p = this.at(0, UP, 0);
        this.world.playSound(new Location(this.world, p[0], p[1], p[2]), sound, SoundCategory.BLOCKS, volume, pitch);
    }
}
