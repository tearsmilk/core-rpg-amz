package core.amazake.systems.corerpg.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Таблица: на уровень N нужно N × 100 опыта; максимум 71. */
    private static final Placeholders.Source SOURCE = new Placeholders.Source() {
        @Override
        public int maxLevel() {
            return 71;
        }

        @Override
        public int professionLevel() {
            return 21;
        }

        @Override
        public long expToNext(final PlayerProfile profile) {
            return profile.level() >= 71 ? 0L : profile.level() * 100L;
        }

        @Override
        public String className(final String classId) {
            return Map.of("swordsman", "Мечник", "thief", "Асассин").getOrDefault(String.valueOf(classId), "");
        }

        @Override
        public String professionName(final String professionId) {
            return Map.of("dark_knight", "Берсерк").getOrDefault(String.valueOf(professionId), "");
        }

        @Override
        public String primaryStatName(final String classId) {
            return classId == null ? "Главный параметр" : "Сила";
        }
    };

    private static final PapiSettings S = PapiSettings.DEFAULTS;

    private static final PlayerProfile HERO = new PlayerProfile(ID, "swordsman", "dark_knight", 30, 750L, 3, 10, 5, 7, 2);
    private static final PlayerProfile NEWBIE = PlayerProfile.fresh(ID);

    private static final AspectSnapshot ASPECTS = AspectSnapshot.builder()
        .add(Aspect.CRIT_CHANCE, 12.345)
        .add(Aspect.EVASION, 4)
        .add(Aspect.STAT_PRIMARY, 2)
        .add("resistance:fire", 0.05)
        .build();

    private static final HandSnapshot HAND = new HandSnapshot(7, 10, 56.1, "Агрессии", "Фиолетовая");

    private static String r(final String params, final PlayerProfile profile) {
        return Placeholders.resolve(params, new Placeholders.View(profile, ASPECTS, HAND), SOURCE, S);
    }

    @Test
    void profileBasics() {
        assertEquals("Мечник", r("class", HERO));
        assertEquals("swordsman", r("class_key", HERO));
        assertEquals("Берсерк", r("profession", HERO));
        assertEquals("dark_knight", r("profession_key", HERO));
        assertEquals("30", r("level", HERO));
        assertEquals("71", r("max_level", HERO));
        assertEquals("750", r("exp", HERO));
        assertEquals("3000", r("exp_needed", HERO));
        assertEquals("2250", r("exp_left", HERO));
        assertEquals("25.0", r("exp_progress", HERO));
        assertEquals("3", r("points", HERO));
        assertEquals("10", r("stat_primary", HERO));
        assertEquals("5", r("stat_agility", HERO));
        assertEquals("7", r("stat_stamina", HERO));
        assertEquals("2", r("stat_luck", HERO));
        assertEquals("12", r("stat_primary_total", HERO));
        assertEquals("Сила", r("stat_primary_name", HERO));
        assertEquals("Ловкость", r("stat_agility_name", HERO));
        assertEquals("true", r("has_class", HERO));
        assertEquals("true", r("has_profession", HERO));
        assertEquals("false", r("needs_profession", HERO));
    }

    @Test
    void caseInsensitive() {
        assertEquals("30", r("LEVEL", HERO));
        assertEquals("4", r("Aspect_Evasion", HERO));
    }

    @Test
    void withoutClass() {
        assertEquals("Нет", r("class", NEWBIE));
        assertEquals("none", r("class_key", NEWBIE));
        assertEquals("Нет", r("profession", NEWBIE));
        assertEquals("none", r("profession_key", NEWBIE));
        assertEquals("false", r("has_class", NEWBIE));
        assertEquals("Главный параметр", r("stat_primary_name", NEWBIE));
    }

    @Test
    void unknownClassKeyFallsBackToKey() {
        final PlayerProfile p = NEWBIE.withClass("paladin_old");
        assertEquals("paladin_old", r("class", p));
    }

    @Test
    void needsProfession() {
        final PlayerProfile at20 = NEWBIE.withClass("swordsman").withLevel(20, 0);
        final PlayerProfile at21 = NEWBIE.withClass("swordsman").withLevel(21, 0);
        assertEquals("false", r("needs_profession", at20));
        assertEquals("true", r("needs_profession", at21));
        assertFalse(Placeholders.needsProfession(NEWBIE.withLevel(40, 0), 21), "без класса профессия не нужна");
        assertTrue(Placeholders.needsProfession(at21, 21));
    }

    @Test
    void aspects() {
        assertEquals("12.35", r("aspect_crit_chance", HERO));
        assertEquals("12.35", r("crit_chance", HERO));
        assertEquals("4", r("aspect_evasion", HERO));
        assertEquals("0", r("aspect_lifesteal", HERO));
        assertEquals("0.05", r("aspect_resistance:fire", HERO));
        assertEquals("0", r("aspect_damage_vs:monster", HERO));
        assertNull(r("aspect_nonsense", HERO));
        assertNull(r("aspect_resistance:", HERO));
    }

    @Test
    void hand() {
        assertEquals("7", r("sharpen", HERO));
        assertEquals("10", r("sharpen_max", HERO));
        assertEquals("56.1", r("sharpen_bonus", HERO));
        assertEquals("+7", r("sharpen_text", HERO));
        assertEquals("Агрессии", r("seal", HERO));
        assertEquals("Фиолетовая", r("seal_color", HERO));
        final Placeholders.View empty = new Placeholders.View(HERO, ASPECTS, null);
        assertEquals("0", Placeholders.resolve("sharpen", empty, SOURCE, S));
        assertEquals("", Placeholders.resolve("sharpen_text", empty, SOURCE, S));
        assertEquals("", Placeholders.resolve("seal", empty, SOURCE, S));
        final PapiSettings custom = new PapiSettings("Нет", "none", "—", "", 1, true);
        assertEquals("—", Placeholders.resolve("seal_color", empty, SOURCE, custom));
    }

    @Test
    void notLoadedIsEmptyButUnknownIsNull() {
        final Placeholders.View none = new Placeholders.View(null, null, null);
        assertEquals("", Placeholders.resolve("level", none, SOURCE, S));
        assertEquals("", Placeholders.resolve("aspect_evasion", none, SOURCE, S));
        assertNull(Placeholders.resolve("whatever", none, SOURCE, S));
        assertEquals("", Placeholders.resolve("level", null, SOURCE, S));
        assertNull(Placeholders.resolve(null, none, SOURCE, S));
    }

    @Test
    void legacyNames() {
        final PapiSettings on = new PapiSettings("Нет", "none", "", "", 1, true);
        final Placeholders.View view = new Placeholders.View(HERO, ASPECTS, HAND);
        assertEquals("3", Placeholders.resolve("stats_available", view, SOURCE, on));
        assertEquals("10", Placeholders.resolve("stats_primary", view, SOURCE, on));
        assertEquals("Сила", Placeholders.resolve("stats_primary_name", view, SOURCE, on));
        assertNull(Placeholders.resolve("stats_available", view, SOURCE, S));
    }

    @Test
    void progressFormula() {
        assertEquals("0.0", Placeholders.progress(0, 100, 1));
        assertEquals("33.3", Placeholders.progress(1, 3, 1));
        assertEquals("99.9", Placeholders.progress(9999, 10000, 1), "не округляется до 100 раньше времени");
        assertEquals("100.0", Placeholders.progress(5, 0, 1), "на максимуме — 100");
        assertEquals("100.0", Placeholders.progress(500, 100, 1), "не больше 100");
        assertEquals("0.0", Placeholders.progress(-5, 100, 1), "не меньше 0");
        assertEquals("33", Placeholders.progress(1, 3, 0));
        assertEquals("66.666", Placeholders.progress(2, 3, 3));
        assertEquals("100", Placeholders.progress(0, 0, 0));
        assertEquals("100.0", r("exp_progress", HERO.withLevel(71, 0)));
    }

    @Test
    void normalize() {
        assertEquals("level", Placeholders.normalize("  Level ", S));
        assertNull(Placeholders.normalize("stats_available", S));
        assertEquals("points", Placeholders.normalize("stats_available", new PapiSettings("Нет", "none", "", "", 1, true)));
        assertNull(Placeholders.normalize("", S));
        for (final Aspect a : Aspect.values()) {
            assertEquals("aspect_" + a.key(), Placeholders.normalize("aspect_" + a.key(), S));
        }
    }

    @Test
    void bridgeResourcePath() {
        assertEquals("papi-bridge/core/amazake/systems/corerpg/papibridge/CoreRpgExpansion.class.bin",
            BridgeLoader.resourcePath(BridgeLoader.BRIDGE_CLASS));
    }
}
