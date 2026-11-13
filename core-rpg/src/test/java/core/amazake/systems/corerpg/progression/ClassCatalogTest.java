package core.amazake.systems.corerpg.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.corerpg.aspect.Aspect;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ClassCatalogTest {

    /** Без сервера реестры недоступны: ищем по имени перечисления. */
    private static Material material(final String name) {
        return Material.getMaterial(name.toUpperCase(java.util.Locale.ROOT));
    }

    private static ClassCatalog shipped(final TestYaml.Warnings w) {
        return ClassCatalog.from(TestYaml.resource("classes.yml"), w::add, ClassCatalogTest::material);
    }

    @Test
    void shippedCatalogMatchesContract() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ClassCatalog c = shipped(w);
        assertEquals(List.of(), w.list);
        assertEquals(List.of("swordsman", "archer", "mage", "thief"), List.copyOf(c.classes().keySet()));
        assertEquals(8, c.professions().size());
        assertEquals("Мечник", c.clazz("swordsman").displayName());
        assertEquals("Асассин", c.clazz("thief").displayName());
        assertEquals("Точность", c.clazz("archer").primaryStat());
        assertEquals("Интеллект", c.clazz("mage").primaryStat());
        assertEquals(Material.BLAZE_ROD, c.clazz("mage").icon());
        assertEquals(List.of("crusader", "dark_knight"), c.professionsOf("swordsman").stream().map(ClassCatalog.RpgProfession::key).toList());
        assertEquals(List.of("sniper", "hunter"), c.professionsOf("archer").stream().map(ClassCatalog.RpgProfession::key).toList());
        assertEquals(List.of("warlock", "wizard"), c.professionsOf("mage").stream().map(ClassCatalog.RpgProfession::key).toList());
        assertEquals(List.of("rogue", "assassin"), c.professionsOf("thief").stream().map(ClassCatalog.RpgProfession::key).toList());
        assertEquals("Клирик", c.profession("wizard").displayName());
        assertEquals("Жнец", c.profession("assassin").displayName());
        assertEquals(List.of(Aspect.CRIT_DAMAGE), c.profession("crusader").lockedAspects());
        assertEquals(List.of(Aspect.KNOCKBACK), c.profession("assassin").lockedAspects());
        assertEquals(new Bonus.AspectBonus("resistance:chaos", 0.05), c.clazz("mage").bonuses().get(2));
        assertEquals(new Bonus.EffectBonus("night_vision", 0, 0), c.clazz("mage").bonuses().get(1));
        assertEquals(4, c.clazz("thief").bonuses().size());
    }

    @Test
    void passives() {
        final ClassCatalog c = shipped(new TestYaml.Warnings());
        assertEquals(Set.of("headshot"), c.passives("archer", null));
        assertEquals(Set.of("backstab", "dual_wield"), c.passives("thief", "rogue"));
        assertEquals(Set.of("rage"), c.passives("swordsman", "dark_knight"));
        assertEquals(Set.of(), c.passives(null, null));
        assertEquals(Set.of(), c.passives("нет", "нет"));
    }

    @Test
    void findByRussianAndEnglishNames() {
        final ClassCatalog c = shipped(new TestYaml.Warnings());
        assertEquals("swordsman", c.findClass("мечник").key());
        assertEquals("archer", c.findClass("Стрелок").key());
        assertEquals("thief", c.findClass("ВОР").key());
        assertEquals("thief", c.findClass("thief").key());
        assertNull(c.findClass("паладин"));
        assertNull(c.findClass(""));
        assertEquals("dark_knight", c.findProfession("Темный_рыцарь").key());
        assertEquals("dark_knight", c.findProfession("тёмный рыцарь").key());
        assertEquals("dark_knight", c.findProfession("Берсерк").key());
        assertEquals("crusader", c.findProfession("paladin").key());
        assertEquals("crusader", c.findProfession("крестоносец").key());
        assertEquals("wizard", c.findProfession("волшебник").key());
        assertEquals("wizard", c.findProfession("Клирик").key());
        assertEquals("assassin", c.findProfession("ассасин").key());
        assertEquals("assassin", c.findProfession("жнец").key());
        assertNotNull(c.findProfession("SNIPER"));
        assertNull(c.findProfession("мечник"));
    }

    @Test
    void bonusesOfClassAndProfession() {
        final ClassCatalog c = shipped(new TestYaml.Warnings());
        assertEquals(c.clazz("swordsman").bonuses().size() + c.profession("crusader").bonuses().size(),
            c.bonuses("swordsman", "crusader").size());
        assertEquals(0, c.bonuses(null, null).size());
    }

    @Test
    void badEntriesWarn() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ClassCatalog c = ClassCatalog.from(TestYaml.of("""
            classes:
              "Bad Key":
                display-name: x
              knight:
                display-name: Рыцарь
                description: "Одна строка"
                icon: NOT_A_ITEM
                bonuses: ["aspect:armor:2", "aspect:mana:5"]
                passives: [rage, fly]
              notsection: 5
            professions:
              orphan:
                parent: nobody
              knight:
                parent: knight
              guard:
                parent: knight
                locked-aspects: [regen, mana]
            """), w::add, ClassCatalogTest::material);
        assertEquals(Set.of("knight"), c.classes().keySet());
        assertEquals(Set.of("guard"), c.professions().keySet());
        final ClassCatalog.RpgClass k = c.clazz("knight");
        assertEquals(List.of("Одна строка"), k.description());
        assertEquals(Material.IRON_SWORD, k.icon());
        assertEquals(1, k.bonuses().size());
        assertEquals(Set.of("rage", "fly"), k.passives());
        assertEquals(List.of(Aspect.REGEN), c.profession("guard").lockedAspects());
        assertEquals(Material.IRON_SWORD, c.profession("guard").icon());
        assertTrue(w.any("Bad Key"));
        assertTrue(w.any("NOT_A_ITEM"));
        assertTrue(w.any("mana"));
        assertTrue(w.any("fly"));
        assertTrue(w.any("notsection"));
        assertTrue(w.any("orphan"));
        assertTrue(w.any("professions.knight"));
    }

    @Test
    void noClassesSection() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        final ClassCatalog c = ClassCatalog.from(TestYaml.of("foo: 1\n"), w::add, ClassCatalogTest::material);
        assertTrue(c.classes().isEmpty());
        assertTrue(w.any("classes"));
    }

    @Test
    void names() {
        assertEquals("темный рыцарь", Names.normalize("  Тёмный_Рыцарь "));
        assertEquals("Мечник", Names.stripTags("<red>Мечник</red>"));
        assertEquals("", Names.normalize(null));
    }
}
