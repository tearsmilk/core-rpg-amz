package dev.tearsmilk.worldshader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackInstallerTest {

    private static Path root() {
        return Path.of(System.getProperty("worldshader.root", "."));
    }

    private static Map<String, byte[]> own() throws IOException {
        Path base = root().resolve("pack/assets");
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(base)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                out.put(base.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return out;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void packFilesAreComplete() throws IOException {
        Map<String, byte[]> own = own();
        for (String f : PackInstaller.GROUP) {
            assertTrue(own.containsKey(f), f);
        }
        for (String f : List.of("worldshader/items/marker.json", "worldshader/models/item/marker.json",
                "worldshader/textures/item/marker.png", "worldshader/shaders/post/ws_distort.fsh",
                "worldshader/shaders/include/ws_data.glsl")) {
            assertTrue(own.containsKey(f), f);
        }
        for (String f : own.keySet()) {
            assertTrue(f.startsWith("worldshader/") || PackInstaller.GROUP.contains(f), "unexpected shared file " + f);
        }
    }

    @Test
    void sharedShadersCarryVersionAndIntactHash() throws IOException {
        Map<String, byte[]> own = own();
        for (String f : PackInstaller.PROGRAM) {
            String text = new String(own.get(f), StandardCharsets.UTF_8);
            assertTrue(text.contains(PackInstaller.MARK + PackInstaller.VERSION + "\n"), f);
            assertNotNull(PackInstaller.intactHash(own.get(f)), f + ": run python3 -I tools/pack_hash.py --write");
        }
        int[] chain = PackInstaller.classify(PackInstaller.CHAIN, own.get(PackInstaller.CHAIN));
        assertEquals(PackInstaller.OURS, chain[0]);
    }

    @Test
    void freshInstallThenNothingToDo(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("assets");
        Path work = tmp.resolve("work");
        Map<String, byte[]> own = own();
        PackInstaller.Report r = PackInstaller.install(dir, own, work);
        assertEquals(own.size(), r.written().size());
        assertTrue(r.active());
        assertTrue(r.backups().isEmpty());
        for (Map.Entry<String, byte[]> e : own.entrySet()) {
            assertArrayEquals(e.getValue(), Files.readAllBytes(dir.resolve(e.getKey())));
        }
        PackInstaller.Report again = PackInstaller.install(dir, own, work);
        assertFalse(again.changed());
        assertTrue(again.summary().contains("все файлы на месте"));
        try (Stream<Path> s = Files.walk(tmp)) {
            assertTrue(s.noneMatch(p -> p.toString().endsWith(".shaderhooks.tmp")));
        }
    }

    @Test
    void foreignOutlineIsNotReplaced(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("assets");
        Path f = dir.resolve(PackInstaller.PROGRAM.get(0));
        Files.createDirectories(f.getParent());
        byte[] foreign = bytes("#version 330\nvoid main() {}\n");
        Files.write(f, foreign);
        PackInstaller.Report r = PackInstaller.install(dir, own(), tmp.resolve("work"));
        assertEquals(List.of(PackInstaller.PROGRAM.get(0)), r.foreign());
        assertFalse(r.active());
        assertArrayEquals(foreign, Files.readAllBytes(f));
        assertFalse(Files.exists(dir.resolve(PackInstaller.CHAIN)));
        assertTrue(Files.exists(dir.resolve("worldshader/shaders/post/ws_distort.fsh")));
        assertTrue(r.summary().contains("ЧУЖИЕ"));
    }

    @Test
    void foreignChainIsNotReplaced(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("assets");
        Path f = dir.resolve(PackInstaller.CHAIN);
        Files.createDirectories(f.getParent());
        byte[] foreign = bytes("{\"targets\": {}, \"passes\": []}\n");
        Files.write(f, foreign);
        PackInstaller.Report r = PackInstaller.install(dir, own(), tmp.resolve("work"));
        assertEquals(List.of(PackInstaller.CHAIN), r.foreign());
        assertArrayEquals(foreign, Files.readAllBytes(f));
    }

    @Test
    void newerVersionIsKept(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("assets");
        Path f = dir.resolve(PackInstaller.PROGRAM.get(1));
        Files.createDirectories(f.getParent());
        byte[] newer = bytes("#version 330\n" + PackInstaller.MARK + (PackInstaller.VERSION + 1) + "\nvoid main() {}\n");
        Files.write(f, newer);
        PackInstaller.Report r = PackInstaller.install(dir, own(), tmp.resolve("work"));
        assertEquals(PackInstaller.GROUP, r.kept());
        assertArrayEquals(newer, Files.readAllBytes(f));
    }

    @Test
    void otherBuildOfSameVersionIsAConflict(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("assets");
        Path f = dir.resolve(PackInstaller.PROGRAM.get(0));
        Files.createDirectories(f.getParent());
        String body = "#version 330\n" + PackInstaller.MARK + PackInstaller.VERSION + "\n" + PackInstaller.HASH + "0\nvoid main() {}\n";
        String h = PackInstaller.contentHash(body);
        byte[] other = bytes(body.replace(PackInstaller.HASH + "0\n", PackInstaller.HASH + h + "\n"));
        Files.write(f, other);
        PackInstaller.Report r = PackInstaller.install(dir, own(), tmp.resolve("work"));
        assertEquals(List.of(PackInstaller.PROGRAM.get(0)), r.conflict());
        assertTrue(r.summary().contains("КОНФЛИКТ"));
        assertArrayEquals(other, Files.readAllBytes(f));
    }

    @Test
    void editedOwnFileIsReplacedWithBackup(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("assets");
        Path work = tmp.resolve("work");
        Map<String, byte[]> own = own();
        PackInstaller.install(dir, own, work);
        Path f = dir.resolve(PackInstaller.PROGRAM.get(0));
        byte[] edited = bytes(new String(own.get(PackInstaller.PROGRAM.get(0)), StandardCharsets.UTF_8) + "\n");
        Files.write(f, edited);
        Path chain = dir.resolve(PackInstaller.CHAIN);
        byte[] oldChain = bytes("{\"passes\": [{\"fragment_shader\": \"worldshader:post/old\"}]}\n");
        Files.write(chain, oldChain);
        PackInstaller.Report r = PackInstaller.install(dir, own, work);
        assertEquals(List.of(PackInstaller.PROGRAM.get(0), PackInstaller.CHAIN), r.written());
        assertEquals(r.written(), r.backups());
        assertArrayEquals(edited, Files.readAllBytes(r.backupDir().resolve(PackInstaller.PROGRAM.get(0))));
        assertArrayEquals(oldChain, Files.readAllBytes(r.backupDir().resolve(PackInstaller.CHAIN)));
        assertTrue(r.backupDir().startsWith(work.resolve(PackInstaller.BACKUP_DIR)));
        assertArrayEquals(own.get(PackInstaller.CHAIN), Files.readAllBytes(chain));
    }

    @Test
    void lockIsTheSharedShaderhooksLiteral() {
        assertEquals("dev.tearsmilk.shaderhooks.install", PackInstaller.LOCK);
    }
}
