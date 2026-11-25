package ink.hoka.odyssey.client.spaceflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import java.util.Random;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** Поза тела: свободно — к прицелу, на поверхности — ноги на ней; соглашение модели игрока ядра/vanilla. */
class BodyFrameTest {
    private static final double EPS = 1e-6;
    private static final double H = 1.8;
    private static final double W = 0.6;

    private static void near(Vec3d a, Vec3d b, double eps) {
        assertEquals(a.x(), b.x(), eps, () -> a + " vs " + b);
        assertEquals(a.y(), b.y(), eps, () -> a + " vs " + b);
        assertEquals(a.z(), b.z(), eps, () -> a + " vs " + b);
    }

    private static Vec3d v(Vector3f f) {
        return new Vec3d(f.x, f.y, f.z);
    }

    /**
     * Соглашение модели: vanilla setupRotations после tilt делает Ry(180° − bodyRot); лицо модели в этих осях
     * (после scale(−1,−1,1)) — −Z, верх — +Y. Проверка посылки: без tilt модель с bodyRot = yaw смотрит по
     * Entity#getViewVector(yaw, 0). Тогда с tilt = Qbody и bodyYaw = 0 лицо — Qbody·(+Z), верх — Qbody·(+Y).
     */
    @Test
    void modelConventionMatchesVanillaChain() {
        Random r = new Random(5);
        for (int i = 0; i < 200; i++) {
            double yaw = r.nextDouble() * 720 - 360;
            Vector3f face = new Quaternionf().rotationY((float) Math.toRadians(180 - yaw))
                    .transform(new Vector3f(0, 0, -1));
            near(BodyFrame.dir(yaw, 0), v(face), 1e-5);
        }
        for (int i = 0; i < 200; i++) {
            Quat body = CameraConventionTest.randomOrientation(r);
            Quaternionf chain = new Quaternionf((float) body.x(), (float) body.y(), (float) body.z(), (float) body.w())
                    .mul(new Quaternionf().rotationY((float) Math.toRadians(180 - 0)));
            near(body.forward(), v(chain.transform(new Vector3f(0, 0, -1))), 1e-5);
            near(body.up(), v(chain.transform(new Vector3f(0, 1, 0))), 1e-5);
        }
    }

    @Test
    void freeBodyFacesAimAroundCentre() {
        Random r = new Random(6);
        for (int i = 0; i < 500; i++) {
            Quat look = CameraConventionTest.randomOrientation(r);
            Quat body = BodyFrame.body(BodyFrame.base(look, -1), 0);
            near(look.forward(), body.forward(), EPS);
            near(look.up(), body.up(), EPS);
            float[] head = BodyFrame.head(body, look.forward());
            assertEquals(0, head[0], 1e-3);
            assertEquals(0, head[1], 1e-3);
            // середина модели остаётся серединой рамки
            Vec3d centre = BodyFrame.centre(-1, false, H, W);
            Vec3d off = BodyFrame.offset(body, centre, H);
            near(new Vec3d(0, H / 2, 0), off.add(body.rotate(0, H / 2, 0)), EPS);
        }
    }

    @Test
    void proneLiesAlongView() {
        Random r = new Random(7);
        for (int i = 0; i < 200; i++) {
            Quat look = CameraConventionTest.randomOrientation(r);
            Quat body = BodyFrame.body(look, 1);
            near(look.forward(), body.up(), EPS);                   // макушка — по взгляду
            near(look.up().negate(), body.forward(), EPS);          // грудь (лицо) — к низу камеры
            float[] head = BodyFrame.head(body, look.forward());
            assertEquals(-90, head[1], 1e-2);                       // голова запрокинута — смотрит вперёд
        }
    }

