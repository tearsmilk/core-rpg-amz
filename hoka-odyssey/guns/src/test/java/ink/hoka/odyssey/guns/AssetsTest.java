package ink.hoka.odyssey.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class AssetsTest {
    @TempDir
    Path dir;

    private static Map<?, ?> yaml(Path f) throws Exception {
        return (Map<?, ?>) new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(f));
    }

    @Test
    void insertsKeepingCommentsAndExisting() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, "# мои предметы\nitems:\n    ruby:\n        name: \"Рубин\"  # камень\n        icon: ruby.png\nother: 1\n");
        Map<String, String> e = new LinkedHashMap<>();
        e.put("ruby", "name: x\n");
        e.put("ak47", Assets.itemYaml(TestGuns.ak()));
        Assets.Result r = Assets.addEntries(f, "items", e);
        assertNull(r.manual());
        String text = Files.readString(f);
        assertTrue(text.contains("# мои предметы") && text.contains("# камень"));
        Map<?, ?> items = (Map<?, ?>) yaml(f).get("items");
        assertEquals("Рубин", ((Map<?, ?>) items.get("ruby")).get("name"));
        assertEquals("tacz/ak47_lod.bbmodel", ((Map<?, ?>) items.get("ak47")).get("model"));
        assertEquals(1, yaml(f).get("other"));
        assertTrue(Files.exists(dir.resolve("config.yml.bak-guns")));
        // второй раз — ничего
        assertEquals("все записи уже есть", Assets.addEntries(f, "items", e).message());
    }

    @Test
    void createsSectionAndFile() throws Exception {
        Path f = dir.resolve("sub/config.yml");
        Map<String, String> e = new LinkedHashMap<>();
        for (Assets.SoundEntry s : Assets.SOUNDS) {
            e.put(s.id(), Assets.soundYaml(s));
        }
        Assets.Result r = Assets.addEntries(f, "sounds", e);
        assertNull(r.manual());
        Map<?, ?> sounds = (Map<?, ?>) yaml(f).get("sounds");
        assertEquals(Assets.SOUNDS.size(), sounds.size());
        Map<?, ?> s3p = (Map<?, ?>) sounds.get("ak47_shoot_3p");
        assertEquals(128, s3p.get("range"));
        assertEquals("Выстрел АКМ", s3p.get("subtitle"));
    }

    @Test
    void inlineSectionGivesManualYaml() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, "items: {ruby: {icon: r.png}}\n");
        Assets.Result r = Assets.addEntries(f, "items", Map.of("ak47", "icon: a.png\n"));
        assertNotNull(r.manual());
        assertEquals("items: {ruby: {icon: r.png}}\n", Files.readString(f));
    }

    @Test
    void addsMissingGunsFromJarConfig() throws Exception {
        Path f = dir.resolve("guns.yml");
        Files.writeString(f, "# мой конфиг\npvp: false\nguns:\n  ak47:\n    name: \"Мой АК\"\n    magazine: 40\n");
        Assets.Result r = Assets.addMissingGuns(f);
        assertNull(r.manual(), r.message());
        Map<?, ?> root = yaml(f);
        assertEquals(Boolean.FALSE, root.get("pvp"));
        Map<?, ?> guns = (Map<?, ?>) root.get("guns");
        assertEquals("Мой АК", ((Map<?, ?>) guns.get("ak47")).get("name"));
        assertEquals(40, ((Map<?, ?>) guns.get("ak47")).get("magazine"));
        GunDef awp = GunDef.read("ai_awp", GunDef.Cfg.of(root));
        GunDef jarAwp = GunDef.read("ai_awp", GunDef.Cfg.of(TestGuns.config()));
        assertEquals(jarAwp.toString().replaceAll("\\[[DI]@[0-9a-f]+", ""), awp.toString().replaceAll("\\[[DI]@[0-9a-f]+", ""));
        assertEquals(3, ((Map<?, ?>) GunDef.Cfg.of(root).get("guns.ai_awp.reload-sounds-3p.empty")).size());
        GunDef glock = GunDef.read("glock_17", GunDef.Cfg.of(root));
        assertTrue(glock.pistol());
        assertEquals("все записи уже есть", Assets.addMissingGuns(f).message());
        assertTrue(Files.readString(f).contains("# мой конфиг"));
    }

    @Test
    void itemYamlPerGun() {
        Map<?, ?> c = TestGuns.config();
        GunDef awp = GunDef.read("ai_awp", GunDef.Cfg.of(c));
        assertTrue(!Assets.itemYaml(awp).contains("icon:"));
        assertTrue(Assets.itemYaml(awp).contains("model: tacz/ai_awp_lod.bbmodel"));
        assertTrue(Assets.ammoYaml(awp).contains("icon: tacz/338.png"));
        GunDef glock = GunDef.read("glock_17", GunDef.Cfg.of(c));
        assertTrue(Assets.itemYaml(glock).contains("icon: tacz/glock_17_slot.png"));
    }

    @Test
    void jarHasAssetsAndNotice() {
        assertNotNull(Assets.resource("models/ak47.bbmodel"));
        assertNotNull(Assets.resource(Assets.NOTICE));
        Map<?, ?> c = TestGuns.config();
        for (Object id : ((Map<?, ?>) c.get("guns")).keySet()) {      // всё оружие из config.yml — с файлами в jar
            GunDef g = GunDef.read(String.valueOf(id), GunDef.Cfg.of(c));
            Assets a = new Assets(dir);
            for (String path : new String[] {g.model(), g.hud(), g.flash(), g.playerAnimation()}) {
                assertNotNull(a.read(path), id + ": " + path);
            }
            assertNotNull(Assets.GUNS.get(g.id()), String.valueOf(id));
            for (String own : new String[] {g.itemModel(), g.itemIcon(), g.ammoIcon()}) {
                if (own.isEmpty()) continue;
                String n = own.substring(5);
                assertNotNull(Assets.resource((n.endsWith(".bbmodel") ? "models/" : "textures/") + n), own);
            }
            for (String snd : g.sounds().values()) {
                if (snd.isEmpty()) continue;
                assertTrue(Assets.SOUNDS.stream().anyMatch(e -> e.id().equals(snd)), snd);
            }
        }
        for (String rel : Assets.ITEM_FILES) {
            assertNotNull(Assets.resource(rel), rel);
        }
        for (Assets.SoundEntry s : Assets.SOUNDS) {
            assertNotNull(Assets.resource("sounds/" + s.file()), s.file());
        }
    }
}
