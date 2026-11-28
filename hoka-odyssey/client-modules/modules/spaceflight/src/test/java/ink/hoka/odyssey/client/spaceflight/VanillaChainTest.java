package ink.hoka.odyssey.client.spaceflight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Полная цепочка отрисовки модели игрока (LivingEntityRenderer.submit 1.21.11 + EntityTiltMixin ядра 0.48 + добавка
 * offset), посчитанная числами: translate(offset) → tilt → Ry(180° − bodyRot) → scale(−1, −1, 1) →
 * translate(0, −1.501, 0) → голова ModelPart: rotationZYX(0, yRot, xRot). В модели (до scale(−1, −1, 1)) лицо — −Z,
 * верх — −Y, ноги — y = 1.5 (24 px). Ядро ставит bodyRot = bodyYaw, yRot = wrap(headYaw − bodyYaw), xRot = headPitch.
 * Проверяем, куда в мире смотрят лицо и верх тела и головы и где ноги и середина — во всех режимах позы.
 */
class VanillaChainTest {
    private static final double H = 1.8;
    private static final double W = 0.6;
    private static final float DEG = (float) (Math.PI / 180);

    /** Поза так же, как её собирает BodyPoses.pose (без плавных переходов). */
    private record Pose(Quat body, float headYaw, float headPitch, Vec3d offset) {}

    private static Pose pose(Quat look, int surface, boolean prone) {
        Quat base = BodyFrame.base(look, surface);
        Quat body = BodyFrame.body(base, prone ? 1 : 0);
        Vec3d centre = BodyFrame.centre(surface, prone, H, W);
        float[] head = BodyFrame.head(body, look.forward());
        return new Pose(body, head[0], head[1], BodyFrame.offset(body, centre, H));
    }

    /** Матрица тела модели (мир ← модель), как в игре; scale сущности s и сдвиг offset/s внутри (ядро делит). */
    private static Matrix4f bodyMatrix(Pose p, float s) {
        float bodyYaw = 0f;
        Quaternionf tilt = new Quaternionf((float) p.body.x(), (float) p.body.y(), (float) p.body.z(),
                (float) p.body.w());
        return new Matrix4f()
                .scale(s)                                                          // submit: scale(s, s, s)
                .translate((float) p.offset.x() / s, (float) p.offset.y() / s, (float) p.offset.z() / s)  // HEAD
                .rotate(tilt)                                                      // HEAD: mulPose(tilt)
                .rotateY((180 - bodyYaw) * DEG)                                    // vanilla setupRotations
                .scale(-1, -1, 1)
                .translate(0, -1.501f, 0);
    }

    private static Matrix4f headMatrix(Pose p, float s) {
        float yRot = wrap(p.headYaw - 0f), xRot = p.headPitch;
        return bodyMatrix(p, s).translate(0, 0, 0)                               // шея: pivot головы (0, 0, 0)
                .rotate(new Quaternionf().rotationZYX(0, yRot * DEG, xRot * DEG));
    }

    private static float wrap(float a) {
        float f = a % 360f;
        if (f >= 180f) f -= 360f;
        if (f < -180f) f += 360f;
        return f;
    }

    private static Vec3d dir(Matrix4f m, float x, float y, float z) {
        Vector3f v = m.transformDirection(new Vector3f(x, y, z));
        return new Vec3d(v.x, v.y, v.z).normalize();
    }

    private static Vec3d point(Matrix4f m, float x, float y, float z) {
        Vector3f v = m.transformPosition(new Vector3f(x, y, z));
        return new Vec3d(v.x, v.y, v.z);
    }

    private static void near(Vec3d a, Vec3d b, double eps, String what) {
        assertEquals(0, a.sub(b).length(), eps, () -> what + ": " + a + " vs " + b);
    }

    /** Лицо, верх тела и головы, ноги в мире. */
    private record World(Vec3d bodyFace, Vec3d bodyUp, Vec3d headFace, Vec3d feet, Vec3d centre) {}

    private static World world(Pose p, float s) {
        Matrix4f b = bodyMatrix(p, s), h = headMatrix(p, s);
        // середина тела модели в своих единицах: ноги y = 1.501, середина — выше на H/2 (мир) / s
        return new World(dir(b, 0, 0, -1), dir(b, 0, -1, 0), dir(h, 0, 0, -1), point(b, 0, 1.501f, 0),
                point(b, 0, 1.501f - (float) (H / 2 / s), 0));
    }

