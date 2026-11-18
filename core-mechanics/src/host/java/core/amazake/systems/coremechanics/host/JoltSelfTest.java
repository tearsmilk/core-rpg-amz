package core.amazake.systems.coremechanics.host;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.MeshShapeSettings;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.TempAllocator;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import core.amazake.systems.coremechanics.physics.BlockSampler;
import core.amazake.systems.coremechanics.physics.SectionMesher;
import java.util.Locale;

/**
 * Самотест при запуске (без Bukkit): куб на пол-бокс и на пол-меш {@link SectionMesher}. Ловит главное — что нативка
 * работает и что меш мира смотрит гранями наружу (иначе куклы проваливались бы сквозь землю).
 */
final class JoltSelfTest {

    private JoltSelfTest() {
    }

    record Outcome(boolean ok, String report) {
    }

    static Outcome quick(final JoltLayers layers, final TempAllocator temp, final JobSystem jobs) {
        final double a = boxFloor(layers, temp, jobs);
        final double b = meshFloor(layers, temp, jobs);
        final boolean ok = near(a, 0.5, 0.06) && near(b, 1.5, 0.06);
        return new Outcome(ok, String.format(Locale.ROOT, "box-floor y=%.3f (жд 0.500), mesh-floor y=%.3f (жд 1.500)", a, b));
    }

    private static double boxFloor(final JoltLayers layers, final TempAllocator temp, final JobSystem jobs) {
        final PhysicsSystem sys = layers.newSystem(256, 1024, 1024);
        final BoxShape floor = new BoxShape(8f, 0.5f, 8f);
        final BoxShape unit = new BoxShape(0.5f, 0.5f, 0.5f);
        try {
            final BodyInterface bi = sys.getBodyInterface();
            addStatic(bi, floor, 0, -0.5, 0);
            final int box = addDynamic(bi, unit, 0, 3, 0);
            run(sys, temp, jobs, 80);
            return bi.getPosition(box).yy();
        } finally {
            sys.forgetMe();
            sys.close();
            java.lang.ref.Reference.reachabilityFence(floor);
            java.lang.ref.Reference.reachabilityFence(unit);
        }
    }

    private static double meshFloor(final JoltLayers layers, final TempAllocator temp, final JobSystem jobs) {
        final PhysicsSystem sys = layers.newSystem(256, 1024, 1024);
        final BoxShape unit = new BoxShape(0.5f, 0.5f, 0.5f);
        try {
            final BodyInterface bi = sys.getBodyInterface();
            final BlockSampler floor = (x, y, z) -> y == 0 ? SectionMesher.FULL : SectionMesher.EMPTY;
            final SectionMesher.Result r = SectionMesher.mesh(floor, 0, 0, 0);
            final MeshShapeSettings mss = new MeshShapeSettings(JoltWorld.toDirect(r.triangles()));
            final ShapeResult res = mss.create();
            if (res.hasError()) {
                throw new IllegalStateException("mesh: " + res.getError());
            }
            final ShapeRefC shape = res.get();
            final BodyCreationSettings s = new BodyCreationSettings()
                .setShape(shape).setMotionType(EMotionType.Static).setObjectLayer(JoltLayers.STATIC).setPosition(0, 0, 0);
            final Body floorBody = bi.createBody(s);
            bi.addBody(floorBody.getId(), EActivation.DontActivate);
            s.close();
            mss.close();
            final int box = addDynamic(bi, unit, 8, 4, 8);
            run(sys, temp, jobs, 80);
            final double y = bi.getPosition(box).yy();
            java.lang.ref.Reference.reachabilityFence(shape);
            return y;
        } finally {
            sys.forgetMe();
            sys.close();
            java.lang.ref.Reference.reachabilityFence(unit);
        }
    }

    private static void run(final PhysicsSystem sys, final TempAllocator temp, final JobSystem jobs, final int ticks) {
        for (int i = 0; i < ticks; i++) {
            sys.update(1f / 20f, 3, temp, jobs);
        }
    }

    private static void addStatic(final BodyInterface bi, final BoxShape shape, final double x, final double y, final double z) {
        final BodyCreationSettings s = new BodyCreationSettings()
            .setShape(shape).setMotionType(EMotionType.Static).setObjectLayer(JoltLayers.STATIC).setPosition(x, y, z);
        final Body b = bi.createBody(s);
        bi.addBody(b.getId(), EActivation.DontActivate);
        s.close();
    }

    private static int addDynamic(final BodyInterface bi, final BoxShape shape, final double x, final double y, final double z) {
        final BodyCreationSettings s = new BodyCreationSettings()
            .setShape(shape).setMotionType(EMotionType.Dynamic).setObjectLayer(JoltLayers.MOVING)
            .setPosition(x, y, z).setFriction(0.6f).setRestitution(0.05f)
            .setLinearDamping(0.1f).setAngularDamping(0.1f).setMotionQuality(EMotionQuality.LinearCast);
        final Body b = bi.createBody(s);
        final int id = b.getId();
        bi.addBody(id, EActivation.Activate);
        s.close();
        return id;
    }

    private static boolean near(final double v, final double want, final double tol) {
        return Math.abs(v - want) <= tol;
    }
}
