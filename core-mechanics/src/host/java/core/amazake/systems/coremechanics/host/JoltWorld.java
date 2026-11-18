package core.amazake.systems.coremechanics.host;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.MeshShapeSettings;
import com.github.stephengold.joltjni.PhysicsSettings;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.TempAllocator;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EBodyType;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import core.amazake.systems.coremechanics.physics.PhysicsWorld;
import core.amazake.systems.coremechanics.physics.RagdollBody;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Физ-система Jolt одного мира Bukkit. Только поток физики; Jolt внутри шага сам раскидывает работу на свой пул. */
final class JoltWorld implements PhysicsWorld {

    final JoltLayers layers;
    final PhysicsSystem sys;
    final BodyInterface bi;
    private final TempAllocator temp;
    private final JobSystem jobs;
    private final int maxBodies;
    private final int substeps;
    /** Формы статичных секций: держим, пока тело в мире (иначе cleaner освободит натив под телом). */
    private final Map<Integer, ShapeRefC> staticShapes = new HashMap<>();
    private final List<JoltRagdoll> ragdolls = new ArrayList<>();
    private int staticAdds;
    private long lastStepNanos;
    private boolean destroyed;

    JoltWorld(final JoltLayers layers, final TempAllocator temp, final JobSystem jobs, final int maxBodies, final float gravity,
              final int substeps) {
        this.layers = layers;
        this.temp = temp;
        this.jobs = jobs;
        this.maxBodies = maxBodies;
        this.substeps = Math.max(1, substeps);
        this.sys = layers.newSystem(maxBodies, 65_536, 20_480);
        this.bi = this.sys.getBodyInterface();
        // меньше «утопания» тел в опору (по умолчанию 2 см)
        final PhysicsSettings ps = this.sys.getPhysicsSettings();
        ps.setPenetrationSlop(0.005f);
        this.sys.setPhysicsSettings(ps);
        this.sys.setGravity(0f, -Math.abs(gravity), 0f);
    }

    @Override
    public boolean hasRoom() {
        return this.sys.getNumBodies() < this.maxBodies - 8;
    }

    @Override
    public int addStaticMesh(final float[] triangles, final int ox, final int oy, final int oz) {
        if (!this.hasRoom() || triangles == null || triangles.length < 9) {
            return -1;
        }
        final MeshShapeSettings mss = new MeshShapeSettings(toDirect(triangles));
        try {
            final ShapeResult res = mss.create();
            if (res.hasError()) {
                return -1;
            }
            final ShapeRefC shape = res.get();
            final BodyCreationSettings bcs = new BodyCreationSettings()
                .setShape(shape)
                .setMotionType(EMotionType.Static)
                .setObjectLayer(JoltLayers.STATIC)
                .setFriction(0.6f)
                .setPosition(ox, oy, oz);
            try {
                final Body b = this.bi.createBody(bcs);
                final int id = b.getId();
                this.bi.addBody(id, EActivation.DontActivate);
                this.staticShapes.put(id, shape);
                if (++this.staticAdds >= 8) {
                    this.sys.optimizeBroadPhase();
                    this.staticAdds = 0;
                }
                return id;
            } finally {
                bcs.close();
            }
        } finally {
            mss.close();
        }
    }

    @Override
    public void removeStatic(final int id) {
        if (id < 0) {
            return;
        }
        this.bi.removeBody(id);
        this.bi.destroyBody(id);
        final ShapeRefC shape = this.staticShapes.remove(id);
        if (shape != null) {
            shape.close();
        }
    }

    @Override
    public RagdollBody createRagdoll(final double fx, final double fy, final double fz, final float yawDeg,
                                     final double vx, final double vy, final double vz) {
        if (!this.hasRoom()) {
            return null;
        }
        final JoltRagdoll rd = JoltRagdoll.create(this, fx, fy, fz, yawDeg, vx, vy, vz);
        this.ragdolls.add(rd);
        return rd;
    }

    void forget(final JoltRagdoll rd) {
        this.ragdolls.remove(rd);
    }

    @Override
    public int step() {
        final long t0 = System.nanoTime();
        final int err = this.sys.update(1f / 20f, this.substeps, this.temp, this.jobs);
        this.lastStepNanos = System.nanoTime() - t0;
        return err;
    }

    @Override
    public int bodyCount() {
        return this.sys.getNumBodies();
    }

    @Override
    public int activeBodyCount() {
        return this.sys.getNumActiveBodies(EBodyType.RigidBody);
    }

    @Override
    public long lastStepNanos() {
        return this.lastStepNanos;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        for (final JoltRagdoll rd : new ArrayList<>(this.ragdolls)) {
            rd.destroy();
        }
        for (final Integer id : new ArrayList<>(this.staticShapes.keySet())) {
            this.removeStatic(id);
        }
        this.sys.forgetMe();   // убрать из статической карты jolt-jni (иначе обёртка живёт вечно)
        this.sys.close();
    }

    /** Direct FloatBuffer РОВНО под треугольники: jolt-jni читает capacity(), а не limit(). */
    static FloatBuffer toDirect(final float[] tris) {
        final FloatBuffer fb = ByteBuffer.allocateDirect(tris.length * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(tris);
        fb.flip();
        return fb;
    }
}
