package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    private YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new File(ROOT.resolve("src/main/resources/config.yml").toString()));
    }

    @Test
    void bundledImagesMatchDefaults() {
        Settings s = new Settings(bundled());
        assertEquals(Settings.DEFAULT_IMAGES, s.images);
        assertEquals("sky_sea", s.defaultImage);
        for (String img : s.images) {
            assertEquals(Settings.DEFAULT_NAMES.get(img), s.imageName(img));
        }
    }

    @Test
    void everyImageIsInThePack() {
        Path pack = ROOT.resolve("pack/assets/coreshaders");
        for (String img : Settings.DEFAULT_IMAGES) {
            for (String shape : List.of("cube", "plane")) {
                assertTrue(Files.isRegularFile(pack.resolve("items/" + shape + "_" + img + ".json")), shape + "_" + img);
                assertTrue(Files.isRegularFile(pack.resolve("models/item/" + shape + "_" + img + ".json")), shape + "_" + img);
            }
            assertTrue(Files.isRegularFile(pack.resolve("textures/item/panorama/" + img + ".png")), img);
            assertTrue(Files.isRegularFile(pack.resolve("textures/item/panorama/" + img + "_glow.png")), img);
        }
    }

    @Test
    void emptyConfigFallsBackToAllImages() {
        Settings s = new Settings(new YamlConfiguration());
        assertEquals(Settings.DEFAULT_IMAGES, s.images);
        assertEquals("Закат", s.imageName("sunset"));
        assertEquals("Море и песок", s.imageName("sea_sand"));
    }

    @Test
    void oldConfigKeepsItsList() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("images", List.of("sky_sea", "end"));
        Settings s = new Settings(c);
        assertEquals(List.of("sky_sea", "end"), s.images);
        assertEquals("Вулкан", s.imageName("volcano"));
    }

    @Test
    void upgradeAddsNewImagesOnce() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("images", List.of("sky_sea", "space", "nether", "aurora", "end"));
        assertEquals(Settings.ADDED_IN_2, Settings.upgrade(c));
        assertEquals(Settings.CONFIG_VERSION, c.getInt("config-version"));
        assertEquals(Settings.DEFAULT_IMAGES, new Settings(c).images);
        c.set("images", List.of("sky_sea", "rain"));
        assertTrue(Settings.upgrade(c).isEmpty());
        assertEquals(List.of("sky_sea", "rain"), new Settings(c).images);
    }

    @Test
    void bundledConfigNeedsNoUpgrade() {
        assertTrue(Settings.upgrade(bundled()).isEmpty());
    }
}
