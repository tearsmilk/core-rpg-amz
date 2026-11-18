package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.GateTimeline;
import core.amazake.systems.coreportals.geo.Box;
import core.amazake.systems.coreportals.geo.GatePlacement;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * «Врата междумирья»: объёмная рама 6×10 поднимается из земли, в проёме от земли растёт энергетическое кольцо,
 * за и перед рамой клубятся облака, на полу — свечение ({@link GateTimeline}, {@link GateVisuals}). Частицы и
 * звуки — ванильные.
 */
final class GateBody implements PortalBody {

    private final World world;
    private final Spot spot;
    private final GateVisuals visuals = new GateVisuals();
    private volatile GateTimeline timeline;

    GateBody(final World world, final Spot spot, final GateTimeline timeline) {
        this.world = world;
        this.spot = spot;
        this.timeline = timeline;
    }

    @Override
    public void spawn(final Settings s, final String instance) {
        this.visuals.spawn(this.world, this.spot, s, instance, this.timeline);
        if (s.sounds()) {
            this.sound("minecraft:block.beacon.activate", 1.0f, 0.55f);
        }
    }

    @Override
    public boolean valid() {
        return this.visuals.valid();
    }

    @Override
    public void closeAt(final long tick) {
        this.timeline = this.timeline.closeAt(tick);
    }

    @Override
    public boolean done(final long tick) {
        return this.timeline.done(tick);
    }

    @Override
    public boolean enterable(final long tick) {
        return this.timeline.enterable(tick);
    }

    @Override
    public String stage(final long tick) {
        return this.timeline.stage(tick);
    }

    @Override
    public double[] center() {
        return this.spot.toWorld(0.0, 4.5, 0.0);
    }

    /** Врата большие: игроков для входа ищем в радиусе 5,5 блока от середины проёма. */
    @Override
    public double scanRadius() {
        return 5.5;
    }

    @Override
    public void remove() {
        this.visuals.remove();
    }

    @Override
    public boolean touches(final BoundingBox b) {
        return GatePlacement.touches(this.spot,
            new Box(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ()));
    }

    @Override
    public void tick(final long tick, final Settings s) {
        final GateTimeline tl = this.timeline;
        if (tick % GateTimeline.KEY_STEP == 0L) {
            // Интерполяция дисплеев — 2 тика: движение рамы и кольца плавное между ключевыми кадрами.
            this.visuals.apply(tl, tick, 2);
        }
        if (s.sounds()) {
            if (tick == 22L) {
                // Кольцо вот-вот начнёт расти (24-й тик).
                this.sound("minecraft:block.respawn_anchor.charge", 1.1f, 0.7f);
            }
            if (tick == GateTimeline.OPENED) {
                this.sound("minecraft:block.end_portal.spawn", 0.75f, 0.7f);
            }
            if (tick == tl.closeStart()) {
                this.sound("minecraft:block.beacon.deactivate", 1.0f, 0.55f);
            }
            if (tl.enterable(tick) && tick % 100L == 0L) {
                // Пока врата открыты — тихий гул раз в 5 секунд.
                this.sound("minecraft:block.portal.ambient", 0.45f, 0.65f);
            }
        }
        if (s.particles() && tick % 4L == 0L && tl.aperture(tick) > 0.05) {
            // Фиолетово-розовые искры по краю кольца (эллипс растёт вместе с кольцом).
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            final double open = tl.aperture(tick);
            for (int i = 0; i < 4; i++) {
                final double a = random.nextDouble(Math.PI * 2);
                final double y = 0.12 + open * (4.25 + Math.sin(a) * 4.2);
                final double[] at = this.spot.toWorld(Math.cos(a) * 2.2 * Math.sqrt(open), y,
                    random.nextDouble(-0.15, 0.15));
                this.world.spawnParticle(Particle.DUST_COLOR_TRANSITION, new Location(this.world, at[0], at[1], at[2]),
                    1, 0.04, 0.1, 0.04, 0.0,
                    new Particle.DustTransition(Color.fromRGB(130, 90, 255), Color.fromRGB(255, 165, 221), 0.8f));
            }
        }
    }

    @Override
    public void enter(final Player player, final Settings s) {
        if (s.sounds()) {
            this.sound("minecraft:entity.enderman.teleport", 0.65f, 0.8f);
        }
    }

    private void sound(final String key, final float volume, final float pitch) {
        this.world.playSound(new Location(this.world, this.spot.x(), this.spot.y() + 2, this.spot.z()), key,
            SoundCategory.BLOCKS, volume, pitch);
    }
}
