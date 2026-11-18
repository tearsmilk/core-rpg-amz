package core.amazake.systems.coremechanics.host;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.CollisionGroup;
import com.github.stephengold.joltjni.GroupFilterTable;
import com.github.stephengold.joltjni.GroupFilterTableRef;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import core.amazake.systems.coremechanics.physics.RagdollBody;
import core.amazake.systems.coremechanics.physics.RagdollSpec;
import java.util.ArrayList;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Рэгдолл человека в Jolt (перенесён из PasToSmith Ragdoll): 6 коробок на шарнирах swing-twist с пределами углов.
 * Части одного рэгдолла друг с другом не сталкиваются (таблица групп), с миром и другими куклами — да.
 */
final class JoltRagdoll implements RagdollBody {

    private static int nextGroup = 1;

    private final JoltWorld w;
    private final int[] ids = new int[RagdollSpec.N];
    private final List<TwoBodyConstraint> joints = new ArrayList<>();
    private final RVec3 tmpPos = new RVec3();
    private final Quat tmpRot = new Quat();
    /** Нативные объекты держим ссылками всё время жизни (иначе сборщик мусора освободит их под телом). */
    private final List<Object> keep = new ArrayList<>();
    private boolean held;
    private boolean destroyed;

    private JoltRagdoll(final JoltWorld w) {
        this.w = w;
    }

    static JoltRagdoll create(final JoltWorld w, final double fx, final double fy, final double fz, final float yawDeg,
                              final double vx, final double vy, final double vz) {
        final JoltRagdoll rd = new JoltRagdoll(w);
        final Quaternionf q = new Quaternionf().rotationY((float) (Math.PI - Math.toRadians(yawDeg)));
        final GroupFilterTable table = new GroupFilterTable(RagdollSpec.N);
        for (int a = 0; a < RagdollSpec.N; a++) {
            for (int b = a + 1; b < RagdollSpec.N; b++) {
                table.disableCollision(a, b);
            }
        }
        final GroupFilterTableRef filter = table.toRef();
        rd.keep.add(table);
        rd.keep.add(filter);
        final int group = nextGroup++;
        final Body[] bodies = new Body[RagdollSpec.N];
        for (int i = 0; i < RagdollSpec.N; i++) {
            final double[] cc = RagdollSpec.CENTER[i];
            final Vector3f c = q.transform(new Vector3f((float) cc[0], (float) cc[1], (float) cc[2]));
            final float[] h = RagdollSpec.HALF[i];
            final BoxShape shape = new BoxShape(h[0], h[1], h[2], 0.04f);
            rd.keep.add(shape);
            final MassProperties mp = new MassProperties();
            mp.setMass(RagdollSpec.MASS[i]);
            final BodyCreationSettings s = new BodyCreationSettings();
            try {
                s.setShape(shape)
                    .setMotionType(EMotionType.Dynamic)
                    .setObjectLayer(JoltLayers.MOVING)
                    .setPosition(fx + c.x, fy + c.y, fz + c.z)
                    .setRotation(q.x, q.y, q.z, q.w)
                    .setFriction(0.8f)
                    .setRestitution(0.05f)
                    .setLinearDamping(0.1f)
                    .setAngularDamping(0.5f)
                    .setMotionQuality(EMotionQuality.LinearCast)
                    .setAllowSleeping(true)
                    .setLinearVelocity((float) vx * 20, (float) vy * 20, (float) vz * 20)
                    .setMaxLinearVelocity(60f)
                    .setOverrideMassProperties(EOverrideMassProperties.CalculateInertia)
                    .setMassPropertiesOverride(mp)
                    .setCollisionGroup(new CollisionGroup(filter, group, i));
                final Body b = w.bi.createBody(s);
                bodies[i] = b;
                rd.ids[i] = b.getId();
                w.bi.addBody(rd.ids[i], EActivation.Activate);
            } finally {
                s.close();
                mp.close();
            }
        }
        for (final float[] j : RagdollSpec.JOINTS) {
            rd.joint(bodies, (int) j[0], q, j);
        }
        return rd;
    }

