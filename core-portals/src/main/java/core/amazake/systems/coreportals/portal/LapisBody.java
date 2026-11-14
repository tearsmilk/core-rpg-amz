package core.amazake.systems.coreportals.portal;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.RiftPhase;
import core.amazake.systems.coreportals.anim.RiftTimeline;
import core.amazake.systems.coreportals.geo.RiftPlacement;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * «Лазуритовый портал»: большой разлом ({@link RiftTimeline}, {@link RiftVisuals}, {@link RiftEffects}) и аура для
 * игроков рядом — свечение и трещины на экране, затягивание ({@link RiftAura}).
 */
final class LapisBody implements PortalBody {

    private final World world;
    private final Spot spot;
    private final RiftPlacement.Size size;
    private final RiftVisuals visuals = new RiftVisuals();
    private final RiftEffects effects;
    private final RiftAura aura;
    private volatile RiftTimeline timeline;

    LapisBody(final SystemContext context, final World world, final Spot spot, final RiftPlacement.Size size,
              final RiftTimeline timeline, final String instance, final ConcurrentMap<UUID, Object> screenOwners) {
        this.world = world;
        this.spot = spot;
        this.size = size;
        this.timeline = timeline;
        this.effects = new RiftEffects(world, spot, size);
        this.aura = new RiftAura(context, world, spot, size, instance, screenOwners);
    }

    @Override
    public void spawn(final Settings s, final String instance) {
        this.visuals.spawn(this.world, this.spot, this.size, s, this.timeline, instance);
        this.effects.tick(this.timeline, 0, s);
    }

    @Override
    public boolean valid() {
        return this.visuals.valid();
    }

    @Override
    public void closeAt(final long t) {
        this.timeline = this.timeline.closeAt(t);
    }

    @Override
    public boolean done(final long t) {
        return this.timeline.phase(t) == RiftPhase.DONE;
    }

    @Override
    public boolean enterable(final long t) {
        return this.timeline.enterable(t);
    }

    @Override
    public String stage(final long t) {
        return this.timeline.phase(t).name();
    }

    @Override
    public void tick(final long t, final Settings s) {
        final RiftTimeline tl = this.timeline;
        this.visuals.apply(tl, t);
        this.effects.tick(tl, t, s);
        this.aura.tick(t, s, tl);
    }

    @Override
    public boolean touches(final BoundingBox bb) {
        return RiftPlacement.touches(this.spot, this.size, bb.getMinX(), bb.getMinY(), bb.getMinZ(),
            bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
    }

    @Override
    public double[] center() {
        return this.spot.toWorld(0, this.size.centerUp(), 0);
    }

    @Override
    public double scanRadius() {
        return Math.max(this.size.halfWidth(), this.size.halfHeight()) + 1.5;
    }

    @Override
    public void enter(final Player player, final Settings s) {
        this.effects.enter(player, s);
    }

    @Override
    public void remove() {
        this.aura.removeAll();
        this.visuals.remove();
    }
}
