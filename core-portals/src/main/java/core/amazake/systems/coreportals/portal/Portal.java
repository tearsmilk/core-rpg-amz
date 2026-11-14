package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.anim.Timeline;
import core.amazake.systems.coreportals.geo.Spot;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.UUID;
import org.bukkit.World;

/**
 * Один открытый портал. Поля без volatile меняет только поток региона портала (его задача, события его чанка);
 * другие потоки только ставят флаги {@link #closeRequested} и {@link #removed}.
 */
final class Portal {

    final UUID id = UUID.randomUUID();
    final UUID owner;
    final String ownerName;
    final World world;
    final Spot spot;
    final int chunkX;
    final int chunkZ;
    final PortalVisuals visuals = new PortalVisuals();
    final PortalEffects effects;

    Timeline timeline;
    long tick;
    volatile ScheduledTask task;
    /** Попросили закрыть (команда, выключение по сроку): задача портала начнёт закрытие на ближайшем тике. */
    volatile boolean closeRequested;
    /** Убран: задача больше ничего не делает. */
    volatile boolean removed;

    Portal(final UUID owner, final String ownerName, final World world, final Spot spot, final Timeline timeline) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.spot = spot;
        this.chunkX = (int) Math.floor(spot.x()) >> 4;
        this.chunkZ = (int) Math.floor(spot.z()) >> 4;
        this.timeline = timeline;
        this.effects = new PortalEffects(world, spot);
    }
}
