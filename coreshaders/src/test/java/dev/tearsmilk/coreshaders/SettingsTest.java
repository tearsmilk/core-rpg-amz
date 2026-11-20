package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    void upgradeTo4KeepsCustomMessagesAndWritesAnimation() {
        YamlConfiguration c = new YamlConfiguration();
        c.setDefaults(bundled());
        c.set("config-version", 3);
        c.set("messages.upload-usage", Settings.OLD_IN_3.get("messages.upload-usage"));
        c.set("messages.image-not-image", "<red>свой текст</red>");
        List<String> help = new ArrayList<>(List.of("<white>моя строка</white>"));
        help.addAll(Settings.OLD_HELP_IN_3);
        c.set("messages.help", help);
        c.set("custom-images.animation.max-frames", 12);
        List<String> kept = new ArrayList<>();
        Settings.upgrade(c, kept);
        YamlConfiguration d = bundled();
        assertEquals(d.getString("messages.upload-usage"), c.getString("messages.upload-usage"));
        assertEquals("<red>свой текст</red>", c.getString("messages.image-not-image"));
        assertEquals(List.of("messages.image-not-image"), kept);
        List<String> h = c.getStringList("messages.help");
        assertEquals("<white>моя строка</white>", h.get(0));
        assertTrue(h.get(1).contains("png, jpg, gif"));
        assertTrue(h.get(2).contains("GIF"));
        assertTrue(c.isSet("messages.image-anim-budget"));
        assertEquals(12, c.getInt("custom-images.animation.max-frames"));
        assertTrue(c.isSet("custom-images.animation.width"));
        assertTrue(c.isSet("custom-images.animation.max-texture-mb"));
        assertTrue(c.isSet("custom-images.animation.total-texture-mb"));
        assertEquals(Settings.CONFIG_VERSION, c.getInt("config-version"));
    }

    @Test
    void bundledConfigNeedsNoUpgrade() {
        assertTrue(Settings.upgrade(bundled()).isEmpty());
    }
}
