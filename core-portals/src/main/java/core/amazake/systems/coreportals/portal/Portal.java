package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.PortalType;
import core.amazake.systems.coreportals.geo.Spot;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.UUID;
import org.bukkit.World;

/**
 * Один открытый портал. Поля без volatile меняет только поток региона портала (его задача, события его чанка);
 * другие потоки только ставят флаги {@link #closeRequested} и {@link #removed} и читают {@link #tick}.
 */
final class Portal {

    final UUID id = UUID.randomUUID();
    final UUID owner;
    final String ownerName;
    final World world;
    final Spot spot;
    final PortalType type;
    final PortalBody body;
    final int chunkX;
    final int chunkZ;

    volatile long tick;
    volatile ScheduledTask task;
    /** Попросили закрыть (команда, выключение по сроку): задача портала начнёт закрытие на ближайшем тике. */
    volatile boolean closeRequested;
    /** Убран: задача больше ничего не делает. */
    volatile boolean removed;

    Portal(final UUID owner, final String ownerName, final World world, final Spot spot, final PortalType type,
           final PortalBody body) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.spot = spot;
        this.type = type;
        this.body = body;
        this.chunkX = (int) Math.floor(spot.x()) >> 4;
        this.chunkZ = (int) Math.floor(spot.z()) >> 4;
    }
}
