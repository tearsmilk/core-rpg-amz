package core.amazake.systems.coremechanics.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.stephengold.joltjni.NativeLibraryLoader;
import core.amazake.systems.coremechanics.physics.PhysicsWorld;
import core.amazake.systems.coremechanics.physics.RagdollBody;
import core.amazake.systems.coremechanics.physics.RagdollSpec;
import core.amazake.systems.coremechanics.physics.SectionMesher;
import core.amazake.systems.coremechanics.physics.SectionSample;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Настоящий Jolt (нативка linux x86-64 из jar-а ReleaseSp на classpath тестов). */
class JoltHostTest {

    private static JoltHost host;

    @BeforeAll
    static void load() throws Exception {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("linux") && (arch.contains("amd64") || arch.contains("x86_64")), "нативка Jolt — только Linux x86-64");
        final Path so = Files.createTempFile("libjoltjni", ".so");
        try (InputStream in = JoltHostTest.class.getClassLoader().getResourceAsStream("linux/x86-64/com/github/stephengold/libjoltjni.so")) {
            assertNotNull(in, "нет нативки на classpath");
            Files.copy(in, so, StandardCopyOption.REPLACE_EXISTING);
        }
        assertTrue(NativeLibraryLoader.loadLibrary(so.toString()));
        host = new JoltHost();
        host.start(2);
    }

    @AfterAll
    static void stop() {
        if (host != null) {
            host.shutdown();
        }
    }

    @Test
    void selfTestPasses() {
        assertTrue(host.selfTest().startsWith("box-floor y=0.49") || host.selfTest().startsWith("box-floor y=0.50"), host.selfTest());
        assertEquals(2, host.threads());
        // фабрика типов — раз на JVM: повторный start (как повторное включение системы) не ломается
        final JoltHost second = new JoltHost();
        try {
            second.start(1);
        } catch (final Exception e) {
            throw new AssertionError(e);
        } finally {
            second.shutdown();
        }
    }

    /** Пол из меша секции (как в мире): y = 64 — верх пола. */
    private static void floor(final PhysicsWorld w) {
        final float[][] b = new float[16 * SectionSample.LAYERS * 16][];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                b[SectionSample.index(x, 15, z)] = SectionMesher.FULL;   // y = 3·16 + 15 = 63
            }
        }
        final SectionSample s = new SectionSample(0, 3, 0, true, b);
        assertTrue(w.addStaticMesh(s.mesh().triangles(), 0, 48, 0) >= 0);
    }

    @Test
    void ragdollFallsAndRestsOnFloor() {
        final PhysicsWorld w = host.newWorld(1024, 17f, 3);
        try {
            floor(w);
            final RagdollBody rd = w.createRagdoll(8, 66, 8, 30f, 0.05, 0.1, 0);
            assertNotNull(rd);
            final double[][] pos = new double[RagdollSpec.N][3];
            final float[][] rot = new float[RagdollSpec.N][4];
            for (int i = 0; i < 200; i++) {
                w.step();
            }
            rd.readBack(pos, rot);
            for (int i = 0; i < RagdollSpec.N; i++) {
                assertTrue(pos[i][1] > 64.05 && pos[i][1] < 65.0, "часть " + i + " y=" + pos[i][1]);   // лежит на полу
                final double len = Math.sqrt(rot[i][0] * rot[i][0] + rot[i][1] * rot[i][1] + rot[i][2] * rot[i][2] + rot[i][3] * rot[i][3]);
                assertEquals(1.0, len, 1e-3);
            }
            // шарниры держат: голова рядом с туловищем
            final double dx = pos[0][0] - pos[1][0], dy = pos[0][1] - pos[1][1], dz = pos[0][2] - pos[1][2];
            assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) < 1.0);
            // бросок: импульс вверх поднимает куклу
            for (int i = 0; i < RagdollSpec.N; i++) {
                rd.impulse(i, 0, 8 * RagdollSpec.mass(i), 0);
            }
            w.step();
            w.step();
            rd.readBack(pos, rot);
            assertTrue(pos[RagdollSpec.TORSO][1] > 64.6, "y=" + pos[RagdollSpec.TORSO][1]);
            rd.destroy();
            assertEquals(1, w.bodyCount());   // остался только пол
        } finally {
            w.destroy();
        }
    }

    @Test
    void heldRagdollDoesNotFall() {
        final PhysicsWorld w = host.newWorld(1024, 17f, 3);
        try {
            final RagdollBody rd = w.createRagdoll(0, 100, 0, 0f, 0, 0, 0);
            rd.hold(true);
            for (int i = 0; i < 20; i++) {
                w.step();
            }
            final double[][] pos = new double[RagdollSpec.N][3];
            rd.readBack(pos, new float[RagdollSpec.N][4]);
            assertEquals(100 + RagdollSpec.CENTER[RagdollSpec.TORSO][1], pos[RagdollSpec.TORSO][1], 1e-3);
            rd.hold(false);
            for (int i = 0; i < 20; i++) {
                w.step();
            }
            rd.readBack(pos, new float[RagdollSpec.N][4]);
            assertTrue(pos[RagdollSpec.TORSO][1] < 99, "y=" + pos[RagdollSpec.TORSO][1]);
        } finally {
            w.destroy();
        }
    }
}
