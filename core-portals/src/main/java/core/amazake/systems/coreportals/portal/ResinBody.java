package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.Phase;
import core.amazake.systems.coreportals.anim.Timeline;
import core.amazake.systems.coreportals.geo.Box;
import core.amazake.systems.coreportals.geo.Spot;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/** «Портал» из смолы: рама из-под земли, малиновая поверхность ({@link Timeline}, {@link PortalVisuals}). */
final class ResinBody implements PortalBody {

    private final World world;
    private final Spot spot;
    private final PortalVisuals visuals = new PortalVisuals();
    private final PortalEffects effects;
    private volatile Timeline timeline;

    ResinBody(final World world, final Spot spot, final Timeline timeline) {
        this.world = world;
        this.spot = spot;
        this.timeline = timeline;
        this.effects = new PortalEffects(world, spot);
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
        return this.timeline.phase(t) == Phase.DONE;
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
        final Timeline tl = this.timeline;
        if (tl.phase(t) != Phase.IDLE && t % Timeline.KEY_STEP == 0) {
            // Длительность чуть больше шага: следующий кадр приходит, пока клиент ещё тянет этот, — без рывков.
            this.visuals.apply(tl, t + Timeline.KEY_STEP, Timeline.KEY_STEP + 1);
        }
        this.effects.tick(tl, t, s);
    }

    @Override
    public boolean touches(final BoundingBox bb) {
        final Box box = this.spot.surfaceBox();
        return box.overlaps(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
    }

    @Override
    public double[] center() {
        return this.spot.toWorld(0, Spot.SURFACE_HEIGHT / 2, 0);
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
