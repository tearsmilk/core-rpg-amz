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
    void awpAndGlockFromTacz() {
        Map<?, ?> c = TestGuns.config();
        GunDef awp = GunDef.read("ai_awp", GunDef.Cfg.of(c));
        assertEquals(5, awp.magazine());
        assertEquals(6, awp.capacity(1));
        assertEquals(171, awp.rpm());
        assertEquals(0.9, awp.boltTime());
        assertEquals(60000.0 / 171 + 900, awp.cycleMs(), 1e-9);
        assertTrue(awp.scope());
        assertEquals(8, awp.zoom());
        assertEquals(50, awp.damageAt(10));
        assertEquals(40, awp.damageAt(500));
        assertEquals(2, awp.headshot());
        assertEquals(4, awp.pierce());
        assertEquals(5, awp.spreadStand());
        assertEquals(0.05, awp.spreadAim());
        assertTrue(!awp.auto() && awp.semi());
        assertEquals("sniper", awp.type());
        assertEquals("", awp.itemIcon());
        assertEquals("tacz/ai_awp_lod.bbmodel", awp.itemModel());
        assertEquals("tacz/338.png", awp.ammoIcon());
        assertEquals("awp_rechamber_out", awp.boltSounds3p().get(0.17));
        assertEquals("338", awp.caliber());

        GunDef glock = GunDef.read("glock_17", GunDef.Cfg.of(c));
        assertTrue(glock.pistol());
        assertEquals(17, glock.magazine());
        assertEquals(400, glock.rpm());
        assertEquals(0, glock.boltTime());
        assertEquals(150, glock.intervalMs(), 1e-9);
        assertEquals(7, glock.damageAt(0));
        assertEquals("guns/pistol_default.player_animation.json", glock.playerAnimation());
        assertEquals("tacz/glock_17_slot.png", glock.itemIcon());
        assertEquals("", glock.sound("put_away"));
        assertEquals("9mm", glock.caliber());
        assertTrue(!TestGuns.ak().pistol());
    }

    @Test
    void defaultsWithoutConfig() {
        GunDef g = GunDef.read("ak47", GunDef.Cfg.of(Map.of()));
        assertEquals(30, g.magazine());
        assertEquals(6, g.damageAt(Double.MAX_VALUE));
        assertEquals("guns/ak47.bbmodel", g.model());
    }
}
