package core.amazake.systems.corerpg.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

class CombatSettingsTest {

    private static YamlConfiguration yaml(final String text) throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void shippedFileParsesWithoutWarningsAndEqualsDefaults() throws Exception {
        final YamlConfiguration yaml = yaml(Files.readString(Path.of("src/main/resources/combat.yml")));
        final List<String> warnings = new ArrayList<>();
        final CombatSettings s = CombatSettings.from(yaml, warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(CombatSettings.DEFAULTS, s);
    }

    @Test
    void defaultsMatchContract() {
        final CombatSettings s = CombatSettings.DEFAULTS;
        assertEquals(57.0, s.crit().capPercent());
        assertEquals(0.85, s.evasion().cap());
        assertEquals(0.40, s.resistance().cap());
        assertEquals(20.0, s.lifesteal().capPercent());
        assertFalse(s.lifesteal().limitToTargetHealth());
        assertEquals(0.0, s.crit().minDamagePercent());
        assertEquals(20L, s.regen().periodTicks());
        assertEquals(2.0, s.passives().rage().percentPerStep());
        assertEquals(5.0, s.passives().rage().stepPercent());
        assertEquals(40.0, s.passives().rage().maxPercent());
        assertEquals(1.5, s.passives().headshot().multiplier());
        assertEquals(0.3, s.passives().headshot().eyeOffset());
        assertEquals(120.0, s.passives().backstab().angle());
        assertEquals(60.0, s.passives().dualWield().percent());
        assertEquals(7.0, s.passives().dualWield().attack().get(Material.DIAMOND_SWORD));
        assertEquals("wither", s.statuses().bleed().effect());
        assertEquals("poison", s.statuses().poison().effect());
        assertEquals(60, s.statuses().burn().durationTicks());
        assertEquals(List.of("fire", "air", "earth", "water"), s.damageVs().elements());
        assertTrue(s.armorSharpen().ignoreCauses().contains(DamageCause.WORLD_BORDER));
        assertTrue(s.armorSharpen().ignoreCauses().contains(DamageCause.VOID));
        assertFalse(s.armorSharpen().ignoreCauses().contains(DamageCause.FALL));
        assertEquals(10, s.resistance().targets().size());
        final CombatSettings.ResistanceTarget fire = s.resistance().targets().get(0);
        assertEquals("fire", fire.id());
        assertTrue(fire.causes().contains(DamageCause.CAMPFIRE));
        assertTrue(fire.attackers().contains(EntityType.MAGMA_CUBE));
        assertEquals(Particle.CRIT, s.crit().particle());
    }

    @Test
    void badValuesFallBackWithWarnings() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final CombatSettings s = CombatSettings.from(yaml("""
            crit:
              cap-percent: 150
              particle: NOT_A_PARTICLE
              sound:
                key: "Bad Sound!"
                pitch: 9
            evasion:
              cap: abc
              causes: [ENTITY_ATTACK, NOPE]
            resistance:
              cap: -1
              targets:
                fire:
                  causes: [LAVA, WRONG]
                  attackers: [BLAZE, DRAGONFLY, "#undead", "#elves"]
                "bad id!":
                  causes: [FALL]
                empty: {}
            passives:
              rage:
                enabled: maybe
                step-percent: 0
              dual_wield:
                attack:
                  DIAMOND_SWORD: 11
                  MAGIC_STICK: 3
                  IRON_AXE: lots
            statuses:
              bleed:
                effect: "Иссушение"
                duration-ticks: 0
            regen:
              period-ticks: 0
            lifesteal:
              cap-percent: 300
            damage-vs:
              elements: [fire, "Огонь"]
            """), warnings::add);

        assertEquals(57.0, s.crit().capPercent());
        assertEquals(Particle.CRIT, s.crit().particle());
        assertEquals("entity.player.attack.crit", s.crit().sound().key());
        assertEquals(1.0f, s.crit().sound().pitch());
        assertEquals(0.85, s.evasion().cap());
        assertEquals(java.util.Set.of(DamageCause.ENTITY_ATTACK), s.evasion().causes());
        assertEquals(0.40, s.resistance().cap());
        assertEquals(2, s.resistance().targets().size());
        final CombatSettings.ResistanceTarget fire = s.resistance().targets().get(0);
        assertEquals(java.util.Set.of(DamageCause.LAVA), fire.causes());
        assertEquals(java.util.Set.of(EntityType.BLAZE), fire.attackers());
        assertEquals(java.util.Set.of(AttackerGroup.UNDEAD), fire.groups());
        assertTrue(s.passives().rage().enabled());
        assertEquals(5.0, s.passives().rage().stepPercent());
        assertEquals(java.util.Map.of(Material.DIAMOND_SWORD, 11.0), s.passives().dualWield().attack());
        assertEquals("wither", s.statuses().bleed().effect());
        assertEquals(60, s.statuses().bleed().durationTicks());
        assertEquals(20L, s.regen().periodTicks());
        assertEquals(20.0, s.lifesteal().capPercent());
        assertFalse(s.lifesteal().limitToTargetHealth());
        assertEquals(0.0, s.crit().minDamagePercent());
        assertEquals(List.of("fire"), s.damageVs().elements());

        final String all = String.join("\n", warnings);
        for (final String path : List.of("crit.cap-percent", "crit.particle", "crit.sound.key", "crit.sound.pitch",
            "evasion.cap", "evasion.causes", "resistance.cap", "resistance.targets.fire.causes",
            "resistance.targets.fire.attackers", "resistance.targets.bad id!", "resistance.targets.empty",
            "passives.rage.enabled", "passives.rage.step-percent", "passives.dual_wield.attack.MAGIC_STICK",
            "passives.dual_wield.attack.IRON_AXE", "statuses.bleed.effect", "statuses.bleed.duration-ticks",
            "regen.period-ticks", "lifesteal.cap-percent", "damage-vs.elements")) {
            assertTrue(all.contains(path), "нет предупреждения для " + path + ":\n" + all);
        }
    }