    @Test
    void standingOnSurfaceKeepsFeetOnItAndFacesLook() {
        Random r = new Random(8);
        for (int s = 0; s < 6; s++) {
            Vec3d n = BodyFrame.NORMALS[s];
            for (int i = 0; i < 300; i++) {
                Quat look = CameraConventionTest.randomOrientation(r);
                Quat body = BodyFrame.base(look, s);
                near(n, body.up(), 1e-6);
                assertEquals(0, body.forward().dot(n), 1e-6);
                Vec3d tangent = BodyFrame.tangent(look.forward(), n);
                if (tangent.length() > 0.05) {
                    near(tangent.normalize(), body.forward(), 1e-6);  // лицом — по взгляду вдоль поверхности
                }
                // голова смотрит ровно туда же, куда взгляд
                float[] head = BodyFrame.head(body, look.forward());
                assertTrue(Math.abs(head[1]) <= 90.0001);
                near(look.forward(), body.rotate(BodyFrame.dir(head[0], head[1])), 1e-4);
                // ноги (начало модели) — в точке касания: середина рамки − n · полразмера вдоль n
                Vec3d off = BodyFrame.offset(body, BodyFrame.centre(s, false, H, W), H);
                double half = Math.abs(n.y()) * H / 2 + (Math.abs(n.x()) + Math.abs(n.z())) * W / 2;
                near(new Vec3d(0, H / 2, 0).sub(n.scale(half)), off, 1e-6);
            }
        }
    }

    @Test
    void feetOffsetsForFloorCeilingWall() {
        Quat look = Quat.fromYawPitchRoll(30, 10, 0);
        near(Vec3d.ZERO, BodyFrame.offset(BodyFrame.base(look, 2), BodyFrame.centre(2, false, H, W), H), EPS);
        near(new Vec3d(0, H, 0), BodyFrame.offset(BodyFrame.base(look, 3), BodyFrame.centre(3, false, H, W), H), EPS);
        near(new Vec3d(-W / 2, H / 2, 0),
                BodyFrame.offset(BodyFrame.base(look, 0), BodyFrame.centre(0, false, H, W), H), EPS);
    }

    @Test
    void floorStandingIsUprightWithYaw() {
        Quat look = Quat.fromYawPitchRoll(70, 35, 0);
        Quat body = BodyFrame.base(look, 2);
        near(Quat.fromYawPitchRoll(70, 0, 0).forward(), body.forward(), 1e-6);
        float[] head = BodyFrame.head(body, look.forward());
        assertEquals(0, head[0], 1e-3);
        assertEquals(35, head[1], 1e-3);
    }

    /** Смотришь прямо в пол — вперёд по верху камеры; прямо в небо — против него (как при обычном взгляде). */
    @Test
    void degenerateLookUsesCameraUp() {
        Quat down = Quat.fromYawPitchRoll(40, 90, 0);
        near(Quat.fromYawPitchRoll(40, 0, 0).forward(), BodyFrame.base(down, 2).forward(), 1e-6);
        Quat up = Quat.fromYawPitchRoll(40, -90, 0);
        near(Quat.fromYawPitchRoll(40, 0, 0).forward(), BodyFrame.base(up, 2).forward(), 1e-6);
        Quat almost = Quat.fromYawPitchRoll(40, 89.5, 0);
        near(Quat.fromYawPitchRoll(40, 0, 0).forward(), BodyFrame.base(almost, 2).forward(), 1e-6);
    }

    @Test
    void fromBasisRoundTrip() {
        Random r = new Random(9);
        for (int i = 0; i < 500; i++) {
            Quat q = CameraConventionTest.randomOrientation(r);
            Quat b = BodyFrame.fromBasis(q.forward(), q.up());
            assertEquals(0, FreeLook.angle(q, b), 1e-6);
        }
    }

    @Test
    void flagsRoundTrip() {
        assertEquals(BodyFrame.FLAG_ACTIVE, BodyFrame.flags(true, false, -1));
        assertEquals(-1, BodyFrame.surfaceOf(BodyFrame.flags(true, true, -1)));
        for (int s = 0; s < 6; s++) {
            int f = BodyFrame.flags(true, s % 2 == 0, s);
            assertEquals(s, BodyFrame.surfaceOf(f));
            assertEquals(s % 2 == 0, (f & BodyFrame.FLAG_PRONE) != 0);
            assertTrue((f & BodyFrame.FLAG_ACTIVE) != 0);
            assertTrue(f < 0x20);                                    // старшие биты свободны
        }
        assertEquals(-1, BodyFrame.surfaceOf(7 << BodyFrame.SURFACE_SHIFT));   // неизвестное значение — свободно
        for (int s = 0; s < 6; s++) assertEquals(s, BodyFrame.normalIndex(BodyFrame.NORMALS[s]));
        assertEquals(-1, BodyFrame.normalIndex(new Vec3d(0.6, 0.8, 0)));
    }
}