    /** Шарнир swing-twist в мировых координатах (родитель — туловище). */
    private void joint(final Body[] b, final int child, final Quaternionf q, final float[] j) {
        final RVec3 p0 = new RVec3();
        this.w.bi.getPositionAndRotation(this.ids[RagdollSpec.TORSO], p0, new Quat());
        final Vector3f off = q.transform(new Vector3f(j[1], (float) (j[2] - RagdollSpec.CENTER[RagdollSpec.TORSO][1]), j[3]));
        final Vector3f twist = q.transform(new Vector3f(j[4], j[5], j[6]));
        final Vector3f plane = q.transform(new Vector3f(j[7], j[8], j[9]));
        final SwingTwistConstraintSettings st = new SwingTwistConstraintSettings();
        try {
            st.setSpace(EConstraintSpace.WorldSpace);
            final double x = p0.xx() + off.x, y = p0.yy() + off.y, z = p0.zz() + off.z;
            st.setPosition1(x, y, z);
            st.setPosition2(x, y, z);
            st.setTwistAxis1(twist.x, twist.y, twist.z);
            st.setTwistAxis2(twist.x, twist.y, twist.z);
            st.setPlaneAxis1(plane.x, plane.y, plane.z);
            st.setPlaneAxis2(plane.x, plane.y, plane.z);
            st.setNormalHalfConeAngle((float) Math.toRadians(j[10]));
            st.setPlaneHalfConeAngle((float) Math.toRadians(j[11]));
            st.setTwistMinAngle((float) Math.toRadians(j[12]));
            st.setTwistMaxAngle((float) Math.toRadians(j[13]));
            final TwoBodyConstraint con = st.create(b[RagdollSpec.TORSO], b[child]);
            this.w.sys.addConstraint(con);
            this.joints.add(con);
        } finally {
            st.close();
        }
    }

    @Override
    public void readBack(final double[][] pos, final float[][] rot) {
        for (int i = 0; i < RagdollSpec.N; i++) {
            this.w.bi.getPositionAndRotation(this.ids[i], this.tmpPos, this.tmpRot);
            pos[i][0] = this.tmpPos.xx();
            pos[i][1] = this.tmpPos.yy();
            pos[i][2] = this.tmpPos.zz();
            rot[i][0] = this.tmpRot.getX();
            rot[i][1] = this.tmpRot.getY();
            rot[i][2] = this.tmpRot.getZ();
            rot[i][3] = this.tmpRot.getW();
        }
    }

    @Override
    public void hold(final boolean on) {
        if (on == this.held) {
            return;
        }
        this.held = on;
        for (final int id : this.ids) {
            this.w.bi.setMotionType(id, on ? EMotionType.Kinematic : EMotionType.Dynamic,
                on ? EActivation.DontActivate : EActivation.Activate);
            if (on) {
                this.w.bi.setLinearVelocity(id, 0, 0, 0);
            }
        }
    }

    @Override
    public void setVelocity(final int part, final double vx, final double vy, final double vz) {
        this.w.bi.setLinearVelocity(this.ids[part], (float) vx, (float) vy, (float) vz);
        this.w.bi.activateBody(this.ids[part]);
    }

    @Override
    public void impulse(final int part, final double ix, final double iy, final double iz) {
        this.w.bi.addImpulse(this.ids[part], new Vec3((float) ix, (float) iy, (float) iz));
        this.w.bi.activateBody(this.ids[part]);
    }

    @Override
    public void wake() {
        for (final int id : this.ids) {
            this.w.bi.activateBody(id);
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        for (final TwoBodyConstraint c : this.joints) {
            this.w.sys.removeConstraint(c);
        }
        this.joints.clear();
        for (final int id : this.ids) {
            this.w.bi.removeBody(id);
            this.w.bi.destroyBody(id);
        }
        this.keep.clear();
        this.w.forget(this);
    }
}
