package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.RingPhase;
import core.amazake.systems.coreportals.anim.RingTimeline;
import core.amazake.systems.coreportals.geo.RingPlacement;
import core.amazake.systems.coreportals.geo.Spot;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/** «Изумрудный портал»: кольцо-воронка ({@link RingTimeline}, {@link RingVisuals}, {@link RingEffects}). */
final class RingBody implements PortalBody {

    private final World world;
    private final Spot spot;
    private final RingVisuals visuals = new RingVisuals();
    private final RingEffects effects;
    private volatile RingTimeline timeline;

    RingBody(final World world, final Spot spot, final RingTimeline timeline) {
        this.world = world;
        this.spot = spot;
        this.timeline = timeline;
        this.effects = new RingEffects(world, spot);
    }

    @Override
    public void spawn(final Settings s, final String instance) {
        this.visuals.spawn(this.world, this.spot, s, this.timeline, instance);
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
        return this.timeline.phase(t) == RingPhase.DONE;
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
        final RingTimeline tl = this.timeline;
        if (tl.phase(t) != RingPhase.IDLE) {
            if (t % RingTimeline.KEY_STEP == 0) {
                this.visuals.apply(tl, t + RingTimeline.KEY_STEP, RingTimeline.KEY_STEP + 1, true);
            }
        } else if (t % RingTimeline.IDLE_STEP == 0) {
            // Открыт: кольцо крутит шейдер, сервер двигает осколки (и первые тики доводит туманность до конца).
            this.visuals.apply(tl, t + RingTimeline.IDLE_STEP, RingTimeline.IDLE_STEP + 1,
                t < RingTimeline.OPENED + RingTimeline.SETTLE);
        }
        this.effects.tick(tl, t, s);
    }

    @Override
    public boolean touches(final BoundingBox bb) {
        return RingPlacement.touches(this.spot, bb.getMinX(), bb.getMinY(), bb.getMinZ(),
            bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
    }

    @Override
    public double[] center() {
        return this.spot.toWorld(0, RingPlacement.CENTER_UP, 0);
    }

    @Override
    public void enter(final Player player, final Settings s) {
        this.effects.enter(player, s);
    }

    @Override
    public void remove() {
        this.visuals.remove();
    }
}
