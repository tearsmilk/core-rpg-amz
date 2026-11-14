package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.RiftPhase;
import core.amazake.systems.coreportals.anim.RiftTimeline;
import core.amazake.systems.coreportals.geo.RiftPlacement;
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
 * Ванильные частицы и звуки лазуритового разлома: только синие и белые (DUST, DUST_COLOR_TRANSITION, ELECTRIC_SPARK
 * на трещине, END_ROD — светлые штрихи, которые затягивает внутрь, синяя вспышка FLASH). Поток региона разлома.
 */
final class RiftEffects {

    private static final Particle.DustOptions BLUE = new Particle.DustOptions(Color.fromRGB(26, 76, 255), 1.1f);
    private static final Particle.DustOptions ICE = new Particle.DustOptions(Color.fromRGB(159, 232, 255), 0.7f);
    private static final Particle.DustOptions DEEP = new Particle.DustOptions(Color.fromRGB(5, 10, 42), 1.5f);
    private static final Particle.DustTransition NAVY_TO_ICE =
        new Particle.DustTransition(Color.fromRGB(10, 30, 120), Color.fromRGB(159, 232, 255), 1.2f);
    private static final Color FLASH_COLOR = Color.fromRGB(110, 170, 255);

    private final World world;
    private final Spot spot;
    private final RiftPlacement.Size size;

    RiftEffects(final World world, final Spot spot, final RiftPlacement.Size size) {
        this.world = world;
        this.spot = spot;
        this.size = size;
    }

    void tick(final RiftTimeline tl, final long t, final Settings s) {
        final RiftPhase phase = tl.phase(t);
        if (s.sounds()) {
            this.sounds(tl, phase, t);
        }
        if (s.particles()) {
            this.particles(tl, phase, t);
        }
    }

