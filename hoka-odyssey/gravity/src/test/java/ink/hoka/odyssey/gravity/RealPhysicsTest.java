package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.spacephys.Body;
import ink.hoka.odyssey.spacephys.Field;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealPhysicsTest {

    private static RealPhysics load(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);
        ModuleConfig c = new ModuleConfig(file);
        return RealPhysics.from(c, Settings.from(c).space().holes());
    }

    @Test
    void bodiesParsedPerMap(@TempDir Path dir) throws IOException {
        RealPhysics rp = load(dir, """
            bodies:
              galaxy1:
                terra: {type: planet, x: 0, y: 0, z: 300, radius: 40, surface-gravity: 9.8}
                station: {type: small, x: 0, y: 100, z: 300, mu: 20, softening: 4}
                sun: {type: star, x: 1000, y: 0, z: 0, radius: 100, mu: 5.0e6}
              2:
                1: {radius: 10, surface-gravity: 2}
            """);
        List<Body> g = rp.bodies().get("galaxy1");
        assertEquals(3, g.size());
        Body terra = g.get(0);
        assertEquals(Body.Kind.PLANET, terra.kind());
        assertEquals(9.8 * 40 * 40, terra.mu(), 1e-9);
        assertEquals(40, terra.radius());
        assertEquals(300, terra.z());
        Body station = g.get(1);
        assertEquals(Body.Kind.SMALL, station.kind());
        assertEquals(20, station.mu());
        assertEquals(4, station.softening());
        assertEquals(Body.Kind.STAR, g.get(2).kind());
        assertEquals(5.0e6, g.get(2).mu());
        // ключи-числа YAML читаются как есть, type по умолчанию — planet
        Body one = rp.bodies().get("2").get(0);
        assertEquals("1", one.name());
        assertEquals(Body.Kind.PLANET, one.kind());
        assertEquals(200, one.mu(), 1e-9);
        assertTrue(rp.warnings().isEmpty(), rp.warnings().toString());
    }

    @Test
    void surfaceGravityIsReachedAtRadius(@TempDir Path dir) throws IOException {
        RealPhysics rp = load(dir, """
            maps: [galaxy1]
            bodies:
              galaxy1:
                terra: {x: 0, y: 0, z: 0, radius: 40, surface-gravity: 9.8}
            """);
        Field f = rp.field("galaxy1");
        assertEquals(-9.8, f.acceleration(0, 40, 0).y(), 1e-9);          // на поверхности — g, к центру
        assertEquals(-9.8 / 4, f.acceleration(0, 80, 0).y(), 1e-9);      // вдвое дальше — вчетверо слабее
        assertEquals(-9.8 / 2, f.acceleration(0, 20, 0).y(), 1e-9);      // внутри однородного шара — линейно
        assertTrue(rp.real("galaxy1"));
    }

    @Test
    void badBodiesAreSkippedWithWarning(@TempDir Path dir) throws IOException {
        RealPhysics rp = load(dir, """
            bodies:
              m:
                ok: {mu: 5}
                nomass: {x: 1}
                noradius: {surface-gravity: 9.8}
                badtype: {type: comet, mu: 1}
                negative: {mu: -3}
                text: {mu: много}
            """);
        assertEquals(List.of("ok"), rp.bodies().get("m").stream().map(Body::name).toList());
        assertEquals(5, rp.warnings().size(), rp.warnings().toString());
    }

    @Test
    void holesJoinTheFieldAndLegacyMapsAreKept(@TempDir Path dir) throws IOException {
        RealPhysics rp = load(dir, """
            maps: [galaxy1, old]
            physics:
              legacy-maps: [old]
              thrust: {acceleration: 3, delta-v: 0}
              push-off: 1.5
              impact: {threshold: 6, damage: 0.5, restitution: 2}
            black-holes:
              galaxy1:
                abyss: {x: 0, y: 0, z: 0, horizon: 3, pull: 0.5}
            """);
        Field f = rp.field("galaxy1");
        assertEquals(1, f.bodies().size());
        assertTrue(f.bodies().get(0).isHole());
        // pull — на расстоянии двух горизонтов от центра
        assertEquals(0.5, f.acceleration(6, 0, 0).length(), 1e-9);
        assertSame(Field.EMPTY, rp.field("nowhere"));
        assertFalse(rp.real("old"));
        assertTrue(rp.real("galaxy1"));
        assertEquals(3, rp.thrust());
        assertEquals(Double.POSITIVE_INFINITY, rp.deltaV());
        assertEquals(1.5, rp.pushOff());
        assertEquals(6, rp.impactThreshold());
        assertEquals(0.5, rp.impactDamage());
        assertEquals(1, rp.restitution());                                 // упругость не больше 1
    }

    @Test
    void defaultsWithoutSections(@TempDir Path dir) throws IOException {
        RealPhysics rp = load(dir, "maps: [galaxy1]\n");
        assertTrue(rp.bodies().isEmpty());
        assertTrue(rp.field("galaxy1").isEmpty());
        assertEquals(2.0, rp.thrust());
        assertEquals(60, rp.deltaV());
        assertEquals(2.5, rp.pushOff());
        assertEquals(8.0, rp.impactThreshold());
        assertTrue(rp.antiCheat());
        assertFalse(rp.speedReadout());
    }

    @Test
    void bundledConfigParses() throws IOException {
        try (InputStream in = GravityModule.class.getResourceAsStream("/config.yml")) {
            Path tmp = Files.createTempFile("gravity", ".yml");
            try {
                Files.write(tmp, in.readAllBytes());
                ModuleConfig c = new ModuleConfig(tmp);
                RealPhysics rp = RealPhysics.from(c, Settings.from(c).space().holes());
                assertTrue(rp.warnings().isEmpty(), rp.warnings().toString());
                assertTrue(rp.real("galaxy1"));
                assertEquals(1, rp.field("galaxy1").bodies().size());          // только дыра abyss
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Test
    void bodiesPacketLayout() throws IOException {
        List<Body> bodies = List.of(Body.planet("p", 1, 2, 3, 400, 10), Body.small("s", -1, 0, 5, 7, 2, 0),
            Body.hole("h", 0, 0, 0, 9, 3));
        byte[] data = SpaceflightLink.bodies(bodies);
        assertEquals(1 + 4 + 3 * 6 * 8, data.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        assertEquals(SpaceflightLink.OP_BODIES, in.readUnsignedByte());
        assertEquals(3, in.readInt());
        double[][] want = {{1, 2, 3, 400, 10, 0}, {-1, 0, 5, 7, 0, 2}, {0, 0, 0, 9, 0, 3}};
        for (double[] w : want) {
            for (double v : w) {
                assertEquals(v, in.readDouble());
            }
        }
    }
}