    @Test
    void emptyStringsDisableSoundsAndParticles() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final CombatSettings s = CombatSettings.from(yaml("""
            crit:
              particle: none
              sound: ""
              message: ""
            evasion:
              sound:
                key: ""
            """), warnings::add);
        assertEquals(List.of(), warnings);
        assertNull(s.crit().particle());
        assertFalse(s.crit().sound().enabled());
        assertFalse(s.evasion().sound().enabled());
        assertEquals("", s.crit().message());
    }

    @Test
    void russianBooleansAndCommaNumbers() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final CombatSettings s = CombatSettings.from(yaml("""
            crit:
              enabled: нет
            evasion:
              cap: "0,5"
            """), warnings::add);
        assertEquals(List.of(), warnings);
        assertFalse(s.crit().enabled());
        assertEquals(0.5, s.evasion().cap());
    }

    @Test
    void nullRootGivesDefaults() {
        assertEquals(CombatSettings.DEFAULTS, CombatSettings.from(null, w -> { }));
    }

    @Test
    void attackerGroupParsing() {
        assertEquals(AttackerGroup.UNDEAD, AttackerGroup.parse("#undead"));
        assertEquals(AttackerGroup.ANIMAL, AttackerGroup.parse("#ANIMAL"));
        assertNull(AttackerGroup.parse("undead"));
        assertNull(AttackerGroup.parse("#elves"));
        assertNull(AttackerGroup.parse(null));
    }

    @Test
    void scalarSectionIsTheEnabledFlag() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final CombatSettings s = CombatSettings.from(yaml("""
            crit: false
            regen: off
            statuses:
              bleed: false
              poison: maybe
            passives:
              rage: false
            """), warnings::add);
        assertFalse(s.crit().enabled());
        assertFalse(s.regen().enabled());
        assertFalse(s.statuses().bleed().enabled());
        assertEquals("wither", s.statuses().bleed().effect());
        assertTrue(s.statuses().poison().enabled());
        assertFalse(s.passives().rage().enabled());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("statuses.poison"));
    }
}
