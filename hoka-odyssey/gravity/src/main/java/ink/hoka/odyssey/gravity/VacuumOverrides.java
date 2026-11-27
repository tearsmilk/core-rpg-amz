package ink.hoka.odyssey.gravity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.utils.WeightedList;

/** Реализация {@link VacuumService}: режимы игроков поверх карт. Живёт, пока включён модуль gravity. */
final class VacuumOverrides implements VacuumService {
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();
    /** Игроки, которых gravity держит в вакууме (та же карта, что в GravityModule). */
    private final Map<UUID, Floater> floaters;
    /** Источник зон для предметов (gravzones) или null. */
    private volatile ZoneProvider zoneProvider;

    /** Игроки с настоящей физикой: толчок — к их настоящей скорости (сервер шлёт её клиенту сам). */
    private final Map<UUID, Spacer> spacers;

    /** Без игроков с настоящей физикой (для тестов). */
    VacuumOverrides(Map<UUID, Floater> floaters) {
        this(floaters, new ConcurrentHashMap<>());
    }

    VacuumOverrides(Map<UUID, Floater> floaters, Map<UUID, Spacer> spacers) {
        this.floaters = floaters;
        this.spacers = spacers;
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
        Spacer sp = spacers.get(p.getUuid());
        if (sp != null && !sp.modded) {
            sp.push(vx, vy, vz);                        // уйдёт клиенту в ближайшем тике вместе со скоростью
            return;
        }
        if (sp != null) {                               // мод spaceflight: толчок придёт взрывом — допустить его в проверке
            double dv = Math.sqrt(vx * vx + vy * vy + vz * vz);  // блоков/тик
            sp.guard.speed += dv * Settings.TPS;
            sp.guard.credit += dv * Inertia.MoveGuard.BURST;
        }
        Floater f = floaters.get(p.getUuid());
        if (f != null) {
            f.vy += vy;                                 // модель вертикали знает про толчок и дальше гасит его сама
        }
        Pos pos = p.getPosition();                      // «пустой» взрыв далеко под игроком, как в GravityModule.push
        p.sendPacket(new ExplosionPacket(new Vec(pos.x(), pos.y() - 4096, pos.z()), 0f, 0,
            new Vec(vx, vy, vz), Particle.BUBBLE, SoundEvent.INTENTIONALLY_EMPTY, WeightedList.of()));
    }

    /** Стабилизатор настоящей физики, блоки/с² (для отдачи игроку со spaceflight без мода guns). */
    volatile double stabilizer = RealPhysics.STABILIZER;

    @Override
    public void recoil(Player p, double dx, double dy, double dz, boolean clientSide) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(d > 0) || !Double.isFinite(d) || p.getVehicle() != null || p.isFlying()) {
            return;
        }
        Spacer sp = spacers.get(p.getUuid());
        if (sp != null) {
            if (sp.modded) {
                if (!clientSide) {                      // (с модом guns путь уже допустил expectRecoil)
                    sp.guard.recoil(d);
                    // spaceflight есть, а guns у клиента нет: толчок станет скоростью полёта, без клавиш её гасит
                    // стабилизатор — путь v²/(2a) = d
                    double v = Math.sqrt(2 * Math.max(0.1, stabilizer) * d) / Settings.TPS / d;
                    explosion(p, dx * v, dy * v, dz * v);
                }
                return;
            }
            if (!sp.grounded && !p.isOnGround()) {      // свободный полёт; касание блока проверит тик gravity
                sp.recoil.add(dx, dy, dz);
            }
            return;
        }
        Floater f = floaters.get(p.getUuid());
        if (f == null || p.isOnGround()) {
            return;
        }
        // старая физика: толчок взрывом, клиент гасит его сам (горизонталь ×0,91, вертикаль ×0,98 за тик) — путь
        // v/(1 − затухание)
        double vy = dy * (1 - Inertia.CLIENT_V_DRAG);
        f.vy += vy;
        explosion(p, dx * (1 - Inertia.CLIENT_H_DRAG), vy, dz * (1 - Inertia.CLIENT_H_DRAG));
    }

    @Override
    public void expectRecoil(Player p, double distance) {
        Spacer sp = spacers.get(p.getUuid());
        if (sp != null && sp.modded) {
            sp.guard.recoil(distance);                  // клиент отнесёт себя сам — допустить этот путь в проверке
        }
    }

    private static void explosion(Player p, double vx, double vy, double vz) {
        Pos pos = p.getPosition();
        p.sendPacket(new ExplosionPacket(new Vec(pos.x(), pos.y() - 4096, pos.z()), 0f, 0,
            new Vec(vx, vy, vz), Particle.BUBBLE, SoundEvent.INTENTIONALLY_EMPTY, WeightedList.of()));
    }

    @Override
    public synchronized void zoneProvider(ZoneProvider provider) {
        zoneProvider = provider;
    }

    @Override
    public synchronized void removeZoneProvider(ZoneProvider provider) {
        if (zoneProvider == provider) {
            zoneProvider = null;
        }
    }

    /** Текущий источник зон (null — нет). */
    ZoneProvider zoneProvider() {
        return zoneProvider;
    }

    /** Зоны карты для предметов; null — зон нет или источника нет. */
    ZoneLookup zones(Instance instance) {
        ZoneProvider zp = zoneProvider;
        return zp == null || instance == null ? null : zp.zones(instance);
    }

    /** Сообщить источнику зон, что предмет отпущен в зоне NORMAL. */
    void released(ItemEntity item) {
        ZoneProvider zp = zoneProvider;
        if (zp != null) {
            zp.released(item);
        }
    }

    /** Режим зоны в точке (MAP, если зон нет). */
    static Mode modeAt(ZoneLookup zones, double x, double y, double z) {
        if (zones == null) {
            return Mode.MAP;
        }
        Mode m = zones.modeAt(x, y, z);
        return m == null ? Mode.MAP : m;
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
        zoneProvider = null;
    }
}
