package ink.hoka.odyssey.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GunDefTest {
    @Test
    void readsTaczNumbers() {
        GunDef g = TestGuns.ak();
        assertEquals("АКМ", g.name());
        assertEquals(30, g.magazine());
        assertEquals(31, g.capacity(5));
        assertEquals(30, g.capacity(0));
        assertEquals(600, g.rpm());
        assertEquals(100, g.intervalMs(), 1e-9);
        assertTrue(g.auto() && g.semi());
        assertEquals(9, g.damageAt(0));
        assertEquals(9, g.damageAt(29.9));
        assertEquals(7.5, g.damageAt(30));
        assertEquals(6, g.damageAt(1000));
        assertEquals(1.5, g.headshot());
        assertEquals(500, g.speed());
        assertEquals(0xFF8888, g.tracerColor());
        assertEquals(5, g.recoilPitch().size());
        assertEquals(-0.23, g.recoilYaw().get(0).min(), 1e-9);
        assertEquals(1.55, g.tactical().feed());
        assertEquals(2.6, g.empty().total());
        assertEquals("ak47_shoot_3p", g.sound("shoot3p"));
        assertEquals("ak47_reload_magout", g.reloadSoundsTactical().get(0.35));
        assertEquals(3, g.reloadSoundsEmpty().size());
    }

    @Test
    void defaultsWithoutConfig() {
        GunDef g = GunDef.read("ak47", GunDef.Cfg.of(Map.of()));
        assertEquals(30, g.magazine());
        assertEquals(6, g.damageAt(Double.MAX_VALUE));
        assertEquals("guns/ak47.bbmodel", g.model());
    }
}