    private void sounds(final RiftTimeline tl, final RiftPhase phase, final long t) {
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        final long d = t - tl.closeStart();
        switch (phase) {
            case CRACK -> {
                if (t == 0) {
                    this.play(Sound.BLOCK_GLASS_BREAK, 0.9f, 0.5f);
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.6f);
                }
                if (t % 3 == 1) {
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 0.5f + r.nextFloat() * 0.4f);
                }
            }
            case SPLIT -> {
                if (t == RiftTimeline.CRACK) {
                    this.play(Sound.BLOCK_GLASS_BREAK, 1.0f, 0.7f);
                    this.play(Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.6f);
                }
            }
            case TEAR -> {
                if (t == RiftTimeline.CRACK + RiftTimeline.SPLIT) {
                    this.play(Sound.ITEM_TRIDENT_RIPTIDE_3, 1.0f, 0.6f);
                    this.play(Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.55f);
                    this.play(Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.5f);
                }
                if (t == RiftTimeline.OPENED - 8) {
                    this.play(Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 0.7f);
                }
            }
            case IDLE -> {
                if (t % 100 == 0) {
                    this.play(Sound.BLOCK_PORTAL_AMBIENT, 0.5f, 0.55f);
                }
                if (t % 47 == 0) {
                    this.play(Sound.BLOCK_CONDUIT_AMBIENT, 0.8f, 0.6f);
                }
                if (r.nextInt(50) == 0) {
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 0.5f + r.nextFloat() * 0.5f);
                }
            }
            case SEAL -> {
                if (d == 0) {
                    this.play(Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.6f);
                    this.play(Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.9f, 0.7f);
                }
                if (d == RiftTimeline.SEAL) {
                    this.play(Sound.BLOCK_GLASS_BREAK, 0.8f, 1.2f);
                }
                if (d == RiftTimeline.CLOSING - 8) {
                    this.play(Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 0.6f);
                    this.play(Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 0.8f);
                }
            }
            default -> {
            }
        }
    }

    private void particles(final RiftTimeline tl, final RiftPhase phase, final long t) {
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        final double tear = tl.tear(t);
        final double open = tl.openness(t);
        switch (phase) {
            case CRACK, SPLIT -> {
                // Искры по трещине (её длина — tear / P_CRACK от полувысоты).
                final double len = Math.min(1, tear / RiftTimeline.P_CRACK) * this.size.halfHeight();
                for (int i = 0; i < 3; i++) {
                    final double y = r.nextDouble(-len, len + 1e-6);
                    this.spawn(Particle.ELECTRIC_SPARK, this.local(r.nextDouble(-0.08, 0.08), y, 0), 1, 0.02, 0.02,
                        0.02, 0.02, null);
                }
                if (t % 2 == 0) {
                    this.spawn(Particle.DUST, this.local(0, r.nextDouble(-len, len + 1e-6), r.nextDouble(-0.1, 0.1)), 1,
                        0.05, 0.05, 0.05, 0, ICE);
                }
                if (phase == RiftPhase.SPLIT && t % 3 == 0) {
                    this.spawn(Particle.END_ROD, this.local(0, r.nextDouble(-len, len + 1e-6), 0), 1, 0.05, 0.1, 0.05,
                        0.03, null);
                }
            }
            case TEAR -> {
                if (t == RiftTimeline.CRACK + RiftTimeline.SPLIT) {
                    this.spawn(Particle.DUST_COLOR_TRANSITION, this.local(0, 0, 0), 30, 0.2,
                        this.size.halfHeight() * 0.6, 0.1, 0, NAVY_TO_ICE);
                }
                // Пыль бежит по расходящимся краям, светлые штрихи летят от края наружу.
                for (int i = 0; i < 6; i++) {
                    final double a = r.nextDouble(Math.PI * 2);
                    this.spawn(Particle.DUST, this.tearRim(a, open, r.nextDouble(-0.1, 0.1)), 1, 0.03, 0.03, 0.03, 0,
                        i % 2 == 0 ? BLUE : ICE);
                }
                if (t % 2 == 0) {
                    final double a = r.nextDouble(Math.PI * 2);
                    final double[] p = this.tearRim(a, open, 0);
                    final double[] o = this.rimDir(a);
                    this.spawn(Particle.END_ROD, p, 0, o[0], o[1], o[2], 0.12, null);
                }
            }
            case IDLE -> {
                for (int i = 0; i < 2; i++) {
                    this.spawn(Particle.DUST, this.rim(r.nextDouble(Math.PI * 2), 1.0 + r.nextDouble(-0.04, 0.06),
                        r.nextDouble(-0.12, 0.12)), 1, 0, 0, 0, 0, i == 0 ? BLUE : ICE);
                }
                if (t % 3 == 0) {
                    // Светлые штрихи затягивает внутрь, к чёрной середине.
                    final double a = r.nextDouble(Math.PI * 2);
                    final double k = 1.25 + r.nextDouble(0.6);
                    final double[] p = this.rim(a, k, r.nextDouble(-0.6, -0.1));
                    final double[] c = this.local(0, 0, 0);
                    this.spawn(Particle.END_ROD, p, 0, c[0] - p[0], c[1] - p[1], c[2] - p[2], 0.06, null);
                }
                if (t % 5 == 0) {
                    this.spawn(Particle.DUST_COLOR_TRANSITION, this.rim(r.nextDouble(Math.PI * 2),
                        1.1 + r.nextDouble(0.5), r.nextDouble(-0.4, 0.2)), 1, 0.05, 0.05, 0.05, 0, NAVY_TO_ICE);
                }
                if (t % 9 == 0) {
                    this.spawn(Particle.DUST, this.local(r.nextDouble(-0.4, 0.4), r.nextDouble(-0.6, 0.6), 0), 1, 0.1,
                        0.1, 0.05, 0, DEEP);
                }
            }
            case SEAL -> {
                final long d = t - tl.closeStart();
                if (d < RiftTimeline.SEAL) {
                    for (int i = 0; i < 5; i++) {
                        this.spawn(Particle.DUST, this.tearRim(r.nextDouble(Math.PI * 2), Math.max(0.03, open), 0), 1, 0.02,
                            0.02, 0.02, 0, i % 2 == 0 ? BLUE : ICE);
                    }
                } else {
                    final double len = Math.min(1, tear / RiftTimeline.P_CRACK) * this.size.halfHeight();
                    this.spawn(Particle.ELECTRIC_SPARK, this.local(0, r.nextDouble(-len, len + 1e-6), 0), 2, 0.03, 0.05,
                        0.03, 0.02, null);
                }
                if (d == RiftTimeline.CLOSING - 8) {
                    final double[] c = this.local(0, 0, 0);
                    this.flash(c);
                    this.spawn(Particle.DUST, c, 50, 0.4, this.size.halfHeight() * 0.4, 0.3, 0, BLUE);
                    this.spawn(Particle.DUST, c, 25, 0.6, this.size.halfHeight() * 0.5, 0.4, 0, ICE);
                    this.spawn(Particle.END_ROD, c, 20, 0.2, 0.6, 0.2, 0.12, null);
                }
            }
            default -> {
            }
        }
    }

    /** Вспышка у вошедшего в разлом (его место — регион разлома). */
    void enter(final Player player, final Settings s) {
        final Location eye = player.getEyeLocation();
        if (s.particles()) {
            this.flash(new double[] {eye.getX(), eye.getY(), eye.getZ()});
            this.world.spawnParticle(Particle.DUST, eye, 30, 0.3, 0.6, 0.3, 0, BLUE);
            this.world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eye, 25, 0.4, 0.7, 0.4, 0, NAVY_TO_ICE);
            this.world.spawnParticle(Particle.END_ROD, eye, 12, 0.3, 0.5, 0.3, 0.05);
        }
        if (s.sounds()) {
            this.world.playSound(eye, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 1.0f, 0.7f);
            this.world.playSound(eye, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0f, 0.8f);
            this.world.playSound(eye, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 0.6f, 1.2f);
        }
    }

    private void flash(final double[] p) {
        if (Particle.FLASH.getDataType() == Color.class) {
            this.world.spawnParticle(Particle.FLASH, p[0], p[1], p[2], 1, 0, 0, 0, 0, FLASH_COLOR);
        } else if (Particle.FLASH.getDataType() == Void.class) {
            this.world.spawnParticle(Particle.FLASH, p[0], p[1], p[2], 1, 0, 0, 0, 0);
        }
    }

    /** Точка на краю овала: угол {@code a}, {@code k} — доля полуосей (по ширине — ещё × раскрытость). */
    private double[] rim(final double a, final double k, final double depth) {
        return this.local(Math.cos(a) * this.size.halfWidth() * k, Math.sin(a) * this.size.halfHeight() * k, depth);
    }

    /** Точка на краю разрыва: по высоте — весь овал, по ширине — доля {@code open}. */
    private double[] tearRim(final double a, final double open, final double depth) {
        return this.local(Math.cos(a) * this.size.halfWidth() * open, Math.sin(a) * this.size.halfHeight(), depth);
    }

    /** Направление наружу от края (в мире). */
    private double[] rimDir(final double a) {
        final double[] o = this.local(Math.cos(a), Math.sin(a), 0);
        final double[] c = this.local(0, 0, 0);
        return new double[] {o[0] - c[0], o[1] - c[1], o[2] - c[2]};
    }

    /** Точка по координатам от центра разлома: вбок, вверх, по нормали. */
    private double[] local(final double side, final double up, final double depth) {
        return this.spot.toWorld(side, this.size.centerUp() + up, depth);
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
        final double[] p = this.local(0, 0, 0);
        this.world.playSound(new Location(this.world, p[0], p[1], p[2]), sound, SoundCategory.BLOCKS, volume, pitch);
    }
}
