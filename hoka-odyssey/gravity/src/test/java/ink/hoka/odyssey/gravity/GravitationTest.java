package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.api.ModuleConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GravitationTest {
    static final BlackHole HOLE = new BlackHole("abyss", 0, 65, -40, 6, 100, 4, 12);
    static final double T2 = Settings.TPS * Settings.TPS;

    /** Как в config.yml по умолчанию, но с дырой в 40 блоках и дрейфом. */
    static Settings settings(boolean spawn) {
        Settings.Space sp = new Settings.Space(Map.of("galaxy1", List.of(HOLE)), 1, 0.05, Settings.Space.DEFAULT_HOLE,
            new double[] {0, 0, -1}, 0.3, 0.3, 0.5, 1.5, spawn, true, Motion.keepPerTick(30), 2, true);
        Settings b = MotionTest.S;
        return new Settings(b.maps(), b.vThrust(), b.vMax(), b.vKeep(), b.hThrust(), b.hMax(), b.hKeep(), false, false, "", sp);
    }

    @Test
    void pullWeakensWithDistanceButNotBelowMinimum() {
        assertEquals(4, Gravitation.accel(HOLE, 3, 1, 0.05), 1e-12, "внутри горизонта — как у края");
        assertEquals(4, Gravitation.accel(HOLE, 6, 1, 0.05), 1e-12, "у края горизонта — pull");
        assertEquals(2, Gravitation.accel(HOLE, 12, 1, 0.05), 1e-12, "вдвое дальше — вдвое слабее (falloff 1)");
        assertEquals(1, Gravitation.accel(HOLE, 12, 2, 0.05), 1e-12, "falloff 2 — вчетверо");
        assertEquals(0.24, Gravitation.accel(HOLE, 100, 1, 0.05), 1e-12);
        assertEquals(0.05, Gravitation.accel(HOLE, 90, 3, 0.05), 1e-12, "не меньше min-pull в пределах reach");
        assertEquals(0, Gravitation.accel(HOLE, 100.01, 1, 0.05), "за reach — 0");
        double prev = Double.MAX_VALUE;
        for (double d = 6; d <= 100; d += 0.5) {
            double a = Gravitation.accel(HOLE, d, 1, 0.05);
            assertTrue(a <= prev && a > 0, "плавно слабеет");
            prev = a;
        }
    }

    @Test
    void nearestHoleWithinReach() {
        BlackHole far = new BlackHole("far", 0, 65, 500, 6, 1000, 4, 12);
        List<BlackHole> holes = List.of(far, HOLE);
        assertSame(HOLE, Gravitation.nearest(holes, 0, 65, 0), "ближайшая из тех, что дотягиваются");
        assertSame(far, Gravitation.nearest(holes, 0, 65, 300));
        assertSame(far, Gravitation.nearest(holes, 0, 65, 200), "HOLE не дотягивается (reach 100) — дальняя");
        assertNull(Gravitation.nearest(List.of(HOLE), 0, 65, 200), "ни одна не дотягивается");
        assertNull(Gravitation.nearest(List.of(), 0, 0, 0));
    }

    @Test
    void stepAcceleratesToCapAndNotBeyond() {
        Gravitation.Force f = new Gravitation.Force(0, 0, -1, 0.001, 0.05);
        double[] keep = {0.95, 0.9, 0.95};
        double[] v = {0, 0, 0};
        for (int i = 0; i < 1000; i++) {
            double[] n = Gravitation.step(v, f, keep, 0);
            assertTrue(-n[2] >= -v[2] - 1e-12, "к дыре скорость не падает");
            assertTrue(-n[2] - -v[2] <= 0.001 + 1e-12, "разгон не больше a за шаг (без рывков)");
            v = n;
            assertTrue(-v[2] <= 0.05 + 1e-12, "не быстрее предела");
        }
        assertEquals(-0.05, v[2], 1e-12);
    }

    @Test
    void stepDampsSidewaysAndAwayMotion() {
        Gravitation.Force f = new Gravitation.Force(0, 0, -1, 0.001, 0.05);
        double[] keep = {0.95, 0.9, 0.95};
        double[] v = {0.3, 0.2, 0.4};                     // вбок, вверх и от дыры
        for (int i = 0; i < 400; i++) {
            v = Gravitation.step(v, f, keep, 0);
        }
        assertEquals(0, v[0], 1e-6, "поперёк — погасло");
        assertEquals(0, v[1], 1e-6);
        assertEquals(-0.05, v[2], 1e-9, "развернуло к дыре");
        double[] fast = Gravitation.step(new double[] {0, 0, -0.5}, f, keep, 0);
        assertTrue(fast[2] > -0.5 && fast[2] <= -0.05, "быстрее предела — тормозит к пределу");
    }

    @Test
    void stepStartsAboveClientThreshold() {
        Gravitation.Force f = new Gravitation.Force(1, 0, 0, 1e-5, 0.015);
        double[] v = Gravitation.step(new double[] {0, 0, 0}, f, new double[] {0.95, 0.95, 0.95}, Floater.START_SPEED);
        assertEquals(Floater.START_SPEED, v[0], 1e-12, "клиент не обнулит");
        Gravitation.Force tiny = new Gravitation.Force(1, 0, 0, 1e-5, 0.001);
        assertEquals(0.001, Gravitation.step(new double[] {0, 0, 0}, tiny, new double[] {1, 1, 1}, Floater.START_SPEED)[0], 1e-12,
            "но не быстрее предела");
    }

    @Test
    void playerForceTowardHoleOrDefaultDrift() {
        Settings.Space sp = settings(false).space();
        Gravitation.Force f = force(sp, 0, 65, 0);
        assertNotNull(f);
        assertEquals(-1, f.nz(), 1e-9, "к дыре на севере");
        assertEquals(Gravitation.accel(HOLE, 40, 1, 0.05) * 0.5 / T2, f.a(), 1e-12, "притяжение × player-pull-scale");
        assertEquals(1.5 / Settings.TPS, f.cap(), 1e-12, "предел — player-max-drift-speed");

        Gravitation.Force out = force(sp, 0, 65, 200);       // дыра не дотягивается — обычный дрейф
        assertNotNull(out);
        assertEquals(0.3 / Settings.TPS, out.cap(), 1e-12);
        assertEquals(-1, out.nz(), 1e-12);

        Settings.Space noDrift = new Settings.Space(sp.holes(), 1, 0.05, sp.holeDefaults(), sp.driftDir(), 0, 0.3, 0.5, 1.5,
            false, true, 1, 2, true);
        assertNull(force(noDrift, 0, 65, 200), "speed 0 — без дрейфа");
    }

    @Test
    void playerIsHeldAtHorizonNotPulledIn() {
        Settings.Space sp = settings(false).space();
        Gravitation.Force near = force(sp, 0, 65, -40 + 7);   // в блоке от горизонта
        assertEquals(Gravitation.BRAKE * 1 / Settings.TPS, near.cap(), 1e-12, "тормозит заранее");
        Gravitation.Force inside = force(sp, 0, 65, -40 + 3);
        assertEquals(0, inside.cap(), 1e-12, "внутри горизонта внутрь не тянет");
        Settings.Space spawn = settings(true).space();
        assertEquals(1.5 / Settings.TPS, force(spawn, 0, 65, -40 + 7).cap(), 1e-12, "режим spawn — без торможения");
    }

    @Test
    void driftedPlayerStopsAtHorizon() {
        // клиент без клавиш: v = (v + imp) * drag; сервер ведёт его Floater'ом к дыре — должен остановиться у горизонта
        Settings s = settings(false);
        Floater fl = new Floater();
        double x = 0;
        double y = 65 - 0.9;
        double z = 0;
        double vx = 0;
        double vy = 0;
        double vz = 0;
        double maxSpeed = 0;
        for (int i = 0; i < 20 * 200; i++) {
            Gravitation.Force f = Gravitation.playerForce(s.space().holes("galaxy1"), x, y + 0.9, z, 1, 0.05, 0.5, 1.5, true,
                s.space().driftDir(), 0.3, 0.3);
            Floater.Impulse imp = fl.tick(s, x, y, z, 0, Floater.Keys.NONE, false, false, f);
            if (imp != null) {
                vx += imp.x();
                vy += imp.y();
                vz += imp.z();
            }
            vx = Math.abs(vx) < Motion.CLIENT_NEGLIGIBLE ? 0 : vx;
            vy = Math.abs(vy) < Motion.CLIENT_NEGLIGIBLE ? 0 : vy;
            vz = Math.abs(vz) < Motion.CLIENT_NEGLIGIBLE ? 0 : vz;
            x += vx;
            y += vy;
            z += vz;
            maxSpeed = Math.max(maxSpeed, Math.sqrt(vx * vx + vy * vy + vz * vz) * Settings.TPS);   // сдвиг за тик
            vx *= Motion.CLIENT_AIR_FRICTION;
            vy *= Motion.CLIENT_VERTICAL_DRAG;
            vz *= Motion.CLIENT_AIR_FRICTION;
        }
        double d = HOLE.distance(x, y + 0.9, z);
        assertTrue(maxSpeed <= 1.5 + 1e-9, "не быстрее player-max-drift-speed: " + maxSpeed);
        assertTrue(maxSpeed > 1.0, "разогнался: " + maxSpeed);
        assertTrue(d >= HOLE.horizon() - 0.05 && d < HOLE.horizon() + 1, "остановился у горизонта: " + d);
    }

    @Test
    void driftIsSmoothAndKeysTakeOver() {
        Settings s = settings(false);
        Floater fl = new Floater();
        Gravitation.Force f = new Gravitation.Force(0, 0, -1, 0.3 / T2, 0.3 / Settings.TPS);
        double z = 0;
        double vz = 0;
        double prev = 0;
        double moved = 0;
        for (int i = 0; i < 200; i++) {
            Floater.Impulse imp = fl.tick(s, 0, 100, z, 0, Floater.Keys.NONE, false, false, f);
            vz += imp == null ? 0 : imp.z();
            vz = Math.abs(vz) < Motion.CLIENT_NEGLIGIBLE ? 0 : vz;
            z += vz;
            moved = vz;
            vz *= Motion.CLIENT_AIR_FRICTION;
            assertTrue(Math.abs(moved - prev) <= 0.0045, "без рывков: " + prev + " -> " + moved);
            prev = moved;
        }
        assertEquals(-0.3 / Settings.TPS, moved, 1e-6, "дрейф ровно 0,3 бл/с");
        Floater.Keys sprint = new Floater.Keys(false, false, false, false, false, false, true);
        assertTrue(sprint.any(), "бег — тоже клавиша");
        Gravitation.Force back = new Gravitation.Force(0, 0, 1, 0.01, 0.3);   // дрейф назад — сильный
        Floater.Impulse k = fl.tick(s, 0, 100, z + vz, 0, sprint, false, false, back);
        assertTrue(k == null || k.z() <= 0, "нажал (бег без WASD) — дрейф не толкает, только прежняя инерция: " + k);
        assertNull(fl.tick(s, 0, 100, z, 0, Floater.Keys.NONE, true, false, f), "на земле не сносит");
    }

    @Test
    void itemsFloatWithoutGravityAndFallIntoHole() {
        double keep = Motion.keepPerTick(30);
        double[] v = Gravitation.itemVelocity(new double[] {0.1, 0, 0}, null, 0, 0, 0, 1, 0.05, keep, 20);
        assertEquals(0.1 * Math.pow(0.5, 1 / 30.0), v[0], 1e-12, "почти без затухания");
        assertEquals(0, v[1], "без гравитации");

        double x = 0;
        double y = 65;
        double z = 0;
        v = new double[] {0, 0, 0};
        int t = 0;
        double max = 0;
        while (HOLE.distance(x, y, z) > HOLE.horizon() && t < 20 * 120) {
            v = Gravitation.itemVelocity(v, Gravitation.nearest(List.of(HOLE), x, y, z), x, y, z, 1, 0.05, keep, 2);
            x += v[0] * 2;
            y += v[1] * 2;
            z += v[2] * 2;
            t += 2;
            max = Math.max(max, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]));
        }
        assertTrue(t < 20 * 120, "долетел до горизонта");
        assertTrue(max <= 12.0 / Settings.TPS + 1e-9, "не быстрее max-speed: " + max * Settings.TPS);
        assertEquals(0, x, 1e-9, "летел прямо к дыре");
    }

    @Test
    void settingsFromDefaultConfig(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.yml");
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            Files.copy(in, file);
        }
        Settings s = Settings.from(new ModuleConfig(file));
        assertEquals(Set.of("galaxy1"), s.maps());
        List<BlackHole> holes = s.space().holes("galaxy1");
        assertEquals(List.of(new BlackHole("abyss", 0.5, 65, -600, 6, 1500, 4, 12)), holes);
        assertEquals(0.3, s.space().driftSpeed(), 1e-12);
        assertEquals(-1, s.space().driftDir()[2], 1e-12);
        assertEquals(0.5, s.space().playerScale(), 1e-12);
        assertEquals(1.5, s.space().playerMaxDrift(), 1e-12);
        assertTrue(!s.space().horizonSpawn());
        assertTrue(s.space().items());

        // запись раздела дыр сохраняет остальные комментарии и читается обратно
        String text = Files.readString(file);
        BlackHole added = new BlackHole("core", 10.25, 70, -3.5, 8, 200, 4, 12);
        String out = HoleYaml.replace(text, Map.of("galaxy1", List.of(holes.get(0), added)));
        Files.writeString(file, out);
        Settings s2 = Settings.from(new ModuleConfig(file));
        assertEquals(List.of(holes.get(0), added), s2.space().holes("galaxy1"));
        assertTrue(out.contains("# Значения для /hograv hole add"), "комментарии после раздела целы");
        assertTrue(out.contains("#   horizon    — радиус горизонта"), "комментарии перед разделом целы");
        assertEquals(text.split("\n").length + 1, out.split("\n").length, "добавилась ровно одна строка");
        assertEquals(s.maps(), s2.maps());
        assertEquals(0.05, s2.space().minPull(), 1e-12);

        String empty = HoleYaml.replace(out, Map.of());
        assertTrue(empty.contains("\nblack-holes: {}\n\n# Значения"), empty);
        assertTrue(Settings.from(cfg(dir, empty)).space().holes().isEmpty());
    }

    @Test
    void holeYamlAppendsWhenMissing() {
        String out = HoleYaml.replace("maps: []\n", Map.of("m", List.of(new BlackHole("a", 1, 2, 3, 4, 5, 6, 7))));
        assertEquals("maps: []\nblack-holes:\n  \"m\":\n    \"a\": {x: 1, y: 2, z: 3, horizon: 4, reach: 5, pull: 6, max-speed: 7}\n", out);
        assertEquals("0.13", HoleYaml.num(0.125));
        assertEquals("-600", HoleYaml.num(-600.0));
        assertEquals("0", HoleYaml.num(-0.001));
    }

    private static Gravitation.Force force(Settings.Space sp, double x, double y, double z) {
        return Gravitation.playerForce(sp.holes("galaxy1"), x, y, z, sp.falloff(), sp.minPull(), sp.playerScale(),
            sp.playerMaxDrift(), !sp.horizonSpawn(), sp.driftDir(), sp.driftSpeed(), sp.driftAccel());
    }

    private static ModuleConfig cfg(Path dir, String text) throws IOException {
        Path f = dir.resolve("other.yml");
        Files.writeString(f, text);
        return new ModuleConfig(f);
    }
}
