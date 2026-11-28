package ink.hoka.odyssey.guns;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;

/**
 * Эффекты для всех: частицы (видны и без мода), звук выстрела из точки другим игрокам (hosounds), трассеры и искры
 * для игроков с клиентским модулем guns.
 */
final class Effects {
    private static final double PARTICLE_RANGE = 64;

    private final GunsModule mod;

    Effects(GunsModule mod) {
        this.mod = mod;
    }

    private static boolean near(Player v, double x, double y, double z, double r) {
        Pos p = v.getPosition();
        double dx = p.x() - x;
        double dy = p.y() - y;
        double dz = p.z() - z;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** Крошка блока в точке попадания. */
    void blockHit(Instance inst, Block block, double x, double y, double z, double nx, double ny, double nz) {
        blockHit(inst, block, x, y, z, nx, ny, nz, false);
    }

    /** То же; plasma — плазменный заряд: вместо части крошки — голубые искры (видны и без мода). */
    void blockHit(Instance inst, Block block, double x, double y, double z, double nx, double ny, double nz,
                  boolean plasma) {
        blockHit(inst, block, x, y, z, nx, ny, nz, plasma, 0);
    }

    /** То же; rgb — цвет трассера (у клиента — цвет свечения подпалины плазмы). */
    void blockHit(Instance inst, Block block, double x, double y, double z, double nx, double ny, double nz,
                  boolean plasma, int rgb) {
        if (plasma) {
            sparks(inst, x + nx * 0.08, y + ny * 0.08, z + nz * 0.08);
        }
        if (mod.particles() && block != null && !block.isAir()) {
            ParticlePacket pk = new ParticlePacket(Particle.BLOCK.withBlock(block), x + nx * 0.05, y + ny * 0.05,
                    z + nz * 0.05, 0.08f, 0.08f, 0.08f, 0.15f, 6);
            for (Player v : inst.getPlayers()) {
                if (near(v, x, y, z, PARTICLE_RANGE)) {
                    v.sendPacket(pk);
                }
            }
        }
        impact(inst, x, y, z, nx, ny, nz, 0, plasma ? Proto.IMPACT_PLASMA : 0, rgb);
    }

    /** Попадание в цель. */
    void entityHit(Instance inst, double x, double y, double z, double nx, double ny, double nz, boolean head) {
        entityHit(inst, x, y, z, nx, ny, nz, head, false);
    }

    /** То же; plasma — ещё голубые искры. */
    void entityHit(Instance inst, double x, double y, double z, double nx, double ny, double nz, boolean head,
                   boolean plasma) {
        if (plasma) {
            sparks(inst, x, y, z);
        }
        if (mod.particles()) {
            ParticlePacket pk = new ParticlePacket(Particle.DAMAGE_INDICATOR, x, y, z, 0.1f, 0.1f, 0.1f, 0.1f, head ? 3 : 1);
            for (Player v : inst.getPlayers()) {
                if (near(v, x, y, z, PARTICLE_RANGE)) {
                    v.sendPacket(pk);
                }
            }
        }
        impact(inst, x, y, z, nx, ny, nz, head ? 2 : 1, plasma ? Proto.IMPACT_PLASMA : 0, 0);
    }

    /** Голубые искры плазмы (частицы игры electric_spark и немного «души» огня) всем рядом. */
    private void sparks(Instance inst, double x, double y, double z) {
        if (!mod.particles()) {
            return;
        }
        ParticlePacket spark = new ParticlePacket(Particle.ELECTRIC_SPARK, x, y, z, 0.06f, 0.06f, 0.06f, 0.35f, 7);
        ParticlePacket glow = new ParticlePacket(Particle.SOUL_FIRE_FLAME, x, y, z, 0.03f, 0.03f, 0.03f, 0.02f, 2);
        for (Player v : inst.getPlayers()) {
            if (near(v, x, y, z, PARTICLE_RANGE)) {
                v.sendPacket(spark);
                v.sendPacket(glow);
            }
        }
    }

    private void impact(Instance inst, double x, double y, double z, double nx, double ny, double nz, int kind,
                        int flags, int rgb) {
        byte[] msg = null;
        for (Player v : inst.getPlayers()) {
            if (mod.modded(v) && near(v, x, y, z, mod.tracerRange())) {
                if (msg == null) {
                    msg = Proto.impact(x, y, z, nx, ny, nz, kind, flags, rgb);
                }
                mod.links().send(v, msg);
            }
        }
    }

    /**
     * Трассер (кроме стрелка — он рисует свой сразу) и (sound) звук выстрела остальным. У дроби трассер — на каждую
     * пулю, звук — один на выстрел.
     */
    void shot(Player shooter, GunDef g, double x, double y, double z, double vx, double vy, double vz, double life,
              boolean vacuum, boolean sound) {
        shot(shooter, g, x, y, z, vx, vy, vz, life, vacuum, sound, false, 0);
    }

    /**
     * То же с глушителем (silenced): у других нет вспышки (флаг TRACER), звук — sounds.shoot3p оружия с обвесом
     * (уже «с глушителем»), слышно на soundAdd блоков меньше/больше sound-range.
     */
    void shot(Player shooter, GunDef g, double x, double y, double z, double vx, double vy, double vz, double life,
              boolean vacuum, boolean sound, boolean silenced, double soundAdd) {
        Instance inst = shooter.getInstance();
        if (inst == null) {
            return;
        }
        byte[] tracer = null;
        String s3p = g.sound("shoot3p");
        boolean othersHear = sound && (!vacuum || "all".equals(mod.vacuumSound()));
        Vec at = new Vec(x, y, z);
        for (Player v : inst.getPlayers()) {
            if (v == shooter) {
                continue;
            }
            if (mod.modded(v) && near(v, x, y, z, mod.tracerRange())) {
                if (tracer == null) {
                    tracer = Proto.tracer(shooter.getEntityId(), x, y, z, vx, vy, vz, life, g.tracerColor(),
                            (vacuum ? Proto.TRACER_VACUUM : 0) | (silenced ? Proto.TRACER_SILENCED : 0)
                                    | (g.plasma() ? Proto.TRACER_PLASMA : 0), g.tracerLength());
                }
                mod.links().send(v, tracer);
            }
            if (othersHear && near(v, x, y, z, Math.max(4, mod.soundRange() + soundAdd))) {
                mod.links().play(v, s3p, at, silenced ? 0.8f : 1f, 0.95f + (float) Math.random() * 0.1f);
            }
        }
        // стрелок без клиентского модуля слышит свой выстрел от сервера (с модулем — играет сам, без задержки)
        if (sound && !mod.modded(shooter) && !(vacuum && "none".equals(mod.vacuumSound()))) {
            mod.links().play(shooter, g.sound("shoot"), at, vacuum ? 0.35f : 1f, vacuum ? 0.7f : 1f);
        }
    }

    /** Взрыв (фугасные патроны): частица и звук всем рядом. */
    void explosion(Instance inst, double x, double y, double z) {
        ParticlePacket pk = new ParticlePacket(Particle.EXPLOSION, x, y, z, 0.1f, 0.1f, 0.1f, 0f, 1);
        net.kyori.adventure.sound.Sound snd = net.kyori.adventure.sound.Sound.sound(
                net.kyori.adventure.key.Key.key("minecraft", "entity.generic.explode"),
                net.kyori.adventure.sound.Sound.Source.PLAYER, 0.6f, 1.2f);
        for (Player v : inst.getPlayers()) {
            if (near(v, x, y, z, PARTICLE_RANGE)) {
                if (mod.particles()) {
                    v.sendPacket(pk);
                }
                v.playSound(snd, x, y, z);
            }
        }
    }

    /** Звук остальным рядом со стрелком (перезарядка). */
    void others(Player shooter, String id, float volume) {
        Instance inst = shooter.getInstance();
        if (inst == null || id == null || id.isEmpty()) {
            return;
        }
        Pos p = shooter.getPosition();
        for (Player v : inst.getPlayers()) {
            if (v != shooter && near(v, p.x(), p.y(), p.z(), 24)) {
                mod.links().play(v, id, p.add(0, shooter.getEyeHeight(), 0), volume, 1f);
            }
        }
    }
}