    /** Без tilt (bodyRot = yaw, yRot = headYaw − yaw, xRot = pitch) цепочка воспроизводит vanilla: голова — по взгляду. */
    @Test
    void chainReproducesVanillaWithoutTilt() {
        Random r = new Random(11);
        for (int i = 0; i < 500; i++) {
            float bodyRot = r.nextFloat() * 720 - 360, headYaw = r.nextFloat() * 720 - 360,
                    pitch = r.nextFloat() * 180 - 90;
            Matrix4f m = new Matrix4f().rotateY((180 - bodyRot) * DEG).scale(-1, -1, 1)
                    .rotate(new Quaternionf().rotationZYX(0, wrap(headYaw - bodyRot) * DEG, pitch * DEG));
            near(BodyFrame.dir(headYaw, pitch), dir(m, 0, 0, -1), 1e-4, "голова vanilla");
            Matrix4f body = new Matrix4f().rotateY((180 - bodyRot) * DEG).scale(-1, -1, 1);
            near(BodyFrame.dir(bodyRot, 0), dir(body, 0, 0, -1), 1e-4, "тело vanilla");
            near(new Vec3d(0, 1, 0), dir(body, 0, -1, 0), 1e-4, "верх vanilla");
            // соглашение FreeLook/spacephys совпадает с углами Minecraft
            near(BodyFrame.dir(headYaw, pitch), Quat.fromYawPitchRoll(headYaw, pitch, 0).forward(), 1e-9, "yaw/pitch");
        }
    }

    @Test
    void freeBodyFacesAimWithCameraUpAroundCentre() {
        Random r = new Random(12);
        for (int i = 0; i < 2000; i++) {
            Quat look = CameraConventionTest.randomOrientation(r);
            float s = i % 3 == 0 ? 0.5f + r.nextFloat() : 1f;
            World w = world(pose(look, -1, false), s);
            near(look.forward(), w.bodyFace, 1e-4, "лицо тела");
            near(look.up(), w.bodyUp, 1e-4, "верх тела");
            near(look.forward(), w.headFace, 1e-4, "голова");
            near(new Vec3d(0, H / 2, 0), w.centre, 1e-4, "середина на месте");
        }
    }

    @Test
    void proneLiesAlongViewChestToCameraDown() {
        Random r = new Random(13);
        for (int i = 0; i < 1000; i++) {
            Quat look = CameraConventionTest.randomOrientation(r);
            World w = world(pose(look, -1, true), 1f);
            near(look.forward(), w.bodyUp, 1e-4, "макушка по взгляду");
            near(look.up().negate(), w.bodyFace, 1e-4, "грудь к низу камеры");
            near(look.forward(), w.headFace, 1e-3, "голова по взгляду");
            near(new Vec3d(0, H / 2, 0), w.centre, 1e-4, "середина на месте");
        }
    }

    @Test
    void standingOnEachFaceFeetOnContactFacingProjectedLook() {
        Random r = new Random(14);
        for (int surface = 0; surface < 6; surface++) {
            Vec3d n = BodyFrame.NORMALS[surface];
            double half = Math.abs(n.y()) * H / 2 + (Math.abs(n.x()) + Math.abs(n.z())) * W / 2;
            Vec3d contact = new Vec3d(0, H / 2, 0).sub(n.scale(half));     // середина грани рамки со стороны блока
            for (int i = 0; i < 1000; i++) {
                Quat look = CameraConventionTest.randomOrientation(r);
                float s = i % 4 == 0 ? 0.5f + r.nextFloat() : 1f;
                World w = world(pose(look, surface, false), s);
                near(n, w.bodyUp, 1e-4, "верх по нормали " + surface);
                near(contact, w.feet, 1e-4, "ноги в точке касания " + surface);
                assertEquals(0, w.bodyFace.dot(n), 1e-4);
                Vec3d t = BodyFrame.tangent(look.forward(), n);
                if (t.length() > 0.05) near(t.normalize(), w.bodyFace, 1e-4, "лицом по взгляду " + surface);
                near(look.forward(), w.headFace, 1e-3, "голова по взгляду " + surface);
            }
        }
    }

    @Test
    void proneOnSurfaceLiesFaceDownJustAboveIt() {
        Random r = new Random(15);
        for (int surface = 0; surface < 6; surface++) {
            Vec3d n = BodyFrame.NORMALS[surface];
            double half = Math.abs(n.y()) * H / 2 + (Math.abs(n.x()) + Math.abs(n.z())) * W / 2;
            Vec3d contact = new Vec3d(0, H / 2, 0).sub(n.scale(half));
            for (int i = 0; i < 300; i++) {
                Quat look = CameraConventionTest.randomOrientation(r);
                World w = world(pose(look, surface, true), 1f);
                near(n.negate(), w.bodyFace, 1e-4, "грудью к поверхности " + surface);
                assertEquals(0, w.bodyUp.dot(n), 1e-4);
                near(contact.add(n.scale(BodyFrame.LIE_LIFT)), w.centre, 1e-4, "середина над поверхностью " + surface);
            }
        }
    }

    /** Взгляд ровно вдоль верха тела (вверх/вниз): голова не крутится вокруг шеи от шума округления. */
    @Test
    void headYawStableWhenLookingAlongBodyUp() {
        Quat body = Quat.fromYawPitchRoll(30, 0, 0);
        for (double eps : new double[] {0, 1e-12, -1e-12, 1e-9}) {
            Vec3d up = body.up();
            Vec3d noisy = new Vec3d(up.x() + eps, up.y(), up.z() - eps);
            float[] h = BodyFrame.head(body, noisy);
            assertEquals(0, h[0], 1e-3);
            assertEquals(-90, h[1], 1e-3);
            float[] d = BodyFrame.head(body, noisy.negate());
            assertEquals(0, d[0], 1e-3);
            assertEquals(90, d[1], 1e-3);
        }
    }
}
