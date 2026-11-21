package ink.hoka.odyssey.gravity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.utils.WeightedList;

/** Реализация {@link VacuumService}: режимы игроков поверх карт. Живёт, пока включён модуль gravity. */
final class VacuumOverrides implements VacuumService {
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();
    /** Игроки, которых gravity держит в вакууме (та же карта, что в GravityModule). */
    private final Map<UUID, Floater> floaters;

    VacuumOverrides(Map<UUID, Floater> floaters) {
        this.floaters = floaters;
    }

    @Override
    public void override(Player p, Mode mode) {
        if (mode == null || mode == Mode.MAP) {
            modes.remove(p.getUuid());
        } else {
            modes.put(p.getUuid(), mode);
        }
    }

    @Override
    public Mode override(Player p) {
        return modes.getOrDefault(p.getUuid(), Mode.MAP);
    }

    @Override
    public boolean inVacuum(Player p) {
        return floaters.containsKey(p.getUuid());
    }

    @Override
    public void push(Player p, double vx, double vy, double vz) {
        Floater f = floaters.get(p.getUuid());
        if (f != null) {
            f.vy += vy;                                 // модель вертикали знает про толчок и дальше гасит его сама
        }
        Pos pos = p.getPosition();                      // «пустой» взрыв далеко под игроком, как в GravityModule.push
        p.sendPacket(new ExplosionPacket(new Vec(pos.x(), pos.y() - 4096, pos.z()), 0f, 0,
            new Vec(vx, vy, vz), Particle.BUBBLE, SoundEvent.INTENTIONALLY_EMPTY, WeightedList.of()));
    }

    /** Решение для игрока: true/false — задано сервисом, null — решает карта. */
    Boolean decide(Player p) {
        Mode m = modes.get(p.getUuid());
        return m == null ? null : m == Mode.VACUUM;
    }

    void forget(UUID id) {
        modes.remove(id);
    }

    void clear() {
        modes.clear();
    }
}
